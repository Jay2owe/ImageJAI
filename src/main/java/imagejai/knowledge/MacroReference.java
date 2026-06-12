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
 * Indexed, searchable reference of ImageJ macro functions and common recipes.
 * Provides keyword-based search for RAG-style prompt augmentation.
 */
public class MacroReference {

    public static class MacroEntry {
        public String name;
        public String syntax;
        public String description;
        public String example;
        public String category;
        public List<String> keywords;

        public MacroEntry(String name, String syntax, String description,
                          String example, String category, String[] keywords) {
            this.name = name;
            this.syntax = syntax;
            this.description = description;
            this.example = example;
            this.category = category;
            this.keywords = Arrays.asList(keywords);
        }
    }

    private final List<MacroEntry> entries;

    public MacroReference() {
        entries = new ArrayList<MacroEntry>();
        buildIndex();
    }

    /**
     * Search for relevant entries given a user query using keyword matching.
     * Tokenizes the query and scores each entry by token overlap with
     * name, description, and keywords.
     */
    public List<MacroEntry> search(String query, int maxResults) {
        if (query == null || query.trim().isEmpty()) {
            return Collections.emptyList();
        }
        final Set<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return Collections.emptyList();
        }

        List<ScoredEntry> scored = new ArrayList<ScoredEntry>();
        for (MacroEntry entry : entries) {
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

        List<MacroEntry> results = new ArrayList<MacroEntry>();
        int limit = Math.min(maxResults, scored.size());
        for (int i = 0; i < limit; i++) {
            results.add(scored.get(i).entry);
        }
        return results;
    }

    /**
     * Get entries by category name (case-insensitive).
     */
    public List<MacroEntry> getByCategory(String category) {
        List<MacroEntry> results = new ArrayList<MacroEntry>();
        if (category == null) {
            return results;
        }
        String lower = category.toLowerCase(Locale.ROOT);
        for (MacroEntry entry : entries) {
            if (entry.category.toLowerCase(Locale.ROOT).equals(lower)) {
                results.add(entry);
            }
        }
        return results;
    }

