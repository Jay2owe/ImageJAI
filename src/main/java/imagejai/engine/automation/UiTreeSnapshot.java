package imagejai.engine.automation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One versioned view of every window this JVM owns, with the environment facts
 * a harness needs to key screenshots and baselines.
 *
 * <p>A snapshot is a value: taking it does not change the UI, and holding it
 * does not pin any component. The {@code generation} it carries is the contract
 * for later actions — see {@link UiIdentityRegistry} for what advances it.</p>
 */
public final class UiTreeSnapshot {

    /** A window root plus the window-only attributes that are not component state. */
    public static final class WindowEntry {
        private final UiNode root;
        private final JsonObject extras;

        WindowEntry(UiNode root, JsonObject extras) {
            this.root = root;
            this.extras = extras;
        }

        public UiNode root() { return root; }

        public JsonObject toJson() {
            JsonObject json = root.toJson(true);
            for (Map.Entry<String, JsonElement> extra : extras.entrySet()) {
                json.add(extra.getKey(), extra.getValue());
            }
            return json;
        }
    }

    private final long generation;
    private final long capturedAtEpochMs;
    private final JsonObject environment;
    private final List<WindowEntry> windows;
    private final int nodeCount;
    private final boolean truncated;

    UiTreeSnapshot(long generation, long capturedAtEpochMs, JsonObject environment,
                   List<WindowEntry> windows, int nodeCount, boolean truncated) {
        this.generation = generation;
        this.capturedAtEpochMs = capturedAtEpochMs;
        this.environment = environment;
        this.windows = Collections.unmodifiableList(new ArrayList<WindowEntry>(windows));
        this.nodeCount = nodeCount;
        this.truncated = truncated;
    }

    public long generation() { return generation; }
    public List<WindowEntry> windows() { return windows; }
    public int nodeCount() { return nodeCount; }
    public boolean truncated() { return truncated; }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("protocol_version", AutomationPolicy.PROTOCOL_VERSION);
        json.addProperty("generation", generation);
        json.addProperty("captured_at_epoch_ms", capturedAtEpochMs);
        json.add("environment", environment.deepCopy());
        JsonArray array = new JsonArray();
        for (WindowEntry window : windows) array.add(window.toJson());
        json.add("windows", array);
        json.addProperty("window_count", windows.size());
        json.addProperty("node_count", nodeCount);
        json.addProperty("truncated", truncated);
        json.addProperty("max_nodes", AutomationPolicy.MAX_TREE_NODES);
        return json;
    }

    /**
     * Environment fingerprint. Screenshot baselines and timing budgets are only
     * comparable within one of these, so the harness keys on it rather than
     * assuming a shared machine.
     */
    static JsonObject describeEnvironment() {
        JsonObject json = new JsonObject();
        json.addProperty("os_name", System.getProperty("os.name", ""));
        json.addProperty("os_version", System.getProperty("os.version", ""));
        json.addProperty("os_arch", System.getProperty("os.arch", ""));
        json.addProperty("java_version", System.getProperty("java.version", ""));
        json.addProperty("java_vendor", System.getProperty("java.vendor", ""));
        boolean headless = java.awt.GraphicsEnvironment.isHeadless();
        json.addProperty("headless", headless);
        String laf = "";
        try {
            javax.swing.LookAndFeel current = javax.swing.UIManager.getLookAndFeel();
            if (current != null) laf = current.getClass().getName();
        } catch (Throwable ignored) {
        }
        json.addProperty("look_and_feel", laf);
        double scale = 1.0d;
        int screenWidth = 0;
        int screenHeight = 0;
        if (!headless) {
            try {
                java.awt.GraphicsConfiguration configuration =
                        java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                                .getDefaultScreenDevice().getDefaultConfiguration();
                scale = configuration.getDefaultTransform().getScaleX();
                java.awt.Rectangle bounds = configuration.getBounds();
                screenWidth = bounds.width;
                screenHeight = bounds.height;
            } catch (Throwable ignored) {
            }
        }
        json.addProperty("screen_scale", scale);
        json.addProperty("screen_width", screenWidth);
        json.addProperty("screen_height", screenHeight);
        return json;
    }
}
