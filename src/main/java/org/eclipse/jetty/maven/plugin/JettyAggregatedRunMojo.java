//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.maven.plugin;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.logging.LogManager;
import java.util.stream.Collectors;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.transfer.artifact.DefaultArtifactCoordinate;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolver;
import org.apache.maven.shared.transfer.artifact.resolve.ArtifactResolverException;
import org.eclipse.jetty.maven.plugin.utils.FilesHelper;
import org.eclipse.jetty.maven.plugin.utils.MavenProjectHelper;
import org.eclipse.jetty.maven.plugin.utils.OverlayUnpacker;
import org.eclipse.jetty.maven.plugin.utils.WebApplicationConfigBuilder;
import org.eclipse.jetty.maven.plugin.utils.WebApplicationScanBuilder;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.IncludeExcludeSet;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.util.Scanner.BulkListener;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * This goal is used in-situ on a Maven project without first requiring that the project
 * is assembled into a war, saving time during the development cycle.
 * <p>
 * The plugin forks a parallel lifecycle to ensure that the "compile" phase has been completed before invoking Jetty. This means
 * that you do not need to explicitly execute a "mvn compile" first. It also means that a "mvn clean jetty:run" will ensure that
 * a full fresh compile is done before invoking Jetty.
 * <p>
 * Once invoked, the plugin can be configured to run continuously, scanning for changes in the project and automatically performing a
 * hot redeploy when necessary. This allows the developer to concentrate on coding changes to the project using their IDE of choice and have those changes
 * immediately and transparently reflected in the running web container, eliminating development time that is wasted on rebuilding, reassembling and redeploying.
 * <p>
 * You may also specify the location of a jetty.xml file whose contents will be applied before any plugin configuration.
 * This can be used, for example, to deploy a static webapp that is not part of your maven build.
 * <p>
 * There is a <a href="https://www.eclipse.org/jetty/documentation/current/maven-and-jetty.html">reference guide</a> to the configuration parameters for this plugin.
 *
 * Runs jetty directly from a maven project
 */
@Mojo(
    name = "run-all",
    aggregator = true,
    requiresDependencyResolution = ResolutionScope.RUNTIME
)
//@Execute(phase = LifecyclePhase.TEST_COMPILE)
public class JettyAggregatedRunMojo extends AbstractJettyMojo
{
    public static final String DEFAULT_WEBAPP_SRC = "src" + File.separator + "main" + File.separator + "webapp";
    public static final String FAKE_WEBAPP = "webapp-tmp";

    /**
     * If true, the &lt;testOutputDirectory&gt;
     * and the dependencies of &lt;scope&gt;test&lt;scope&gt;
     * will be put first on the runtime classpath.
     */
    @Parameter(alias = "useTestClasspath", defaultValue = "false")
    protected boolean useTestScope;

    /**
     * The default location of the web.xml file. Will be used
     * if &lt;webApp&gt;&lt;descriptor&gt; is not set.
     */
    @Parameter(defaultValue = "${maven.war.webxml}", readonly = true)
    protected String webXml;

    /**
     * The directory containing generated classes.
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}", required = true)
    protected File classesDirectory;

    /**
     * An optional pattern for includes/excludes of classes in the classesDirectory
     */
    @Parameter
    protected ScanPattern scanClassesPattern;

    /**
     * The directory containing generated test classes.
     */
    @Parameter(defaultValue = "${project.build.testOutputDirectory}", required = true)
    protected File testClassesDirectory;

    /**
     * An optional pattern for includes/excludes of classes in the testClassesDirectory
     */
    @Parameter
    protected ScanPattern scanTestClassesPattern;

    /**
     * Root directory for all html/jsp etc files
     */
    @Parameter(defaultValue = "${maven.war.src}")
    protected File webAppSourceDirectory;

    /**
     * List of files or directories to additionally periodically scan for changes. Optional.
     */
    @Parameter
    protected File[] scanTargets;

    /**
     * List of directories with ant-style &lt;include&gt; and &lt;exclude&gt; patterns
     * for extra targets to periodically scan for changes. Can be used instead of,
     * or in conjunction with &lt;scanTargets&gt;.Optional.
     */
    @Parameter
    protected ScanTargetPattern[] scanTargetPatterns;

    /**
     * maven-war-plugin reference
     */
    protected WarPluginInfo warPluginInfo;

    /**
     * List of deps that are wars
     */
    protected List<Artifact> warArtifacts;

