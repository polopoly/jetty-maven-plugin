package org.mortbay.jetty.plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.xml.XmlConfiguration;

/**
 * AbstractEmbeddedJettyMojo
 *
 * Created by bitter on 2011-08-31
 */
public abstract class AbstractEmbeddedJettyMojo extends AbstractMojo {

    public final String PORT_SYSPROPERTY = "jetty.port";

    /**
     * A wrapper for the Server object
     */
    private JettyServer server;

    /**
     * List of connectors to use. If none are configured
     * then the default is a single SelectChannelConnector at port 8080. You can
     * override this default port number by using the system property jetty.port
     * on the command line, eg:  mvn -Djetty.port=9999 jetty:run
     *
     * @parameter
     */
    private Connector[] connectors;


    /**
     * List of security realms to set up. Optional.
     * @parameter
     */
    private LoginService[] loginServices;

    /**
     * A RequestLog implementation to use for the webapp at runtime.
     * Optional.
     * @parameter
     */
    private RequestLog requestLog;

    /**
     * Location of a jetty xml configuration file whose contents
     * will be applied before any plugin configuration. Optional.
     * @parameter
     */
    private String jettyConfig;

    /**
     * Port to listen to stop jetty on executing -DSTOP.PORT=&lt;stopPort&gt;
     * -DSTOP.KEY=&lt;stopKey&gt; -jar start.jar --stop
     * @parameter
     */
    private int stopPort;

    /**
     * Key to provide when stopping jetty on executing java -DSTOP.KEY=&lt;stopKey&gt;
     * -DSTOP.PORT=&lt;stopPort&gt; -jar start.jar --stop
     * @parameter
     */
    private String stopKey;

    /**
     * <p>
     * Determines whether or not the server blocks when started. The default
     * behavior (daemon = false) will cause the server to pause other processes
     * while it continues to handle web requests. This is useful when starting the
     * server with the intent to work with it interactively.
     * </p><p>
     * Often, it is desirable to let the server start and continue running subsequent
     * processes in an automated build environment. This can be facilitated by setting
     * daemon to true.
     * </p>
     * @parameter expression="${jetty.daemon}" default-value="false"
     */
    private boolean daemon;

    /**
     * @parameter expression="${jetty.skip}" default-value="false"
     */
    private boolean skip;

    /**
     * A scanner to check ENTER hits on the console
     */
    private Thread consoleScanner;

    /**
     * The maven project.
     *
     * @parameter expression="${executedProject}"
     * @required
     * @readonly
     */
    private MavenProject project;
    /**
     * reload can be set to either 'automatic' or 'manual'
     *
     * if 'manual' then the context can be reloaded by a linefeed in the console
     * if 'automatic' then traditional reloading on changed files is enabled.
     *
     * @parameter expression="${jetty.reload}" default-value="automatic"
     */
    protected String reload;
    /**
     * System properties to set before execution.
     * Note that these properties will NOT override System properties
     * that have been set on the command line or by the JVM. They WILL
     * override System properties that have been set via systemPropertiesFile.
     * Optional.
     * @parameter
     */
    protected SystemProperties systemProperties;

    /**
     * The interval in seconds to scan the webapp for changes
     * and restart the context if necessary. Ignored if reload
     * is enabled. Disabled by default.
     *
     * @parameter expression="${jetty.scanIntervalSeconds}" default-value="0"
     * @required
     */
    private int scanIntervalSeconds;

    public abstract void deployWebApplications() throws Exception;
    public abstract void restartWebApplications(boolean reconfigureScanner) throws Exception;

    public MavenProject getProject()
    {
        return this.project;
    }

    public void execute() throws MojoExecutionException, MojoFailureException
    {
        getLog().info("Configuring Jetty for project: " + getProject().getName());
        if (skip)
        {
            getLog().info("Skipping Jetty start: jetty.skip==true");
            return;
        }
        PluginLog.setLog(getLog());
        checkPomConfiguration();
        startJetty();
    }

