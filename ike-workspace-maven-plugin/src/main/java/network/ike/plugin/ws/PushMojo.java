package network.ike.plugin.ws;

import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;

/**
 * Push with a VCS bridge catch-up preamble.
 *
 * <p>Syncs local git state first (if behind), then pushes.
 * Sets {@code IKE_VCS_CONTEXT} so the pre-push hook allows new
 * ref creation if needed.
 *
 * <p>Usage: {@code mvnw ws:push}
 */
@Mojo(name = "push", requiresProject = false, threadSafe = true)
public class PushMojo extends AbstractWorkspaceMojo {

    /** Creates this goal instance. */
    public PushMojo() {}

    /**
     * Remote name to push to.
     */
    @Parameter(property = "remote", defaultValue = "origin")
    String remote;

    @Override
    public void execute() throws MojoExecutionException {
        File dir = new File(System.getProperty("user.dir"));

        getLog().info("");
        getLog().info("IKE VCS Bridge — Push");
        getLog().info("══════════════════════════════════════════════════════════════");

        VcsOperations.catchUp(dir, getLog());

        String branch = VcsOperations.currentBranch(dir);
        getLog().info("  Pushing to " + remote + "/" + branch + "...");
        VcsOperations.push(dir, getLog(), remote, branch);

        VcsOperations.writeVcsState(dir, VcsState.ACTION_PUSH);

        getLog().info("");
        getLog().info("  Done.");
        getLog().info("");
    }
}
