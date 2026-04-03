package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;

import network.ike.workspace.ManifestWriter;
import network.ike.workspace.WorkspaceGraph;
import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Abandon a feature branch across all workspace components.
 *
 * <p>Checks out the default branch (or a specified target branch) in
 * each component that is currently on the feature branch, then deletes
 * the feature branch locally. Optionally deletes the remote branches
 * as well.
 *
 * <p>Components are processed in reverse topological order (downstream
 * first) to avoid transient dependency issues.
 *
 * <p>Safety: warns about unmerged commits before deleting. Use
 * {@code -Dforce=true} to suppress the warning and force-delete.
 *
 * <pre>{@code
 * mvn ike:feature-abandon -Dfeature=my-experiment
 * mvn ike:feature-abandon -Dfeature=dead-end -DdeleteRemote=true
 * mvn ike:feature-abandon -Dfeature=stale -Dforce=true
 * }</pre>
 *
 * @see FeatureStartMojo for creating feature branches
 */
@Mojo(name = "feature-abandon", requiresProject = false, threadSafe = true)
public class FeatureAbandonMojo extends AbstractWorkspaceMojo {

    /**
     * Feature name. The branch {@code feature/<name>} will be abandoned.
     */
    @Parameter(property = "feature")
    String feature;

    /**
     * Restrict to a named group (or single component). Default: all.
     */
    @Parameter(property = "group")
    String group;

    /**
     * Branch to check out after abandoning. Defaults to the workspace
     * default branch (usually {@code main}).
     */
    @Parameter(property = "targetBranch")
    String targetBranch;

    /**
     * Also delete remote feature branches on origin.
     */
    @Parameter(property = "deleteRemote", defaultValue = "false")
    boolean deleteRemote;

    /**
     * Show what would happen without making changes.
     */
    @Parameter(property = "dryRun", defaultValue = "false")
    boolean dryRun;

    /**
     * Force-delete branches even if they have unmerged commits.
     */
    @Parameter(property = "force", defaultValue = "false")
    boolean force;

    /** Creates this goal instance. */
    public FeatureAbandonMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        feature = requireParam(feature, "feature",
                "Feature name (branch will be feature/<name>)");
        String branchName = "feature/" + feature;

        if (!isWorkspaceMode()) {
            executeBareMode(branchName);
            return;
        }