    public void startJetty() throws MojoExecutionException {
        try
        {
            getLog().debug("Starting Jetty Server ...");

            printSystemProperties();
            this.server = new JettyServer();

            //apply any config from a jetty.xml file first which is able to
            //be overwritten by config in the pom.xml
            applyJettyXml ();

            // if the user hasn't configured their project's pom to use a
            // different set of connectors,
            // use the default
            Connector[] connectors = this.server.getConnectors();
            if (connectors == null|| connectors.length == 0)
            {
                //try using ones configured in pom
                this.server.setConnectors(this.connectors);

                connectors = this.server.getConnectors();
                if (connectors == null || connectors.length == 0)
                {
                    //if a SystemProperty -Djetty.port=<portnum> has been supplied, use that as the default port
                    this.connectors = new Connector[] { this.server.createDefaultConnector(System.getProperty(PORT_SYSPROPERTY, null)) };
                    this.server.setConnectors(this.connectors);
                }
            }


            //set up a RequestLog if one is provided
            if (this.requestLog != null)
                getServer().setRequestLog(this.requestLog);

            //set up the webapp and any context provided
            this.server.configureHandlers();
            
            // set up security realms
            for (int i = 0; (this.loginServices != null) && i < this.loginServices.length; i++)
            {
                getLog().debug(this.loginServices[i].getClass().getName() + ": "+ this.loginServices[i].toString());
                getServer().addBean(this.loginServices[i]);
            }

            // deploy web applications
            deployWebApplications();

            // start Jetty
            this.server.start();

            getLog().info("Started Jetty Server");

            if(stopPort>0 && stopKey!=null)
            {
                Monitor monitor = new Monitor(stopPort, stopKey, new Server[]{server}, !daemon);
                monitor.start();
            }


            // start the new line scanner thread if necessary
            startConsoleScanner();


            // keep the thread going if not in daemon mode
            if (!daemon )
            {
                server.join();
            }
        }
        catch (Exception e)
        {
            throw new MojoExecutionException("Failure", e);
        }
        finally
        {
            if (!daemon )
            {
                getLog().info("Jetty server exiting.");
            }
        }
    }

    public List<File> getJettyXmlFiles()
    {
        if ( this.jettyConfig == null )
        {
            return null;
        }

        List<File> jettyXmlFiles = new ArrayList<File>();

        if ( this.jettyConfig.indexOf(',') == -1 )
        {
            jettyXmlFiles.add( new File( this.jettyConfig ) );
        }
        else
        {
            String[] files = this.jettyConfig.split(",");

            for ( String file : files )
            {
                jettyXmlFiles.add( new File(file) );
            }
        }

        return jettyXmlFiles;
    }

    public void applyJettyXml() throws Exception
    {
        if (getJettyXmlFiles() == null)
            return;

        for ( File xmlFile : getJettyXmlFiles() )
        {
            getLog().info( "Configuring Jetty from xml configuration file = " + xmlFile.getCanonicalPath() );
            XmlConfiguration xmlConfiguration = new XmlConfiguration(xmlFile.toURI().toURL());
            xmlConfiguration.configure(this.getServer());
        }
    }

    public JettyServer getServer ()
    {
        return this.server;
    }

    /**
     * @return Returns the scanIntervalSeconds.
     */
    public int getScanIntervalSeconds()
    {
        return this.scanIntervalSeconds;
    }

    /**
     * Run a thread that monitors the console input to detect ENTER hits.
     */
    protected void startConsoleScanner() throws Exception
    {
        if ( "manual".equalsIgnoreCase( reload ) )
        {
            getLog().info("Console reloading is ENABLED. Hit ENTER on the console to restart the context.");
            consoleScanner = new ConsoleScanner(this);
            consoleScanner.start();
        }
    }

    private void printSystemProperties ()
    {
        // print out which system properties were set up
        if (getLog().isDebugEnabled())
        {
            if (systemProperties != null)
            {
                Iterator itor = systemProperties.getSystemProperties().iterator();
                while (itor.hasNext())
                {
                    SystemProperty prop = (SystemProperty)itor.next();
                    getLog().debug("Property "+prop.getName()+"="+prop.getValue()+" was "+ (prop.isSet() ? "set" : "skipped"));
                }
            }
        }
    }

    public void checkPomConfiguration() throws MojoExecutionException {
        // check reload mechanic
        if ( !"automatic".equalsIgnoreCase( reload ) && !"manual".equalsIgnoreCase( reload ) )
        {
            throw new MojoExecutionException( "invalid reload mechanic specified, must be 'automatic' or 'manual'" );
        }
        else
        {
            getLog().info("Reload Mechanic: " + reload );
        }
    }

    protected void setDaemon(boolean daemon) {
        this.daemon = daemon;
    }
}