    protected Resource originalBaseResource;

    @Parameter(defaultValue = "${session}", required = true, readonly = true)
    private MavenSession session;

    /**
     * The project's remote repositories to use for the resolution.
     */
    @Parameter(defaultValue = "${project.remoteArtifactRepositories}", required = true, readonly = true)
    private List<ArtifactRepository> remoteRepositories;

    @Component
    private ArtifactResolver artifactResolver;

    /**
     * A list of submodules that should be excluded from the
     * list of web applications started by Jetty. This is a
     * list of simple submodule names like 'webapp-front',
     * not artifact coordinates.
     *
     */
    @Parameter
    private String[] excludedWebApps;

    /**
     * List of other contexts to set up.
     * Typically, these contexts are web applications not available as Maven submodules
     * in the current project, but that are instead deployed in a Maven repository.
     * See the "Jetty Maven plugin" page in the Nitro documentation for information
     * about the context handler structure.
     */
    @Parameter
    private ContextHandler[] externalArtifactContextHandlers;

    /**
     * Configure java util logging properties. This parameter has precedence
     * over parameter loggingPropertiesFile.
     */
    @Parameter
    private Properties loggingProperties = new Properties();

    /**
     * Configure java util logging via logging properties file. It may be
     * overridden by parameter loggingProperties.
     */
    @Parameter(defaultValue = "${project.loggingProperties.file}", required = true, readonly = true)
    private File loggingPropertiesFile;

    /**
     * Configure it to true if you also want to configure the main webapp (like the 'run' mojo).
     */
    @Parameter(defaultValue = "false")
    protected boolean startMainWebapp;

    final WebApplicationScanBuilder scanBuilder = new WebApplicationScanBuilder();
    final WebApplicationConfigBuilder configBuilder = new WebApplicationConfigBuilder();

    final Map<String, Scanner> scanners = new HashMap<>();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException
    {
        supportedPackagings = Arrays.asList("war", "pom");
        warPluginInfo = new WarPluginInfo(project);
        applyLoggingProperties();
        super.execute();
    }

    /**
     * Verify the configuration given in the pom.
     */
    @Override
    public boolean checkPomConfiguration() throws MojoExecutionException
    {
        // check the location of the static content/jsps etc
        try
        {
            if ((webAppSourceDirectory == null) || !webAppSourceDirectory.exists())
            {
                getLog().info("webAppSourceDirectory" + (webAppSourceDirectory == null ? " not set." : (webAppSourceDirectory.getAbsolutePath() + " does not exist.")) + " Trying " + DEFAULT_WEBAPP_SRC);
                webAppSourceDirectory = new File(project.getBasedir(), DEFAULT_WEBAPP_SRC);
                if (!webAppSourceDirectory.exists())
                {
                    getLog().info("webAppSourceDirectory " + webAppSourceDirectory.getAbsolutePath() + " does not exist. Trying " + project.getBuild().getDirectory() + File.separator + FAKE_WEBAPP);

                    //try last resort of making a fake empty dir
                    File target = new File(project.getBuild().getDirectory());
                    webAppSourceDirectory = new File(target, FAKE_WEBAPP);
                    if (!webAppSourceDirectory.exists())
                        webAppSourceDirectory.mkdirs();
                }
            }
            else
                getLog().info("Webapp source directory = " + webAppSourceDirectory.getCanonicalPath());
        }
        catch (IOException e)
        {
            throw new MojoExecutionException("Webapp source directory does not exist", e);
        }

        // check reload mechanic
        if (!"automatic".equalsIgnoreCase(reload) && !"manual".equalsIgnoreCase(reload))
        {
            throw new MojoExecutionException("invalid reload mechanic specified, must be 'automatic' or 'manual'");
        }
        else
        {
            getLog().info("Reload Mechanic: " + reload);
        }
        getLog().info("nonBlocking:" + nonBlocking);

        // check the classes to form a classpath with
        try
        {
            //allow a webapp with no classes in it (just jsps/html)
            if (classesDirectory != null)
            {
                if (!classesDirectory.exists())
                    getLog().info("Classes directory " + classesDirectory.getCanonicalPath() + " does not exist");
                else
                    getLog().info("Classes = " + classesDirectory.getCanonicalPath());
            }
            else
                getLog().info("Classes directory not set");
        }
        catch (IOException e)
        {
            throw new MojoExecutionException("Location of classesDirectory does not exist");
        }

        return true;
    }

