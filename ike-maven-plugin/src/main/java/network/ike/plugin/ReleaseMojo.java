package network.ike.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.time.Instant;
import java.util.List;

/**
 * Full release: build, deploy, tag, merge, and bump to next SNAPSHOT.
 *
 * <p>This goal automates the complete release workflow in one command.
 * All local git work completes before any external action, so a
 * deploy failure leaves the local repository in a consistent state
 * and the deploy can be retried manually.
 *
 * <p><strong>Local phase (idempotent):</strong></p>
 * <ol>
 *   <li>Validate prerequisites (branch, clean worktree)</li>
 *   <li>Create {@code release/<version>} branch</li>
 *   <li>Set POM version to release version</li>
 *   <li>Build and verify</li>
 *   <li>Build site (pre-flight — catches javadoc errors early)</li>
 *   <li>Commit, tag</li>
 *   <li>Restore {@code ${project.version}}, merge to main</li>
 *   <li>Bump to next SNAPSHOT version, verify, commit</li>
 * </ol>
 *
 * <p><strong>External phase (most reversible first, irreversible last):</strong></p>
 * <ol>
 *   <li>Deploy site from tagged commit (overwritable — safe to retry)</li>
 *   <li>Deploy to Nexus from tagged commit (irreversible — last)</li>
 *   <li>Push tag and main to origin</li>
 *   <li>Create GitHub Release</li>
 * </ol>
 *
 * <p>Usage: {@code mvn ike:release} (auto-derives version from POM),
 * or override with {@code mvn ike:release -DreleaseVersion=2}
 *
 * @see CheckpointMojo
 */
@Mojo(name = "release", requiresProject = false, aggregator = true, threadSafe = true)
public class ReleaseMojo extends AbstractMojo {

    @Parameter(property = "releaseVersion")
    String releaseVersion;

    @Parameter(property = "nextVersion")
    String nextVersion;

    @Parameter(property = "dryRun", defaultValue = "false")
    boolean dryRun;

    @Parameter(property = "skipVerify", defaultValue = "false")
    boolean skipVerify;

    @Parameter(property = "allowBranch")
    String allowBranch;

    @Parameter(property = "deploySite", defaultValue = "true")
    boolean deploySite;

    /** Override working directory for tests. If null, uses current directory. */
    File baseDir;

    /** Creates this goal instance. */
    public ReleaseMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        File startDir = baseDir != null ? baseDir : new File(".");
        File gitRoot = ReleaseSupport.gitRoot(startDir);
        File mvnw = ReleaseSupport.resolveMavenWrapper(gitRoot, getLog());
        File rootPom = new File(gitRoot, "pom.xml");

        // Default releaseVersion from current POM version
        String oldVersion = ReleaseSupport.readPomVersion(rootPom);
        if (releaseVersion == null || releaseVersion.isBlank()) {
            releaseVersion = ReleaseSupport.deriveReleaseVersion(oldVersion);
            getLog().info("No -DreleaseVersion specified; defaulting to: " + releaseVersion);
        }

        // Default nextVersion
        if (nextVersion == null || nextVersion.isBlank()) {
            nextVersion = ReleaseSupport.deriveNextSnapshot(releaseVersion);
        }

        // Reject SNAPSHOT release versions
        if (releaseVersion.contains("-SNAPSHOT")) {
            throw new MojoExecutionException(
                    "Release version must not contain -SNAPSHOT.");
        }

        // Enforce SNAPSHOT suffix on next version
        if (!nextVersion.endsWith("-SNAPSHOT")) {
            throw new MojoExecutionException(
                    "Next version must end with -SNAPSHOT (got '" + nextVersion + "').");
        }

        // Validate branch
        String currentBranch = ReleaseSupport.currentBranch(gitRoot);
        String expectedBranch = allowBranch != null ? allowBranch : "main";
        if (!currentBranch.equals(expectedBranch)) {
            throw new MojoExecutionException(
                    "Must be on '" + expectedBranch + "' branch (currently on '" +
                            currentBranch + "'). Use -DallowBranch=" +
                            currentBranch + " to override.");
        }

