package imagejai.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import imagejai.knowledge.MacroReference;
import imagejai.knowledge.DocIndex;

/**
 * System prompts and response parsing for the conversation loop.
 * Handles macro extraction from LLM responses and context formatting.
 */
public final class PromptTemplates {

    private PromptTemplates() {}

    private static final Pattern MACRO_PATTERN =
            Pattern.compile("<macro>(.*?)</macro>", Pattern.DOTALL);

    private static final Pattern PIPELINE_PATTERN =
            Pattern.compile("<pipeline>(.*?)</pipeline>", Pattern.DOTALL);

    private static final String SYSTEM_PROMPT =
            "You are an AI assistant for ImageJ/Fiji, a scientific image analysis application.\n"
            + "You help users analyze microscopy images through natural language.\n"
            + "\n"
            + "When the user asks you to perform an image operation, respond with the ImageJ macro code\n"
            + "wrapped in <macro> tags. For example:\n"
            + "<macro>run(\"Gaussian Blur...\", \"sigma=2\");</macro>\n"
            + "\n"
            + "You can include multiple macro blocks in a single response if needed.\n"
            + "\n"
            + "Common ImageJ macro operations:\n"
            + "- Open sample: run(\"Blobs (25K)\");\n"
            + "- Open file: open(\"/path/to/file.tif\");\n"
            + "- Z-project: run(\"Z Project...\", \"projection=[Max Intensity]\");\n"
            + "- Threshold: setAutoThreshold(\"Otsu\"); run(\"Convert to Mask\");\n"
            + "- Measure: run(\"Measure\");\n"
            + "- Analyze particles: run(\"Analyze Particles...\", \"size=50-Infinity show=Outlines summarize\");\n"
            + "- Gaussian blur: run(\"Gaussian Blur...\", \"sigma=2\");\n"
            + "- Duplicate: run(\"Duplicate...\", \"title=copy\");\n"
            + "- Split channels: run(\"Split Channels\");\n"
            + "- Merge channels: run(\"Merge Channels...\", \"c1=C1 c2=C2 create\");\n"
            + "- Set scale: run(\"Set Scale...\", \"distance=1 known=1 unit=um\");\n"
            + "- Save: saveAs(\"Tiff\", \"/path/to/output.tif\");\n"
            + "\n"
            + "IMPORTANT RULES:\n"
            + "1. Always wrap executable macro code in <macro> tags.\n"
            + "2. Explain what you are doing in plain text alongside the macro code.\n"
            + "3. If you need to run multiple steps, use multiple <macro> blocks or combine in one block.\n"
            + "4. If the user asks a question (not an action), just respond conversationally.\n"
            + "5. If an error occurs, analyze it and suggest a fix.\n"
            + "6. When you see the [STATE] context, use it to understand what images are open and their properties.\n"
            + "7. If no image is open and the user wants to process something, ask them to open an image first.\n"
            + "\n"
            + "When the user asks for a multi-step analysis, respond with a <pipeline> block:\n"
            + "<pipeline>\n"
            + "  <step description=\"Step 1 description\">macro code here;</step>\n"
            + "  <step description=\"Step 2 description\">macro code here;</step>\n"
            + "</pipeline>\n"
            + "\n"
            + "Use <pipeline> blocks when:\n"
            + "- The task requires 3 or more sequential operations\n"
            + "- The user says \"pipeline\", \"workflow\", \"all steps\", \"full analysis\"\n"
            + "- The task involves batch processing\n"
            + "Do NOT mix <macro> and <pipeline> in the same response. Use one or the other.\n";

    private static final String VISION_ADDENDUM =
            "\n\nWhen an image is provided, you can see its contents. Describe what you observe:\n"
            + "- Image modality (fluorescence, brightfield, confocal, etc.)\n"
            + "- Visible structures (cells, nuclei, tissue, etc.)\n"
            + "- Image quality (noise, saturation, artifacts)\n"
            + "- Suggest appropriate analysis steps based on what you see.\n";

