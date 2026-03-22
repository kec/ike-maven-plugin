package network.ike.plugin;

import network.ike.workspace.Component;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.util.List;

/**
 * Compute the cascade (propagation set) from a changed component.
 *
 * <p>Shows all components that transitively depend on the given
 * component and would need rebuilding after a change.
 *
 * <pre>{@code
 * mvn ike:cascade -Dcomponent=tinkar-core
 * }</pre>
 */
@Mojo(name = "cascade", requiresProject = false, threadSafe = true)
public class CascadeWorkspaceMojo extends AbstractWorkspaceMojo {

    /**
     * The component that changed. Prompted if omitted.
     */
    @Parameter(property = "component")
    String component;

    @Override
    public void execute() throws MojoExecutionException {
        component = requireParam(component, "component", "Component that changed");

        WorkspaceGraph graph = loadGraph();

        getLog().info("");
        getLog().info("IKE Workspace — Cascade from: " + component);
        getLog().info("══════════════════════════════════════════════════════════════");

        List<String> affected = graph.cascade(component);

        if (affected.isEmpty()) {
            getLog().info("");
            getLog().info("  " + component + " is a leaf — no downstream dependents.");
        } else {
            getLog().info("");
            getLog().info("  Affected components (" + affected.size() + "):");
            getLog().info("");

            // Show in cascade order with type and relationship
            for (int i = 0; i < affected.size(); i++) {
                String name = affected.get(i);
                Component comp = graph.manifest().components().get(name);
                String type = comp != null ? comp.type() : "?";
                getLog().info(String.format("    %2d. %-28s [%s]",
                        i + 1, name, type));
            }

            // Show build order
            getLog().info("");
            getLog().info("  Rebuild order (topological):");
            getLog().info("");

            List<String> buildOrder = graph.topologicalSort(
                    new java.util.LinkedHashSet<>(affected));
            for (int i = 0; i < buildOrder.size(); i++) {
                String name = buildOrder.get(i);
                Component comp = graph.manifest().components().get(name);
                String cmd = "";
                if (comp != null) {
                    var ct = graph.manifest().componentTypes().get(comp.type());
                    cmd = ct != null ? ct.buildCommand() : "";
                }
                getLog().info(String.format("    %2d. %-28s %s",
                        i + 1, name, cmd));
            }
        }

        getLog().info("");
    }
}
