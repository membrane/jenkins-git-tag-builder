# Jenkins Git Tag Builder
## Summary

This plugin will poll a Jenkins Job's Git Repository for new tags and tigger a build for each new tag found.

Currently, it is barely functional.

## Requirements

* Jenkins 1.642.4 or above
* Jenkins Git Plugin 2.4.5 or above.

## Setup

* Grab the latest version of this plugin from [our releases page] (https://github.com/membrane/jenkins-git-tag-builder/releases) and put the .hpi file into the "plugins" subdirectory of your Jenkins.

* Setup a FreeStyleProject.
 * Select "This build is parameterized" and add a String parameter called "tagName".
 * Do not check "Execute concurrent builds if necessary". (This plugin does currently not work with concurrent builds.)
 * Select "Git" as Source Code Management and configure your repository.
  * Enter `refs/tags/${tagName}` as Branch Specifier.
 * Check "Poll Git Repo for new Tags" and provide a schedule when to poll the git repo (e.g. `* * * * *` to poll every minute).

## Notes

Only newly discovered tags will trigger a build. Tags already present in the repository when the plugin is set up are ignored. (Once setup, you can trigger the job manually for these tags, of course.)

If no Jenkins workspace for the job exists, the plugin will initially trigger a first build to create a workspace and configure the Git Client. This first build will fail. This is a known issue and you can safely ignore this (deleting the failed build).