    @Override
    public void finishConfigurationBeforeStart() throws Exception
    {
        server.setStopAtShutdown(true); //as we will normally be stopped with a cntrl-c, ensure server stopped 
        super.finishConfigurationBeforeStart();
    }

    @Override
    public void configureWebApplication() throws Exception
    {
        removeAnnotationConfiguration();

        deployWebApplications();

        if (startMainWebapp) {
            super.configureWebApplication();
            configureMainWebApplication();
        }
    }

    /**
     * Copied from the previous configureWebApplication, this is the same code used by {@link JettyRunMojo#configureWebApplication()}.
     *
     * @throws Exception if something is wrong.
     */
    private void configureMainWebApplication() throws Exception {
        //Set up the location of the webapp.
        //There are 2 parts to this: setWar() and setBaseResource(). On standalone jetty,
        //the former could be the location of a packed war, while the latter is the location
        //after any unpacking. With this mojo, you are running an unpacked, unassembled webapp,
        //so the two locations should be equal.
        Resource webAppSourceDirectoryResource = Resource.newResource(webAppSourceDirectory.getCanonicalPath());
        if (webApp.getWar() == null)
            webApp.setWar(webAppSourceDirectoryResource.toString());

        //The first time we run, remember the original base dir
        if (originalBaseResource == null)
        {
            if (webApp.getBaseResource() == null)
                originalBaseResource = webAppSourceDirectoryResource;
            else
                originalBaseResource = webApp.getBaseResource();
        }

        //On every subsequent re-run set it back to the original base dir before
        //we might have applied any war overlays onto it
        webApp.setBaseResource(originalBaseResource);

        if (classesDirectory != null)
            webApp.setClasses(classesDirectory);
        if (useTestScope && (testClassesDirectory != null))
            webApp.setTestClasses(testClassesDirectory);

        MavenProjectHelper mavenProjectHelper = new MavenProjectHelper(project);
        List<File> webInfLibs = getWebInfLibArtifacts(project.getArtifacts()).stream()
                                                                             .map(a ->
                                                                             {
                                                                                 Path p = mavenProjectHelper.getArtifactPath(a);
                                                                                 getLog().debug("Artifact " + a.getId() + " loaded from " + p + " added to WEB-INF/lib");
                                                                                 return p.toFile();
                                                                             }).collect(Collectors.toList());
        getLog().debug("WEB-INF/lib initialized (at root)");
        webApp.setWebInfLib(webInfLibs);

        //if we have not already set web.xml location, need to set one up
        if (webApp.getDescriptor() == null)
        {
            //Has an explicit web.xml file been configured to use?
            if (webXml != null)
            {
                try (Resource r = Resource.newResource(webXml)) {
                    if (r.exists() && !r.isDirectory()) {
                        webApp.setDescriptor(r.toString());
                    }
                }
            }

            //Still don't have a web.xml file: try the resourceBase of the webapp, if it is set
            if (webApp.getDescriptor() == null && webApp.getBaseResource() != null)
            {
                //noinspection resource
                Resource r = webApp.getBaseResource().addPath("WEB-INF/web.xml");
                if (r.exists() && !r.isDirectory())
                {
                    webApp.setDescriptor(r.toString());
                }
            }

            //Still don't have a web.xml file: finally try the configured static resource directory if there is one
            if (webApp.getDescriptor() == null && (webAppSourceDirectory != null))
            {
                File f = new File(new File(webAppSourceDirectory, "WEB-INF"), "web.xml");
                if (f.exists() && f.isFile())
                {
                    webApp.setDescriptor(f.getCanonicalPath());
                }
            }
        }

        //process any overlays and the war type artifacts
        List<Overlay> overlays = getOverlays();
        new OverlayUnpacker(project, webApp, getLog()).unpackOverlays(overlays); //this sets up the base resource collection

        getLog().info("web.xml file = " + webApp.getDescriptor());
        getLog().info("Webapp directory = " + webAppSourceDirectory.getCanonicalPath());
    }

