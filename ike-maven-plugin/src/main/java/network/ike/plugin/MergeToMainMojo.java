package network.ike.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Deprecated — use {@code ike:feature-finish} for multi-repo merge.
 *
 * @deprecated Replaced by {@code ike:feature-finish} which handles
 *             coordinated merge across workspace components.
 */
@Deprecated
@Mojo(name = "merge-to-main", requiresProject = false, threadSafe = true)
public class MergeToMainMojo extends AbstractMojo {

    @Override
    public void execute() throws MojoExecutionException {
        getLog().warn("ike:merge-to-main is deprecated.");
        getLog().info("Use ike:feature-finish for multi-repo merge:");
        getLog().info("  mvn ike:feature-finish -Dfeature=<name> -Dgroup=<group>");
        getLog().info("");
        getLog().info("Run 'mvn ike:help' for full options.");
    }
}
