package network.ike.plugin.ws;

import network.ike.workspace.Component;
import network.ike.workspace.WorkspaceGraph;
import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

import java.io.File;
import java.util.Optional;

/**
 * Reconcile git state after Syncthing delivers changes from another machine.
 *
 * <p>Reads the VCS state file, fetches from all remotes, switches branch
 * if needed, and soft-resets HEAD to match the remote. The working tree
 * is left untouched — Syncthing already made it correct.
 *
 * <p>In workspace mode, syncs the workspace repo first, then each
 * component that has a state file.
 *
 * <p>Usage: {@code mvnw ws:vcs-sync}
 */
@Mojo(name = "vcs-sync", requiresProject = false, threadSafe = true)
public class SyncMojo extends AbstractWorkspaceMojo {

    /** Creates this goal instance. */
    public SyncMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("");
        getLog().info("IKE VCS Bridge — Sync");
        getLog().info("══════════════════════════════════════════════════════════════");

        if (!isWorkspaceMode()) {
            syncBareMode();
            return;
        }

        syncWorkspaceMode();
    }

    private void syncBareMode() throws MojoExecutionException {
        File dir = new File(System.getProperty("user.dir"));
        if (!VcsState.isIkeManaged(dir.toPath())) {
            getLog().info("  Not an IKE-managed repo. Nothing to sync.");
            return;
        }

        Optional<VcsState> state = VcsState.readFrom(dir.toPath());
        if (state.isEmpty()) {
            getLog().info("  No VCS state file. Nothing to sync.");
            return;
        }

        getLog().info("");
        getLog().info("  " + dir.getName());
        VcsOperations.sync(dir, getLog());

        getLog().info("");
        getLog().info("  Sync complete.");
        getLog().info("");
    }

    private void syncWorkspaceMode() throws MojoExecutionException {
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        // Sync workspace repo first
        if (VcsState.isIkeManaged(root.toPath())) {
            getLog().info("");
            getLog().info("  Workspace");
            Optional<VcsState> wsState = VcsState.readFrom(root.toPath());
            if (wsState.isPresent()) {
                VcsOperations.sync(root, getLog());
            } else {
                getLog().info("  No VCS state file — skipping.");
            }
        }

        // Sync each component
        int synced = 0;
        int skipped = 0;

        for (var entry : graph.manifest().components().entrySet()) {
            String name = entry.getKey();
            File compDir = new File(root, name);

            if (!new File(compDir, ".git").exists()) {
                skipped++;
                continue;
            }

            if (!VcsState.isIkeManaged(compDir.toPath())) {
                skipped++;
                continue;
            }

            Optional<VcsState> state = VcsState.readFrom(compDir.toPath());
            if (state.isEmpty()) {
                skipped++;
                continue;
            }

            if (!VcsOperations.needsSync(compDir)) {
                getLog().info("");
                getLog().info("  " + name + ": already in sync ✓");
                continue;
            }

            getLog().info("");
            getLog().info("  " + name);
            VcsOperations.sync(compDir, getLog());
            synced++;
        }

        getLog().info("");
        getLog().info("  Sync complete. " + synced + " component(s) synced, "
                + skipped + " skipped.");
        getLog().info("");
    }
}