        // Check release branch doesn't already exist
        String releaseBranch = "release/" + releaseVersion;
        try {
            ReleaseSupport.execCapture(gitRoot,
                    "git", "rev-parse", "--verify", releaseBranch);
            throw new MojoExecutionException(
                    "Branch '" + releaseBranch + "' already exists locally.");
        } catch (MojoExecutionException e) {
            if (e.getMessage().startsWith("Branch '")) throw e;
            // Expected — branch does not exist
        }

        String projectId = ReleaseSupport.readPomArtifactId(rootPom);

        // Build environment audit
        logAudit(gitRoot, mvnw, currentBranch, releaseBranch, oldVersion, projectId);

        // Validate clean worktree
        ReleaseSupport.requireCleanWorktree(gitRoot);

        if (dryRun) {
            getLog().info("[DRY RUN] Would create branch: " + releaseBranch);
            getLog().info("[DRY RUN] Would set version: " + oldVersion +
                    " -> " + releaseVersion);
            getLog().info("[DRY RUN] Would resolve ${project.version} -> " +
                    releaseVersion + " in all POMs");
            getLog().info("[DRY RUN] Would run: mvnw clean verify -B");
            getLog().info("[DRY RUN] Would commit, tag v" + releaseVersion);
            getLog().info("[DRY RUN] Would restore ${project.version} references");
            getLog().info("[DRY RUN] Would merge " + releaseBranch + " to main");
            getLog().info("[DRY RUN] Would bump to next version: " + nextVersion);
            getLog().info("[DRY RUN] --- all local work above, external below ---");
            if (deploySite) {
                getLog().info("[DRY RUN] Would deploy site to: " +
                        "scpexe://proxy/srv/ike-site/" + projectId + "/release");
            }
            getLog().info("[DRY RUN] Would deploy to Nexus from tag v" +
                    releaseVersion + " (last — irreversible)");
            getLog().info("[DRY RUN] Would push tag and main to origin");
            getLog().info("[DRY RUN] Would create GitHub Release");
            return;
        }

        // ── Release ───────────────────────────────────────────────────

        // Create release branch
        ReleaseSupport.exec(gitRoot, getLog(),
                "git", "checkout", "-b", releaseBranch);

        // Set version
        getLog().info("Setting version: " + oldVersion + " -> " + releaseVersion);
        ReleaseSupport.setPomVersion(rootPom, oldVersion, releaseVersion);

        // WORKAROUND: Maven 4 consumer POM doesn't resolve ${project.version}
        // in <build><plugins>, <pluginManagement>, or <dependencyManagement>.
        getLog().info("Resolving ${project.version} references:");
        List<File> resolvedPoms =
                ReleaseSupport.replaceProjectVersionRefs(gitRoot, releaseVersion, getLog());

        // Build and install (not just verify) — reactor siblings with
        // BOM imports need installed artifacts to resolve classified
        // dependencies (e.g., ike-build-standards:zip:claude). The
        // release version has never been installed, so 'verify' alone
        // fails on inter-module resolution. Using 'install' puts
        // artifacts in the local repo for sibling resolution.
        if (!skipVerify) {
            ReleaseSupport.exec(gitRoot, getLog(),
                    mvnw.getAbsolutePath(), "clean", "install", "-B", "-T", "1");
        } else {
            getLog().info("Skipping verify (-DskipVerify=true)");
        }

        // Build site (catches javadoc errors before any commits/tags).
        // -T 1 overrides .mvn/maven.config parallelism: maven-site-plugin
        // is not @ThreadSafe and emits a warning in parallel sessions.
        if (deploySite) {
            getLog().info("Building site (pre-flight check)...");
            ReleaseSupport.exec(gitRoot, getLog(),
                    mvnw.getAbsolutePath(), "site", "site:stage", "-B", "-T", "1");
        }

        // Commit
        ReleaseSupport.exec(gitRoot, getLog(), "git", "add", "pom.xml");
        ReleaseSupport.gitAddFiles(gitRoot, getLog(), resolvedPoms);
        ReleaseSupport.exec(gitRoot, getLog(),
                "git", "commit", "-m",
                "release: set version to " + releaseVersion);

        // Tag
        ReleaseSupport.exec(gitRoot, getLog(),
                "git", "tag", "-a", "v" + releaseVersion,
                "-m", "Release " + releaseVersion);

