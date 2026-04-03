package network.ike.plugin.ws;

import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;

/**
 * Commit with a VCS bridge catch-up preamble.
 *
 * <p>Syncs local git state first (if behind), then commits.
 * Sets {@code IKE_VCS_CONTEXT} so the pre-commit hook allows the
 * operation through without a state file check.
 *
 * <p>Usage: {@code mvnw ws:commit -Dmessage="my commit message"}
 */
@Mojo(name = "commit", requiresProject = false, threadSafe = true)
public class CommitMojo extends AbstractWorkspaceMojo {

    /** Creates this goal instance. */
    public CommitMojo() {}

    /**
     * Commit message. If omitted, git opens the editor and the
     * prepare-commit-msg hook generates a message via Claude.
     */
    @Parameter(property = "message")
    String message;

    /**
     * Stage all changes before committing ({@code git add -A}).
     */
    @Parameter(property = "addAll", defaultValue = "false")
    boolean addAll;

    /**
     * Push to origin after committing.
     */
    @Parameter(property = "push", defaultValue = "false")
    boolean push;

    @Override
    public void execute() throws MojoExecutionException {
        File dir = new File(System.getProperty("user.dir"));

        getLog().info("");
        getLog().info("IKE VCS Bridge — Commit");
        getLog().info("══════════════════════════════════════════════════════════════");

        VcsOperations.catchUp(dir, getLog());

        if (addAll) {
            getLog().info("  Staging all changes...");
            VcsOperations.addAll(dir, getLog());
        }

        if (message != null && !message.isBlank()) {
            getLog().info("  Committing...");
            VcsOperations.commit(dir, getLog(), message);
        } else {
            // No message — open editor (prepare-commit-msg hook will generate)
            getLog().info("  Committing (editor will open for message)...");
            VcsOperations.commitStaged(dir, getLog(), null);
        }

        VcsOperations.writeVcsState(dir, VcsState.ACTION_COMMIT);

        if (push) {
            String branch = VcsOperations.currentBranch(dir);
            getLog().info("  Pushing to origin/" + branch + "...");
            VcsOperations.push(dir, getLog(), "origin", branch);
            VcsOperations.writeVcsState(dir, VcsState.ACTION_PUSH);
        }

        getLog().info("");
        getLog().info("  Done.");
        getLog().info("");
    }
}
