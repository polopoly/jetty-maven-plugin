package org.eclipse.jetty.maven.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.ResolutionScope;

/**
 * Wait for jetty to stop
 */
@Mojo(
    name = "run-all-wait",
    aggregator = true,
    requiresDependencyResolution = ResolutionScope.RUNTIME
)
public class JettyAggregatedStopMojo extends JettyAggregatedRunMojo
{

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException
    {
        PluginLog.setLog(getLog());
        if (useJettyLock) {
            waitLockDisappear();
        }
    }

}
