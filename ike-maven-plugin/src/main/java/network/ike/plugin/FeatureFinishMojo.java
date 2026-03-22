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
 * Finish a feature branch — merge back to main across components.
 *
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
 * </ol>
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

    @Override
    public void execute() throws MojoExecutionException {
        feature = requireParam(feature, "feature", "Feature name (expects branch feature/<name>)");

        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        String branchName = "feature/" + feature;
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
        getLog().info("IKE Workspace — Feature Finish");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("  Feature:  " + feature);
        getLog().info("  Branch:   " + branchName + " → " + targetBranch);
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
                getLog().info("  ⚠ " + name + " — not cloned, skipping");
                continue;
            }

            String currentBranch = gitBranch(dir);
            if (!currentBranch.equals(branchName)) {
                getLog().info("  · " + name + " — on " + currentBranch
                        + ", not " + branchName + " — skipping");
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
            getLog().info("  No components on " + branchName + " — nothing to do.");
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
                    versionInfo = " (version → " + baseVersion + ")";
                }
                getLog().info("  [dry-run] " + name
                        + " — would merge " + branchName + " → "
                        + targetBranch + versionInfo);
                merged++;
                continue;
            }

            getLog().info("  → " + name);

            // Strip branch qualifier from version if present
            if (component.version() != null
                    && VersionSupport.isBranchQualified(component.version())) {
                String currentVersion = readCurrentVersion(dir);
                if (currentVersion != null && VersionSupport.isBranchQualified(currentVersion)) {
                    String baseVersion = VersionSupport.extractNumericBase(
                            VersionSupport.stripSnapshot(currentVersion))
                            + "-SNAPSHOT";
                    getLog().info("    version: " + currentVersion
                            + " → " + baseVersion);
                    setAllVersions(dir, currentVersion, baseVersion);
                    ReleaseSupport.exec(dir, getLog(),
                            "git", "add", "-A");
                    ReleaseSupport.exec(dir, getLog(),
                            "git", "commit", "-m",
                            "merge-prep: strip branch qualifier → " + baseVersion);
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
            getLog().info("  ⚠ Changes are local only. Run with -Dpush=true to push.");
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
