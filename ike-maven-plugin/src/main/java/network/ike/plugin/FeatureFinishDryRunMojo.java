package network.ike.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Preview a coordinated feature branch merge without executing.
 *
 * <p>This is a convenience goal equivalent to
 * {@code ike:feature-finish -DdryRun=true}. It prompts for any
 * missing parameters interactively, making it suitable for
 * double-clicking in an IDE Maven tool window.
 *
 * <pre>{@code
 * mvn ike:feature-finish-dry-run
 * mvn ike:feature-finish-dry-run -Dfeature=my-feature
 * }</pre>
 */
@Mojo(name = "feature-finish-dry-run", requiresProject = false, threadSafe = true)
public class FeatureFinishDryRunMojo extends FeatureFinishMojo {

    /** Creates this goal instance. */
    public FeatureFinishDryRunMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        dryRun = true;
        super.execute();
    }
}