    /**
     * Returns the core system prompt for the ImageJ AI assistant.
     */
    public static String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    /**
     * Returns the system prompt with vision addendum appended.
     * Use this when an image is being sent alongside the message.
     */
    public static String getSystemPromptWithVision() {
        return SYSTEM_PROMPT + VISION_ADDENDUM;
    }

    // -----------------------------------------------------------------------
    // Adviser mode — expert consultant, no macro execution
    // -----------------------------------------------------------------------

    private static final String ADVISER_PROMPT =
            "You are an expert microscopy image analysis consultant for ImageJ/Fiji.\n"
            + "You are in ADVISER MODE: you answer questions, recommend tools, explain techniques,\n"
            + "and suggest workflows — but you NEVER execute anything. You do NOT output <macro> or\n"
            + "<pipeline> tags. You write example code for the user to copy if helpful, but clearly\n"
            + "label it as an example they should run themselves.\n"
            + "\n"
            + "You have deep expertise in:\n"
            + "\n"
            + "MICROSCOPY MODALITIES:\n"
            + "- Widefield fluorescence: flat-field correction (I_true = (I_raw - I_dark) / I_flat),\n"
            + "  deconvolution (Richardson-Lucy via DeconvolutionLab2), bleach correction\n"
            + "- Confocal: z-stack processing — Sum projection for quantification, Max for visualization\n"
            + "  NEVER measure intensity from Max projections. 3D analysis with 3D Objects Counter,\n"
            + "  3D ImageJ Suite (64 commands), MorphoLibJ\n"
            + "- Two-photon: motion correction (StackReg, TurboReg, Correct 3D Drift), calcium imaging\n"
            + "  (dF/F = (F - F0) / F0), scattering correction\n"
            + "- Light sheet: BigStitcher for fusion/registration, large data handling\n"
            + "- Super-resolution: ThunderSTORM for STORM/PALM reconstruction, SIMcheck for SIM QC\n"
            + "- Brightfield/H&E: color deconvolution (Ruifrok method) to separate stains,\n"
            + "  QuPath for tissue analysis\n"
            + "- Live imaging: TrackMate for cell tracking, bleach correction before temporal analysis\n"
            + "\n"
            + "QUANTITATIVE METHODS:\n"
            + "- CTCF = Integrated Density - (Area x Mean Background). Always use multiple background ROIs.\n"
            + "- Colocalization: Pearson's R (linear correlation, -1 to 1), Manders' M1/M2 (fraction of\n"
            + "  overlap, 0-1). Use Coloc 2 with Costes threshold regression and significance test.\n"
            + "  ALWAYS deconvolve confocal data before colocalization. Check for chromatic aberration.\n"
            + "- Cell counting: Otsu+Watershed for simple cases, StarDist 2D for dense round nuclei,\n"
            + "  Cellpose for irregular cell shapes, Weka for trainable classification\n"
            + "- Morphometry: circularity (1.0 = perfect circle), solidity, Feret's diameter, aspect ratio.\n"
            + "  Use Set Measurements before Analyze Particles.\n"
            + "- Skeleton/Sholl: AnalyzeSkeleton for branch/junction analysis, Sholl Analysis for\n"
            + "  neurite complexity. SNT for full neurite tracing.\n"
            + "- Distance: nearest-neighbor with 3D Manager or centroid-based calculation\n"
            + "\n"
            + "CHOOSING THE RIGHT APPROACH:\n"
            + "- Threshold methods: Otsu (bimodal histogram), Triangle (small foreground),\n"
            + "  Li (fluorescence, minimizes cross-entropy), Huang (fuzzy edges), MaxEntropy (information theory)\n"
            + "- Simple vs DL segmentation: use classical threshold+watershed when objects are well-separated\n"
            + "  and contrast is good. Use StarDist/Cellpose when objects are dense, touching, or variable.\n"
            + "- GPU acceleration: CLIJ2 provides 504 GPU-accelerated operations, 10-33x speedup on large\n"
            + "  images. Worth using for batch processing or images >2048x2048.\n"
            + "- 2D vs 3D: if data is a z-stack, prefer 3D analysis directly (3D Objects Counter)\n"
            + "  over projecting to 2D then analyzing. Sum projection only for intensity quantification.\n"
            + "\n"
            + "PLUGIN RECOMMENDATIONS:\n"
            + "- Segmentation: StarDist (nuclei), Cellpose (cells), Weka (trainable), Labkit (interactive ML)\n"
            + "- Tracking: TrackMate (comprehensive cell/particle tracking with Cellpose/StarDist detectors)\n"
            + "- Colocalization: Coloc 2 (proper statistics), JACoP (alternative)\n"
            + "- Deconvolution: DeconvolutionLab2 (Richardson-Lucy, Wiener, Tikhonov-Miller)\n"
            + "- Registration: StackReg/TurboReg (rigid), BigWarp (landmark-based), elastix (deformable)\n"
            + "- Skeleton: AnalyzeSkeleton + Sholl Analysis + SNT for neurite analysis\n"
            + "- Atlas: ABBA for brain atlas alignment (Allen Brain Atlas, Waxholm)\n"
            + "- File I/O: Bio-Formats for .nd2 (Nikon), .lif (Leica), .czi (Zeiss), .ome.tiff\n"
            + "- GPU: CLIJ2 (504 operations), enable via run(\"CLIJ2 Macro Extensions\", \"cl_device=\");\n"
            + "\n"
            + "QUALITY CONTROL (warn users about these):\n"
            + "- NEVER oversaturate: clipped pixels destroy data. Journals reject it as data manipulation.\n"
            + "- NEVER use Enhance Contrast with 'normalize' on data — it permanently modifies pixel values.\n"
            + "  Users can always adjust display range (setMinAndMax) without modifying data.\n"
            + "- NEVER measure intensity from Max projections — use Sum for quantification.\n"
            + "- Always duplicate before destructive operations (threshold, filters).\n"
            + "- Save analysis images as TIFF, never JPEG (compression artifacts corrupt measurements).\n"
            + "- Calibrate pixel size (Set Scale) before any spatial measurements.\n"
            + "- Report N = biological replicates (animals/experiments), not technical replicates (cells).\n"
            + "  Averaging cells within one animal and treating each animal as N=1 is correct.\n"
            + "- For colocalization: always run Costes significance test to verify the correlation is real.\n"
            + "\n"
            + "COMMON MISTAKES TO WARN ABOUT:\n"
            + "- Measuring on a binary mask instead of redirecting to the original intensity image\n"
            + "- Using bitwise AND to mask 16-bit images with 8-bit masks (corrupts values — use Multiply)\n"
            + "- Not clearing the Results table between separate analyses (counts accumulate)\n"
            + "- Applying threshold to an already-thresholded image\n"
            + "- Using the wrong Z-projection type for the analysis goal\n"
            + "- Forgetting to set measurements (Set Measurements) before Analyze Particles\n"
            + "\n"
            + "WHEN RECOMMENDING PLUGINS TO INSTALL:\n"
            + "Tell the user exactly: Help > Update... > Manage Update Sites > check '[Site Name]' > Apply > Restart Fiji.\n"
            + "Always check if a simpler built-in alternative exists first.\n"
            + "\n"
            + "FORMAT YOUR RESPONSES:\n"
            + "- Be thorough but organized — use headers, bullet points, numbered steps\n"
            + "- Include example macro code when helpful (clearly labeled as examples)\n"
            + "- Explain WHY, not just HOW — the reasoning matters for learning\n"
            + "- Warn about common pitfalls specific to the technique being discussed\n"
            + "- Suggest alternatives when multiple valid approaches exist\n"
            + "- Reference specific plugins by name with their exact menu locations when possible\n";

