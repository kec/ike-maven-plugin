package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;

import network.ike.workspace.Component;
import network.ike.workspace.ManifestWriter;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Synchronize workspace.yaml branch fields with actual git branches.
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>Default</b> (from=repos): read actual branches from each
 *       cloned component and update workspace.yaml to match reality.</li>
 *   <li><b>from=manifest</b>: read workspace.yaml branch fields and
 *       switch each cloned component to the declared branch.</li>
 * </ul>
 *
 * <pre>{@code
 * mvn ike:ws-sync                    # update yaml from repos
 * mvn ike:ws-sync -Dfrom=manifest    # switch repos to match yaml
 * }</pre>
 */
@Mojo(name = "sync", requiresProject = false, threadSafe = true)
public class WsSyncMojo extends AbstractWorkspaceMojo {

    /**
     * Sync direction: {@code repos} (default) updates workspace.yaml
     * from actual branches; {@code manifest} switches repos to match
     * workspace.yaml.
     */
    @Parameter(property = "from", defaultValue = "repos")
    String from;

    /** Show plan without executing. */
    @Parameter(property = "dryRun", defaultValue = "false")
    boolean dryRun;

    /** Creates this goal instance. */
    public WsSyncMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();
        Path manifestPath = resolveManifest();

        getLog().info("");
        getLog().info("IKE Workspace \u2014 Sync");
        getLog().info("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
        getLog().info("  Direction: " + ("manifest".equals(from) ? "manifest \u2192 repos" : "repos \u2192 manifest"));
        if (dryRun) {
            getLog().info("  Mode:      DRY RUN");
        }
        getLog().info("");

        if ("manifest".equals(from)) {
            syncFromManifest(graph, root);
        } else {
            syncFromRepos(graph, root, manifestPath);
        }

        getLog().info("");
    }

    /**
     * Read actual branches and update workspace.yaml.
     */
    private void syncFromRepos(WorkspaceGraph graph, File root, Path manifestPath)
            throws MojoExecutionException {
        Map<String, String> updates = new LinkedHashMap<>();
        int unchanged = 0;

        for (Map.Entry<String, Component> entry : graph.manifest().components().entrySet()) {
            String name = entry.getKey();
            Component component = entry.getValue();
            File dir = new File(root, name);

            if (!new File(dir, ".git").exists()) {
                continue;
            }

            String actual = gitBranch(dir);
            String declared = component.branch();

            if (actual.equals(declared)) {
                unchanged++;
            } else {
                updates.put(name, actual);
                getLog().info("  " + name + ": " + declared + " \u2192 " + actual);
            }
        }

        if (updates.isEmpty()) {
            getLog().info("  All branches match workspace.yaml (" + unchanged + " components)");
            return;
        }

        if (!dryRun) {
            try {
                ManifestWriter.updateBranches(manifestPath, updates);
                getLog().info("  Updated workspace.yaml (" + updates.size() + " changes)");

                // Commit if workspace is a git repo
                File wsRoot = manifestPath.getParent().toFile();
                if (new File(wsRoot, ".git").exists()) {
                    ReleaseSupport.exec(wsRoot, getLog(),
                            "git", "add", "workspace.yaml");
                    ReleaseSupport.exec(wsRoot, getLog(),
                            "git", "commit", "-m",
                            "workspace: sync branch fields from repos");
                    getLog().info("  Committed workspace.yaml");
                }
            } catch (IOException e) {
                throw new MojoExecutionException(
                        "Failed to update workspace.yaml: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Read workspace.yaml and switch repos to declared branches.
     */
    private void syncFromManifest(WorkspaceGraph graph, File root)
            throws MojoExecutionException {
        int switched = 0;
        int unchanged = 0;

        for (Map.Entry<String, Component> entry : graph.manifest().components().entrySet()) {
            String name = entry.getKey();
            Component component = entry.getValue();
            File dir = new File(root, name);

            if (!new File(dir, ".git").exists()) {
                continue;
            }

            String actual = gitBranch(dir);
            String declared = component.branch();

            if (declared == null || actual.equals(declared)) {
                unchanged++;
                continue;
            }

            // Check for uncommitted changes
            String status = gitStatus(dir);
            if (!status.isEmpty()) {
                getLog().warn("  \u26A0 " + name + ": has uncommitted changes, skipping");
                continue;
            }

            getLog().info("  " + name + ": " + actual + " \u2192 " + declared);

            if (!dryRun) {
                ReleaseSupport.exec(dir, getLog(),
                        "git", "checkout", declared);
                switched++;
            }
        }

        getLog().info("  Switched: " + switched + " | Unchanged: " + unchanged);
    }
}
