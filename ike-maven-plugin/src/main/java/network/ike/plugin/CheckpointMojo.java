package network.ike.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.time.Instant;
import java.util.List;

/**
 * Create an immutable checkpoint.
 *
 * <p>A checkpoint is a lightweight, tagged snapshot — no release
 * branch ceremony, no GPG signing. The workflow:
 * <ol>
 *   <li>Derive checkpoint version from current SNAPSHOT</li>
 *   <li>Set POM versions, resolve {@code ${project.version}}</li>
 *   <li>Build and verify</li>
 *   <li>Commit, tag with {@code checkpoint/<version>}</li>
 *   <li>Deploy to Nexus (no GPG)</li>
 *   <li>Optionally deploy site to immutable checkpoint URL</li>
 *   <li>Restore SNAPSHOT version</li>
 * </ol>
 *
 * <p>Usage:
 * <pre>
 * mvn ike:checkpoint
 * mvn ike:checkpoint -DdryRun=true
 * mvn ike:checkpoint -DdeploySite=false
 * </pre>
 */
@Mojo(name = "checkpoint", requiresProject = false, aggregator = true, threadSafe = true)
public class CheckpointMojo extends AbstractMojo {

    private static final String SITE_BASE = "scpexe://proxy/srv/ike-site/";

    @Parameter(property = "checkpointLabel")
    private String checkpointLabel;

    @Parameter(property = "deploySite", defaultValue = "true")
    private boolean deploySite;

    @Parameter(property = "dryRun", defaultValue = "false")
    private boolean dryRun;

    @Parameter(property = "skipVerify", defaultValue = "false")
    private boolean skipVerify;

