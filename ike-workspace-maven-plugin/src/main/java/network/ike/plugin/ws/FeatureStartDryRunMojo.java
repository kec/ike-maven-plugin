package network.ike.plugin.ws;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Preview a coordinated feature branch creation without executing.
 *
 * <p>This is a convenience goal equivalent to
 * {@code ike:feature-start -DdryRun=true}. It prompts for any
 * missing parameters interactively, making it suitable for
 * double-clicking in an IDE Maven tool window.
 *
 * <pre>{@code
 * mvn ike:feature-start-dry-run
 * mvn ike:feature-start-dry-run -Dfeature=my-feature
 * }</pre>
 */
@Mojo(name = "feature-start-dry-run", requiresProject = false, threadSafe = true)
public class FeatureStartDryRunMojo extends FeatureStartMojo {

    /** Creates this goal instance. */
    public FeatureStartDryRunMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        dryRun = true;
        super.execute();
    }
}
