package network.ike.workspace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Graph operations over a workspace {@link Manifest}.
 *
 * <p>All algorithms operate on the component dependency graph defined
 * by {@code depends-on} entries in workspace.yaml. The graph is small
 * (typically &lt; 20 nodes) so simple implementations are preferred
 * over library dependencies.
 */
public final class WorkspaceGraph {

    private final Manifest manifest;

    /** Forward edges: component → components it depends on. */
    private final Map<String, List<String>> forward;

    /** Reverse edges: component → components that depend on it. */
    private final Map<String, List<String>> reverse;

    /** @param manifest parsed workspace manifest */
    public WorkspaceGraph(Manifest manifest) {
        this.manifest = manifest;
        this.forward = buildForwardEdges();
        this.reverse = buildReverseEdges();
    }

    /**
     * Return the underlying manifest.
     *
     * @return the parsed workspace manifest
     */
    public Manifest manifest() {
        return manifest;
    }

    // ── Topological Sort ────────────────────────────────────────────

    /**
     * Topological sort of the given components (or all if none specified).
     * Dependencies outside the target set are ignored.
     *
     * @param targetNames components to include; empty means all
     * @return components in dependency order (dependencies first)
     * @throws ManifestException if a cycle is detected
     */
    public List<String> topologicalSort(Set<String> targetNames) {
        Set<String> targets = targetNames.isEmpty()
                ? manifest.components().keySet()
                : targetNames;

        // Kahn's algorithm
        Map<String, Integer> inDegree = new LinkedHashMap<>();
        for (String name : targets) {
            inDegree.put(name, 0);
        }
        for (String name : targets) {
            for (String dep : depsInSet(name, targets)) {
                inDegree.merge(dep, 0, Integer::sum); // ensure dep exists
                inDegree.merge(name, 1, Integer::sum);
            }
        }

        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<String> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            sorted.add(current);
            for (String name : targets) {
                if (depsInSet(name, targets).contains(current)) {
                    int remaining = inDegree.merge(name, -1, Integer::sum);
                    if (remaining == 0) {
                        queue.add(name);
                    }
                }
            }
        }

        if (sorted.size() < targets.size()) {
            Set<String> missing = new LinkedHashSet<>(targets);
            missing.removeAll(sorted);
            throw new ManifestException("Dependency cycle involving: " + missing);
        }

