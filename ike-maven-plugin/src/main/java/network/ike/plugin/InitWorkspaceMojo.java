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
 * Clone and initialize workspace components from the manifest.
 *
 * <p>Three initialization modes per component:
 * <ol>
 *   <li><b>Already cloned</b> — directory has {@code .git/}; skip.</li>
 *   <li><b>Syncthing working tree</b> — directory exists but no
 *       {@code .git/}. Initializes git in-place: {@code git init},
 *       adds the remote, fetches, and resets to match the remote branch.
 *       This preserves file content synced from another machine.</li>
 *   <li><b>Fresh clone</b> — no directory; runs {@code git clone}.</li>
 * </ol>
 *
 * <p>Components are initialized in topological (dependency) order.
 *
 * <pre>{@code
 * mvn ike:init
 * mvn ike:init -Dgroup=studio
 * mvn ike:init -Dgroup=docs
 * }</pre>
 */
@Mojo(name = "init", requiresProject = false, threadSafe = true)
public class InitWorkspaceMojo extends AbstractWorkspaceMojo {

    /**
     * Restrict to a named group (or single component). Default: all.
     */
    @Parameter(property = "group")
    String group;

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

        // Sort in dependency order
        List<String> sorted = graph.topologicalSort(new LinkedHashSet<>(targets));

        getLog().info("");
        getLog().info("IKE Workspace — Init");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Target: " + (group != null ? group : "all")
                + " (" + sorted.size() + " components)");
        getLog().info("  Root:   " + root.getAbsolutePath());
        getLog().info("");

        int cloned = 0;
        int syncthing = 0;
        int skipped = 0;

        for (String name : sorted) {
            Component component = graph.manifest().components().get(name);
            File dir = new File(root, name);
            File gitDir = new File(dir, ".git");

            if (gitDir.exists()) {
                // Already a git repo
                getLog().info("  ✓ " + name + " — already initialized");
                skipped++;
                continue;
            }

            String repo = component.repo();
            String branch = component.branch();

            if (repo == null || repo.isEmpty()) {
                getLog().warn("  ⚠ " + name + " — no repo URL, skipping");
                continue;
            }

            if (dir.exists()) {
                // Syncthing working tree — init git in-place
                getLog().info("  ↻ " + name
                        + " — initializing git in existing directory (Syncthing)");
                initSyncthingRepo(dir, repo, branch);
                syncthing++;
            } else {
                // Fresh clone
                getLog().info("  ↓ " + name + " — cloning from " + repo);
                cloneRepo(root, name, repo, branch);
                cloned++;
            }
        }

        getLog().info("");
        getLog().info("  Done: " + cloned + " cloned, " + syncthing
                + " Syncthing-initialized, " + skipped + " already present");
        getLog().info("");
    }

    /**
     * Initialize a git repo inside an existing directory (Syncthing case).
     * The directory has files but no .git — we init, add remote, fetch,
     * and reset to match the remote branch without overwriting working-tree files.
     */
    private void initSyncthingRepo(File dir, String repo, String branch)
            throws MojoExecutionException {
        ReleaseSupport.exec(dir, getLog(), "git", "init");
        ReleaseSupport.exec(dir, getLog(), "git", "remote", "add", "origin", repo);
        ReleaseSupport.exec(dir, getLog(), "git", "fetch", "origin", branch);
        // Mixed reset: updates HEAD and index to match remote, keeps working tree
        ReleaseSupport.exec(dir, getLog(),
                "git", "reset", "origin/" + branch);
    }

    /**
     * Standard git clone into a new directory.
     */
    private void cloneRepo(File root, String name, String repo, String branch)
            throws MojoExecutionException {
        ReleaseSupport.exec(root, getLog(),
                "git", "clone", "-b", branch, repo, name);
    }
}
