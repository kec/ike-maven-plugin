package network.ike.plugin;

/**
 * A single dependency entry in a Bill of Materials POM.
 *
 * <p>Decouples BOM XML generation from Maven's {@code Dependency} model
 * so the generation logic is testable with plain records.
 *
 * @param groupId    Maven group ID
 * @param artifactId Maven artifact ID
 * @param version    resolved version string
 * @param classifier optional classifier (e.g., "claude"); null if absent
 * @param type       packaging type (e.g., "jar", "zip"); null defaults to jar
 * @param scope      dependency scope (e.g., "test", "provided"); null defaults to compile
 */
public record BomEntry(String groupId, String artifactId, String version,
                        String classifier, String type, String scope) {}
