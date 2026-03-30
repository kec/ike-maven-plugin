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
import java.util.LinkedHashSet;

/**
 * Create a workspace checkpoint — build, tag, and deploy every component,
 * then record the resulting checkpoint coordinates in a YAML file.
 *
 * <p>Each component is processed in topological order (dependencies before
 * dependents). For each component, {@code ike:checkpoint} is invoked in that
 * component's directory, which:
 * <ol>
 *   <li>Derives an immutable checkpoint version from the current SNAPSHOT</li>
 *   <li>Stamps the version, commits, and tags {@code checkpoint/<version>}</li>
 *   <li>Runs {@code mvnw clean deploy} to build and publish to Nexus</li>
 *   <li>Restores the SNAPSHOT version and commits</li>
 * </ol>
 *
 * <p>After all components are checkpointed, a YAML file recording the
 * checkpoint versions and tagged SHAs is written to
 * {@code checkpoints/checkpoint-<name>.yaml} in the workspace root.
 *
 * <p>All components must have a clean working tree before this goal runs.
 *
 * <pre>{@code
 * mvn ike:ws-checkpoint -Dname=sprint-42
 * mvn ike:ws-checkpoint-dry-run -Dname=sprint-42
 * }</pre>
 *
 * @see CheckpointMojo the per-component engine invoked by this goal
 */
@Mojo(name = "ws-checkpoint", requiresProject = false, threadSafe = true)
public class WsCheckpointMojo extends AbstractWorkspaceMojo {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                    .withZone(ZoneOffset.UTC);

    /** Checkpoint name. Used in the YAML filename and component tag paths. */
    @Parameter(property = "name")
    String name;

    /**
     * Show what the checkpoint would do without running builds, writing
     * files, or creating tags. Set automatically by
     * {@code ike:ws-checkpoint-dry-run}.
     */
    @Parameter(property = "dryRun", defaultValue = "false")
    boolean dryRun;

    /** Creates this goal instance. */
    public WsCheckpointMojo() {}

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
        if (dryRun) {
            getLog().info("  Mode:   DRY RUN — no builds, no tags, no files written");
        }
        getLog().info("");

        // ── Validate all components are clean before starting ─────────
        if (!dryRun) {
            validateCleanWorktrees(graph, root);
        }

        // ── Checkpoint each component in dependency order ──────────────
        List<ComponentSnapshot> snapshots = new ArrayList<>();
        List<String> absentComponents = new ArrayList<>();

        List<String> ordered = graph.topologicalSort(
                new LinkedHashSet<>(graph.manifest().components().keySet()));

        for (String compName : ordered) {
            Component component = graph.manifest().components().get(compName);
            File dir = new File(root, compName);
            File gitDir = new File(dir, ".git");

            if (!gitDir.exists()) {
                absentComponents.add(compName);
                getLog().info("  - " + compName + " [absent — skipped]");
                continue;
            }

            String branch   = gitBranch(dir);
            String snapshot = readVersion(dir);
            String checkpointVersion =
                    ReleaseSupport.deriveCheckpointVersion(snapshot, dir);
            String tagName = "checkpoint/" + checkpointVersion;

            var ct = graph.manifest().componentTypes().get(component.type());
            boolean composite = ct != null
                    && "composite".equals(ct.checkpointMechanism());

            if (dryRun) {
                String shortSha = gitShortSha(dir);
                String sha      = gitFullSha(dir);
                File mvnw = ReleaseSupport.resolveMavenWrapper(dir, getLog());
                getLog().info("  ✓ " + compName + " [" + shortSha + "] " + branch);
                getLog().info("    [DRY RUN] " + snapshot
                        + " → " + checkpointVersion);
                getLog().info("    [DRY RUN] Would run: " + mvnw.getName()
                        + " ike:checkpoint -DcheckpointLabel="
                        + checkpointVersion + " -B");
                snapshots.add(new ComponentSnapshot(
                        compName, sha, shortSha, branch,
                        checkpointVersion, false, component.type(), composite));
            } else {
                getLog().info("  ⚙ " + compName + ": "
                        + snapshot + " → " + checkpointVersion);
                checkpointComponent(dir, checkpointVersion);

                // Record the SHA the tag points to, not the post-restore HEAD
                String tagSha = ReleaseSupport.execCapture(dir,
                        "git", "rev-parse", tagName);
                String shortTagSha = tagSha.length() >= 8
                        ? tagSha.substring(0, 8) : tagSha;
                getLog().info("  ✓ " + compName + " ["
                        + shortTagSha + "] → " + tagName);
                snapshots.add(new ComponentSnapshot(
                        compName, tagSha, shortTagSha, branch,
                        checkpointVersion, false, component.type(), composite));
            }
        }

