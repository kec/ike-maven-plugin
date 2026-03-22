package network.ike.plugin;

import network.ike.workspace.Component;
import network.ike.workspace.ManifestWriter;
import network.ike.workspace.VersionSupport;
import network.ike.workspace.WorkspaceGraph;
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
 * Finish a feature branch — merge back to main across components.
 *
 * <p><strong>Workspace mode</strong> (workspace.yaml found):</p>
 * <p>For each component in the specified group that is currently on
 * the feature branch:
 * <ol>
 *   <li>Validates the working tree is clean</li>
 *   <li>If the version is branch-qualified, strips the qualifier
 *       (e.g., {@code 1.2.0-my-feature-SNAPSHOT} → {@code 1.2.0-SNAPSHOT})</li>
 *   <li>Commits the version change</li>
 *   <li>Checks out the target branch (default: main)</li>
 *   <li>Merges the feature branch with {@code --no-ff}</li>
 *   <li>Tags the merge point as {@code merge/feature/<name>}</li>
 *   <li>Optionally pushes to origin</li>
 *   <li>Updates workspace.yaml branch fields back to targetBranch</li>
 * </ol>
 *
 * <p><strong>Bare mode</strong> (no workspace.yaml):</p>
 * <p>Merges the feature branch in the current repo only.
 *
 * <p>Components are processed in <b>reverse</b> topological order so
 * that leaf components (komet-desktop) merge first, and foundation
 * components (ike-parent) merge last.
 *
 * <pre>{@code
 * mvn ike:feature-finish -Dfeature=shield-terminology -Dgroup=core
 * mvn ike:feature-finish -Dfeature=kec-march-25 -Dgroup=studio -Dpush=true
 * mvn ike:feature-finish -Dfeature=kec-march-25 -DdryRun=true
 * }</pre>
 */
@Mojo(name = "feature-finish", requiresProject = false, threadSafe = true)
public class FeatureFinishMojo extends AbstractWorkspaceMojo {

    /** Feature name. Expects branch {@code feature/<name>}. Prompted if omitted. */
    @Parameter(property = "feature")
    String feature;

    /** Restrict to a named group or component. Default: all cloned. */
    @Parameter(property = "group")
    String group;

    /** Target branch to merge into. Default: main. */
    @Parameter(property = "targetBranch", defaultValue = "main")
    String targetBranch;

    /** Push to origin after merge. Default: false (safety). */
    @Parameter(property = "push", defaultValue = "false")
    boolean push;

    /** Show plan without executing. */
    @Parameter(property = "dryRun", defaultValue = "false")
    boolean dryRun;

    /** Creates this goal instance. */
    public FeatureFinishMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        feature = requireParam(feature, "feature", "Feature name (expects branch feature/<name>)");

        String branchName = "feature/" + feature;

        if (!isWorkspaceMode()) {
            executeBareMode(branchName);
            return;
        }

        // --- Workspace mode ---
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        String mergeTag = "merge/" + branchName;

        Set<String> targets;
        if (group != null && !group.isEmpty()) {
            targets = graph.expandGroup(group);
        } else {
            targets = graph.manifest().components().keySet();
        }

        // Reverse topological order: leaves first, foundations last
        List<String> sorted = graph.topologicalSort(new LinkedHashSet<>(targets));
        List<String> reversed = new ArrayList<>(sorted);
        java.util.Collections.reverse(reversed);

