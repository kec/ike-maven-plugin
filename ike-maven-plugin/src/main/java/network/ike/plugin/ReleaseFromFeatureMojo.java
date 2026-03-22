package network.ike.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Create a release from a feature branch.
 *
 * <p>Not yet implemented. Use the bash script fallback:
 * {@code target/build-tools/scripts/release-from-feature.sh}
 */
@Mojo(name = "release-from-feature", requiresProject = false, threadSafe = true)
public class ReleaseFromFeatureMojo extends AbstractMojo {

    @Override
    public void execute() throws MojoExecutionException {
        getLog().warn("ike:release-from-feature is not yet implemented.");
        getLog().info("Fallback: target/build-tools/scripts/release-from-feature.sh");
    }
}
