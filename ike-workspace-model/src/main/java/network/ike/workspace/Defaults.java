package network.ike.workspace;

/**
 * Default values applied to components that omit a field.
 *
 * @param branch       the default git branch (typically "main")
 * @param mavenVersion the default Maven version for the Maven wrapper
 *                     (e.g., "4.0.0-rc-5"). Null means no wrapper is installed.
 */
public record Defaults(
        String branch,
        String mavenVersion
) {}
