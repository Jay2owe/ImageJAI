package imagejai.engine;

import com.google.gson.JsonObject;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.plugin.frame.RoiManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Queries current ImageJ state for context injection into LLM prompts.
 */
public class StateInspector {

    private final EventBus bus = EventBus.getInstance();
    // Last-known row count for results.changed edge detection. Shared across
    // callers; mutations are serialised via the synchronized check method.
    private int lastResultsRowCount = 0;
    private int lastResultsColCount = 0;

    /**
     * Publish a {@code results.changed} event if the ResultsTable row or
     * column count has changed since the last call. Safe to call frequently;
     * the bus coalesces to 200ms.
     */
    public synchronized void checkResultsTableChange() {
        ResultsTable rt = ResultsTable.getResultsTable();
        int rows = (rt == null) ? 0 : rt.getCounter();
        String[] headings = (rt == null) ? new String[0] : rt.getHeadings();
        int cols = headings == null ? 0 : headings.length;
        if (rows != lastResultsRowCount || cols != lastResultsColCount) {
            JsonObject data = new JsonObject();
            data.addProperty("rows", rows);
            data.addProperty("cols", cols);
            if (rows != lastResultsRowCount) {
                data.addProperty("delta", rows - lastResultsRowCount);
            }
            bus.publish("results.changed", data);
            lastResultsRowCount = rows;
            lastResultsColCount = cols;
        }
    }

    /**
     * Get info about the currently active image, or null if none is open.
     */
    public ImageInfo getActiveImageInfo() {
        ImagePlus imp = WindowManager.getCurrentImage();
        if (imp == null) {
            return null;
        }
        return buildImageInfo(imp);
    }

    /**
     * Get summaries of all open images.
     */
    public List<ImageInfo> getAllImages() {
        List<ImageInfo> images = new ArrayList<ImageInfo>();
        int[] ids = WindowManager.getIDList();
        if (ids == null) {
            return images;
        }
        int[] sortedIds = ids.clone();
        Arrays.sort(sortedIds);
        for (int id : sortedIds) {
            ImagePlus imp = WindowManager.getImage(id);
            if (imp != null) {
                images.add(buildImageInfo(imp));
            }
        }
        return images;
    }

