package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;
import network.ike.workspace.Component;
import network.ike.workspace.Dependency;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Align inter-component dependency versions in POM files to match the
 * current workspace component versions.
 *
 * <p>This is the {@code ws:align} goal. For each component on disk, it
 * scans POM dependency declarations. When a dependency's groupId matches
 * another workspace component and the declared version does not match
 * that component's current POM version, the dependency version is
 * updated.
 *
 * <p>Property-based versions (e.g., {@code <ike-bom.version>}) are
 * updated via {@code ReleaseSupport.updateVersionProperty()}. Direct
 * {@code <version>} tags in dependency blocks are updated via text
 * replacement.
 *
 * <pre>{@code
 * mvn ws:align                    # apply changes
 * mvn ws:align -DdryRun=true      # report only
 * }</pre>
 */
@Mojo(name = "align", requiresProject = false, threadSafe = true)
public class WsAlignMojo extends AbstractWorkspaceMojo {

    /**
     * When true, report changes without writing to POM files.
     */
    @Parameter(property = "dryRun", defaultValue = "false")
    private boolean dryRun;

    /** Creates this goal instance. */
    public WsAlignMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("");
        getLog().info("IKE Workspace Align — synchronize inter-component dependency versions");
        getLog().info("══════════════════════════════════════════════════════════════");

        if (dryRun) {
            getLog().info("  (dry run — no files will be modified)");
            getLog().info("");
        }

        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        // Build lookup: groupId → (component name, current POM version)
        Map<String, ComponentVersion> groupIdIndex = buildGroupIdIndex(graph, root);

        int totalChanges = 0;
        List<String> changedComponents = new ArrayList<>();

        for (Map.Entry<String, Component> entry : graph.manifest().components().entrySet()) {
            String name = entry.getKey();
            Component component = entry.getValue();
            File componentDir = new File(root, name);

            if (!new File(componentDir, "pom.xml").exists()) {
                getLog().debug("  " + name + ": not cloned — skipping");
                continue;
            }

            // Find all POM files in this component
            List<File> pomFiles;
            try {
                pomFiles = ReleaseSupport.findPomFiles(componentDir);
            } catch (MojoExecutionException e) {
                getLog().warn("  " + name + ": could not scan POM files — "
                        + e.getMessage());
                continue;
            }

            // Also use the declared depends-on to find version-property hints
            Map<String, String> versionPropertyMap = new LinkedHashMap<>();
            for (Dependency dep : component.dependsOn()) {
                if (dep.versionProperty() != null && !dep.versionProperty().isEmpty()) {
                    Component target = graph.manifest().components().get(dep.component());
                    if (target != null && target.groupId() != null
                            && !target.groupId().isEmpty()) {
                        versionPropertyMap.put(dep.component(), dep.versionProperty());
                    }
                }
            }

            int componentChanges = 0;

            for (File pomFile : pomFiles) {
                int changes = alignPomDependencies(
                        name, pomFile, groupIdIndex, versionPropertyMap, componentDir);
                componentChanges += changes;
            }

            if (componentChanges > 0) {
                totalChanges += componentChanges;
                changedComponents.add(name);
            }
        }

