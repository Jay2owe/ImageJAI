package imagejai.engine;

import com.google.gson.JsonObject;
import ij.ImagePlus;
import ij.WindowManager;
import ij.measure.Calibration;
import ij.process.ImageProcessor;
import ij.process.ImageStatistics;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Background monitor that watches ImageJ state and proactively warns about issues.
 * Polls every 5 seconds and fires warnings via MonitorListener.
 */
public class ImageMonitor {

    public interface MonitorListener {
        void onWarning(String message);
        void onInfo(String message);
    }

    private static final int POLL_INTERVAL_MS = 5000;
    private static final long WARNING_COOLDOWN_MS = 30000;
    private static final double MEMORY_THRESHOLD_PERCENT = 80.0;
    private static final double SATURATION_THRESHOLD_PERCENT = 1.0;
    private static final long LARGE_IMAGE_PIXEL_THRESHOLD = 500000000L;

    private static final String WARN_MEMORY = "memory";
    private static final String WARN_SATURATED = "saturated";
    private static final String WARN_UNCALIBRATED = "uncalibrated";
    private static final String WARN_RGB = "rgb";
    private static final String WARN_LARGE = "large";

    private final StateInspector stateInspector;
    private final EventBus bus = EventBus.getInstance();
    private MonitorListener listener;
    private Timer pollTimer;
    private boolean running;

    private int lastImageCount;
    private String lastImageTitle;
    // Track open image titles between polls — diff drives image.opened/image.closed events.
    private final Set<String> lastOpenImages = new HashSet<String>();
    // Track whether memory.pressure is currently latched; re-fire only when it
    // crosses back above the threshold from below.
    private boolean memoryPressureActive = false;

    // Track warning timestamps per image+type to avoid spamming
    // Key: "imageTitle:warningType"
    private final Map<String, Long> warningTimestamps = new HashMap<String, Long>();

    // Track which images have already been checked for one-time warnings
    private final Set<String> checkedImages = new HashSet<String>();

    public ImageMonitor(StateInspector stateInspector) {
        this.stateInspector = stateInspector;
        this.lastImageCount = 0;
        this.lastImageTitle = "";
        this.running = false;
    }