    /**
     * Format search results for injection into LLM prompt.
     */
    public String formatForPrompt(List<MacroEntry> matchedEntries) {
        StringBuilder sb = new StringBuilder();
        for (MacroEntry e : matchedEntries) {
            sb.append("- ").append(e.name).append(": ").append(e.description).append("\n");
            sb.append("  Syntax: ").append(e.syntax).append("\n");
            if (e.example != null && !e.example.isEmpty()) {
                sb.append("  Example: ").append(e.example).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Get all distinct categories.
     */
    public List<String> getCategories() {
        Set<String> cats = new HashSet<String>();
        for (MacroEntry entry : entries) {
            cats.add(entry.category);
        }
        List<String> result = new ArrayList<String>(cats);
        Collections.sort(result);
        return result;
    }

    // --- Private helpers ---

    private static class ScoredEntry {
        final MacroEntry entry;
        final int score;
        ScoredEntry(MacroEntry entry, int score) {
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

    private int scoreEntry(MacroEntry entry, Set<String> queryTokens) {
        Set<String> entryTokens = new HashSet<String>();
        entryTokens.addAll(tokenize(entry.name));
        entryTokens.addAll(tokenize(entry.description));
        entryTokens.addAll(tokenize(entry.category));
        for (String kw : entry.keywords) {
            entryTokens.addAll(tokenize(kw));
        }

        int score = 0;
        for (String qt : queryTokens) {
            // Exact match in entry tokens
            if (entryTokens.contains(qt)) {
                score += 2;
                continue;
            }
            // Partial / substring match
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
        // === Image Operations ===
        add("open", "open(path)", "Open an image file from disk",
                "open(\"/path/to/image.tif\");", "image",
                new String[]{"open", "load", "file", "read", "import"});
        add("close", "close()", "Close the active image window",
                "close();", "image",
                new String[]{"close", "window", "shut"});
        add("close pattern", "close(pattern)", "Close windows matching a name pattern",
                "close(\"*\");", "image",
                new String[]{"close", "all", "windows", "pattern"});
        add("save", "save(path)", "Save the active image to a file",
                "save(\"/path/to/output.tif\");", "image",
                new String[]{"save", "write", "export", "file"});
        add("saveAs", "saveAs(format, path)", "Save image in a specific format",
                "saveAs(\"Tiff\", \"/path/to/output.tif\");", "image",
                new String[]{"save", "tiff", "png", "jpeg", "format", "export"});
        add("duplicate", "run(\"Duplicate...\", \"title=name\")", "Duplicate the active image",
                "run(\"Duplicate...\", \"title=copy\");", "image",
                new String[]{"duplicate", "copy", "clone"});
        add("rename", "rename(name)", "Rename the active image window",
                "rename(\"processed\");", "image",
                new String[]{"rename", "title", "name"});
        add("newImage", "newImage(title, type, width, height, depth)", "Create a new blank image",
                "newImage(\"blank\", \"8-bit black\", 512, 512, 1);", "image",
                new String[]{"new", "create", "blank", "image"});
        add("selectWindow", "selectWindow(title)", "Select (bring to front) a window by title",
                "selectWindow(\"blobs.gif\");", "image",
                new String[]{"select", "window", "activate", "focus", "switch"});
        add("selectImage", "selectImage(id)", "Select an image by its numeric ID",
                "selectImage(1);", "image",
                new String[]{"select", "image", "id"});
        add("getTitle", "getTitle()", "Get the title of the active image",
                "title = getTitle();", "image",
                new String[]{"title", "name", "get"});
        add("getImageID", "getImageID()", "Get the numeric ID of the active image",
                "id = getImageID();", "image",
                new String[]{"id", "image", "get"});
        add("getWidth", "getWidth()", "Get image width in pixels",
                "w = getWidth();", "image",
                new String[]{"width", "size", "dimension"});
        add("getHeight", "getHeight()", "Get image height in pixels",
                "h = getHeight();", "image",
                new String[]{"height", "size", "dimension"});

        // === Filters ===
        add("Gaussian Blur", "run(\"Gaussian Blur...\", \"sigma=N\")", "Apply Gaussian blur filter to smooth image",
                "run(\"Gaussian Blur...\", \"sigma=2\");", "filter",
                new String[]{"gaussian", "blur", "smooth", "filter", "denoise"});
        add("Median", "run(\"Median...\", \"radius=N\")", "Apply median filter for salt-and-pepper noise removal",
                "run(\"Median...\", \"radius=2\");", "filter",
                new String[]{"median", "filter", "denoise", "noise", "smooth"});
        add("Unsharp Mask", "run(\"Unsharp Mask...\", \"radius=N mask=M\")", "Sharpen image using unsharp mask",
                "run(\"Unsharp Mask...\", \"radius=3 mask=0.6\");", "filter",
                new String[]{"unsharp", "sharpen", "enhance", "detail"});
        add("Subtract Background", "run(\"Subtract Background...\", \"rolling=N\")", "Remove uneven background illumination",
                "run(\"Subtract Background...\", \"rolling=50\");", "filter",
                new String[]{"background", "subtract", "rolling", "ball", "illumination", "uneven"});
        add("Enhance Contrast", "run(\"Enhance Contrast...\", \"saturated=N\")", "Enhance image contrast by stretching histogram",
                "run(\"Enhance Contrast...\", \"saturated=0.35\");", "filter",
                new String[]{"contrast", "enhance", "histogram", "stretch", "brightness"});
        add("Smooth", "run(\"Smooth\")", "Apply 3x3 mean filter",
                "run(\"Smooth\");", "filter",
                new String[]{"smooth", "mean", "filter", "average"});
        add("Sharpen", "run(\"Sharpen\")", "Apply 3x3 sharpening filter",
                "run(\"Sharpen\");", "filter",
                new String[]{"sharpen", "filter", "edge"});
        add("Minimum", "run(\"Minimum...\", \"radius=N\")", "Morphological erosion (minimum filter)",
                "run(\"Minimum...\", \"radius=1\");", "filter",
                new String[]{"minimum", "erosion", "morphology", "shrink"});
        add("Maximum", "run(\"Maximum...\", \"radius=N\")", "Morphological dilation (maximum filter)",
                "run(\"Maximum...\", \"radius=1\");", "filter",
                new String[]{"maximum", "dilation", "morphology", "dilate", "grow"});

        // === Threshold ===
        add("setAutoThreshold", "setAutoThreshold(method)", "Apply automatic threshold using named method",
                "setAutoThreshold(\"Otsu\");", "threshold",
                new String[]{"threshold", "auto", "otsu", "segment", "binary"});
        add("setThreshold", "setThreshold(lower, upper)", "Set manual threshold range",
                "setThreshold(50, 255);", "threshold",
                new String[]{"threshold", "manual", "range", "set"});
        add("Convert to Mask", "run(\"Convert to Mask\")", "Convert thresholded image to binary mask",
                "setAutoThreshold(\"Otsu\");\nrun(\"Convert to Mask\");", "threshold",
                new String[]{"mask", "binary", "convert", "threshold", "segment"});
        add("Auto Threshold methods", "run(\"Auto Threshold\", \"method=X\")", "Apply auto threshold with specific method (Otsu, Huang, Triangle, etc.)",
                "run(\"Auto Threshold\", \"method=Otsu white\");", "threshold",
                new String[]{"threshold", "otsu", "huang", "triangle", "li", "yen", "isodata", "method"});
        add("resetThreshold", "resetThreshold()", "Remove threshold from image",
                "resetThreshold();", "threshold",
                new String[]{"threshold", "reset", "remove", "clear"});
        add("Watershed", "run(\"Watershed\")", "Separate touching binary objects using watershed segmentation",
                "run(\"Watershed\");", "threshold",
                new String[]{"watershed", "separate", "touching", "segment", "split", "binary"});
        add("Fill Holes", "run(\"Fill Holes\")", "Fill holes in binary objects",
                "run(\"Fill Holes\");", "threshold",
                new String[]{"fill", "holes", "binary", "mask"});

        // === Analysis ===
        add("Measure", "run(\"Measure\")", "Measure properties of current selection or whole image",
                "run(\"Measure\");", "analysis",
                new String[]{"measure", "area", "mean", "intensity", "results"});
        add("Analyze Particles", "run(\"Analyze Particles...\", \"options\")", "Find and measure objects in binary image",
                "run(\"Analyze Particles...\", \"size=50-Infinity circularity=0.5-1.0 show=Outlines summarize\");", "analysis",
                new String[]{"analyze", "particles", "count", "objects", "size", "circularity", "cell"});
        add("Summarize", "run(\"Summarize\")", "Add summary statistics to Results table",
                "run(\"Summarize\");", "analysis",
                new String[]{"summarize", "summary", "statistics", "mean", "results"});
        add("getResult", "getResult(column, row)", "Get a value from the Results table",
                "area = getResult(\"Area\", 0);", "analysis",
                new String[]{"result", "get", "table", "value", "read"});
        add("nResults", "nResults", "Get number of rows in the Results table",
                "n = nResults;", "analysis",
                new String[]{"results", "count", "number", "rows"});
        add("setResult", "setResult(column, row, value)", "Set a value in the Results table",
                "setResult(\"CTCF\", 0, intDen - (area * meanBg));", "analysis",
                new String[]{"result", "set", "table", "write", "add"});
        add("updateResults", "updateResults()", "Refresh the Results table display",
                "updateResults();", "analysis",
                new String[]{"results", "update", "refresh", "table"});
        add("Set Measurements", "run(\"Set Measurements...\", \"options\")", "Configure which measurements to include",
                "run(\"Set Measurements...\", \"area mean integrated limit display redirect=None decimal=3\");", "analysis",
                new String[]{"measurements", "set", "configure", "area", "mean", "integrated", "density"});
        add("Set Scale", "run(\"Set Scale...\", \"options\")", "Set pixel-to-physical unit conversion",
                "run(\"Set Scale...\", \"distance=100 known=10 unit=um\");", "analysis",
                new String[]{"scale", "calibration", "pixel", "micron", "um", "unit"});
        add("getStatistics", "getStatistics(area, mean, min, max, std, histogram)", "Get basic statistics for current selection",
                "getStatistics(area, mean, min, max);", "analysis",
                new String[]{"statistics", "mean", "min", "max", "std", "area"});
        add("getRawStatistics", "getRawStatistics(nPixels, mean, min, max, std, histogram)", "Get uncalibrated statistics",
                "getRawStatistics(n, mean, min, max);", "analysis",
                new String[]{"statistics", "raw", "uncalibrated", "pixels"});

        // === ROI ===
        add("makeRectangle", "makeRectangle(x, y, width, height)", "Create a rectangular selection",
                "makeRectangle(10, 10, 100, 100);", "roi",
                new String[]{"rectangle", "selection", "roi", "make", "box"});
        add("makeOval", "makeOval(x, y, width, height)", "Create an oval/elliptical selection",
                "makeOval(10, 10, 50, 50);", "roi",
                new String[]{"oval", "ellipse", "circle", "selection", "roi"});
        add("makePolygon", "makePolygon(x1,y1,...)", "Create a polygon selection from coordinate pairs",
                "makePolygon(10,10, 100,10, 100,100, 10,100);", "roi",
                new String[]{"polygon", "selection", "roi", "coordinates", "vertices"});
        add("makeLine", "makeLine(x1, y1, x2, y2)", "Create a straight line selection",
                "makeLine(10, 10, 100, 100);", "roi",
                new String[]{"line", "selection", "roi", "profile", "distance"});
        add("roiManager Add", "roiManager(\"Add\")", "Add current selection to the ROI Manager",
                "roiManager(\"Add\");", "roi",
                new String[]{"roi", "manager", "add", "store", "save"});
        add("roiManager Select", "roiManager(\"Select\", index)", "Select an ROI from the manager by index",
                "roiManager(\"Select\", 0);", "roi",
                new String[]{"roi", "manager", "select", "choose"});
        add("roiManager Delete", "roiManager(\"Delete\")", "Delete selected ROIs from manager",
                "roiManager(\"Delete\");", "roi",
                new String[]{"roi", "manager", "delete", "remove"});
        add("roiManager Measure", "roiManager(\"Measure\")", "Measure all ROIs in the manager",
                "roiManager(\"Measure\");", "roi",
                new String[]{"roi", "manager", "measure", "all", "batch"});
        add("roiManager Count", "roiManager(\"Count\")", "Get number of ROIs in the manager",
                "n = roiManager(\"Count\");", "roi",
                new String[]{"roi", "manager", "count", "number"});
        add("Roi.getName", "Roi.getName", "Get the name of the current ROI",
                "name = Roi.getName;", "roi",
                new String[]{"roi", "name", "get", "label"});
        add("setSelectionName", "setSelectionName(name)", "Set the name of the current selection",
                "setSelectionName(\"cell_1\");", "roi",
                new String[]{"selection", "name", "set", "label", "roi"});
        add("run Select None", "run(\"Select None\")", "Remove the current selection",
                "run(\"Select None\");", "roi",
                new String[]{"selection", "deselect", "none", "remove", "clear"});

        // === Stack/Hyperstack ===
        add("Z Project", "run(\"Z Project...\", \"projection=[Method]\")", "Create Z-projection from stack (Max, Mean, Sum, etc.)",
                "run(\"Z Project...\", \"projection=[Max Intensity]\");", "stack",
                new String[]{"project", "projection", "max", "mean", "sum", "stack", "slice"});
        add("Stack.setSlice", "Stack.setSlice(n)", "Go to a specific slice in a stack",
                "Stack.setSlice(5);", "stack",
                new String[]{"stack", "slice", "set", "goto", "navigate"});
        add("Stack.setChannel", "Stack.setChannel(n)", "Set active channel in hyperstack",
                "Stack.setChannel(2);", "stack",
                new String[]{"channel", "set", "hyperstack", "switch"});
        add("Stack.setFrame", "Stack.setFrame(n)", "Set active time frame in hyperstack",
                "Stack.setFrame(10);", "stack",
                new String[]{"frame", "time", "set", "hyperstack", "timelapse"});
        add("nSlices", "nSlices", "Get total number of slices in stack",
                "n = nSlices;", "stack",
                new String[]{"slices", "count", "stack", "number"});
        add("Split Channels", "run(\"Split Channels\")", "Split a multi-channel image into separate windows",
                "run(\"Split Channels\");", "stack",
                new String[]{"split", "channels", "separate", "multi"});
        add("Merge Channels", "run(\"Merge Channels...\", \"options\")", "Merge separate images into a multi-channel composite",
                "run(\"Merge Channels...\", \"c1=C1-image c2=C2-image create\");", "stack",
                new String[]{"merge", "channels", "composite", "combine", "overlay"});
        add("Stack to Images", "run(\"Stack to Images\")", "Convert stack to individual image windows",
                "run(\"Stack to Images\");", "stack",
                new String[]{"stack", "images", "convert", "split", "individual"});
        add("Images to Stack", "run(\"Images to Stack\")", "Combine open images into a stack",
                "run(\"Images to Stack\");", "stack",
                new String[]{"images", "stack", "combine", "join"});
        add("getDimensions", "getDimensions(width, height, channels, slices, frames)", "Get full hyperstack dimensions",
                "getDimensions(w, h, ch, sl, fr);", "stack",
                new String[]{"dimensions", "channels", "slices", "frames", "size", "hyperstack"});

        // === Math/Process ===
        add("Subtract value", "run(\"Subtract...\", \"value=X\")", "Subtract a constant value from all pixels",
                "run(\"Subtract...\", \"value=50\");", "math",
                new String[]{"subtract", "value", "math", "background"});
        add("Add value", "run(\"Add...\", \"value=X\")", "Add a constant value to all pixels",
                "run(\"Add...\", \"value=25\");", "math",
                new String[]{"add", "value", "math", "brighten"});
        add("Multiply value", "run(\"Multiply...\", \"value=X\")", "Multiply all pixels by a constant",
                "run(\"Multiply...\", \"value=1.5\");", "math",
                new String[]{"multiply", "value", "math", "scale"});
        add("imageCalculator", "imageCalculator(operation, img1, img2)", "Perform pixel-by-pixel operation between two images",
                "imageCalculator(\"Subtract create\", \"image1\", \"image2\");", "math",
                new String[]{"calculator", "image", "subtract", "add", "multiply", "divide", "and", "or"});
        add("Invert", "run(\"Invert\")", "Invert pixel values (bright becomes dark)",
                "run(\"Invert\");", "math",
                new String[]{"invert", "negative", "reverse"});
        add("Log transform", "run(\"Log\")", "Apply log transform to pixel values",
                "run(\"Log\");", "math",
                new String[]{"log", "transform", "math", "dynamic", "range"});

        // === Color/LUT ===
        add("8-bit", "run(\"8-bit\")", "Convert image to 8-bit grayscale",
                "run(\"8-bit\");", "color",
                new String[]{"8bit", "grayscale", "convert", "bit", "depth"});
        add("16-bit", "run(\"16-bit\")", "Convert image to 16-bit grayscale",
                "run(\"16-bit\");", "color",
                new String[]{"16bit", "convert", "bit", "depth"});
        add("RGB Color", "run(\"RGB Color\")", "Convert image to RGB color",
                "run(\"RGB Color\");", "color",
                new String[]{"rgb", "color", "convert"});
        add("changeLUT", "run(\"LUT_name\")", "Apply a lookup table (Fire, Green, Grays, etc.)",
                "run(\"Fire\");", "color",
                new String[]{"lut", "lookup", "color", "fire", "green", "grays", "pseudocolor"});
        add("setMinAndMax", "setMinAndMax(min, max)", "Adjust display range (brightness/contrast)",
                "setMinAndMax(0, 200);", "color",
                new String[]{"display", "range", "brightness", "contrast", "min", "max"});
        add("resetMinAndMax", "resetMinAndMax()", "Reset display range to full data range",
                "resetMinAndMax();", "color",
                new String[]{"display", "reset", "range", "auto"});

        // === Batch/File ===
        add("setBatchMode", "setBatchMode(boolean)", "Enable/disable batch mode (suppresses display for speed)",
                "setBatchMode(true);", "batch",
                new String[]{"batch", "mode", "fast", "speed", "headless", "display"});
        add("getFileList", "getFileList(dir)", "List all files in a directory",
                "list = getFileList(\"/path/to/folder/\");", "batch",
                new String[]{"file", "list", "directory", "folder", "batch"});
        add("File.exists", "File.exists(path)", "Check if a file or directory exists",
                "if (File.exists(\"/path/to/file\")) { ... }", "batch",
                new String[]{"file", "exists", "check", "path"});
        add("File.isDirectory", "File.isDirectory(path)", "Check if path is a directory",
                "if (File.isDirectory(\"/path/\")) { ... }", "batch",
                new String[]{"directory", "folder", "check"});
        add("File.makeDirectory", "File.makeDirectory(path)", "Create a new directory",
                "File.makeDirectory(\"/path/to/output/\");", "batch",
                new String[]{"directory", "create", "make", "folder", "mkdir"});
        add("File.getName", "File.getName(path)", "Get filename from a path",
                "name = File.getName(\"/path/to/image.tif\");", "batch",
                new String[]{"file", "name", "filename", "path"});
        add("getDirectory", "getDirectory(title)", "Prompt user to choose a directory",
                "dir = getDirectory(\"Choose input folder\");", "batch",
                new String[]{"directory", "choose", "dialog", "folder", "browse"});

        // === Drawing ===
        add("setColor", "setColor(r, g, b)", "Set drawing color",
                "setColor(255, 0, 0);", "drawing",
                new String[]{"color", "set", "draw", "red", "green", "blue"});
        add("setLineWidth", "setLineWidth(width)", "Set drawing line width",
                "setLineWidth(2);", "drawing",
                new String[]{"line", "width", "thickness", "draw"});
        add("drawLine", "drawLine(x1, y1, x2, y2)", "Draw a line on the image",
                "drawLine(10, 10, 100, 100);", "drawing",
                new String[]{"draw", "line", "annotation"});
        add("drawRect", "drawRect(x, y, w, h)", "Draw a rectangle outline on the image",
                "drawRect(10, 10, 100, 100);", "drawing",
                new String[]{"draw", "rectangle", "outline", "annotation"});
        add("fillRect", "fillRect(x, y, w, h)", "Draw a filled rectangle on the image",
                "fillRect(10, 10, 100, 100);", "drawing",
                new String[]{"fill", "rectangle", "annotation"});
        add("drawString", "drawString(text, x, y)", "Draw text on the image",
                "drawString(\"Scale bar\", 10, 20);", "drawing",
                new String[]{"draw", "text", "string", "label", "annotation"});
        add("setFont", "setFont(name, size, style)", "Set font for text drawing",
                "setFont(\"SansSerif\", 18, \"bold\");", "drawing",
                new String[]{"font", "text", "size", "style"});

        // === Recipes ===
        add("CTCF measurement", "Corrected Total Cell Fluorescence workflow",
                "Measure integrated density, cell area, and mean background to calculate CTCF = IntDen - (Area * MeanBg). "
                + "Select cell ROI, measure IntDen and Area, select background ROI, measure mean, compute CTCF.",
                "// For each cell:\nroiManager(\"Select\", cellIdx);\nrun(\"Measure\");\n"
                + "intDen = getResult(\"IntDen\", nResults-1);\narea = getResult(\"Area\", nResults-1);\n"
                + "// Measure background mean\nroiManager(\"Select\", bgIdx);\nrun(\"Measure\");\n"
                + "bgMean = getResult(\"Mean\", nResults-1);\nctcf = intDen - (area * bgMean);",
                "recipe",
                new String[]{"ctcf", "corrected", "total", "cell", "fluorescence", "integrated", "density", "background"});
        add("Colocalization analysis", "Measure overlap between two channels",
                "Split channels, threshold each channel independently, use imageCalculator AND to find overlap, "
                + "then measure overlap area vs individual channel areas for Manders coefficients.",
                "run(\"Split Channels\");\nselectWindow(\"C1-image\");\nsetAutoThreshold(\"Otsu\");\n"
                + "run(\"Convert to Mask\");\nselectWindow(\"C2-image\");\nsetAutoThreshold(\"Otsu\");\n"
                + "run(\"Convert to Mask\");\nimageCalculator(\"AND create\", \"C1-image\", \"C2-image\");",
                "recipe",
                new String[]{"colocalization", "overlap", "channels", "manders", "coloc"});
        add("Cell counting", "Count cells using threshold and Analyze Particles",
                "Apply threshold to segment cells, optionally watershed to separate touching cells, "
                + "then use Analyze Particles with size and circularity filters.",
                "run(\"Duplicate...\", \"title=mask\");\nsetAutoThreshold(\"Otsu\");\n"
                + "run(\"Convert to Mask\");\nrun(\"Watershed\");\n"
                + "run(\"Analyze Particles...\", \"size=50-Infinity circularity=0.5-1.0 show=Outlines summarize\");",
                "recipe",
                new String[]{"count", "cells", "particles", "segment", "number", "detect"});
        add("Batch processing", "Process all images in a folder",
                "Use getFileList to iterate over a directory, open each image, process, save, and close. "
                + "Use setBatchMode(true) for speed.",
                "dir = getDirectory(\"Choose folder\");\nlist = getFileList(dir);\n"
                + "setBatchMode(true);\nfor (i = 0; i < list.length; i++) {\n"
                + "  if (endsWith(list[i], \".tif\")) {\n    open(dir + list[i]);\n"
                + "    // process...\n    saveAs(\"Tiff\", dir + \"output/\" + list[i]);\n"
                + "    close();\n  }\n}\nsetBatchMode(false);",
                "recipe",
                new String[]{"batch", "process", "folder", "loop", "multiple", "all", "automate"});
        add("Scale bar", "Add a scale bar to the image",
                "Add a calibrated scale bar overlay. Requires image to have proper scale set.",
                "run(\"Scale Bar...\", \"width=50 height=5 font=18 color=White background=None location=[Lower Right] bold\");",
                "recipe",
                new String[]{"scale", "bar", "calibration", "annotation", "micron"});
        add("Background subtraction workflow", "Correct uneven illumination before analysis",
                "Duplicate image, apply large rolling ball, or use Subtract Background. "
                + "Always process before thresholding for accurate segmentation.",
                "run(\"Duplicate...\", \"title=corrected\");\n"
                + "run(\"Subtract Background...\", \"rolling=50\");",
                "recipe",
                new String[]{"background", "subtract", "illumination", "correct", "uneven", "rolling"});
        add("Fluorescence intensity measurement", "Measure fluorescence per cell",
                "Set measurements to include area, mean, integrated density. "
                + "Draw ROIs around each cell, add to ROI Manager, then Measure all. "
                + "Always subtract background fluorescence.",
                "run(\"Set Measurements...\", \"area mean integrated display redirect=None decimal=3\");\n"
                + "// Add cell ROIs to manager, then:\nroiManager(\"Measure\");",
                "recipe",
                new String[]{"fluorescence", "intensity", "measure", "cell", "mean", "integrated"});
        add("Noise reduction workflow", "Remove noise while preserving features",
                "For Gaussian noise: Gaussian Blur or Median filter. For shot noise: Median filter. "
                + "For background: Subtract Background. Start with small radius and increase.",
                "// Gentle denoising:\nrun(\"Median...\", \"radius=1\");\n"
                + "// Or for Gaussian noise:\nrun(\"Gaussian Blur...\", \"sigma=1\");",
                "recipe",
                new String[]{"noise", "denoise", "reduce", "filter", "clean", "smooth"});
        add("Montage creation", "Create a multi-panel figure from stack or images",
                "Use Make Montage to create publication-ready multi-panel images from stacks.",
                "run(\"Make Montage...\", \"columns=4 rows=3 scale=0.5 border=2\");",
                "recipe",
                new String[]{"montage", "panel", "figure", "grid", "publication"});
        add("Line profile", "Measure intensity along a line",
                "Draw a line selection, then use Plot Profile to see intensity values along the line.",
                "makeLine(10, 50, 200, 50);\nrun(\"Plot Profile\");",
                "recipe",
                new String[]{"profile", "line", "intensity", "plot", "cross", "section"});
    }

    private void add(String name, String syntax, String description,
                     String example, String category, String[] keywords) {
        entries.add(new MacroEntry(name, syntax, description, example, category, keywords));
    }
}
