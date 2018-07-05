// Copyright (C) 2009 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.patch;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.common.collect.ImmutableSet;
import com.google.gerrit.common.data.CommentDetail;
import com.google.gerrit.common.data.PatchScript;
import com.google.gerrit.common.data.PatchScript.DisplayMethod;
import com.google.gerrit.extensions.client.DiffPreferencesInfo;
import com.google.gerrit.extensions.client.DiffPreferencesInfo.Whitespace;
import com.google.gerrit.prettify.common.EditList;
import com.google.gerrit.prettify.common.SparseFileContent;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Comment;
import com.google.gerrit.reviewdb.client.Patch;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.server.mime.FileTypeRegistry;
import com.google.inject.Inject;
import eu.medsea.mimeutil.MimeType;
import eu.medsea.mimeutil.MimeUtil2;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.errors.CorruptObjectException;
import org.eclipse.jgit.errors.IncorrectObjectTypeException;
import org.eclipse.jgit.errors.MissingObjectException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.treewalk.TreeWalk;

class PatchScriptBuilder {
  static final int MAX_CONTEXT = 5000000;
  static final int BIG_FILE = 9000;

  private static final Comparator<Edit> EDIT_SORT =
      new Comparator<Edit>() {
        @Override
        public int compare(Edit o1, Edit o2) {
          return o1.getBeginA() - o2.getBeginA();
        }
      };

  private Repository db;
  private Project.NameKey projectKey;
  private ObjectReader reader;
  private Change change;
  private DiffPreferencesInfo diffPrefs;
  private ComparisonType comparisonType;
  private ObjectId aId;
  private ObjectId bId;

  private final Side a;
  private final Side b;

  private List<Edit> edits;
  private final FileTypeRegistry registry;
  private final PatchListCache patchListCache;
  private int context;

  @Inject
  PatchScriptBuilder(FileTypeRegistry ftr, PatchListCache plc) {
    a = new Side();
    b = new Side();
    registry = ftr;
    patchListCache = plc;
  }

  void setRepository(Repository r, Project.NameKey projectKey) {
    this.db = r;
    this.projectKey = projectKey;
  }

  void setChange(Change c) {
    this.change = c;
  }

  void setDiffPrefs(DiffPreferencesInfo dp) {
    diffPrefs = dp;

    context = diffPrefs.context;
    if (context == DiffPreferencesInfo.WHOLE_FILE_CONTEXT) {
      context = MAX_CONTEXT;
    } else if (context > MAX_CONTEXT) {
      context = MAX_CONTEXT;
    }
  }

  void setTrees(ComparisonType ct, ObjectId a, ObjectId b) {
    comparisonType = ct;
    aId = a;
    bId = b;
  }

  PatchScript toPatchScript(PatchListEntry content, CommentDetail comments, List<Patch> history)
      throws IOException {
    reader = db.newObjectReader();
    try {
      return build(content, comments, history);
    } finally {
      reader.close();
    }
  }

