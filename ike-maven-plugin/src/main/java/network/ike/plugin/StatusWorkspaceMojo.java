package network.ike.plugin;

import network.ike.workspace.Component;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.util.Set;

/**
 * Show git status across all workspace components.
 *
 * <p>For each component directory that exists, reports the current
 * branch, short SHA, and whether the working tree is clean or dirty.
 * Missing directories are flagged as "not cloned".
 *
 * <pre>{@code
 * mvn ike:status
 * mvn ike:status -Dgroup=studio
 * }</pre>
 */
@Mojo(name = "status", requiresProject = false, threadSafe = true)
public class StatusWorkspaceMojo extends AbstractWorkspaceMojo {

    /**
     * Restrict to a named group (or single component). Default: all.
     */
    @Parameter(property = "group")
    String group;

    @Override
    public void execute() throws MojoExecutionException {
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        getLog().info("");
        getLog().info("IKE Workspace — Status");
        getLog().info("══════════════════════════════════════════════════════════════");

        Set<String> targets;
        if (group != null && !group.isEmpty()) {
            targets = graph.expandGroup(group);
            getLog().info("  Group: " + group + " → " + targets.size() + " components");
        } else {
            targets = graph.manifest().components().keySet();
        }

        getLog().info("");
        getLog().info(String.format("  %-28s %-28s %-10s %s",
                "COMPONENT", "BRANCH", "SHA", "STATUS"));
        getLog().info(String.format("  %-28s %-28s %-10s %s",
                "─────────", "──────", "───", "──────"));

        int cloned = 0;
        int dirty = 0;

        for (String name : targets) {
            Component component = graph.manifest().components().get(name);
            File dir = new File(root, name);

            if (!dir.exists()) {
                getLog().info(String.format("  %-28s %-28s %-10s %s",
                        name, "—", "—", "not cloned"));
                continue;
            }

            cloned++;
            String branch = gitBranch(dir);
            String sha = gitShortSha(dir);
            String status = gitStatus(dir);

            String statusLabel;
            if (status.isEmpty()) {
                statusLabel = "clean";
            } else {
                dirty++;
                long changed = status.lines().count();
                statusLabel = "dirty (" + changed + " file"
                        + (changed == 1 ? "" : "s") + ")";
            }

            // Flag branch mismatch with manifest
            String branchDisplay = branch;
            if (component.branch() != null
                    && !branch.equals(component.branch())) {
                branchDisplay = branch + " (expected: " + component.branch() + ")";
            }

            getLog().info(String.format("  %-28s %-28s %-10s %s",
                    name, branchDisplay, sha, statusLabel));
        }

        getLog().info("");
        getLog().info("  " + cloned + "/" + targets.size() + " cloned, "
                + dirty + " dirty");
        getLog().info("");
    }
}