    private void removeAnnotationConfiguration() {
        // the AnnotationConfiguration will warn about the same classes defined multiple times
        // and we have plenty of them...so for this mojo we will remove the annotation discovery.
        // the annotations is something specific to Jetty but we normally deploy to tomcat so it
        // makes sense just to disable it, for reference this is the documentation for the feature
        // in jetty 9: http://www.eclipse.org/jetty/documentation/jetty-9/index.html#annotations
        final String[] configurationClasses = (String[]) server.getAttribute(Configuration.ATTR);
        if (configurationClasses != null) {
            final List<String> confClass = new ArrayList<>(Arrays.asList(configurationClasses));
            confClass.remove("org.eclipse.jetty.annotations.AnnotationConfiguration");
            server.setAttribute(Configuration.ATTR, confClass.toArray(new String[] {}));
        }
    }

    private void deployWebApplications() throws Exception {
        scanners.clear();
        Set<String> subprojects = new HashSet<>();

        final List<String> projectJars = new ArrayList<>();
        for (MavenProject subProject : session.getProjects()) {
            if (subProject.equals(project)) {
                continue;
            }
            if ("jar".equals(subProject.getPackaging())) {
                projectJars.add(subProject.getGroupId() + ":" + subProject.getArtifactId());
            }
        }
        getLog().debug("projectJars " + projectJars);
        final Map<String, JettyWebAppContext> contextMap = new HashMap<>();
        for (MavenProject subProject : session.getProjects()) {
            if (subProject.equals(project)) {
                continue;
            }
            final String projectId = subProject.getGroupId() + ":" + subProject.getArtifactId();
            if ("war".equals(subProject.getPackaging()) && !isAnExcludedWebApp(subProject)) {
                final JettyWebAppContext webAppConfig = configBuilder.configureWebApplication(
                    contextMap.getOrDefault(projectId, new JettyWebAppContext()),
                    subProject,
                    getLog());
                contextMap.putIfAbsent(projectId, webAppConfig);
                subprojects.add(webAppConfig.getContextPath());

                final List<File> allFiles = removeDependencyJars(webAppConfig, subProject);

                getLog().info("\n=========================================================================="
                    + "\nInjecting : " + subProject.getName() + "\n\n" +  configBuilder.toInfoString(webAppConfig)
                    + "\n==========================================================================");

                addWebApplication(webAppConfig);

                if (getScanIntervalSeconds() > 0) {
                    final List<File> scanningFiles = new ArrayList<>(allFiles);
                    Optional.ofNullable(webAppConfig.getClasses())
                            .ifPresent(scanningFiles::add);
                    FilesHelper.removeDuplicates(scanningFiles);

                    getLog().debug("Scanning: " + scanningFiles);

                    final Scanner scanner = new Scanner();
                    scanner.addListener((BulkListener) changes -> {
                        try {
                            getLog().info("Detected changes: " + changes);

                            scanners.get(projectId).stop();

                            getLog().info("Stopping webapp " + projectId + " ...");
                            contextMap.get(projectId).stop();

                            getLog().info("Reconfiguring webapp " + projectId + " ...");

                            final JettyWebAppContext appConfig = configBuilder.configureWebApplication(
                                contextMap.getOrDefault(projectId, new JettyWebAppContext()),
                                subProject,
                                getLog());
                            removeDependencyJars(appConfig, subProject);

                            getLog().info("Restarting webapp " + projectId + " ...");
                            appConfig.start();
                            scanners.get(projectId).start();
                            getLog().info("Restart " + projectId + " completed at " + new Date());
                        } catch (Exception e) {
                            getLog().error("Error reconfiguring/restarting webapp " + projectId + " after change in watched files", e);
                        }
                    });

                    scanner.setReportExistingFilesOnStartup(false);
                    scanner.setScanInterval(getScanIntervalSeconds());
                    scanner.setScanDirs(scanningFiles);
                    //scanner.setRecursive(true);
                    scanner.setScanDepth(Scanner.MAX_SCAN_DEPTH);

                    getLog().debug("Scanning: " + scanner.getScannables());

                    scanner.start();
                    scanners.put(projectId, scanner);
                }
            }
        }

        if (externalArtifactContextHandlers != null) {
            configureWarArtifactsForExtraContextHandlers(subprojects);
        }

        getLog().info("Starting scanner at interval of " + getScanIntervalSeconds() + " seconds.");
    }

    @Override
    public void configureScanner()
        throws MojoExecutionException
    {
        try
        {
            gatherScannables();
        }
        catch (Exception e)
        {
            throw new MojoExecutionException("Error forming scan list", e);
        }
    }