  private PatchScript build(PatchListEntry content, CommentDetail comments, List<Patch> history)
      throws IOException {
    boolean intralineDifferenceIsPossible = true;
    boolean intralineFailure = false;
    boolean intralineTimeout = false;

    a.path = oldName(content);
    b.path = newName(content);

    a.resolve(null, aId);
    b.resolve(a, bId);

    edits = new ArrayList<>(content.getEdits());
    ImmutableSet<Edit> editsDueToRebase = content.getEditsDueToRebase();

    if (!isModify(content)) {
      intralineDifferenceIsPossible = false;
    } else if (diffPrefs.intralineDifference) {
      IntraLineDiff d =
          patchListCache.getIntraLineDiff(
              IntraLineDiffKey.create(a.id, b.id, diffPrefs.ignoreWhitespace),
              IntraLineDiffArgs.create(
                  a.src, b.src, edits, editsDueToRebase, projectKey, bId, b.path));
      if (d != null) {
        switch (d.getStatus()) {
          case EDIT_LIST:
            edits = new ArrayList<>(d.getEdits());
            break;

          case DISABLED:
            intralineDifferenceIsPossible = false;
            break;

          case ERROR:
            intralineDifferenceIsPossible = false;
            intralineFailure = true;
            break;

          case TIMEOUT:
            intralineDifferenceIsPossible = false;
            intralineTimeout = true;
            break;
        }
      } else {
        intralineDifferenceIsPossible = false;
        intralineFailure = true;
      }
    }

    correctForDifferencesInNewlineAtEnd();

    if (comments != null) {
      ensureCommentsVisible(comments);
    }

    boolean hugeFile = false;
    if (a.src == b.src && a.size() <= context && content.getEdits().isEmpty()) {
      // Odd special case; the files are identical (100% rename or copy)
      // and the user has asked for context that is larger than the file.
      // Send them the entire file, with an empty edit after the last line.
      //
      for (int i = 0; i < a.size(); i++) {
        a.addLine(i);
      }
      edits = new ArrayList<>(1);
      edits.add(new Edit(a.size(), a.size()));

    } else {
      if (BIG_FILE < Math.max(a.size(), b.size())) {
        // IF the file is really large, we disable things to avoid choking
        // the browser client.
        //
        hugeFile = true;
      }

      // In order to expand the skipped common lines or syntax highlight the
      // file properly we need to give the client the complete file contents.
      // So force our context temporarily to the complete file size.
      //
      context = MAX_CONTEXT;

      packContent(diffPrefs.ignoreWhitespace != Whitespace.IGNORE_NONE);
    }

    return new PatchScript(
        change.getKey(),
        content.getChangeType(),
        content.getOldName(),
        content.getNewName(),
        a.fileMode,
        b.fileMode,
        content.getHeaderLines(),
        diffPrefs,
        a.dst,
        b.dst,
        edits,
        editsDueToRebase,
        a.displayMethod,
        b.displayMethod,
        a.mimeType.toString(),
        b.mimeType.toString(),
        comments,
        history,
        hugeFile,
        intralineDifferenceIsPossible,
        intralineFailure,
        intralineTimeout,
        content.getPatchType() == Patch.PatchType.BINARY,
        aId == null ? null : aId.getName(),
        bId == null ? null : bId.getName());
  }

  private static boolean isModify(PatchListEntry content) {
    switch (content.getChangeType()) {
      case MODIFIED:
      case COPIED:
      case RENAMED:
      case REWRITE:
        return true;

      case ADDED:
      case DELETED:
      default:
        return false;
    }
  }

  private static String oldName(PatchListEntry entry) {
    switch (entry.getChangeType()) {
      case ADDED:
        return null;
      case DELETED:
      case MODIFIED:
      case REWRITE:
        return entry.getNewName();
      case COPIED:
      case RENAMED:
      default:
        return entry.getOldName();
    }
  }

  private static String newName(PatchListEntry entry) {
    switch (entry.getChangeType()) {
      case DELETED:
        return null;
      case ADDED:
      case MODIFIED:
      case COPIED:
      case RENAMED:
      case REWRITE:
      default:
        return entry.getNewName();
    }
  }

  private void correctForDifferencesInNewlineAtEnd() {
    // a.src.size() is the size ignoring a newline at the end whereas a.size() considers it.
    int aSize = a.src.size();
    int bSize = b.src.size();

    Optional<Edit> lastEdit = getLast(edits);
    if (isNewlineAtEndDeleted()) {
      Optional<Edit> lastLineEdit = lastEdit.filter(edit -> edit.getEndA() == aSize);
      if (lastLineEdit.isPresent()) {
        lastLineEdit.get().extendA();
      } else {
        Edit newlineEdit = new Edit(aSize, aSize + 1, bSize, bSize);
        edits.add(newlineEdit);
      }
    } else if (isNewlineAtEndAdded()) {
      Optional<Edit> lastLineEdit = lastEdit.filter(edit -> edit.getEndB() == bSize);
      if (lastLineEdit.isPresent()) {
        lastLineEdit.get().extendB();
      } else {
        Edit newlineEdit = new Edit(aSize, aSize, bSize, bSize + 1);
        edits.add(newlineEdit);
      }
    }
  }

  private static <T> Optional<T> getLast(List<T> list) {
    return list.isEmpty() ? Optional.empty() : Optional.ofNullable(list.get(list.size() - 1));
  }

  private boolean isNewlineAtEndDeleted() {
    return !a.src.isMissingNewlineAtEnd() && b.src.isMissingNewlineAtEnd();
  }

  private boolean isNewlineAtEndAdded() {
    return a.src.isMissingNewlineAtEnd() && !b.src.isMissingNewlineAtEnd();
  }

