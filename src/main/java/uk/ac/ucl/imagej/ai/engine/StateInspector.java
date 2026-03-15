package uk.ac.ucl.imagej.ai.engine;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.measure.Calibration;
import ij.measure.ResultsTable;
import ij.plugin.frame.RoiManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Queries current ImageJ state for context injection into LLM prompts.
 */
public class StateInspector {

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
        for (int id : ids) {
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
        String[] names = rm.getList().getItems();
        int limit = Math.min(names.length, 20);
        for (int i = 0; i < limit; i++) {
            sb.append("  ").append(i + 1).append(". ").append(names[i]).append("\n");
        }
        if (names.length > 20) {
            sb.append("  ... and ").append(names.length - 20).append(" more\n");
        }
        return sb.toString();
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
