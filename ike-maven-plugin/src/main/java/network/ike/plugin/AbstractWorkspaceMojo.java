package network.ike.plugin;

import network.ike.workspace.Manifest;
import network.ike.workspace.ManifestException;
import network.ike.workspace.ManifestReader;
import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.nio.file.Path;

/**
 * Base class for workspace goals that read {@code workspace.yaml}.
 *
 * <p>Resolves the manifest by searching upward from the invocation
 * directory for a file named {@code workspace.yaml}. All workspace
 * goals inherit this resolution logic.
 */
abstract class AbstractWorkspaceMojo extends AbstractMojo {

    /**
     * Path to workspace.yaml. If not set, searches upward from the
     * current directory. Package-private for test access.
     */
    @Parameter(property = "workspace.manifest")
    File manifest;

    /**
     * Load the manifest and build the workspace graph.
     */
    protected WorkspaceGraph loadGraph() throws MojoExecutionException {
        Path manifestPath = resolveManifest();
        getLog().debug("Reading manifest: " + manifestPath);
        try {
            Manifest m = ManifestReader.read(manifestPath);
            return new WorkspaceGraph(m);
        } catch (ManifestException e) {
            throw new MojoExecutionException(
                    "Failed to read workspace manifest: " + e.getMessage(), e);
        }
    }

    /**
     * Resolve the manifest path — explicit parameter, or search upward.
     */
    protected Path resolveManifest() throws MojoExecutionException {
        if (manifest != null && manifest.exists()) {
            return manifest.toPath();
        }

        // Search upward from current directory
        Path dir = Path.of(System.getProperty("user.dir"));
        while (dir != null) {
            Path candidate = dir.resolve("workspace.yaml");
            if (candidate.toFile().exists()) {
                return candidate;
            }
            dir = dir.getParent();
        }

        throw new MojoExecutionException(
                "Cannot find workspace.yaml. Specify -Dworkspace.manifest=<path> " +
                        "or run from within a workspace directory.");
    }

    /**
     * Resolve the workspace root directory (parent of workspace.yaml).
     */
    protected File workspaceRoot() throws MojoExecutionException {
        return resolveManifest().getParent().toFile();
    }

    /**
     * Run {@code git status --porcelain} on a component directory and
     * return the output (empty string = clean).
     */
    protected String gitStatus(File componentDir) {
        try {
            return ReleaseSupport.execCapture(componentDir,
                    "git", "status", "--porcelain");
        } catch (Exception e) {
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Get the current branch of a component directory.
     */
    protected String gitBranch(File componentDir) {
        try {
            return ReleaseSupport.execCapture(componentDir,
                    "git", "rev-parse", "--abbrev-ref", "HEAD");
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Get the short SHA of HEAD for a component directory.
     */
    protected String gitShortSha(File componentDir) {
        try {
            return ReleaseSupport.execCapture(componentDir,
                    "git", "rev-parse", "--short", "HEAD");
        } catch (Exception e) {
            return "???????";
        }
    }

    /**
     * Prompt the user interactively for a required parameter when it
     * was not supplied on the command line.
     *
     * <p>Uses {@link System#console()} so that IntelliJ run configs
     * and terminal sessions can provide values without requiring
     * placeholder {@code -D} properties. Falls back to a clear error
     * message when running non-interactively (e.g., piped input).
     *
     * @param currentValue the value from the {@code @Parameter} field (may be null)
     * @param propertyName the {@code -D} property name (for the error message)
     * @param promptLabel  human-readable label shown in the prompt
     * @return the resolved value — either the original or user-supplied
     * @throws MojoExecutionException if no value can be obtained
     */
    protected String requireParam(String currentValue, String propertyName,
                                  String promptLabel)
            throws MojoExecutionException {
        if (currentValue != null && !currentValue.isBlank()) {
            return currentValue.trim();
        }

        java.io.Console console = System.console();
        if (console != null) {
            String input = console.readLine("%s: ", promptLabel);
            if (input != null && !input.isBlank()) {
                return input.trim();
            }
        }

        throw new MojoExecutionException(
                propertyName + " is required. Specify -D" + propertyName
                        + "=<value> or run interactively.");
    }
}