  private void ensureCommentsVisible(CommentDetail comments) {
    if (comments.getCommentsA().isEmpty() && comments.getCommentsB().isEmpty()) {
      // No comments, no additional dummy edits are required.
      //
      return;
    }

    // Construct empty Edit blocks around each location where a comment is.
    // This will force the later packContent method to include the regions
    // containing comments, potentially combining those regions together if
    // they have overlapping contexts. UI renders will also be able to make
    // correct hunks from this, but because the Edit is empty they will not
    // style it specially.
    //
    final List<Edit> empty = new ArrayList<>();
    int lastLine;

    lastLine = -1;
    for (Comment c : comments.getCommentsA()) {
      final int a = c.lineNbr;
      if (lastLine != a) {
        final int b = mapA2B(a - 1);
        if (0 <= b) {
          safeAdd(empty, new Edit(a - 1, b));
        }
        lastLine = a;
      }
    }

    lastLine = -1;
    for (Comment c : comments.getCommentsB()) {
      int b = c.lineNbr;
      if (lastLine != b) {
        final int a = mapB2A(b - 1);
        if (0 <= a) {
          safeAdd(empty, new Edit(a, b - 1));
        }
        lastLine = b;
      }
    }

    // Sort the final list by the index in A, so packContent can combine
    // them correctly later.
    //
    edits.addAll(empty);
    Collections.sort(edits, EDIT_SORT);
  }

  private void safeAdd(List<Edit> empty, Edit toAdd) {
    final int a = toAdd.getBeginA();
    final int b = toAdd.getBeginB();
    for (Edit e : edits) {
      if (e.getBeginA() <= a && a <= e.getEndA()) {
        return;
      }
      if (e.getBeginB() <= b && b <= e.getEndB()) {
        return;
      }
    }
    empty.add(toAdd);
  }

  private int mapA2B(int a) {
    if (edits.isEmpty()) {
      // Magic special case of an unmodified file.
      //
      return a;
    }

    for (int i = 0; i < edits.size(); i++) {
      final Edit e = edits.get(i);
      if (a < e.getBeginA()) {
        if (i == 0) {
          // Special case of context at start of file.
          //
          return a;
        }
        return e.getBeginB() - (e.getBeginA() - a);
      }
      if (e.getBeginA() <= a && a <= e.getEndA()) {
        return -1;
      }
    }

    final Edit last = edits.get(edits.size() - 1);
    return last.getEndB() + (a - last.getEndA());
  }

  private int mapB2A(int b) {
    if (edits.isEmpty()) {
      // Magic special case of an unmodified file.
      //
      return b;
    }

    for (int i = 0; i < edits.size(); i++) {
      final Edit e = edits.get(i);
      if (b < e.getBeginB()) {
        if (i == 0) {
          // Special case of context at start of file.
          //
          return b;
        }
        return e.getBeginA() - (e.getBeginB() - b);
      }
      if (e.getBeginB() <= b && b <= e.getEndB()) {
        return -1;
      }
    }

    final Edit last = edits.get(edits.size() - 1);
    return last.getEndA() + (b - last.getEndB());
  }

  private void packContent(boolean ignoredWhitespace) {
    EditList list = new EditList(edits, context, a.size(), b.size());
    for (EditList.Hunk hunk : list.getHunks()) {
      while (hunk.next()) {
        if (hunk.isContextLine()) {
          String lineA = a.getSourceLine(hunk.getCurA());
          a.dst.addLine(hunk.getCurA(), lineA);

          if (ignoredWhitespace) {
            // If we ignored whitespace in some form, also get the line
            // from b when it does not exactly match the line from a.
            //
            String lineB = b.getSourceLine(hunk.getCurB());
            if (!lineA.equals(lineB)) {
              b.dst.addLine(hunk.getCurB(), lineB);
            }
          }
          hunk.incBoth();
          continue;
        }

        if (hunk.isDeletedA()) {
          a.addLine(hunk.getCurA());
          hunk.incA();
        }

        if (hunk.isInsertedB()) {
          b.addLine(hunk.getCurB());
          hunk.incB();
        }
      }
    }
  }

  private class Side {
    String path;
    ObjectId id;
    FileMode mode;
    byte[] srcContent;
    Text src;
    MimeType mimeType = MimeUtil2.UNKNOWN_MIME_TYPE;
    DisplayMethod displayMethod = DisplayMethod.DIFF;
    PatchScript.FileMode fileMode = PatchScript.FileMode.FILE;
    final SparseFileContent dst = new SparseFileContent();

    int size() {
      if (src == null) {
        return 0;
      }
      if (src.isMissingNewlineAtEnd()) {
        return src.size();
      }
      return src.size() + 1;
    }

