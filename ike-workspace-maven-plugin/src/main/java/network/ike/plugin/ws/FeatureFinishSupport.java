package network.ike.plugin.ws;

import network.ike.plugin.ReleaseSupport;

import network.ike.workspace.Component;
import network.ike.workspace.ManifestWriter;
import network.ike.workspace.VersionSupport;
import network.ike.workspace.WorkspaceGraph;
import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared logic for feature-finish goals (squash, merge, rebase).
 *
 * <p>Each strategy goal delegates to this class for validation,
 * version stripping, workspace.yaml updates, branch deletion,
 * and state file writing. The actual merge operation is performed
 * by the strategy goal itself.
 */
class FeatureFinishSupport {

    private FeatureFinishSupport() {}

    /**
     * Validate that a component is eligible for feature-finish.
     * Returns null if eligible, or a skip reason string.
     */
    static String validateComponent(File root, String name, String branchName,
                                     AbstractWorkspaceMojo mojo) {
        File dir = new File(root, name);
        File gitDir = new File(dir, ".git");

        if (!gitDir.exists()) {
            return "not cloned";
        }

        String currentBranch = mojo.gitBranch(dir);
        if (!currentBranch.equals(branchName)) {
            return "on " + currentBranch + ", not " + branchName;
        }

        String status = mojo.gitStatus(dir);
        if (!status.isEmpty()) {
            return "DIRTY";  // Caller should throw
        }

        return null;
    }

    /**
     * Strip branch-qualified version back to base SNAPSHOT.
     * Returns the base version, or null if no stripping was needed.
     */
    static String stripBranchVersion(File dir, Component component, Log log)
            throws MojoExecutionException {
        if (component.version() == null
                || !VersionSupport.isBranchQualified(component.version())) {
            return null;
        }

        String currentVersion = readCurrentVersion(dir, log);
        if (currentVersion == null || !VersionSupport.isBranchQualified(currentVersion)) {
            return null;
        }

        String baseVersion = VersionSupport.extractNumericBase(
                VersionSupport.stripSnapshot(currentVersion)) + "-SNAPSHOT";

        log.info("    version: " + currentVersion + " → " + baseVersion);
        setAllVersions(dir, currentVersion, baseVersion, log);
        ReleaseSupport.exec(dir, log, "git", "add", "-A");
        ReleaseSupport.exec(dir, log, "git", "commit", "-m",
                "merge-prep: strip branch qualifier → " + baseVersion);

        return baseVersion;
    }

    /**
     * Strip branch-qualified version in bare mode.
     */
    static String stripBranchVersionBare(File dir, Log log)
            throws MojoExecutionException {
        File pom = new File(dir, "pom.xml");
        if (!pom.exists()) return null;

        String currentVersion;
        try {
            currentVersion = ReleaseSupport.readPomVersion(pom);
        } catch (MojoExecutionException e) {
            return null;
        }

        if (currentVersion == null || !VersionSupport.isBranchQualified(currentVersion)) {
            return null;
        }

        String baseVersion = VersionSupport.extractNumericBase(
                VersionSupport.stripSnapshot(currentVersion)) + "-SNAPSHOT";

        log.info("  Version: " + currentVersion + " → " + baseVersion);
        setAllVersions(dir, currentVersion, baseVersion, log);
        ReleaseSupport.exec(dir, log, "git", "add", "-A");
        ReleaseSupport.exec(dir, log, "git", "commit", "-m",
                "merge-prep: strip branch qualifier → " + baseVersion);

        return baseVersion;
    }

    /**
     * Delete feature branch locally and remotely.
     */
    static void deleteBranch(File dir, Log log, String branchName)
            throws MojoExecutionException {
        VcsOperations.deleteBranch(dir, log, branchName);
        log.info("    deleted local branch: " + branchName);

        try {
            VcsOperations.deleteRemoteBranch(dir, log, "origin", branchName);
            log.info("    deleted remote branch: origin/" + branchName);
        } catch (MojoExecutionException e) {
            log.warn("    could not delete remote branch: " + e.getMessage());
        }
    }

