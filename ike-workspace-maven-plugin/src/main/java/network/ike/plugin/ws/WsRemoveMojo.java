package network.ike.plugin.ws;

import network.ike.workspace.WorkspaceGraph;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Remove a component from the workspace.
 *
 * <p>Given a component name, this goal:
 * <ol>
 *   <li>Loads the workspace graph and verifies the component exists</li>
 *   <li>Checks for downstream dependents — fails if any exist
 *       (unless {@code -Dforce=true})</li>
 *   <li>Removes the component entry from workspace.yaml</li>
 *   <li>Removes the file-activated profile from the aggregator pom.xml</li>
 *   <li>Removes the component from any group lists in workspace.yaml</li>
 *   <li>Optionally deletes the cloned directory</li>
 * </ol>
 *
 * <pre>{@code
 * mvn ike:ws-remove -Dcomponent=tinkar-core
 * mvn ike:ws-remove -Dcomponent=tinkar-core -Dforce=true
 * mvn ike:ws-remove -Dcomponent=tinkar-core -DdeleteDir=true
 * }</pre>
 *
 * @see WsAddMojo for adding a component
 */
@Mojo(name = "remove", requiresProject = false, threadSafe = true)
public class WsRemoveMojo extends AbstractWorkspaceMojo {

    /**
     * Component name to remove (required).
     */
    @Parameter(property = "component", required = true)
    private String component;

    /**
     * Skip the downstream-dependent safety check.
     */
    @Parameter(property = "force", defaultValue = "false")
    private boolean force;

    /**
     * Also delete the cloned component directory from disk.
     */
    @Parameter(property = "deleteDir", defaultValue = "false")
    private boolean deleteDir;

    /** Creates this goal instance. */
    public WsRemoveMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        // Resolve workspace root and paths
        Path manifestPath = resolveManifest();
        Path wsDir = manifestPath.getParent();
        Path pomPath = wsDir.resolve("pom.xml");

        // Load graph and validate component exists
        WorkspaceGraph graph = loadGraph();

        if (!graph.manifest().components().containsKey(component)) {
            throw new MojoExecutionException(
                    "Component '" + component + "' not found in workspace.yaml.");
        }

        // Check for downstream dependents
        List<String> dependents = graph.cascade(component);
        if (!dependents.isEmpty() && !force) {
            throw new MojoExecutionException(
                    "Cannot remove '" + component + "' — the following components "
                    + "depend on it: " + dependents + "\n"
                    + "Use -Dforce=true to remove anyway.");
        }

        getLog().info("");
        getLog().info("IKE Workspace — Remove Component");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Component: " + component);
        if (!dependents.isEmpty()) {
            getLog().warn("  Dependents (forced): " + dependents);
        }
        getLog().info("");

        try {
            // Remove from workspace.yaml
            removeComponentFromManifest(manifestPath);
            getLog().info("  ✓ workspace.yaml updated — component entry removed");

            // Remove from groups in workspace.yaml
            removeFromGroups(manifestPath);
            getLog().info("  ✓ workspace.yaml updated — group references removed");

            // Remove profile from pom.xml
            removeProfileFromPom(pomPath);
            getLog().info("  ✓ pom.xml updated — profile with-" + component + " removed");

        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Failed to update workspace files: " + e.getMessage(), e);
        }

        // Optionally delete the cloned directory
        if (deleteDir) {
            Path componentDir = wsDir.resolve(component);
            if (Files.isDirectory(componentDir)) {
                deleteDirectory(componentDir);
                getLog().info("  ✓ Deleted directory: " + componentDir);
            } else {
                getLog().info("  - Directory not present: " + componentDir);
            }
        }

