package uk.ac.ucl.imagej.ai.agents;

import uk.ac.ucl.imagej.ai.config.Settings;
import uk.ac.ucl.imagej.ai.engine.CommandEngine;
import uk.ac.ucl.imagej.ai.engine.ExplorationEngine;
import uk.ac.ucl.imagej.ai.llm.LLMBackend;
import uk.ac.ucl.imagej.ai.llm.Message;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Routes user requests to specialist agents based on intent classification.
 * <p>
 * Uses keyword-based classification (no LLM call) to determine which specialist
 * agent should handle a request. Falls back to GENERAL for the main conversation
 * loop when no specialist pattern matches.
 */
public class AgentOrchestrator {

    /**
     * The type of specialist agent to handle a request.
     */
    public enum AgentType {
        /** Default - handled by ConversationLoop as before. */
        GENERAL,
        /** Cell/nuclei segmentation, thresholding, watershed. */
        SEGMENTATION,
        /** Intensity, area, counting, CTCF, colocalization. */
        MEASUREMENT,
        /** LUTs, projections, montages, figures, scale bars. */
        VISUALIZATION,
        /** Statistical tests, plots, data export. */
        STATISTICS,
        /** Hypothesis-driven analysis design. */
        HYPOTHESIS
    }

    // Keyword patterns for intent classification (case-insensitive)
    private static final Pattern SEGMENTATION_PATTERN = Pattern.compile(
            "\\b(segment|threshold|binary|mask|watershed|detect\\s+cells|detect\\s+nuclei"
            + "|separate|stardist|cellpose|objects|foreground|background\\s+subtract"
            + "|find\\s+cells|find\\s+nuclei|identify\\s+cells|identify\\s+nuclei"
            + "|cell\\s+detection|nuclei\\s+detection|binarize|binarise)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern MEASUREMENT_PATTERN = Pattern.compile(
            "\\b(measure|intensity|fluorescence|count|area|ctcf|colocalization|colocalisation"
            + "|quantif|mean\\s+intensity|integrated\\s+density|particle\\s+analysis"
            + "|how\\s+many|number\\s+of\\s+cells|number\\s+of\\s+nuclei)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern VISUALIZATION_PATTERN = Pattern.compile(
            "\\b(lut|lookup\\s+table|projection|montage|figure|scale\\s+bar"
            + "|merge\\s+channels|composite|overlay|display|brightness|contrast"
            + "|color\\s+map|pseudocolor|rainbow|fire\\s+lut|green\\s+lut)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern STATISTICS_PATTERN = Pattern.compile(
            "\\b(statistics|statistical|t-test|ttest|anova|p-value|pvalue|significant"
            + "|compare\\s+groups|plot|histogram|box\\s+plot|boxplot|export\\s+csv"
            + "|standard\\s+deviation|mean\\s+comparison|bar\\s+chart|scatter)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern HYPOTHESIS_PATTERN = Pattern.compile(
            "\\b(hypothesi[sz]|test\\s+whether|is\\s+there\\s+a\\s+difference"
            + "|is\\s+there\\s+an\\s+effect|does\\s+\\w+\\s+affect"
            + "|relationship\\s+between)\\b"
            + "|\\bcompare\\s+\\w+\\s+between\\b"
            + "|\\bis\\s+\\w+\\s+increased\\b"
            + "|\\bis\\s+\\w+\\s+decreased\\b",
            Pattern.CASE_INSENSITIVE);

    private LLMBackend backend;
    private final CommandEngine commandEngine;
    private final ExplorationEngine explorationEngine;
    private final Settings settings;

    // Specialist agents
    private SegmentationAgent segmentationAgent;
    private MeasurementAgent measurementAgent;
    private VisualizationAgent visualizationAgent;
    private StatsAgent statsAgent;
    private HypothesisAgent hypothesisAgent;