        // --- Summary ---
        getLog().info("");
        if (totalChanges == 0) {
            getLog().info("  All inter-component dependency versions are aligned  ✓");
        } else if (dryRun) {
            getLog().info("  " + totalChanges + " version(s) would be updated across "
                    + changedComponents.size() + " component(s)");
            getLog().info("  Run without -DdryRun to apply changes.");
        } else {
            getLog().info("  Updated " + totalChanges + " version(s) across "
                    + changedComponents.size() + " component(s)");
        }
        getLog().info("");
    }

    // ── GroupId index ───────────────────────────────────────────────

    /**
     * Build an index from Maven groupId to (component name, current
     * POM version) for all cloned workspace components.
     */
    private Map<String, ComponentVersion> buildGroupIdIndex(
            WorkspaceGraph graph, File root) throws MojoExecutionException {
        Map<String, ComponentVersion> index = new LinkedHashMap<>();

        for (Map.Entry<String, Component> entry : graph.manifest().components().entrySet()) {
            String name = entry.getKey();
            Component component = entry.getValue();
            File pomFile = new File(root, name + "/pom.xml");

            if (!pomFile.exists()) {
                continue;
            }

            String groupId = component.groupId();
            if (groupId == null || groupId.isEmpty()) {
                // Try reading from POM
                groupId = WsFixMojo.readPomGroupId(pomFile);
            }

            String pomVersion;
            try {
                pomVersion = ReleaseSupport.readPomVersion(pomFile);
            } catch (MojoExecutionException e) {
                getLog().warn("  " + name + ": could not read POM version — "
                        + e.getMessage());
                continue;
            }

            if (groupId != null && !groupId.isEmpty()) {
                index.put(groupId, new ComponentVersion(name, pomVersion));
            }
        }

        return index;
    }

    // ── POM dependency alignment ────────────────────────────────────

    /**
     * Scan a single POM file for dependencies whose groupId matches a
     * workspace component, and update mismatched versions.
     *
     * @return number of changes made (or that would be made in dry-run)
     */
    private int alignPomDependencies(String ownerName, File pomFile,
                                     Map<String, ComponentVersion> groupIdIndex,
                                     Map<String, String> versionPropertyMap,
                                     File componentDir)
            throws MojoExecutionException {
        String content;
        try {
            content = Files.readString(pomFile.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read " + pomFile, e);
        }

        String updated = content;
        int changes = 0;

        for (Map.Entry<String, ComponentVersion> entry : groupIdIndex.entrySet()) {
            String targetGroupId = entry.getKey();
            ComponentVersion target = entry.getValue();

            // Skip self-references (same component)
            if (target.name.equals(ownerName)) {
                continue;
            }

            // Find all <dependency> blocks referencing this groupId
            // Pattern: <dependency> ... <groupId>target</groupId> ... <version>X</version> ... </dependency>
            Pattern depPattern = Pattern.compile(
                    "(?s)(<dependency>\\s*" +
                    "<groupId>" + Pattern.quote(targetGroupId) + "</groupId>\\s*" +
                    "<artifactId>[^<]+</artifactId>\\s*" +
                    "<version>)([^<]+)(</version>)",
                    Pattern.MULTILINE
            );

            Matcher m = depPattern.matcher(updated);
            StringBuilder sb = new StringBuilder();
            boolean found = false;

            while (m.find()) {
                String currentVersion = m.group(2).trim();

                // Check if this is a property reference like ${...}
                if (currentVersion.startsWith("${") && currentVersion.endsWith("}")) {
                    // Property-based version — handle via version-property update
                    String propName = currentVersion.substring(2, currentVersion.length() - 1);
                    String propResult = updatePropertyVersion(
                            ownerName, pomFile, updated, propName,
                            target, componentDir);
                    if (propResult != null) {
                        updated = propResult;
                        changes++;
                    }
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                } else if (!currentVersion.equals(target.version)) {
                    // Direct version mismatch
                    String relPath = componentDir.toPath().relativize(
                            pomFile.toPath()).toString();
                    getLog().info("  " + ownerName + " (" + relPath + "): "
                            + targetGroupId + " " + currentVersion
                            + " → " + target.version);
                    m.appendReplacement(sb,
                            Matcher.quoteReplacement(m.group(1) + target.version + m.group(3)));
                    found = true;
                    changes++;
                } else {
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group(0)));
                }
            }

            if (found) {
                m.appendTail(sb);
                updated = sb.toString();
            }
        }

        // Also handle version-property updates declared in depends-on
        for (Map.Entry<String, String> vpEntry : versionPropertyMap.entrySet()) {
            String targetComponent = vpEntry.getKey();
            String versionProperty = vpEntry.getValue();
            Component target = loadGraph().manifest().components().get(targetComponent);

            if (target == null) continue;

            ComponentVersion cv = groupIdIndex.get(target.groupId());
            if (cv == null) continue;

            // Check if the property exists in this POM
            Pattern propPattern = Pattern.compile(
                    "<" + Pattern.quote(versionProperty) + ">([^<]+)</"
                    + Pattern.quote(versionProperty) + ">"
            );
            Matcher pm = propPattern.matcher(updated);
            if (pm.find()) {
                String currentValue = pm.group(1).trim();
                if (!currentValue.equals(cv.version)) {
                    String relPath = componentDir.toPath().relativize(
                            pomFile.toPath()).toString();
                    getLog().info("  " + ownerName + " (" + relPath + "): property <"
                            + versionProperty + "> " + currentValue
                            + " → " + cv.version);
                    updated = ReleaseSupport.updateVersionProperty(
                            updated, versionProperty, cv.version);
                    changes++;
                }
            }
        }

        // Write if changed
        if (changes > 0 && !dryRun && !updated.equals(content)) {
            try {
                Files.writeString(pomFile.toPath(), updated, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new MojoExecutionException(
                        "Failed to write " + pomFile + ": " + e.getMessage(), e);
            }
        }

        return changes;
    }

    /**
     * Update a property-based version in the POM content.
     *
     * @return updated content if changed, null if no change needed
     */
    private String updatePropertyVersion(String ownerName, File pomFile,
                                         String content, String propertyName,
                                         ComponentVersion target,
                                         File componentDir) {
        Pattern propPattern = Pattern.compile(
                "<" + Pattern.quote(propertyName) + ">([^<]+)</"
                + Pattern.quote(propertyName) + ">"
        );
        Matcher m = propPattern.matcher(content);
        if (m.find()) {
            String currentValue = m.group(1).trim();
            if (!currentValue.equals(target.version)) {
                String relPath = componentDir.toPath().relativize(
                        pomFile.toPath()).toString();
                getLog().info("  " + ownerName + " (" + relPath + "): property <"
                        + propertyName + "> " + currentValue
                        + " → " + target.version);
                return ReleaseSupport.updateVersionProperty(
                        content, propertyName, target.version);
            }
        }
        return null;
    }

    // ── Internal record ─────────────────────────────────────────────

    /**
     * Associates a component name with its current POM version.
     */
    private record ComponentVersion(String name, String version) {}
}
