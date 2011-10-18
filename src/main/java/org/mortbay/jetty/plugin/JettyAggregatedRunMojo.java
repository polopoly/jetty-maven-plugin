package org.mortbay.jetty.plugin;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
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
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Scanner;

/**
 * Created by bitter on 2011-08-31
 *
 * @aggregator
 * @goal run-all
 * @requiresDependencyResolution runtime
 * @description Runs embedded jetty and deploys war submodules
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
     * java util logging properties
     * @parameter
     */
    private Properties loggingProperties = new Properties();

    /**
     * @parameter expression="${session}"
     * @required
     */
    private MavenSession session;

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
    
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        applyLoggingProperties();
        super.execute();
    }

    private void applyLoggingProperties() throws MojoFailureException {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            loggingProperties.store(baos, "Logging properties");
            LogManager.getLogManager().readConfiguration(new ByteArrayInputStream(baos.toByteArray()));
        } catch (IOException e) {
            throw new MojoFailureException("Unable to apply logging properties", e);
        }
    }

    @Override
    public void deployWebApplications()
        throws Exception
    {
        scanners.clear();

        for (MavenProject subProject : session.getProjects()) {
            if ("war".equals(subProject.getPackaging())) {
                final JettyWebAppContext webAppConfig = configBuilder.configureWebApplication(subProject, getLog());

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

                /**
                 * Order in classpath should be
                 *
                 * (1) target/classes of the webapp itself
                 * (2) target/classes of all local dependencies
                 * (3) all regular maven dependencies
                 */

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

                getLog().info("Starting scanner at interval of " + getScanIntervalSeconds() + " seconds.");
            }
        }

        if (externalArtifactContextHandlers != null) {
            configureWarArtifactsForExtraContextHandlers();
        }
    }

    private void configureWarArtifactsForExtraContextHandlers()
        throws Exception
    {
        for (Handler contextHandler : externalArtifactContextHandlers) {
            if (contextHandler instanceof JettyWebAppContext) {
                JettyWebAppContext jettyContext = (JettyWebAppContext) contextHandler;

                ArtifactData warArtifact = jettyContext.getWarArtifact();

                if (warArtifact != null) {
                    Artifact artifact = artifactFactory.createArtifact(warArtifact.groupId,
                                                                       warArtifact.artifactId,
                                                                       warArtifact.version,
                                                                       warArtifact.scope,
                                                                       warArtifact.type);

                    resolver.resolve(artifact, remoteRepositories, localRepository);

                    File warFile = artifact.getFile();
                    jettyContext.setWar(warFile.getAbsolutePath());

                    getServer().addHandler(jettyContext);
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
