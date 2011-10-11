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


import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.RequestLog;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.RequestLogHandler;
import org.eclipse.jetty.server.nio.SelectChannelConnector;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.WebAppContext;

/**
 * JettyServer
 * 
 * Maven jetty plugin version of a wrapper for the Server class.
 * 
 */
public class JettyServer extends org.eclipse.jetty.server.Server
{
    public static int DEFAULT_PORT = 8080;
    public static int DEFAULT_MAX_IDLE_TIME = 30000;
  

    private RequestLog requestLog;
    private ContextHandlerCollection contexts;
    
    
    public JettyServer()
    {
        super();
        setStopAtShutdown(true);
        //make sure Jetty does not use URLConnection caches with the plugin
        Resource.setDefaultUseCaches(false);
    }

   
    public void setRequestLog (RequestLog requestLog)
    {
        this.requestLog = requestLog;
    }

    /**
     * @see org.eclipse.jetty.server.Server#doStart()
     */
    public void doStart() throws Exception
    {
        PluginLog.getLog().info("Starting jetty "+getClass().getSuperclass().getPackage().getImplementationVersion()+" ...");
        super.doStart();
    }

 
    /**
     * @see org.eclipse.jetty.server.handler.HandlerCollection#addHandler(org.eclipse.jetty.server.Handler)
     */
    public void addWebApplication(WebAppContext webapp) throws Exception
    {
        addHandler(webapp);
    }


    /**
     * @see org.eclipse.jetty.server.handler.HandlerCollection#addHandler(org.eclipse.jetty.server.Handler)
     */
    public void addHandler(Handler handler) throws Exception {
        contexts.addHandler(handler);
    }
    
    /**
     * Set up the handler structure to receive a webapp.
     * Also put in a DefaultHandler so we get a nice page
     * than a 404 if we hit the root and the webapp's
     * context isn't at root.
     * @throws Exception
     */
    public void configureHandlers () throws Exception 
    {
        DefaultHandler defaultHandler = new DefaultHandler();
        RequestLogHandler requestLogHandler = new RequestLogHandler();
        if (this.requestLog != null)
            requestLogHandler.setRequestLog(this.requestLog);
        
        contexts = (ContextHandlerCollection)super.getChildHandlerByClass(ContextHandlerCollection.class);
        if (contexts==null)
        {   
            contexts = new ContextHandlerCollection();
            HandlerCollection handlers = (HandlerCollection)super.getChildHandlerByClass(HandlerCollection.class);
            if (handlers==null)
            {
                handlers = new HandlerCollection();               
                super.setHandler(handlers);                            
                handlers.setHandlers(new Handler[]{contexts, defaultHandler, requestLogHandler});
            }
            else
            {
                handlers.addHandler(contexts);
            }
        }  
    }
    
    
    
    
    public Connector createDefaultConnector(String portnum) throws Exception
    {
        SelectChannelConnector connector = new SelectChannelConnector();
        connector = new SelectChannelConnector();
        int port = ((portnum==null||portnum.equals(""))?DEFAULT_PORT:Integer.parseInt(portnum.trim()));
        connector.setPort(port);
        connector.setMaxIdleTime(DEFAULT_MAX_IDLE_TIME);
        
        return connector;
    }
    
 
}
