// Copyright (C) 2016 The Android Open Source Project
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

package com.google.gerrit.server.schema;

import static java.util.stream.Collectors.joining;

import com.google.gerrit.reviewdb.client.Project;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.GerritPersonIdent;
import com.google.gerrit.server.extensions.events.GitReferenceUpdated;
import com.google.gerrit.server.git.GitRepositoryManager;
import com.google.gerrit.server.git.meta.MetaDataUpdate;
import com.google.gwtorm.server.OrmException;
import com.google.inject.Inject;
import com.google.inject.Provider;
import java.io.IOException;
import java.util.SortedSet;
import java.util.TreeSet;
import org.eclipse.jgit.errors.ConfigInvalidException;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;

public class Schema_130 extends SchemaVersion {
  private static final String COMMIT_MSG =
      "Remove force option from 'Push Annotated Tag' permission\n"
          + "\n"
          + "The force option on 'Push Annotated Tag' had no effect and is no longer\n"
          + "supported.";

  private final GitRepositoryManager repoManager;
  private final PersonIdent serverUser;

  @Inject
  Schema_130(
      Provider<Schema_129> prior,
      GitRepositoryManager repoManager,
      @GerritPersonIdent PersonIdent serverUser) {
    super(prior);
    this.repoManager = repoManager;
    this.serverUser = serverUser;
  }

  @Override
  protected void migrateData(ReviewDb db, UpdateUI ui) throws OrmException {
    SortedSet<Project.NameKey> repoList = repoManager.list();
    SortedSet<Project.NameKey> repoUpgraded = new TreeSet<>();
    ui.message("\tMigrating " + repoList.size() + " repositories ...");
    for (Project.NameKey projectName : repoList) {
      try (Repository git = repoManager.openRepository(projectName);
          MetaDataUpdate md = new MetaDataUpdate(GitReferenceUpdated.DISABLED, projectName, git)) {
        ProjectConfigSchemaUpdate cfg = ProjectConfigSchemaUpdate.read(md);
        cfg.removeForceFromPermission("pushTag");
        if (cfg.isUpdated()) {
          repoUpgraded.add(projectName);
        }
        cfg.save(serverUser, COMMIT_MSG);
      } catch (ConfigInvalidException | IOException ex) {
        throw new OrmException("Cannot migrate project " + projectName, ex);
      }
    }
    ui.message("\tMigration completed:  " + repoUpgraded.size() + " repositories updated:");
    ui.message("\t" + repoUpgraded.stream().map(Project.NameKey::get).collect(joining(" ")));
  }
}
