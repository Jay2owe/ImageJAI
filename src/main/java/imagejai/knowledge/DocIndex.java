package imagejai.knowledge;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Searchable index of ImageJ tips, common problems, and best practices.
 * Provides contextual documentation for RAG-style prompt augmentation.
 */
public class DocIndex {

    public static class DocEntry {
        public String title;
        public String content;
        public List<String> keywords;

        public DocEntry(String title, String content, String[] keywords) {
            this.title = title;
            this.content = content;
            this.keywords = Arrays.asList(keywords);
        }
    }

    private final List<DocEntry> entries;

    public DocIndex() {
        entries = new ArrayList<DocEntry>();
        buildIndex();
    }

    /**
     * Search for relevant docs given a user query using keyword matching.
     */
    public List<DocEntry> search(String query, int maxResults) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        final Set<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return Collections.emptyList();
        }

        List<ScoredEntry> scored = new ArrayList<ScoredEntry>();
        for (DocEntry entry : entries) {
            int score = scoreEntry(entry, tokens);
            if (score > 0) {
                scored.add(new ScoredEntry(entry, score));
            }
        }

        Collections.sort(scored, new Comparator<ScoredEntry>() {
            public int compare(ScoredEntry a, ScoredEntry b) {
                return Integer.compare(b.score, a.score);
            }
        });

        List<DocEntry> results = new ArrayList<DocEntry>();
        int limit = Math.min(maxResults, scored.size());
        for (int i = 0; i < limit; i++) {
            results.add(scored.get(i).entry);
        }
        return results;
    }

    /**
     * Format search results for injection into LLM prompt.
     */
    public String formatForPrompt(List<DocEntry> matchedEntries) {
        StringBuilder sb = new StringBuilder();
        for (DocEntry e : matchedEntries) {
            sb.append("## ").append(e.title).append("\n");
            sb.append(e.content).append("\n\n");
        }
        return sb.toString();
    }

    // --- Private helpers ---

    private static class ScoredEntry {
        final DocEntry entry;
        final int score;
        ScoredEntry(DocEntry entry, int score) {
            this.entry = entry;
            this.score = score;
        }
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<String>();
        String lower = text.toLowerCase(Locale.ROOT);
        String[] parts = lower.split("[^a-z0-9]+");
        for (String part : parts) {
            if (part.length() >= 2) {
                tokens.add(part);
            }
        }
        return tokens;
    }

    private int scoreEntry(DocEntry entry, Set<String> queryTokens) {
        Set<String> entryTokens = new HashSet<String>();
        entryTokens.addAll(tokenize(entry.title));
        entryTokens.addAll(tokenize(entry.content));
        for (String kw : entry.keywords) {
            entryTokens.addAll(tokenize(kw));
        }

        int score = 0;
        for (String qt : queryTokens) {
            if (entryTokens.contains(qt)) {
                score += 2;
                continue;
            }
            for (String et : entryTokens) {
                if (et.contains(qt) || qt.contains(et)) {
                    score += 1;
                    break;
                }
            }
        }
        return score;
    }

    private void buildIndex() {
        // === Common Errors and Solutions ===
        add("Error: No image open",
                "This error occurs when a macro command requires an active image but none is open. "
                + "Always check that an image is open before processing. Use nImages to check: "
                + "if (nImages == 0) { exit(\"No image open\"); }",
                new String[]{"error", "no", "image", "open", "none"});

        add("Error: Not a binary image",
                "Commands like Analyze Particles and Watershed require a binary (black and white) image. "
                + "First threshold the image with setAutoThreshold(), then run Convert to Mask. "
                + "Binary images have only 0 and 255 pixel values.",
                new String[]{"error", "binary", "image", "mask", "threshold", "analyze", "particles"});

        add("Error: Selection required",
                "Some operations require a selection (ROI) on the image. Use makeRectangle(), makeOval(), "
                + "or draw a selection manually. Check with selectionType() which returns -1 if no selection exists.",
                new String[]{"error", "selection", "required", "roi", "none"});

        add("Error: Image is not a stack",
                "Stack operations like Z Project require a stack (multiple slices). Single-slice images "
                + "cannot be projected. Check with nSlices: if (nSlices == 1) the image is not a stack.",
                new String[]{"error", "stack", "slices", "project", "single"});

        add("Error: Wrong image type for operation",
                "Some operations only work on specific image types. For example, some filters need 8-bit or "
                + "16-bit images, not RGB. Convert with run(\"8-bit\") or run(\"16-bit\") before processing. "
                + "Check type with bitDepth() which returns 8, 16, 24 (RGB), or 32.",
                new String[]{"error", "type", "bit", "depth", "rgb", "convert", "grayscale"});

        // === Best Practices ===
        add("Always duplicate before processing",
                "Before applying destructive operations (filters, threshold, math), duplicate your image first "
                + "with run(\"Duplicate...\", \"title=working_copy\"). This preserves the original for comparison "
                + "and re-analysis. This is especially important for threshold and Convert to Mask which destroy "
                + "intensity information.",
                new String[]{"duplicate", "copy", "preserve", "original", "best", "practice", "workflow"});

        add("Set scale before measuring",
                "Measurements are only meaningful with proper calibration. Use Analyze > Set Scale or "
                + "run(\"Set Scale...\", \"distance=X known=Y unit=um\") before any measurements. "
                + "Without calibration, all measurements are in pixels. Check calibration with getPixelSize(unit, pw, ph).",
                new String[]{"scale", "calibration", "measure", "pixel", "micron", "unit", "size"});

        add("Save as TIFF for analysis, not JPEG",
                "JPEG compression introduces artifacts and destroys intensity data. Always use TIFF or PNG for "
                + "quantitative analysis. JPEG is acceptable only for display/publication figures. "
                + "Use saveAs(\"Tiff\", path) for analysis images.",
                new String[]{"save", "tiff", "jpeg", "jpg", "compression", "format", "artifact", "quality"});

        add("Use batch mode for speed",
                "When processing many images, wrap your loop in setBatchMode(true/false). This suppresses "
                + "display updates and dramatically speeds up processing (often 10-100x faster). "
                + "Remember to call setBatchMode(false) at the end to restore display.",
                new String[]{"batch", "mode", "speed", "fast", "performance", "loop", "multiple"});

        // === Measurement Tips ===
        add("CTCF formula for fluorescence quantification",
                "Corrected Total Cell Fluorescence (CTCF) = Integrated Density - (Cell Area * Mean Background). "
                + "This corrects for autofluorescence and background signal. Steps: "
                + "1) Measure IntDen and Area for each cell ROI. "
                + "2) Measure Mean intensity of a background ROI (region with no cells). "
                + "3) CTCF = IntDen - (Area * MeanBackground). "
                + "Always use multiple background ROIs and average them for accuracy.",
                new String[]{"ctcf", "corrected", "total", "cell", "fluorescence", "integrated", "density",
                        "background", "quantification"});

        add("Background correction for intensity measurements",
                "For accurate intensity measurements, always subtract background. Methods: "
                + "1) Measure background ROI mean and subtract from measurements (CTCF method). "
                + "2) Use Subtract Background with rolling ball before measurement. "
                + "3) Use imageCalculator to subtract a background image. "
                + "Choose the method appropriate for your illumination pattern.",
                new String[]{"background", "correction", "subtract", "intensity", "measure", "illumination"});

        add("Per-cell normalization",
                "When comparing fluorescence between cells, normalize to cell area (Mean intensity, not IntDen) "
                + "or use CTCF. Raw IntDen scales with cell size, so larger cells appear brighter even at "
                + "the same concentration. For comparing across experiments, include a calibration standard.",
                new String[]{"normalize", "cell", "area", "mean", "intensity", "compare", "intden"});

        add("Do not measure intensity from max projections",
                "Maximum intensity projections show the brightest voxel at each XY position. This overestimates "
                + "true fluorescence intensity. For quantitative intensity measurements, use Sum projections "
                + "or measure individual slices. Max projections are fine for visualization and counting but not "
                + "for intensity quantification.",
                new String[]{"max", "projection", "intensity", "measure", "quantification", "sum", "overestimate"});

        // === Segmentation Advice ===
        add("When to use Watershed segmentation",
                "Watershed separates touching round objects in binary images. Use it when: "
                + "1) Objects are roughly circular (cells, nuclei). "
                + "2) Objects overlap slightly but have visible boundaries. "
                + "Do NOT use when: objects are very elongated, heavily overlapping, or irregular. "
                + "For complex segmentation, consider trainable classifiers (Weka, StarDist, Cellpose).",
                new String[]{"watershed", "segment", "touching", "separate", "cells", "nuclei", "overlap"});

        add("When to use StarDist or Cellpose",
                "Deep learning segmentation tools like StarDist (for nuclei) and Cellpose (for cells) are "
                + "preferred over classical thresholding when: "
                + "1) Cells are tightly packed or overlapping. "
                + "2) Intensity is uneven across the image. "
                + "3) Classical threshold + watershed gives poor results. "
                + "Both are available as Fiji plugins. StarDist works best on round nuclei; "
                + "Cellpose handles irregular cell shapes.",
                new String[]{"stardist", "cellpose", "deep", "learning", "segment", "nuclei", "cell", "ai"});

        add("Choosing a threshold method",
                "Different threshold methods suit different images: "
                + "- Otsu: Good general-purpose method, works well when histogram is bimodal. "
                + "- Triangle: Good for images where foreground is small relative to background. "
                + "- Huang: Good for fuzzy edges and low contrast. "
                + "- Li: Minimizes cross-entropy, good for fluorescence microscopy. "
                + "- Yen: Good for multi-level thresholding situations. "
                + "Try Auto Threshold with 'Try all' to compare methods visually.",
                new String[]{"threshold", "method", "otsu", "triangle", "huang", "li", "yen", "choose", "select"});

        // === Stack Handling ===
        add("Z-project before threshold for 3D stacks",
                "For 3D fluorescence stacks, create a Z-projection before thresholding unless you need "
                + "slice-by-slice analysis. Max Intensity projection for visualization, Sum or Mean for "
                + "quantification. Thresholding a stack directly applies the same threshold to all slices, "
                + "which may not be appropriate if intensity varies across Z.",
                new String[]{"project", "stack", "threshold", "slice", "fluorescence"});

        add("Hyperstack navigation and processing",
                "Hyperstacks have multiple dimensions (channels, slices, frames). To process a specific channel: "
                + "Stack.setChannel(n) then run your operation, or use Split Channels to separate. "
                + "For time series: iterate over frames with a for loop using Stack.setFrame(). "
                + "Use getDimensions(w,h,ch,sl,fr) to query hyperstack structure.",
                new String[]{"hyperstack", "channel", "frame", "time", "navigate", "dimension", "timelapse"});

        // === Calibration ===
        add("Importance of pixel size calibration",
                "Without proper calibration, all measurements (area, perimeter, length) are in pixel units. "
                + "Set scale using: Analyze > Set Scale, or run(\"Set Scale...\"). "
                + "Many file formats (ND2, LIF, CZI) contain calibration metadata that ImageJ reads automatically. "
                + "Verify with getPixelSize(unit, pw, ph). If unit is 'pixel' or 'pixels', the image is uncalibrated.",
                new String[]{"calibration", "pixel", "size", "scale", "micron", "unit", "measurement"});

        // === File Format Tips ===
        add("Bio-Formats for proprietary microscope files",
                "Fiji's Bio-Formats plugin reads proprietary formats: .nd2 (Nikon), .lif (Leica), .czi (Zeiss), "
                + ".oib/.oif (Olympus). Use: run(\"Bio-Formats Importer\", \"open=/path/to/file\") or just "
                + "drag and drop. Bio-Formats preserves metadata including calibration, channel info, and timestamps.",
                new String[]{"bioformats", "format", "nd2", "lif", "czi", "nikon", "leica", "zeiss", "open", "import"});

        add("Image sequence import and export",
                "To open a numbered series of images as a stack: File > Import > Image Sequence, or "
                + "run(\"Image Sequence...\", \"open=/path/to/folder/ sort\"). "
                + "To save a stack as numbered files: run(\"Image Sequence... \", \"format=TIFF save=/path/\"). "
                + "Ensure files are named with consistent numbering (e.g., img_001.tif, img_002.tif).",
                new String[]{"sequence", "series", "numbered", "import", "folder", "stack", "timelapse"});

        // === Batch Processing ===
        add("Batch processing patterns",
                "Standard batch processing pattern: "
                + "1) Get input directory with getDirectory(). "
                + "2) Create output directory with File.makeDirectory(). "
                + "3) Get file list with getFileList(). "
                + "4) Enable setBatchMode(true). "
                + "5) Loop through files, filter by extension, open, process, save, close. "
                + "6) Disable setBatchMode(false). "
                + "Always close each image after saving to avoid memory issues.",
                new String[]{"batch", "process", "folder", "loop", "automate", "multiple", "workflow"});

        add("Macro recording for automation",
                "Use Plugins > Macros > Record to capture your manual operations as macro code. "
                + "The recorder shows the exact commands and parameters for each operation. "
                + "This is the easiest way to learn macro syntax for any ImageJ function.",
                new String[]{"record", "macro", "automate", "learn", "syntax", "command"});

        // === Neuroscience-Specific ===
        add("Cell counting in fluorescence images",
                "For counting fluorescent cells (e.g., DAPI-stained nuclei): "
                + "1) Duplicate the channel of interest. "
                + "2) Apply Gaussian blur (sigma 1-2) to reduce noise. "
                + "3) Auto threshold (Otsu or Li for fluorescence). "
                + "4) Convert to Mask. "
                + "5) Watershed to separate touching nuclei. "
                + "6) Analyze Particles with appropriate size filter (e.g., 50-5000). "
                + "For dense samples, consider StarDist instead.",
                new String[]{"count", "cells", "nuclei", "dapi", "fluorescence", "detect", "neuroscience"});

        add("Fluorescence colocalization analysis",
                "To assess colocalization between two channels: "
                + "1) Split channels. "
                + "2) Set threshold for each channel independently. "
                + "3) Use Coloc 2 plugin (Analyze > Colocalization > Coloc 2) for proper statistics. "
                + "4) Report Pearson's R, Manders M1/M2, or Costes' thresholded overlap. "
                + "Avoid visual overlap assessment alone — it is unreliable due to brightness/display settings.",
                new String[]{"colocalization", "coloc", "overlap", "channels", "pearson", "manders", "costes"});

        add("Neurite tracing and measurement",
                "For tracing neurites or dendrites: "
                + "1) Simple Neurite Tracer (SNT) plugin for manual/semi-auto tracing. "
                + "2) Skeletonize binary image for automated length measurement. "
                + "3) AnalyzeSkeleton plugin provides branch length and junction counts. "
                + "Process: threshold, skeletonize, run(\"Analyze Skeleton (2D/3D)\").",
                new String[]{"neurite", "trace", "dendrite", "skeleton", "branch", "length", "neuroscience"});

        add("Fluorescence quantification per region",
                "To measure fluorescence in specific brain regions or tissue areas: "
                + "1) Draw ROIs around regions of interest (freehand or polygon). "
                + "2) Add each to ROI Manager with a descriptive name. "
                + "3) Set Measurements to include Mean, IntDen, Area. "
                + "4) Multi Measure: roiManager(\"Multi Measure\"). "
                + "5) Apply background correction (CTCF or subtract background ROI). "
                + "6) Normalize to area for fair comparison between regions.",
                new String[]{"fluorescence", "region", "brain", "tissue", "quantify", "roi", "area"});

        // === Statistical Considerations ===
        add("Statistical considerations for image analysis",
                "Key statistical points: "
                + "1) N = number of biological replicates, NOT number of cells (cells from one animal = technical replicates). "
                + "2) Average measurements per image/animal before statistical testing. "
                + "3) Report variability (SD or SEM) and sample size. "
                + "4) Blind the analysis: use coded filenames so the analyst does not know group identity. "
                + "5) Use consistent parameters (threshold method, size filters) across all conditions.",
                new String[]{"statistics", "replicate", "sample", "size", "variability", "bias", "blind"});

        // === Common Workflow Pitfalls ===
        add("Avoid measuring from RGB images",
                "RGB images combine all channels into one. For quantitative analysis of multi-channel "
                + "fluorescence, work with the original composite/hyperstack and split channels. "
                + "Converting to RGB and back loses channel separation and introduces color mixing artifacts. "
                + "Use run(\"Split Channels\") on the original composite.",
                new String[]{"rgb", "composite", "channels", "measure", "fluorescence", "convert", "artifact"});

        add("Memory management for large datasets",
                "ImageJ runs in limited JVM memory. For large datasets: "
                + "1) Use setBatchMode(true) to avoid display overhead. "
                + "2) Close images after processing: close(). "
                + "3) Use Virtual Stacks for very large files (File > Import > Virtual Stack). "
                + "4) Increase memory: Edit > Options > Memory & Threads. "
                + "5) Process images sequentially rather than opening all at once.",
                new String[]{"memory", "large", "dataset", "virtual", "stack", "performance", "heap", "ram"});

        add("Reproducible analysis workflow",
                "For reproducible results: "
                + "1) Record all macro commands or write a macro script. "
                + "2) Use the same parameters for all images in an experiment. "
                + "3) Document threshold methods, filter parameters, and size cutoffs. "
                + "4) Save ROIs with roiManager(\"Save\", path) for reanalysis. "
                + "5) Save the macro file alongside the data.",
                new String[]{"reproducible", "workflow", "record", "parameters", "consistent", "document"});

        add("Working with time-lapse data",
                "For time-lapse analysis: "
                + "1) Use Stack.setFrame(n) to navigate frames. "
                + "2) Track objects with TrackMate plugin for automated tracking. "
                + "3) For intensity over time: draw ROI, then Analyze > Plot Z-axis Profile. "
                + "4) Correct for photobleaching if measuring intensity across time. "
                + "5) Register images if there is drift (Plugins > Registration).",
                new String[]{"timelapse", "time", "lapse", "track", "frame", "bleach", "drift", "temporal"});
    }

    private void add(String title, String content, String[] keywords) {
        entries.add(new DocEntry(title, content, keywords));
    }
}
