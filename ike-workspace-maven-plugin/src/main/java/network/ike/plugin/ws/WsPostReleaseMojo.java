package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;

import network.ike.workspace.Component;
import network.ike.workspace.ManifestWriter;
import network.ike.workspace.WorkspaceGraph;
import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Post-release version bump across workspace components.
 *
 * <p>After a release, this goal bumps every checked-out component's
 * POM version to the specified {@code nextVersion}, commits the
 * change, pushes if a remote exists, then updates workspace.yaml
 * to reflect the new development versions.
 *
 * <p>Components are processed in topological order so that upstream
 * dependencies are bumped before downstream consumers.
 *
 * <pre>{@code
 * mvn ike:post-release -DnextVersion=4-SNAPSHOT
 * }</pre>
 */
@Mojo(name = "post-release", requiresProject = false, threadSafe = true)
public class WsPostReleaseMojo extends AbstractWorkspaceMojo {

    /**
     * The next development version to set across all components,
     * e.g., {@code "4-SNAPSHOT"}.
     */
    @Parameter(property = "nextVersion", required = true)
    String nextVersion;

    /** Creates this goal instance. */
    public WsPostReleaseMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        nextVersion = requireParam(nextVersion, "nextVersion",
                "Next development version (e.g., 4-SNAPSHOT)");

        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();
        Path manifestPath = resolveManifest();

        // VCS bridge: catch-up before modifying
        VcsOperations.catchUp(root, getLog());

        List<String> sorted = graph.topologicalSort(
                new LinkedHashSet<>(graph.manifest().components().keySet()));

        getLog().info("");
        getLog().info("IKE Workspace \u2014 Post-Release");
        getLog().info("\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550\u2550");
        getLog().info("  Next version: " + nextVersion);
        getLog().info("  Components:   " + sorted.size());
        getLog().info("");

        Map<String, String> versionUpdates = new LinkedHashMap<>();
        int bumped = 0;
        int skipped = 0;

        for (String name : sorted) {
            Component component = graph.manifest().components().get(name);
            File dir = new File(root, name);
            File gitDir = new File(dir, ".git");
            File pomFile = new File(dir, "pom.xml");

            if (!gitDir.exists()) {
                getLog().info("  \u26A0 " + name + " \u2014 not cloned, skipping");
                skipped++;
                continue;
            }

            if (!pomFile.exists()) {
                getLog().info("  \u26A0 " + name + " \u2014 no pom.xml, skipping");
                skipped++;
                continue;
            }

            // Read current version from root POM
            String currentVersion;
            try {
                currentVersion = ReleaseSupport.readPomVersion(pomFile);
            } catch (MojoExecutionException e) {
                getLog().warn("  \u26A0 " + name + " \u2014 could not read version: "
                        + e.getMessage());
                skipped++;
                continue;
            }

            getLog().info("  \u2192 " + name + " \u2014 " + currentVersion
                    + " \u2192 " + nextVersion);

            // Set version to nextVersion in POM
            ReleaseSupport.setPomVersion(pomFile, currentVersion, nextVersion);

            // Also update submodule POMs that reference the old version
            try {
                List<File> allPoms = ReleaseSupport.findPomFiles(dir);
                for (File subPom : allPoms) {
                    if (subPom.equals(pomFile)) continue;
                    try {
                        String content = java.nio.file.Files.readString(
                                subPom.toPath(), java.nio.charset.StandardCharsets.UTF_8);
                        if (content.contains("<version>" + currentVersion + "</version>")) {
                            String updated = content.replace(
                                    "<version>" + currentVersion + "</version>",
                                    "<version>" + nextVersion + "</version>");
                            java.nio.file.Files.writeString(
                                    subPom.toPath(), updated,
                                    java.nio.charset.StandardCharsets.UTF_8);
                            String rel = dir.toPath().relativize(subPom.toPath()).toString();
                            getLog().info("    updated: " + rel);
                        }
                    } catch (java.io.IOException e) {
                        getLog().warn("    Could not update " + subPom + ": "
                                + e.getMessage());
                    }
                }
            } catch (MojoExecutionException e) {
                getLog().warn("    Could not scan submodule POMs: " + e.getMessage());
            }

            // Commit: git add pom.xml && git commit
            ReleaseSupport.exec(dir, getLog(), "git", "add", "pom.xml");
            // Stage any submodule POMs that were updated
            try {
                List<File> allPoms = ReleaseSupport.findPomFiles(dir);
                for (File subPom : allPoms) {
                    if (!subPom.equals(pomFile)) {
                        String rel = dir.toPath().relativize(subPom.toPath()).toString();
                        ReleaseSupport.exec(dir, getLog(), "git", "add", rel);
                    }
                }
            } catch (MojoExecutionException e) {
                getLog().debug("Could not stage submodule POMs: " + e.getMessage());
            }
            VcsOperations.commitStaged(dir, getLog(),
                    "post-release: bump to " + nextVersion);

            // Push if remote exists (safe — ignores failure)
            VcsOperations.pushSafe(dir, getLog(), "origin",
                    gitBranch(dir));

            versionUpdates.put(name, nextVersion);
            bumped++;
        }

        // Update workspace.yaml versions
        if (!versionUpdates.isEmpty()) {
            try {
                ManifestWriter.updateMavenVersions(manifestPath, versionUpdates);
                getLog().info("");
                getLog().info("  Updated workspace.yaml versions for "
                        + versionUpdates.size() + " components");
            } catch (IOException e) {
                throw new MojoExecutionException(
                        "Failed to update workspace.yaml: " + e.getMessage(), e);
            }

            // Commit workspace.yaml on aggregator
            File wsRoot = manifestPath.getParent().toFile();
            File wsGit = new File(wsRoot, ".git");
            if (wsGit.exists()) {
                ReleaseSupport.exec(wsRoot, getLog(), "git", "add", "workspace.yaml");
                VcsOperations.commitStaged(wsRoot, getLog(),
                        "post-release: bump workspace versions to " + nextVersion);
                VcsOperations.pushSafe(wsRoot, getLog(), "origin",
                        gitBranch(wsRoot));
            }
        }

        getLog().info("");
        getLog().info("  Bumped: " + bumped + " | Skipped: " + skipped);
        getLog().info("");
    }
}
