package org.eclipse.jetty.maven.plugin.utils;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.configuration.BeanConfigurationException;
import org.apache.maven.configuration.BeanConfigurationRequest;
import org.apache.maven.configuration.BeanConfigurator;
import org.apache.maven.configuration.DefaultBeanConfigurationRequest;
import org.apache.maven.configuration.internal.DefaultBeanConfigurator;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.jetty.maven.plugin.JettyWebAppContext;
import org.eclipse.jetty.maven.plugin.Overlay;
import org.eclipse.jetty.maven.plugin.OverlayConfig;
import org.eclipse.jetty.maven.plugin.SelectiveJarResource;
import org.eclipse.jetty.util.resource.Resource;

public class WebApplicationConfigBuilder
{
    BeanConfigurator beanConfigurator = new DefaultBeanConfigurator();

    private final String pluginName;

    public WebApplicationConfigBuilder(final String pluginName) {
        this.pluginName = pluginName;
    }

    public JettyWebAppContext configureWebApplication(final JettyWebAppContext webAppConfig,
                                                      final MavenSession session,
                                                      final MavenProject project,
                                                      final Log log)
        throws Exception
    {
        Plugin plugin = project.getPlugin(pluginName);
        if (plugin == null) {
            final String msg = String.format(
                "Project '%s:%s' does not have the plugin '%s' configured",
                project.getGroupId(),
                project.getArtifactId(),
                pluginName
            );
            log.error(msg);
            throw new RuntimeException(msg);
        }
        Xpp3Dom config = (Xpp3Dom) plugin.getConfiguration();
        log.debug("plugin config " + config);
        applyPOMWebAppConfig(config, webAppConfig);

        if (webAppConfig.getContextPath() == null || webAppConfig.getContextPath().length() < 1) {
            webAppConfig.setContextPath("/" + project.getArtifactId());
        }

        final File baseDir = project.getBasedir();
        final File webAppSourceDirectory = getWebAppSourceDirectory(log, baseDir);
        final File classesDirectory = new File(baseDir, FilesHelper.toOSPath("target", "classes"));

        Resource webAppSourceDirectoryResource = Resource.newResource(webAppSourceDirectory.getCanonicalPath());
        if (webAppConfig.getWar() == null) {
            webAppConfig.setWar(webAppSourceDirectoryResource.toString());
        }

        if (webAppConfig.getBaseResource() == null) {
            webAppConfig.setBaseResource(webAppSourceDirectoryResource);
        }

        if (classesDirectory.exists()) {
            webAppConfig.setClasses(classesDirectory);
        } else {
            log.debug(classesDirectory + " does not exists");
        }
        addDependencies(session, project, log, webAppConfig);

        //if we have not already set web.xml location, need to set one up
        if (webAppConfig.getDescriptor() == null)
        {
            //Still don't have a web.xml file: try the resourceBase of the webapp, if it is set
            final Resource baseResource = webAppConfig.getBaseResource();
            if (webAppConfig.getDescriptor() == null && baseResource != null)
            {
                Resource r = baseResource.addPath("WEB-INF" + File.separator + "web.xml");
                if (r.exists() && !r.isDirectory())
                {
                    webAppConfig.setDescriptor(r.toString());
                }
            }

            //Still don't have a web.xml file: finally try the configured static resource directory if there is one
            if (webAppConfig.getDescriptor() == null)
            {
                File f = new File(new File(webAppSourceDirectory, "WEB-INF"), "web.xml");
                if (f.exists() && f.isFile())
                {
                    webAppConfig.setDescriptor(f.getCanonicalPath());
                }
            }
        }

        // Turn off some default settings in jetty
        URL overrideWebXMLUrl = this.getClass().getResource("/com/polopoly/web_override.xml");
        if (overrideWebXMLUrl != null) {
            webAppConfig.addOverrideDescriptor(overrideWebXMLUrl.toExternalForm());
        }
        // Increase session-timeout
        URL defaultsWebXMLUrl = this.getClass().getResource("/com/polopoly/webdefaults.xml");
        if (defaultsWebXMLUrl != null) {
            webAppConfig.setDefaultsDescriptor(defaultsWebXMLUrl.toExternalForm());
        }
        return webAppConfig;
    }