    /**
     * Start monitoring (polls every 5 seconds on EDT).
     */
    public void start(MonitorListener listener) {
        if (running) {
            stop();
        }
        this.listener = listener;
        this.running = true;

        pollTimer = new Timer(POLL_INTERVAL_MS, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                pollCheck();
            }
        });
        pollTimer.setRepeats(true);
        pollTimer.start();
    }

    /** Start monitoring without a chat listener — useful for TCP-only mode so events still flow. */
    public void start() {
        start(null);
    }

    /**
     * Stop monitoring.
     */
    public void stop() {
        running = false;
        if (pollTimer != null) {
            pollTimer.stop();
            pollTimer = null;
        }
        listener = null;
    }

    /**
     * Run a one-time check and return any warnings.
     */
    public List<String> checkNow() {
        List<String> warnings = new ArrayList<String>();
        collectWarnings(warnings);
        return warnings;
    }

    /**
     * Called on each timer tick.
     */
    private void pollCheck() {
        if (!running) {
            return;
        }
        // Image-open/close diff runs every tick regardless of whether a chat
        // listener is attached — events flow to the bus independently.
        publishImageDiffEvents();

        // Edge-detect ResultsTable growth too. StateInspector coalesces its
        // own emissions through the bus.
        try {
            stateInspector.checkResultsTableChange();
        } catch (Throwable ignore) {
        }

        List<String> warnings = new ArrayList<String>();
        collectWarnings(warnings);
        // Warnings and infos are dispatched inside collectWarnings
    }

    /**
     * Diff the current open-image set against the last snapshot and publish
     * {@code image.opened} / {@code image.closed} events through the bus.
     * Also publishes {@code image.updated} for the active image whenever the
     * active title changes — a coarse "something switched" signal.
     */
    private void publishImageDiffEvents() {
        Set<String> current = new HashSet<String>();
        Map<String, ImagePlus> byTitle = new HashMap<String, ImagePlus>();
        int[] ids = WindowManager.getIDList();
        if (ids != null) {
            for (int id : ids) {
                ImagePlus imp = WindowManager.getImage(id);
                if (imp != null) {
                    String t = imp.getTitle();
                    if (t == null) t = "";
                    current.add(t);
                    byTitle.put(t, imp);
                }
            }
        }

        // New titles -> image.opened
        for (String t : current) {
            if (!lastOpenImages.contains(t)) {
                ImagePlus imp = byTitle.get(t);
                JsonObject data = new JsonObject();
                data.addProperty("title", t);
                if (imp != null) {
                    JsonObject dims = new JsonObject();
                    dims.addProperty("width", imp.getWidth());
                    dims.addProperty("height", imp.getHeight());
                    dims.addProperty("channels", imp.getNChannels());
                    dims.addProperty("slices", imp.getNSlices());
                    dims.addProperty("frames", imp.getNFrames());
                    data.add("dims", dims);
                    Calibration cal = imp.getCalibration();
                    if (cal != null) {
                        JsonObject calJson = new JsonObject();
                        calJson.addProperty("pixelWidth", cal.pixelWidth);
                        calJson.addProperty("pixelHeight", cal.pixelHeight);
                        calJson.addProperty("unit", cal.getUnit() == null ? "" : cal.getUnit());
                        data.add("calibration", calJson);
                    }
                    addImagePath(data, imp);
                }
                bus.publish("image.opened", data);
            }
        }

        // Disappeared titles -> image.closed
        for (String t : lastOpenImages) {
            if (!current.contains(t)) {
                JsonObject data = new JsonObject();
                data.addProperty("title", t);
                bus.publish("image.closed", data);
            }
        }

        // Active image switch -> image.updated (coalesced to 200ms by the bus).
        ImagePlus active = WindowManager.getCurrentImage();
        String activeTitle = active == null ? "" : active.getTitle();
        if (activeTitle == null) activeTitle = "";
        if (!activeTitle.equals(lastImageTitle) && !activeTitle.isEmpty()) {
            JsonObject data = new JsonObject();
            data.addProperty("title", activeTitle);
            data.addProperty("reason", "active_changed");
            if (active != null) addImagePath(data, active);
            bus.publish("image.updated", data);
        }

        lastOpenImages.clear();
        lastOpenImages.addAll(current);
    }

    private void collectWarnings(List<String> output) {
        // Check memory pressure
        checkMemory(output);

        // Check active image
        ImagePlus imp = WindowManager.getCurrentImage();
        if (imp == null) {
            lastImageTitle = "";
            lastImageCount = countOpenImages();
            return;
        }

        String currentTitle = imp.getTitle();
        int currentCount = countOpenImages();
        boolean isNewImage = !currentTitle.equals(lastImageTitle) || currentCount != lastImageCount;

        // One-time checks for new/changed images
        if (isNewImage && !checkedImages.contains(currentTitle)) {
            checkedImages.add(currentTitle);
            checkSaturation(imp, currentTitle, output);
            checkCalibration(imp, currentTitle, output);
            checkRGB(imp, currentTitle, output);
            checkLargeImage(imp, currentTitle, output);
        }

        lastImageTitle = currentTitle;
        lastImageCount = currentCount;
    }

    private void checkMemory(List<String> output) {
        MemoryInfo mem = stateInspector.getMemoryInfo();
        int percent = mem.getUsagePercent();
        boolean aboveThreshold = percent > MEMORY_THRESHOLD_PERCENT;

        // Event-bus side: fire memory.pressure on rising edge only. Re-arm once
        // usage drops back below threshold so repeated crossings are reported.
        if (aboveThreshold && !memoryPressureActive) {
            JsonObject data = new JsonObject();
            data.addProperty("used_pct", percent);
            data.addProperty("usedMB", mem.getUsedMB());
            data.addProperty("maxMB", mem.getMaxMB());
            bus.publish("memory.pressure", data);
            memoryPressureActive = true;
        } else if (!aboveThreshold && memoryPressureActive) {
            memoryPressureActive = false;
        }

        if (aboveThreshold) {
            String key = WARN_MEMORY;
            if (canWarn(key)) {
                String msg = "ImageJ is using " + mem.getUsedMB() + "MB of " + mem.getMaxMB()
                        + "MB RAM (" + percent + "%). Consider closing some images or "
                        + "increasing memory (Edit > Options > Memory).";
                recordWarning(key);
                output.add(msg);
                fireWarning(msg);
            }
        }
    }

    private void checkSaturation(ImagePlus imp, String title, List<String> output) {
        int type = imp.getType();
        if (type == ImagePlus.COLOR_RGB || type == ImagePlus.GRAY32) {
            // Skip saturation check for RGB (ambiguous) and 32-bit float
            return;
        }

        double maxVal;
        if (type == ImagePlus.GRAY8 || type == ImagePlus.COLOR_256) {
            maxVal = 255;
        } else if (type == ImagePlus.GRAY16) {
            // Check actual bit depth via image stats for 12-bit images
            ImageStatistics stats = imp.getStatistics();
            if (stats.max <= 4095) {
                maxVal = 4095;
            } else {
                maxVal = 65535;
            }
        } else {
            return;
        }

        ImageProcessor ip = imp.getProcessor();
        int width = ip.getWidth();
        int height = ip.getHeight();
        long totalPixels = (long) width * height;
        long saturatedCount = 0;

        for (int i = 0; i < totalPixels; i++) {
            if (ip.getf(i) >= maxVal) {
                saturatedCount++;
            }
        }

        double saturatedPercent = (saturatedCount * 100.0) / totalPixels;
        if (saturatedPercent > SATURATION_THRESHOLD_PERCENT) {
            String key = title + ":" + WARN_SATURATED;
            if (canWarn(key)) {
                String msg = "Warning: " + String.format("%.1f", saturatedPercent)
                        + "% of pixels are saturated (at max value). "
                        + "Intensity measurements in saturated regions will be unreliable.";
                recordWarning(key);
                output.add(msg);
                fireWarning(msg);
            }
        }
    }

    private void checkCalibration(ImagePlus imp, String title, List<String> output) {
        Calibration cal = imp.getCalibration();
        if (cal == null) {
            return;
        }
        boolean isDefault = (cal.pixelWidth == 1.0 && cal.pixelHeight == 1.0);
        String unit = cal.getUnit();
        boolean noUnit = (unit == null || unit.isEmpty() || "pixel".equalsIgnoreCase(unit)
                || "pixels".equalsIgnoreCase(unit));

        if (isDefault && noUnit) {
            String key = title + ":" + WARN_UNCALIBRATED;
            if (canWarn(key)) {
                String msg = "This image has no spatial calibration. Measurements will be in pixels. "
                        + "Set scale via Analyze > Set Scale if you know the pixel size.";
                recordWarning(key);
                output.add(msg);
                fireInfo(msg);
            }
        }
    }

    private void checkRGB(ImagePlus imp, String title, List<String> output) {
        if (imp.getType() == ImagePlus.COLOR_RGB) {
            String key = title + ":" + WARN_RGB;
            if (canWarn(key)) {
                String msg = "This is an RGB image. For quantitative fluorescence analysis, "
                        + "consider splitting channels first.";
                recordWarning(key);
                output.add(msg);
                fireInfo(msg);
            }
        }
    }

    private void checkLargeImage(ImagePlus imp, String title, List<String> output) {
        long totalPixels = (long) imp.getWidth() * imp.getHeight()
                * imp.getNSlices() * imp.getNChannels();
        if (totalPixels > LARGE_IMAGE_PIXEL_THRESHOLD) {
            String key = title + ":" + WARN_LARGE;
            if (canWarn(key)) {
                long megapixels = totalPixels / 1000000L;
                String msg = "This is a very large image (" + megapixels
                        + " megapixels total). Operations may be slow and memory-intensive. "
                        + "Consider working on a cropped region or downsampled copy.";
                recordWarning(key);
                output.add(msg);
                fireWarning(msg);
            }
        }
    }

    /**
     * Check whether enough time has passed since the last warning of this type.
     */
    private boolean canWarn(String key) {
        Long lastTime = warningTimestamps.get(key);
        if (lastTime == null) {
            return true;
        }
        return (System.currentTimeMillis() - lastTime) > WARNING_COOLDOWN_MS;
    }

    private void recordWarning(String key) {
        warningTimestamps.put(key, System.currentTimeMillis());
    }

    private void fireWarning(String message) {
        if (listener != null) {
            listener.onWarning(message);
        }
    }

    private void fireInfo(String message) {
        if (listener != null) {
            listener.onInfo(message);
        }
    }

    private int countOpenImages() {
        int[] ids = WindowManager.getIDList();
        return ids != null ? ids.length : 0;
    }

    private void addImagePath(JsonObject data, ImagePlus imp) {
        if (data == null || imp == null) return;
        try {
            ij.io.FileInfo fi = imp.getOriginalFileInfo();
            if (fi == null || fi.directory == null || fi.fileName == null) return;
            data.addProperty("path", fi.directory + fi.fileName);
        } catch (Throwable ignore) {
        }
    }
}
