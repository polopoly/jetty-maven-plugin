//========================================================================
//$Id$
//Copyright 2000-2004 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//Licensed under the Apache License, Version 2.0 (the "License");
//you may not use this file except in compliance with the License.
//You may obtain a copy of the License at
//http://www.apache.org/licenses/LICENSE-2.0
//Unless required by applicable law or agreed to in writing, software
//distributed under the License is distributed on an "AS IS" BASIS,
//WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//See the License for the specific language governing permissions and
//limitations under the License.
//========================================================================


package org.mortbay.jetty.plugin;


import org.apache.maven.plugin.MojoExecutionException;
import org.codehaus.plexus.util.FileUtils;
import org.eclipse.jetty.util.Scanner;
import org.eclipse.jetty.xml.XmlConfiguration;

import java.io.File;
import java.io.FileInputStream;
import java.util.*;



/**
 * AbstractJettyMojo
 *
 *
 */
public abstract class AbstractJettyMojo extends AbstractEmbeddedJettyMojo
{


    /**
     * The "virtual" webapp created by the plugin
     * @parameter
     */
    protected JettyWebAppContext webAppConfig;


    /**
     * The context path for the webapp. Defaults to the
     * name of the webapp's artifact.
     *
     * @parameter expression="/${project.artifactId}"
     * @required
     * @readonly
     */
    protected String contextPath;


    /**
     * The temporary directory to use for the webapp.
     * Defaults to target/tmp
     *
     * @parameter expression="${project.build.directory}/tmp"
     * @required
     * @readonly
     */
    protected File tmpDirectory;


    /**
     * File containing system properties to be set before execution
     * 
     * Note that these properties will NOT override System properties
     * that have been set on the command line, by the JVM, or directly 
     * in the POM via systemProperties. Optional.
     * 
     * @parameter expression="${jetty.systemPropertiesFile}"
     */
    protected File systemPropertiesFile;


    /**
     * Location of a context xml configuration file whose contents
     * will be applied to the webapp AFTER anything in &lt;webAppConfig&gt;.Optional.
     * @parameter
     */
    protected String webAppXml;

    /**
     * A scanner to check for changes to the webapp
     */
    protected Scanner scanner;
    
    /**
     *  List of files and directories to scan
     */
    protected ArrayList<File> scanList;
    
    /**
     * List of Listeners for the scanner
     */
    protected ArrayList<Scanner.BulkListener> scannerListeners;


    public abstract void configureScanner () throws MojoExecutionException;
    


    public File getTmpDirectory()
    {
        return this.tmpDirectory;
    }

    /**
     * @return Returns the contextPath.
     */
    public String getContextPath()
    {
        return this.contextPath;
    }

    /**
     * @return returns the path to the systemPropertiesFile
     */
    public File getSystemPropertiesFile()
    {
        return this.systemPropertiesFile;
    }
    
    public void setSystemPropertiesFile(File file) throws Exception
    {
        this.systemPropertiesFile = file;
        FileInputStream propFile = new FileInputStream(systemPropertiesFile);
        Properties properties = new Properties();
        properties.load(propFile);
        
        if (this.systemProperties == null )
            this.systemProperties = new SystemProperties();
        
        for (Enumeration keys = properties.keys(); keys.hasMoreElements();  )
        {
            String key = (String)keys.nextElement();
            if ( ! systemProperties.containsSystemProperty(key) )
            {
                SystemProperty prop = new SystemProperty();
                prop.setKey(key);
                prop.setValue(properties.getProperty(key));
                
                this.systemProperties.setSystemProperty(prop);
            }
        }
        
    }
    
    public void setSystemProperties(SystemProperties systemProperties)
    {
        if (this.systemProperties == null)
            this.systemProperties = systemProperties;
        else
        {
            Iterator itor = systemProperties.getSystemProperties().iterator();
            while (itor.hasNext())
            {
                SystemProperty prop = (SystemProperty)itor.next();
                this.systemProperties.setSystemProperty(prop);
            }   
        }
    }


    public void setScanList (ArrayList<File> list)
    {
        this.scanList = new ArrayList<File>(list);
    }

    public ArrayList<File> getScanList ()
    {
        return this.scanList;
    }


