package network.ike.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Displays available IKE build tool goals.
 *
 * @see <a href="https://github.com/IKE-Network/ike-pipeline">IKE Pipeline</a>
 */
@Mojo(name = "help", requiresProject = false, threadSafe = true)
public class IkeHelpMojo extends AbstractMojo {

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("");
        getLog().info("IKE Build Tools — Available Goals");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("");
        getLog().info("  ── Workspace Goals ──────────────────────────────────────");
        getLog().info("  ike:dashboard                                   Composite overview (verify+status+cascade)");
        getLog().info("  ike:status                                      Git status across all repos");
        getLog().info("  ike:verify                                      Check manifest consistency");
        getLog().info("  ike:cascade                                     Show downstream impact of a change");
        getLog().info("  ike:graph                                       Print dependency graph");
        getLog().info("  ike:init                                        Clone/initialize repos from manifest");
        getLog().info("  ike:pull                                        Git pull --rebase across repos");
        getLog().info("  ike:stignore                                    Generate .stignore for Syncthing");
        getLog().info("");
        getLog().info("  ── Gitflow Goals ────────────────────────────────────────");
        getLog().info("  ike:feature-start                               Create feature branch across repos");
        getLog().info("  ike:feature-finish                              Merge feature branch to main");
        getLog().info("  ike:ws-checkpoint                               Record multi-repo checkpoint (SHAs+versions)");
        getLog().info("  ike:ws-release                                  Release all dirty components in topo order");
        getLog().info("");
        getLog().info("  ── Release Goals ────────────────────────────────────────");
        getLog().info("  ike:help                                        This help message");
        getLog().info("  ike:release                                     Full release + bump to next SNAPSHOT");
        getLog().info("  ike:generate-bom                                Auto-generate BOM from ike-parent");
        getLog().info("  ike:checkpoint                                  Create single-repo immutable checkpoint");
        getLog().info("  ike:deploy-site                                 Deploy site to versioned URL");
        getLog().info("  ike:clean-site                                  Remove a deployed site from the server");
        getLog().info("");
        getLog().info("Options for workspace goals:");
        getLog().info("  -Dworkspace.manifest=<path>  Path to workspace.yaml (auto-detected)");
        getLog().info("  -Dgroup=<name>               Restrict to group (status, init, pull)");
        getLog().info("  -Dcomponent=<name>           Component for ike:cascade (required)");
        getLog().info("  -Dformat=dot                 Graphviz DOT output for ike:graph");
        getLog().info("");
        getLog().info("Options for gitflow goals:");
        getLog().info("  -Dfeature=<name>       Feature name (branch: feature/<name>)");
        getLog().info("  -Dgroup=<name>         Restrict to group");
        getLog().info("  -DskipVersion=true     Skip POM version qualification (feature-start)");
        getLog().info("  -DtargetBranch=<name>  Merge target (default: main)");
        getLog().info("  -Dpush=true            Push to origin after merge/tag");
        getLog().info("  -DdryRun=true          Show plan without executing");
        getLog().info("");
        getLog().info("Options for ike:ws-checkpoint:");
        getLog().info("  -Dname=<name>          Checkpoint name (required)");
        getLog().info("  -Dtag=true             Tag each component");
        getLog().info("  -Dpush=true            Push tags to origin");
        getLog().info("");
        getLog().info("Options for ike:ws-release:");
        getLog().info("  -Dcomponent=<name>     Release one specific component");
        getLog().info("  -Dgroup=<name>         Restrict to components in group");
        getLog().info("  -DdryRun=true          Show what would be released");
        getLog().info("  -Dpush=true            Push releases to origin (default: true)");
        getLog().info("  -DskipCheckpoint=true  Skip pre-release checkpoint");
        getLog().info("");
        getLog().info("Options for ike:release:");
        getLog().info("  -DreleaseVersion=<v>   Version to release (auto-derived from POM)");
        getLog().info("  -DnextVersion=<v>      Next SNAPSHOT (auto-derived)");
        getLog().info("  -DdryRun=true          Show plan without executing");
        getLog().info("  -DskipVerify=true      Skip 'mvnw clean verify'");
        getLog().info("  -DallowBranch=<name>   Allow release from non-main branch");
        getLog().info("  -DdeploySite=false     Skip site deployment");
        getLog().info("");
        getLog().info("Options for ike:checkpoint:");
        getLog().info("  -DdryRun=true          Show plan without executing");
        getLog().info("  -DskipVerify=true      Skip 'mvnw clean verify'");
        getLog().info("  -DdeploySite=false     Skip site deployment");
        getLog().info("  -DcheckpointLabel=<v>  Custom checkpoint version label");
        getLog().info("");
        getLog().info("Options for ike:deploy-site:");
        getLog().info("  -DsiteType=<type>      One of: release, snapshot, checkpoint");
        getLog().info("  -Dbranch=<name>        Branch for snapshot path (auto-detected)");
        getLog().info("  -DsiteVersion=<v>      Version for checkpoint URL path");
        getLog().info("  -DdryRun=true          Show plan without executing");
        getLog().info("  -DskipBuild=true       Skip 'mvnw clean verify'");
        getLog().info("  -DskipSwap=true        Deploy directly (no atomic swap)");
        getLog().info("");
        getLog().info("Options for ike:clean-site:");
        getLog().info("  -DsiteType=<type>      One of: release, snapshot, checkpoint");
        getLog().info("  -Dbranch=<name>        Branch for snapshot (auto-detected)");
        getLog().info("  -DsiteVersion=<v>      Version for checkpoint cleanup");
        getLog().info("  -DdryRun=true          Show plan without executing");
        getLog().info("");
    }
}