        return Collections.unmodifiableList(sorted);
    }

    /**
     * Topological sort of all components.
     *
     * @return component names in dependency order (leaves first)
     */
    public List<String> topologicalSort() {
        return topologicalSort(Set.of());
    }

    // ── Cascade Analysis ────────────────────────────────────────────

    /**
     * Compute the propagation set: all components that transitively
     * depend on the given component (BFS on reverse edges).
     *
     * <p>The result does NOT include the starting component itself.
     *
     * @param componentName the changed component
     * @return components affected by a change, in BFS discovery order
     * @throws ManifestException if the component does not exist
     */
    public List<String> cascade(String componentName) {
        if (!manifest.components().containsKey(componentName)) {
            throw new ManifestException(
                    "Unknown component: " + componentName);
        }

        List<String> result = new ArrayList<>();
        Set<String> visited = new LinkedHashSet<>();
        visited.add(componentName);

        Deque<String> queue = new ArrayDeque<>();
        queue.add(componentName);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            List<String> dependents = reverse.getOrDefault(current, List.of());
            for (String dep : dependents) {
                if (visited.add(dep)) {
                    result.add(dep);
                    queue.add(dep);
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    // ── Cycle Detection ─────────────────────────────────────────────

    /**
     * Detect dependency cycles. Returns the first cycle found as a
     * list of component names forming the cycle, or an empty list
     * if no cycles exist.
     *
     * @return cycle path, or empty list if acyclic
     */
    public List<String> detectCycle() {
        // DFS with three-color marking: WHITE=unvisited, GRAY=in-stack, BLACK=done
        Set<String> white = new LinkedHashSet<>(manifest.components().keySet());
        Set<String> gray = new LinkedHashSet<>();
        Map<String, String> parent = new LinkedHashMap<>();

        for (String start : manifest.components().keySet()) {
            if (!white.contains(start)) continue;

            Deque<String> stack = new ArrayDeque<>();
            stack.push(start);

            while (!stack.isEmpty()) {
                String current = stack.peek();

                if (white.remove(current)) {
                    gray.add(current);
                    for (String dep : forwardDeps(current)) {
                        if (gray.contains(dep)) {
                            // Found cycle — reconstruct path
                            return reconstructCycle(parent, current, dep);
                        }
                        if (white.contains(dep)) {
                            parent.put(dep, current);
                            stack.push(dep);
                        }
                    }
                } else {
                    stack.pop();
                    gray.remove(current);
                }
            }
        }
        return List.of();
    }

    private List<String> reconstructCycle(Map<String, String> parent,
                                           String from, String to) {
        List<String> cycle = new ArrayList<>();
        cycle.add(to);
        String current = from;
        while (!current.equals(to)) {
            cycle.add(current);
            current = parent.getOrDefault(current, to);
        }
        cycle.add(to);
        Collections.reverse(cycle);
        return Collections.unmodifiableList(cycle);
    }

    // ── Group Expansion ─────────────────────────────────────────────

    /**
     * Expand a group name recursively into its component names.
     * Group references within groups are expanded transitively.
     * Direct component names pass through unchanged.
     *
     * @param groupName the group (or component) to expand
     * @return deduplicated set of component names
     * @throws ManifestException if a group reference cannot be resolved
     */
    public Set<String> expandGroup(String groupName) {
        Set<String> result = new LinkedHashSet<>();
        Set<String> expanding = new LinkedHashSet<>();
        expandGroupRecursive(groupName, result, expanding);
        return Collections.unmodifiableSet(result);
    }

    private void expandGroupRecursive(String name, Set<String> result,
                                       Set<String> expanding) {
        // If it's a component name, add directly
        if (manifest.components().containsKey(name)) {
            result.add(name);
            return;
        }

        // Must be a group — expand its members
        List<String> members = manifest.groups().get(name);
        if (members == null) {
            throw new ManifestException(
                    "Unknown component or group: " + name);
        }

        if (!expanding.add(name)) {
            throw new ManifestException(
                    "Circular group reference: " + name);
        }

        for (String member : members) {
            expandGroupRecursive(member, result, expanding);
        }

        expanding.remove(name);
    }

    // ── Manifest Verification ───────────────────────────────────────

    /**
     * Verify manifest consistency. Returns a list of error messages;
     * an empty list means the manifest is valid.
     *
     * @return error messages, or empty list if valid
     */
    public List<String> verify() {
        List<String> errors = new ArrayList<>();

        // Check 1: all dependency targets exist as components
        for (Component component : manifest.components().values()) {
            for (Dependency dep : component.dependsOn()) {
                if (!manifest.components().containsKey(dep.component())) {
                    errors.add(component.name() + " depends on unknown component: "
                            + dep.component());
                }
            }
        }

        // Check 2: no dependency cycles
        List<String> cycle = detectCycle();
        if (!cycle.isEmpty()) {
            errors.add("Dependency cycle: " + String.join(" → ", cycle));
        }

        // Check 3: all group references resolve
        for (Map.Entry<String, List<String>> entry : manifest.groups().entrySet()) {
            for (String member : entry.getValue()) {
                if (!manifest.components().containsKey(member)
                        && !manifest.groups().containsKey(member)) {
                    errors.add("Group '" + entry.getKey()
                            + "' references unknown component or group: " + member);
                }
            }
        }

        // Check 4: all component types are defined
        for (Component component : manifest.components().values()) {
            if (!manifest.componentTypes().containsKey(component.type())) {
                errors.add(component.name() + " has unknown type: "
                        + component.type());
            }
        }

        return Collections.unmodifiableList(errors);
    }

    // ── Internal ────────────────────────────────────────────────────

    private List<String> forwardDeps(String name) {
        return forward.getOrDefault(name, List.of());
    }

    private Set<String> depsInSet(String name, Set<String> targetSet) {
        Set<String> result = new LinkedHashSet<>();
        for (String dep : forwardDeps(name)) {
            if (targetSet.contains(dep)) {
                result.add(dep);
            }
        }
        return result;
    }

    private Map<String, List<String>> buildForwardEdges() {
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (Component component : manifest.components().values()) {
            List<String> deps = new ArrayList<>();
            for (Dependency dep : component.dependsOn()) {
                deps.add(dep.component());
            }
            edges.put(component.name(), Collections.unmodifiableList(deps));
        }
        return Collections.unmodifiableMap(edges);
    }

    private Map<String, List<String>> buildReverseEdges() {
        Map<String, List<String>> edges = new LinkedHashMap<>();
        for (String name : manifest.components().keySet()) {
            edges.put(name, new ArrayList<>());
        }
        for (Component component : manifest.components().values()) {
            for (Dependency dep : component.dependsOn()) {
                edges.computeIfAbsent(dep.component(), k -> new ArrayList<>())
                        .add(component.name());
            }
        }
        // Make immutable
        Map<String, List<String>> immutable = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : edges.entrySet()) {
            immutable.put(entry.getKey(),
                    Collections.unmodifiableList(entry.getValue()));
        }
        return Collections.unmodifiableMap(immutable);
    }
}