        // ── Build checkpoint YAML ──────────────────────────────────────
        String yamlContent = buildCheckpointYaml(
                name, timestamp, author,
                graph.manifest().schemaVersion(),
                snapshots, absentComponents);

        if (dryRun) {
            getLog().info("");
            getLog().info("[DRY RUN] Checkpoint file would be written to:");
            getLog().info("[DRY RUN]   checkpoints/" + checkpointFileName(name));
            getLog().info("");
            getLog().info("[DRY RUN] Contents:");
            yamlContent.lines().forEach(line ->
                    getLog().info("[DRY RUN]   " + line));
            getLog().info("");
            return;
        }

        // ── Write checkpoint file ──────────────────────────────────────
        Path checkpointsDir = root.toPath().resolve("checkpoints");
        try {
            Files.createDirectories(checkpointsDir);
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Cannot create checkpoints directory", e);
        }
        Path checkpointFile = checkpointsDir.resolve(checkpointFileName(name));
        try {
            Files.writeString(checkpointFile, yamlContent, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Failed to write " + checkpointFile, e);
        }

        getLog().info("");
        getLog().info("  Checkpoint: " + checkpointFile);
        getLog().info("  Components: " + snapshots.size()
                + " | Absent: " + absentComponents.size());
        getLog().info("");
    }

    // ── Per-component checkpoint (overridable for tests) ──────────────

    /**
     * Execute the per-component checkpoint. Invokes {@code ike:checkpoint}
     * as a Maven subprocess in the component directory.
     *
     * <p>Override in tests to substitute a lighter-weight simulation
     * that creates the git tag without running a real build.
     *
     * @param dir             component git root
     * @param checkpointLabel version label (e.g.,
     *                        {@code 1.0.0-checkpoint.20260330.abc1234})
     * @throws MojoExecutionException if the subprocess fails
     */
    protected void checkpointComponent(File dir, String checkpointLabel)
            throws MojoExecutionException {
        File mvnw = ReleaseSupport.resolveMavenWrapper(dir, getLog());
        ReleaseSupport.exec(dir, getLog(),
                mvnw.getAbsolutePath(), "ike:checkpoint",
                "-DcheckpointLabel=" + checkpointLabel, "-B");
    }

    // ── YAML generation (pure, static, testable) ──────────────────────

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
     * @param snapshots       component snapshots (checkpoint versions, not SNAPSHOTs)
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
            if (snap.version() != null) {
                yaml.add("      version: \"" + snap.version() + "\"");
                yaml.add("      tag: \"checkpoint/" + snap.version() + "\"");
            }
            yaml.add("      sha: \"" + snap.sha() + "\"");
            yaml.add("      short-sha: \"" + snap.shortSha() + "\"");
            yaml.add("      branch: \"" + snap.branch() + "\"");
            yaml.add("      type: " + snap.type());
            if (snap.compositeCheckpoint()) {
                yaml.add("      # TODO: add view-coordinate from Tinkar runtime");
            }
        }

        return String.join("\n", yaml) + "\n";
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

    // ── Private helpers ────────────────────────────────────────────────

    /**
     * Validate that all present components have clean working trees.
     * Fails fast before any component is checkpointed.
     */
    private void validateCleanWorktrees(WorkspaceGraph graph, File root)
            throws MojoExecutionException {
        List<String> dirty = new ArrayList<>();
        for (String compName : graph.manifest().components().keySet()) {
            File dir = new File(root, compName);
            if (!new File(dir, ".git").exists()) continue;
            String status = gitStatus(dir);
            if (!status.isEmpty()) {
                dirty.add(compName);
            }
        }
        if (!dirty.isEmpty()) {
            throw new MojoExecutionException(
                    "Cannot checkpoint — dirty working tree in: "
                    + String.join(", ", dirty)
                    + ". Commit or stash changes first.");
        }
    }

    private String gitFullSha(File dir) {
        try {
            return ReleaseSupport.execCapture(dir, "git", "rev-parse", "HEAD");
        } catch (MojoExecutionException e) {
            return "unknown";
        }
    }

    private String readVersion(File dir) throws MojoExecutionException {
        return ReleaseSupport.readPomVersion(new File(dir, "pom.xml"));
    }

    private String resolveAuthor(File root) {
        try {
            return ReleaseSupport.execCapture(root, "git", "config", "user.name");
        } catch (MojoExecutionException e) {
            return System.getProperty("user.name", "unknown");
        }
    }
}