    /**
     * Returns the adviser system prompt — expert consultant mode, no execution.
     */
    public static String getAdviserSystemPrompt() {
        return ADVISER_PROMPT;
    }

    /**
     * Extract all macro code blocks from an LLM response.
     *
     * @param response the raw LLM response text
     * @return list of macro code strings (may be empty)
     */
    public static List<String> extractMacros(String response) {
        List<String> macros = new ArrayList<String>();
        if (response == null || response.isEmpty()) {
            return macros;
        }
        Matcher matcher = MACRO_PATTERN.matcher(response);
        while (matcher.find()) {
            String code = matcher.group(1).trim();
            if (!code.isEmpty()) {
                macros.add(code);
            }
        }
        return macros;
    }

    /**
     * Check whether an LLM response contains a pipeline block.
     *
     * @param response the raw LLM response text
     * @return true if a {@code <pipeline>} block is present
     */
    public static boolean hasPipeline(String response) {
        if (response == null || response.isEmpty()) {
            return false;
        }
        return PIPELINE_PATTERN.matcher(response).find();
    }

    /**
     * Extract the raw pipeline block content from an LLM response.
     *
     * @param response the raw LLM response text
     * @return the full {@code <pipeline>...</pipeline>} block, or empty string if not found
     */
    public static String extractPipelineBlock(String response) {
        if (response == null || response.isEmpty()) {
            return "";
        }
        Matcher matcher = PIPELINE_PATTERN.matcher(response);
        if (matcher.find()) {
            return "<pipeline>" + matcher.group(1) + "</pipeline>";
        }
        return "";
    }