    public void gatherScannables() throws Exception
    {
        if (webApp.getDescriptor() != null)
        {
            Resource r = Resource.newResource(webApp.getDescriptor());
            scanner.addFile(r.getFile().toPath());
        }

        if (webApp.getJettyEnvXml() != null)
            scanner.addFile(new File(webApp.getJettyEnvXml()).toPath());

        if (webApp.getDefaultsDescriptor() != null)
        {
            if (!WebAppContext.WEB_DEFAULTS_XML.equals(webApp.getDefaultsDescriptor()))
                scanner.addFile(new File(webApp.getDefaultsDescriptor()).toPath());
        }

        if (webApp.getOverrideDescriptor() != null)
        {
            scanner.addFile(new File(webApp.getOverrideDescriptor()).toPath());
        }

        File jettyWebXmlFile = findJettyWebXmlFile(new File(webAppSourceDirectory, "WEB-INF"));
        if (jettyWebXmlFile != null)
        {
            scanner.addFile(jettyWebXmlFile.toPath());
        }

        //make sure each of the war artifacts is added to the scanner
        for (Artifact a : getWarArtifacts())
        {
            File f = a.getFile();
            if (a.getFile().isDirectory())
                scanner.addDirectory(f.toPath());
            else
                scanner.addFile(f.toPath());
        }

        //handle the explicit extra scan targets
        if (scanTargets != null)
        {
            for (File f : scanTargets)
            {
                if (f.isDirectory())
                {
                    scanner.addDirectory(f.toPath());
                }
                else
                    scanner.addFile(f.toPath());
            }
        }

        scanner.addFile(project.getFile().toPath());

        //handle the extra scan patterns
        if (scanTargetPatterns != null)
        {
            for (ScanTargetPattern p : scanTargetPatterns)
            {
                IncludeExcludeSet<PathMatcher, Path> includesExcludes = scanner.addDirectory(p.getDirectory().toPath());
                p.configureIncludesExcludeSet(includesExcludes);
            }
        }

        if (webApp.getTestClasses() != null && webApp.getTestClasses().exists())
        {
            Path p = webApp.getTestClasses().toPath();
            IncludeExcludeSet<PathMatcher, Path> includeExcludeSet = scanner.addDirectory(p);

            if (scanTestClassesPattern != null)
            {
                for (String s : scanTestClassesPattern.getExcludes())
                {
                    if (!s.startsWith("glob:"))
                        s = "glob:" + s;
                    includeExcludeSet.exclude(p.getFileSystem().getPathMatcher(s));
                }
                for (String s : scanTestClassesPattern.getIncludes())
                {
                    if (!s.startsWith("glob:"))
                        s = "glob:" + s;
                    includeExcludeSet.include(p.getFileSystem().getPathMatcher(s));
                }
            }
        }

        if (webApp.getClasses() != null && webApp.getClasses().exists())
        {
            Path p = webApp.getClasses().toPath();
            IncludeExcludeSet<PathMatcher, Path> includeExcludes = scanner.addDirectory(p);
            if (scanClassesPattern != null)
            {
                for (String s : scanClassesPattern.getExcludes())
                {
                    if (!s.startsWith("glob:"))
                        s = "glob:" + s;
                    includeExcludes.exclude(p.getFileSystem().getPathMatcher(s));
                }

                for (String s : scanClassesPattern.getIncludes())
                {
                    if (!s.startsWith("glob:"))
                        s = "glob:" + s;
                    includeExcludes.include(p.getFileSystem().getPathMatcher(s));
                }
            }
        }

        if (webApp.getWebInfLib() != null)
        {
            for (File f : webApp.getWebInfLib())
            {
                if (f.isDirectory())
                    scanner.addDirectory(f.toPath());
                else
                    scanner.addFile(f.toPath());
            }
        }
    }

    @Override
    public void restartWebApp(boolean reconfigureScanner) throws Exception
    {
        getLog().info("restarting " + webApp);
        getLog().debug("Stopping webapp ...");
        stopScanner();
        webApp.stop();

        getLog().debug("Reconfiguring webapp ...");

        checkPomConfiguration();
        configureWebApplication();

        // check if we need to reconfigure the scanner,
        // which is if the pom changes
        if (reconfigureScanner)
        {
            getLog().info("Reconfiguring scanner after change to pom.xml ...");
            scanner.reset();
            warArtifacts = null;
            configureScanner();
        }

        getLog().debug("Restarting webapp ...");
        webApp.start();
        startScanner();
        getLog().info("Restart completed at " + new Date().toString());
    }