        // Restore ${project.version} references
        getLog().info("Restoring ${project.version} references:");
        List<File> restoredPoms = ReleaseSupport.restoreBackups(gitRoot, getLog());
        if (!restoredPoms.isEmpty()) {
            ReleaseSupport.gitAddFiles(gitRoot, getLog(), restoredPoms);
            ReleaseSupport.exec(gitRoot, getLog(),
                    "git", "commit", "-m",
                    "release: restore ${project.version} references");
        }

        // Merge back to main
        ReleaseSupport.exec(gitRoot, getLog(), "git", "checkout", "main");
        ReleaseSupport.exec(gitRoot, getLog(),
                "git", "merge", "--no-ff", releaseBranch,
                "-m", "merge: release " + releaseVersion);

        // ── Post-release bump ─────────────────────────────────────────

        getLog().info("");
        getLog().info("Bumping to next version: " + nextVersion);

        // Re-read version after merge (it's the release version on main now)
        String currentVersion = ReleaseSupport.readPomVersion(rootPom);
        ReleaseSupport.setPomVersion(rootPom, currentVersion, nextVersion);

        // Verify build with new SNAPSHOT version
        ReleaseSupport.exec(gitRoot, getLog(),
                mvnw.getAbsolutePath(), "clean", "verify", "-B", "-T", "1");

        // Commit
        ReleaseSupport.exec(gitRoot, getLog(), "git", "add", "pom.xml");
        ReleaseSupport.exec(gitRoot, getLog(),
                "git", "commit", "-m",
                "post-release: bump to " + nextVersion);

        // Clean up release branch
        ReleaseSupport.exec(gitRoot, getLog(),
                "git", "branch", "-d", releaseBranch);

        // ── External actions (all local work is done) ─────────────────
        // Everything above this point is local and idempotent. If any
        // external action below fails, all local git state is consistent
        // and the deploy can be retried manually.
        //
        // Order matters — most reversible actions first, irreversible last:
        //   1. Site deploy (overwritable — safe to retry)
        //   2. Nexus deploy (irreversible — release repos reject overwrites)
        //   3. Push tag + main (additive — safe to retry)
        //   4. GitHub Release (additive — safe to retry)

        getLog().info("");
        getLog().info("Local work complete. Starting external deploys...");
        getLog().info("");

        boolean hasOrigin = ReleaseSupport.hasRemote(gitRoot, "origin");

        // Deploy from the tagged release commit
        ReleaseSupport.exec(gitRoot, getLog(),
                "git", "checkout", "v" + releaseVersion);
        try {
            // Site deploy first (overwritable — can always re-deploy).
            // Must rebuild site here because the tag checkout wiped target/.
            // The local-phase site:stage was a pre-flight check only.
            //
            // Stage-and-swap: deploy to .staging dir, then atomic rename.
            // This avoids a window where the live site is missing and
            // eliminates stale files from previous releases.
            if (deploySite) {
                String releaseDisk = ReleaseSupport.siteDiskPath(
                        projectId, "release", null);
                String stagingDisk = ReleaseSupport.siteStagingPath(releaseDisk);
                String releaseUrl = "scpexe://proxy" + releaseDisk;
                String stagingUrl = ReleaseSupport.siteStagingUrl(releaseUrl);

                getLog().info("Deploying site to staging...");
                ReleaseSupport.cleanRemoteSiteDir(gitRoot, getLog(), stagingDisk);
                // Run verify before site so JaCoCo coverage data is generated.
                // The tag checkout wiped target/, so jacoco.exec must be
                // recreated for the coverage report to appear in the site.
                // -T 1: maven-site-plugin is not @ThreadSafe; sequential
                // execution avoids spurious warnings in parallel sessions.
                ReleaseSupport.exec(gitRoot, getLog(),
                        mvnw.getAbsolutePath(), "verify", "site", "site:stage",
                        "site:deploy", "-B", "-T", "1",
                        "-Dsite.deploy.url=" + stagingUrl);
                ReleaseSupport.swapRemoteSiteDir(gitRoot, getLog(), releaseDisk);
            }

            // Nexus deploy LAST — irreversible, only after everything
            // else has succeeded. Install first so reactor siblings
            // with BOM imports can resolve classified artifacts.
            getLog().info("Deploying to Nexus...");
            ReleaseSupport.exec(gitRoot, getLog(),
                    mvnw.getAbsolutePath(), "clean", "deploy", "-B", "-T", "1",
                    "-P", "release,signArtifacts");
        } finally {
            // Always return to main, even if deploy fails
            ReleaseSupport.exec(gitRoot, getLog(), "git", "checkout", "main");
        }