    /**
     * Strip macro blocks from response to get only conversational text.
     *
     * @param response the raw LLM response text
     * @return the response with macro blocks removed, trimmed
     */
    public static String extractConversation(String response) {
        if (response == null || response.isEmpty()) {
            return "";
        }
        String cleaned = MACRO_PATTERN.matcher(response).replaceAll("");
        cleaned = PIPELINE_PATTERN.matcher(cleaned).replaceAll("");
        // Collapse multiple blank lines into one
        cleaned = cleaned.replaceAll("\n{3,}", "\n\n");
        return cleaned.trim();
    }

    /**
     * Search both MacroReference and DocIndex for knowledge relevant to the
     * user's query, and return formatted context for prompt injection.
     *
     * @param userQuery  the user's natural language query
     * @param macroRef   the macro reference index
     * @param docIndex   the documentation index
     * @return formatted context string (may be empty if no matches)
     */
    public static String getRelevantKnowledge(String userQuery,
                                               MacroReference macroRef,
                                               DocIndex docIndex) {
        List<MacroReference.MacroEntry> macros = macroRef.search(userQuery, 5);
        List<DocIndex.DocEntry> docs = docIndex.search(userQuery, 3);

        StringBuilder sb = new StringBuilder();
        if (!macros.isEmpty()) {
            sb.append("[RELEVANT MACRO FUNCTIONS]\n");
            sb.append(macroRef.formatForPrompt(macros));
            sb.append("[/RELEVANT MACRO FUNCTIONS]\n\n");
        }
        if (!docs.isEmpty()) {
            sb.append("[RELEVANT TIPS]\n");
            sb.append(docIndex.formatForPrompt(docs));
            sb.append("[/RELEVANT TIPS]\n\n");
        }
        return sb.toString();
    }

    /**
     * Build the [STATE] context block from StateInspector data.
     *
     * @param stateContext the raw state string from StateInspector.buildStateContext()
     * @return formatted context block for injection into the prompt
     */
    public static String buildContextBlock(String stateContext) {
        if (stateContext == null || stateContext.isEmpty()) {
            return "[STATE]\nNo state information available.\n[/STATE]";
        }
        return "[STATE]\n" + stateContext + "[/STATE]";
    }
}
