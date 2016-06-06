// Copyright (C) 2010 The Android Open Source Project
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

package com.google.gerrit.server.git;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.gerrit.common.Nullable;
import com.google.gerrit.reviewdb.client.Account;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.client.RefNames;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.IdentifiedUser;
import com.google.gerrit.server.notedb.ChangeNotes;
import com.google.gerrit.server.project.ProjectControl;
import com.google.gerrit.server.query.change.ChangeData;
import com.google.gwtorm.server.OrmException;

import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefDatabase;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.SymbolicRef;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.AbstractAdvertiseRefsHook;
import org.eclipse.jgit.transport.ServiceMayNotContinueException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class VisibleRefFilter extends AbstractAdvertiseRefsHook {
  private static final Logger log =
      LoggerFactory.getLogger(VisibleRefFilter.class);

  private final TagCache tagCache;
  private final ChangeNotes.Factory changeNotesFactory;
  @Nullable private final SearchingChangeCacheImpl changeCache;
  private final Repository db;
  private final Project.NameKey projectName;
  private final ProjectControl projectCtl;
  private final ReviewDb reviewDb;
  private final boolean showMetadata;

  public VisibleRefFilter(
      TagCache tagCache,
      ChangeNotes.Factory changeNotesFactory,
      @Nullable SearchingChangeCacheImpl changeCache,
      Repository db,
      ProjectControl projectControl,
      ReviewDb reviewDb,
      boolean showMetadata) {
    this.tagCache = tagCache;
    this.changeNotesFactory = changeNotesFactory;
    this.changeCache = changeCache;
    this.db = db;
    this.projectName = projectControl.getProject().getNameKey();
    this.projectCtl = projectControl;
    this.reviewDb = reviewDb;
    this.showMetadata = showMetadata;
  }

  public Map<String, Ref> filter(Map<String, Ref> refs, boolean filterTagsSeparately) {
    if (projectCtl.getProjectState().isAllUsers()
        && projectCtl.getUser().isIdentifiedUser()) {
      Ref userRef =
          refs.get(RefNames.refsUsers(projectCtl.getUser().getAccountId()));
      if (userRef != null) {
        SymbolicRef refsUsersSelf =
            new SymbolicRef(RefNames.REFS_USERS_SELF, userRef);
        refs = new HashMap<>(refs);
        refs.put(refsUsersSelf.getName(), refsUsersSelf);
      }
    }

    if (projectCtl.allRefsAreVisible(ImmutableSet.of(RefNames.REFS_CONFIG))) {
      Map<String, Ref> r = Maps.newHashMap(refs);
      if (!projectCtl.controlForRef(RefNames.REFS_CONFIG).isVisible()) {
        r.remove(RefNames.REFS_CONFIG);
      }
      return r;
    }

    Account.Id currAccountId;
    boolean canViewMetadata;
    if (projectCtl.getUser().isIdentifiedUser()) {
      IdentifiedUser user = projectCtl.getUser().asIdentifiedUser();
      currAccountId = user.getAccountId();
      canViewMetadata = user.getCapabilities().canAccessDatabase();
    } else {
      currAccountId = null;
      canViewMetadata = false;
    }

    Set<Change.Id> visibleChanges = visibleChanges();
    Map<String, Ref> result = new HashMap<>();
    List<Ref> deferredTags = new ArrayList<>();

    for (Ref ref : refs.values()) {
      Change.Id changeId;
      Account.Id accountId;
      if (ref.getName().startsWith(RefNames.REFS_CACHE_AUTOMERGE)) {
        continue;
      } else if (showMetadata
          && (RefNames.isRefsEditOf(ref.getLeaf().getName(), currAccountId)
              || (RefNames.isRefsEdit(ref.getLeaf().getName())
                  && canViewMetadata))) {
        // Change edit reference related is visible to the account that owns the
        // change edit.
        //
        // TODO(dborowitz): Verify if change is visible (to exclude edits on
        // changes that the user has lost access to).
        result.put(ref.getName(), ref);

      } else if ((changeId = Change.Id.fromRef(ref.getName())) != null) {
        // Reference related to a change is visible if the change is visible.
        //
        if (showMetadata
            && (canViewMetadata || visibleChanges.contains(changeId))) {
          result.put(ref.getName(), ref);
        }

      } else if (isTag(ref)) {
        // If its a tag, consider it later.
        //
        if (ref.getObjectId() != null) {
          deferredTags.add(ref);
        }

      } else if (projectCtl.controlForRef(ref.getLeaf().getName()).isVisible()) {
        // Use the leaf to lookup the control data. If the reference is
        // symbolic we want the control around the final target. If its
        // not symbolic then getLeaf() is a no-op returning ref itself.
        //

        if ((accountId =
            Account.Id.fromRef(ref.getLeaf().getName())) != null) {
          // Reference related to an account is visible only for the current
          // account.
          if (showMetadata
              && (canViewMetadata || accountId.equals(currAccountId))) {
            result.put(ref.getName(), ref);
          }
        } else {
          result.put(ref.getName(), ref);
        }
      }
    }

    // If we have tags that were deferred, we need to do a revision walk
    // to identify what tags we can actually reach, and what we cannot.
    //
    if (!deferredTags.isEmpty() && (!result.isEmpty() || filterTagsSeparately)) {
      TagMatcher tags = tagCache.get(projectName).matcher(
          tagCache,
          db,
          filterTagsSeparately ? filter(db.getAllRefs()).values() : result.values());
      for (Ref tag : deferredTags) {
        if (tags.isReachable(tag)) {
          result.put(tag.getName(), tag);
        }
      }
    }

    return result;
  }

  @Override
  protected Map<String, Ref> getAdvertisedRefs(Repository repository,
      RevWalk revWalk) throws ServiceMayNotContinueException {
    try {
      return filter(repository.getRefDatabase().getRefs(RefDatabase.ALL));
    } catch (ServiceMayNotContinueException e) {
      throw e;
    } catch (IOException e) {
      ServiceMayNotContinueException ex = new ServiceMayNotContinueException();
      ex.initCause(e);
      throw ex;
    }
  }

  private Map<String, Ref> filter(Map<String, Ref> refs) {
    return filter(refs, false);
  }

  private Set<Change.Id> visibleChanges() {
    if (!showMetadata) {
      return Collections.emptySet();
    } else if (changeCache == null) {
      return visibleChangesByScan();
    }
    return visibleChangesBySearch();
  }

  private Set<Change.Id> visibleChangesBySearch() {
    Project project = projectCtl.getProject();
    try {
      Set<Change.Id> visibleChanges = new HashSet<>();
      for (ChangeData cd : changeCache.getChangeData(
          reviewDb, project.getNameKey())) {
        if (projectCtl.controlForIndexedChange(cd.change())
            .isVisible(reviewDb, cd)) {
          visibleChanges.add(cd.getId());
        }
      }
      return visibleChanges;
    } catch (OrmException e) {
      log.error("Cannot load changes for project " + project.getName()
          + ", assuming no changes are visible", e);
      return Collections.emptySet();
    }
  }

  private Set<Change.Id> visibleChangesByScan() {
    Project.NameKey project = projectCtl.getProject().getNameKey();
    try {
      Set<Change.Id> visibleChanges = new HashSet<>();
      for (ChangeNotes cn : changeNotesFactory.scan(db, reviewDb, project)) {
        if (projectCtl.controlFor(cn).isVisible(reviewDb)) {
          visibleChanges.add(cn.getChangeId());
        }
      }
      return visibleChanges;
    } catch (IOException | OrmException e) {
      log.error("Cannot load changes for project " + project
          + ", assuming no changes are visible", e);
      return Collections.emptySet();
    }
  }

  private static boolean isTag(Ref ref) {
    return ref.getLeaf().getName().startsWith(Constants.R_TAGS);
  }
}
