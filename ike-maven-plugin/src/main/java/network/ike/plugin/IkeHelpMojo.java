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

    /** Creates this goal instance. */
    public IkeHelpMojo() {}

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
        getLog().info("  ike:check-branch                                Warn on direct branching (git hook)");
        getLog().info("  ike:ws-sync                                     Sync workspace.yaml ↔ actual branches");
        getLog().info("");
        getLog().info("  ── Gitflow Goals ────────────────────────────────────────");
        getLog().info("  ike:feature-start                               Create feature branch across repos");
        getLog().info("  ike:feature-start-dry-run                       Preview feature branch creation (interactive)");
        getLog().info("  ike:feature-finish                              Merge feature branch to main");
        getLog().info("  ike:feature-finish-dry-run                      Preview feature branch merge (interactive)");
        getLog().info("  ike:ws-checkpoint                               Record multi-repo checkpoint (SHAs+versions)");
        getLog().info("  ike:ws-checkpoint-dry-run                       Preview checkpoint without writing files or tags");
        getLog().info("  ike:ws-release                                  Release all dirty components in topo order");
        getLog().info("");
        getLog().info("  ── Release Goals ────────────────────────────────────────");
        getLog().info("  ike:help                                        This help message");
        getLog().info("  ike:release                                     Full release + bump to next SNAPSHOT");
        getLog().info("  ike:generate-bom                                Auto-generate BOM from ike-parent");
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
        getLog().info("Options for ike:ws-sync:");
        getLog().info("  -Dfrom=repos           Update workspace.yaml from repos (default)");
        getLog().info("  -Dfrom=manifest        Switch repos to match workspace.yaml");
        getLog().info("  -DdryRun=true          Show plan without executing");
        getLog().info("");
        getLog().info("Options for ike:ws-checkpoint / ike:ws-checkpoint-dry-run:");
        getLog().info("  -Dname=<name>          Checkpoint name (required)");
        getLog().info("  -DdeploySite=true      Deploy site for each component");
        getLog().info("  -DskipVerify=true      Skip tests during build");
        getLog().info("  -DdryRun=true          Preview without writing files or tags");
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
