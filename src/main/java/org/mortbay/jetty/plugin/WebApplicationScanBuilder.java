package org.mortbay.jetty.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.eclipse.jetty.util.resource.Resource;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by bitter on 2011-08-31
 */
public class WebApplicationScanBuilder {

    public List<File> setupScannerFiles(JettyWebAppContext webAppConfig, List<File> extraFiles, List<String> extraResources)
        throws MojoExecutionException
    {
        final ArrayList<File> scanList = new ArrayList<File>();
        addToList(webAppConfig.getDescriptor(), scanList);
        addToList(webAppConfig.getJettyEnvXml(), scanList);
        addToList(webAppConfig.getDefaultsDescriptor(), scanList);
        addToList(webAppConfig.getOverrideDescriptors(), scanList);
        addToList(extraResources, scanList);

        addFilesToList(webAppConfig.getWebInfLib(), scanList);
        addFilesToList(extraFiles, scanList);

        return scanList;
    }

    private void addToList(List<String> items, ArrayList<File> scanList)
            throws MojoExecutionException
    {
        if (items != null) {
            for (String item : items) {
                addToList(item, scanList);
            }
        }
    }

    private void addToList(String item, ArrayList<File> scanList)
            throws MojoExecutionException
    {
        if (item != null) {
            try {
                addToList(Resource.newResource(item), scanList);
            } catch (IOException e) {
                throw new MojoExecutionException("Problem configuring scanner for " + item, e);
            }
        }
    }

    private void addToList(Resource item, ArrayList<File> scanList)
            throws MojoExecutionException
    {
        if (item != null) {
            try {
                addToList(item.getFile(), scanList);
            } catch (IOException e) {
                throw new MojoExecutionException("Problem configuring scanner for " + item, e);
            }
        }
    }

    private void addFilesToList(List<File> items, ArrayList<File> scanList) {
        if (items != null) {
            scanList.addAll(items);
        }
    }

    private void addToList(File item, ArrayList<File> scanList) {
        if (item != null) {
            scanList.add(item);
        }
    }
}
