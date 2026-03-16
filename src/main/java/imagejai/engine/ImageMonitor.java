package imagejai.engine;

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
    private MonitorListener listener;
    private Timer pollTimer;
    private boolean running;

    private int lastImageCount;
    private String lastImageTitle;

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
        if (!running || listener == null) {
            return;
        }
        List<String> warnings = new ArrayList<String>();
        collectWarnings(warnings);
        // Warnings and infos are dispatched inside collectWarnings
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
        if (percent > MEMORY_THRESHOLD_PERCENT) {
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
}
