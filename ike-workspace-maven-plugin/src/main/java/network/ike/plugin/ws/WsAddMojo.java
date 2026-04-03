package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Add a component repository to an existing workspace.
 *
 * <p>Given a git URL, this goal:
 * <ol>
 *   <li>Derives the component name from the URL (or accepts
 *       {@code -Dcomponent=<name>})</li>
 *   <li>Appends a component entry to workspace.yaml</li>
 *   <li>Adds a file-activated profile to the reactor POM</li>
 *   <li>Optionally clones the repo immediately</li>
 * </ol>
 *
 * <p>The component name is derived from the last path segment of the
 * URL with {@code .git} stripped. For example,
 * {@code https://github.com/ikmdev/tinkar-core.git} becomes
 * {@code tinkar-core}.
 *
 * <pre>{@code
 * mvn ike:ws-add -Drepo=https://github.com/ikmdev/tinkar-core.git
 * mvn ike:ws-add -Drepo=https://github.com/ikmdev/rocks-kb.git \
 *     -DdependsOn=tinkar-core
 * mvn ike:ws-add -Drepo=https://github.com/ikmdev/komet.git \
 *     -DdependsOn=tinkar-core,rocks-kb -Dtype=software
 * }</pre>
 *
 * @see WsCreateMojo for creating a new workspace
 * @see InitWorkspaceMojo for cloning all components
 */
@Mojo(name = "add", requiresProject = false, threadSafe = true)
public class WsAddMojo extends AbstractMojo {

    /**
     * Git repository URL (required). The component name is derived
     * from the URL unless {@code -Dcomponent} is specified.
     */
    @Parameter(property = "repo", required = true)
    private String repo;

    /**
     * Component name override. If omitted, derived from the repo URL
     * (last path segment minus {@code .git}).
     */
    @Parameter(property = "component")
    private String component;

    /**
     * Component type. Must match a key in the workspace.yaml
     * {@code component-types} section.
     */
    @Parameter(property = "type", defaultValue = "software")
    private String type;

    /**
     * Short description of the component.
     */
    @Parameter(property = "description")
    private String description;

    /**
     * Branch to track. If omitted, uses the workspace default.
     */
    @Parameter(property = "branch")
    private String branch;

    /**
     * Maven groupId for the component. If omitted, left as
     * a placeholder in workspace.yaml.
     */
    @Parameter(property = "groupId")
    private String groupId;

    /**
     * Comma-separated list of component names this component
     * depends on. Each dependency uses the {@code build} relationship.
     */
    @Parameter(property = "dependsOn")
    private String dependsOn;

    /**
     * Clone the component immediately after adding it.
     */
    @Parameter(property = "clone", defaultValue = "false")
    private boolean cloneNow;

    /** Creates this goal instance. */
    public WsAddMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        // Resolve workspace root
        Path wsDir = findWorkspaceRoot();
        Path manifestPath = wsDir.resolve("workspace.yaml");
        Path pomPath = wsDir.resolve("pom.xml");

        if (!Files.exists(manifestPath)) {
            throw new MojoExecutionException(
                    "No workspace.yaml found in " + wsDir
                    + ". Run ike:ws-create first.");
        }

        // Derive component name from URL if not specified
        if (component == null || component.isBlank()) {
            component = deriveComponentName(repo);
        }

        if (description == null || description.isBlank()) {
            description = component + " component.";
        }

        getLog().info("");
        getLog().info("IKE Workspace — Add Component");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Component: " + component);
        getLog().info("  Repo:      " + repo);
        getLog().info("  Type:      " + type);
        if (branch != null) {
            getLog().info("  Branch:    " + branch);
        }
        if (dependsOn != null) {
            getLog().info("  Depends:   " + dependsOn);
        }
        getLog().info("");

        try {
            // Update workspace.yaml
            appendComponentToManifest(manifestPath);
            getLog().info("  ✓ workspace.yaml updated");

            // Update pom.xml
            addProfileToPom(pomPath);
            getLog().info("  ✓ pom.xml updated (profile: with-" + component + ")");

        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Failed to update workspace files: " + e.getMessage(), e);
        }

        // Optionally clone
        if (cloneNow) {
            cloneComponent(wsDir);
            getLog().info("  ✓ Cloned " + component);
        }

        getLog().info("");
        getLog().info("  Component added. Run 'mvn ike:init' to clone.");
        getLog().info("");
    }

    // ── YAML generation ──────────────────────────────────────────

    void appendComponentToManifest(Path manifestPath) throws IOException {
        String yaml = Files.readString(manifestPath, StandardCharsets.UTF_8);

        StringBuilder entry = new StringBuilder();
        entry.append("\n  ").append(component).append(":\n");
        entry.append("    type: ").append(type).append("\n");
        entry.append("    description: >\n");
        entry.append("      ").append(description).append("\n");
        entry.append("    repo: ").append(repo).append("\n");
        if (branch != null && !branch.isBlank()) {
            entry.append("    branch: ").append(branch).append("\n");
        }
        if (groupId != null && !groupId.isBlank()) {
            entry.append("    groupId: ").append(groupId).append("\n");
        }
        if (dependsOn != null && !dependsOn.isBlank()) {
            entry.append("    depends-on:\n");
            for (String dep : dependsOn.split(",")) {
                dep = dep.trim();
                if (!dep.isEmpty()) {
                    entry.append("      - component: ").append(dep).append("\n");
                    entry.append("        relationship: build\n");
                }
            }
        } else {
            entry.append("    depends-on: []\n");
        }

        // Insert before the "groups:" section if it exists,
        // otherwise append at end
        int groupsIdx = yaml.indexOf("\ngroups:");
        if (groupsIdx >= 0) {
            yaml = yaml.substring(0, groupsIdx) + entry + yaml.substring(groupsIdx);
        } else {
            yaml = yaml + entry;
        }

        // Update the "all" group to include the new component
        yaml = addToAllGroup(yaml, component);

        Files.writeString(manifestPath, yaml, StandardCharsets.UTF_8);
    }

    // ── POM generation ───────────────────────────────────────────

    void addProfileToPom(Path pomPath) throws IOException {
        String pom = Files.readString(pomPath, StandardCharsets.UTF_8);

        // Check if profile already exists
        if (pom.contains("with-" + component)) {
            getLog().info("  Profile with-" + component + " already exists");
            return;
        }

        String profile = "\n"
                + "        <profile>\n"
                + "            <id>with-" + component + "</id>\n"
                + "            <activation>\n"
                + "                <file>\n"
                + "                    <exists>${project.basedir}/" + component + "/pom.xml</exists>\n"
                + "                </file>\n"
                + "            </activation>\n"
                + "            <subprojects>\n"
                + "                <subproject>" + component + "</subproject>\n"
                + "            </subprojects>\n"
                + "        </profile>\n";

        // Insert before closing </profiles>
        int closingIdx = pom.lastIndexOf("</profiles>");
        if (closingIdx >= 0) {
            pom = pom.substring(0, closingIdx) + profile + "\n    " + pom.substring(closingIdx);
        } else {
            getLog().warn("  No </profiles> tag found in pom.xml — add profile manually");
        }

        Files.writeString(pomPath, pom, StandardCharsets.UTF_8);
    }

    // ── Clone ────────────────────────────────────────────────────

    private void cloneComponent(Path wsDir) throws MojoExecutionException {
        String[] cmd;
        if (branch != null && !branch.isBlank()) {
            cmd = new String[]{"git", "clone", "-b", branch, repo, component};
        } else {
            cmd = new String[]{"git", "clone", repo, component};
        }
        ReleaseSupport.exec(wsDir.toFile(), getLog(), cmd);
    }

    // ── Helpers ──────────────────────────────────────────────────

    /**
     * Derive a component name from a git URL.
     * {@code https://github.com/ikmdev/tinkar-core.git} → {@code tinkar-core}
     */
    static String deriveComponentName(String repoUrl) {
        String name = repoUrl;
        // Strip trailing .git
        if (name.endsWith(".git")) {
            name = name.substring(0, name.length() - 4);
        }
        // Strip trailing slash
        if (name.endsWith("/")) {
            name = name.substring(0, name.length() - 1);
        }
        // Take last path segment
        int lastSlash = name.lastIndexOf('/');
        if (lastSlash >= 0) {
            name = name.substring(lastSlash + 1);
        }
        return name;
    }

    /**
     * Add a component to the "all" group in workspace.yaml.
     */
    static String addToAllGroup(String yaml, String componentName) {
        // Match "  all: [...]" and add the component
        Pattern allGroup = Pattern.compile(
                "(  all:\\s*\\[)(.*?)(])", Pattern.DOTALL);
        Matcher m = allGroup.matcher(yaml);
        if (m.find()) {
            String existing = m.group(2).trim();
            String updated;
            if (existing.isEmpty()) {
                updated = componentName;
            } else {
                updated = existing + ", " + componentName;
            }
            return m.replaceFirst("$1" + Matcher.quoteReplacement(updated) + "]");
        }
        return yaml;
    }

    private Path findWorkspaceRoot() throws MojoExecutionException {
        Path dir = Path.of(System.getProperty("user.dir"));
        while (dir != null) {
            if (Files.exists(dir.resolve("workspace.yaml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new MojoExecutionException(
                "Cannot find workspace.yaml. Run from within a workspace "
                + "directory or use ike:ws-create first.");
    }
}