    private File getWebAppSourceDirectory(final Log log, final File baseDir) {
        File webAppSourceDirectory = new File(baseDir, FilesHelper.toOSPath("src", "main", "webapp"));
        if (!webAppSourceDirectory.exists()) {
            log.debug(webAppSourceDirectory + " does not exists");
            webAppSourceDirectory = FilesHelper.ensureDirectoryExists(
                new File(baseDir, FilesHelper.toOSPath("target", "webapp")));
            FilesHelper.ensureDirectoryExists(new File(webAppSourceDirectory, "WEB-INF"));
        }
        return webAppSourceDirectory;
    }

    private void applyPOMWebAppConfig(final Xpp3Dom configuration,
                                      final JettyWebAppContext webAppConfig)
        throws BeanConfigurationException
    {
        BeanConfigurationRequest beanConfigurationRequest = new DefaultBeanConfigurationRequest();
        beanConfigurationRequest.setBean(webAppConfig).setConfiguration(configuration.getChild("webAppConfig"));
        beanConfigurator.configureBean(beanConfigurationRequest);
    }

    private void addDependencies(final MavenSession session,
                                 final MavenProject project,
                                 final Log log,
                                 final JettyWebAppContext webAppConfig)
        throws Exception
    {
        List<File> dependencyFiles = new ArrayList<>();
        List<Overlay> overlays = new ArrayList<>();

        final Map<String, MavenProject> projectWars = new HashMap<>();
        for (MavenProject subProject : session.getProjects()) {
            if (subProject.equals(project)) {
                continue;
            }
            if ("war".equals(subProject.getPackaging())) {
                projectWars.put(subProject.getGroupId() + ":" + subProject.getArtifactId(), subProject);
            }
        }
        for (Artifact artifact : project.getArtifacts()) {
            log.debug("Artifact " + artifact);
            final OverlayConfig config = new OverlayConfig();
            if (artifact.getType().equals("war")) {
                SelectiveJarResource r = new SelectiveJarResource(new URL("jar:" + Resource.toURL(artifact.getFile()) + "!/"));
                r.setIncludes(config.getIncludes());
                r.setExcludes(config.getExcludes());
                final Overlay overlay;
                final String key = artifact.getGroupId() + ":" + artifact.getArtifactId();
                if (projectWars.containsKey(key)) {
                    overlay = new Overlay(config, r);
                } else {
                    config.setGroupId(artifact.getGroupId());
                    config.setArtifactId(artifact.getArtifactId());
                    config.setClassifier(artifact.getClassifier());
                    config.setVersion(artifact.getVersion());
                    overlay = new Overlay(config, r);
                }
                overlays.add(overlay);
            } else if ((!Artifact.SCOPE_PROVIDED.equals(artifact.getScope()))
                && (!Artifact.SCOPE_TEST.equals( artifact.getScope())))
            {
                File dependencyFile = artifact.getFile();

                if (dependencyFile == null || !dependencyFile.exists()) {
                    String coordinates = String.format("%s:%s:%s",
                        artifact.getGroupId(),
                        artifact.getArtifactId(),
                        artifact.getVersion());

                    log.warn("Dependency '" + coordinates + "' does not exist in repository. Skipping!");
                    continue;
                }

                dependencyFiles.add(artifact.getFile());
            }
        }

        webAppConfig.setOverlays(overlays);
        webAppConfig.setWebInfLib(dependencyFiles);
        log.debug("overlays " + overlays);
    }

    public String toInfoString(JettyWebAppContext webAppConfig)
    {
        final List<File> classes = new ArrayList<>();
        if (webAppConfig.getClasses() != null) {
            classes.add(webAppConfig.getClasses());
        }
        if (webAppConfig.getWebInfClasses() != null) {
            classes.addAll(webAppConfig.getWebInfClasses());
        }
        FilesHelper.removeDuplicates(classes);
        return String.format("Context path   : %s"
                + "\nWork directory : %s"
                + "\nWeb defaults   : %s"
                + "\nWeb overrides  : %s"
                + "\nWeb source     : %s"
                + "\nWeb classes    : %s"
                + "\nWeb XML        : %s",
            webAppConfig.getContextPath(),
            (webAppConfig.getTempDirectory()       == null ? "determined at runtime" : webAppConfig.getTempDirectory()),
            (webAppConfig.getDefaultsDescriptor()  == null ? "jetty default" : webAppConfig.getDefaultsDescriptor()),
            (webAppConfig.getOverrideDescriptors() == null ? "none" : webAppConfig.getOverrideDescriptors()),
            (webAppConfig.getWar()                 == null ? "none" : webAppConfig.getWar()),
            classes,
            (webAppConfig.getDescriptor()          == null ? "none" : webAppConfig.getDescriptor()));
    }
}
