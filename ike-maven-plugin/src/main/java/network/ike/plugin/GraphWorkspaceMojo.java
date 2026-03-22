package network.ike.plugin;

import network.ike.workspace.Component;
import network.ike.workspace.Dependency;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Print the workspace dependency graph.
 *
 * <p>Displays all components in topological order with their
 * dependencies. Optionally outputs DOT format for Graphviz rendering.
 *
 * <pre>{@code
 * mvn ike:graph
 * mvn ike:graph -Dformat=dot
 * }</pre>
 */
@Mojo(name = "graph", requiresProject = false, threadSafe = true)
public class GraphWorkspaceMojo extends AbstractWorkspaceMojo {

    /**
     * Output format: "text" (default) or "dot" (Graphviz DOT).
     */
    @Parameter(property = "format", defaultValue = "text")
    String format;

    @Override
    public void execute() throws MojoExecutionException {
        WorkspaceGraph graph = loadGraph();

        if ("dot".equalsIgnoreCase(format)) {
            printDot(graph);
        } else {
            printText(graph);
        }
    }

    private void printText(WorkspaceGraph graph) {
        getLog().info("");
        getLog().info("IKE Workspace — Dependency Graph");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("");

        List<String> sorted = graph.topologicalSort();

        for (int i = 0; i < sorted.size(); i++) {
            String name = sorted.get(i);
            Component comp = graph.manifest().components().get(name);

            getLog().info(String.format("  %2d. %-28s [%s]",
                    i + 1, name, comp.type()));

            if (!comp.dependsOn().isEmpty()) {
                for (Dependency dep : comp.dependsOn()) {
                    getLog().info(String.format("        └─ %s (%s)",
                            dep.component(), dep.relationship()));
                }
            }
        }

        getLog().info("");
        getLog().info("  " + sorted.size() + " components in dependency order.");
        getLog().info("");
    }

    private void printDot(WorkspaceGraph graph) {
        // Build data structures for the pure function
        Map<String, String> componentTypes = new LinkedHashMap<>();
        for (Component comp : graph.manifest().components().values()) {
            componentTypes.put(comp.name(), comp.type());
        }

        Map<String, List<String[]>> edges = new LinkedHashMap<>();
        for (Component comp : graph.manifest().components().values()) {
            List<String[]> compEdges = comp.dependsOn().stream()
                    .map(dep -> new String[]{dep.component(), dep.relationship()})
                    .toList();
            if (!compEdges.isEmpty()) {
                edges.put(comp.name(), compEdges);
            }
        }

        String dot = buildDotGraph("workspace", componentTypes, edges);
        for (String line : dot.split("\n")) {
            getLog().info(line);
        }
    }

    // ── DOT generation (pure, static, testable) ─────────────────────

    /**
     * Return the fill color for a component type name.
     *
     * @param typeName component type (e.g., "infrastructure", "software")
     * @return hex color string
     */
    public static String componentColor(String typeName) {
        return switch (typeName) {
            case "infrastructure"   -> "#e8d5b7";
            case "software"         -> "#b7d5e8";
            case "document"         -> "#b7e8c4";
            case "knowledge-source" -> "#e8b7d5";
            case "template"         -> "#d5d5d5";
            default                 -> "#ffffff";
        };
    }

    /**
     * Build a Graphviz DOT graph from component types and edges.
     *
     * <p>This is a pure function with no workspace-model dependencies,
     * suitable for direct unit testing.
     *
     * @param title          graph name used in {@code digraph <title>}
     * @param componentTypes map of component name to type name
     * @param edges          map of source component to list of
     *                       {@code [target, relationship]} pairs
     * @return complete DOT source
     */
    public static String buildDotGraph(String title,
                                        Map<String, String> componentTypes,
                                        Map<String, List<String[]>> edges) {
        StringBuilder dot = new StringBuilder(1024);
        dot.append("digraph ").append(title).append(" {\n");
        dot.append("    rankdir=BT;\n");
        dot.append("    node [shape=box, style=rounded, fontname=\"Helvetica\"];\n");
        dot.append("\n");

        // Node declarations with colors
        for (var entry : componentTypes.entrySet()) {
            String compName = entry.getKey();
            String color = componentColor(entry.getValue());
            dot.append("    \"").append(compName)
               .append("\" [fillcolor=\"").append(color)
               .append("\", style=\"rounded,filled\"];\n");
        }

        dot.append("\n");

        // Edges
        for (var entry : edges.entrySet()) {
            String source = entry.getKey();
            for (String[] edge : entry.getValue()) {
                String target = edge[0];
                String relationship = edge[1];
                String style = "content".equals(relationship)
                        ? " [style=dashed]" : "";
                dot.append("    \"").append(source).append("\" -> \"")
                   .append(target).append("\"").append(style).append(";\n");
            }
        }

        dot.append("}\n");
        return dot.toString();
    }
}
