package network.ike.plugin.ws;

import network.ike.workspace.Component;
import network.ike.workspace.WorkspaceGraph;
import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * No-fast-forward merge of a feature branch, preserving full history.
 *
 * <p>Creates a merge commit on the target branch containing the
 * complete feature branch history. The feature branch is <b>kept alive</b>
 * by default because histories stay connected — the branch can
 * continue to receive work and be merged again later.
 *
 * <p>When to use: long-lived feature branches that periodically merge
 * intermediate work to the target branch. Use when you need
 * traceability of individual feature commits on the target branch.
 *
 * <pre>{@code
 * mvn ike:feature-finish-merge -Dfeature=long-running
 * mvn ike:feature-finish-merge -Dfeature=done-feature -DkeepBranch=false
 * }</pre>
 *
 * @see FeatureFinishSquashMojo for clean single-commit merges (default)
 * @see FeatureFinishRebaseMojo for linear history
 */
@Mojo(name = "feature-finish-merge", requiresProject = false, threadSafe = true)
public class FeatureFinishMergeMojo extends AbstractWorkspaceMojo {

    /** Creates this goal instance. */
    public FeatureFinishMergeMojo() {}

    @Parameter(property = "feature")
    String feature;

    @Parameter(property = "group")
    String group;

    @Parameter(property = "targetBranch", defaultValue = "main")
    String targetBranch;

    /**
     * Keep the feature branch after merge. Default is true because
     * no-ff merge preserves history — the branch can continue to
     * receive work and be merged again.
     */
    @Parameter(property = "keepBranch", defaultValue = "true")
    boolean keepBranch = true;

    @Parameter(property = "message")
    String message;

    @Parameter(property = "dryRun", defaultValue = "false")
    boolean dryRun;

    @Override
    public void execute() throws MojoExecutionException {
        feature = requireParam(feature, "feature",
                "Feature name (expects branch feature/<name>)");
        String branchName = "feature/" + feature;

        if (message == null || message.isBlank()) {
            message = "Merge " + branchName + " into " + targetBranch;
        }

        if (!isWorkspaceMode()) {
            executeBareMode(branchName);
            return;
        }

        executeWorkspaceMode(branchName);
    }

    private void executeWorkspaceMode(String branchName) throws MojoExecutionException {
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();
        Path manifestPath = resolveManifest();

        var targets = group != null && !group.isEmpty()
                ? graph.expandGroup(group)
                : graph.manifest().components().keySet();
        List<String> sorted = graph.topologicalSort(new LinkedHashSet<>(targets));
        List<String> reversed = new ArrayList<>(sorted);
        Collections.reverse(reversed);

        getLog().info("");
        getLog().info("IKE Workspace — Feature Finish (merge)");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Feature:  " + feature);
        getLog().info("  Branch:   " + branchName + " → " + targetBranch);
        getLog().info("  Strategy: no-fast-forward merge");
        if (dryRun) getLog().info("  Mode:     DRY RUN");
        getLog().info("");

        VcsOperations.catchUp(root, getLog());

        List<String> eligible = new ArrayList<>();
        for (String name : reversed) {
            String reason = FeatureFinishSupport.validateComponent(
                    root, name, branchName, this);
            if (reason == null) {
                eligible.add(name);
            } else if ("DIRTY".equals(reason)) {
                throw new MojoExecutionException(
                        name + " has uncommitted changes. Commit or stash first.");
            } else {
                getLog().info("  · " + name + " — " + reason + ", skipping");
            }
        }

        if (eligible.isEmpty()) {
            getLog().info("  No components on " + branchName + " — nothing to do.");
            return;
        }

        int merged = 0;
        for (String name : eligible) {
            File dir = new File(root, name);

            if (dryRun) {
                getLog().info("  [dry-run] " + name + " — would merge → " + targetBranch);
                merged++;
                continue;
            }

            getLog().info("  → " + name);
            VcsOperations.catchUp(dir, getLog());
            VcsOperations.checkout(dir, getLog(), targetBranch);
            VcsOperations.mergeNoFf(dir, getLog(), branchName, message);
            VcsOperations.pushSafe(dir, getLog(), "origin", targetBranch);

            if (!keepBranch) {
                FeatureFinishSupport.deleteBranch(dir, getLog(), branchName);
            }

            VcsOperations.writeVcsState(dir, VcsState.ACTION_FEATURE_FINISH);
            merged++;
        }

        if (merged > 0 && !dryRun) {
            FeatureFinishSupport.updateWorkspaceYaml(
                    manifestPath, eligible, targetBranch, feature, getLog());
            FeatureFinishSupport.mergeWorkspaceRepo(
                    manifestPath, branchName, targetBranch, keepBranch, getLog());
        }

        getLog().info("");
        getLog().info("  Merged: " + merged + " components (no-ff)");
        getLog().info("  Branch " + (keepBranch ? "kept" : "deleted") + ": " + branchName);
        getLog().info("");
    }

    private void executeBareMode(String branchName) throws MojoExecutionException {
        File dir = new File(System.getProperty("user.dir"));

        getLog().info("");
        getLog().info("IKE Feature Finish — Merge (bare repo)");
        getLog().info("══════════════════════════════════════════════════════════════");

        VcsOperations.catchUp(dir, getLog());

        String currentBranch = gitBranch(dir);
        if (!currentBranch.equals(branchName)) {
            throw new MojoExecutionException(
                    "Not on " + branchName + " (currently on " + currentBranch + ")");
        }
        if (!gitStatus(dir).isEmpty()) {
            throw new MojoExecutionException("Uncommitted changes. Commit or stash first.");
        }

        if (dryRun) {
            getLog().info("  [dry-run] Would merge → " + targetBranch);
            return;
        }

        VcsOperations.checkout(dir, getLog(), targetBranch);
        VcsOperations.mergeNoFf(dir, getLog(), branchName, message);
        VcsOperations.pushSafe(dir, getLog(), "origin", targetBranch);

        if (!keepBranch) {
            FeatureFinishSupport.deleteBranch(dir, getLog(), branchName);
        }

        VcsOperations.writeVcsState(dir, VcsState.ACTION_FEATURE_FINISH);

        getLog().info("  Done.");
        getLog().info("");
    }
}
