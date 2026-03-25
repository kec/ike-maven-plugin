package network.ike.workspace;

import java.util.List;

/**
 * A workspace component — one git repository in the workspace manifest.
 *
 * @param name         the component identifier (directory name and YAML key)
 * @param type         references a {@link ComponentType} name
 * @param description  human-readable purpose
 * @param repo         git clone URL
 * @param branch       the branch to track
 * @param version      Maven version string, or null if not versioned
 * @param groupId      Maven groupId
 * @param dependsOn    inter-repository dependencies
 * @param notes        free-text migration or status notes
 * @param mavenVersion Maven version for the wrapper (e.g., "4.0.0-rc-5"),
 *                     overrides {@link Defaults#mavenVersion()}. Null to inherit.
 */
public record Component(
        String name,
        String type,
        String description,
        String repo,
        String branch,
        String version,
        String groupId,
        List<Dependency> dependsOn,
        String notes,
        String mavenVersion
) {}
