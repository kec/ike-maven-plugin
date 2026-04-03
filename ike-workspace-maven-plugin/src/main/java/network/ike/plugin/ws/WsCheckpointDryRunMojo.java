package network.ike.plugin.ws;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Preview a workspace checkpoint without writing files or creating tags.
 *
 * <p>This is a convenience goal equivalent to
 * {@code ike:ws-checkpoint -DdryRun=true}. It prompts for the checkpoint
 * name interactively, making it suitable for double-clicking in an IDE
 * Maven tool window.
 *
 * <p>Shows the complete checkpoint YAML that would be written and all
 * tags that would be created, without making any changes.
 *
 * <pre>{@code
 * mvn ike:ws-checkpoint-dry-run
 * mvn ike:ws-checkpoint-dry-run -Dname=sprint-42
 * mvn ike:ws-checkpoint-dry-run -Dname=sprint-42 -Dtag=true
 * }</pre>
 */
@Mojo(name = "checkpoint-dry-run", requiresProject = false, threadSafe = true)
public class WsCheckpointDryRunMojo extends WsCheckpointMojo {

    /** Creates this goal instance. */
    public WsCheckpointDryRunMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        dryRun = true;
        super.execute();
    }
}
