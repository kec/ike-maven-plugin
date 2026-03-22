package network.ike.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

/**
 * Deprecated placeholder — use the specific workspace goals instead.
 *
 * @deprecated Use {@code ike:dashboard}, {@code ike:init}, {@code ike:status},
 *             {@code ike:pull}, {@code ike:verify}, {@code ike:cascade},
 *             {@code ike:graph}, or {@code ike:stignore}.
 */
@Deprecated
@Mojo(name = "workspace", requiresProject = false, threadSafe = true)
public class WorkspaceMojo extends AbstractMojo {

    @Override
    public void execute() throws MojoExecutionException {
        getLog().warn("ike:workspace is deprecated. Use the specific workspace goals:");
        getLog().info("  ike:dashboard   — Composite overview (verify + status + cascade)");
        getLog().info("  ike:init        — Clone/initialize repos");
        getLog().info("  ike:pull        — Git pull across repos");
        getLog().info("  ike:status      — Git status across repos");
        getLog().info("  ike:verify      — Check manifest consistency");
        getLog().info("  ike:cascade     — Downstream impact analysis");
        getLog().info("  ike:graph       — Dependency graph");
        getLog().info("  ike:stignore    — Generate Syncthing .stignore files");
        getLog().info("");
        getLog().info("Run 'mvn ike:help' for full options.");
    }
}
