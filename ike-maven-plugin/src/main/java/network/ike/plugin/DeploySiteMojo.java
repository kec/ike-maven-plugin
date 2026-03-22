package network.ike.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;

/**
 * Generate and deploy the Maven site to a versioned URL.
 *
 * <p>This goal deploys the project site to one of three location
 * types under {@code ike.komet.sh}:
 * <ul>
 *   <li>{@code release} — overwritten on each release</li>
 *   <li>{@code snapshot} — versioned by git branch
 *       (e.g., {@code snapshot/main/}, {@code snapshot/feature/my-work/})</li>
 *   <li>{@code checkpoint} — immutable, versioned subdirectory</li>
 * </ul>
 *
 * <p>Every deployment uses a stage-and-swap strategy: SCP uploads
 * to a {@code .staging} directory, then an atomic rename replaces
 * the live directory. This eliminates stale files from previous
 * deploys (SCP alone only copies, never deletes) and avoids a
 * window where the site is missing.
 *
 * <p>Usage:
 * <pre>
 * mvn ike:deploy-site -DsiteType=snapshot
 * mvn ike:deploy-site -DsiteType=snapshot -Dbranch=feature/kec-march-25
 * mvn ike:deploy-site -DsiteType=checkpoint -DsiteVersion=7-checkpoint.20260228.1
 * mvn ike:deploy-site -DsiteType=release
 * </pre>
 */
@Mojo(name = "deploy-site", requiresProject = false, aggregator = true, threadSafe = true)
public class DeploySiteMojo extends AbstractMojo {

    private static final String SITE_URL_BASE = "scpexe://proxy/srv/ike-site/";

    @Parameter(property = "siteType", required = true)
    private String siteType;

    /**
     * Explicit site version for checkpoint deploys.
     * Defaults to the POM version.
     */
    @Parameter(property = "siteVersion")
    private String siteVersion;

    /**
     * Git branch for snapshot deploys. Defaults to the current branch.
     * Used to derive the snapshot subdirectory
     * (e.g., {@code main} → {@code snapshot/main/}).
     */
    @Parameter(property = "branch")
    private String branch;

    /** Show plan without executing. */
    @Parameter(property = "dryRun", defaultValue = "false")
    private boolean dryRun;

    /** Skip the {@code mvn clean verify} step. */
    @Parameter(property = "skipBuild", defaultValue = "false")
    private boolean skipBuild;

    /**
     * Skip the atomic swap (deploy directly over the live directory).
     * Not recommended — leaves stale files from previous deploys
     * and causes a brief window where the site is incomplete.
     */
    @Parameter(property = "skipSwap", defaultValue = "false")
    private boolean skipSwap;

    @Override
    public void execute() throws MojoExecutionException {
        File gitRoot = ReleaseSupport.gitRoot(new File("."));
        File mvnw = ReleaseSupport.resolveMavenWrapper(gitRoot, getLog());
        File rootPom = new File(gitRoot, "pom.xml");

        String projectId = ReleaseSupport.readPomArtifactId(rootPom);

        // Default siteVersion from POM version
        if (siteVersion == null || siteVersion.isBlank()) {
            siteVersion = ReleaseSupport.readPomVersion(rootPom);
        }

        // Default branch from git
        if (branch == null || branch.isBlank()) {
            branch = ReleaseSupport.currentBranch(gitRoot);
        }

        // Resolve target URL and disk path
        String subPath;
        String targetUrl;
        String diskPath;

        switch (siteType) {
            case "release" -> {
                subPath = null;
                targetUrl = SITE_URL_BASE + projectId + "/release";
                diskPath = ReleaseSupport.siteDiskPath(projectId, "release", null);
            }
            case "snapshot" -> {
                String safeBranch = ReleaseSupport.branchToSitePath(branch);
                subPath = safeBranch;
                targetUrl = SITE_URL_BASE + projectId + "/snapshot/" + safeBranch;
                diskPath = ReleaseSupport.siteDiskPath(projectId, "snapshot", safeBranch);
            }
            case "checkpoint" -> {
                subPath = siteVersion;
                targetUrl = SITE_URL_BASE + projectId + "/checkpoint/" + siteVersion;
                diskPath = ReleaseSupport.siteDiskPath(projectId, "checkpoint", siteVersion);
            }
            default -> throw new MojoExecutionException(
                    "Invalid siteType: '" + siteType +
                            "'. Must be one of: release, snapshot, checkpoint");
        }

        getLog().info("");
        getLog().info("SITE DEPLOYMENT");
        getLog().info("  Project:     " + projectId);
        getLog().info("  Site type:   " + siteType);
        if ("snapshot".equals(siteType)) {
            getLog().info("  Branch:      " + branch);
        }
        if ("checkpoint".equals(siteType)) {
            getLog().info("  Version:     " + siteVersion);
        }
        getLog().info("  Target URL:  " + targetUrl);
        getLog().info("  Disk path:   " + diskPath);
        getLog().info("  Skip build:  " + skipBuild);
        getLog().info("  Skip swap:   " + skipSwap);
        getLog().info("  Dry run:     " + dryRun);
        getLog().info("");

        // Determine deploy URL — either staging dir or direct
        String deployUrl = skipSwap ? targetUrl
                : ReleaseSupport.siteStagingUrl(targetUrl);
        String stagingDisk = ReleaseSupport.siteStagingPath(diskPath);

        if (dryRun) {
            if (!skipBuild) {
                getLog().info("[DRY RUN] Would run: mvnw clean verify -B");
            }
            if (!skipSwap) {
                getLog().info("[DRY RUN] Would clean staging dir: " + stagingDisk);
                getLog().info("[DRY RUN] Would deploy site to staging: " + deployUrl);
                getLog().info("[DRY RUN] Would swap: " + stagingDisk + " → " + diskPath);
            } else {
                getLog().info("[DRY RUN] Would deploy site to: " + targetUrl);
            }
            return;
        }

        // Build first (unless skipped)
        if (!skipBuild) {
            ReleaseSupport.exec(gitRoot, getLog(),
                    mvnw.getAbsolutePath(), "clean", "verify", "-B");
        }

        if (!skipSwap) {
            // Clean any leftover staging directory
            ReleaseSupport.cleanRemoteSiteDir(gitRoot, getLog(), stagingDisk);
        }

        // Generate, stage, and deploy site (to staging dir or direct)
        ReleaseSupport.exec(gitRoot, getLog(),
                mvnw.getAbsolutePath(), "site", "site:stage", "site:deploy", "-B",
                "-Dsite.deploy.url=" + deployUrl);

        if (!skipSwap) {
            // Atomic swap: staging → live
            ReleaseSupport.swapRemoteSiteDir(gitRoot, getLog(), diskPath);
        }

        String publicUrl = toPublicSiteUrl(targetUrl);
        getLog().info("");
        getLog().info("Site deployed to: " + publicUrl);
    }

    // ── URL conversion (pure, static, testable) ─────────────────────

    /**
     * Convert an internal SCP-style site URL to its public HTTP equivalent.
     *
     * <p>Replaces the {@code scpexe://proxy/srv/ike-site} prefix with
     * {@code http://ike.komet.sh}.
     *
     * @param scpUrl the internal deployment URL
     * @return the public-facing URL
     */
    public static String toPublicSiteUrl(String scpUrl) {
        return scpUrl.replace("scpexe://proxy/srv/ike-site", "http://ike.komet.sh");
    }
}
