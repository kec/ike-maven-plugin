package network.ike.plugin;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for git remote operations against a real SSH
 * server in a Docker container. Tests push, clone, branch, and tag
 * operations that the release and gitflow goals depend on.
 *
 * <p>Requires Docker. Excluded from default build; run with
 * {@code mvn verify -Dsurefire.excludedGroups= -Dsurefire.groups=container}.
 */
class GitRemoteIntegrationTest extends ContainerTestSupport {

    private static final String SSH_USER = "testuser";
    private static final String BARE_REPO = "/tmp/remote-repo.git";

    private static Path privateKeyFile;
    private static GenericContainer<?> sshContainer;
    private static int sshPort;

    @BeforeAll
    static void startContainer() throws Exception {
        // Generate ephemeral keypair
        Path keyDir = Files.createTempDirectory("git-test-keys");
        privateKeyFile = keyDir.resolve("id_rsa");
        Path publicKeyFile = keyDir.resolve("id_rsa.pub");

        Process keygen = new ProcessBuilder(
                "ssh-keygen", "-t", "rsa", "-b", "2048",
                "-f", privateKeyFile.toString(), "-N", "", "-q")
                .redirectErrorStream(true).start();
        keygen.getInputStream().readAllBytes();
        assertThat(keygen.waitFor()).isZero();
        Files.setPosixFilePermissions(privateKeyFile,
                PosixFilePermissions.fromString("rw-------"));

        sshContainer = new GenericContainer<>(
                DockerImageName.parse("lscr.io/linuxserver/openssh-server:latest"))
                .withExposedPorts(2222)
                .withEnv("PUID", "1000")
                .withEnv("PGID", "1000")
                .withEnv("USER_NAME", SSH_USER)
                .withEnv("PUBLIC_KEY", Files.readString(publicKeyFile).trim())
                .withEnv("SUDO_ACCESS", "true")
                .withEnv("PASSWORD_ACCESS", "false")
                .withEnv("DOCKER_MODS", "linuxserver/mods:openssh-server-git");

        sshContainer.start();
        sshPort = sshContainer.getMappedPort(2222);

        // Create bare repo on the remote
        sshExecStatic("git init --bare " + BARE_REPO);
    }

    @AfterAll
    static void stopContainer() {
        if (sshContainer != null) sshContainer.stop();
    }

    @Test
    void canCloneFromRemote(@TempDir Path workDir) throws Exception {
        // Push an initial commit first
        File local = pushInitialCommit(workDir, "clone-seed");

        // Clone into a new directory
        File cloneDir = workDir.resolve("cloned").toFile();
        gitLocal(workDir.toFile(),
                "git", "clone", sshUrl(), cloneDir.getAbsolutePath());

        assertThat(new File(cloneDir, "README.md")).exists();
        assertThat(new File(cloneDir, ".git")).isDirectory();
    }

    @Test
    void canPushAndVerifyTag(@TempDir Path workDir) throws Exception {
        File local = pushInitialCommit(workDir, "tag-test");

        // Create and push a tag
        gitLocal(local, "git", "tag", "-a", "v1.0", "-m", "Release 1.0");
        gitLocal(local, "git", "push", "origin", "v1.0");

        // Verify tag on remote
        String tags = sshExecCapture("cd " + BARE_REPO + " && git tag");
        assertThat(tags.trim()).contains("v1.0");
    }

    @Test
    void canCreateAndPushFeatureBranch(@TempDir Path workDir) throws Exception {
        File local = pushInitialCommit(workDir, "feature-test");

        // Create feature branch
        gitLocal(local, "git", "checkout", "-b", "feature/test-branch");
        Files.writeString(local.toPath().resolve("feature.txt"), "feature work");
        gitLocal(local, "git", "add", "feature.txt");
        gitLocal(local, "git", "commit", "-m", "feature commit");
        gitLocal(local, "git", "push", "-u", "origin", "feature/test-branch");

        // Verify branch exists on remote
        String branches = sshExecCapture(
                "cd " + BARE_REPO + " && git branch");
        assertThat(branches).contains("feature/test-branch");
    }

