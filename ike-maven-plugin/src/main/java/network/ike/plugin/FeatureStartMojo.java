package network.ike.plugin;

import network.ike.workspace.Component;
import network.ike.workspace.ManifestWriter;
import network.ike.workspace.VersionSupport;
import network.ike.workspace.WorkspaceGraph;
import network.ike.plugin.vcs.VcsOperations;
import network.ike.plugin.vcs.VcsState;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Start a coordinated feature branch across workspace components.
 *
 * <p>Creates a feature branch with a consistent name across the
 * specified components (or group), optionally setting branch-qualified
 * SNAPSHOT versions in each POM.
 *
 * <p><strong>Workspace mode</strong> (workspace.yaml found):</p>
 * <ol>
 *   <li>Validates the working tree is clean</li>
 *   <li>Creates branch {@code feature/<name>} from the current HEAD</li>
 *   <li>If the component has a Maven version, sets a branch-qualified
 *       version (e.g., {@code 1.2.0-my-feature-SNAPSHOT})</li>
 *   <li>Commits the version change</li>
 *   <li>Updates workspace.yaml branch fields for all branched components</li>
 *   <li>Commits the workspace.yaml change</li>
 * </ol>
 *
 * <p><strong>Bare mode</strong> (no workspace.yaml):</p>
 * <ol>
 *   <li>Creates the feature branch in the current repo only</li>
 *   <li>Sets version-qualified SNAPSHOT in the current repo's POMs</li>
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

    /** Creates this goal instance. */
    public FeatureStartMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        feature = requireParam(feature, "feature", "Feature name (branch will be feature/<name>)");
        String branchName = "feature/" + feature;

        if (!isWorkspaceMode()) {
            executeBareMode(branchName);
            return;
        }

        // --- Workspace mode ---
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        // VCS bridge: catch-up before branching
        VcsOperations.catchUp(root, getLog());

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
                getLog().info("  \u26A0 " + name + " \u2014 not cloned, skipping");
                continue;
            }

            String currentBranch = gitBranch(dir);
            if (currentBranch.equals(branchName)) {
                skippedAlreadyOnBranch.add(name);
                getLog().info("  \u2713 " + name + " \u2014 already on " + branchName);
                continue;
            }

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
                    versionInfo = " \u2192 " + newVersion;
                }
                getLog().info("  [dry-run] " + name + " \u2014 would create "
                        + branchName + versionInfo);
                created.add(name);
                continue;
            }

            getLog().info("  \u2192 " + name + " \u2014 creating " + branchName);
            ReleaseSupport.exec(dir, getLog(),
                    "git", "checkout", "-b", branchName);

            if (!skipVersion && component.version() != null
                    && !component.version().isEmpty()) {
                String newVersion = VersionSupport.branchQualifiedVersion(
                        component.version(), branchName);
                getLog().info("    version: " + component.version()
                        + " \u2192 " + newVersion);

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

        // Cascade version-property updates to downstream components
        if (!created.isEmpty() && !dryRun && !skipVersion) {
            cascadeVersionProperties(graph, root, sorted, branchName);
        }

        // Auto-push each branched component with IKE_VCS_CONTEXT
        if (!created.isEmpty() && !dryRun) {
            for (String name : created) {
                File dir = new File(root, name);
                try {
                    VcsOperations.pushWithUpstream(dir, getLog(), "origin", branchName);
                    VcsOperations.writeVcsState(dir, VcsState.ACTION_FEATURE_START);
                } catch (MojoExecutionException e) {
                    getLog().warn("  Could not push " + name + ": " + e.getMessage());
                }
            }
        }

        // Branch the workspace repo and update workspace.yaml on the feature branch
        if (!created.isEmpty() && !dryRun) {
            branchWorkspaceRepo(branchName, created);
        }

        getLog().info("");
        getLog().info("  Created: " + created.size()
                + " | Already on branch: " + skippedAlreadyOnBranch.size()
                + " | Not cloned: " + skippedNotCloned.size());
        getLog().info("");
    }

    /**
     * Bare-mode: create feature branch in the current repo only.
     */
    private void executeBareMode(String branchName) throws MojoExecutionException {
        File dir = new File(System.getProperty("user.dir"));

        getLog().info("");
        getLog().info("IKE Feature Start (bare repo)");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Feature: " + feature);
        getLog().info("  Branch:  " + branchName);
        getLog().info("  Repo:    " + dir.getName());
        if (dryRun) {
            getLog().info("  Mode:    DRY RUN");
        }
        getLog().info("");

        // VCS bridge: catch-up before branching
        VcsOperations.catchUp(dir, getLog());

        // Validate clean worktree
        String status = gitStatus(dir);
        if (!status.isEmpty()) {
            throw new MojoExecutionException(
                    "Uncommitted changes. Commit or stash before starting a feature.");
        }

        // Read current version from POM
        String currentVersion = null;
        File pom = new File(dir, "pom.xml");
        if (pom.exists() && !skipVersion) {
            try {
                currentVersion = ReleaseSupport.readPomVersion(pom);
            } catch (MojoExecutionException e) {
                getLog().debug("Could not read POM version: " + e.getMessage());
            }
        }

        if (dryRun) {
            String versionInfo = "";
            if (currentVersion != null) {
                versionInfo = " \u2192 " + VersionSupport.branchQualifiedVersion(
                        currentVersion, branchName);
            }
            getLog().info("  [dry-run] Would create " + branchName + versionInfo);
            getLog().info("");
            return;
        }

        // Create branch
        ReleaseSupport.exec(dir, getLog(),
                "git", "checkout", "-b", branchName);
        getLog().info("  Created " + branchName);

        // Set branch-qualified version
        if (currentVersion != null && !currentVersion.isEmpty()) {
            String newVersion = VersionSupport.branchQualifiedVersion(
                    currentVersion, branchName);
            getLog().info("  Version: " + currentVersion + " \u2192 " + newVersion);
            setPomVersion(dir, currentVersion, newVersion);
            ReleaseSupport.exec(dir, getLog(), "git", "add", "pom.xml");
            // Also stage any updated submodule POMs
            try {
                List<File> allPoms = ReleaseSupport.findPomFiles(dir);
                for (File subPom : allPoms) {
                    if (!subPom.equals(pom)) {
                        String rel = dir.toPath().relativize(subPom.toPath()).toString();
                        ReleaseSupport.exec(dir, getLog(), "git", "add", rel);
                    }
                }
            } catch (MojoExecutionException e) {
                getLog().debug("Could not scan submodule POMs: " + e.getMessage());
            }
            ReleaseSupport.exec(dir, getLog(),
                    "git", "commit", "-m",
                    "feature: set version " + newVersion + " for " + branchName);
        }

        // Auto-push and write state file
        try {
            VcsOperations.pushWithUpstream(dir, getLog(), "origin", branchName);
        } catch (MojoExecutionException e) {
            getLog().warn("  Could not push: " + e.getMessage());
        }
        VcsOperations.writeVcsState(dir, VcsState.ACTION_FEATURE_START);

        getLog().info("");
    }

    /**
     * Branch the workspace repo, update workspace.yaml on the feature branch,
     * and push with IKE_VCS_CONTEXT.
     */
    private void branchWorkspaceRepo(String branchName, List<String> components)
            throws MojoExecutionException {
        try {
            Path manifestPath = resolveManifest();
            File wsRoot = manifestPath.getParent().toFile();
            File wsGit = new File(wsRoot, ".git");
            if (!wsGit.exists()) return;

            // Branch the workspace repo
            getLog().info("  Branching workspace repo → " + branchName);
            VcsOperations.checkoutNew(wsRoot, getLog(), branchName);

            // Update workspace.yaml on the feature branch
            Map<String, String> updates = new LinkedHashMap<>();
            for (String name : components) {
                updates.put(name, branchName);
            }
            ManifestWriter.updateBranches(manifestPath, updates);
            getLog().info("  Updated workspace.yaml branches for "
                    + components.size() + " components");

            ReleaseSupport.exec(wsRoot, getLog(), "git", "add", "workspace.yaml");
            VcsOperations.commit(wsRoot, getLog(),
                    "workspace: update branches for " + branchName);

            // Push ws feature branch
            VcsOperations.pushWithUpstream(wsRoot, getLog(), "origin", branchName);
            VcsOperations.writeVcsState(wsRoot, VcsState.ACTION_FEATURE_START);

        } catch (IOException e) {
            getLog().warn("  Could not update workspace.yaml: " + e.getMessage());
        }
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

    /**
     * Cascade version-property updates to downstream components.
     *
     * <p>When an upstream component's version changes (e.g., tinkar-core
     * gets a branch-qualified version), downstream components that track
     * that version via a POM property (declared as {@code version-property}
     * in workspace.yaml) need their property updated too.
     *
     * <p>For example, if rocks-kb depends on tinkar-core with
     * {@code version-property: ike-bom.version}, and tinkar-core's version
     * changed to {@code 1.127.2-feature-foo-SNAPSHOT}, then rocks-kb's
     * {@code <ike-bom.version>} property is updated to match.
     */
    private void cascadeVersionProperties(WorkspaceGraph graph, File root,
                                           List<String> sorted, String branchName)
            throws MojoExecutionException {

        // Build map of upstream component → new branch-qualified version
        java.util.Map<String, String> newVersions = new java.util.LinkedHashMap<>();
        for (String name : sorted) {
            Component comp = graph.manifest().components().get(name);
            if (comp.version() != null && !comp.version().isEmpty()) {
                newVersions.put(name, VersionSupport.branchQualifiedVersion(
                        comp.version(), branchName));
            }
        }

        // For each component in topological order, update version-properties
        // that reference upstream components
        for (String name : sorted) {
            Component comp = graph.manifest().components().get(name);
            File dir = new File(root, name);
            File pomFile = new File(dir, "pom.xml");
            if (!pomFile.exists()) continue;

            boolean pomChanged = false;
            try {
                String content = java.nio.file.Files.readString(
                        pomFile.toPath(), java.nio.charset.StandardCharsets.UTF_8);
                String original = content;

                for (network.ike.workspace.Dependency dep : comp.dependsOn()) {
                    String upstreamName = dep.component();
                    if (dep.versionProperty() == null) continue;
                    if (!newVersions.containsKey(upstreamName)) continue;

                    String upstreamVersion = newVersions.get(upstreamName);
                    String before = content;
                    content = ReleaseSupport.updateVersionProperty(
                            content, dep.versionProperty(), upstreamVersion);

                    if (!content.equals(before)) {
                        getLog().info("    " + name + ": " + dep.versionProperty()
                                + " → " + upstreamVersion
                                + " (from " + upstreamName + ")");
                    }
                }

                if (!content.equals(original)) {
                    java.nio.file.Files.writeString(
                            pomFile.toPath(), content,
                            java.nio.charset.StandardCharsets.UTF_8);
                    ReleaseSupport.exec(dir, getLog(), "git", "add", "pom.xml");
                    ReleaseSupport.exec(dir, getLog(), "git", "commit", "-m",
                            "feature: update dependency versions for " + branchName);
                    pomChanged = true;
                }
            } catch (java.io.IOException e) {
                getLog().warn("    Could not cascade version properties in "
                        + name + ": " + e.getMessage());
            }
        }
    }
}
