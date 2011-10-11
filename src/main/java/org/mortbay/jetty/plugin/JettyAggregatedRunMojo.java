package org.mortbay.jetty.plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.util.Scanner;

/**
 * Created by bitter on 2011-08-31
 * 
 * @aggregator
 * @goal run-all
 * @requiresDependencyResolution runtime
 * @description Runs embedded jetty and deploys war submodules
 */
public class JettyAggregatedRunMojo extends AbstractEmbeddedJettyMojo {

    final WebApplicationScanBuilder scanBuilder = new WebApplicationScanBuilder();
    final WebApplicationConfigBuilder configBuilder = new WebApplicationConfigBuilder();

    final List<Scanner> scanners = new ArrayList<Scanner>();

    /**
     * @parameter expression="${session}"
     * @required
     */
    private MavenSession session;

    @Override
    public void deployWebApplications() throws Exception {
        scanners.clear();
        for (MavenProject subProject : session.getProjects()) {
            if ("war".equals(subProject.getPackaging())) {
                final JettyWebAppContext webAppConfig = configBuilder.configureWebApplication(subProject, getLog());
                getLog().info("\n=========================================================================="
                        + "\nInjecting : " + subProject.getName() + "\n\n" +  configBuilder.toInfoString(webAppConfig)
                        + "\n==========================================================================");
                getServer().addWebApplication(webAppConfig);

                // TODO:
                //scanList.addAll(getExtraScanTargets());

                List<File> files = scanBuilder.setupScannerFiles(webAppConfig,
                        Arrays.asList(subProject.getFile()),
                        Collections.<String>emptyList());
                webAppConfig.setClassPathFiles(files);

                getLog().info("Scanning: " + files);
                
                Scanner scanner = new Scanner();
                scanner.addListener(new Scanner.BulkListener() {
                    public void filesChanged(List changes) {
                        try {
                            getLog().info("Detected changes: " + changes);
                            webAppConfig.stop();
                            webAppConfig.start();
                        } catch (Exception e) {
                            getLog().error("Error reconfiguring/restarting webapp after change in watched files", e);
                        }
                    }
                });
                scanner.setReportExistingFilesOnStartup(false);
                scanner.setScanInterval(getScanIntervalSeconds());
                scanner.setScanDirs(files);
                scanner.setRecursive(true);
                scanner.start();
                scanners.add(scanner);
            }
        }
    }

    @Override
    public void restartWebApplications(boolean reconfigureScanner) throws Exception {
        Handler context = getServer().getHandler();
        context.stop();
        context.start();
    }

    private void log(Object o) {
        getLog().info("JettyAggregatedRunMojo: " + o);
    }
}
