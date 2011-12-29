package org.mortbay.jetty.plugin;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.logging.LogManager;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.factory.ArtifactFactory;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.artifact.resolver.ArtifactResolver;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.model.Profile;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Scanner;

/**
 * Runs embedded jetty and deploys war submodules
 *
 * @aggregator
 * @goal run-all
 *
 * @requiresDependencyResolution runtime
 */
public class JettyAggregatedRunMojo
    extends AbstractEmbeddedJettyMojo
{
    final WebApplicationScanBuilder scanBuilder = new WebApplicationScanBuilder();
    final WebApplicationConfigBuilder configBuilder = new WebApplicationConfigBuilder();

    final List<Scanner> scanners = new ArrayList<Scanner>();

    /**
    * List of other contexts to set up. Optional.
    * @parameter
    */
    private ContextHandler[] externalArtifactContextHandlers;

    /**
     * Configure java util logging properties. This parameter has precedence
     * over parameter loggingPropertiesFile.
     * @parameter
     */
    private Properties loggingProperties = new Properties();

    /**
     * Configure java util logging via logging properties file. It may be
     * overridden by parameter loggingProperties.
     * @parameter expression="${loggingProperties.file}"
     */
    private File loggingPropertiesFile;

    /**
     * @parameter expression="${session}"
     * @required
     */
    private MavenSession session;

    /**
     * What project should be considered the base when deciding what war modules to start,
     * can be either null (use the Maven session projects, the default, this should work
     * for all cases except when multiple distinct war collections exists in the same project,
     * eg multiple test modules). If not null only modules referenced from the base project will
     * be included. Nb parent only works if the execution is started from the
     * parent or higher up (eg the parent is part of the session projects).
     * @parameter
     */
    private String baseJettyModuleProjectPath = null;

    /**
     * Used for resolving artifacts
     *
     * @component
     */
    protected ArtifactResolver resolver;

    /**
     * The local repository where the artifacts are located.
     *
     * @parameter expression="${localRepository}"
     */
    protected ArtifactRepository localRepository;

    /**
     * The remote repositories where artifacts are located.
     *
     * @parameter expression="${project.remoteArtifactRepositories}"
     */
    protected List<?> remoteRepositories;

    /**
     * Used to create artifacts
     *
     * @component
     */
    protected ArtifactFactory artifactFactory;

    /**
     * A list of submodules that should be excluded from the
     * list of web applications started by Jetty.
     *
     * @parameter
     */
    private String[] excludedWebApps;

    @Override
    public void execute()
        throws MojoExecutionException, MojoFailureException
    {
        applyLoggingProperties();
        super.execute();
    }

    private void applyLoggingProperties()
        throws MojoFailureException
    {
        try {
            InputStream is;

            if (!loggingProperties.isEmpty()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                loggingProperties.store(baos, "Logging properties");
                is = new ByteArrayInputStream(baos.toByteArray());
                LogManager.getLogManager().readConfiguration(is);
            } else if (loggingPropertiesFile != null) {
                is = new BufferedInputStream(new FileInputStream(loggingPropertiesFile));
                LogManager.getLogManager().readConfiguration(is);
            }
        } catch (IOException e) {
            throw new MojoFailureException("Unable to apply logging properties", e);
        }
    }

    private boolean isAnExcludedWebApp(final MavenProject project)
    {
        if (excludedWebApps != null) {
            for (String excludedWebApp : excludedWebApps) {
                if (project.getArtifactId().equals(excludedWebApp)) {
                    return true;
                }
            }
        }

        return false;
    }

    @Override
    public void deployWebApplications()
        throws Exception
    {
        scanners.clear();
        Set<String> subprojects = new HashSet<String>();
        List<MavenProject> includedProjects = session.getProjects();
        MavenProject base = getProject(baseJettyModuleProjectPath, session.getCurrentProject(), includedProjects);
        // If the base project is not part of the session (or no base is specified), run with default maven session projects
        if (base != null) {
            includedProjects = filterNestedProjects(base, includedProjects);
        }
        for (MavenProject subProject : includedProjects) {
            if ("war".equals(subProject.getPackaging()) && !isAnExcludedWebApp(subProject)) {
                final JettyWebAppContext webAppConfig = configBuilder.configureWebApplication(subProject, getLog());
                subprojects.add(webAppConfig.getContextPath());

                getLog().info("\n=========================================================================="
                            + "\nInjecting : " + subProject.getName() + "\n\n" +  configBuilder.toInfoString(webAppConfig)
                            + "\n==========================================================================");

                getServer().addWebApplication(webAppConfig);

                List<File> dependencyOutputLocations = new ArrayList<File>();
                Set<Artifact> artifacts = subProject.getArtifacts();

                for (Artifact artifact : artifacts) {
                    MavenProject artifactProject = getLocalDownstreamProjectForDependency(artifact, getProject());

                    if (artifactProject != null) {
                        dependencyOutputLocations.add(new File(artifactProject.getBuild().getOutputDirectory()));
                    }
                }

                List<File> files =
                        scanBuilder.setupScannerFiles(webAppConfig,
                                                      Arrays.asList(subProject.getFile()),
                                                      Collections.<String>emptyList());

                List<File> allFiles = new ArrayList<File>(webAppConfig.getWebInfClasses());

                allFiles.addAll(dependencyOutputLocations);
                allFiles.addAll(files);

                webAppConfig.setClassPathFiles(allFiles);

                getLog().debug("Scanning: " + allFiles);

                Scanner scanner = new Scanner();
                scanner.addListener(new Scanner.BulkListener() {
                    public void filesChanged(List<String> changes)
                    {
                        try {
                            getLog().info("Detected changes: " + changes);

                            webAppConfig.stop();
                            webAppConfig.start();
                        } catch (Exception e) {
                            getLog().error("Error reconfiguring/restarting webapp after change in watched files", e);
                        }
                    }
                });

                scanner.setReportExistingFilesOnStartup(false);
                scanner.setScanInterval(getScanIntervalSeconds());
                scanner.setScanDirs(allFiles);
                scanner.setRecursive(true);

                scanner.start();
                scanners.add(scanner);
            }
        }

        if (externalArtifactContextHandlers != null) {
            configureWarArtifactsForExtraContextHandlers(subprojects);
        }

        getLog().info("Starting scanner at interval of " + getScanIntervalSeconds() + " seconds.");
    }

    private MavenProject getProject(String basePath, MavenProject thisProject, List<MavenProject> includedProjects) throws IOException
    {
        if (basePath == null) {
            return null;
        }

        String base = new File(thisProject.getBasedir().getAbsoluteFile() + "/" + basePath).getCanonicalPath();
        for (MavenProject project : includedProjects) {
            if (project.getBasedir().getAbsolutePath().equals(base)) {
                return project;
            }
        }
        return null;
    }

    private List<MavenProject> filterNestedProjects(MavenProject base, List<MavenProject> includedProjects) throws IOException
    {
        Set<String> projectsReached = new HashSet<String>();
        projectsReached.add(base.getArtifact().getDependencyConflictId());
        getReachedProjects(base, includedProjects, projectsReached);
        List<MavenProject> projects = new ArrayList<MavenProject>();
        for (MavenProject project : includedProjects) {
            if (projectsReached.contains(project.getArtifact().getDependencyConflictId())) {
                projects.add(project);
            }
        }
        return projects;
    }

    private void getReachedProjects(MavenProject base, List<MavenProject> includedProjects, Set<String> hashSet) throws IOException
    {
        for (String module : base.getModules()) {
            MavenProject moduleProject = getProject(module, base, includedProjects);
            if (moduleProject != null && !isAnExcludedWebApp(moduleProject)) {
                String id = moduleProject.getArtifact().getDependencyConflictId();
                if (!hashSet.contains(id)) {
                    hashSet.add(id);
                    getReachedProjects(moduleProject, includedProjects, hashSet);
                }
            }
        }
        for (Profile p : base.getActiveProfiles()) {
            if (p.getModules() != null) {
                for (Object module : p.getModules()) {
                    MavenProject moduleProject = getProject((String) module, base, includedProjects);
                    if (moduleProject != null && !isAnExcludedWebApp(moduleProject)) {
                        String id = moduleProject.getArtifact().getDependencyConflictId();
                        if (!hashSet.contains(id)) {
                            hashSet.add(id);
                            getReachedProjects(moduleProject, includedProjects, hashSet);
                        }
                    }
                }
            }
        }
    }

    private void configureWarArtifactsForExtraContextHandlers(final Set<String> skipContexts)
        throws Exception
    {
        for (Handler contextHandler : externalArtifactContextHandlers) {
            if (contextHandler instanceof JettyWebAppContext) {
                JettyWebAppContext jettyContext = (JettyWebAppContext) contextHandler;

                ArtifactData warArtifact = jettyContext.getWarArtifact();

                if (warArtifact != null) {
                    if (skipContexts.contains(jettyContext.getContextPath())) {
                        getLog().info(String.format("Not deploying '%s' for context '%s' since it is already handled by sub-project",
                                                    warArtifact.toString(), jettyContext.getContextPath()));
                        continue;
                    }

                    Artifact artifact = artifactFactory.createArtifact(warArtifact.groupId,
                                                                       warArtifact.artifactId,
                                                                       warArtifact.version,
                                                                       warArtifact.scope,
                                                                       warArtifact.type);

                    resolver.resolve(artifact, remoteRepositories, localRepository);

                    File warFile = artifact.getFile();
                    jettyContext.setWar(warFile.getAbsolutePath());
                    getServer().addHandler(jettyContext);

                    getLog().info(String.format("Deploying '%s' for context '%s'", warFile.getAbsolutePath(), jettyContext.getContextPath()));
                }
            }
        }
    }

    private MavenProject getLocalDownstreamProjectForDependency(final Artifact artifact,
                                                                final MavenProject topProject)
    {
        ProjectDependencyGraph projectDependencyGraph = session.getProjectDependencyGraph();
        List<MavenProject> downstreamProjects = projectDependencyGraph.getDownstreamProjects(topProject, true);

        for (MavenProject mavenProject : downstreamProjects) {
            if (mavenProject.getPackaging().equals("jar")) {
                if (artifact.getArtifactId().equals(mavenProject.getArtifactId()) &&
                    artifact.getGroupId().equals(mavenProject.getGroupId()) &&
                    artifact.getVersion().equals(mavenProject.getVersion())) {

                    return mavenProject;
                }
            }
        }

        return null;
    }

    @Override
    public void restartWebApplications(boolean reconfigureScanner)
        throws Exception
    {
        Handler context = getServer().getHandler();

        context.stop();
        context.start();
    }
}
