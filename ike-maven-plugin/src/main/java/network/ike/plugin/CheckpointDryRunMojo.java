package network.ike.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Preview a single-repo checkpoint without running a build, writing
 * files, or creating tags.
 *
 * <p>This is a convenience goal equivalent to
 * {@code ike:checkpoint -DdryRun=true}. It shows the checkpoint version
 * that would be derived and the steps that would be executed.
 *
 * <pre>{@code
 * mvn ike:checkpoint-dry-run
 * mvn ike:checkpoint-dry-run -DcheckpointLabel=1.2.3-checkpoint.2026-01-01.1
 * }</pre>
 *
 * @see CheckpointMojo the full single-repo checkpoint goal
 * @see WsCheckpointDryRunMojo the workspace-level dry-run
 */
@Mojo(name = "checkpoint-dry-run", requiresProject = false,
        aggregator = true, threadSafe = true)
public class CheckpointDryRunMojo extends CheckpointMojo {

    /** Creates this goal instance. */
    public CheckpointDryRunMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        dryRun = true;
        super.execute();
    }
}
