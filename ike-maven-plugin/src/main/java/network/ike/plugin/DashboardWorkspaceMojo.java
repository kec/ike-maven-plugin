package network.ike.plugin;

import network.ike.workspace.Component;
import network.ike.workspace.Dependency;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Composite workspace dashboard — one invocation, full overview.
 *
 * <p>Runs verify + status + cascade-from-dirty in a single pass,
 * loading the manifest once and iterating components once. This is
 * the recommended "morning standup" command.
 *
 * <pre>{@code mvn ike:dashboard}</pre>
 */
@Mojo(name = "dashboard", requiresProject = false, threadSafe = true)
public class DashboardWorkspaceMojo extends AbstractWorkspaceMojo {

    @Override
    public void execute() throws MojoExecutionException {
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        getLog().info("");
        getLog().info("IKE Workspace — Dashboard");
        getLog().info("══════════════════════════════════════════════════════════════");

        // ── Section 1: Manifest health ──────────────────────────────
        List<String> errors = graph.verify();
        getLog().info("");
        if (errors.isEmpty()) {
            getLog().info("  ✓ Manifest: " + graph.manifest().components().size()
                    + " components, " + graph.manifest().groups().size()
                    + " groups — consistent");
        } else {
            getLog().warn("  ✗ Manifest: " + errors.size() + " error(s)");
            for (String error : errors) {
                getLog().warn("    " + error);
            }
        }

        // ── Section 2: Component status ─────────────────────────────
        getLog().info("");
        getLog().info("  Status");
        getLog().info("  ──────────────────────────────────────────────────────");
        getLog().info(String.format("  %-24s %-24s %-8s %s",
                "COMPONENT", "BRANCH", "SHA", ""));

        List<String> dirtyComponents = new ArrayList<>();
        int cloned = 0;
        int notCloned = 0;

        for (var entry : graph.manifest().components().entrySet()) {
            String name = entry.getKey();
            Component comp = entry.getValue();
            File dir = new File(root, name);

            if (!dir.exists()) {
                notCloned++;
                getLog().info(String.format("  %-24s %-24s %-8s %s",
                        name, "—", "—", "not cloned"));
                continue;
            }

            cloned++;
            String branch = gitBranch(dir);
            String sha = gitShortSha(dir);
            String status = gitStatus(dir);

            String marker;
            if (status.isEmpty()) {
                marker = "✓";
            } else {
                long count = status.lines().count();
                marker = "✗ " + count + " changed";
                dirtyComponents.add(name);
            }

            // Branch mismatch warning
            String branchCol = branch;
            if (comp.branch() != null && !branch.equals(comp.branch())) {
                branchCol = branch + " ⚠";
            }

            getLog().info(String.format("  %-24s %-24s %-8s %s",
                    name, branchCol, sha, marker));
        }

        getLog().info("");
        getLog().info("  " + cloned + " cloned, " + notCloned + " not cloned, "
                + dirtyComponents.size() + " dirty");

        // ── Section 3: Cascade from dirty ───────────────────────────
        if (!dirtyComponents.isEmpty()) {
            Set<String> allAffected = new LinkedHashSet<>();
            for (String dirty : dirtyComponents) {
                allAffected.addAll(graph.cascade(dirty));
            }
            // Remove components that are themselves dirty (already known)
            allAffected.removeAll(dirtyComponents);

            if (!allAffected.isEmpty()) {
                getLog().info("");
                getLog().info("  Cascade — components needing rebuild:");
                getLog().info("  ──────────────────────────────────────────────────────");

                List<String> buildOrder = graph.topologicalSort(
                        new LinkedHashSet<>(allAffected));
                for (String name : buildOrder) {
                    // Show which dirty component triggers this
                    List<String> triggeredBy = new ArrayList<>();
                    Component comp = graph.manifest().components().get(name);
                    if (comp != null) {
                        for (Dependency dep : comp.dependsOn()) {
                            if (dirtyComponents.contains(dep.component())
                                    || allAffected.contains(dep.component())) {
                                triggeredBy.add(dep.component());
                            }
                        }
                    }
                    getLog().info("    " + name + " ← "
                            + String.join(", ", triggeredBy));
                }
            }
        }

        getLog().info("");
    }
}
