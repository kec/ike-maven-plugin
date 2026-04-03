package network.ike.plugin.ws;

import network.ike.plugin.ws.vcs.VcsOperations;
import network.ike.plugin.ws.vcs.VcsState;
import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration tests for the VCS bridge workflow.
 *
 * <p>Simulates the multi-machine scenario: machine A commits (writing a
 * state file), then machine B needs to sync before it can commit.
 * Uses two local repos sharing a "remote" bare repo.
 */
class VcsBridgeIntegrationTest {

    @TempDir
    Path tempDir;

    private Path remoteDir;
    private Path machineADir;
    private Path machineBDir;
    private String originalUserDir;

    @BeforeEach
    void setUp() throws Exception {
        originalUserDir = System.getProperty("user.dir");

        // Create a bare "remote" repo
        remoteDir = tempDir.resolve("remote.git");
        Files.createDirectories(remoteDir);
        exec(remoteDir, "git", "init", "--bare", "-b", "main");

        // Clone to machine A
        machineADir = tempDir.resolve("machine-a");
        exec(tempDir, "git", "clone", remoteDir.toString(), machineADir.getFileName().toString());
        exec(machineADir, "git", "config", "user.email", "a@test.com");
        exec(machineADir, "git", "config", "user.name", "Machine A");
        Files.writeString(machineADir.resolve("file.txt"), "initial", StandardCharsets.UTF_8);
        Files.createDirectories(machineADir.resolve(".ike"));
        exec(machineADir, "git", "add", ".");
        exec(machineADir, "git", "commit", "-m", "Initial commit");
        exec(machineADir, "git", "push", "-u", "origin", "main");

        // Clone to machine B
        machineBDir = tempDir.resolve("machine-b");
        exec(tempDir, "git", "clone", remoteDir.toString(), machineBDir.getFileName().toString());
        exec(machineBDir, "git", "config", "user.email", "b@test.com");
        exec(machineBDir, "git", "config", "user.name", "Machine B");
    }

    @AfterEach
    void tearDown() {
        System.setProperty("user.dir", originalUserDir);
    }

    @Test
    void stateFile_writtenAfterCommit() throws Exception {
        VcsOperations.writeVcsState(machineADir.toFile(), VcsState.ACTION_COMMIT);

        Optional<VcsState> state = VcsState.readFrom(machineADir);
        assertThat(state).isPresent();
        assertThat(state.get().action()).isEqualTo("commit");
        assertThat(state.get().branch()).isEqualTo("main");
        assertThat(state.get().sha()).hasSize(8);
        assertThat(state.get().machine()).isNotEmpty();
        assertThat(state.get().timestamp()).contains("T");
    }

    @Test
    void needsSync_detectsStaleMachine() throws Exception {
        // Machine A commits and pushes
        Files.writeString(machineADir.resolve("file.txt"), "updated by A", StandardCharsets.UTF_8);
        exec(machineADir, "git", "add", ".");
        exec(machineADir, "git", "commit", "-m", "Update from A");
        exec(machineADir, "git", "push", "origin", "main");
        VcsOperations.writeVcsState(machineADir.toFile(), VcsState.ACTION_COMMIT);

        // Simulate Syncthing delivering state file to machine B
        Files.createDirectories(machineBDir.resolve(".ike"));
        Files.copy(machineADir.resolve(".ike/vcs-state"),
                machineBDir.resolve(".ike/vcs-state"));

        // Machine B should detect it needs sync
        assertThat(VcsOperations.needsSync(machineBDir.toFile())).isTrue();
    }

    @Test
    void sync_reconcilesAfterRemoteCommit() throws Exception {
        // Machine A commits and pushes
        Files.writeString(machineADir.resolve("file.txt"), "updated by A", StandardCharsets.UTF_8);
        exec(machineADir, "git", "add", ".");
        exec(machineADir, "git", "commit", "-m", "Update from A");
        exec(machineADir, "git", "push", "origin", "main");
        VcsOperations.writeVcsState(machineADir.toFile(), VcsState.ACTION_COMMIT);

        String expectedSha = VcsOperations.headSha(machineADir.toFile());

        // Simulate Syncthing delivering state file to machine B
        Files.createDirectories(machineBDir.resolve(".ike"));
        Files.copy(machineADir.resolve(".ike/vcs-state"),
                machineBDir.resolve(".ike/vcs-state"));

        // Machine B syncs
        org.apache.maven.plugin.logging.Log log = new org.apache.maven.plugin.logging.SystemStreamLog();
        VcsOperations.sync(machineBDir.toFile(), log);

        // Machine B HEAD should now match
        assertThat(VcsOperations.headSha(machineBDir.toFile())).isEqualTo(expectedSha);
        assertThat(VcsOperations.needsSync(machineBDir.toFile())).isFalse();
    }

    @Test
    void syncMojo_bareModeSync() throws Exception {
        // Machine A commits and pushes
        Files.writeString(machineADir.resolve("file.txt"), "A update", StandardCharsets.UTF_8);
        exec(machineADir, "git", "add", ".");
        exec(machineADir, "git", "commit", "-m", "A update");
        exec(machineADir, "git", "push", "origin", "main");
        VcsOperations.writeVcsState(machineADir.toFile(), VcsState.ACTION_COMMIT);

        String expectedSha = VcsOperations.headSha(machineADir.toFile());

        // Deliver state file
        Files.createDirectories(machineBDir.resolve(".ike"));
        Files.copy(machineADir.resolve(".ike/vcs-state"),
                machineBDir.resolve(".ike/vcs-state"));

        // Run SyncMojo on machine B
        System.setProperty("user.dir", machineBDir.toAbsolutePath().toString());
        SyncMojo mojo = new SyncMojo();
        mojo.execute();

        assertThat(VcsOperations.headSha(machineBDir.toFile())).isEqualTo(expectedSha);
    }

