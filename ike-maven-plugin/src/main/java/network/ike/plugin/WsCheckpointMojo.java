package network.ike.plugin;

import network.ike.workspace.Component;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Record a workspace checkpoint — a consistent snapshot of all
 * component SHAs, branches, and versions.
 *
 * <p>Writes a YAML checkpoint file to {@code checkpoints/} in the
 * workspace root and optionally tags each component's current commit.
 *
 * <p>This is the <b>workspace-level</b> checkpoint (multi-repo).
 * For single-repo checkpoints, use {@code ike:checkpoint} instead.
 *
 * <pre>{@code
 * mvn ike:ws-checkpoint -Dname=sprint-42
 * mvn ike:ws-checkpoint -Dname=pre-release -Dtag=true
 * }</pre>
 */
@Mojo(name = "ws-checkpoint", requiresProject = false, threadSafe = true)
public class WsCheckpointMojo extends AbstractWorkspaceMojo {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                    .withZone(ZoneOffset.UTC);

    /** Checkpoint name. Used in filename and tags. Prompted if omitted. */
    @Parameter(property = "name")
    String name;

    /** Tag each component with {@code checkpoint/<name>/<component>}. */
    @Parameter(property = "tag", defaultValue = "false")
    boolean tag;

    /** Push tags to origin. Only applies when tag=true. */
    @Parameter(property = "push", defaultValue = "false")
    boolean push;

