package network.ike.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

/**
 * Install VCS bridge git hooks to {@code ~/.git-hooks/}.
 *
 * <p>Installs the pre-commit, post-commit, and pre-push hooks that
 * coordinate git state across Syncthing-paired machines. Only the
 * VCS bridge hooks are written — existing hooks (prepare-commit-msg,
 * commit-msg, post-checkout) are never touched.
 *
 * <p>After installation, verifies that {@code core.hooksPath} is
 * configured to point to {@code ~/.git-hooks/}.
 *
 * <p>Usage: {@code mvnw ike:setup}
 */
@Mojo(name = "setup", requiresProject = false, threadSafe = true)
public class SetupMojo extends AbstractMojo {

    /** VCS bridge hook filenames — only these are written. */
    private static final List<String> VCS_HOOKS =
            List.of("pre-commit", "post-commit", "pre-push");

    /**
     * Force overwrite of existing VCS bridge hooks without prompting.
     */
    @Parameter(property = "force", defaultValue = "false")
    boolean force;

    @Override
    public void execute() throws MojoExecutionException {
        getLog().info("");
        getLog().info("IKE VCS Bridge — Setup");
        getLog().info("══════════════════════════════════════════════════════════════");

        Path hooksDir = Path.of(System.getProperty("user.home"), ".git-hooks");

        // Create hooks directory if it doesn't exist
        try {
            Files.createDirectories(hooksDir);
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Failed to create hooks directory: " + hooksDir, e);
        }

        // Install each VCS bridge hook
        int installed = 0;
        for (String hookName : VCS_HOOKS) {
            Path target = hooksDir.resolve(hookName);

            if (Files.exists(target) && !force) {
                getLog().info("  " + hookName + ": already exists (use -Dforce=true to overwrite)");
                continue;
            }

            String content = readResource("/hooks/" + hookName);
            try {
                Files.writeString(target, content, StandardCharsets.UTF_8);
                setExecutable(target);
                getLog().info("  " + hookName + ": installed");
                installed++;
            } catch (IOException e) {
                throw new MojoExecutionException(
                        "Failed to write hook: " + target, e);
            }
        }

        getLog().info("");
        if (installed > 0) {
            getLog().info("  Installed " + installed + " hook(s) to " + hooksDir);
        } else {
            getLog().info("  All hooks already present.");
        }

        // Verify core.hooksPath
        getLog().info("");
        checkHooksPath(hooksDir);

        getLog().info("");
    }

    private String readResource(String path) throws MojoExecutionException {
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) {
                throw new MojoExecutionException(
                        "Hook resource not found on classpath: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoExecutionException(
                    "Failed to read hook resource: " + path, e);
        }
    }

    private void setExecutable(Path file) {
        try {
            Set<PosixFilePermission> perms = Files.getPosixFilePermissions(file);
            perms.add(PosixFilePermission.OWNER_EXECUTE);
            perms.add(PosixFilePermission.GROUP_EXECUTE);
            perms.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, perms);
        } catch (UnsupportedOperationException e) {
            // Windows — Git Bash handles execute permission via git config
            getLog().debug("POSIX permissions not supported (Windows); skipping chmod.");
        } catch (IOException e) {
            getLog().warn("Could not set execute permission on " + file
                    + ": " + e.getMessage());
        }
    }

    private void checkHooksPath(Path expectedDir) throws MojoExecutionException {
        try {
            Process proc = new ProcessBuilder(
                    "git", "config", "--global", "core.hooksPath")
                    .redirectErrorStream(false)
                    .start();
            String output;
            try (var reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(proc.getInputStream(),
                            StandardCharsets.UTF_8))) {
                output = reader.lines()
                        .collect(java.util.stream.Collectors.joining())
                        .trim();
            }
            int exit = proc.waitFor();

            if (exit != 0 || output.isEmpty()) {
                getLog().warn("  core.hooksPath is not set.");
                getLog().warn("  Run: git config --global core.hooksPath "
                        + expectedDir);
            } else {
                Path actual = Path.of(output).toAbsolutePath().normalize();
                Path expected = expectedDir.toAbsolutePath().normalize();
                if (actual.equals(expected)) {
                    getLog().info("  core.hooksPath: " + output + "  ✓");
                } else {
                    getLog().warn("  core.hooksPath is set to: " + output);
                    getLog().warn("  Expected: " + expectedDir);
                    getLog().warn("  Hooks may not activate. Update with:");
                    getLog().warn("    git config --global core.hooksPath "
                            + expectedDir);
                }
            }
        } catch (IOException | InterruptedException e) {
            getLog().warn("  Could not check core.hooksPath: " + e.getMessage());
        }
    }
}
