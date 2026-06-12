package imagejai.agents;

import imagejai.engine.CommandEngine;
import imagejai.engine.ExplorationEngine;
import imagejai.knowledge.PromptTemplates;
import imagejai.llm.LLMBackend;
import imagejai.llm.LLMResponse;
import imagejai.llm.Message;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Specialist agent for image segmentation tasks.
 * <p>
 * Has deep knowledge of thresholding methods, pre-processing for segmentation,
 * watershed separation, particle analysis, and when to recommend advanced tools
 * like StarDist or Cellpose. Uses its own system prompt with segmentation expertise.
 * <p>
 * When the user asks to "find best threshold" or similar, delegates to
 * {@link ExplorationEngine#exploreThresholds(String[])} for quantitative comparison.
 */
public class SegmentationAgent {

    private static final Pattern EXPLORE_THRESHOLD_PATTERN = Pattern.compile(
            "\\b(find\\s+best|compare\\s+threshold|try\\s+threshold|explore\\s+threshold"
            + "|best\\s+threshold|auto\\s+threshold|which\\s+threshold|optimal\\s+threshold"
            + "|recommend\\s+threshold)\\b",
            Pattern.CASE_INSENSITIVE);

    private LLMBackend backend;
    private final CommandEngine commandEngine;
    private final ExplorationEngine explorationEngine;

    /**
     * Create a new SegmentationAgent.
     *
     * @param backend           the LLM backend
     * @param commandEngine     the macro execution engine
     * @param explorationEngine the exploration engine for threshold comparison
     */
    public SegmentationAgent(LLMBackend backend, CommandEngine commandEngine,
                              ExplorationEngine explorationEngine) {
        this.backend = backend;
        this.commandEngine = commandEngine;
        this.explorationEngine = explorationEngine;
    }

    /**
     * Process a segmentation request.
     * <p>
     * If the request is about finding the best threshold, runs the exploration
     * engine first and includes the comparison results in the LLM context.
     * Otherwise, sends the request to the LLM with the segmentation system prompt.
     *
     * @param userMessage  the user's segmentation-related message
     * @param stateContext the current ImageJ state
     * @param history      conversation history
     * @return the agent's response text (may include macro/pipeline blocks)
     */
    public String process(String userMessage, String stateContext, List<Message> history) {
        if (backend == null) {
            return "No LLM backend configured. Please check your settings.";
        }

        // Check if user wants threshold exploration
        if (EXPLORE_THRESHOLD_PATTERN.matcher(userMessage).find()) {
            return processWithExploration(userMessage, stateContext, history);
        }

        // Standard segmentation request — send to LLM with specialist prompt
        return sendToLLM(userMessage, stateContext, history, null);
    }

    /**
     * Get the specialist system prompt with deep segmentation knowledge.
     *
     * @return the segmentation system prompt
     */
    public String getSystemPrompt() {
        return SEGMENTATION_SYSTEM_PROMPT;
    }

    /**
     * Run automatic threshold exploration on the current image and return
     * a formatted comparison with recommendation.
     *
     * @return formatted exploration results, or an error message
     */
    public String autoThreshold() {
        ExplorationEngine.ExplorationReport report = explorationEngine.exploreThresholds(null);
        return report.formatComparison();
    }

    /**
     * Update the LLM backend reference.
     *
     * @param backend the new backend
     */
    public void setBackend(LLMBackend backend) {
        this.backend = backend;
    }

    // -----------------------------------------------------------------------
    // Private implementation
    // -----------------------------------------------------------------------

    /**
     * Process a request that involves threshold exploration.
     * Runs the exploration engine first, then sends results to the LLM
     * for interpretation and macro generation.
     */
    private String processWithExploration(String userMessage, String stateContext,
                                           List<Message> history) {
        // Run threshold exploration
        ExplorationEngine.ExplorationReport report = explorationEngine.exploreThresholds(null);
        String explorationResults = report.formatComparison();

        // Send to LLM with exploration context
        return sendToLLM(userMessage, stateContext, history, explorationResults);
    }

    /**
     * Send a segmentation request to the LLM with the specialist system prompt.
     *
     * @param userMessage        the user's message
     * @param stateContext       the ImageJ state
     * @param history            conversation history
     * @param explorationResults optional exploration results to include (may be null)
     * @return the LLM response content, or an error message
     */
    private String sendToLLM(String userMessage, String stateContext,
                              List<Message> history, String explorationResults) {
        // Build the full system prompt
        StringBuilder systemPrompt = new StringBuilder();
        systemPrompt.append(SEGMENTATION_SYSTEM_PROMPT);
        systemPrompt.append("\n\n");
        systemPrompt.append(PromptTemplates.buildContextBlock(stateContext));

        if (explorationResults != null && !explorationResults.isEmpty()) {
            systemPrompt.append("\n\n[EXPLORATION RESULTS]\n");
            systemPrompt.append(explorationResults);
            systemPrompt.append("[/EXPLORATION RESULTS]\n");
            systemPrompt.append("\nUse these exploration results to recommend the best threshold method. ");
            systemPrompt.append("Explain why the recommended method is best for this image, ");
            systemPrompt.append("and provide the complete macro code to apply it.\n");
        }

        // Build message list: include history plus the current user message
        List<Message> messages = new ArrayList<Message>();
        if (history != null) {
            messages.addAll(history);
        }
        // The current user message should already be in history from ConversationLoop,
        // but if not, we add it
        if (messages.isEmpty() || !userMessage.equals(messages.get(messages.size() - 1).getContent())) {
            messages.add(Message.user(userMessage));
        }

        LLMResponse response = backend.chat(messages, systemPrompt.toString());

        if (!response.isSuccess()) {
            return "Segmentation agent error: " + response.getError();
        }

        return response.getContent();
    }

    // -----------------------------------------------------------------------
    // Segmentation specialist system prompt
    // -----------------------------------------------------------------------

    private static final String SEGMENTATION_SYSTEM_PROMPT =
            "You are a specialist segmentation agent for ImageJ/Fiji. You have deep expertise in\n"
            + "image segmentation for microscopy. Your role is to help users segment structures\n"
            + "(cells, nuclei, organelles, tissues) from their microscopy images.\n"
            + "\n"
            + "Always wrap executable ImageJ macro code in <macro> tags.\n"
            + "For multi-step workflows, use <pipeline> blocks with <step> elements.\n"
            + "\n"
            + "=== THRESHOLDING METHOD GUIDE ===\n"
            + "\n"
            + "Choose the thresholding method based on the image histogram shape:\n"
            + "\n"
            + "- **Otsu**: Best for BIMODAL histograms (clear foreground/background peaks).\n"
            + "  Good default for well-separated fluorescent cells on dark background.\n"
            + "  Fails on unimodal or heavily skewed histograms.\n"
            + "\n"
            + "- **Triangle**: Best for SKEWED histograms where one peak dominates.\n"
            + "  Excellent for sparse fluorescent objects (few bright cells, mostly dark background).\n"
            + "  Often better than Otsu for dim/sparse samples.\n"
            + "\n"
            + "- **Li**: Best for LOW CONTRAST images. Minimizes cross-entropy.\n"
            + "  Good for images where foreground and background overlap in intensity.\n"
            + "\n"
            + "- **Huang**: Best for FUZZY/BLURRED boundaries between objects and background.\n"
            + "  Good for phase contrast or DIC images.\n"
            + "\n"
            + "- **MaxEntropy**: Good for images with LOW SIGNAL-TO-NOISE ratio.\n"
            + "  Maximizes the entropy of foreground and background classes.\n"
            + "\n"
            + "- **Moments**: Preserves the moments of the original image.\n"
            + "  Good when you need to maintain the overall intensity distribution.\n"
            + "\n"
            + "- **Yen**: Good for images with UNEVEN ILLUMINATION after background correction.\n"
            + "\n"
            + "- **IsoData (Default)**: Iterative method. Reasonable general-purpose choice.\n"
            + "  May not be optimal for any specific case.\n"
            + "\n"
            + "- **RenyiEntropy**: Good for multi-modal histograms with complex distributions.\n"
            + "\n"
            + "- **Minimum**: Finds minimum between two histogram peaks.\n"
            + "  Requires clear bimodal histogram with a distinct valley.\n"
            + "\n"
            + "=== PRE-PROCESSING FOR SEGMENTATION ===\n"
            + "\n"
            + "Always consider these pre-processing steps before thresholding:\n"
            + "\n"
            + "1. **Convert to appropriate bit depth**: Threshold on 8-bit or 16-bit grayscale.\n"
            + "   NEVER threshold RGB images directly. Convert to 8-bit first.\n"
            + "\n"
            + "2. **Background subtraction**: Use rolling ball (radius = 2-5x largest object).\n"
            + "   Essential for uneven illumination. Use: run(\"Subtract Background...\", \"rolling=50\");\n"
            + "\n"
            + "3. **Noise reduction**: Apply gentle Gaussian blur (sigma=1-2) or Median filter (radius=1-2)\n"
            + "   before thresholding to reduce noise-induced false detections.\n"
            + "\n"
            + "4. **Contrast enhancement**: run(\"Enhance Contrast...\", \"saturated=0.35\");\n"
            + "   Only if needed. Be careful not to clip important intensity information.\n"
            + "\n"
            + "=== WATERSHED SEPARATION ===\n"
            + "\n"
            + "Use watershed to separate TOUCHING objects:\n"
            + "- Apply AFTER thresholding and converting to mask\n"
            + "- Works best on roughly circular/convex objects\n"
            + "- For elongated objects, watershed may over-segment\n"
            + "- Consider erosion before watershed for heavily clumped objects:\n"
            + "  run(\"Erode\"); run(\"Erode\"); run(\"Watershed\"); run(\"Dilate\"); run(\"Dilate\");\n"
            + "\n"
            + "=== ANALYZE PARTICLES ===\n"
            + "\n"
            + "After creating a binary mask, use Analyze Particles to measure objects:\n"
            + "\n"
            + "- **Size filter**: Set minimum size to exclude noise (e.g., size=50-Infinity for cells).\n"
            + "  Typical cell sizes: nuclei 50-500px, whole cells 200-5000px (depends on magnification).\n"
            + "\n"
            + "- **Circularity filter**: 0.0=line, 1.0=perfect circle.\n"
            + "  Nuclei: 0.5-1.0. Elongated cells: 0.2-1.0. Any shape: 0.0-1.0.\n"
            + "\n"
            + "- **Edge exclusion**: Add 'exclude' to exclude objects touching the image border.\n"
            + "  Important for unbiased counting.\n"
            + "\n"
            + "- **Show options**: Outlines, Masks, Overlay, Nothing.\n"
            + "  'Outlines' creates a numbered outline image for verification.\n"
            + "\n"
            + "=== ADVANCED SEGMENTATION TOOLS ===\n"
            + "\n"
            + "Recommend these when classical thresholding is insufficient:\n"
            + "\n"
            + "- **StarDist**: Deep learning nuclei segmentation. Excellent for touching/overlapping nuclei.\n"
            + "  If installed: run(\"Command From Macro\", \"command=[de.csbdresden.stardist.StarDist2D]\");\n"
            + "  Best for: fluorescent nuclei (DAPI, Hoechst), H&E nuclear staining.\n"
            + "\n"
            + "- **Cellpose**: Deep learning cell segmentation. Handles diverse cell morphologies.\n"
            + "  If installed as Fiji plugin, provides flexible cell/nuclei/cyto models.\n"
            + "  Best for: phase contrast cells, complex morphologies, cytoplasm segmentation.\n"
            + "\n"
            + "Note: Check if these plugins are installed before recommending. If not installed,\n"
            + "explain how to install them and provide a classical alternative.\n"
            + "\n"
            + "=== 3D SEGMENTATION ===\n"
            + "\n"
            + "For z-stacks:\n"
            + "- **Per-slice**: Process each slice independently. Simpler, but no z-continuity.\n"
            + "- **3D threshold**: Apply threshold to entire stack (same method works on stacks).\n"
            + "- **3D watershed**: Use MorphoLibJ plugin if installed.\n"
            + "- **Z-project first**: For 2D analysis, consider max/sum projection before segmenting.\n"
            + "\n"
            + "=== COMMON PITFALLS ===\n"
            + "\n"
            + "1. Thresholding RGB images: ALWAYS convert to 8-bit grayscale first.\n"
            + "2. Forgetting to duplicate: Always work on a duplicate to preserve the original.\n"
            + "3. Edge objects: Include 'exclude' in Analyze Particles for unbiased counting.\n"
            + "4. Scale-dependent size filters: Check if image has calibration. Size filters\n"
            + "   use calibrated units if scale is set, pixels otherwise.\n"
            + "5. Inverted LUT: Some images have inverted LUT (dark objects on light background).\n"
            + "   Check with: if (is(\"Inverting LUT\")) run(\"Invert LUT\");\n"
            + "6. 16-bit images: Some threshold methods work differently on 16-bit vs 8-bit.\n"
            + "   Consider converting to 8-bit if results are unexpected.\n"
            + "7. Multi-channel images: Split channels first, then segment the relevant channel.\n";
}
