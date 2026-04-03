package network.ike.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scans a Maven component root to determine the complete set of
 * published artifacts (groupId:artifactId pairs).
 *
 * <p>This is the "published artifact set" from the handoff design:
 * given a component root directory, recursively walk the POM hierarchy
 * (root POM plus all subprojects/modules) and collect every
 * groupId:artifactId pair that the component publishes.
 *
 * <p>POM parsing uses simple regex matching (consistent with the
 * {@code ReleaseSupport} pattern) rather than a full XML parser.
 * The {@code <parent>} block is stripped before extracting the
 * project's own groupId and artifactId; if no groupId is declared
 * outside the parent block, the parent's groupId is inherited.
 */
public final class PublishedArtifactSet {

    private PublishedArtifactSet() {}

    /**
     * A published Maven artifact coordinate.
     *
     * @param groupId    the Maven groupId
     * @param artifactId the Maven artifactId
     */
    public record Artifact(String groupId, String artifactId) {}

    private static final Pattern VERSION_PATTERN =
            Pattern.compile("<version>([^<]+)</version>");
    private static final Pattern GROUP_ID_PATTERN =
            Pattern.compile("<groupId>([^<]+)</groupId>");
    private static final Pattern ARTIFACT_ID_PATTERN =
            Pattern.compile("<artifactId>([^<]+)</artifactId>");
    private static final Pattern SUBPROJECTS_PATTERN =
            Pattern.compile("<subproject>([^<]+)</subproject>");
    private static final Pattern MODULES_PATTERN =
            Pattern.compile("<module>([^<]+)</module>");
    private static final Pattern PARENT_BLOCK =
            Pattern.compile("(?s)<parent>.*?</parent>");

    /**
     * Scan a component root and return the complete set of published
     * artifacts (groupId:artifactId pairs).
     *
     * <p>Reads the root pom.xml, extracts its coordinates, then
     * recursively descends into each subproject (or module) directory
     * to collect all published artifacts.
     *
     * @param componentRoot the root directory of the Maven component
     * @return the set of all published artifacts
     * @throws IOException if a POM file cannot be read
     */
    public static Set<Artifact> scan(Path componentRoot) throws IOException {
        Set<Artifact> artifacts = new LinkedHashSet<>();
        Path rootPom = componentRoot.resolve("pom.xml");

        if (!Files.exists(rootPom)) {
            return artifacts;
        }

        scanPom(componentRoot, rootPom, null, artifacts);
        return artifacts;
    }

    /**
     * Check whether a groupId:artifactId pair is in the published set.
     *
     * @param artifacts  the set from {@link #scan(Path)}
     * @param groupId    the groupId to check
     * @param artifactId the artifactId to check
     * @return true if the pair is in the set
     */
    public static boolean matches(Set<Artifact> artifacts,
                                  String groupId, String artifactId) {
        return artifacts.contains(new Artifact(groupId, artifactId));
    }

    /**
     * Parse a single POM, add its artifact to the set, then recurse
     * into any declared subprojects or modules.
     *
     * @param componentRoot  the component root (for resolving relative paths)
     * @param pomPath        the POM file to parse
     * @param inheritGroupId the parent groupId to inherit if not declared
     * @param artifacts      accumulator for discovered artifacts
     */
    private static void scanPom(Path componentRoot, Path pomPath,
                                String inheritGroupId,
                                Set<Artifact> artifacts) throws IOException {
        String content = Files.readString(pomPath, StandardCharsets.UTF_8);

        // Extract groupId from parent block (for inheritance)
        String parentGroupId = null;
        Matcher parentMatcher = PARENT_BLOCK.matcher(content);
        if (parentMatcher.find()) {
            String parentBlock = parentMatcher.group();
            Matcher gm = GROUP_ID_PATTERN.matcher(parentBlock);
            if (gm.find()) {
                parentGroupId = gm.group(1).trim();
            }
        }

        // Strip parent block to find project's own coordinates
        String stripped = PARENT_BLOCK.matcher(content).replaceFirst("");

        // Extract project groupId (outside parent block)
        String groupId = null;
        Matcher gidMatcher = GROUP_ID_PATTERN.matcher(stripped);
        if (gidMatcher.find()) {
            groupId = gidMatcher.group(1).trim();
        }

        // Inherit groupId: prefer own, then parent block, then caller
        if (groupId == null) {
            groupId = parentGroupId;
        }
        if (groupId == null) {
            groupId = inheritGroupId;
        }

        // Extract artifactId (outside parent block)
        String artifactId = null;
        Matcher aidMatcher = ARTIFACT_ID_PATTERN.matcher(stripped);
        if (aidMatcher.find()) {
            artifactId = aidMatcher.group(1).trim();
        }

        if (groupId != null && artifactId != null) {
            artifacts.add(new Artifact(groupId, artifactId));
        }

        // The groupId to pass down for inheritance
        String effectiveGroupId = groupId;

        // Find subprojects (POM 4.1.0) or modules (POM 4.0.0)
        Path pomDir = pomPath.getParent();

        // Scan <subproject> entries first (newer model)
        Matcher subMatcher = SUBPROJECTS_PATTERN.matcher(content);
        while (subMatcher.find()) {
            String subproject = subMatcher.group(1).trim();
            Path subPom = pomDir.resolve(subproject).resolve("pom.xml");
            if (Files.exists(subPom)) {
                scanPom(componentRoot, subPom, effectiveGroupId, artifacts);
            }
        }

        // Scan <module> entries (classic model)
        Matcher modMatcher = MODULES_PATTERN.matcher(content);
        while (modMatcher.find()) {
            String module = modMatcher.group(1).trim();
            Path modPom = pomDir.resolve(module).resolve("pom.xml");
            if (Files.exists(modPom)) {
                scanPom(componentRoot, modPom, effectiveGroupId, artifacts);
            }
        }
    }
}