    @Test
    void verifyMojo_reportsInSync() throws Exception {
        // Both machines on same commit
        Files.createDirectories(machineBDir.resolve(".ike"));
        VcsOperations.writeVcsState(machineBDir.toFile(), VcsState.ACTION_COMMIT);

        System.setProperty("user.dir", machineBDir.toAbsolutePath().toString());
        VerifyWorkspaceMojo mojo = new VerifyWorkspaceMojo();
        // Should not throw — just reports state
        mojo.execute();
    }

    @Test
    void verifyMojo_reportsBehind() throws Exception {
        // Machine A advances, state file delivered to B
        Files.writeString(machineADir.resolve("file.txt"), "ahead", StandardCharsets.UTF_8);
        exec(machineADir, "git", "add", ".");
        exec(machineADir, "git", "commit", "-m", "Ahead");
        exec(machineADir, "git", "push", "origin", "main");
        VcsOperations.writeVcsState(machineADir.toFile(), VcsState.ACTION_COMMIT);

        Files.createDirectories(machineBDir.resolve(".ike"));
        Files.copy(machineADir.resolve(".ike/vcs-state"),
                machineBDir.resolve(".ike/vcs-state"));

        System.setProperty("user.dir", machineBDir.toAbsolutePath().toString());
        VerifyWorkspaceMojo mojo = new VerifyWorkspaceMojo();
        // Should not throw — reports "behind" state
        mojo.execute();
    }

    @Test
    void sync_handlesBranchSwitch() throws Exception {
        // Machine A creates a feature branch
        exec(machineADir, "git", "checkout", "-b", "feature/test");
        Files.writeString(machineADir.resolve("feature.txt"), "feature work", StandardCharsets.UTF_8);
        exec(machineADir, "git", "add", ".");
        exec(machineADir, "git", "commit", "-m", "Feature work");
        exec(machineADir, "git", "push", "-u", "origin", "feature/test");
        VcsOperations.writeVcsState(machineADir.toFile(), VcsState.ACTION_FEATURE_START);

        // Deliver state file to machine B (still on main)
        Files.createDirectories(machineBDir.resolve(".ike"));
        Files.copy(machineADir.resolve(".ike/vcs-state"),
                machineBDir.resolve(".ike/vcs-state"));

        assertThat(VcsOperations.currentBranch(machineBDir.toFile())).isEqualTo("main");

        // Sync should switch B to the feature branch
        org.apache.maven.plugin.logging.Log log = new org.apache.maven.plugin.logging.SystemStreamLog();
        VcsOperations.sync(machineBDir.toFile(), log);

        assertThat(VcsOperations.currentBranch(machineBDir.toFile())).isEqualTo("feature/test");
    }

    @Test
    void commitMojo_setsIkeVcsContext() throws Exception {
        // Create .ike directory
        Files.createDirectories(machineADir.resolve(".ike"));
        VcsOperations.writeVcsState(machineADir.toFile(), VcsState.ACTION_COMMIT);

        // Make a change
        Files.writeString(machineADir.resolve("new.txt"), "new file", StandardCharsets.UTF_8);
        exec(machineADir, "git", "add", ".");

        System.setProperty("user.dir", machineADir.toAbsolutePath().toString());
        CommitMojo mojo = new CommitMojo();
        mojo.message = "Test commit via plugin";

        mojo.execute();

        // Verify commit happened
        String log = execCapture(machineADir, "git", "log", "--oneline", "-1");
        assertThat(log).contains("Test commit via plugin");

        // State file updated
        Optional<VcsState> state = VcsState.readFrom(machineADir);
        assertThat(state).isPresent();
        assertThat(state.get().action()).isEqualTo("commit");
    }

    @Test
    void isIkeManaged_correctDetection() throws Exception {
        assertThat(VcsState.isIkeManaged(machineADir)).isTrue();  // .ike/ created in setUp
        assertThat(VcsState.isIkeManaged(tempDir.resolve("nonexistent"))).isFalse();
    }

    @Test
    void stateFile_actionConstants() {
        assertThat(VcsState.ACTION_COMMIT).isEqualTo("commit");
        assertThat(VcsState.ACTION_PUSH).isEqualTo("push");
        assertThat(VcsState.ACTION_FEATURE_START).isEqualTo("feature-start");
        assertThat(VcsState.ACTION_FEATURE_FINISH).isEqualTo("feature-finish");
        assertThat(VcsState.ACTION_RELEASE).isEqualTo("release");
        assertThat(VcsState.ACTION_CHECKPOINT).isEqualTo("checkpoint");
    }

    // ── Helpers ──────────────────────────────────────────────────

    private void exec(Path workDir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(
                    "Command failed (exit " + exitCode + "): "
                            + String.join(" ", command));
        }
    }

    private String execCapture(Path workDir, String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).trim();
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException(
                    "Command failed (exit " + exitCode + "): "
                            + String.join(" ", command));
        }
        return output;
    }
}
