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
 * Squash-merge a feature branch back to the target branch.
 *
 * <p>This is the <b>default and recommended</b> strategy for finishing
 * features. The feature branch's full commit history is compressed into
 * a single commit on the target branch. The feature branch is deleted
 * after merge because squash creates divergent history — continuing
 * on the branch would cause conflicts.
 *
 * <p>Use {@code -DkeepBranch=true} only if you understand that the
 * branch can no longer be cleanly merged again.
 *
 * <p>When to use: most features. Feature branch history is disposable.
 * Target branch gets one clean commit.
 *
 * <pre>{@code
 * mvn ike:feature-finish-squash -Dfeature=my-feature -Dmessage="Add widget support"
 * mvn ike:feature-finish-squash -Dfeature=kec-march-25 -Dgroup=core -Dmessage="Core updates"
 * }</pre>
 *
 * @see FeatureFinishMergeMojo for long-lived branches
 * @see FeatureFinishRebaseMojo for linear history
 */
@Mojo(name = "feature-finish-squash", requiresProject = false, threadSafe = true)
public class FeatureFinishSquashMojo extends AbstractWorkspaceMojo {

    /** Creates this goal instance. */
    public FeatureFinishSquashMojo() {}

    /** Feature name. Expects branch {@code feature/<name>}. Prompted if omitted. */
    @Parameter(property = "feature")
    String feature;

    /** Restrict to a named group or component. Default: all cloned. */
    @Parameter(property = "group")
    String group;

    /** Target branch to merge into. */
    @Parameter(property = "targetBranch", defaultValue = "main")
    String targetBranch;

    /**
     * Keep the feature branch after squash-merge. Default is false because
     * squash creates divergent history — the branch cannot be cleanly merged
     * again.
     */
    @Parameter(property = "keepBranch", defaultValue = "false")
    boolean keepBranch;

    /** Squash commit message. Prompted if omitted. */
    @Parameter(property = "message")
    String message;

    /** Show plan without executing. */
    @Parameter(property = "dryRun", defaultValue = "false")
    boolean dryRun;

    @Override
    public void execute() throws MojoExecutionException {
        feature = requireParam(feature, "feature",
                "Feature name (expects branch feature/<name>)");
        message = requireParam(message, "message", "Squash commit message");
        String branchName = "feature/" + feature;

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
        getLog().info("IKE Workspace — Feature Finish (squash)");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Feature:  " + feature);
        getLog().info("  Branch:   " + branchName + " → " + targetBranch);
        getLog().info("  Strategy: squash-merge");
        if (dryRun) getLog().info("  Mode:     DRY RUN");
        getLog().info("");

        // Catch-up
        VcsOperations.catchUp(root, getLog());

        // Validate and collect eligible components
        List<String> eligible = new ArrayList<>();
        for (String name : reversed) {
            String reason = FeatureFinishSupport.validateComponent(
                    root, name, branchName, this);
            if (reason == null) {
                eligible.add(name);
            } else if ("DIRTY".equals(reason)) {
                throw new MojoExecutionException(
                        name + " has uncommitted changes on " + branchName
                                + ". Commit or stash before finishing.");
            } else {
                getLog().info("  · " + name + " — " + reason + ", skipping");
            }
        }

        if (eligible.isEmpty()) {
            getLog().info("  No components on " + branchName + " — nothing to do.");
            return;
        }

        // Merge each component
        int merged = 0;
        for (String name : eligible) {
            Component component = graph.manifest().components().get(name);
            File dir = new File(root, name);

            if (dryRun) {
                getLog().info("  [dry-run] " + name + " — would squash-merge → " + targetBranch);
                merged++;
                continue;
            }

            getLog().info("  → " + name);
            VcsOperations.catchUp(dir, getLog());
            FeatureFinishSupport.stripBranchVersion(dir, component, getLog());

            VcsOperations.checkout(dir, getLog(), targetBranch);
            VcsOperations.mergeSquash(dir, getLog(), branchName);
            VcsOperations.commit(dir, getLog(), message);
            VcsOperations.pushSafe(dir, getLog(), "origin", targetBranch);

            if (!keepBranch) {
                FeatureFinishSupport.deleteBranch(dir, getLog(), branchName);
            }

            VcsOperations.writeVcsState(dir, VcsState.ACTION_FEATURE_FINISH);
            merged++;
        }

        // Clean up sites
        if (merged > 0 && !dryRun) {
            FeatureFinishSupport.cleanFeatureSites(root, eligible, branchName, getLog());
            FeatureFinishSupport.updateWorkspaceYaml(
                    manifestPath, eligible, targetBranch, feature, getLog());
            FeatureFinishSupport.mergeWorkspaceRepo(
                    manifestPath, branchName, targetBranch, keepBranch, getLog());
        }

        getLog().info("");
        getLog().info("  Squash-merged: " + merged + " components");
        if (!keepBranch) {
            getLog().info("  Branch deleted: " + branchName);
        }
        getLog().info("");
    }

    private void executeBareMode(String branchName) throws MojoExecutionException {
        File dir = new File(System.getProperty("user.dir"));

        getLog().info("");
        getLog().info("IKE Feature Finish — Squash (bare repo)");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Feature:  " + feature);
        getLog().info("  Branch:   " + branchName + " → " + targetBranch);
        if (dryRun) getLog().info("  Mode:     DRY RUN");
        getLog().info("");

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
            getLog().info("  [dry-run] Would squash-merge → " + targetBranch);
            return;
        }

        FeatureFinishSupport.stripBranchVersionBare(dir, getLog());

        VcsOperations.checkout(dir, getLog(), targetBranch);
        VcsOperations.mergeSquash(dir, getLog(), branchName);
        VcsOperations.commit(dir, getLog(), message);
        VcsOperations.pushSafe(dir, getLog(), "origin", targetBranch);

        if (!keepBranch) {
            FeatureFinishSupport.deleteBranch(dir, getLog(), branchName);
        }

        VcsOperations.writeVcsState(dir, VcsState.ACTION_FEATURE_FINISH);

        getLog().info("");
        getLog().info("  Done.");
        getLog().info("");
    }
}
