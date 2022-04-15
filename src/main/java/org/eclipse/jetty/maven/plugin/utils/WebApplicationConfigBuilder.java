package org.eclipse.jetty.maven.plugin.utils;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.configuration.BeanConfigurationException;
import org.apache.maven.configuration.BeanConfigurationRequest;
import org.apache.maven.configuration.BeanConfigurator;
import org.apache.maven.configuration.DefaultBeanConfigurationRequest;
import org.apache.maven.configuration.internal.DefaultBeanConfigurator;
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

    public JettyWebAppContext configureWebApplication(final JettyWebAppContext webAppConfig,
                                                      final MavenProject project,
                                                      final Log log)
        throws Exception
    {
        Plugin plugin = project.getPlugin("com.polopoly.jetty:jetty-maven-plugin");
        if (plugin == null) {
            final String msg = String.format(
                    "Project '%s:%s' does not have the plugin 'com.polopoly.jetty:jetty-maven-plugin' configured",
                    project.getGroupId(),
                    project.getArtifactId()
            );
            log.error(msg);
            throw new RuntimeException(msg);
        }
        Xpp3Dom config = (Xpp3Dom) plugin.getConfiguration();
        applyPOMWebAppConfig(config, webAppConfig);

        if (webAppConfig.getContextPath() == null || webAppConfig.getContextPath().length() < 1) {
            webAppConfig.setContextPath("/" + project.getArtifactId());
        }

        final File baseDir = project.getBasedir();
        final File webAppSourceDirectory = new File(baseDir, "src/main/webapp");
        final File classesDirectory = new File(baseDir, "target/classes");

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
        addDependencies(project, log, webAppConfig);

        //if we have not already set web.xml location, need to set one up
        if (webAppConfig.getDescriptor() == null)
        {
            //Still don't have a web.xml file: try the resourceBase of the webapp, if it is set
            final Resource baseResource = webAppConfig.getBaseResource();
            if (webAppConfig.getDescriptor() == null && baseResource != null)
            {
                Resource r = baseResource.addPath("WEB-INF" + File.pathSeparator + "web.xml");
                if (r.exists() && !r.isDirectory())
                {
                    webAppConfig.setDescriptor(r.toString());
                }
            }

            //Still don't have a web.xml file: finally try the configured static resource directory if there is one
            if (webAppConfig.getDescriptor() == null)
            {
                File f = new File (new File (webAppSourceDirectory, "WEB-INF"), "web.xml");
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

    private void applyPOMWebAppConfig(final Xpp3Dom configuration,
                                      final JettyWebAppContext webAppConfig)
        throws BeanConfigurationException
    {
        BeanConfigurationRequest beanConfigurationRequest = new DefaultBeanConfigurationRequest();
        beanConfigurationRequest.setBean(webAppConfig).setConfiguration(configuration.getChild("webAppConfig"));
        beanConfigurator.configureBean(beanConfigurationRequest);
    }

    private void addDependencies(final MavenProject project,
                                 final Log log,
                                 final JettyWebAppContext webAppConfig)
        throws Exception
    {
        List<File> dependencyFiles = new ArrayList<>();
        List<Overlay> overlays = new ArrayList<>();

        for (Artifact artifact : project.getArtifacts())
        {
            final OverlayConfig config = new OverlayConfig();
            if (artifact.getType().equals("war")) {
                SelectiveJarResource r = new SelectiveJarResource(new URL("jar:" + Resource.toURL(artifact.getFile()) + "!/"));
                r.setIncludes(config.getIncludes());
                r.setExcludes(config.getExcludes());
                Overlay overlay = new Overlay(config, r);
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