    @Override
    public boolean isScanningEnabled() {
        return startMainWebapp && super.isScanningEnabled();
    }

    private Collection<Artifact> getWebInfLibArtifacts(Set<Artifact> artifacts)
    {
        return artifacts.stream()
            .filter(this::canPutArtifactInWebInfLib)
            .collect(Collectors.toList());
    }

    private boolean canPutArtifactInWebInfLib(Artifact artifact)
    {
        if ("war".equalsIgnoreCase(artifact.getType()))
        {
            return false;
        }
        if (Artifact.SCOPE_PROVIDED.equals(artifact.getScope()))
        {
            return false;
        }
        return !Artifact.SCOPE_TEST.equals(artifact.getScope()) || useTestScope;
    }

    private List<Overlay> getOverlays()
        throws Exception
    {
        //get copy of a list of war artifacts
        Set<Artifact> matchedWarArtifacts = new HashSet<>();
        List<Overlay> overlays = new ArrayList<>();
        for (OverlayConfig config : warPluginInfo.getMavenWarOverlayConfigs())
        {
            //overlays can be individually skipped
            if (config.isSkip())
                continue;

            //an empty overlay refers to the current project - important for ordering
            if (config.isCurrentProject())
            {
                Overlay overlay = new Overlay(config, null);
                overlays.add(overlay);
                continue;
            }

            //if a war matches an overlay config
            Artifact a = getArtifactForOverlay(config, getWarArtifacts());
            if (a != null)
            {
                matchedWarArtifacts.add(a);
                SelectiveJarResource r = new SelectiveJarResource(new URL("jar:" + Resource.toURL(a.getFile()).toString() + "!/"));
                r.setIncludes(config.getIncludes());
                r.setExcludes(config.getExcludes());
                Overlay overlay = new Overlay(config, r);
                overlays.add(overlay);
            }
        }

        //iterate over the left over war artifacts and unpack them (without include/exclude processing) as necessary
        for (Artifact a : getWarArtifacts())
        {
            if (!matchedWarArtifacts.contains(a))
            {
                Overlay overlay = new Overlay(null, Resource.newResource(new URL("jar:" + Resource.toURL(a.getFile()).toString() + "!/")));
                overlays.add(overlay);
            }
        }
        return overlays;
    }

    private List<Artifact> getWarArtifacts()
    {
        if (warArtifacts != null)
            return warArtifacts;

        warArtifacts = new ArrayList<>();
        for (Artifact artifact : projectArtifacts)
        {
            if (artifact.getType().equals("war") || artifact.getType().equals("zip"))
            {
                try
                {
                    warArtifacts.add(artifact);
                    getLog().info("Dependent war artifact " + artifact.getId());
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
            }
        }
        return warArtifacts;
    }

    protected Artifact getArtifactForOverlay(OverlayConfig o, List<Artifact> warArtifacts)
    {
        if (o == null || warArtifacts == null || warArtifacts.isEmpty())
            return null;

        for (Artifact a : warArtifacts)
        {
            if (o.matchesArtifact(a.getGroupId(), a.getArtifactId(), a.getClassifier()))
            {
                return a;
            }
        }

        return null;
    }

    protected String getJavaBin()
    {
        String[] javaexes = new String[]
            {"java", "java.exe"};

        File javaHomeDir = new File(System.getProperty("java.home"));
        for (String javaexe : javaexes)
        {
            File javabin = new File(javaHomeDir, fileSeparators("bin/" + javaexe));
            if (javabin.exists() && javabin.isFile())
            {
                return javabin.getAbsolutePath();
            }
        }

        return "java";
    }

    public static String fileSeparators(String path)
    {
        StringBuilder ret = new StringBuilder();
        for (char c : path.toCharArray())
        {
            if ((c == '/') || (c == '\\'))
            {
                ret.append(File.separatorChar);
            }
            else
            {
                ret.append(c);
            }
        }
        return ret.toString();
    }

    /**
     * Resolve an Artifact from remote repo if necessary.
     *
     * @param groupId the groupId of the artifact
     * @param artifactId the artifactId of the artifact
     * @param version the version of the artifact
     * @param type the extension type of the artifact eg "zip", "jar"
     * @return the artifact from the local or remote repo
     * @throws ArtifactResolverException in case of an error while resolving the artifact
     */
    public Artifact resolveArtifact(final String groupId,
                                    final String artifactId,
                                    final String version,
                                    final String type)
        throws ArtifactResolverException
    {
        DefaultArtifactCoordinate coordinate = new DefaultArtifactCoordinate();
        coordinate.setGroupId(groupId);
        coordinate.setArtifactId(artifactId);
        coordinate.setVersion(version);
        coordinate.setExtension(type);

        ProjectBuildingRequest buildingRequest =
            new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());

        buildingRequest.setRemoteRepositories(remoteRepositories);

        return artifactResolver.resolveArtifact(buildingRequest, coordinate).getArtifact();
    }

