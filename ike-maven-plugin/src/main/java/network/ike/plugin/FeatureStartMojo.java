package network.ike.plugin;

import network.ike.workspace.Component;
import network.ike.workspace.VersionSupport;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Start a coordinated feature branch across workspace components.
 *
 * <p>Creates a feature branch with a consistent name across the
 * specified components (or group), optionally setting branch-qualified
 * SNAPSHOT versions in each POM.
 *
 * <p><strong>What it does, per component:</strong></p>
 * <ol>
 *   <li>Validates the working tree is clean</li>
 *   <li>Creates branch {@code feature/<name>} from the current HEAD</li>
 *   <li>If the component has a Maven version, sets a branch-qualified
 *       version (e.g., {@code 1.2.0-my-feature-SNAPSHOT})</li>
 *   <li>Commits the version change</li>
 * </ol>
 *
 * <p>Components are processed in topological order so that upstream
 * dependencies get their new versions first.
 *
 * <pre>{@code
 * mvn ike:feature-start -Dfeature=shield-terminology -Dgroup=core
 * mvn ike:feature-start -Dfeature=kec-march-25 -Dgroup=studio
 * mvn ike:feature-start -Dfeature=doc-refresh -Dgroup=docs -DskipVersion=true
 * }</pre>
 */
@Mojo(name = "feature-start", requiresProject = false, threadSafe = true)
public class FeatureStartMojo extends AbstractWorkspaceMojo {

    /** Feature name. Branch will be {@code feature/<name>}. Prompted if omitted. */
    @Parameter(property = "feature")
    String feature;

    /** Restrict to a named group or component. Default: all cloned. */
    @Parameter(property = "group")
    String group;

    /**
     * Skip POM version qualification. Useful for document projects
     * that don't have versioned artifacts.
     */
    @Parameter(property = "skipVersion", defaultValue = "false")
    boolean skipVersion;

    /** Show plan without executing. */
    @Parameter(property = "dryRun", defaultValue = "false")
    boolean dryRun;

    @Override
    public void execute() throws MojoExecutionException {
        feature = requireParam(feature, "feature", "Feature name (branch will be feature/<name>)");

        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        String branchName = "feature/" + feature;

        Set<String> targets;
        if (group != null && !group.isEmpty()) {
            targets = graph.expandGroup(group);
        } else {
            targets = graph.manifest().components().keySet();
        }

        List<String> sorted = graph.topologicalSort(new LinkedHashSet<>(targets));

        getLog().info("");
        getLog().info("IKE Workspace — Feature Start");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Feature: " + feature);
        getLog().info("  Branch:  " + branchName);
        getLog().info("  Scope:   " + (group != null ? group : "all")
                + " (" + sorted.size() + " components)");
        if (dryRun) {
            getLog().info("  Mode:    DRY RUN");
        }
        getLog().info("");

        List<String> created = new ArrayList<>();
        List<String> skippedNotCloned = new ArrayList<>();
        List<String> skippedAlreadyOnBranch = new ArrayList<>();

        for (String name : sorted) {
            Component component = graph.manifest().components().get(name);
            File dir = new File(root, name);
            File gitDir = new File(dir, ".git");

            if (!gitDir.exists()) {
                skippedNotCloned.add(name);
                getLog().info("  ⚠ " + name + " — not cloned, skipping");
                continue;
            }

            // Check if already on target branch
            String currentBranch = gitBranch(dir);
            if (currentBranch.equals(branchName)) {
                skippedAlreadyOnBranch.add(name);
                getLog().info("  ✓ " + name + " — already on " + branchName);
                continue;
            }

            // Validate clean worktree
            String status = gitStatus(dir);
            if (!status.isEmpty()) {
                throw new MojoExecutionException(
                        name + " has uncommitted changes. Commit or stash before starting a feature.");
            }

            if (dryRun) {
                String versionInfo = "";
                if (!skipVersion && component.version() != null) {
                    String newVersion = VersionSupport.branchQualifiedVersion(
                            component.version(), branchName);
                    versionInfo = " → " + newVersion;
                }
                getLog().info("  [dry-run] " + name + " — would create "
                        + branchName + versionInfo);
                created.add(name);
                continue;
            }

            // Create branch
            getLog().info("  → " + name + " — creating " + branchName);
            ReleaseSupport.exec(dir, getLog(),
                    "git", "checkout", "-b", branchName);

            // Set branch-qualified version if applicable
            if (!skipVersion && component.version() != null
                    && !component.version().isEmpty()) {
                String newVersion = VersionSupport.branchQualifiedVersion(
                        component.version(), branchName);
                getLog().info("    version: " + component.version()
                        + " → " + newVersion);

                setPomVersion(dir, component.version(), newVersion);
                ReleaseSupport.exec(dir, getLog(),
                        "git", "add", "pom.xml");
                ReleaseSupport.exec(dir, getLog(),
                        "git", "commit", "-m",
                        "feature: set version " + newVersion
                                + " for " + branchName);
            }

            created.add(name);
        }

        getLog().info("");
        getLog().info("  Created: " + created.size()
                + " | Already on branch: " + skippedAlreadyOnBranch.size()
                + " | Not cloned: " + skippedNotCloned.size());
        getLog().info("");
    }

    /**
     * Set the POM version, handling both simple and multi-module projects.
     * Uses ReleaseSupport's POM manipulation which skips the parent block.
     */
    private void setPomVersion(File dir, String oldVersion, String newVersion)
            throws MojoExecutionException {
        File pom = new File(dir, "pom.xml");
        if (!pom.exists()) {
            getLog().warn("    No pom.xml found in " + dir.getName());
            return;
        }

        // Set version in root POM
        ReleaseSupport.setPomVersion(pom, oldVersion, newVersion);

        // Also update any submodule POMs that reference the old version
        // in their <parent> block (for multi-module projects)
        try {
            List<File> allPoms = ReleaseSupport.findPomFiles(dir);
            for (File subPom : allPoms) {
                if (subPom.equals(pom)) continue;
                try {
                    String content = java.nio.file.Files.readString(
                            subPom.toPath(), java.nio.charset.StandardCharsets.UTF_8);
                    if (content.contains("<version>" + oldVersion + "</version>")) {
                        String updated = content.replace(
                                "<version>" + oldVersion + "</version>",
                                "<version>" + newVersion + "</version>");
                        java.nio.file.Files.writeString(
                                subPom.toPath(), updated,
                                java.nio.charset.StandardCharsets.UTF_8);
                        String rel = dir.toPath().relativize(subPom.toPath()).toString();
                        getLog().info("    updated: " + rel);
                        ReleaseSupport.exec(dir, getLog(), "git", "add", rel);
                    }
                } catch (java.io.IOException e) {
                    getLog().warn("    Could not update " + subPom + ": " + e.getMessage());
                }
            }
        } catch (MojoExecutionException e) {
            getLog().warn("    Could not scan for submodule POMs: " + e.getMessage());
        }
    }
}
