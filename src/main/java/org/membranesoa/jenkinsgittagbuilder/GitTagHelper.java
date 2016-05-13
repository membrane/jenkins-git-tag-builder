/*
 * The MIT License
 *
 * Copyright (c) The original authors of GitSCM.java
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

import hudson.EnvVars;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.*;
import hudson.model.Queue;
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.Revision;
import hudson.plugins.git.extensions.GitSCMExtension;
import hudson.plugins.git.util.Build;
import hudson.plugins.git.util.BuildData;
import hudson.plugins.git.util.GitUtils;
import hudson.scm.PollingResult;
import hudson.slaves.NodeProperty;
import jenkins.model.Jenkins;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.plugins.gitclient.GitClient;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static hudson.scm.PollingResult.BUILD_NOW;
import static hudson.scm.PollingResult.NO_CHANGES;

/**
 * Heavily based on GitSCM.java and GitUtils.java from https://github.com/jenkinsci/git-plugin.git .
 *
 * @author predic8 GmbH, Tobias Polley
 */
public class GitTagHelper {

    public static final Pattern GIT_REF = Pattern.compile("(refs/[^/]+)/.*");

    /**
     * An attempt to generate at least semi-useful EnvVars for polling calls, based on previous build.
     * Cribbed from various places.
     */
    public static EnvVars getPollEnvironment(AbstractProject p, AbstractBuild fakeBuild, TaskListener listener)
            throws IOException,InterruptedException {
        EnvVars env;
        StreamBuildListener buildListener = new StreamBuildListener((OutputStream)listener.getLogger());

        env = new EnvVars(System.getenv());

        String rootUrl = Hudson.getInstance().getRootUrl();
        if(rootUrl!=null) {
            env.put("HUDSON_URL", rootUrl); // Legacy.
            env.put("JENKINS_URL", rootUrl);
            env.put("BUILD_URL", rootUrl+fakeBuild.getUrl());
            env.put("JOB_URL", rootUrl+p.getUrl());
        }

        if(!env.containsKey("HUDSON_HOME")) // Legacy
            env.put("HUDSON_HOME", Hudson.getInstance().getRootDir().getPath() );

        if(!env.containsKey("JENKINS_HOME"))
            env.put("JENKINS_HOME", Hudson.getInstance().getRootDir().getPath() );

        for (NodeProperty nodeProperty: Hudson.getInstance().getGlobalNodeProperties()) {
            Environment environment = nodeProperty.setUp(fakeBuild, null, (BuildListener)buildListener);
            if (environment != null) {
                environment.buildEnvVars(env);
            }
        }

        // add env contributing actions' values from last build to environment - fixes JENKINS-22009
        addEnvironmentContributingActionsValues(env, fakeBuild);

        EnvVars.resolve(env);

        return env;
    }

    private static void addEnvironmentContributingActionsValues(EnvVars env, AbstractBuild b) {
        List<? extends Action> buildActions = b.getAllActions();
        if (buildActions != null) {
            for (Action action : buildActions) {
                // most importantly, ParametersAction will be processed here (for parameterized builds)
                if (action instanceof ParametersAction) {
                    ParametersAction envAction = (ParametersAction) action;
                    envAction.buildEnvVars(b, env);
                }
            }
        }

        // Use the default parameter values (if any) instead of the ones from the last build
        ParametersDefinitionProperty paramDefProp = (ParametersDefinitionProperty) b.getProject().getProperty(ParametersDefinitionProperty.class);
        if (paramDefProp != null) {
            for(ParameterDefinition paramDefinition : paramDefProp.getParameterDefinitions()) {
                ParameterValue defaultValue  = paramDefinition.getDefaultParameterValue();
                if (defaultValue != null) {
                    defaultValue.buildEnvironment(b, env);
                }
            }
        }
    }


    public static Set<String> pollTags(AbstractProject<?, ?> project, GitSCM scm, final TaskListener listener) throws IOException, InterruptedException {
        // Poll for changes. Are there any unbuilt revisions that Hudson ought to build ?

        listener.getLogger().println("Using strategy: " + scm.getBuildChooser().getDisplayName());

        if (project.isConcurrentBuild()) {
            listener.error("This plugin does not currently work with concurrent builds enabled.");
            return new HashSet<>();
        }

        FreeStyleBuild lastRun = (FreeStyleBuild) project.getLastBuild();

        if (lastRun != null && lastRun.getWorkspace() != null && new FilePath(lastRun.getWorkspace(), ".git").exists()) {
            // OK
        } else {

            // do a git clone
            lastRun = new FreeStyleBuild((FreeStyleProject) project) {
                @Override
                public void run() {
                    if (!getLogFile().getParentFile().exists())
                        if (!getLogFile().getParentFile().mkdirs())
                            throw new RuntimeException("Could not create directory " + getLogFile().getParent());


                    execute(new BuildExecution() {
                        @Override
                        protected Result doRun(@Nonnull BuildListener listener) throws Exception {
                            return Result.SUCCESS;
                        }
                    });
                }
            };
            listener.getLogger().println("No workspace found, enqueuing build to create a workspace.");
            project.scheduleBuild(null);
            return new HashSet<>();
        }

        final EnvVars pollEnv = getPollEnvironment((AbstractProject) project, lastRun, listener);

        GitClient git = scm.createClient(listener, pollEnv, lastRun, lastRun.getWorkspace());

        HashSet<String> result = new HashSet<>();
        for (String tag : git.getRemoteTagNames(null))
            if (!tag.contains("^{}"))
                result.add(tag);
        return result;
    }

    private static List<RefSpec> getRefSpecs(GitSCM scm, RemoteConfig repo, EnvVars env) {
        List<RefSpec> refSpecs = new ArrayList<RefSpec>();
        for (RefSpec refSpec : repo.getFetchRefSpecs()) {
            refSpecs.add(new RefSpec(scm.getParameterString(refSpec.toString(), env)));
        }
        return refSpecs;
    }


}