        getLog().info("");
        getLog().info("IKE Workspace \u2014 Feature Finish");
        getLog().info("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
        getLog().info("  Feature:  " + feature);
        getLog().info("  Branch:   " + branchName + " \u2192 " + targetBranch);
        getLog().info("  Merge tag:" + mergeTag);
        getLog().info("  Push:     " + push);
        if (dryRun) {
            getLog().info("  Mode:     DRY RUN");
        }
        getLog().info("");

        // First pass: validate all components are ready
        List<String> eligible = new ArrayList<>();
        for (String name : reversed) {
            File dir = new File(root, name);
            File gitDir = new File(dir, ".git");

            if (!gitDir.exists()) {
                getLog().info("  \u26A0 " + name + " \u2014 not cloned, skipping");
                continue;
            }

            String currentBranch = gitBranch(dir);
            if (!currentBranch.equals(branchName)) {
                getLog().info("  \u00B7 " + name + " \u2014 on " + currentBranch
                        + ", not " + branchName + " \u2014 skipping");
                continue;
            }

            String status = gitStatus(dir);
            if (!status.isEmpty()) {
                throw new MojoExecutionException(
                        name + " has uncommitted changes on " + branchName
                                + ". Commit or stash before finishing the feature.");
            }

            eligible.add(name);
        }

        if (eligible.isEmpty()) {
            getLog().info("  No components on " + branchName + " \u2014 nothing to do.");
            getLog().info("");
            return;
        }

        getLog().info("  Eligible: " + eligible.size() + " components on "
                + branchName);
        getLog().info("");

        // Second pass: merge each component
        int merged = 0;
        for (String name : eligible) {
            Component component = graph.manifest().components().get(name);
            File dir = new File(root, name);

            if (dryRun) {
                String versionInfo = "";
                if (component.version() != null
                        && VersionSupport.isBranchQualified(component.version())) {
                    String baseVersion = VersionSupport.extractNumericBase(
                            VersionSupport.stripSnapshot(component.version()))
                            + "-SNAPSHOT";
                    versionInfo = " (version \u2192 " + baseVersion + ")";
                }
                getLog().info("  [dry-run] " + name
                        + " \u2014 would merge " + branchName + " \u2192 "
                        + targetBranch + versionInfo);
                merged++;
                continue;
            }

            getLog().info("  \u2192 " + name);

            // Strip branch qualifier from version if present
            if (component.version() != null
                    && VersionSupport.isBranchQualified(component.version())) {
                String currentVersion = readCurrentVersion(dir);
                if (currentVersion != null && VersionSupport.isBranchQualified(currentVersion)) {
                    String baseVersion = VersionSupport.extractNumericBase(
                            VersionSupport.stripSnapshot(currentVersion))
                            + "-SNAPSHOT";
                    getLog().info("    version: " + currentVersion
                            + " \u2192 " + baseVersion);
                    setAllVersions(dir, currentVersion, baseVersion);
                    ReleaseSupport.exec(dir, getLog(),
                            "git", "add", "-A");
                    ReleaseSupport.exec(dir, getLog(),
                            "git", "commit", "-m",
                            "merge-prep: strip branch qualifier \u2192 " + baseVersion);
                }
            }

            // Checkout target and merge
            ReleaseSupport.exec(dir, getLog(),
                    "git", "checkout", targetBranch);
            ReleaseSupport.exec(dir, getLog(),
                    "git", "merge", "--no-ff", branchName,
                    "-m", "Merge " + branchName + " into " + targetBranch);

            // Tag the merge point
            String componentTag = mergeTag + "/" + name;
            ReleaseSupport.exec(dir, getLog(),
                    "git", "tag", componentTag);
            getLog().info("    tagged: " + componentTag);

            // Push if requested
            if (push) {
                ReleaseSupport.exec(dir, getLog(),
                        "git", "push", "origin", targetBranch);
                ReleaseSupport.exec(dir, getLog(),
                        "git", "push", "origin", componentTag);
                getLog().info("    pushed to origin");
            }

            merged++;
        }

        getLog().info("");
        getLog().info("  Merged: " + merged + " components");
        if (!push) {
            getLog().info("  \u26A0 Changes are local only. Run with -Dpush=true to push.");
        }

        // Clean up feature branch snapshot sites for each merged component.
        // Non-fatal — the site may never have been deployed for this feature.
        if (merged > 0) {
            String featurePath = ReleaseSupport.branchToSitePath(branchName);
            for (String name : eligible) {
                String siteDisk = ReleaseSupport.siteDiskPath(
                        name, "snapshot", featurePath);
                try {
                    ReleaseSupport.cleanRemoteSiteDir(
                            new File(root, name), getLog(), siteDisk);
                } catch (MojoExecutionException e) {
                    getLog().debug("No snapshot site to clean for " + name
                            + ": " + e.getMessage());
                }
            }
        }

        // Update workspace.yaml branch fields back to targetBranch
        if (merged > 0 && !dryRun) {
            try {
                Path manifestPath = resolveManifest();
                Map<String, String> updates = new LinkedHashMap<>();
                for (String name : eligible) {
                    updates.put(name, targetBranch);
                }
                ManifestWriter.updateBranches(manifestPath, updates);
                getLog().info("  Updated workspace.yaml branches \u2192 " + targetBranch);

                File wsRoot = manifestPath.getParent().toFile();
                File wsGit = new File(wsRoot, ".git");
                if (wsGit.exists()) {
                    ReleaseSupport.exec(wsRoot, getLog(),
                            "git", "add", "workspace.yaml");
                    ReleaseSupport.exec(wsRoot, getLog(),
                            "git", "commit", "-m",
                            "workspace: restore branches to " + targetBranch + " after feature/" + feature);
                }
            } catch (IOException e) {
                getLog().warn("  Could not update workspace.yaml: " + e.getMessage());
            }
        }

        getLog().info("");
    }

