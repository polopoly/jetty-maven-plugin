package org.eclipse.jetty.maven.plugin.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.eclipse.jetty.maven.plugin.JettyWebAppContext;
import org.eclipse.jetty.maven.plugin.Overlay;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;

/**
 * OverlayUnpacker
 *
 * @author mnova
 */
public class OverlayUnpacker {

    private final MavenProject project;
    private final JettyWebAppContext webApp;
    private final Log log;

    public OverlayUnpacker(final MavenProject project,
                           final JettyWebAppContext webApp,
                           final Log log) {
        this.project = project;
        this.webApp = webApp;
        this.log = log;
    }

    public Log getLog() {
        return log;
    }

    public void unpackOverlays(final List<Overlay> overlays)
        throws Exception
    {
        if (overlays == null || overlays.isEmpty()) {
            return;
        }

        List<Resource> resourceBaseCollection = new ArrayList<>();

        for (final Overlay o : overlays) {
            //can refer to the current project in list of overlays for ordering purposes
            if (o.getConfig() != null && o.getConfig().isCurrentProject() && webApp.getBaseResource().exists()) {
                if (!resourceBaseCollection.contains(webApp.getBaseResource())) {
                resourceBaseCollection.add(webApp.getBaseResource());
                }
                // the overlay may be an overlay of a war build in the project
                final Resource r = o.getResource();
                if (r.exists() && !resourceBaseCollection.contains(r)) {
                    resourceBaseCollection.add(r);
                    // check if the new resource contains a valid web.xml
                    if (StringUtil.isEmpty(webApp.getDescriptor())) {
                        final Resource webXml = r.addPath("WEB-INF").addPath("web.xml");
                        if (webXml.exists()) {
                            getLog().debug("Set web.xml from inner overlay: " + webXml);
                            webApp.setDescriptor(webXml.toString());
                        }
                    }
                }
                continue;
            }

            Resource unpacked = unpackOverlay(o);
            if (unpacked != null) {
                //_unpackedOverlayResources.add(unpacked); //remember the unpacked overlays for later so we can delete the tmp files
                resourceBaseCollection.add(unpacked); //add in the selectively unpacked overlay in the correct order to the webapps resource base
                if (StringUtil.isEmpty(webApp.getDescriptor())) {
                    final Path path = Paths.get(unpacked.getName(), "WEB-INF", "web.xml");
                    final File f = path.toFile();
                    if (f.exists() && f.isFile()) {
                        getLog().debug("Set web.xml from overlay: " + f.getAbsolutePath());
                        webApp.setDescriptor(f.getAbsolutePath());
                    }
                }
            }
        }

        if (webApp.getBaseResource().exists() && !resourceBaseCollection.contains(webApp.getBaseResource())) {
            if (webApp.getBaseAppFirst()) {
                resourceBaseCollection.add(0, webApp.getBaseResource());
            } else {
                resourceBaseCollection.add(webApp.getBaseResource());
            }
        }
        webApp.setBaseResource(new ResourceCollection(resourceBaseCollection.toArray(new Resource[] {})));
    }

    public Resource unpackOverlay(Overlay overlay)
        throws IOException
    {
        if (overlay.getResource() == null)
            return null; //nothing to unpack

        //Get the name of the overlayed war and unpack it to a dir of the
        //same name in the temporary directory
        String name = overlay.getResource().getName();
        if (name.endsWith("!/"))
            name = name.substring(0, name.length() - 2);
        int i = name.lastIndexOf('/');
        if (i > 0)
            name = name.substring(i + 1);
        name = StringUtil.replace(name, '.', '_');
        //name = name+(++COUNTER); //add some digits to ensure uniqueness
        File overlaysDir = new File(project.getBuild().getDirectory(), "jetty_overlays");
        File dir = new File(overlaysDir, name);

        //if specified targetPath, unpack to that subdir instead
        File unpackDir = dir;
        if (overlay.getConfig() != null && overlay.getConfig().getTargetPath() != null)
            unpackDir = new File(dir, overlay.getConfig().getTargetPath());

        //only unpack if the overlay is newer
        if (!unpackDir.exists() || (overlay.getResource().lastModified() > unpackDir.lastModified()))
        {
            boolean made = unpackDir.mkdirs();
            overlay.getResource().copyTo(unpackDir);
        }

        //use top level of unpacked content
        return Resource.newResource(dir.getCanonicalPath());
    }

}
