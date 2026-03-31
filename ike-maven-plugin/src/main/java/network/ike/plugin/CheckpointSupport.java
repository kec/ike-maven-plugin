package network.ike.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

import java.io.File;
import java.time.Instant;
import java.util.List;

/**
 * Per-component checkpoint engine — build, tag, and deploy a single repo.
 *
 * <p>This is the internal engine invoked by {@link WsCheckpointMojo}
 * for each workspace component in topological order. It is not exposed
 * as a standalone Maven goal.
 *
 * <p>The workflow for a single component:
 * <ol>
 *   <li>Validate clean working tree</li>
 *   <li>Set POM versions, resolve {@code ${project.version}}</li>
 *   <li>Commit, tag with {@code checkpoint/<version>}</li>
 *   <li>Run {@code mvnw clean deploy} — build and publish to Nexus</li>
 *   <li>Optionally deploy site to immutable checkpoint URL</li>
 *   <li>Restore SNAPSHOT version and commit</li>
 *   <li>Push tag to origin</li>
 * </ol>
 */
class CheckpointSupport {

    private static final String SITE_BASE = "scpexe://proxy/srv/ike-site/";

    private CheckpointSupport() {}

    /**
     * Execute a full checkpoint for a single component directory.
     *
     * @param dir               component git root directory
     * @param checkpointVersion the version to stamp (e.g., {@code 1.0.0-checkpoint.20260330.abc1234})
     * @param deploySite        whether to deploy site documentation
     * @param skipVerify        whether to skip tests during the build
     * @param log               Maven logger
     * @throws MojoExecutionException if any step fails
     */
    static void checkpoint(File dir, String checkpointVersion,
                           boolean deploySite, boolean skipVerify, Log log)
            throws MojoExecutionException {
        File gitRoot = ReleaseSupport.gitRoot(dir);
        File mvnw = ReleaseSupport.resolveMavenWrapper(gitRoot, log);
        File rootPom = new File(gitRoot, "pom.xml");

        String oldVersion = ReleaseSupport.readPomVersion(rootPom);
        String tagName = "checkpoint/" + checkpointVersion;
        String projectId = ReleaseSupport.readPomArtifactId(rootPom);

        logAudit(gitRoot, oldVersion, checkpointVersion, tagName,
                projectId, deploySite, skipVerify, log);

        // Validate clean worktree
        ReleaseSupport.requireCleanWorktree(gitRoot);

        // Set POM version to checkpoint version
        log.info("Setting version: " + oldVersion + " -> " + checkpointVersion);
        ReleaseSupport.setPomVersion(rootPom, oldVersion, checkpointVersion);

        // Resolve ${project.version} references
        log.info("Resolving ${project.version} references:");
        List<File> resolvedPoms =
                ReleaseSupport.replaceProjectVersionRefs(gitRoot, checkpointVersion, log);

        // Commit — stage root POM + all POMs that had ${project.version} resolved
        ReleaseSupport.exec(gitRoot, log, "git", "add", "pom.xml");
        ReleaseSupport.gitAddFiles(gitRoot, log, resolvedPoms);
        ReleaseSupport.exec(gitRoot, log,
                "git", "commit", "-m",
                "checkpoint: " + checkpointVersion);

        // Tag
        ReleaseSupport.exec(gitRoot, log,
                "git", "tag", "-a", tagName,
                "-m", "Checkpoint " + checkpointVersion);

        // Build, verify, and deploy to Nexus (and site in parallel if enabled)
        String siteUrl = null;
        String[] deployCommand = skipVerify
                ? new String[]{mvnw.getAbsolutePath(), "clean", "deploy", "-B", "-DskipTests"}
                : new String[]{mvnw.getAbsolutePath(), "clean", "deploy", "-B"};
        if (skipVerify) {
            log.info("Skipping verify (-DskipVerify=true)");
        }

        if (deploySite) {
            siteUrl = SITE_BASE + projectId + "/checkpoint/" + checkpointVersion;
            ReleaseSupport.execParallel(gitRoot, log,
                    new ReleaseSupport.LabeledTask("nexus", deployCommand),
                    new ReleaseSupport.LabeledTask("site",
                            new String[]{mvnw.getAbsolutePath(), "site", "site:stage",
                                    "site:deploy", "-B", "-T", "1",
                                    "-Dsite.deploy.url=" + siteUrl}));
        } else {
            ReleaseSupport.exec(gitRoot, log, deployCommand);
        }

        // Restore ${project.version} references from backups
        log.info("Restoring ${project.version} references:");
        List<File> restoredPoms = ReleaseSupport.restoreBackups(gitRoot, log);

        // Restore original SNAPSHOT version
        log.info("Restoring version: " + checkpointVersion + " -> " + oldVersion);
        ReleaseSupport.setPomVersion(rootPom, checkpointVersion, oldVersion);

        // Commit restored state
        ReleaseSupport.exec(gitRoot, log, "git", "add", "pom.xml");
        if (!restoredPoms.isEmpty()) {
            ReleaseSupport.gitAddFiles(gitRoot, log, restoredPoms);
        }
        ReleaseSupport.exec(gitRoot, log,
                "git", "commit", "-m",
                "checkpoint: restore SNAPSHOT version");

        // Push tag (if origin exists)
        boolean hasOrigin = ReleaseSupport.hasRemote(gitRoot, "origin");
        if (hasOrigin) {
            ReleaseSupport.exec(gitRoot, log,
                    "git", "push", "origin", tagName);
        } else {
            log.info("No 'origin' remote — skipping tag push");
        }

        log.info("");
        log.info("Checkpoint " + checkpointVersion + " complete.");
        log.info("  Tagged: " + tagName);
        log.info("  Deployed to Nexus");
        if (siteUrl != null) {
            log.info("  Site: " + siteUrl.replace(
                    "scpexe://proxy/srv/ike-site",
                    "http://ike.komet.sh"));
        }
        log.info("");
    }