    @Override
    public void execute() throws MojoExecutionException {
        name = requireParam(name, "name", "Checkpoint name");

        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        String timestamp = ISO_UTC.format(Instant.now());
        String author = resolveAuthor(root);

        getLog().info("");
        getLog().info("IKE Workspace — Checkpoint");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Name:   " + name);
        getLog().info("  Time:   " + timestamp);
        getLog().info("  Author: " + author);
        getLog().info("  Tag:    " + tag);
        getLog().info("");

        // ── Gather component snapshots ─────────────────────────────
        List<ComponentSnapshot> snapshots = new ArrayList<>();
        List<String> absentComponents = new ArrayList<>();
        List<String> taggedComponents = new ArrayList<>();

        for (var entry : graph.manifest().components().entrySet()) {
            String compName = entry.getKey();
            Component component = entry.getValue();
            File dir = new File(root, compName);
            File gitDir = new File(dir, ".git");

            if (!gitDir.exists()) {
                absentComponents.add(compName);
                continue;
            }

            String sha = gitFullSha(dir);
            String shortSha = gitShortSha(dir);
            String branch = gitBranch(dir);
            String version = readVersion(dir);
            String status = gitStatus(dir);
            boolean dirty = !status.isEmpty();

            var ct = graph.manifest().componentTypes().get(component.type());
            boolean composite = ct != null && "composite".equals(ct.checkpointMechanism());

            snapshots.add(new ComponentSnapshot(
                    compName, sha, shortSha, branch, version, dirty,
                    component.type(), composite));

            // Tag if requested
            if (tag && !dirty) {
                String tagName = checkpointTagName(name, compName);
                try {
                    ReleaseSupport.exec(dir, getLog(),
                            "git", "tag", tagName);
                    taggedComponents.add(compName);
                    getLog().info("  ✓ " + compName + " [" + shortSha + "] "
                            + branch + " → tagged " + tagName);
                    if (push) {
                        ReleaseSupport.exec(dir, getLog(),
                                "git", "push", "origin", tagName);
                    }
                } catch (MojoExecutionException e) {
                    getLog().warn("  ⚠ " + compName
                            + " — tag failed (may already exist): "
                            + e.getMessage());
                }
            } else {
                String dirtyMark = dirty ? " [DIRTY]" : "";
                getLog().info("  ✓ " + compName + " [" + shortSha + "] "
                        + branch + dirtyMark);
            }
        }

        // ── Build checkpoint YAML ────────────────────────────────────
        String yamlContent = buildCheckpointYaml(
                name, timestamp, author,
                graph.manifest().schemaVersion(),
                snapshots, absentComponents);

        // Write checkpoint file
        int recorded = snapshots.size();
        int absent = absentComponents.size();
        Path checkpointsDir = root.toPath().resolve("checkpoints");
        try {
            Files.createDirectories(checkpointsDir);
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Cannot create checkpoints directory", e);
        }

        Path checkpointFile = checkpointsDir.resolve(
                checkpointFileName(name));
        try {
            Files.writeString(checkpointFile, yamlContent,
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Failed to write " + checkpointFile, e);
        }

        getLog().info("");
        getLog().info("  Checkpoint: " + checkpointFile);
        getLog().info("  Recorded: " + recorded + " | Absent: " + absent);
        if (tag) {
            getLog().info("  Tagged: " + taggedComponents.size()
                    + (push ? " (pushed)" : " (local only)"));
        }
        getLog().info("");
    }

    // ── YAML generation (pure, static, testable) ──────────────────

    /**
     * Build checkpoint YAML content from pre-gathered component data.
     *
     * <p>This is a pure function with no git or I/O dependencies,
     * suitable for direct unit testing.
     *
     * @param name            checkpoint name
     * @param timestamp       ISO-8601 UTC timestamp
     * @param author          checkpoint author name
     * @param schemaVersion   workspace schema version
     * @param snapshots       component snapshots (present components)
     * @param absentNames     names of components not checked out
     * @return YAML checkpoint content
     */
    public static String buildCheckpointYaml(String name, String timestamp,
                                              String author, String schemaVersion,
                                              List<ComponentSnapshot> snapshots,
                                              List<String> absentNames) {
        List<String> yaml = new ArrayList<>();
        yaml.add("# IKE Workspace Checkpoint");
        yaml.add("# Generated by: mvn ike:ws-checkpoint -Dname=" + name);
        yaml.add("#");
        yaml.add("checkpoint:");
        yaml.add("  name: \"" + name + "\"");
        yaml.add("  created: \"" + timestamp + "\"");
        yaml.add("  author: \"" + author + "\"");
        yaml.add("  schema-version: \"" + schemaVersion + "\"");
        yaml.add("");
        yaml.add("  components:");

        for (String absent : absentNames) {
            yaml.add("    " + absent + ":");
            yaml.add("      status: absent");
        }

        for (ComponentSnapshot snap : snapshots) {
            yaml.add("    " + snap.name() + ":");
            yaml.add("      sha: \"" + snap.sha() + "\"");
            yaml.add("      short-sha: \"" + snap.shortSha() + "\"");
            yaml.add("      branch: \"" + snap.branch() + "\"");
            yaml.add("      type: " + snap.type());
            if (snap.version() != null) {
                yaml.add("      version: \"" + snap.version() + "\"");
            }
            if (snap.dirty()) {
                yaml.add("      dirty: true");
                yaml.add("      # WARNING: working tree had uncommitted changes");
            }
            if (snap.compositeCheckpoint()) {
                yaml.add("      # TODO: add view-coordinate from Tinkar runtime");
            }
        }

        return String.join("\n", yaml) + "\n";
    }

    /**
     * Derive the git tag name for a checkpoint component.
     *
     * @param checkpointName the checkpoint name
     * @param componentName  the component name
     * @return tag name in the form {@code checkpoint/<name>/<component>}
     */
    public static String checkpointTagName(String checkpointName,
                                            String componentName) {
        return "checkpoint/" + checkpointName + "/" + componentName;
    }

    /**
     * Derive the checkpoint YAML file name.
     *
     * @param checkpointName the checkpoint name
     * @return file name in the form {@code checkpoint-<name>.yaml}
     */
    public static String checkpointFileName(String checkpointName) {
        return "checkpoint-" + checkpointName + ".yaml";
    }

    /**
     * Format a component status line for log output.
     *
     * @param compName the component name
     * @param shortSha the short git SHA
     * @param branch   the branch name
     * @param dirty    whether the working tree has uncommitted changes
     * @param tagName  the tag name (null if not tagging)
     * @return formatted status line
     */
    public static String formatComponentStatus(String compName, String shortSha,
                                                String branch, boolean dirty,
                                                String tagName) {
        if (tagName != null) {
            return compName + " [" + shortSha + "] " + branch
                    + " → tagged " + tagName;
        }
        String dirtyMark = dirty ? " [DIRTY]" : "";
        return compName + " [" + shortSha + "] " + branch + dirtyMark;
    }

    private String resolveAuthor(File root) {
        try {
            return ReleaseSupport.execCapture(root,
                    "git", "config", "user.name");
        } catch (MojoExecutionException e) {
            return System.getProperty("user.name", "unknown");
        }
    }

    private String gitFullSha(File dir) {
        try {
            return ReleaseSupport.execCapture(dir,
                    "git", "rev-parse", "HEAD");
        } catch (MojoExecutionException e) {
            return "unknown";
        }
    }

    private String readVersion(File dir) {
        try {
            return ReleaseSupport.readPomVersion(new File(dir, "pom.xml"));
        } catch (MojoExecutionException e) {
            return null;
        }
    }
}
