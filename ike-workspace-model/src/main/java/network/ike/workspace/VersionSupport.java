package network.ike.workspace;

/**
 * Version manipulation for IKE workspace conventions.
 *
 * <p>Handles SNAPSHOT suffixes, branch-qualified versions, checkpoint
 * versions, and release version derivation. Extracted from
 * {@code ReleaseSupport} in ike-maven-plugin so these operations are
 * testable without Maven dependencies.
 */
public final class VersionSupport {

    private VersionSupport() {}

    /**
     * Strip {@code -SNAPSHOT} from a version string.
     *
     * @param version e.g. "1.1.0-SNAPSHOT" or "1.1.0-my-feature-SNAPSHOT"
     * @return e.g. "1.1.0" or "1.1.0-my-feature"
     */
    public static String stripSnapshot(String version) {
        return version.endsWith("-SNAPSHOT")
                ? version.substring(0, version.length() - "-SNAPSHOT".length())
                : version;
    }

    /**
     * Derive the release version from a SNAPSHOT version.
     * Equivalent to {@link #stripSnapshot(String)}.
     *
     * @param snapshotVersion version to strip
     * @return release version without -SNAPSHOT suffix
     */
    public static String deriveReleaseVersion(String snapshotVersion) {
        return stripSnapshot(snapshotVersion);
    }

    /**
     * Derive the next SNAPSHOT version by incrementing the last numeric
     * segment. {@code "2"} becomes {@code "3-SNAPSHOT"};
     * {@code "1.1.0"} becomes {@code "1.1.1-SNAPSHOT"}.
     *
     * @param releaseVersion a version without -SNAPSHOT
     * @return the next SNAPSHOT version
     */
    public static String deriveNextSnapshot(String releaseVersion) {
        String base = stripSnapshot(releaseVersion);
        int lastDot = base.lastIndexOf('.');
        if (lastDot >= 0) {
            String prefix = base.substring(0, lastDot + 1);
            String last = base.substring(lastDot + 1);
            return prefix + (Integer.parseInt(last) + 1) + "-SNAPSHOT";
        }
        // Simple integer version (e.g., "2" -> "3-SNAPSHOT")
        return (Integer.parseInt(base) + 1) + "-SNAPSHOT";
    }

    /**
     * Transform a branch name into a safe directory/version qualifier.
     * Replaces {@code /} with {@code -}.
     *
     * @param branch e.g. "feature/shield-terminology"
     * @return e.g. "feature-shield-terminology"
     */
    public static String safeBranchName(String branch) {
        return branch.replace('/', '-');
    }

    /**
     * Derive a branch-qualified SNAPSHOT version.
     *
     * <p>Given base version {@code "1.2.0-SNAPSHOT"} and branch
     * {@code "feature/my-work"}, returns {@code "1.2.0-feature-my-work-SNAPSHOT"}.
     *
     * <p>If the branch is "main", returns the base version unchanged.
     *
     * @param baseVersion the unqualified version (with or without -SNAPSHOT)
     * @param branch      the git branch name
     * @return the branch-qualified SNAPSHOT version
     */
    public static String branchQualifiedVersion(String baseVersion, String branch) {
        if ("main".equals(branch)) {
            return baseVersion.endsWith("-SNAPSHOT")
                    ? baseVersion
                    : baseVersion + "-SNAPSHOT";
        }
        String base = stripSnapshot(baseVersion);
        // Strip any existing branch qualifier: take only the numeric prefix
        String numericBase = extractNumericBase(base);
        return numericBase + "-" + safeBranchName(branch) + "-SNAPSHOT";
    }

    /**
     * Extract the numeric base version, stripping any branch qualifier.
     * {@code "1.2.0-my-feature"} becomes {@code "1.2.0"};
     * {@code "1.2.0"} is unchanged.
     *
     * @param version version string possibly containing a branch qualifier
     * @return numeric portion only
     */
    public static String extractNumericBase(String version) {
        // Find the first '-' that follows a digit and precedes a letter
        for (int i = 1; i < version.length(); i++) {
            if (version.charAt(i) == '-' && Character.isDigit(version.charAt(i - 1))
                    && i + 1 < version.length()
                    && Character.isLetter(version.charAt(i + 1))) {
                return version.substring(0, i);
            }
        }
        return version;
    }

    /**
     * Check whether a version string is a SNAPSHOT.
     *
     * @param version version to check
     * @return true if version ends with -SNAPSHOT
     */
    public static boolean isSnapshot(String version) {
        return version != null && version.endsWith("-SNAPSHOT");
    }

    /**
     * Check whether a version string is branch-qualified
     * (has a non-numeric qualifier before -SNAPSHOT).
     *
     * @param version version to check
     * @return true if version contains a branch qualifier
     */
    public static boolean isBranchQualified(String version) {
        if (!isSnapshot(version)) return false;
        String base = stripSnapshot(version);
        return !base.equals(extractNumericBase(base));
    }
}
