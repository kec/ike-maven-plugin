package network.ike.plugin.ws.vcs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.Properties;
import java.io.StringReader;

/**
 * The VCS state file ({@code .ike/vcs-state}) records the last VCS action
 * performed in a repository. Written by git hooks and plugin goals,
 * delivered between machines by Syncthing.
 *
 * <p>Format is a plain properties file — trivial to parse in both bash
 * (grep/cut) and Java (Properties.load).
 *
 * @param timestamp UTC timestamp of the action
 * @param machine   short hostname of the machine that performed the action
 * @param branch    the branch name at the time of the action
 * @param sha       the 8-character short SHA at the time of the action
 * @param action    the action performed (e.g., {@link #ACTION_COMMIT})
 */
public record VcsState(
        String timestamp,
        String machine,
        String branch,
        String sha,
        String action
) {

    /** Actions written to the state file by hooks and plugin goals. */
    public static final String ACTION_COMMIT = "commit";
    public static final String ACTION_PUSH = "push";
    public static final String ACTION_FEATURE_START = "feature-start";
    public static final String ACTION_FEATURE_FINISH = "feature-finish";
    public static final String ACTION_RELEASE = "release";
    public static final String ACTION_CHECKPOINT = "checkpoint";

    private static final String STATE_FILE = ".ike/vcs-state";
    private static final DateTimeFormatter UTC_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                    .withZone(ZoneOffset.UTC);

    /**
     * Read the VCS state file from the given directory.
     *
     * @param dir the repository root directory
     * @return the state if the file exists and is readable, empty otherwise
     */
    public static Optional<VcsState> readFrom(Path dir) {
        Path stateFile = dir.resolve(STATE_FILE);
        if (!Files.exists(stateFile)) {
            return Optional.empty();
        }
        try {
            String content = Files.readString(stateFile, StandardCharsets.UTF_8);
            Properties props = new Properties();
            props.load(new StringReader(content));

            String timestamp = props.getProperty("timestamp", "");
            String machine = props.getProperty("machine", "");
            String branch = props.getProperty("branch", "");
            String sha = props.getProperty("sha", "");
            String action = props.getProperty("action", "");

            if (sha.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new VcsState(timestamp, machine, branch, sha, action));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Write the VCS state file to the given directory.
     *
     * @param dir    the repository root directory (must contain {@code .ike/})
     * @param state  the state to write
     * @throws IOException if the file cannot be written
     */
    public static void writeTo(Path dir, VcsState state) throws IOException {
        Path stateFile = dir.resolve(STATE_FILE);
        Files.createDirectories(stateFile.getParent());
        String content = "timestamp=" + state.timestamp() + "\n"
                + "machine=" + state.machine() + "\n"
                + "branch=" + state.branch() + "\n"
                + "sha=" + state.sha() + "\n"
                + "action=" + state.action() + "\n";
        Files.writeString(stateFile, content, StandardCharsets.UTF_8);
    }

    /**
     * Create a VcsState with the current timestamp and local hostname.
     *
     * @param branch the current branch name
     * @param sha    the current HEAD SHA (8-character short form)
     * @param action the action being performed
     * @return a new VcsState
     */
    public static VcsState create(String branch, String sha, String action) {
        String timestamp = UTC_FORMAT.format(Instant.now());
        String machine = hostname();
        return new VcsState(timestamp, machine, branch, sha, action);
    }

    /**
     * Check whether the {@code .ike/} directory exists in the given path,
     * indicating the repo is IKE-managed.
     *
     * @param dir the repository root directory
     * @return true if the repo has an {@code .ike/} directory
     */
    public static boolean isIkeManaged(Path dir) {
        return Files.isDirectory(dir.resolve(".ike"));
    }

    private static String hostname() {
        String host = System.getenv("HOSTNAME");
        if (host == null || host.isEmpty()) {
            try {
                host = java.net.InetAddress.getLocalHost().getHostName();
            } catch (Exception e) {
                host = "unknown";
            }
        }
        // Strip domain — equivalent to ${HOSTNAME%%.*} in bash
        int dot = host.indexOf('.');
        return dot > 0 ? host.substring(0, dot) : host;
    }
}