    /**
     * Create a new AgentOrchestrator.
     *
     * @param backend           the LLM backend for agent calls
     * @param commandEngine     the macro execution engine
     * @param explorationEngine the threshold/parameter exploration engine
     * @param settings          plugin settings
     */
    public AgentOrchestrator(LLMBackend backend, CommandEngine commandEngine,
                              ExplorationEngine explorationEngine, Settings settings) {
        this.backend = backend;
        this.commandEngine = commandEngine;
        this.explorationEngine = explorationEngine;
        this.settings = settings;
        this.segmentationAgent = new SegmentationAgent(backend, commandEngine, explorationEngine);
        this.measurementAgent = new MeasurementAgent(backend, commandEngine);
        this.visualizationAgent = new VisualizationAgent(backend, commandEngine);
        this.statsAgent = new StatsAgent(backend, commandEngine);
        this.hypothesisAgent = new HypothesisAgent(backend, commandEngine);
    }

    /**
     * Classify user intent based on keyword matching.
     * Returns the most appropriate agent type for the message.
     *
     * @param userMessage the user's message text
     * @return the classified agent type
     */
    public AgentType classifyIntent(String userMessage) {
        if (userMessage == null || userMessage.trim().isEmpty()) {
            return AgentType.GENERAL;
        }

        String lower = userMessage.toLowerCase(Locale.ROOT);

        // Check each specialist pattern. Priority order matters for ambiguous messages:
        // hypothesis first (most holistic), then segmentation, measurement, visualization, statistics.
        if (HYPOTHESIS_PATTERN.matcher(lower).find()) {
            return AgentType.HYPOTHESIS;
        }
        if (SEGMENTATION_PATTERN.matcher(lower).find()) {
            return AgentType.SEGMENTATION;
        }
        if (MEASUREMENT_PATTERN.matcher(lower).find()) {
            return AgentType.MEASUREMENT;
        }
        if (VISUALIZATION_PATTERN.matcher(lower).find()) {
            return AgentType.VISUALIZATION;
        }
        if (STATISTICS_PATTERN.matcher(lower).find()) {
            return AgentType.STATISTICS;
        }

        return AgentType.GENERAL;
    }

    /**
     * Process a message through the appropriate specialist agent.
     * <p>
     * Currently only SEGMENTATION has a dedicated agent. Other specialist types
     * return null, indicating the caller should fall back to the general conversation loop.
     *
     * @param type         the classified agent type
     * @param userMessage  the user's message
     * @param stateContext the current ImageJ state context string
     * @param history      conversation history
     * @return the specialist's response, or null if no specialist is available for this type
     */
    public String processWithSpecialist(AgentType type, String userMessage,
                                         String stateContext, List<Message> history) {
        switch (type) {
            case SEGMENTATION:
                return segmentationAgent.process(userMessage, stateContext, history);
            case MEASUREMENT:
                return measurementAgent.process(userMessage, stateContext, history);
            case VISUALIZATION:
                return visualizationAgent.process(userMessage, stateContext, history);
            case STATISTICS:
                return statsAgent.process(userMessage, stateContext, history);
            case HYPOTHESIS:
                return hypothesisAgent.process(userMessage, stateContext, history);
            default:
                return null;
        }
    }

    /**
     * Get a display label for the agent type, suitable for showing in the chat UI.
     *
     * @param type the agent type
     * @return a human-readable label, or null for GENERAL
     */
    public static String getAgentLabel(AgentType type) {
        switch (type) {
            case SEGMENTATION:
                return "[Segmentation Agent]";
            case MEASUREMENT:
                return "[Measurement Agent]";
            case VISUALIZATION:
                return "[Visualization Agent]";
            case STATISTICS:
                return "[Statistics Agent]";
            case HYPOTHESIS:
                return "[Hypothesis Agent]";
            default:
                return null;
        }
    }

    /**
     * Update the LLM backend (e.g. after settings change).
     *
     * @param backend the new backend
     */
    public void setBackend(LLMBackend backend) {
        this.backend = backend;
        if (segmentationAgent != null) {
            segmentationAgent.setBackend(backend);
        }
        if (measurementAgent != null) {
            measurementAgent.setBackend(backend);
        }
        if (visualizationAgent != null) {
            visualizationAgent.setBackend(backend);
        }
        if (statsAgent != null) {
            statsAgent.setBackend(backend);
        }
        if (hypothesisAgent != null) {
            hypothesisAgent.setBackend(backend);
        }
    }
}