        // Push tag and main
        if (hasOrigin) {
            ReleaseSupport.exec(gitRoot, getLog(),
                    "git", "push", "origin", "v" + releaseVersion);
            ReleaseSupport.exec(gitRoot, getLog(),
                    "git", "push", "origin", "main");
        } else {
            getLog().info("No 'origin' remote — skipping push");
        }

        // Create GitHub Release
        if (hasOrigin) {
            try {
                ReleaseSupport.exec(gitRoot, getLog(),
                        "gh", "release", "create", "v" + releaseVersion,
                        "--title", releaseVersion,
                        "--generate-notes", "--verify-tag");
            } catch (MojoExecutionException e) {
                getLog().warn("GitHub Release creation failed (gh CLI may not be installed): " +
                        e.getMessage());
                getLog().warn("Run manually: gh release create v" + releaseVersion +
                        " --title " + releaseVersion + " --generate-notes");
            }
        } else {
            getLog().info("No 'origin' remote — skipping GitHub Release");
        }

        // Clean up the main-branch snapshot site — the release site
        // replaces it. Non-fatal if it fails (may not exist).
        if (deploySite) {
            String snapshotDisk = ReleaseSupport.siteDiskPath(
                    projectId, "snapshot", "main");
            try {
                getLog().info("Cleaning snapshot/main site...");
                ReleaseSupport.cleanRemoteSiteDir(gitRoot, getLog(), snapshotDisk);
            } catch (MojoExecutionException e) {
                getLog().warn("Could not clean snapshot site (may not exist): "
                        + e.getMessage());
            }
        }

        getLog().info("");
        getLog().info("Release " + releaseVersion + " complete.");
        getLog().info("  Tagged: v" + releaseVersion);
        getLog().info("  Deployed to Nexus");
        if (deploySite) {
            getLog().info("  Site: http://ike.komet.sh/" + projectId + "/release/");
        }
        getLog().info("  Merged to main");
        getLog().info("  Next version: " + nextVersion);
    }

    private void logAudit(File gitRoot, File mvnw, String branch,
                          String releaseBranch, String oldVersion,
                          String projectId) throws MojoExecutionException {
        String gitCommit = ReleaseSupport.execCapture(gitRoot,
                "git", "rev-parse", "--short", "HEAD");
        String mavenVersion = ReleaseSupport.execCapture(gitRoot,
                mvnw.getAbsolutePath(), "--version");
        String javaVersion = System.getProperty("java.version", "unknown");

        getLog().info("");
        getLog().info("RELEASE PARAMETERS");
        getLog().info("  Version:        " + oldVersion + " -> " + releaseVersion);
        getLog().info("  Next version:   " + nextVersion);
        getLog().info("  Source branch:  " + branch);
        getLog().info("  Release branch: " + releaseBranch);
        getLog().info("  Tag:            v" + releaseVersion);
        getLog().info("  Project:        " + projectId);
        getLog().info("  Deploy site:    " + deploySite);
        getLog().info("  Skip verify:    " + skipVerify);
        getLog().info("  Dry run:        " + dryRun);
        getLog().info("");
        getLog().info("BUILD ENVIRONMENT");
        getLog().info("  Date:           " + Instant.now());
        getLog().info("  User:           " + System.getProperty("user.name", "unknown"));
        getLog().info("  Git commit:     " + gitCommit);
        getLog().info("  Git root:       " + gitRoot.getAbsolutePath());
        getLog().info("  Maven:          " + mavenVersion.lines().findFirst().orElse("unknown"));
        getLog().info("  Java version:   " + javaVersion);
        getLog().info("  OS:             " + System.getProperty("os.name") + " " +
                System.getProperty("os.arch"));
        getLog().info("");
    }
}
