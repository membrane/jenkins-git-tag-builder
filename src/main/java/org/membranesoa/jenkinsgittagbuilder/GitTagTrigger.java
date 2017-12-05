/*
 * The MIT License
 * 
 * Copyright (c) 2004-2009, Sun Microsystems, Inc., Kohsuke Kawaguchi, Brian Westrich, Jean-Baptiste Quenot, id:cactusman
 *               2015 Kanstantsin Shautsou
 *               2016 predic8 GmbH, Oliver Weiler and Tobias Polley
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

import antlr.ANTLRException;

import com.google.common.base.Preconditions;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.ExtensionListListener;
import hudson.Util;
import hudson.console.AnnotatedLargeText;
import hudson.init.Initializer;
import hudson.model.*;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.GitStatus;
import hudson.scm.SCM;
import hudson.scm.SCMDescriptor;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.FlushProofOutputStream;
import hudson.util.FormValidation;
import hudson.util.IOUtils;
import hudson.util.NamingThreadFactory;
import hudson.util.SequentialExecutionQueue;
import hudson.util.StreamTaskListener;
import hudson.util.TimeUnit2;

import org.apache.commons.io.FileUtils;
import org.apache.commons.jelly.XMLOutput;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.DataBoundConstructor;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import jenkins.model.Jenkins;
import jenkins.triggers.SCMTriggerItem;
import net.sf.json.JSONObject;

import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerResponse;

import static hudson.init.InitMilestone.JOB_LOADED;
import static java.util.logging.Level.*;

import javax.annotation.Nullable;

import jenkins.model.RunAction2;


/**
 * {@link Trigger} that checks for new Git Tags periodically.
 *
 * You can add UI elements under the SCM section by creating a config.jelly or config.groovy in the resources area for your class that inherits from
 * GitTagTrigger and has the
 *
 * @author Kohsuke Kawaguchi
 * @{@link hudson.model.Extension} annotation. The UI should be wrapped in an f:section element to denote it.
 */
public class GitTagTrigger extends Trigger<Item> {

    private boolean ignorePostCommitHooks;

    private String tagFilter;

    @Initializer(after = JOB_LOADED)
    public static void setup() throws IOException {
        Jenkins instance = Jenkins.getInstance();
        if (instance == null)
            return;

        ExtensionList<GitStatus.Listener> extensionList = instance.getExtensionList(GitStatus.Listener.class);

        boolean found = false;
        for (GitStatus.Listener listener : extensionList) {
            if (listener instanceof MyListener)
                found = true;
        }

        if (!found)
            extensionList.add(0, new MyListener());
    }

    public GitTagTrigger(String scmpoll_spec) throws ANTLRException {
        this("", scmpoll_spec, false);
    }

    @DataBoundConstructor
    public GitTagTrigger(String tagFilter, String scmpoll_spec, boolean ignorePostCommitHooks) throws ANTLRException {
        super(scmpoll_spec);
        this.tagFilter = tagFilter;
        this.ignorePostCommitHooks = ignorePostCommitHooks;
    }

    /**
     * This trigger wants to ignore post-commit hooks. <p> SCM plugins must respect this and not run this trigger for post-commit notifications.
     *
     * @since 1.493
     */
    public boolean isIgnorePostCommitHooks() {
        return this.ignorePostCommitHooks;
    }

    public final String getTagFilter() {
        return tagFilter;
    }


    @Override
    public void run() {
        if (job == null) {
            return;
        }

        run(null);
    }