    /**
     * Get the current ResultsTable contents as CSV text.
     *
     * @return CSV string, or empty string if no results table exists
     */
    public String getResultsTableCSV() {
        ResultsTable rt = ResultsTable.getResultsTable();
        if (rt == null || rt.getCounter() == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        // Header row
        String[] headings = rt.getHeadings();
        for (int i = 0; i < headings.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(escapeCsv(headings[i]));
        }
        sb.append("\n");

        // Data rows
        int rowCount = rt.getCounter();
        for (int row = 0; row < rowCount; row++) {
            for (int col = 0; col < headings.length; col++) {
                if (col > 0) sb.append(",");
                String value = rt.getStringValue(headings[col], row);
                sb.append(escapeCsv(value));
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Get ROI Manager contents as a summary string.
     *
     * @return description of ROIs, or empty string if no ROI Manager
     */
    public String getRoiManagerInfo() {
        RoiManager rm = RoiManager.getInstance();
        if (rm == null || rm.getCount() == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("ROI Manager: ").append(rm.getCount()).append(" ROIs\n");
        int roiCount = rm.getCount();
        int limit = Math.min(roiCount, 20);
        for (int i = 0; i < limit; i++) {
            sb.append("  ").append(i + 1).append(". ").append(rm.getName(i)).append("\n");
        }
        if (roiCount > 20) {
            sb.append("  ... and ").append(roiCount - 20).append(" more\n");
        }
        return sb.toString();
    }

    /**
     * Stable SHA-256 identity of every logical C/Z/T plane and measurement
     * calibration. Titles, active-plane selection and the default locale are
     * deliberately excluded.
     */
    public static String datasetHash(ImagePlus imp) {
        if (imp == null) return null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateString(digest, "ImageJAI-dataset-v1");
            updateInt(digest, imp.getWidth());
            updateInt(digest, imp.getHeight());
            updateInt(digest, imp.getType());
            updateInt(digest, imp.getNChannels());
            updateInt(digest, imp.getNSlices());
            updateInt(digest, imp.getNFrames());
            updateInt(digest, imp.getStackSize());

            Calibration cal = imp.getCalibration();
            if (cal == null) {
                updateInt(digest, 0);
            } else {
                updateInt(digest, 1);
                updateLong(digest, Double.doubleToLongBits(cal.pixelWidth));
                updateLong(digest, Double.doubleToLongBits(cal.pixelHeight));
                updateLong(digest, Double.doubleToLongBits(cal.pixelDepth));
                updateLong(digest, Double.doubleToLongBits(cal.xOrigin));
                updateLong(digest, Double.doubleToLongBits(cal.yOrigin));
                updateLong(digest, Double.doubleToLongBits(cal.zOrigin));
                updateLong(digest, Double.doubleToLongBits(cal.frameInterval));
                updateString(digest, cal.getUnit());
                updateString(digest, cal.getValueUnit());
            }

            ImageStack stack = imp.getStack();
            int channels = Math.max(1, imp.getNChannels());
            int slices = Math.max(1, imp.getNSlices());
            int frames = Math.max(1, imp.getNFrames());
            for (int t = 1; t <= frames; t++) {
                for (int z = 1; z <= slices; z++) {
                    for (int c = 1; c <= channels; c++) {
                        updateInt(digest, c);
                        updateInt(digest, z);
                        updateInt(digest, t);
                        updatePixels(digest, stack.getPixels(imp.getStackIndex(c, z, t)));
                    }
                }
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void updatePixels(MessageDigest digest, Object pixels) {
        if (pixels instanceof byte[]) {
            digest.update((byte[]) pixels);
        } else if (pixels instanceof short[]) {
            for (short value : (short[]) pixels) updateInt16(digest, value & 0xffff);
        } else if (pixels instanceof int[]) {
            for (int value : (int[]) pixels) updateInt(digest, value);
        } else if (pixels instanceof float[]) {
            for (float value : (float[]) pixels) {
                updateInt(digest, Float.floatToRawIntBits(value));
            }
        } else {
            throw new IllegalArgumentException("unsupported pixel array: "
                    + (pixels == null ? "null" : pixels.getClass().getName()));
        }
    }

    private static void updateString(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        updateInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void updateInt16(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void updateLong(MessageDigest digest, long value) {
        updateInt(digest, (int) (value >>> 32));
        updateInt(digest, (int) value);
    }

    private static String hex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int value = bytes[i] & 0xff;
            chars[i * 2] = digits[value >>> 4];
            chars[i * 2 + 1] = digits[value & 0xf];
        }
        return new String(chars);
    }

    /**
     * Get current JVM memory usage.
     */
    public MemoryInfo getMemoryInfo() {
        Runtime runtime = Runtime.getRuntime();
        long maxMB = runtime.maxMemory() / (1024 * 1024);
        long totalMB = runtime.totalMemory() / (1024 * 1024);
        long freeMB = runtime.freeMemory() / (1024 * 1024);
        long usedMB = totalMB - freeMB;

        int imageCount = 0;
        int[] ids = WindowManager.getIDList();
        if (ids != null) {
            imageCount = ids.length;
        }

        return new MemoryInfo(usedMB, maxMB, maxMB - usedMB, imageCount);
    }

    /**
     * Build a state context string suitable for injection into LLM prompts.
     * Summarizes open images, results, ROIs, and memory.
     */
    public String buildStateContext() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== ImageJ State ===\n");

        // Open images
        List<ImageInfo> images = getAllImages();
        if (images.isEmpty()) {
            sb.append("No images open.\n");
        } else {
            sb.append("Open images (").append(images.size()).append("):\n");
            for (ImageInfo info : images) {
                sb.append("  - ").append(info.toString()).append("\n");
            }
        }

        // Active image
        ImageInfo active = getActiveImageInfo();
        if (active != null) {
            sb.append("Active image: ").append(active.getTitle()).append("\n");
        }

        // Results table
        ResultsTable rt = ResultsTable.getResultsTable();
        if (rt != null && rt.getCounter() > 0) {
            sb.append("Results table: ").append(rt.getCounter()).append(" rows\n");
        }

        // ROI Manager
        String roiInfo = getRoiManagerInfo();
        if (!roiInfo.isEmpty()) {
            sb.append(roiInfo);
        }

        // Memory
        MemoryInfo mem = getMemoryInfo();
        sb.append(mem.toString()).append("\n");

        return sb.toString();
    }

    /**
     * Build an ImageInfo from an ImagePlus.
     */
    private ImageInfo buildImageInfo(ImagePlus imp) {
        String typeStr;
        switch (imp.getType()) {
            case ImagePlus.GRAY8:
                typeStr = "8-bit";
                break;
            case ImagePlus.GRAY16:
                typeStr = "16-bit";
                break;
            case ImagePlus.GRAY32:
                typeStr = "32-bit";
                break;
            case ImagePlus.COLOR_RGB:
                typeStr = "RGB";
                break;
            case ImagePlus.COLOR_256:
                typeStr = "8-bit color";
                break;
            default:
                typeStr = "unknown";
        }

        Calibration cal = imp.getCalibration();
        String calibStr = "";
        if (cal != null && cal.scaled()) {
            calibStr = cal.pixelWidth + " " + cal.getUnit() + "/px";
        }

        return new ImageInfo(
                imp.getTitle(),
                imp.getWidth(),
                imp.getHeight(),
                imp.getNSlices(),
                imp.getNChannels(),
                imp.getNFrames(),
                typeStr,
                calibStr,
                imp.getStackSize() > 1,
                imp.isHyperStack()
        );
    }

    /**
     * Escape a value for CSV output.
     */
    private String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
