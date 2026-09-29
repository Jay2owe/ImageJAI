package imagejai.engine.automation;

import com.google.gson.JsonObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * The secret-free readiness signal a harness waits on before it connects.
 *
 * <p>A listening socket is not proof that the right Fiji, the right JAR, or the
 * right bridge is up. This file is written <em>after</em> the server has bound
 * and knows its actual port, is moved into place atomically so a reader never
 * sees a half-written document, and carries the instance identity and PID so a
 * stale file from a previous run cannot impersonate readiness.</p>
 *
 * <p>It never contains the installation token. Authentication still requires
 * reading the token from the run's isolated {@code user.home}; readiness and
 * credentials are deliberately separate files with separate lifetimes.</p>
 */
public final class AutomationReadyFile {

    public static final int SCHEMA_VERSION = 1;

    private AutomationReadyFile() {
    }

    /**
     * Build the ready document. Kept separate from writing so the exact bytes
     * can be asserted without touching a filesystem.
     */
    public static JsonObject describe(AutomationPolicy policy, int boundPort,
                                      String host, String serverVersion,
                                      String pluginVersion) {
        JsonObject json = new JsonObject();
        json.addProperty("schema_version", SCHEMA_VERSION);
        json.addProperty("protocol_version", AutomationPolicy.PROTOCOL_VERSION);
        json.addProperty("instance_id", policy.instanceId());
        json.addProperty("pid", currentPid());
        json.addProperty("host", host == null ? "127.0.0.1" : host);
        json.addProperty("port", boundPort);
        json.addProperty("server_version", serverVersion == null ? "" : serverVersion);
        json.addProperty("plugin_version", pluginVersion == null ? "" : pluginVersion);
        json.addProperty("workspace_id", policy.workspaceId());
        json.addProperty("test_automation", true);
        json.addProperty("capability", AutomationPolicy.CAPABILITY);
        json.addProperty("started_at_epoch_ms", policy.startedAtEpochMs());
        json.addProperty("ready_at_epoch_ms", System.currentTimeMillis());
        return json;
    }

    /**
     * Atomically publish readiness inside the declared workspace.
     *
     * @return true when the file is on disk and readable
     */
    public static boolean write(AutomationPolicy policy, int boundPort, String host,
                                String serverVersion, String pluginVersion) {
        if (policy == null || !policy.isEnabled()) return false;
        Path target = policy.readyFile();
        if (target == null || !policy.contains(target)) return false;
        Path pending = null;
        try {
            Path parent = target.getParent();
            if (parent == null) return false;
            Files.createDirectories(parent);
            pending = Files.createTempFile(parent, ".imagejai-ready-", ".tmp");
            byte[] payload = describe(policy, boundPort, host, serverVersion,
                    pluginVersion).toString().getBytes(StandardCharsets.UTF_8);
            Files.write(pending, payload, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException fallback) {
                Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING);
            }
            pending = null;
            return Files.isRegularFile(target) && Files.size(target) == payload.length;
        } catch (IOException failure) {
            System.err.println("[ImageJAI-Automation] ready file write failed: "
                    + failure.getClass().getSimpleName());
            return false;
        } finally {
            if (pending != null) {
                try {
                    Files.deleteIfExists(pending);
                } catch (IOException ignored) {
                    // The original failure is what matters.
                }
            }
        }
    }

    /** Retire readiness on shutdown so a dead instance cannot look alive. */
    public static boolean delete(AutomationPolicy policy) {
        if (policy == null || !policy.isEnabled()) return false;
        Path target = policy.readyFile();
        if (target == null || !policy.contains(target)) return false;
        try {
            return Files.deleteIfExists(target);
        } catch (IOException failure) {
            System.err.println("[ImageJAI-Automation] ready file delete failed: "
                    + failure.getClass().getSimpleName());
            return false;
        }
    }

    static long currentPid() {
        try {
            // Java 9+ exposes the PID directly; the reflective call keeps the
            // Java 11 bytecode target free of a hard ProcessHandle reference in
            // case a future build lowers it again.
            Class<?> handle = Class.forName("java.lang.ProcessHandle");
            Object current = handle.getMethod("current").invoke(null);
            Object pid = handle.getMethod("pid").invoke(current);
            return pid instanceof Long ? ((Long) pid).longValue() : -1L;
        } catch (Throwable unavailable) {
            return -1L;
        }
    }
}
