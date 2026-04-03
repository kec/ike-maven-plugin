package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;

import network.ike.workspace.Component;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import network.ike.workspace.VersionSupport;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workspace-level release — release all dirty checked-out components
 * in topological order.
 *
 * <p>Scans checked-out components for commits since their last release
 * tag. Dirty components are topologically sorted and released in
 * dependency order. After each release, downstream parent version
 * references are updated automatically.</p>
 *
 * <p><strong>What it does, per component:</strong></p>
 * <ol>
 *   <li>Detect latest release tag ({@code v*})</li>
 *   <li>Check for commits since that tag</li>
 *   <li>If dirty: run {@code mvn ike:release} in that component's directory</li>
 *   <li>After release: update parent version in downstream components</li>
 * </ol>
 *
 * <p>The cascade is self-limiting: only checked-out components with
 * changes since their last release are candidates. Components not
 * present in the aggregator are not considered.</p>
 *
 * <pre>{@code
 * mvn ike:ws-release                         # release all dirty components
 * mvn ike:ws-release -DdryRun=true           # show what would be released
 * mvn ike:ws-release -Dgroup=core            # only components in the core group
 * mvn ike:ws-release -Dcomponent=ike-pipeline  # release one specific component
 * }</pre>
 */
@Mojo(name = "release", requiresProject = false, threadSafe = true)
public class WsReleaseMojo extends AbstractWorkspaceMojo {

    private static final DateTimeFormatter ISO_UTC =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                    .withZone(ZoneOffset.UTC);

    /** Release only components in this group. */
    @Parameter(property = "group")
    String group;

    /** Release only this specific component. */
    @Parameter(property = "component")
    String component;

    /** Preview what would be released without executing. */
    @Parameter(property = "dryRun", defaultValue = "false")
    boolean dryRun;

    /** Skip the pre-release checkpoint. */
    @Parameter(property = "skipCheckpoint", defaultValue = "false")
    boolean skipCheckpoint;

    /** Push releases to remote. Passed through to ike:release. */
    @Parameter(property = "push", defaultValue = "true")
    boolean push;

    /** Creates this goal instance. */
    public WsReleaseMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        // ── 1. Determine candidate components ─────────────────────────
        List<String> candidates;
        if (component != null) {
            candidates = List.of(component);
        } else if (group != null) {
            candidates = new ArrayList<>(graph.expandGroup(group));
        } else {
            candidates = graph.topologicalSort();
        }

        // ── 2. Filter to checked-out and dirty ────────────────────────
        Map<String, ReleaseCandidate> dirty = new LinkedHashMap<>();
        for (String name : graph.topologicalSort()) {
            if (!candidates.contains(name)) continue;

            Component comp = graph.manifest().components().get(name);
            if (comp == null) continue;

            File compDir = new File(root, name);
            if (!compDir.isDirectory() || !new File(compDir, "pom.xml").exists()) {
                getLog().debug("Skipping " + name + " — not checked out");
                continue;
            }

            String latestTag = latestReleaseTag(compDir);
            if (latestTag == null) {
                // No release tag exists — component has never been released
                dirty.put(name, new ReleaseCandidate(name, comp, compDir,
                        null, "never released"));
                continue;
            }

            int commitsSinceTag = commitsSinceTag(compDir, latestTag);
            if (commitsSinceTag > 0) {
                dirty.put(name, new ReleaseCandidate(name, comp, compDir,
                        latestTag, commitsSinceTag + " commits since " + latestTag));
                continue;
            }

            getLog().debug("Skipping " + name + " — clean (at " + latestTag + ")");
        }

        if (dirty.isEmpty()) {
            getLog().info("No components need releasing. All are clean.");
            return;
        }

        // ── 3. Topological sort of dirty components ───────────────────
        List<String> releaseOrder = graph.topologicalSort().stream()
                .filter(dirty::containsKey)
                .toList();