    /**
     * Bare-mode: merge feature branch in the current repo only.
     */
    private void executeBareMode(String branchName) throws MojoExecutionException {
        File dir = new File(System.getProperty("user.dir"));
        String mergeTag = "merge/" + branchName;

        getLog().info("");
        getLog().info("IKE Feature Finish (bare repo)");
        getLog().info("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
        getLog().info("  Feature:  " + feature);
        getLog().info("  Branch:   " + branchName + " \u2192 " + targetBranch);
        getLog().info("  Push:     " + push);
        if (dryRun) {
            getLog().info("  Mode:     DRY RUN");
        }
        getLog().info("");

        String currentBranch = gitBranch(dir);
        if (!currentBranch.equals(branchName)) {
            throw new MojoExecutionException(
                    "Not on " + branchName + " (currently on " + currentBranch + ")");
        }

        String status = gitStatus(dir);
        if (!status.isEmpty()) {
            throw new MojoExecutionException(
                    "Uncommitted changes. Commit or stash before finishing the feature.");
        }

        if (dryRun) {
            getLog().info("  [dry-run] Would merge " + branchName + " \u2192 " + targetBranch);
            getLog().info("");
            return;
        }

        // Strip branch qualifier from version if present
        File pom = new File(dir, "pom.xml");
        if (pom.exists()) {
            try {
                String currentVersion = ReleaseSupport.readPomVersion(pom);
                if (currentVersion != null && VersionSupport.isBranchQualified(currentVersion)) {
                    String baseVersion = VersionSupport.extractNumericBase(
                            VersionSupport.stripSnapshot(currentVersion)) + "-SNAPSHOT";
                    getLog().info("  Version: " + currentVersion + " \u2192 " + baseVersion);
                    ReleaseSupport.setPomVersion(pom, currentVersion, baseVersion);
                    // Update submodule POMs too
                    List<File> allPoms = ReleaseSupport.findPomFiles(dir);
                    for (File subPom : allPoms) {
                        if (!subPom.equals(pom)) {
                            try {
                                String content = java.nio.file.Files.readString(
                                        subPom.toPath(), java.nio.charset.StandardCharsets.UTF_8);
                                if (content.contains("<version>" + currentVersion + "</version>")) {
                                    String updated = content.replace(
                                            "<version>" + currentVersion + "</version>",
                                            "<version>" + baseVersion + "</version>");
                                    java.nio.file.Files.writeString(
                                            subPom.toPath(), updated,
                                            java.nio.charset.StandardCharsets.UTF_8);
                                }
                            } catch (java.io.IOException e) {
                                // skip
                            }
                        }
                    }
                    ReleaseSupport.exec(dir, getLog(), "git", "add", "-A");
                    ReleaseSupport.exec(dir, getLog(), "git", "commit", "-m",
                            "merge-prep: strip branch qualifier \u2192 " + baseVersion);
                }
            } catch (MojoExecutionException e) {
                getLog().debug("Could not read POM version: " + e.getMessage());
            }
        }

        // Merge
        ReleaseSupport.exec(dir, getLog(),
                "git", "checkout", targetBranch);
        ReleaseSupport.exec(dir, getLog(),
                "git", "merge", "--no-ff", branchName,
                "-m", "Merge " + branchName + " into " + targetBranch);

        // Tag
        String tag = mergeTag + "/" + dir.getName();
        ReleaseSupport.exec(dir, getLog(), "git", "tag", tag);
        getLog().info("  Tagged: " + tag);

        if (push) {
            ReleaseSupport.exec(dir, getLog(),
                    "git", "push", "origin", targetBranch);
            ReleaseSupport.exec(dir, getLog(),
                    "git", "push", "origin", tag);
            getLog().info("  Pushed to origin");
        }

        getLog().info("");
    }

    /**
     * Read the current POM version from the component's root pom.xml.
     */
    private String readCurrentVersion(File dir) {
        try {
            return ReleaseSupport.readPomVersion(new File(dir, "pom.xml"));
        } catch (MojoExecutionException e) {
            getLog().warn("    Could not read version from " + dir.getName()
                    + "/pom.xml: " + e.getMessage());
            return null;
        }
    }

    /**
     * Set version in root POM and all submodule POMs.
     */
    private void setAllVersions(File dir, String oldVersion, String newVersion)
            throws MojoExecutionException {
        File pom = new File(dir, "pom.xml");
        ReleaseSupport.setPomVersion(pom, oldVersion, newVersion);

        // Update submodule POMs
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
                }
            } catch (java.io.IOException e) {
                getLog().warn("    Could not update " + subPom + ": " + e.getMessage());
            }
        }
    }
}