    /**
     * Run the SCM trigger with additional build actions. Used by SubversionRepositoryStatus to trigger a build at a specific revisionn number.
     *
     * @since 1.375
     */
    public void run(Action[] additionalActions) {
        if (job == null) {
            return;
        }

        DescriptorImpl d = getDescriptor();

        LOGGER.fine("Scheduling a polling for " + job);
        if (d.synchronousPolling) {
            LOGGER.fine("Running the trigger directly without threading, " +
                    "as it's already taken care of by Trigger.Cron");
            new Runner(additionalActions).run();
        } else {
            // schedule the polling.
            // even if we end up submitting this too many times, that's OK.
            // the real exclusion control happens inside Runner.
            LOGGER.fine("scheduling the trigger to (asynchronously) run");
            d.queue.execute(new Runner(additionalActions));
            d.clogCheck();
        }
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    @Override
    public Collection<? extends Action> getProjectActions() {
        if (job == null) {
            return Collections.emptyList();
        }

        return Collections.singleton(new GitTriggerAction());
    }

    /**
     * Returns the file that records the last/current polling activity.
     */
    public File getLogFile() {
        return new File(job.getRootDir(), "git-tag-polling.log");
    }

    @Extension
    public static class DescriptorImpl extends TriggerDescriptor {

        private static ThreadFactory threadFactory() {
            return new NamingThreadFactory(Executors.defaultThreadFactory(), "GitTagTrigger");
        }

        /**
         * Used to control the execution of the polling tasks. <p> This executor implementation has a semantics suitable for polling. Namely, no two threads
         * will try to poll the same project at once, and multiple polling requests to the same job will be combined into one. Note that because executor isn't
         * aware of a potential workspace lock between a build and a polling, we may end up using executor threads unwisely --- they may block.
         */
        private transient final SequentialExecutionQueue queue = new SequentialExecutionQueue(Executors.newSingleThreadExecutor(threadFactory()));

        /**
         * Whether the projects should be polled all in one go in the order of dependencies. The default behavior is that each project polls for changes
         * independently.
         */
        public boolean synchronousPolling = false;

        /**
         * Max number of threads for SCM polling. 0 for unbounded.
         */
        private int maximumThreads;

        public DescriptorImpl() {
            load();
            resizeThreadPool();
        }

        public boolean isApplicable(Item item) {
            return GitTagTriggerItem.GitTagTriggerItems.asGitTagTriggerItem(item) != null;
        }

        public ExecutorService getExecutor() {
            return queue.getExecutors();
        }

        /**
         * Returns true if the SCM polling thread queue has too many jobs than it can handle.
         */
        public boolean isClogged() {
            return queue.isStarving(STARVATION_THRESHOLD);
        }

        /**
         * Checks if the queue is clogged, and if so, activate {@link AdministrativeMonitorImpl}.
         */
        public void clogCheck() {
            AdministrativeMonitor.all().get(AdministrativeMonitorImpl.class).on = isClogged();
        }

        /**
         * Gets the snapshot of {@link Runner}s that are performing polling.
         */
        public List<Runner> getRunners() {
            return Util.filter(queue.getInProgress(), Runner.class);
        }

        // originally List<SCMedItem> but known to be used only for logging, in which case the instances are not actually cast to SCMedItem anyway
        public List<GitTagTriggerItem> getItemsBeingPolled() {
            List<GitTagTriggerItem> r = new ArrayList<GitTagTriggerItem>();
            for (Runner i : getRunners())
                r.add(i.getTarget());
            return r;
        }

        public String getDisplayName() {
            return Messages.GitTagTrigger_DisplayName();
        }

        /**
         * Gets the number of concurrent threads used for polling.
         *
         * @return 0 if unlimited.
         */
        public int getPollingThreadCount() {
            return maximumThreads;
        }

        /**
         * Sets the number of concurrent threads used for SCM polling and resizes the thread pool accordingly
         *
         * @param n number of concurrent threads, zero or less means unlimited, maximum is 100
         */
        public void setPollingThreadCount(int n) {
            // fool proof
            if (n < 0) n = 0;
            if (n > 100) n = 100;

            maximumThreads = n;

            resizeThreadPool();
        }

        @Restricted(NoExternalUse.class)
        public boolean isPollingThreadCountOptionVisible() {
            // unless you have a fair number of projects, this option is likely pointless.
            // so let's hide this option for new users to avoid confusing them
            // unless it was already changed
            // TODO switch to check for GitTagTriggerItem
            return getPollingThreadCount() != 0;
        }

        /**
         * Update the {@link ExecutorService} instance.
         */
        /*package*/
        synchronized void resizeThreadPool() {
            queue.setExecutors(
                    (maximumThreads == 0 ? Executors.newCachedThreadPool(threadFactory()) : Executors.newFixedThreadPool(maximumThreads, threadFactory())));
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            String t = json.optString("pollingThreadCount", null);
            if (t == null || t.length() == 0)
                setPollingThreadCount(0);
            else
                setPollingThreadCount(Integer.parseInt(t));

            // Save configuration
            save();

            return true;
        }

        public FormValidation doCheckPollingThreadCount(@QueryParameter String value) {
            if (value != null && "".equals(value.trim()))
                return FormValidation.ok();
            return FormValidation.validateNonNegativeInteger(value);
        }
    }

    @Extension
    public static final class AdministrativeMonitorImpl extends AdministrativeMonitor {
        private boolean on;

        public boolean isActivated() {
            return on;
        }
    }

    /**
     * Associated with {@link Run} to show the polling log that triggered that build.
     *
     * @since 1.376
     */
    public static class BuildAction implements RunAction2 {
        private transient /*final*/ Run<?, ?> run;
        @Deprecated
        public transient /*final*/ AbstractBuild build;

        /**
         * @since 1.568
         */
        public BuildAction(Run<?, ?> run) {
            this.run = run;
            build = run instanceof AbstractBuild ? (AbstractBuild) run : null;
        }

        @Deprecated
        public BuildAction(AbstractBuild build) {
            this((Run) build);
        }

        /**
         * @since 1.568
         */
        public Run<?, ?> getRun() {
            return run;
        }

        /**
         * Polling log that triggered the build.
         */
        public File getPollingLogFile() {
            return new File(run.getRootDir(), "polling.log");
        }

        public String getIconFileName() {
            return "clipboard.png";
        }

        public String getDisplayName() {
            return Messages.GitTagTrigger_BuildAction_DisplayName();
        }

        public String getUrlName() {
            return "pollingLog";
        }

        /**
         * Sends out the raw polling log output.
         */
        public void doPollingLog(StaplerRequest req, StaplerResponse rsp) throws IOException {
            rsp.setContentType("text/plain;charset=UTF-8");
            // Prevent jelly from flushing stream so Content-Length header can be added afterwards
            FlushProofOutputStream out = new FlushProofOutputStream(rsp.getCompressedOutputStream(req));
            try {
                getPollingLogText().writeLogTo(0, out);
            } finally {
                IOUtils.closeQuietly(out);
            }
        }

        public AnnotatedLargeText getPollingLogText() {
            return new AnnotatedLargeText<BuildAction>(getPollingLogFile(), Charset.defaultCharset(), true, this);
        }

        /**
         * Used from <tt>polling.jelly</tt> to write annotated polling log to the given output.
         */
        public void writePollingLogTo(long offset, XMLOutput out) throws IOException {
            // TODO: resurrect compressed log file support
            getPollingLogText().writeHtmlTo(offset, out.asWriter());
        }

        @Override
        public void onAttached(Run<?, ?> r) {
            // unnecessary, existing constructor does this
        }

        @Override
        public void onLoad(Run<?, ?> r) {
            run = r;
            build = run instanceof AbstractBuild ? (AbstractBuild) run : null;
        }
    }

    /**
     * Action object for job. Used to display the last polling log.
     */
    public final class GitTriggerAction implements Action {
        public AbstractProject<?, ?> getOwner() {
            Item item = getItem();
            return item instanceof AbstractProject ? ((AbstractProject) item) : null;
        }

        /**
         * @since 1.568
         */
        public Item getItem() {
            return job().asItem();
        }

        public String getIconFileName() {
            return "clipboard.png";
        }

        public String getDisplayName() {
            Set<SCMDescriptor<?>> descriptors = new HashSet<SCMDescriptor<?>>();
            for (SCM scm : job().getSCMs()) {
                descriptors.add(scm.getDescriptor());
            }
            return descriptors.size() == 1 ? Messages.GitTagTrigger_getDisplayName(descriptors.iterator().next().getDisplayName()) : Messages.GitTagTrigger_BuildAction_DisplayName();
        }

        public String getUrlName() {
            return "gitTagPollLog";
        }

        public String getLog() throws IOException {
            return Util.loadFile(getLogFile());
        }

        /**
         * Writes the annotated log to the given output.
         *
         * @since 1.350
         */
        public void writeLogTo(XMLOutput out) throws IOException {
            new AnnotatedLargeText<GitTriggerAction>(getLogFile(), Charset.defaultCharset(), true, this).writeHtmlTo(0, out.asWriter());
        }
    }

    private static final Logger LOGGER = Logger.getLogger(GitTagTrigger.class.getName());

    /**
     * {@link Runnable} that actually performs polling.
     */
    public class Runner implements Runnable {

        /**
         * When did the polling start?
         */
        private volatile long startTime;

        private Action[] additionalActions;

        public Runner() {
            this(null);
        }

        public Runner(Action[] actions) {
            Preconditions.checkNotNull(job, "Runner can't be instantiated when job is null");

            if (actions == null) {
                additionalActions = new Action[0];
            } else {
                additionalActions = actions;
            }
        }

        /**
         * Where the log file is written.
         */
        public File getLogFile() {
            return GitTagTrigger.this.getLogFile();
        }

        /**
         * For which {@link Item} are we polling?
         *
         * @since 1.568
         */
        public GitTagTriggerItem getTarget() {
            return job();
        }

        /**
         * When was this polling started?
         */
        public long getStartTime() {
            return startTime;
        }

        /**
         * Human readable string of when this polling is started.
         */
        public String getDuration() {
            return Util.getTimeSpanString(System.currentTimeMillis() - startTime);
        }

        private Set<String> runPolling() {
            try {
                // to make sure that the log file contains up-to-date text,
                // don't do buffering.
                StreamTaskListener listener = new StreamTaskListener(getLogFile());

                try {
                    PrintStream logger = listener.getLogger();
                    long start = System.currentTimeMillis();
                    logger.println("Started on " + DateFormat.getDateTimeInstance().format(new Date()));
                    Set<String> newTags = filterTags(job().poll(listener));
                    logger.println("Done. Took " + Util.getTimeSpanString(System.currentTimeMillis() - start));
                    if (newTags.size() > 0)
                        logger.println("Changes found");
                    else
                        logger.println("No changes");
                    return newTags;
                } catch (Error | RuntimeException e) {
                    e.printStackTrace(listener.error("Failed to record SCM polling for " + job));
                    LOGGER.log(Level.SEVERE, "Failed to record SCM polling for " + job, e);
                    throw e;
                } finally {
                    listener.close();
                }
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "Failed to record SCM polling for " + job, e);
                return new HashSet<>();
            }
        }

        public void run() {
            if (job == null) {
                return;
            }

            String threadName = Thread.currentThread().getName();
            Thread.currentThread().setName("SCM polling for " + job);
            try {
                startTime = System.currentTimeMillis();
                for (String tag : runPolling()) {
                    GitTagTriggerItem p = job();
                    GitTagTriggerCause cause;
                    try {
                        cause = new GitTagTriggerCause(getLogFile());
                    } catch (IOException e) {
                        LOGGER.log(WARNING, "Failed to parse the polling log", e);
                        cause = new GitTagTriggerCause();
                    }
                    Action[] queueActions = new Action[additionalActions.length + 3];
                    queueActions[0] = new CauseAction(cause);
                    queueActions[1] = new ParametersAction(new TextParameterValue("tagName", tag));
                    queueActions[2] = new ParametersAction(getDefaultParametersValues());
                    System.arraycopy(additionalActions, 0, queueActions, 3, additionalActions.length);
                    if (p.scheduleBuild2(p.getQuietPeriod(), queueActions) != null) {
                        LOGGER.info("New Git Tags changes detected in " + job.getFullDisplayName() + ". Triggering build.");
                    } else {
                        LOGGER.info("New Git Tags detected in " + job.getFullDisplayName() + ". Job is already in the queue");
                    }
                }
            } finally {
                Thread.currentThread().setName(threadName);
            }
        }

        private List<ParameterValue> getDefaultParametersValues() {
            ParametersDefinitionProperty paramDefProp = ((FreeStyleProject) job).getProperty(ParametersDefinitionProperty.class);
            ArrayList<ParameterValue> defValues = new ArrayList<ParameterValue>();

        /*
         * This check is made ONLY if someone will call this method even if isParametrized() is false.
         */
            if (paramDefProp == null)
                return defValues;

        /* Scan for all parameter with an associated default values */
            for (ParameterDefinition paramDefinition : paramDefProp.getParameterDefinitions()) {
                ParameterValue defaultValue = paramDefinition.getDefaultParameterValue();

                if (paramDefinition.getName().equals("tagName")) continue;
                if (defaultValue != null)
                    defValues.add(defaultValue);
            }

            return defValues;
        }

        // as per the requirement of SequentialExecutionQueue, value equality is necessary
        @Override
        public boolean equals(Object that) {
            return that instanceof Runner && job == ((Runner) that)._job();
        }

        private Item _job() {
            return job;
        }

        @Override
        public int hashCode() {
            return job.hashCode();
        }
    }

