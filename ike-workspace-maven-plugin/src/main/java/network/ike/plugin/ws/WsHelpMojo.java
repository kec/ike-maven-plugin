package network.ike.plugin.ws;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Displays available ws: workspace goals.
 *
 * @see <a href="https://github.com/IKE-Network/ike-pipeline">IKE Pipeline</a>
 */
@Mojo(name = "help", requiresProject = false, threadSafe = true)
public class WsHelpMojo extends AbstractMojo {

    /** Creates this goal instance. */
    public WsHelpMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("");
        getLog().info("IKE Workspace Tools — Available Goals");
        getLog().info("══════════════════════════════════════════════════════════════");
        getLog().info("");
        getLog().info("  ── Workspace Management ─────────────────────────────────");
        getLog().info("  ws:create                                       Create a new workspace (workspace.yaml + reactor POM)");
        getLog().info("  ws:add                                          Add a component to the workspace manifest");
        getLog().info("  ws:init                                         Clone/initialize repos from workspace.yaml");
        getLog().info("  ws:verify                                       Check manifest + VCS bridge state");
        getLog().info("  ws:fix                                          Auto-fix issues found by verify");
        getLog().info("  ws:status                                       Git status across all repos");
        getLog().info("  ws:dashboard                                    Composite overview (verify + status + cascade)");
        getLog().info("  ws:graph                                        Print dependency graph");
        getLog().info("  ws:stignore                                     Generate .stignore for Syncthing");
        getLog().info("  ws:sync                                         Sync workspace.yaml <-> actual branches");
        getLog().info("  ws:upgrade                                      Upgrade workspace plugin/reactor versions");
        getLog().info("  ws:pull                                         Git pull --rebase across repos");
        getLog().info("  ws:remove                                       Remove a component from the workspace");
        getLog().info("");
        getLog().info("  ── Feature Branching ────────────────────────────────────");
        getLog().info("  ws:feature-start                                Create feature branch across repos");
        getLog().info("  ws:feature-start-dry-run                        Preview feature branch creation");
        getLog().info("  ws:feature-abandon                              Abandon feature branch across repos");
        getLog().info("  ws:feature-finish-merge                         No-ff merge, keeps branch alive");
        getLog().info("  ws:feature-finish-rebase                        Rebase onto target, linear history");
        getLog().info("  ws:feature-finish-squash                        Squash-merge (default, deletes branch)");
        getLog().info("");
        getLog().info("  ── Release & Checkpoint ─────────────────────────────────");
        getLog().info("  ws:release                                      Release all dirty components in topo order");
        getLog().info("  ws:checkpoint                                   Record multi-repo checkpoint (SHAs + versions)");
        getLog().info("  ws:checkpoint-dry-run                           Preview checkpoint without writing files or tags");
        getLog().info("  ws:post-release                                 Post-release cleanup (bump to next SNAPSHOT)");
        getLog().info("  ws:align                                        Align dependency versions across workspace");
        getLog().info("");
        getLog().info("  ── VCS Bridge ───────────────────────────────────────────");
        getLog().info("  ws:vcs-sync                                     Reconcile git state after machine switch");
        getLog().info("  ws:commit                                       Catch-up + commit");
        getLog().info("  ws:push                                         Catch-up + push");
        getLog().info("  ws:check-branch                                 Warn on direct branching (git hook)");
        getLog().info("");
        getLog().info("  ── Cascade ──────────────────────────────────────────────");
        getLog().info("  ws:cascade                                      Show downstream impact of a change");
        getLog().info("");
        getLog().info("  ws:help                                         This help message");
        getLog().info("");
        getLog().info("Options for workspace management:");
        getLog().info("  -Dworkspace.manifest=<path>  Path to workspace.yaml (auto-detected)");
        getLog().info("  -Dgroup=<name>               Restrict to group (status, init, pull)");
        getLog().info("  -Dcomponent=<name>           Component for ws:cascade (required)");
        getLog().info("  -Dformat=dot                 Graphviz DOT output for ws:graph");
        getLog().info("");
        getLog().info("Options for feature branching:");
        getLog().info("  -Dfeature=<name>             Feature name (branch: feature/<name>)");
        getLog().info("  -Dgroup=<name>               Restrict to group");
        getLog().info("  -DskipVersion=true           Skip POM version qualification (feature-start)");
        getLog().info("  -DtargetBranch=<name>        Merge target (default: main)");
        getLog().info("  -DkeepBranch=true            Keep branch after merge (feature-finish)");
        getLog().info("  -Dmessage=<msg>              Squash commit message (feature-finish-squash)");
        getLog().info("  -DdryRun=true                Show plan without executing");
        getLog().info("");
        getLog().info("Options for release & checkpoint:");
        getLog().info("  -Dname=<name>                Checkpoint name (required for checkpoint)");
        getLog().info("  -Dcomponent=<name>           Release one specific component");
        getLog().info("  -Dgroup=<name>               Restrict to components in group");
        getLog().info("  -DdeploySite=true            Deploy site for each component");
        getLog().info("  -DskipVerify=true            Skip tests during build");
        getLog().info("  -DdryRun=true                Show what would be released");
        getLog().info("  -Dpush=true                  Push releases to origin (default: true)");
        getLog().info("  -DskipCheckpoint=true        Skip pre-release checkpoint");
        getLog().info("");
        getLog().info("Options for VCS bridge:");
        getLog().info("  -Dmessage=<msg>              Commit message (ws:commit)");
        getLog().info("  -DaddAll=true                Stage all changes before commit");
        getLog().info("  -Dpush=true                  Push after commit");
        getLog().info("  -Dremote=<name>              Remote name (default: origin)");
        getLog().info("");
        getLog().info("Options for ws:sync:");
        getLog().info("  -Dfrom=repos                 Update workspace.yaml from repos (default)");
        getLog().info("  -Dfrom=manifest              Switch repos to match workspace.yaml");
        getLog().info("  -DdryRun=true                Show plan without executing");
        getLog().info("");
    }
}
