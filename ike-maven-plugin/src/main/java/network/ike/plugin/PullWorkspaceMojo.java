package network.ike.plugin;

import network.ike.workspace.Component;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Pull latest changes across workspace components.
 *
 * <p>Runs {@code git pull --rebase} in each cloned component directory
 * in topological order (dependencies first). Uninitialized components
 * are skipped with a warning.
 *
 * <pre>{@code
 * mvn ike:pull
 * mvn ike:pull -Dgroup=studio
 * }</pre>
 */
@Mojo(name = "pull", requiresProject = false, threadSafe = true)
public class PullWorkspaceMojo extends AbstractWorkspaceMojo {

    /**
     * Restrict to a named group (or single component). Default: all.
     */
    @Parameter(property = "group")
    private String group;

    @Override
    public void execute() throws MojoExecutionException {
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        Set<String> targets;
        if (group != null && !group.isEmpty()) {
            targets = graph.expandGroup(group);
        } else {
            targets = graph.manifest().components().keySet();
        }

        List<String> sorted = graph.topologicalSort(new LinkedHashSet<>(targets));

        getLog().info("");
        getLog().info("IKE Workspace — Pull");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("");

        int pulled = 0;
        int skipped = 0;
        int failed = 0;

        for (String name : sorted) {
            File dir = new File(root, name);
            File gitDir = new File(dir, ".git");

            if (!gitDir.exists()) {
                getLog().info("  ⚠ " + name + " — not cloned, skipping");
                skipped++;
                continue;
            }

            getLog().info("  ↓ " + name);
            try {
                ReleaseSupport.exec(dir, getLog(),
                        "git", "pull", "--rebase");
                pulled++;
            } catch (MojoExecutionException e) {
                getLog().warn("  ✗ " + name + " — pull failed: " + e.getMessage());
                failed++;
            }
        }

        getLog().info("");
        getLog().info("  Done: " + pulled + " pulled, " + skipped
                + " skipped, " + failed + " failed");
        getLog().info("");

        if (failed > 0) {
            getLog().warn("  Some pulls failed — check output above for details.");
        }
    }
}