        // ── 4. Report plan ────────────────────────────────────────────
        getLog().info("════════════════════════════════════════════════════");
        getLog().info(dryRun ? "  WORKSPACE RELEASE — DRY RUN" : "  WORKSPACE RELEASE");
        getLog().info("════════════════════════════════════════════════════");
        getLog().info("");
        getLog().info("Components to release (" + releaseOrder.size() + "):");
        for (int i = 0; i < releaseOrder.size(); i++) {
            ReleaseCandidate rc = dirty.get(releaseOrder.get(i));
            String version = currentVersion(rc.dir);
            getLog().info("  " + (i + 1) + ". " + rc.name
                    + " (" + version + ") — " + rc.reason);
        }
        getLog().info("");

        if (dryRun) {
            getLog().info("[DRY RUN] No releases executed.");
            return;
        }

        // ── 5. Pre-release checkpoint ─────────────────────────────────
        if (!skipCheckpoint) {
            String checkpointName = "pre-release-"
                    + Instant.now().atZone(ZoneOffset.UTC)
                            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            getLog().info("Creating pre-release checkpoint: " + checkpointName);
            writeCheckpoint(root, graph, checkpointName);
        }

        // ── 6. Release each component in order ────────────────────────
        List<String> released = new ArrayList<>();
        Map<String, String> releasedVersions = new LinkedHashMap<>();

        for (String name : releaseOrder) {
            ReleaseCandidate rc = dirty.get(name);
            getLog().info("");
            getLog().info("────────────────────────────────────────────────");
            getLog().info("  Releasing: " + rc.name);
            getLog().info("────────────────────────────────────────────────");

            // Update parent version if an upstream component was just released
            updateParentVersions(rc, releasedVersions);

            // Derive release version from current SNAPSHOT
            String currentVersion = currentVersion(rc.dir);
            String releaseVersion = currentVersion.replace("-SNAPSHOT", "");

            try {
                // Find mvnw or mvn
                String mvn = findMvn(rc.dir);

                ReleaseSupport.exec(rc.dir, getLog(),
                        mvn, "ike:release",
                        "-DpushRelease=" + push,
                        "-B");

                released.add(rc.name);
                releasedVersions.put(rc.name, releaseVersion);
                getLog().info("  ✓ Released " + rc.name + " " + releaseVersion);
            } catch (Exception e) {
                getLog().error("  ✗ Failed to release " + rc.name + ": " + e.getMessage());
                getLog().error("");
                getLog().error("Released so far: " + released);
                getLog().error("Failed at: " + rc.name);
                getLog().error("Remaining: " + releaseOrder.subList(
                        releaseOrder.indexOf(name) + 1, releaseOrder.size()));
                throw new MojoExecutionException(
                        "Workspace release failed at " + rc.name, e);
            }
        }

