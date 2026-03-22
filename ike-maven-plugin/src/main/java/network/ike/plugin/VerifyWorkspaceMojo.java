package network.ike.plugin;

import network.ike.workspace.WorkspaceGraph;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

import java.util.List;

/**
 * Verify workspace manifest consistency.
 *
 * <p>Checks that all dependency references resolve, no cycles exist,
 * all group members are valid, and all component types are defined.
 * Fails the build if any errors are found.
 *
 * <pre>{@code mvn ike:verify}</pre>
 */
@Mojo(name = "verify", requiresProject = false, threadSafe = true)
public class VerifyWorkspaceMojo extends AbstractWorkspaceMojo {

    @Override
    public void execute() throws MojoExecutionException {
        WorkspaceGraph graph = loadGraph();

        getLog().info("");
        getLog().info("IKE Workspace — Verify");
        getLog().info("══════════════════════════════════════════════════════════════");

        List<String> errors = graph.verify();

        int componentCount = graph.manifest().components().size();
        int typeCount = graph.manifest().componentTypes().size();
        int groupCount = graph.manifest().groups().size();

        getLog().info("  Components:      " + componentCount);
        getLog().info("  Component types: " + typeCount);
        getLog().info("  Groups:          " + groupCount);
        getLog().info("");

        if (errors.isEmpty()) {
            getLog().info("  ✓ Manifest is consistent — no errors found.");
        } else {
            getLog().error("  Errors found: " + errors.size());
            for (String error : errors) {
                getLog().error("    ✗ " + error);
            }
            getLog().info("");
            throw new MojoExecutionException(
                    "Workspace manifest has " + errors.size() + " error(s).");
        }

        getLog().info("");
    }
}