    @Test
    void canMergeFeatureBranchWithNoFf(@TempDir Path workDir) throws Exception {
        File local = pushInitialCommit(workDir, "merge-test");

        // Create and push feature branch
        gitLocal(local, "git", "checkout", "-b", "feature/merge-me");
        Files.writeString(local.toPath().resolve("feature.txt"), "content");
        gitLocal(local, "git", "add", "feature.txt");
        gitLocal(local, "git", "commit", "-m", "feature work");
        gitLocal(local, "git", "push", "-u", "origin", "feature/merge-me");

        // Merge with --no-ff (like ike:feature-finish does)
        gitLocal(local, "git", "checkout", "main");
        gitLocal(local, "git", "merge", "--no-ff", "feature/merge-me",
                "-m", "merge: feature/merge-me");
        gitLocal(local, "git", "push", "origin", "main");

        // Verify merge commit on remote (use refs/heads/main for bare repo)
        String log = sshExecCapture(
                "cd " + BARE_REPO + " && git log --oneline refs/heads/main -3");
        assertThat(log).contains("merge: feature/merge-me");
    }

    @Test
    void canDeleteRemoteBranch(@TempDir Path workDir) throws Exception {
        File local = pushInitialCommit(workDir, "delete-branch-test");

        // Create, push, then delete
        gitLocal(local, "git", "checkout", "-b", "feature/to-delete");
        Files.writeString(local.toPath().resolve("temp.txt"), "temp");
        gitLocal(local, "git", "add", "temp.txt");
        gitLocal(local, "git", "commit", "-m", "temp commit");
        gitLocal(local, "git", "push", "-u", "origin", "feature/to-delete");
        gitLocal(local, "git", "checkout", "main");
        gitLocal(local, "git", "push", "origin", "--delete", "feature/to-delete");

        // Verify branch gone on remote
        String branches = sshExecCapture(
                "cd " + BARE_REPO + " && git branch");
        assertThat(branches).doesNotContain("feature/to-delete");
    }

    // ── PullWorkspaceMojo tests ────────────────────────────────────

    @Test
    void pullWorkspaceMojo_pullsNewCommits(@TempDir Path workDir)
            throws Exception {
        // Setup: create bare repo, clone it as a "component", push a
        // new commit from another clone, then run PullWorkspaceMojo
        // and verify the component has the new commit.

        // Reset bare repo
        sshExecStatic("rm -rf " + BARE_REPO + " && git init --bare " + BARE_REPO);

        // Create the "workspace" directory structure
        File wsRoot = workDir.resolve("workspace").toFile();
        wsRoot.mkdirs();

        // Clone into a component directory (simulates an initialized component)
        String componentName = "my-component";
        File componentDir = new File(wsRoot, componentName);

        // First, push an initial commit so we have something to clone
        File seedDir = workDir.resolve("seed").toFile();
        seedDir.mkdirs();
        gitLocal(seedDir, "git", "init", "-b", "main");
        gitLocal(seedDir, "git", "config", "user.email", "test@test.com");
        gitLocal(seedDir, "git", "config", "user.name", "Test");
        Files.writeString(seedDir.toPath().resolve("README.md"), "# initial");
        gitLocal(seedDir, "git", "add", "README.md");
        gitLocal(seedDir, "git", "commit", "-m", "initial commit");
        gitLocal(seedDir, "git", "remote", "add", "origin", sshUrl());
        gitLocal(seedDir, "git", "push", "-u", "origin", "main");

        // Clone into the workspace component directory
        gitLocal(wsRoot, "git", "clone", sshUrl(), componentName);

        // Configure SSH for the cloned repo
        String sshCmd = "ssh -i " + privateKeyFile
                + " -o StrictHostKeyChecking=no"
                + " -o UserKnownHostsFile=/dev/null"
                + " -o LogLevel=ERROR"
                + " -p " + sshPort;
        gitLocal(componentDir, "git", "config", "core.sshCommand", sshCmd);

        // Push a new commit from the seed directory (simulates another dev)
        Files.writeString(seedDir.toPath().resolve("NEW-FILE.md"), "new content");
        gitLocal(seedDir, "git", "add", "NEW-FILE.md");
        gitLocal(seedDir, "git", "commit", "-m", "second commit");
        gitLocal(seedDir, "git", "push", "origin", "main");

        // Verify the component doesn't have the new file yet
        assertThat(new File(componentDir, "NEW-FILE.md")).doesNotExist();

        // Create a minimal workspace.yaml
        String manifestYaml = """
                schema-version: "1.0"
                components:
                  %s:
                    type: software
                    description: test component
                    repo: %s
                    branch: main
                """.formatted(componentName, sshUrl());
        Files.writeString(wsRoot.toPath().resolve("workspace.yaml"), manifestYaml);

        // Create and configure the PullWorkspaceMojo
        PullWorkspaceMojo mojo = new PullWorkspaceMojo();
        setField(mojo, "manifest",
                new File(wsRoot, "workspace.yaml"));

        // Execute the Mojo
        mojo.execute();

        // Verify the component now has the new file
        assertThat(new File(componentDir, "NEW-FILE.md")).exists();
        assertThat(Files.readString(componentDir.toPath().resolve("NEW-FILE.md")))
                .isEqualTo("new content");
    }