        // ── 7. Summary ───────────────────────────────────────────────
        getLog().info("");
        getLog().info("════════════════════════════════════════════════════");
        getLog().info("  WORKSPACE RELEASE COMPLETE");
        getLog().info("════════════════════════════════════════════════════");
        for (var entry : releasedVersions.entrySet()) {
            getLog().info("  " + entry.getKey() + " → " + entry.getValue());
        }
        getLog().info("");
    }

    // ── Helper: find latest release tag ──────────────────────────────

    private String latestReleaseTag(File compDir) {
        try {
            String tags = ReleaseSupport.execCapture(compDir,
                    "git", "tag", "-l", "v*", "--sort=-version:refname");
            if (tags == null || tags.isBlank()) return null;
            return tags.lines().findFirst().orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    // ── Helper: count commits since tag ──────────────────────────────

    private int commitsSinceTag(File compDir, String tag) {
        try {
            String count = ReleaseSupport.execCapture(compDir,
                    "git", "rev-list", tag + "..HEAD", "--count");
            return Integer.parseInt(count.strip());
        } catch (Exception e) {
            return -1;
        }
    }

    // ── Helper: read current POM version ─────────────────────────────

    private String currentVersion(File compDir) {
        try {
            Path pom = compDir.toPath().resolve("pom.xml");
            String content = Files.readString(pom, StandardCharsets.UTF_8);
            return extractVersionFromPom(content);
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Extract the first {@code <version>} value from POM XML content.
     *
     * <p>This is a simple regex extraction — finds the first
     * {@code <version>...</version>} in the content. Good enough for
     * workspace POMs where the project version appears early.
     *
     * @param pomContent raw POM XML as a string
     * @return the version string, or {@code "unknown"} if not found
     */
    public static String extractVersionFromPom(String pomContent) {
        if (pomContent == null || pomContent.isBlank()) return "unknown";
        var matcher = java.util.regex.Pattern.compile(
                "<version>([^<]+)</version>").matcher(pomContent);
        if (matcher.find()) return matcher.group(1);
        return "unknown";
    }

    // ── Helper: update parent/BOM version in downstream POM ─────────

    private void updateParentVersions(ReleaseCandidate rc,
                                       Map<String, String> releasedVersions) {
        if (releasedVersions.isEmpty()) return;

        try {
            Path pom = rc.dir.toPath().resolve("pom.xml");
            String content = Files.readString(pom, StandardCharsets.UTF_8);
            String original = content;

            // Use explicit version-property declarations from depends-on edges
            for (network.ike.workspace.Dependency dep : rc.component.dependsOn()) {
                String upstreamName = dep.component();
                if (!releasedVersions.containsKey(upstreamName)) continue;

                String releasedVersion = releasedVersions.get(upstreamName);
                String nextSnapshot = bumpToNextSnapshot(releasedVersion);

                // Always try parent version update (matches <parent><artifactId>)
                content = updateParentVersion(content, upstreamName, nextSnapshot);

                // Use declared version-property if present
                if (dep.versionProperty() != null) {
                    content = updateVersionProperty(content, dep.versionProperty(), nextSnapshot);
                    getLog().info("    " + rc.name + ": " + dep.versionProperty()
                            + " → " + nextSnapshot
                            + " (from " + upstreamName + " " + releasedVersion + ")");
                } else {
                    // Fallback: heuristic property name matching
                    String propName1 = upstreamName + ".version";
                    String propName2 = upstreamName.replace("-", ".") + ".version";
                    for (String prop : List.of(propName1, propName2)) {
                        String before = content;
                        content = updateVersionProperty(content, prop, nextSnapshot);
                        if (!content.equals(before)) {
                            getLog().info("    " + rc.name + ": " + prop
                                    + " → " + nextSnapshot
                                    + " (heuristic, from " + upstreamName + ")");
                        }
                    }
                }
            }

            if (!content.equals(original)) {
                Files.writeString(pom, content, StandardCharsets.UTF_8);
                getLog().info("  Updated version references in " + rc.name + "/pom.xml");

                // Stage and commit the version update
                ReleaseSupport.exec(rc.dir, getLog(),
                        "git", "add", "pom.xml");
                ReleaseSupport.exec(rc.dir, getLog(),
                        "git", "commit", "-m",
                        "chore: bump dependency versions after upstream release");
            }
        } catch (IOException | MojoExecutionException e) {
            getLog().warn("Could not update parent versions in " + rc.name + ": " + e);
        }
    }

    /**
     * Update the parent version in POM content when the parent's
     * artifactId matches {@code parentArtifactId}.
     *
     * <p>Matches the pattern:
     * {@code <parent>...<artifactId>name</artifactId>...<version>old</version>...</parent>}
     * and replaces the version with {@code newVersion}.
     *
     * @param pomContent       raw POM XML as a string
     * @param parentArtifactId the parent artifact ID to match
     * @param newVersion       the new version to set
     * @return the updated POM content (unchanged if no match)
     */
    public static String updateParentVersion(String pomContent,
                                              String parentArtifactId,
                                              String newVersion) {
        var parentPattern = java.util.regex.Pattern.compile(
                "(<parent>\\s*" +
                "<groupId>[^<]+</groupId>\\s*" +
                "<artifactId>" + java.util.regex.Pattern.quote(parentArtifactId)
                        + "</artifactId>\\s*" +
                "<version>)[^<]+(</version>)",
                java.util.regex.Pattern.DOTALL);
        return parentPattern.matcher(pomContent)
                .replaceFirst("$1" + newVersion + "$2");
    }

    /**
     * Delegate to shared utility in {@link ReleaseSupport}.
     *
     * @param pomContent   the POM file content as a string
     * @param propertyName the property element name to update
     * @param newVersion   the new version value
     * @return the updated POM content
     */
    public static String updateVersionProperty(String pomContent,
                                                String propertyName,
                                                String newVersion) {
        return ReleaseSupport.updateVersionProperty(pomContent, propertyName, newVersion);
    }

    // ── Helper: version bump ─────────────────────────────────────────

    private String bumpToNextSnapshot(String releaseVersion) {
        return VersionSupport.deriveNextSnapshot(releaseVersion);
    }

    // ── Helper: write checkpoint YAML ────────────────────────────────

    private void writeCheckpoint(File root, WorkspaceGraph graph, String name)
            throws MojoExecutionException {
        Path checkpointsDir = root.toPath().resolve("checkpoints");
        try {
            Files.createDirectories(checkpointsDir);
            Path file = checkpointsDir.resolve("checkpoint-" + name + ".yaml");

            // Gather component data for the pure function
            String timestamp = ISO_UTC.format(Instant.now());
            List<String[]> componentData = new ArrayList<>();
            for (String compName : graph.topologicalSort()) {
                File compDir = new File(root, compName);
                if (!compDir.isDirectory()) continue;
                componentData.add(new String[]{
                        compName, gitBranch(compDir), gitShortSha(compDir),
                        currentVersion(compDir),
                        String.valueOf(!gitStatus(compDir).isEmpty())
                });
            }

            String yaml = buildPreReleaseCheckpointYaml(name, timestamp, componentData);
            Files.writeString(file, yaml, StandardCharsets.UTF_8);
            getLog().info("Checkpoint written: " + file);
        } catch (IOException e) {
            getLog().warn("Could not write checkpoint: " + e.getMessage());
        }
    }

    /**
     * Build pre-release checkpoint YAML content from pre-gathered
     * component data.
     *
     * <p>This is a pure function with no git or I/O dependencies,
     * suitable for direct unit testing.
     *
     * @param name          checkpoint name
     * @param timestamp     ISO-8601 UTC timestamp
     * @param componentData list of {@code [name, branch, sha, version, dirty]}
     *                      arrays for each present component
     * @return YAML checkpoint content
     */
    public static String buildPreReleaseCheckpointYaml(
            String name, String timestamp, List<String[]> componentData) {
        StringBuilder yaml = new StringBuilder();
        yaml.append("# Workspace checkpoint: ").append(name).append("\n");
        yaml.append("# Generated: ").append(timestamp).append("\n");
        yaml.append("checkpoint: ").append(name).append("\n");
        yaml.append("timestamp: ").append(timestamp).append("\n");
        yaml.append("components:\n");

        for (String[] comp : componentData) {
            yaml.append("  ").append(comp[0]).append(":\n");
            yaml.append("    branch: ").append(comp[1]).append("\n");
            yaml.append("    sha: ").append(comp[2]).append("\n");
            yaml.append("    version: ").append(comp[3]).append("\n");
            yaml.append("    dirty: ").append(comp[4]).append("\n");
        }

        return yaml.toString();
    }

    // ── Helper: find mvn or mvnw ─────────────────────────────────────

    private String findMvn(File compDir) {
        return resolveMvnCommand(compDir);
    }

    /**
     * Resolve the Maven executable for a component directory.
     *
     * <p>Checks for {@code mvnw} (executable) and {@code mvnw.cmd} in
     * the given directory. Falls back to {@code "mvn"} from the system
     * PATH if no wrapper is found.
     *
     * @param compDir the component directory to check
     * @return absolute path to mvnw/mvnw.cmd, or {@code "mvn"}
     */
    public static String resolveMvnCommand(File compDir) {
        File mvnw = new File(compDir, "mvnw");
        if (mvnw.exists() && mvnw.canExecute()) {
            return mvnw.getAbsolutePath();
        }
        File mvnwCmd = new File(compDir, "mvnw.cmd");
        if (mvnwCmd.exists()) {
            return mvnwCmd.getAbsolutePath();
        }
        return "mvn";
    }

    // ── Record for candidate tracking ────────────────────────────────

    private record ReleaseCandidate(
            String name,
            Component component,
            File dir,
            String lastTag,
            String reason) {}
}