        executeWorkspaceMode(branchName);
    }

    private void executeWorkspaceMode(String branchName)
            throws MojoExecutionException {

        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();
        Path manifestPath = resolveManifest();

        // Resolve target branch from parameter or workspace defaults
        if (targetBranch == null || targetBranch.isBlank()) {
            targetBranch = graph.manifest().defaults().branch();
            if (targetBranch == null) targetBranch = "main";
        }

        Set<String> targets;
        if (group != null && !group.isEmpty()) {
            targets = graph.expandGroup(group);
        } else {
            targets = graph.manifest().components().keySet();
        }

        // Reverse topological order — downstream first
        List<String> sorted = graph.topologicalSort(new LinkedHashSet<>(targets));
        List<String> reversed = new ArrayList<>(sorted);
        Collections.reverse(reversed);

        getLog().info("");
        getLog().info("IKE Workspace — Feature Abandon");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Feature:  " + feature);
        getLog().info("  Branch:   " + branchName + " → " + targetBranch);
        getLog().info("  Scope:    " + (group != null ? group : "all")
                + " (" + reversed.size() + " components)");
        if (deleteRemote) getLog().info("  Remote:   will delete origin/" + branchName);
        if (dryRun) getLog().info("  Mode:     DRY RUN");
        getLog().info("");

        List<String> abandoned = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (String name : reversed) {
            File dir = new File(root, name);
            File gitDir = new File(dir, ".git");

            if (!gitDir.exists()) {
                getLog().info("  · " + name + " — not cloned, skipping");
                skipped.add(name);
                continue;
            }

            String currentBranch = gitBranch(dir);
            if (!currentBranch.equals(branchName)) {
                getLog().info("  · " + name + " — not on " + branchName
                        + " (on " + currentBranch + "), skipping");
                skipped.add(name);
                continue;
            }

            // Check for uncommitted changes
            String status = gitStatus(dir);
            if (!status.isEmpty()) {
                throw new MojoExecutionException(
                        name + " has uncommitted changes. Commit, stash, or discard before abandoning.");
            }

            // Warn about unmerged commits
            if (!force && !dryRun) {
                try {
                    String unmerged = ReleaseSupport.execCapture(dir,
                            "git", "log", "--oneline",
                            targetBranch + ".." + branchName);
                    if (!unmerged.isBlank()) {
                        long commitCount = unmerged.lines().count();
                        getLog().warn("  ⚠ " + name + " has " + commitCount
                                + " unmerged commit(s) on " + branchName + ":");
                        unmerged.lines().limit(5).forEach(
                                line -> getLog().warn("      " + line));
                        if (commitCount > 5) {
                            getLog().warn("      ... and " + (commitCount - 5) + " more");
                        }
                    }
                } catch (MojoExecutionException e) {
                    // Target branch may not exist locally — not fatal
                    getLog().debug("Could not check unmerged commits: " + e.getMessage());
                }
            }

            if (dryRun) {
                getLog().info("  [dry-run] " + name + " — would abandon " + branchName
                        + " → " + targetBranch);
                abandoned.add(name);
                continue;
            }

            getLog().info("  ✕ " + name + " — abandoning " + branchName);

            // Switch to target branch
            VcsOperations.checkout(dir, getLog(), targetBranch);

            // Delete local feature branch
            VcsOperations.deleteBranch(dir, getLog(), branchName);
            getLog().info("    deleted local branch: " + branchName);

            // Optionally delete remote branch
            if (deleteRemote) {
                try {
                    VcsOperations.deleteRemoteBranch(dir, getLog(), "origin", branchName);
                    getLog().info("    deleted remote branch: origin/" + branchName);
                } catch (MojoExecutionException e) {
                    getLog().warn("    could not delete remote branch: " + e.getMessage());
                }
            }

            VcsOperations.writeVcsState(dir, VcsState.ACTION_FEATURE_FINISH);
            abandoned.add(name);
        }

        // Update workspace.yaml and workspace repo
        if (!abandoned.isEmpty() && !dryRun) {
            abandonWorkspaceRepo(manifestPath, abandoned, branchName);
        }

        getLog().info("");
        getLog().info("  Abandoned: " + abandoned.size()
                + " | Skipped: " + skipped.size());
        if (!deleteRemote && !abandoned.isEmpty()) {
            getLog().info("  Remote branches kept. Use -DdeleteRemote=true to delete them.");
        }
        getLog().info("");
    }

    /**
     * Bare-mode: abandon feature branch in the current repo only.
     */
    private void executeBareMode(String branchName) throws MojoExecutionException {
        File dir = new File(System.getProperty("user.dir"));

        if (targetBranch == null || targetBranch.isBlank()) {
            targetBranch = "main";
        }

        getLog().info("");
        getLog().info("IKE Feature Abandon (bare repo)");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Feature: " + feature);
        getLog().info("  Branch:  " + branchName + " → " + targetBranch);
        getLog().info("");

        String currentBranch = gitBranch(dir);
        if (!currentBranch.equals(branchName)) {
            throw new MojoExecutionException(
                    "Not on " + branchName + " (currently on " + currentBranch + ")");
        }
        if (!gitStatus(dir).isEmpty()) {
            throw new MojoExecutionException(
                    "Uncommitted changes. Commit, stash, or discard first.");
        }

        if (dryRun) {
            getLog().info("  [dry-run] Would abandon " + branchName + " → " + targetBranch);
            return;
        }

        VcsOperations.checkout(dir, getLog(), targetBranch);
        VcsOperations.deleteBranch(dir, getLog(), branchName);
        getLog().info("  Deleted local branch: " + branchName);

        if (deleteRemote) {
            try {
                VcsOperations.deleteRemoteBranch(dir, getLog(), "origin", branchName);
                getLog().info("  Deleted remote branch: origin/" + branchName);
            } catch (MojoExecutionException e) {
                getLog().warn("  Could not delete remote branch: " + e.getMessage());
            }
        }

        VcsOperations.writeVcsState(dir, VcsState.ACTION_FEATURE_FINISH);
        getLog().info("  Done.");
        getLog().info("");
    }

    /**
     * Revert workspace.yaml branches back to targetBranch and clean up
     * the workspace repo's feature branch.
     */
    private void abandonWorkspaceRepo(Path manifestPath,
                                       List<String> components,
                                       String branchName)
            throws MojoExecutionException {
        try {
            // Update workspace.yaml branch fields back to target
            Map<String, String> updates = new LinkedHashMap<>();
            for (String name : components) {
                updates.put(name, targetBranch);
            }
            ManifestWriter.updateBranches(manifestPath, updates);
            getLog().info("  Updated workspace.yaml branches → " + targetBranch);

            File wsRoot = manifestPath.getParent().toFile();
            if (!new File(wsRoot, ".git").exists()) return;

            // If workspace repo is on the feature branch, switch back
            String wsBranch = gitBranch(wsRoot);
            if (wsBranch.equals(branchName)) {
                // Commit yaml changes on feature branch first, then switch
                ReleaseSupport.exec(wsRoot, getLog(), "git", "add", "workspace.yaml");
                VcsOperations.commit(wsRoot, getLog(),
                        "workspace: revert branches for abandon " + branchName);

                VcsOperations.checkout(wsRoot, getLog(), targetBranch);

                // Cherry-pick the workspace.yaml update to target branch
                try {
                    ReleaseSupport.exec(wsRoot, getLog(),
                            "git", "cherry-pick", branchName);
                } catch (MojoExecutionException e) {
                    // If cherry-pick fails, just update directly
                    ManifestWriter.updateBranches(manifestPath, updates);
                    ReleaseSupport.exec(wsRoot, getLog(), "git", "add", "workspace.yaml");
                    VcsOperations.commit(wsRoot, getLog(),
                            "workspace: revert branches after abandon " + branchName);
                }

                // Delete workspace feature branch
                VcsOperations.deleteBranch(wsRoot, getLog(), branchName);
                getLog().info("  Deleted workspace feature branch: " + branchName);

                if (deleteRemote) {
                    try {
                        VcsOperations.deleteRemoteBranch(wsRoot, getLog(), "origin", branchName);
                    } catch (MojoExecutionException e) {
                        getLog().warn("  Could not delete workspace remote branch: " + e.getMessage());
                    }
                }
            } else {
                // Already on target — just commit the yaml update
                ReleaseSupport.exec(wsRoot, getLog(), "git", "add", "workspace.yaml");
                VcsOperations.commit(wsRoot, getLog(),
                        "workspace: revert branches after abandon " + branchName);
            }

            VcsOperations.pushSafe(wsRoot, getLog(), "origin", targetBranch);

        } catch (IOException e) {
            getLog().warn("  Could not update workspace.yaml: " + e.getMessage());
        }
    }
}