    private Set<String> filterTags(Set<String> tags) {
        if (tagFilter == null || tagFilter.equals(""))
            return tags;
        Pattern pattern = Pattern.compile(tagFilter);
        HashSet<String> res = new HashSet<>();
        for (String tag : tags)
            if (pattern.matcher(tag).matches())
                res.add(tag);
        return res;
    }

    @SuppressWarnings("deprecation")
    private GitTagTriggerItem job() {
        return GitTagTriggerItem.GitTagTriggerItems.asGitTagTriggerItem(job);
    }

    public static class GitTagTriggerCause extends Cause {
        /**
         * Only used while ths cause is in the queue. Once attached to the build, we'll move this into a file to reduce the memory footprint.
         */
        private String pollingLog;

        private transient Run run;

        public GitTagTriggerCause(File logFile) throws IOException {
            // TODO: charset of this log file?
            this(FileUtils.readFileToString(logFile));
        }

        public GitTagTriggerCause(String pollingLog) {
            this.pollingLog = pollingLog;
        }

        /**
         * @deprecated Use {@link GitTagTrigger.GitTagTriggerCause(String)}.
         */
        @Deprecated
        public GitTagTriggerCause() {
            this("");
        }

        @Override
        public void onLoad(Run run) {
            this.run = run;
        }