    @Test
    void pullWorkspaceMojo_skipsUninitializedComponents(@TempDir Path workDir)
            throws Exception {
        // Workspace with a component that hasn't been cloned yet
        File wsRoot = workDir.resolve("workspace").toFile();
        wsRoot.mkdirs();

        // Create workspace.yaml referencing a component that doesn't exist on disk
        String manifestYaml = """
                schema-version: "1.0"
                components:
                  missing-component:
                    type: software
                    description: not yet cloned
                    repo: git@github.com:fake/repo.git
                    branch: main
                """;
        Files.writeString(wsRoot.toPath().resolve("workspace.yaml"), manifestYaml);

        PullWorkspaceMojo mojo = new PullWorkspaceMojo();
        setField(mojo, "manifest",
                new File(wsRoot, "workspace.yaml"));

        // Should complete without error — just skip the uncloned component
        mojo.execute();

        // The directory should not have been created
        assertThat(new File(wsRoot, "missing-component/.git")).doesNotExist();
    }

    @Test
    void pullWorkspaceMojo_pullsOnlyNamedGroup(@TempDir Path workDir)
            throws Exception {
        // Reset bare repo
        sshExecStatic("rm -rf " + BARE_REPO + " && git init --bare " + BARE_REPO);

        File wsRoot = workDir.resolve("workspace").toFile();
        wsRoot.mkdirs();

        // Push initial commit
        File seedDir = workDir.resolve("seed").toFile();
        seedDir.mkdirs();
        gitLocal(seedDir, "git", "init", "-b", "main");
        gitLocal(seedDir, "git", "config", "user.email", "test@test.com");
        gitLocal(seedDir, "git", "config", "user.name", "Test");
        Files.writeString(seedDir.toPath().resolve("README.md"), "# initial");
        gitLocal(seedDir, "git", "add", "README.md");
        gitLocal(seedDir, "git", "commit", "-m", "initial commit");
        gitLocal(seedDir, "git", "remote", "add", "origin", sshUrl());
        gitLocal(seedDir, "git", "push", "-u", "origin", "main");

        // Clone as component-a
        gitLocal(wsRoot, "git", "clone", sshUrl(), "component-a");
        File compA = new File(wsRoot, "component-a");
        String sshCmd = "ssh -i " + privateKeyFile
                + " -o StrictHostKeyChecking=no"
                + " -o UserKnownHostsFile=/dev/null"
                + " -o LogLevel=ERROR"
                + " -p " + sshPort;
        gitLocal(compA, "git", "config", "core.sshCommand", sshCmd);

        // Push a new commit
        Files.writeString(seedDir.toPath().resolve("GROUPED.md"), "grouped");
        gitLocal(seedDir, "git", "add", "GROUPED.md");
        gitLocal(seedDir, "git", "commit", "-m", "grouped commit");
        gitLocal(seedDir, "git", "push", "origin", "main");

        // Create workspace.yaml with a group
        String manifestYaml = """
                schema-version: "1.0"
                components:
                  component-a:
                    type: software
                    description: in group
                    repo: %s
                    branch: main
                  component-b:
                    type: software
                    description: not in group
                    repo: git@github.com:fake/repo.git
                    branch: main
                groups:
                  my-group:
                    - component-a
                """.formatted(sshUrl());
        Files.writeString(wsRoot.toPath().resolve("workspace.yaml"), manifestYaml);

        PullWorkspaceMojo mojo = new PullWorkspaceMojo();
        setField(mojo, "manifest",
                new File(wsRoot, "workspace.yaml"));
        setField(mojo, "group", "my-group");

        mojo.execute();

        // component-a should have the new file
        assertThat(new File(compA, "GROUPED.md")).exists();
        // component-b should still not exist (never cloned, not in group)
        assertThat(new File(wsRoot, "component-b/.git")).doesNotExist();
    }

