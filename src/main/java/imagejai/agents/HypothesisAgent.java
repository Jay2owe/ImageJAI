package imagejai.agents;

import imagejai.engine.CommandEngine;
import imagejai.knowledge.PromptTemplates;
import imagejai.llm.LLMBackend;
import imagejai.llm.LLMResponse;
import imagejai.llm.Message;

import java.util.ArrayList;
import java.util.List;

/**
 * Specialist agent for hypothesis-driven image analysis.
 * <p>
 * Takes a scientific hypothesis and designs the complete image analysis
 * workflow to test it. Thinks like a scientist: identifies variables,
 * designs the analysis pipeline, warns about pitfalls, and recommends
 * appropriate statistical tests.
 * <p>
 * This is the most holistic agent -- it may internally leverage concepts
 * from segmentation, measurement, and statistics to build an end-to-end
 * analysis plan.
 */
public class HypothesisAgent {

    private LLMBackend backend;
    private final CommandEngine commandEngine;

    private static final String SYSTEM_PROMPT =
            "You are a scientific image analysis consultant specializing in neuroscience microscopy.\n"
            + "When a user states a hypothesis, you design the complete image analysis workflow to test it.\n"
            + "\n"
            + "YOUR APPROACH:\n"
            + "1. UNDERSTAND the hypothesis -- what is being compared? What is the expected outcome?\n"
            + "2. IDENTIFY the variables:\n"
            + "   - Independent variable (e.g., genotype: CK1-mutant vs wild-type)\n"
            + "   - Dependent variable (e.g., AT8 fluorescence intensity in SCN)\n"
            + "   - Controls and confounds\n"
            + "3. DESIGN the analysis pipeline:\n"
            + "   - Which channels contain which markers?\n"
            + "   - How to identify the region of interest (e.g., SCN in brain sections)\n"
            + "   - What segmentation approach for the structures of interest\n"
            + "   - What measurements to extract\n"
            + "   - What normalization is needed (per-cell? per-area? CTCF?)\n"
            + "   - What statistical test is appropriate\n"
            + "4. GENERATE the pipeline as a <pipeline> block\n"
            + "5. WARN about potential pitfalls:\n"
            + "   - Is the sample size sufficient?\n"
            + "   - Are there confounding variables?\n"
            + "   - Should intensity be normalized per cell or per area?\n"
            + "   - Is the projection method appropriate for quantification?\n"
            + "   - Is background correction needed?\n"
            + "\n"
            + "EXAMPLE:\n"
            + "User: \"I hypothesize that tau phosphorylation (AT8) is increased in the SCN of "
            + "CK1-mutant mice at 4 weeks\"\n"
            + "Your response should:\n"
            + "- Identify: IV=genotype, DV=AT8 intensity in SCN\n"
            + "- Design: segment SCN (DAPI-dense region), measure AT8 in SCN ROI, normalize appropriately\n"
            + "- Warn: don't use max projection for intensity quantification, need per-cell or CTCF "
            + "normalization\n"
            + "- Generate a pipeline with all steps\n"
            + "- Recommend appropriate statistical test (t-test if 2 groups, normal distribution)\n"
            + "\n"
            + "SCIENTIFIC RIGOR:\n"
            + "- Always recommend appropriate controls\n"
            + "- Always consider normalization\n"
            + "- Always warn about n (biological replicates = animals, not cells/images)\n"
            + "- Always suggest the simplest appropriate statistical test\n"
            + "- If the hypothesis is vague, ask clarifying questions before designing the analysis\n"
            + "- If the analysis requires plugins not in standard Fiji (StarDist, Cellpose), mention it\n"
            + "\n"
            + "When generating pipelines, use the <pipeline> format:\n"
            + "<pipeline>\n"
            + "  <step description=\"...\">macro code</step>\n"
            + "</pipeline>\n"
            + "\n"
            + "When you need to ask clarifying questions, do NOT generate a pipeline -- just ask.\n"
            + "\n"
            + "IMPORTANT RULES:\n"
            + "1. Always wrap executable macro code in <macro> tags or use <pipeline> blocks.\n"
            + "2. Explain your reasoning as a scientist would.\n"
            + "3. When you see [STATE] context, use it to understand current images and their properties.\n"
            + "4. If no image is open, you can still design the analysis plan and provide the pipeline.\n"
            + "5. Do NOT mix <macro> and <pipeline> in the same response. Use one or the other.\n";

    /**
     * Create a new HypothesisAgent.
     *
     * @param backend       the LLM backend
     * @param commandEngine the macro execution engine
     */
    public HypothesisAgent(LLMBackend backend, CommandEngine commandEngine) {
        this.backend = backend;
        this.commandEngine = commandEngine;
    }

    /**
     * Process a hypothesis-driven analysis request.
     * Returns a response that may contain a {@code <pipeline>} block
     * with the complete analysis workflow.
     *
     * @param userMessage  the user's hypothesis or analysis request
     * @param stateContext the current ImageJ state context string
     * @param history      conversation history
     * @return the agent's response text (may include pipeline/macro blocks)
     */
    public String process(String userMessage, String stateContext, List<Message> history) {
        if (backend == null) {
            return "No LLM backend configured. Please check your settings.";
        }

        // Build the full system prompt with state context
        String contextBlock = PromptTemplates.buildContextBlock(stateContext);
        String fullSystemPrompt = SYSTEM_PROMPT + "\n\n" + contextBlock;

        // Build messages: include history plus the current user message
        List<Message> messages = new ArrayList<Message>();
        if (history != null) {
            messages.addAll(history);
        }
        // Add current message if not already the last in history
        if (messages.isEmpty() || !userMessage.equals(messages.get(messages.size() - 1).getContent())) {
            messages.add(Message.user(userMessage));
        }

        // Call the LLM with the specialist prompt
        LLMResponse response = backend.chat(messages, fullSystemPrompt);

        if (!response.isSuccess()) {
            return "Hypothesis agent error: " + response.getError();
        }

        return response.getContent();
    }

    /**
     * Get the specialist system prompt for hypothesis-driven analysis.
     *
     * @return the hypothesis agent system prompt
     */
    public String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    /**
     * Update the LLM backend (e.g., after settings change).
     *
     * @param backend the new LLM backend
     */
    public void setBackend(LLMBackend backend) {
        this.backend = backend;
    }
}
