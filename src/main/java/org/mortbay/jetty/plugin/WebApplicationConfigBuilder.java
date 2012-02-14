package org.mortbay.jetty.plugin;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

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
import org.eclipse.jetty.util.resource.Resource;

/**
 * Created by bitter on 2011-08-31
 */
public class WebApplicationConfigBuilder
{
    private static final Logger LOG = Logger.getLogger(WebApplicationConfigBuilder.class.getName());

    BeanConfigurator beanConfigurator = new DefaultBeanConfigurator();

    public JettyWebAppContext configureWebApplication(MavenProject project, Log log)
        throws Exception
    {
        JettyWebAppContext webAppConfig = new JettyWebAppContext();

        Plugin plugin = project.getPlugin("com.polopoly.jetty:jetty-maven-plugin");
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

        webAppConfig.setWebInfClasses(new ArrayList<File>() {{ add(classesDirectory); }});
        addDependencies(project, webAppConfig);

        //if we have not already set web.xml location, need to set one up
        if (webAppConfig.getDescriptor() == null)
        {
            //Still don't have a web.xml file: try the resourceBase of the webapp, if it is set
            if (webAppConfig.getDescriptor() == null && webAppConfig.getBaseResource() != null)
            {
                Resource r = webAppConfig.getBaseResource().addPath("WEB-INF/web.xml");
                if (r.exists() && !r.isDirectory())
                {
                    webAppConfig.setDescriptor(r.toString());
                }
            }

            //Still don't have a web.xml file: finally try the configured static resource directory if there is one
            if (webAppConfig.getDescriptor() == null && (webAppSourceDirectory != null))
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
                                 final JettyWebAppContext webAppConfig)
        throws Exception
    {
        List<File> dependencyFiles = new ArrayList<File>();
        List<Resource> overlays = new ArrayList<Resource>();

        for (Artifact artifact : project.getArtifacts())
        {
            if (artifact.getType().equals("war")) {
                overlays.add(Resource.newResource("jar:"+artifact.getFile().toURL().toString()+"!/"));
            } else if ((!Artifact.SCOPE_PROVIDED.equals(artifact.getScope()))
                    && (!Artifact.SCOPE_TEST.equals( artifact.getScope())))
            {
                File dependencyFile = artifact.getFile();

                if (dependencyFile == null || !dependencyFile.exists()) {
                    String coordinates = String.format("%s:%s:%s",
                                                       artifact.getGroupId(),
                                                       artifact.getArtifactId(),
                                                       artifact.getVersion());

                    LOG.log(Level.WARNING, "Dependency '" + coordinates + "' does not exist in repository. Skipping!");
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
                (webAppConfig.getWebInfClasses()       == null ? "none" : webAppConfig.getWebInfClasses()),
                (webAppConfig.getDescriptor()          == null ? "none" : webAppConfig.getDescriptor()));
    }
}