    /**
     * Clean up feature branch snapshot sites.
     */
    static void cleanFeatureSites(File root, List<String> components,
                                    String branchName, Log log) {
        String featurePath = ReleaseSupport.branchToSitePath(branchName);
        for (String name : components) {
            String siteDisk = ReleaseSupport.siteDiskPath(
                    name, "snapshot", featurePath);
            try {
                ReleaseSupport.cleanRemoteSiteDir(
                        new File(root, name), log, siteDisk);
            } catch (MojoExecutionException e) {
                log.debug("No snapshot site to clean for " + name
                        + ": " + e.getMessage());
            }
        }
    }

    /**
     * Update workspace.yaml branch fields back to targetBranch and commit.
     */
    static void updateWorkspaceYaml(Path manifestPath, List<String> components,
                                      String targetBranch, String feature,
                                      Log log) {
        try {
            Map<String, String> updates = new LinkedHashMap<>();
            for (String name : components) {
                updates.put(name, targetBranch);
            }
            ManifestWriter.updateBranches(manifestPath, updates);
            log.info("  Updated workspace.yaml branches → " + targetBranch);

            File wsRoot = manifestPath.getParent().toFile();
            File wsGit = new File(wsRoot, ".git");
            if (wsGit.exists()) {
                ReleaseSupport.exec(wsRoot, log, "git", "add", "workspace.yaml");
                ReleaseSupport.exec(wsRoot, log, "git", "commit", "-m",
                        "workspace: restore branches to " + targetBranch
                                + " after feature/" + feature);
            }
        } catch (IOException | MojoExecutionException e) {
            log.warn("  Could not update workspace.yaml: " + e.getMessage());
        }
    }

    /**
     * Merge the ws repo back to target branch.
     */
    static void mergeWorkspaceRepo(Path manifestPath, String branchName,
                                     String targetBranch, boolean keepBranch,
                                     Log log)
            throws MojoExecutionException {
        File wsRoot = manifestPath.getParent().toFile();
        if (!new File(wsRoot, ".git").exists()) return;

        String wsBranch = null;
        try {
            wsBranch = VcsOperations.currentBranch(wsRoot);
        } catch (MojoExecutionException e) {
            return;
        }

        if (wsBranch != null && wsBranch.equals(branchName)) {
            VcsOperations.checkout(wsRoot, log, targetBranch);
        }

        if (!keepBranch) {
            try {
                deleteBranch(wsRoot, log, branchName);
            } catch (MojoExecutionException e) {
                log.warn("  Could not delete ws branch: " + e.getMessage());
            }
        }

        // Write state file for ws
        if (VcsState.isIkeManaged(wsRoot.toPath())) {
            VcsOperations.writeVcsState(wsRoot, VcsState.ACTION_FEATURE_FINISH);
        }
    }

    // ── Internal helpers ─────────────────────────────────────────

    private static String readCurrentVersion(File dir, Log log) {
        try {
            return ReleaseSupport.readPomVersion(new File(dir, "pom.xml"));
        } catch (MojoExecutionException e) {
            log.warn("    Could not read version from " + dir.getName()
                    + "/pom.xml: " + e.getMessage());
            return null;
        }
    }

    static void setAllVersions(File dir, String oldVersion, String newVersion,
                                 Log log) throws MojoExecutionException {
        File pom = new File(dir, "pom.xml");
        ReleaseSupport.setPomVersion(pom, oldVersion, newVersion);

        List<File> allPoms = ReleaseSupport.findPomFiles(dir);
        for (File subPom : allPoms) {
            if (subPom.equals(pom)) continue;
            try {
                String content = Files.readString(subPom.toPath(), StandardCharsets.UTF_8);
                if (content.contains("<version>" + oldVersion + "</version>")) {
                    String updated = content.replace(
                            "<version>" + oldVersion + "</version>",
                            "<version>" + newVersion + "</version>");
                    Files.writeString(subPom.toPath(), updated, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                log.warn("    Could not update " + subPom + ": " + e.getMessage());
            }
        }
    }
}
