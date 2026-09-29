package imagejai.engine.automation;

import java.nio.charset.StandardCharsets;
import java.nio.file.InvalidPathException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * Immutable startup-only gate for the ImageJ-AI test automation bridge.
 *
 * <p>The bridge is inert unless Fiji was launched with all of:</p>
 *
 * <pre>
 * -Dimagejai.testAutomation.enabled=true
 * -Dimagejai.testAutomation.workspace=&lt;absolute run workspace directory&gt;
 * -Dimagejai.testAutomation.readyFile=&lt;absolute ready JSON path inside the workspace&gt;
 * </pre>
 *
 * <p>The policy is read exactly once per JVM. Nothing re-reads the system
 * properties per request, so a later {@code System.setProperty} cannot turn the
 * bridge on in a running production Fiji. A property gate alone is still not
 * enough: an authenticated {@code hello} must also request
 * {@code capabilities.test_automation=true} before any automation command is
 * accepted, and compatibility (unauthenticated) sessions never receive it.</p>
 *
 * <p>Every rejection is reported as a stable {@link #disabledReason()} code and
 * never echoes a filesystem path, so a disabled-bridge error cannot be used to
 * probe the host.</p>
 */
public final class AutomationPolicy {

    /** Wire-protocol version of the automation bridge. */
    public static final String PROTOCOL_VERSION = "1.0.0";

    /** Negotiated {@code hello} capability name. */
    public static final String CAPABILITY = "test_automation";

    public static final String PROP_ENABLED = "imagejai.testAutomation.enabled";
    public static final String PROP_WORKSPACE = "imagejai.testAutomation.workspace";
    public static final String PROP_READY_FILE = "imagejai.testAutomation.readyFile";
    public static final String PROP_PORT = "imagejai.testAutomation.port";

    // ---- Response and work bounds. All automation replies stay inside these. ----

    /** Hard ceiling on nodes returned by one {@code get_ui_tree} call. */
    public static final int MAX_TREE_NODES = 4_000;
    /** Hard ceiling on hierarchy depth walked for one window. */
    public static final int MAX_TREE_DEPTH = 64;
    /** Per-string ceiling for any label/text/value copied out of a component. */
    public static final int MAX_TEXT_CHARS = 512;
    /** Ceiling on enumerated choices (combo items, tabs, list rows) per node. */
    public static final int MAX_ITEMS_PER_NODE = 128;
    /** Longest a single automation command may occupy an EDT slot. */
    public static final long MAX_COMMAND_TIMEOUT_MS = 120_000L;
    /** Default per-command timeout when the caller does not supply one. */
    public static final long DEFAULT_COMMAND_TIMEOUT_MS = 5_000L;
    /** Longest {@code wait_for_ui_state} / {@code wait_for_ui_idle} deadline. */
    public static final long MAX_WAIT_TIMEOUT_MS = 300_000L;
    /** Largest captured edge, in device pixels, for {@code capture_ui}. */
    public static final int MAX_CAPTURE_DIMENSION = 4_096;
    /** Largest PNG payload, in bytes, returned by {@code capture_ui}. */
    public static final int MAX_CAPTURE_BYTES = 16 * 1024 * 1024;
    /** Concurrent automation EDT operations admitted by this bridge. */
    public static final int MAX_CONCURRENT_ACTIONS = 8;
    /** Concurrent open performance traces. */
    public static final int MAX_OPEN_TRACES = 8;
    /** Retained finished traces available to {@code get_ui_metrics}. */
    public static final int MAX_RETAINED_TRACES = 64;

    /** Gated command names, in the order they are advertised. */
    public static final List<String> COMMANDS = Collections.unmodifiableList(Arrays.asList(
            "get_ui_tree",
            "get_ui_component",
            "perform_ui_action",
            "wait_for_ui_state",
            "wait_for_ui_idle",
            "capture_ui",
            "start_ui_trace",
            "stop_ui_trace",
            "get_ui_metrics"));

    // ---- Stable disabled reasons. Never contains caller or host data. ----

    public static final String REASON_ENABLED_ABSENT = "startup_property_absent";
    public static final String REASON_ENABLED_NOT_TRUE = "startup_property_not_true";
    public static final String REASON_WORKSPACE_ABSENT = "workspace_property_absent";
    public static final String REASON_WORKSPACE_NOT_ABSOLUTE = "workspace_not_absolute";
    public static final String REASON_WORKSPACE_NOT_A_DIRECTORY = "workspace_not_a_directory";
    public static final String REASON_WORKSPACE_UNREADABLE = "workspace_unreadable";
    public static final String REASON_READY_FILE_ABSENT = "ready_file_property_absent";
    public static final String REASON_READY_FILE_NOT_ABSOLUTE = "ready_file_not_absolute";
    public static final String REASON_READY_FILE_OUTSIDE_WORKSPACE = "ready_file_outside_workspace";
    public static final String REASON_READY_FILE_PARENT_MISSING = "ready_file_parent_missing";
    public static final String REASON_PORT_INVALID = "port_property_invalid";
    public static final String REASON_ENABLED = "enabled";

    private static volatile AutomationPolicy current;

    private final boolean enabled;
    private final String disabledReason;
    private final Path workspace;
    private final Path readyFile;
    private final int requestedPort;
    private final String instanceId;
    private final String workspaceId;
    private final long startedAtEpochMs;

    private AutomationPolicy(boolean enabled, String disabledReason, Path workspace,
                             Path readyFile, int requestedPort) {
        this.enabled = enabled;
        this.disabledReason = disabledReason;
        this.workspace = workspace;
        this.readyFile = readyFile;
        this.requestedPort = requestedPort;
        this.instanceId = enabled ? UUID.randomUUID().toString() : "";
        this.workspaceId = enabled ? shortDigest(workspace.toString()) : "";
        this.startedAtEpochMs = System.currentTimeMillis();
    }

    /**
     * The one policy for this JVM, derived from the startup properties the
     * first time it is asked for and never recomputed.
     */
    public static AutomationPolicy current() {
        AutomationPolicy snapshot = current;
        if (snapshot != null) return snapshot;
        synchronized (AutomationPolicy.class) {
            if (current == null) {
                current = fromProperties(System.getProperties());
            }
            return current;
        }
    }

    /** Test seam: build a policy from an explicit property set. */
    public static AutomationPolicy fromProperties(Properties properties) {
        if (properties == null) return disabled(REASON_ENABLED_ABSENT);
        String enabled = properties.getProperty(PROP_ENABLED);
        if (enabled == null || enabled.trim().isEmpty()) {
            return disabled(REASON_ENABLED_ABSENT);
        }
        if (!"true".equalsIgnoreCase(enabled.trim())) {
            return disabled(REASON_ENABLED_NOT_TRUE);
        }

        String rawWorkspace = properties.getProperty(PROP_WORKSPACE);
        if (rawWorkspace == null || rawWorkspace.trim().isEmpty()) {
            return disabled(REASON_WORKSPACE_ABSENT);
        }
        Path workspace;
        try {
            workspace = Paths.get(rawWorkspace.trim());
        } catch (InvalidPathException malformed) {
            return disabled(REASON_WORKSPACE_NOT_ABSOLUTE);
        }
        if (!workspace.isAbsolute()) return disabled(REASON_WORKSPACE_NOT_ABSOLUTE);
        try {
            workspace = workspace.toRealPath();
        } catch (java.io.IOException unreadable) {
            return disabled(REASON_WORKSPACE_UNREADABLE);
        }
        if (!Files.isDirectory(workspace)) {
            return disabled(REASON_WORKSPACE_NOT_A_DIRECTORY);
        }

        String rawReady = properties.getProperty(PROP_READY_FILE);
        if (rawReady == null || rawReady.trim().isEmpty()) {
            return disabled(REASON_READY_FILE_ABSENT);
        }
        Path readyFile;
        try {
            readyFile = Paths.get(rawReady.trim());
        } catch (InvalidPathException malformed) {
            return disabled(REASON_READY_FILE_NOT_ABSOLUTE);
        }
        if (!readyFile.isAbsolute()) return disabled(REASON_READY_FILE_NOT_ABSOLUTE);
        readyFile = readyFile.normalize();
        Path readyParent = readyFile.getParent();
        if (readyParent == null) return disabled(REASON_READY_FILE_OUTSIDE_WORKSPACE);
        try {
            readyParent = readyParent.toRealPath();
        } catch (java.io.IOException missing) {
            return disabled(REASON_READY_FILE_PARENT_MISSING);
        }
        if (!readyParent.startsWith(workspace)) {
            return disabled(REASON_READY_FILE_OUTSIDE_WORKSPACE);
        }
        readyFile = readyParent.resolve(readyFile.getFileName());

        int port = -1;
        String rawPort = properties.getProperty(PROP_PORT);
        if (rawPort != null && !rawPort.trim().isEmpty()) {
            try {
                port = Integer.parseInt(rawPort.trim());
            } catch (NumberFormatException malformed) {
                return disabled(REASON_PORT_INVALID);
            }
            if (port < 0 || port > 65535) return disabled(REASON_PORT_INVALID);
        }

        return new AutomationPolicy(true, REASON_ENABLED, workspace, readyFile, port);
    }

    /** A policy that refuses everything, carrying a stable machine-readable reason. */
    public static AutomationPolicy disabled(String reason) {
        return new AutomationPolicy(false,
                reason == null || reason.isEmpty() ? REASON_ENABLED_ABSENT : reason,
                null, null, -1);
    }

    public boolean isEnabled() { return enabled; }

    /**
     * Stable reason code. {@code "enabled"} when the bridge is armed; otherwise
     * the exact gate that refused. Safe to return to a client: it names a
     * property or a validation rule, never a path or a secret.
     */
    public String disabledReason() { return disabledReason; }

    /** Absolute, real, existing run workspace. {@code null} when disabled. */
    public Path workspace() { return workspace; }

    /** Absolute ready-file path inside {@link #workspace()}. {@code null} when disabled. */
    public Path readyFile() { return readyFile; }

    /** Startup port override, or {@code -1} when the normal port selection applies. */
    public int requestedPort() { return requestedPort; }

    /** Opaque per-JVM instance identity published in the ready file and hello. */
    public String instanceId() { return instanceId; }

    /**
     * SHA-256 prefix of the absolute workspace path. Lets the harness confirm
     * it is talking to the instance it provisioned without the bridge echoing
     * a filesystem path back over the socket.
     */
    public String workspaceId() { return workspaceId; }

    public long startedAtEpochMs() { return startedAtEpochMs; }

    /** True when {@code candidate} resolves inside the declared workspace. */
    public boolean contains(Path candidate) {
        if (!enabled || candidate == null) return false;
        try {
            Path normalised = candidate.isAbsolute()
                    ? candidate.normalize()
                    : workspace.resolve(candidate).normalize();
            return normalised.startsWith(workspace);
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private static String shortDigest(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("sha256:");
            for (int i = 0; i < 16 && i < digest.length; i++) {
                int unsigned = digest[i] & 0xff;
                if (unsigned < 16) out.append('0');
                out.append(Integer.toHexString(unsigned));
            }
            return out.toString();
        } catch (Exception impossible) {
            return "sha256:unavailable";
        }
    }
}