        getLog().info("");
        getLog().info("  Component '" + component + "' removed.");
        getLog().info("");
    }

    // ── YAML removal ────────────────────────────────────────────

    /**
     * Remove a component block from workspace.yaml.
     *
     * <p>Matches the component header at 2-space indent under
     * {@code components:} and removes everything until the next
     * component header or section header (a line at 0 or 2-space
     * indent that is not a continuation of this block).
     */
    void removeComponentFromManifest(Path manifestPath) throws IOException {
        String yaml = Files.readString(manifestPath, StandardCharsets.UTF_8);

        // Match:  "  component-name:\n" followed by lines at 4+ space indent
        // (including multi-line description blocks) until the next 2-space key
        // or top-level key or end of file.
        String escaped = Pattern.quote(component);
        Pattern blockPattern = Pattern.compile(
                "\\n  " + escaped + ":\\s*\\n(?:    .*\\n)*",
                Pattern.MULTILINE);

        Matcher m = blockPattern.matcher(yaml);
        if (m.find()) {
            yaml = m.replaceFirst("\n");
        }

        Files.writeString(manifestPath, yaml, StandardCharsets.UTF_8);
    }

    /**
     * Remove the component from all group lists in workspace.yaml.
     *
     * <p>Handles both inline bracket syntax {@code [a, b, c]} and
     * removes the component from comma-separated lists within brackets.
     */
    void removeFromGroups(Path manifestPath) throws IOException {
        String yaml = Files.readString(manifestPath, StandardCharsets.UTF_8);
        String escaped = Pattern.quote(component);

        // Case 1: component is the only member — leave empty list
        // e.g. "  docs: [ike-lab-documents]" → "  docs: []"
        yaml = yaml.replaceAll(
                "(:\\s*\\[)\\s*" + escaped + "\\s*(])",
                "$1$2");

        // Case 2: component is first in list with others after
        // e.g. "[component, other]" → "[other]"
        yaml = yaml.replaceAll(
                "(\\[)\\s*" + escaped + "\\s*,\\s*",
                "$1");

        // Case 3: component is in the middle or at end
        // e.g. "[other, component]" → "[other]"
        // e.g. "[a, component, b]" → "[a, b]"
        yaml = yaml.replaceAll(
                ",\\s*" + escaped + "(?=\\s*[,\\]])",
                "");

        Files.writeString(manifestPath, yaml, StandardCharsets.UTF_8);
    }

    // ── POM removal ─────────────────────────────────────────────

    /**
     * Remove the file-activated profile for this component from pom.xml.
     *
     * <p>Matches the entire {@code <profile>} block whose
     * {@code <id>} is {@code with-<component>}.
     */
    void removeProfileFromPom(Path pomPath) throws IOException {
        if (!Files.exists(pomPath)) {
            getLog().warn("  No pom.xml found at " + pomPath);
            return;
        }

        String pom = Files.readString(pomPath, StandardCharsets.UTF_8);
        String profileId = "with-" + component;

        if (!pom.contains(profileId)) {
            getLog().info("  - Profile " + profileId + " not found in pom.xml (already removed?)");
            return;
        }

        // Match the entire <profile>...</profile> block containing this profile id.
        // Allow flexible whitespace. The profile block ends at </profile>.
        String escapedId = Pattern.quote(profileId);
        Pattern profilePattern = Pattern.compile(
                "\\s*<profile>\\s*\\n"
                + "\\s*<id>" + escapedId + "</id>\\s*\\n"
                + ".*?"
                + "\\s*</profile>\\s*\\n",
                Pattern.DOTALL);

        Matcher m = profilePattern.matcher(pom);
        if (m.find()) {
            pom = pom.substring(0, m.start()) + "\n" + pom.substring(m.end());
        }

        Files.writeString(pomPath, pom, StandardCharsets.UTF_8);
    }

    // ── Directory deletion ──────────────────────────────────────

    /**
     * Recursively delete a directory tree.
     */
    private void deleteDirectory(Path dir) throws MojoExecutionException {
        try {
            // Walk the tree bottom-up and delete
            Files.walk(dir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            throw new RuntimeException(
                                    "Failed to delete " + path + ": " + e.getMessage(), e);
                        }
                    });
        } catch (IOException | RuntimeException e) {
            throw new MojoExecutionException(
                    "Failed to delete directory " + dir + ": " + e.getMessage(), e);
        }
    }
}
