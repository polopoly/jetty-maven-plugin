//========================================================================
//$Id$
//Copyright 2009 Mort Bay Consulting Pty. Ltd.
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

import java.io.File;
import java.io.IOException;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.webapp.WebAppClassLoader;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebInfConfiguration;

public class MavenWebInfConfiguration extends WebInfConfiguration
{
    protected Resource _originalResourceBase;
    protected Resource[]  _unpackedOverlays;
  
    
    public void configure(WebAppContext context) throws Exception
    {
        JettyWebAppContext jwac = (JettyWebAppContext)context;
        if (jwac.getClassPathFiles() != null)
        {
            if (Log.isDebugEnabled()) Log.debug("Setting up classpath ...");

            //put the classes dir and all dependencies into the classpath
            Iterator itor = jwac.getClassPathFiles().iterator();
            while (itor.hasNext())
                ((WebAppClassLoader)context.getClassLoader()).addClassPath(((File)itor.next()).getCanonicalPath());

            if (Log.isDebugEnabled())
                Log.debug("Classpath = "+LazyList.array2List(((URLClassLoader)context.getClassLoader()).getURLs()));
        }
        super.configure(context);

        // knock out environmental maven and plexus classes from webAppContext
        String[] existingServerClasses = context.getServerClasses();
        String[] newServerClasses = new String[2+(existingServerClasses==null?0:existingServerClasses.length)];
        newServerClasses[0] = "org.apache.maven.";
        newServerClasses[1] = "org.codehaus.plexus.";
        System.arraycopy( existingServerClasses, 0, newServerClasses, 2, existingServerClasses.length );
        if (Log.isDebugEnabled())
        {
            Log.debug("Server classes:");
            for (int i=0;i<newServerClasses.length;i++)
                Log.debug(newServerClasses[i]);
        }
        context.setServerClasses( newServerClasses ); 
    }


    public void preConfigure(WebAppContext context) throws Exception
    {
        _originalResourceBase = context.getBaseResource();
        JettyWebAppContext jwac = (JettyWebAppContext)context;

        //Add in any overlaid wars as base resources
        if (jwac.getOverlays() != null && !jwac.getOverlays().isEmpty())
        {
            ResourceCollection rc;

            if(jwac.getBaseResource()==null)
            {
                // nothing configured, so we automagically enable the overlays                    
                int size = jwac.getOverlays().size()+1;
                Resource[] resources = new Resource[size];
                _unpackedOverlays = new Resource[size];
                for(int i=0; i<size; i++)
                {
                    if (jwac.getUnpackOverlays())
                    {
                        resources[i] = unpackOverlay(context,jwac.getOverlays().get(i));
                        _unpackedOverlays[i] = resources[i];
                    }
                    else
                        resources[i] =jwac.getOverlays().get(i);

                    Log.info("Adding overlay: " + resources[i]);
                }
                
                rc=new ResourceCollection(resources);
            }                
            else
            {                    
                if(jwac.getBaseResource() instanceof ResourceCollection)
                {
                    // there was a preconfigured ResourceCollection ... append the artifact wars
                    Resource[] old = ((ResourceCollection)jwac.getBaseResource()).getResources();
                    int size = old.length + jwac.getOverlays().size();
                    Resource[] resources = new Resource[size];
                    _unpackedOverlays = new Resource[size];
                    System.arraycopy(old, 0, resources, 0, old.length);
                    for(int i=old.length,j=0; i<size; i++,j++)
                    {
                        if (jwac.getUnpackOverlays())
                        {
                            resources[i] = unpackOverlay(context,jwac.getOverlays().get(j));
                            _unpackedOverlays[i] = resources[i];
                        }
                        else
                            resources[i] = jwac.getOverlays().get(j);

                        Log.info("Adding overlay: " + resources[i]);
                    }
                    rc=new ResourceCollection(resources);
                }
                else
                {
                    int size = jwac.getOverlays().size()+1;
                    Resource[] resources = new Resource[size];
                    _unpackedOverlays = new Resource[size-1];
                    resources[0] = jwac.getBaseResource();
                    for(int i=1; i<size; i++)
                    {
                        if (jwac.getUnpackOverlays())
                        {
                            resources[i] = unpackOverlay(context,jwac.getOverlays().get(i-1));
                            _unpackedOverlays[i-1] = resources[i];
                        }
                        else
                            resources[i] = jwac.getOverlays().get(i-1);
                        
                        Log.info("Adding overlay: " + resources[i]);
                    }
                    rc=new ResourceCollection(resources);
                }
            }

            jwac.setBaseResource(rc);
        }
        super.preConfigure(context);
    }
    
    public void postConfigure(WebAppContext context) throws Exception
    {
        super.postConfigure(context);
    }


    public void deconfigure(WebAppContext context) throws Exception
    {
        JettyWebAppContext jwac = (JettyWebAppContext)context;
        
        //remove the unpacked wars
        if (jwac.getUnpackOverlays() && _unpackedOverlays != null && _unpackedOverlays.length>0)
        {
            try
            {
                for (int i=0; i<_unpackedOverlays.length; i++)
                {
                    IO.delete(_unpackedOverlays[i].getFile());
                }
            }
            catch (IOException e)
            {
                Log.ignore(e);
            }
        }
        super.deconfigure(context);
        //restore whatever the base resource was before we might have included overlaid wars
        context.setBaseResource(_originalResourceBase);
  
    }

  
    /**
     * Get the jars to examine from the files from which we have
     * synthesized the classpath. Note that the classpath is not
     * set at this point, so we cannot get them from the classpath.
     * @param context
     * @return the list of jars found
     */
    protected List<Resource> findJars (WebAppContext context)
    throws Exception
    {
        List<Resource> list = new ArrayList<Resource>();
        JettyWebAppContext jwac = (JettyWebAppContext)context;
        if (jwac.getClassPathFiles() != null)
        {
            for (File f: jwac.getClassPathFiles())
            {
                if (f.getName().toLowerCase().endsWith(".jar"))
                {
                    try
                    {
                        list.add(Resource.newResource(f.toURI()));
                    }
                    catch (Exception e)
                    {
                        Log.warn("Bad url ", e);
                    }
                }
            }
        }
        
        List<Resource> superList = super.findJars(context);
        if (superList != null)
            list.addAll(superList);
        return list;
    }

    protected  Resource unpackOverlay (WebAppContext context, Resource overlay)
    throws IOException
    {
        //resolve if not already resolved
        resolveTempDirectory(context);
        
   
        //Get the name of the overlayed war and unpack it to a dir of the
        //same name in the temporary directory
        String name = overlay.getName();
        if (name.endsWith("!/"))
            name = name.substring(0,name.length()-2);
        int i = name.lastIndexOf('/');
        if (i>0)
            name = name.substring(i+1,name.length());
        name = name.replace('.', '_');
        File dir = new File(context.getTempDirectory(), name);
        overlay.copyTo(dir);
        Resource unpackedOverlay = Resource.newResource(dir.getCanonicalPath());
        return  unpackedOverlay;
    }


}