    /**
     * Log a dry-run summary for a single component.
     *
     * @param dir               component git root directory
     * @param checkpointVersion the version that would be stamped
     * @param deploySite        whether site would be deployed
     * @param skipVerify        whether tests would be skipped
     * @param log               Maven logger
     */
    static void dryRun(File dir, String checkpointVersion,
                       boolean deploySite, boolean skipVerify, Log log)
            throws MojoExecutionException {
        File gitRoot = ReleaseSupport.gitRoot(dir);
        File mvnw = ReleaseSupport.resolveMavenWrapper(gitRoot, log);
        File rootPom = new File(gitRoot, "pom.xml");

        String oldVersion = ReleaseSupport.readPomVersion(rootPom);
        String tagName = "checkpoint/" + checkpointVersion;
        String projectId = ReleaseSupport.readPomArtifactId(rootPom);

        log.info("[DRY RUN] Would set version: " + oldVersion +
                " -> " + checkpointVersion);
        log.info("[DRY RUN] Would resolve ${project.version} -> " +
                checkpointVersion + " in all POMs");
        log.info("[DRY RUN] Would commit: checkpoint: " + checkpointVersion);
        log.info("[DRY RUN] Would tag: " + tagName);
        if (!skipVerify) {
            log.info("[DRY RUN] Would run: mvnw clean deploy -B");
        } else {
            log.info("[DRY RUN] Would run: mvnw clean deploy -B -DskipTests");
        }
        if (deploySite) {
            log.info("[DRY RUN] Would deploy site to: " +
                    SITE_BASE + projectId + "/checkpoint/" + checkpointVersion);
        }
        log.info("[DRY RUN] Would restore ${project.version} references");
        log.info("[DRY RUN] Would restore version: " + checkpointVersion +
                " -> " + oldVersion);
        log.info("[DRY RUN] Would commit: checkpoint: restore SNAPSHOT version");
    }

    private static void logAudit(File gitRoot, String oldVersion,
                                  String checkpointVersion, String tagName,
                                  String projectId, boolean deploySite,
                                  boolean skipVerify, Log log)
            throws MojoExecutionException {
        String gitCommit = ReleaseSupport.execCapture(gitRoot,
                "git", "rev-parse", "--short", "HEAD");
        String currentBranch = ReleaseSupport.currentBranch(gitRoot);
        String javaVersion = System.getProperty("java.version", "unknown");

        log.info("");
        log.info("CHECKPOINT PARAMETERS");
        log.info("  Version:      " + oldVersion + " -> " + checkpointVersion);
        log.info("  Tag:          " + tagName);
        log.info("  Project:      " + projectId);
        log.info("  Branch:       " + currentBranch);
        log.info("  Deploy site:  " + deploySite);
        log.info("  Skip verify:  " + skipVerify);
        log.info("");
        log.info("BUILD ENVIRONMENT");
        log.info("  Date:         " + Instant.now());
        log.info("  User:         " + System.getProperty("user.name", "unknown"));
        log.info("  Git commit:   " + gitCommit);
        log.info("  Git root:     " + gitRoot.getAbsolutePath());
        log.info("  Java version: " + javaVersion);
        log.info("  OS:           " + System.getProperty("os.name") + " " +
                System.getProperty("os.arch"));
        log.info("");
    }
}