    void addLine(int lineNumber) {
      String lineContent = getSourceLine(lineNumber);
      dst.addLine(lineNumber, lineContent);
    }

    String getSourceLine(int lineNumber) {
      return lineNumber >= src.size() ? "" : src.getString(lineNumber);
    }

    void resolve(Side other, ObjectId within) throws IOException {
      try {
        final boolean reuse;
        if (Patch.COMMIT_MSG.equals(path)) {
          if (comparisonType.isAgainstParentOrAutoMerge()
              && (aId == within || within.equals(aId))) {
            id = ObjectId.zeroId();
            src = Text.EMPTY;
            srcContent = Text.NO_BYTES;
            mode = FileMode.MISSING;
            displayMethod = DisplayMethod.NONE;
          } else {
            id = within;
            src = Text.forCommit(reader, within);
            srcContent = src.getContent();
            if (src == Text.EMPTY) {
              mode = FileMode.MISSING;
              displayMethod = DisplayMethod.NONE;
            } else {
              mode = FileMode.REGULAR_FILE;
            }
          }
          reuse = false;
        } else if (Patch.MERGE_LIST.equals(path)) {
          if (comparisonType.isAgainstParentOrAutoMerge()
              && (aId == within || within.equals(aId))) {
            id = ObjectId.zeroId();
            src = Text.EMPTY;
            srcContent = Text.NO_BYTES;
            mode = FileMode.MISSING;
            displayMethod = DisplayMethod.NONE;
          } else {
            id = within;
            src = Text.forMergeList(comparisonType, reader, within);
            srcContent = src.getContent();
            if (src == Text.EMPTY) {
              mode = FileMode.MISSING;
              displayMethod = DisplayMethod.NONE;
            } else {
              mode = FileMode.REGULAR_FILE;
            }
          }
          reuse = false;
        } else {
          final TreeWalk tw = find(within);

          id = tw != null ? tw.getObjectId(0) : ObjectId.zeroId();
          mode = tw != null ? tw.getFileMode(0) : FileMode.MISSING;
          reuse =
              other != null
                  && other.id.equals(id)
                  && (other.mode == mode || isBothFile(other.mode, mode));

          if (reuse) {
            srcContent = other.srcContent;

          } else if (mode.getObjectType() == Constants.OBJ_BLOB) {
            srcContent = Text.asByteArray(db.open(id, Constants.OBJ_BLOB));

          } else if (mode.getObjectType() == Constants.OBJ_COMMIT) {
            String strContent = "Subproject commit " + ObjectId.toString(id);
            srcContent = strContent.getBytes(UTF_8);

          } else {
            srcContent = Text.NO_BYTES;
          }

          if (reuse) {
            mimeType = other.mimeType;
            displayMethod = other.displayMethod;
            src = other.src;

          } else if (srcContent.length > 0 && FileMode.SYMLINK != mode) {
            mimeType = registry.getMimeType(path, srcContent);
            if ("image".equals(mimeType.getMediaType()) && registry.isSafeInline(mimeType)) {
              displayMethod = DisplayMethod.IMG;
            }
          }
        }

        if (mode == FileMode.MISSING) {
          displayMethod = DisplayMethod.NONE;
        }

        if (!reuse) {
          if (srcContent == Text.NO_BYTES) {
            src = Text.EMPTY;
          } else {
            src = new Text(srcContent);
          }
        }

        dst.setSize(size());

        if (mode == FileMode.SYMLINK) {
          fileMode = PatchScript.FileMode.SYMLINK;
        } else if (mode == FileMode.GITLINK) {
          fileMode = PatchScript.FileMode.GITLINK;
        }
      } catch (IOException err) {
        throw new IOException("Cannot read " + within.name() + ":" + path, err);
      }
    }

    private TreeWalk find(ObjectId within)
        throws MissingObjectException, IncorrectObjectTypeException, CorruptObjectException,
            IOException {
      if (path == null || within == null) {
        return null;
      }
      try (RevWalk rw = new RevWalk(reader)) {
        final RevTree tree = rw.parseTree(within);
        return TreeWalk.forPath(reader, path, tree);
      }
    }
  }

  private static boolean isBothFile(FileMode a, FileMode b) {
    return (a.getBits() & FileMode.TYPE_FILE) == FileMode.TYPE_FILE
        && (b.getBits() & FileMode.TYPE_FILE) == FileMode.TYPE_FILE;
  }
}