    // ── Test helper: set Mojo field via reflection ──────────────────

    private static void setField(Object obj, String fieldName, Object value)
            throws Exception {
        Class<?> cls = obj.getClass();
        while (cls != null) {
            try {
                java.lang.reflect.Field field = cls.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(obj, value);
                return;
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName + " not found on " + obj.getClass());
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /**
     * Push an initial commit to the bare repo and return the local dir.
     * Reinitializes the bare repo each time to avoid conflicts between tests.
     */
    private File pushInitialCommit(Path workDir, String name) throws Exception {
        // Reset bare repo
        sshExecStatic("rm -rf " + BARE_REPO + " && git init --bare " + BARE_REPO);

        File local = workDir.resolve(name).toFile();
        local.mkdirs();

        gitLocal(local, "git", "init", "-b", "main");
        gitLocal(local, "git", "config", "user.email", "test@test.com");
        gitLocal(local, "git", "config", "user.name", "Test");

        Files.writeString(local.toPath().resolve("README.md"), "# " + name);
        gitLocal(local, "git", "add", "README.md");
        gitLocal(local, "git", "commit", "-m", "initial commit");

        // Configure SSH for git push (no host key checking)
        String sshCmd = "ssh -i " + privateKeyFile
                + " -o StrictHostKeyChecking=no"
                + " -o UserKnownHostsFile=/dev/null"
                + " -o LogLevel=ERROR"
                + " -p " + sshPort;
        gitLocal(local, "git", "config", "core.sshCommand", sshCmd);
        gitLocal(local, "git", "remote", "add", "origin", sshUrl());
        gitLocal(local, "git", "push", "-u", "origin", "main");

        return local;
    }

    private String sshUrl() {
        return "ssh://" + SSH_USER + "@localhost:" + sshPort + BARE_REPO;
    }

    private void gitLocal(File dir, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(dir)
                .redirectErrorStream(true);
        // Pass SSH command config through environment
        pb.environment().put("GIT_SSH_COMMAND",
                "ssh -i " + privateKeyFile
                        + " -o StrictHostKeyChecking=no"
                        + " -o UserKnownHostsFile=/dev/null"
                        + " -o LogLevel=ERROR"
                        + " -p " + sshPort);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        int exit = proc.waitFor();
        assertThat(exit)
                .as("Git command failed: %s\nOutput: %s",
                        String.join(" ", cmd), output)
                .isZero();
    }

    // ── SSH helpers ──────────────────────────────────────────────────

    private static void sshExecStatic(String command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "ssh", "-i", privateKeyFile.toString(),
                "-o", "StrictHostKeyChecking=no",
                "-o", "UserKnownHostsFile=/dev/null",
                "-o", "LogLevel=ERROR",
                "-p", String.valueOf(sshPort),
                SSH_USER + "@localhost", command)
                .redirectErrorStream(true);
        Process proc = pb.start();
        proc.getInputStream().readAllBytes();
        assertThat(proc.waitFor()).as("SSH failed: " + command).isZero();
    }

    private String sshExecCapture(String command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "ssh", "-i", privateKeyFile.toString(),
                "-o", "StrictHostKeyChecking=no",
                "-o", "UserKnownHostsFile=/dev/null",
                "-o", "LogLevel=ERROR",
                "-p", String.valueOf(sshPort),
                SSH_USER + "@localhost", command)
                .redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        assertThat(proc.waitFor()).as("SSH failed: " + command).isZero();
        return output;
    }
}
