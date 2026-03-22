package network.ike.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;

/**
 * Remove a deployed site directory from the server.
 *
 * <p>Useful for manual cleanup of stale snapshot or checkpoint sites
 * that were not automatically removed by {@code feature-finish} or
 * {@code release}.
 *
 * <p>Usage:
 * <pre>
 * mvn ike:clean-site -DsiteType=snapshot -Dbranch=feature/old-work
 * mvn ike:clean-site -DsiteType=checkpoint -DsiteVersion=7-checkpoint.20260101.1
 * mvn ike:clean-site -DsiteType=snapshot              # cleans current branch's snapshot
 * </pre>
 */
@Mojo(name = "clean-site", requiresProject = false, threadSafe = true)
public class CleanSiteMojo extends AbstractMojo {

    /**
     * Site type to clean: snapshot, checkpoint, or release.
     */
    @Parameter(property = "siteType", required = true)
    private String siteType;

    /**
     * Branch whose snapshot site should be cleaned.
     * Defaults to the current git branch.
     * Only used when siteType=snapshot.
     */
    @Parameter(property = "branch")
    private String branch;

    /**
     * Version of the checkpoint site to clean.
     * Only used when siteType=checkpoint.
     */
    @Parameter(property = "siteVersion")
    private String siteVersion;

    /** Show plan without executing. */
    @Parameter(property = "dryRun", defaultValue = "false")
    private boolean dryRun;

    @Override
    public void execute() throws MojoExecutionException {
        File gitRoot = ReleaseSupport.gitRoot(new File("."));
        File rootPom = new File(gitRoot, "pom.xml");
        String projectId = ReleaseSupport.readPomArtifactId(rootPom);

        String diskPath;

        switch (siteType) {
            case "release" -> {
                diskPath = ReleaseSupport.siteDiskPath(projectId, "release", null);
            }
            case "snapshot" -> {
                if (branch == null || branch.isBlank()) {
                    branch = ReleaseSupport.currentBranch(gitRoot);
                }
                String safeBranch = ReleaseSupport.branchToSitePath(branch);
                diskPath = ReleaseSupport.siteDiskPath(
                        projectId, "snapshot", safeBranch);
            }
            case "checkpoint" -> {
                if (siteVersion == null || siteVersion.isBlank()) {
                    throw new MojoExecutionException(
                            "siteVersion is required for checkpoint cleanup. "
                                    + "Specify -DsiteVersion=<version>.");
                }
                diskPath = ReleaseSupport.siteDiskPath(
                        projectId, "checkpoint", siteVersion);
            }
            default -> throw new MojoExecutionException(
                    "Invalid siteType: '" + siteType
                            + "'. Must be one of: release, snapshot, checkpoint");
        }

        getLog().info("");
        getLog().info("SITE CLEANUP");
        getLog().info("  Project:   " + projectId);
        getLog().info("  Type:      " + siteType);
        if ("snapshot".equals(siteType)) {
            getLog().info("  Branch:    " + branch);
        }
        if ("checkpoint".equals(siteType)) {
            getLog().info("  Version:   " + siteVersion);
        }
        getLog().info("  Disk path: " + diskPath);
        getLog().info("");

        if (dryRun) {
            getLog().info("[DRY RUN] Would remove: " + diskPath);
            return;
        }

        ReleaseSupport.cleanRemoteSiteDir(gitRoot, getLog(), diskPath);
        getLog().info("Cleaned: " + diskPath);
    }
}