    public void setScannerListeners (ArrayList<Scanner.BulkListener> listeners)
    {
        this.scannerListeners = new ArrayList<Scanner.BulkListener>(listeners);
    }

    public ArrayList getScannerListeners ()
    {
        return this.scannerListeners;
    }

    public Scanner getScanner ()
    {
        return scanner;
    }


    @Override
    public void deployWebApplications() throws Exception {

        configureWebApplication();
        getServer().addWebApplication(webAppConfig);

        // start the scanner thread (if necessary) on the main webapp
        configureScanner();
        startScanner();
    }



    /**
     * Subclasses should invoke this to setup basic info
     * on the webapp
     * 
     * @throws MojoExecutionException
     */
    public void configureWebApplication () throws Exception
    {
        //As of jetty-7, you must use a <webAppConfig> element
        if (webAppConfig == null)
            webAppConfig = new JettyWebAppContext();
        
        //Apply any context xml file to set up the webapp
        //CAUTION: if you've defined a <webAppConfig> element then the
        //context xml file can OVERRIDE those settings
        if (webAppXml != null)
        {
            File file = FileUtils.getFile(webAppXml);
            XmlConfiguration xmlConfiguration = new XmlConfiguration(file.toURL());
            getLog().info("Applying context xml file "+webAppXml);
            xmlConfiguration.configure(webAppConfig);
        }

        
        //If no contextPath was specified, go with our default
        String cp = webAppConfig.getContextPath();
        if (cp == null || "".equals(cp))
        {
            webAppConfig.setContextPath((contextPath.startsWith("/") ? contextPath : "/"+ contextPath));
        }

        //If no tmp directory was specified, and we have one, use it
        if (webAppConfig.getTempDirectory() == null && tmpDirectory != null)
        {
            if (!tmpDirectory.exists())
                tmpDirectory.mkdirs();
            
            webAppConfig.setTempDirectory(tmpDirectory);
        }
      
        getLog().info("Context path = " + webAppConfig.getContextPath());
        getLog().info("Tmp directory = "+ (webAppConfig.getTempDirectory()== null? " determined at runtime": webAppConfig.getTempDirectory()));
        getLog().info("Web defaults = "+(webAppConfig.getDefaultsDescriptor()==null?" jetty default":webAppConfig.getDefaultsDescriptor()));
        getLog().info("Web overrides = "+(webAppConfig.getOverrideDescriptor()==null?" none":webAppConfig.getOverrideDescriptor()));
    }

    /**
     * Run a scanner thread on the given list of files and directories, calling
     * stop/start on the given list of LifeCycle objects if any of the watched
     * files change.
     *
     */
    private void startScanner() throws Exception
    {
        // check if scanning is enabled
        if (getScanIntervalSeconds() <= 0) return;

        // check if reload is manual. It disables file scanning
        if ( "manual".equalsIgnoreCase( reload ) )
        {
            // issue a warning if both scanIntervalSeconds and reload
            // are enabled
            getLog().warn("scanIntervalSeconds is set to " + getScanIntervalSeconds() + " but will be IGNORED due to manual reloading");
            return;
        }

        scanner = new Scanner();
        scanner.setReportExistingFilesOnStartup(false);
        scanner.setScanInterval(getScanIntervalSeconds());
        scanner.setScanDirs(getScanList());
        scanner.setRecursive(true);
        List listeners = getScannerListeners();
        Iterator itor = (listeners==null?null:listeners.iterator());
        while (itor!=null && itor.hasNext())
            scanner.addListener((Scanner.Listener)itor.next());
        getLog().info("Starting scanner at interval of " + getScanIntervalSeconds()+ " seconds.");
        scanner.start();
    }

    /**
     * Try and find a jetty-web.xml file, using some
     * historical naming conventions if necessary.
     * @param webInfDir
     * @return the jetty web xml file
     */
    public File findJettyWebXmlFile (File webInfDir)
    {
        if (webInfDir == null)
            return null;
        if (!webInfDir.exists())
            return null;

        File f = new File (webInfDir, "jetty-web.xml");
        if (f.exists())
            return f;

        //try some historical alternatives
        f = new File (webInfDir, "web-jetty.xml");
        if (f.exists())
            return f;
        
        return null;
    }
}