        @Override
        public void onAddedTo(Run build) {
            this.run = build;
            try {
                BuildAction a = new BuildAction(build);
                FileUtils.writeStringToFile(a.getPollingLogFile(), pollingLog);
                build.replaceAction(a);
            } catch (IOException e) {
                LOGGER.log(WARNING, "Failed to persist the polling log", e);
            }
            pollingLog = null;
        }

        @Override
        public String getShortDescription() {
            return Messages.GitTagTrigger_GitTagTriggerCause_ShortDescription();
        }

        @Restricted(DoNotUse.class)
        public Run getRun() {
            return this.run;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof GitTagTriggerCause;
        }

        @Override
        public int hashCode() {
            return 3;
        }
    }

    /**
     * How long is too long for a polling activity to be in the queue?
     */
    public static long STARVATION_THRESHOLD = Long.getLong(GitTagTrigger.class.getName() + ".starvationThreshold", TimeUnit2.HOURS.toMillis(1));

    private static class MyListener extends GitStatus.Listener {

        public List<GitStatus.ResponseContributor> onNotifyCommit(String origin, URIish uri, @Nullable String sha1, List<ParameterValue> buildParameters, String... branches) {
            
            List<GitStatus.ResponseContributor> result = new ArrayList<>();
            Jenkins jenkins = Jenkins.getInstance();
            if (jenkins == null) {
                LOGGER.severe("Jenkins.getInstance() is null in GitStatus.onNotifyCommit");
                return result;
            }
            for (final Item project : jenkins.getAllItems()) {
                SCMTriggerItem scmTriggerItem = SCMTriggerItem.SCMTriggerItems.asSCMTriggerItem(project);
                if (scmTriggerItem == null) {
                    continue;
                }

                for (SCM scm : scmTriggerItem.getSCMs()) {
                    if (scm instanceof GitSCM) {
                        GitTagTriggerItem gitTagTriggerItem = GitTagTriggerItem.GitTagTriggerItems.asGitTagTriggerItem(project);
                        if (gitTagTriggerItem != null) {

                            for (RemoteConfig remoteConfig : ((GitSCM) scm).getRepositories()) {
                                List<URIish> urIs = remoteConfig.getURIs();

                                for (URIish is : urIs) {
                                    if (project instanceof FreeStyleProject) {
                                        FreeStyleProject fp = (FreeStyleProject) project;
                                        GitTagTrigger trigger = fp.getTrigger(GitTagTrigger.class);

                                        if (trigger != null && uri != null && uri.toASCIIString().equalsIgnoreCase(is.toASCIIString())) {
                                            LOGGER.info("Found Git Tag Trigger");
                                            trigger.run();
                                        }

                                    }

                                }
                            }
                        }


                    }
                }


            }


            return Arrays.asList((GitStatus.ResponseContributor) new GitStatus.MessageResponseContributor("Demo 111"));
        }
    }
}