    private List<File> removeDependencyJars(final JettyWebAppContext webAppConfig,
                                            final MavenProject subProject) throws Exception {
        List<File> dependencyOutputLocations = new ArrayList<>();
        List<File> excludedFiles = new ArrayList<>();
        Set<Artifact> artifacts = subProject.getArtifacts();

        for (Artifact artifact : artifacts) {
            MavenProject artifactProject = getLocalDownstreamProjectForDependency(artifact, getProject());
            if (artifactProject != null) {
                final File file = new File(artifactProject.getBuild().getOutputDirectory());
                if (file.exists()) {
                    dependencyOutputLocations.add(file);
                    excludedFiles.add(artifact.getFile());
                } else {
                    getLog().debug("Dependency " + file.getAbsolutePath() + " does not exists!");
                }
            }
        }

        getLog().debug("dependencyOutputLocations " + dependencyOutputLocations);
        getLog().debug("excludedFiles " + excludedFiles);

        List<File> files =
            scanBuilder.setupScannerFiles(webAppConfig,
                Collections.singletonList(subProject.getFile()),
                Collections.emptyList());

        final List<File> allFiles = new ArrayList<>();
        allFiles.addAll(dependencyOutputLocations); // JUST ADDED THIS
        allFiles.addAll(webAppConfig.getWebInfClasses());
        allFiles.addAll(files);

        FilesHelper.removeList(allFiles, excludedFiles);

        webAppConfig.setWebInfLib(allFiles);
        FilesHelper.removeDuplicates(webAppConfig.getWebInfLib());
        FilesHelper.removeList(webAppConfig.getWebInfLib(), excludedFiles);

        getLog().debug("webInfLib " + webAppConfig.getWebInfLib());

        return allFiles;
    }

    private void configureWarArtifactsForExtraContextHandlers(final Set<String> skipContexts)
        throws Exception
    {
        for (Handler contextHandler : externalArtifactContextHandlers) {
            if (contextHandler instanceof org.mortbay.jetty.plugin.JettyWebAppContext) {
                getLog().warn("This class " + contextHandler.getClass().getName() + " is deprecated! You should " +
                    "use " + JettyWebAppContext.class.getName());
            }
            if (contextHandler instanceof JettyWebAppContext) {
                JettyWebAppContext jettyContext = (JettyWebAppContext) contextHandler;

                ArtifactData warArtifact = jettyContext.getWarArtifact();

                if (warArtifact != null) {
                    if (skipContexts.contains(jettyContext.getContextPath())) {
                        getLog().info(String.format("Not deploying '%s' for context '%s' since it is already handled by sub-project",
                            warArtifact, jettyContext.getContextPath()));
                        continue;
                    }

                    final Artifact artifact = resolveArtifact(
                        warArtifact.groupId,
                        warArtifact.artifactId,
                        warArtifact.version,
                        warArtifact.type
                    );

                    final File warFile = artifact.getFile();
                    jettyContext.setWar(warFile.getAbsolutePath());
                    addWebApplication(jettyContext);

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

    private void addWebApplication(WebAppContext webapp) throws Exception {
        ServerSupport.addWebApplication(getServer(), webapp);
    }

    private int getScanIntervalSeconds() {
        return scanIntervalSeconds;
    }

    private MavenProject getProject() {
        return project;
    }

    private Server getServer() {
        return server;
    }

    private void applyLoggingProperties()
        throws MojoFailureException
    {
        try {
            if (!loggingProperties.isEmpty()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                loggingProperties.store(baos, "Logging properties");
                try (InputStream is = new ByteArrayInputStream(baos.toByteArray())) {
                    LogManager.getLogManager().readConfiguration(is);
                }
            } else if (loggingPropertiesFile != null) {
                try (InputStream is = new BufferedInputStream(Files.newInputStream(loggingPropertiesFile.toPath()))) {
                    LogManager.getLogManager().readConfiguration(is);
                }
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

}