    @Override
    public void execute() throws MojoExecutionException {
        File gitRoot = ReleaseSupport.gitRoot(new File("."));
        File mvnw = ReleaseSupport.resolveMavenWrapper(gitRoot, getLog());
        File rootPom = new File(gitRoot, "pom.xml");

        // Read current version and derive checkpoint version
        String oldVersion = ReleaseSupport.readPomVersion(rootPom);
        String checkpointVersion = (checkpointLabel != null && !checkpointLabel.isBlank())
                ? checkpointLabel
                : ReleaseSupport.deriveCheckpointVersion(oldVersion, gitRoot);

        String tagName = "checkpoint/" + checkpointVersion;
        String projectId = ReleaseSupport.readPomArtifactId(rootPom);

        // Audit information
        logAudit(gitRoot, mvnw, oldVersion, checkpointVersion, tagName, projectId);

        if (dryRun) {
            getLog().info("[DRY RUN] Would set version: " + oldVersion +
                    " -> " + checkpointVersion);
            getLog().info("[DRY RUN] Would resolve ${project.version} -> " +
                    checkpointVersion + " in all POMs");
            getLog().info("[DRY RUN] Would commit: checkpoint: " + checkpointVersion);
            getLog().info("[DRY RUN] Would tag: " + tagName);
            if (!skipVerify) {
                getLog().info("[DRY RUN] Would run: mvnw clean deploy -B");
            } else {
                getLog().info("[DRY RUN] Would run: mvnw clean deploy -B -DskipTests");
            }
            if (deploySite) {
                getLog().info("[DRY RUN] Would deploy site to: " +
                        SITE_BASE + projectId + "/checkpoint/" + checkpointVersion);
            }
            getLog().info("[DRY RUN] Would restore ${project.version} references");
            getLog().info("[DRY RUN] Would restore version: " + checkpointVersion +
                    " -> " + oldVersion);
            getLog().info("[DRY RUN] Would commit: checkpoint: restore SNAPSHOT version");
            return;
        }

        // Validate clean worktree
        ReleaseSupport.requireCleanWorktree(gitRoot);

        // Set POM version to checkpoint version
        getLog().info("Setting version: " + oldVersion + " -> " + checkpointVersion);
        ReleaseSupport.setPomVersion(rootPom, oldVersion, checkpointVersion);

        // WORKAROUND: same ${project.version} resolution issue as release
        getLog().info("Resolving ${project.version} references:");
        List<File> resolvedPoms =
                ReleaseSupport.replaceProjectVersionRefs(gitRoot, checkpointVersion, getLog());

        // Commit — stage root POM + all POMs that had ${project.version} resolved
        ReleaseSupport.exec(gitRoot, getLog(), "git", "add", "pom.xml");
        ReleaseSupport.gitAddFiles(gitRoot, getLog(), resolvedPoms);
        ReleaseSupport.exec(gitRoot, getLog(),
                "git", "commit", "-m",
                "checkpoint: " + checkpointVersion);

        // Tag
        ReleaseSupport.exec(gitRoot, getLog(),
                "git", "tag", "-a", tagName,
                "-m", "Checkpoint " + checkpointVersion);

        // Build, verify, and deploy to Nexus (and site in parallel if enabled)
        String siteUrl = null;
        String[] deployCommand = skipVerify
                ? new String[]{mvnw.getAbsolutePath(), "clean", "deploy", "-B", "-DskipTests"}
                : new String[]{mvnw.getAbsolutePath(), "clean", "deploy", "-B"};
        if (skipVerify) {
            getLog().info("Skipping verify (-DskipVerify=true)");
        }

        if (deploySite) {
            siteUrl = SITE_BASE + projectId + "/checkpoint/" + checkpointVersion;
            // -T 1 on the site task only: maven-site-plugin is not @ThreadSafe
            // and emits a warning in parallel sessions. The nexus task retains
            // .mvn/maven.config parallelism — the two subprocesses run
            // concurrently with each other, but each manages its own threads.
            ReleaseSupport.execParallel(gitRoot, getLog(),
                    new ReleaseSupport.LabeledTask("nexus", deployCommand),
                    new ReleaseSupport.LabeledTask("site",
                            new String[]{mvnw.getAbsolutePath(), "site", "site:stage",
                                    "site:deploy", "-B", "-T", "1",
                                    "-Dsite.deploy.url=" + siteUrl}));
        } else {
            ReleaseSupport.exec(gitRoot, getLog(), deployCommand);
        }

        // Restore ${project.version} references from backups
        getLog().info("Restoring ${project.version} references:");
        List<File> restoredPoms = ReleaseSupport.restoreBackups(gitRoot, getLog());

        // Restore original SNAPSHOT version
        getLog().info("Restoring version: " + checkpointVersion + " -> " + oldVersion);
        ReleaseSupport.setPomVersion(rootPom, checkpointVersion, oldVersion);

        // Commit restored state
        ReleaseSupport.exec(gitRoot, getLog(), "git", "add", "pom.xml");
        if (!restoredPoms.isEmpty()) {
            ReleaseSupport.gitAddFiles(gitRoot, getLog(), restoredPoms);
        }
        ReleaseSupport.exec(gitRoot, getLog(),
                "git", "commit", "-m",
                "checkpoint: restore SNAPSHOT version");

        // Push tag (if origin exists)
        boolean hasOrigin = ReleaseSupport.hasRemote(gitRoot, "origin");
        if (hasOrigin) {
            ReleaseSupport.exec(gitRoot, getLog(),
                    "git", "push", "origin", tagName);
        } else {
            getLog().info("No 'origin' remote — skipping tag push");
        }

        getLog().info("");
        getLog().info("Checkpoint " + checkpointVersion + " complete.");
        getLog().info("  Tagged: " + tagName);
        getLog().info("  Deployed to Nexus");
        if (siteUrl != null) {
            getLog().info("  Site: " + siteUrl.replace(
                    "scpexe://proxy/srv/ike-site",
                    "http://ike.komet.sh"));
        }
        getLog().info("");
    }

    private void logAudit(File gitRoot, File mvnw, String oldVersion,
                          String checkpointVersion, String tagName,
                          String projectId) throws MojoExecutionException {
        String gitCommit = ReleaseSupport.execCapture(gitRoot,
                "git", "rev-parse", "--short", "HEAD");
        String currentBranch = ReleaseSupport.currentBranch(gitRoot);
        String javaVersion = System.getProperty("java.version", "unknown");

        getLog().info("");
        getLog().info("CHECKPOINT PARAMETERS");
        getLog().info("  Version:      " + oldVersion + " -> " + checkpointVersion);
        getLog().info("  Tag:          " + tagName);
        getLog().info("  Project:      " + projectId);
        getLog().info("  Branch:       " + currentBranch);
        getLog().info("  Deploy site:  " + deploySite);
        getLog().info("  Skip verify:  " + skipVerify);
        getLog().info("  Dry run:      " + dryRun);
        getLog().info("");
        getLog().info("BUILD ENVIRONMENT");
        getLog().info("  Date:         " + Instant.now());
        getLog().info("  User:         " + System.getProperty("user.name", "unknown"));
        getLog().info("  Git commit:   " + gitCommit);
        getLog().info("  Git root:     " + gitRoot.getAbsolutePath());
        getLog().info("  Java version: " + javaVersion);
        getLog().info("  OS:           " + System.getProperty("os.name") + " " +
                System.getProperty("os.arch"));
        getLog().info("");
    }
}
