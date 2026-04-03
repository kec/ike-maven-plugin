package network.ike.plugin.ws;

import network.ike.workspace.Component;
import network.ike.workspace.WorkspaceGraph;
import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

/**
 * Verify workspace manifest consistency and VCS bridge state.
 *
 * <p>Checks that all dependency references resolve, no cycles exist,
 * all group members are valid, and all component types are defined.
 * Also reports VCS bridge state, Syncthing health, and environment
 * presence.
 *
 * <pre>{@code mvn ike:verify}</pre>
 */
@Mojo(name = "verify", requiresProject = false, threadSafe = true)
public class VerifyWorkspaceMojo extends AbstractWorkspaceMojo {

    /** Creates this goal instance. */
    public VerifyWorkspaceMojo() {}

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("");
        getLog().info("IKE Environment Verification");
        getLog().info("══════════════════════════════════════════════════════════════");

        if (isWorkspaceMode()) {
            verifyWorkspaceManifest();
            verifyWorkspaceVcs();
        } else {
            verifyBareVcs();
        }

        verifyEnvironment();
        getLog().info("");
    }

    // ── Workspace manifest verification (existing logic) ──────────

    private void verifyWorkspaceManifest() throws MojoExecutionException {
        WorkspaceGraph graph = loadGraph();

        List<String> errors = graph.verify();

        int componentCount = graph.manifest().components().size();
        int typeCount = graph.manifest().componentTypes().size();
        int groupCount = graph.manifest().groups().size();

        getLog().info("  Components:      " + componentCount);
        getLog().info("  Component types: " + typeCount);
        getLog().info("  Groups:          " + groupCount);
        getLog().info("");

        if (errors.isEmpty()) {
            getLog().info("  Manifest:    consistent  ✓");
        } else {
            getLog().error("  Manifest:    " + errors.size() + " error(s)");
            for (String error : errors) {
                getLog().error("    ✗ " + error);
            }
        }
    }

    // ── VCS bridge verification (workspace mode) ─────────────────

    private void verifyWorkspaceVcs() throws MojoExecutionException {
        WorkspaceGraph graph = loadGraph();
        File root = workspaceRoot();

        getLog().info("");

        // Workspace repo itself
        if (VcsState.isIkeManaged(root.toPath())) {
            getLog().info("  Workspace");
            reportVcsState(root, "    ");
        }

        // Each component
        for (var entry : graph.manifest().components().entrySet()) {
            String name = entry.getKey();
            File dir = new File(root, name);

            if (!new File(dir, ".git").exists()) {
                continue;
            }

            getLog().info("  " + name);

            if (!VcsState.isIkeManaged(dir.toPath())) {
                getLog().info("    VCS bridge: not IKE-managed");
                continue;
            }

            reportVcsState(dir, "    ");
        }
    }

    // ── VCS bridge verification (bare mode) ──────────────────────

    private void verifyBareVcs() throws MojoExecutionException {
        File dir = new File(System.getProperty("user.dir"));
        String dirName = dir.getName();

        getLog().info("  Machine:     " + hostname());

        if (!VcsState.isIkeManaged(dir.toPath())) {
            getLog().info("  VCS bridge:  not an IKE-managed repo");
            return;
        }

        getLog().info("");
        getLog().info("  " + dirName);
        reportVcsState(dir, "    ");
    }

    // ── Shared VCS state reporting ───────────────────────────────

    private void reportVcsState(File dir, String indent)
            throws MojoExecutionException {
        String localBranch = gitBranch(dir);
        String localSha = gitShortSha(dir);

        getLog().info(indent + "Branch:        " + localBranch);
        getLog().info(indent + "Local HEAD:    " + localSha);

        Optional<VcsState> stateOpt = VcsState.readFrom(dir.toPath());

        if (stateOpt.isEmpty()) {
            getLog().info(indent + "State file:    absent (first commit, or Syncthing not delivered)");
            getLog().info(indent + "Status:        no state file  ─");
            return;
        }

        VcsState state = stateOpt.get();
        getLog().info(indent + "State file:    " + state.action()
                + " by " + state.machine() + " at " + state.timestamp());
        getLog().info(indent + "State SHA:     " + state.sha());
        getLog().info(indent + "State branch:  " + state.branch());

        // In sync?
        boolean shaMatch = state.sha().equals(localSha);
        boolean branchMatch = state.branch().equals(localBranch);

        if (shaMatch && branchMatch) {
            getLog().info(indent + "Status:        in sync  ✓");
            return;
        }

        // Not in sync — diagnose based on action
        if (!branchMatch) {
            diagnoseBranchMismatch(dir, indent, state, localBranch);
        } else {
            diagnoseShaMismatch(dir, indent, state, localSha);
        }
    }

    private void diagnoseBranchMismatch(File dir, String indent,
                                         VcsState state, String localBranch) {
        switch (state.action()) {
            case VcsState.ACTION_FEATURE_START:
                getLog().warn(indent + "Status:        feature branch '"
                        + state.branch() + "' started on " + state.machine()
                        + " at " + state.timestamp());
                getLog().warn(indent + "               You are on '"
                        + localBranch + "'.");
                getLog().warn(indent + "Action:        run 'mvnw ike:sync' to switch to the feature branch");
                break;
            case VcsState.ACTION_FEATURE_FINISH:
                getLog().warn(indent + "Status:        feature finished on "
                        + state.machine() + " at " + state.timestamp()
                        + ", merged to '" + state.branch() + "'");
                getLog().warn(indent + "               You are on '"
                        + localBranch + "'.");
                getLog().warn(indent + "Action:        run 'mvnw ike:sync' to return to '"
                        + state.branch() + "'");
                break;
            default:
                getLog().warn(indent + "Status:        branch mismatch — local '"
                        + localBranch + "', state file '" + state.branch() + "'");
                getLog().warn(indent + "Action:        run 'mvnw ike:sync' to reconcile");
                break;
        }
    }

    private void diagnoseShaMismatch(File dir, String indent,
                                      VcsState state, String localSha) {
        // Check if the state SHA exists on the remote
        Optional<String> remoteSha;
        try {
            remoteSha = VcsOperations.remoteSha(dir, "origin", state.branch());
        } catch (MojoExecutionException e) {
            remoteSha = Optional.empty();
        }

        boolean shaOnRemote = remoteSha.isPresent();

        switch (state.action()) {
            case VcsState.ACTION_COMMIT:
                if (shaOnRemote) {
                    getLog().warn(indent + "Status:        commit on "
                            + state.machine() + " at " + state.timestamp());
                    getLog().warn(indent + "Action:        run 'mvnw ike:sync'");
                } else {
                    getLog().warn(indent + "Status:        commit on "
                            + state.machine() + " at " + state.timestamp()
                            + ", but push did not complete");
                    getLog().warn(indent + "Action:        push from "
                            + state.machine() + " first, then 'mvnw ike:sync' here");
                    getLog().warn(indent + "               Or: IKE_VCS_OVERRIDE=1 to proceed independently");
                }
                break;
            case VcsState.ACTION_PUSH:
                getLog().warn(indent + "Status:        push from "
                        + state.machine() + " at " + state.timestamp());
                getLog().warn(indent + "               Local HEAD behind remote.");
                getLog().warn(indent + "Action:        run 'mvnw ike:sync'");
                break;
            case VcsState.ACTION_RELEASE:
                getLog().warn(indent + "Status:        release performed on "
                        + state.machine() + " at " + state.timestamp());
                getLog().warn(indent + "Action:        run 'mvnw ike:sync'");
                break;
            case VcsState.ACTION_CHECKPOINT:
                getLog().warn(indent + "Status:        checkpoint created on "
                        + state.machine() + " at " + state.timestamp());
                getLog().warn(indent + "Action:        run 'mvnw ike:sync'");
                break;
            default:
                getLog().warn(indent + "Status:        behind ("
                        + state.action() + " on " + state.machine() + ")");
                getLog().warn(indent + "Action:        run 'mvnw ike:sync'");
                break;
        }
    }

    // ── Environment checks ──────────────────────────────────────

    private void verifyEnvironment() {
        File dir = new File(System.getProperty("user.dir"));

        getLog().info("");

        // Standards
        File standards = new File(dir, ".claude/standards");
        if (standards.isDirectory()) {
            getLog().info("  Standards:   .claude/standards/ present  ✓");
        } else {
            getLog().info("  Standards:   .claude/standards/ absent");
        }

        // CLAUDE.md
        File claudeMd = new File(dir, "CLAUDE.md");
        if (claudeMd.exists()) {
            getLog().info("  CLAUDE.md:   present  ✓");
        } else {
            getLog().info("  CLAUDE.md:   absent");
        }

        // Syncthing
        checkSyncthingHealth();
    }

    private void checkSyncthingHealth() {
        int port = 8384;

        // Check for custom port in .ike/config
        File dir = new File(System.getProperty("user.dir"));
        Path config = dir.toPath().resolve(".ike/config");
        if (Files.exists(config)) {
            try {
                Properties props = new Properties();
                props.load(new java.io.StringReader(
                        Files.readString(config, StandardCharsets.UTF_8)));
                String portStr = props.getProperty("syncthing.port");
                if (portStr != null) {
                    port = Integer.parseInt(portStr.trim());
                }
            } catch (Exception e) {
                getLog().debug("Could not read .ike/config: " + e.getMessage());
            }
        }

        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2))
                    .build();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/rest/noauth/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                getLog().info("  Syncthing:   connected (port " + port + ")  ✓");
            } else {
                getLog().info("  Syncthing:   responded with status "
                        + response.statusCode());
            }
        } catch (Exception e) {
            getLog().info("  Syncthing:   not running (port " + port + ")");
        }
    }

    private String hostname() {
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isEmpty()) {
            try {
                host = java.net.InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                host = "unknown";
            }
        }
        int dot = host.indexOf('.');
        return dot > 0 ? host.substring(0, dot) : host;
    }
}
