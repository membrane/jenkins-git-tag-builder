/*
 * The MIT License
 *
 * Copyright 2014 Jesse Glick.
 * Copyright 2016 predic8 Gmbh, Oliver Weiler and Tobias Polley.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.membranesoa.jenkinsgittagbuilder;

import hudson.model.*;
import hudson.model.queue.QueueTaskFuture;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.UserRemoteConfig;
import hudson.scm.NullSCM;
import hudson.scm.PollingResult;
import hudson.scm.SCM;

import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import jenkins.model.ParameterizedJobMixIn;

/**
 * The item type accepted by {@link GitTagTrigger}.
 * @since 1.568
 */
public interface GitTagTriggerItem {

    /** Should be {@code this}. */
    Item asItem();

    /** @see Job#getNextBuildNumber */
    int getNextBuildNumber();

    /** @see jenkins.model.ParameterizedJobMixIn.ParameterizedJob#getQuietPeriod */
    int getQuietPeriod();

    /** @see ParameterizedJobMixIn#scheduleBuild2 */
    @CheckForNull QueueTaskFuture<?> scheduleBuild2(int quietPeriod, Action... actions);

    /**
     * Returns all newly discovered tags.
     *
     * <p>
     * The implementation is responsible for ensuring mutual exclusion between polling and builds
     * if necessary.
     */
    @Nonnull Set<String> poll(@Nonnull TaskListener listener);

    @CheckForNull GitTagTrigger getGitTagTrigger();

    /**
     * Obtains all active SCMs.
     * May be used for informational purposes, or to determine whether to initiate polling.
     * @return a possibly empty collection
     */
    @Nonnull Collection<? extends SCM> getSCMs();

    /**
     * Utilities.
     */
    class GitTagTriggerItems {

        /**
         * See whether an item can be coerced to {@link GitTagTriggerItem}.
         * @param item any item
         * @return itself, if a {@link GitTagTriggerItem}, or an adapter, if an {@link hudson.model.SCMedItem}, else null
         */
        @SuppressWarnings("deprecation")
        public static @CheckForNull
        GitTagTriggerItem asGitTagTriggerItem(Item item) {
            if (item instanceof GitTagTriggerItem) {
                return (GitTagTriggerItem) item;
            } else if (item instanceof FreeStyleProject) { // TODO: is a more generic check possible?
                return new Bridge((FreeStyleProject) item);
            } else {
                return null;
            }
        }

        private static final class Bridge implements GitTagTriggerItem {
            private final FreeStyleProject delegate;
            Bridge(FreeStyleProject delegate) {
                this.delegate = delegate;
            }
            @Override public Item asItem() {
                return delegate.asProject();
            }
            @Override public int getNextBuildNumber() {
                return delegate.asProject().getNextBuildNumber();
            }
            @Override public int getQuietPeriod() {
                return delegate.asProject().getQuietPeriod();
            }
            @Override public QueueTaskFuture<?> scheduleBuild2(int quietPeriod, Action... actions) {
                return delegate.asProject().scheduleBuild2(quietPeriod, null, actions);
            }

            HashMap<String, Object> locks = new HashMap<>();

            @Override public Set<String> poll(TaskListener listener) {
                //was previously: return delegate.poll(listener);

                boolean found = false;
                for (SCM scm : getSCMs()) {
                    // TODO: implement multiple SCM support
                    if (delegate.getScm() instanceof GitSCM) {
                        found = true;

                        GitSCM git = (GitSCM) delegate.getScm();


                        try {

                            Object lock;
                            String projectName = delegate.asProject().getName();
                            synchronized (locks) {
                                lock = locks.get(projectName);
                                if (lock == null) {
                                    lock = new Object();
                                    locks.put(projectName, lock);
                                }
                            }

                            Set<String> tags;
                            synchronized (lock) {
                                tags = GitTagHelper.pollTags(delegate.asProject(), git, listener);
                                if (delegate.asProject().getLastBuild() == null ||
                                        delegate.asProject().getLastBuild().getWorkspace() == null)
                                    return new HashSet<>();
                                Storage storage = new Storage(delegate.asProject().getLastBuild().getWorkspace());
                                tags = storage.storeNewTags(tags);
                            }

                            return tags;

                        } catch (IOException e) {
                            e.printStackTrace();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }

                    }
                }
                if (!found) {
                    listener.error("No Git Repository found that could be polled for newly created tags.");
                }
                return new HashSet<>();
            }
            @Override public GitTagTrigger getGitTagTrigger() {
                return delegate.asProject().getTrigger(GitTagTrigger.class);
            }
            @Override public Collection<? extends SCM> getSCMs() {
                return resolveMultiScmIfConfigured(delegate.asProject().getScm());
            }
        }

        public static @Nonnull Collection<? extends SCM> resolveMultiScmIfConfigured(@CheckForNull SCM scm) {
            if (scm == null || scm instanceof NullSCM) {
                return Collections.emptySet();
            } else if (scm.getClass().getName().equals("org.jenkinsci.plugins.multiplescms.MultiSCM")) {
                try {
                    return (Collection<? extends SCM>) scm.getClass().getMethod("getConfiguredSCMs").invoke(scm);
                } catch (Exception x) {
                    Logger.getLogger(GitTagTriggerItem.class.getName()).log(Level.WARNING, null, x);
                    return Collections.singleton(scm);
                }
            } else {
                return Collections.singleton(scm);
            }
        }

        private GitTagTriggerItems() {}

    }

}
