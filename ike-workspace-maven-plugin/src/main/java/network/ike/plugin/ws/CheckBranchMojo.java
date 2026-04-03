package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.workspace.Component;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

import java.io.File;
import java.nio.file.Path;

/**
 * Defensive git hook — warns when a branch is created or switched
 * outside the workspace tooling.
 *
 * <p>Intended to be called from a {@code post-checkout} git hook:
 * <pre>{@code
 * #!/bin/sh
 * mvn -q ike:check-branch -- "$@"
 * }</pre>
 *
 * <p>In workspace mode, compares the current branch to the expected
 * branch in workspace.yaml and warns on mismatch. Provides
 * copy-pasteable undo commands.
 *
 * <p>In bare mode (no workspace.yaml), silently exits — nothing to check.
 *
 * <p>Never blocks — warnings only. Always exits 0.
 */
@Mojo(name = "check-branch", requiresProject = false, threadSafe = true)
public class CheckBranchMojo extends AbstractWorkspaceMojo {

    /** Creates this goal instance. */
    public CheckBranchMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        if (!isWorkspaceMode()) {
            return; // Bare repo — nothing to check
        }

        WorkspaceGraph graph = loadGraph();
        File wsRoot = workspaceRoot();
        File cwd = new File(System.getProperty("user.dir"));

        // Determine which component we're in by matching CWD to workspace root + component name
        String componentName = findComponentName(wsRoot, cwd, graph);
        if (componentName == null) {
            return; // Not inside a known component directory
        }

        Component component = graph.manifest().components().get(componentName);
        if (component == null || component.branch() == null) {
            return; // No expected branch declared
        }

        String expectedBranch = component.branch();
        String actualBranch = gitBranch(cwd);

        if (actualBranch.equals(expectedBranch)) {
            return; // On the expected branch — all good
        }

        // Determine if this was a branch creation (new branch that doesn't match expected)
        boolean isNewBranch = !branchExistsRemotely(cwd, actualBranch);

        if (isNewBranch && actualBranch.startsWith("feature/")) {
            // Created a feature branch directly — suggest ike:feature-start
            String featureName = actualBranch.substring("feature/".length());
            getLog().warn("");
            getLog().warn("\u26A0 You created branch '" + actualBranch + "' directly in " + componentName + ".");
            getLog().warn("");
            getLog().warn("  To fix:");
            getLog().warn("    git checkout " + expectedBranch);
            getLog().warn("    git branch -D " + actualBranch);
            getLog().warn("    mvn ike:feature-start -Dfeature=" + featureName);
            getLog().warn("");
            getLog().warn("  ike:feature-start creates aligned branches across all workspace");
            getLog().warn("  components and sets version-qualified SNAPSHOTs.");
            getLog().warn("");
        } else if (isNewBranch) {
            // Created a non-feature branch directly
            getLog().warn("");
            getLog().warn("\u26A0 You created branch '" + actualBranch + "' directly in " + componentName + ".");
            getLog().warn("");
            getLog().warn("  The workspace expects branch '" + expectedBranch + "' for this component.");
            getLog().warn("");
            getLog().warn("  To undo:");
            getLog().warn("    git checkout " + expectedBranch);
            getLog().warn("    git branch -D " + actualBranch);
            getLog().warn("");
        } else {
            // Switched to an existing branch that doesn't match workspace.yaml
            getLog().warn("");
            getLog().warn("\u26A0 You switched to branch '" + actualBranch + "' in " + componentName + ".");
            getLog().warn("");
            getLog().warn("  The workspace expects branch '" + expectedBranch + "' for this component.");
            getLog().warn("  If this is intentional, update the workspace:");
            getLog().warn("    mvn ike:ws-sync");
            getLog().warn("");
            getLog().warn("  If not:");
            getLog().warn("    git checkout " + expectedBranch);
            getLog().warn("");
        }
    }

    /**
     * Find which workspace component the CWD belongs to.
     * Handles being in a subdirectory of a multi-module reactor.
     *
     * @param wsRoot workspace root directory
     * @param cwd    current working directory
     * @param graph  workspace dependency graph
     * @return the component name, or null if CWD is not inside a known component
     */
    static String findComponentName(File wsRoot, File cwd, WorkspaceGraph graph) {
        // Walk up from CWD toward wsRoot, checking each directory name
        Path wsPath = wsRoot.toPath().toAbsolutePath().normalize();
        Path cwdPath = cwd.toPath().toAbsolutePath().normalize();

        while (cwdPath != null && cwdPath.startsWith(wsPath) && !cwdPath.equals(wsPath)) {
            // The directory immediately under wsRoot is the component name
            Path relative = wsPath.relativize(cwdPath);
            String topDir = relative.getName(0).toString();
            if (graph.manifest().components().containsKey(topDir)) {
                return topDir;
            }
            cwdPath = cwdPath.getParent();
        }
        return null;
    }

    /**
     * Check if a branch exists on the remote (origin).
     * Returns false if the check fails (no remote, offline, etc.).
     */
    private boolean branchExistsRemotely(File dir, String branch) {
        try {
            String result = ReleaseSupport.execCapture(dir,
                    "git", "ls-remote", "--heads", "origin", branch);
            return result != null && !result.isEmpty();
        } catch (Exception e) {
            return false; // Assume new if we can't check
        }
    }
}
