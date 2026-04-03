package network.ike.plugin.ws;

/**
 * Immutable snapshot of a single workspace component at checkpoint time.
 *
 * <p>Decouples checkpoint YAML generation from git subprocess calls
 * so the formatting logic is testable with plain records.
 *
 * @param name    component directory name
 * @param sha     full commit SHA (or "unknown" if unavailable)
 * @param shortSha abbreviated commit SHA
 * @param branch  current branch name
 * @param version POM version (may be null)
 * @param dirty   true if working tree has uncommitted changes
 * @param type    component type from workspace manifest
 * @param compositeCheckpoint true if checkpoint mechanism is "composite"
 */
public record ComponentSnapshot(String name, String sha, String shortSha,
                                 String branch, String version, boolean dirty,
                                 String type, boolean compositeCheckpoint) {}
