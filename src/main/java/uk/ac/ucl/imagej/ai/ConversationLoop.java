package uk.ac.ucl.imagej.ai;

import uk.ac.ucl.imagej.ai.config.Constants;
import uk.ac.ucl.imagej.ai.config.Settings;
import uk.ac.ucl.imagej.ai.engine.CommandEngine;
import uk.ac.ucl.imagej.ai.engine.ExecutionResult;
import uk.ac.ucl.imagej.ai.engine.ImageCapture;
import uk.ac.ucl.imagej.ai.engine.StateInspector;
import uk.ac.ucl.imagej.ai.knowledge.PromptTemplates;
import uk.ac.ucl.imagej.ai.llm.BackendFactory;
import uk.ac.ucl.imagej.ai.llm.LLMBackend;
import uk.ac.ucl.imagej.ai.llm.LLMResponse;
import uk.ac.ucl.imagej.ai.llm.Message;
import uk.ac.ucl.imagej.ai.ui.ChatPanel;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Main orchestrator: wires ChatPanel, LLM backend, and CommandEngine together.
 * <p>
 * Implements ChatListener to receive user messages, sends them to the LLM
 * with ImageJ state context, extracts and executes macro blocks, and handles
 * the self-correction loop on macro failure.
 * <p>
 * All LLM calls run on background threads. All GUI updates run on the EDT.
 */
public class ConversationLoop implements ChatPanel.ChatListener {

    /**
     * Pattern matching vision-related keywords in user messages.
     * Case-insensitive word-boundary matching for common vision trigger words.
     */
    private static final Pattern VISION_KEYWORDS = Pattern.compile(
            "\\b(look|see|check|show|image|picture|screenshot|describe|what do you see"
            + "|does this look|what type|examine|inspect|view|visible|appear|observe)\\b",
            Pattern.CASE_INSENSITIVE);

    private final ChatPanel chatPanel;
    private final Settings settings;
    private LLMBackend backend;
    private final CommandEngine commandEngine;
    private final StateInspector stateInspector;
    private final List<Message> history;

    public ConversationLoop(ChatPanel chatPanel, Settings settings) {
        this.chatPanel = chatPanel;
        this.settings = settings;
        this.commandEngine = new CommandEngine();
        this.stateInspector = new StateInspector();
        this.history = new ArrayList<Message>();
        refreshBackend();
    }

    /**
     * Rebuild the LLM backend from current settings.
     * Call this after settings have been changed.
     */
    public void refreshBackend() {
        try {
            this.backend = BackendFactory.create(settings);
            System.err.println("[ImageJAI] Backend: " + backend.getProviderName()
                    + " / " + backend.getModelName());
        } catch (Exception e) {
            System.err.println("[ImageJAI] Failed to create backend: " + e.getMessage());
            this.backend = null;
        }
    }

    @Override
    public void onUserMessage(final String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }

        // Disable input and show thinking indicator
        chatPanel.setEnabled(false);
        chatPanel.setThinking(true);

        // Run LLM call on a background thread
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    processUserMessage(text);
                } catch (Exception e) {
                    System.err.println("[ImageJAI] Error in conversation loop: " + e.getMessage());
                    e.printStackTrace();
                    showAssistantMessage("An error occurred: " + e.getMessage());
                } finally {
                    // Re-enable input on EDT
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            chatPanel.setThinking(false);
                            chatPanel.setEnabled(true);
                        }
                    });
                }
            }
        }, "ImageJAI-ConversationLoop");
        worker.setDaemon(true);
        worker.start();
    }

    /**
     * Core processing logic. Runs on a background thread.
     */
    private void processUserMessage(String userText) {
        if (backend == null) {
            showAssistantMessage("No LLM backend configured. Please check your settings "
                    + "(click the gear icon).");
            return;
        }

        // Determine whether to use vision for this message
        boolean useVision = shouldUseVision(userText);
        byte[] imageBytes = null;

        if (useVision) {
            imageBytes = ImageCapture.captureActiveImage();
            if (imageBytes == null) {
                // No image open, fall back to text-only
                useVision = false;
            }
        }

        // Add user message to history (with or without image)
        if (useVision && imageBytes != null) {
            history.add(Message.userWithImage(userText, imageBytes));
        } else {
            history.add(Message.user(userText));
        }
        trimHistory();

        // Build state context
        String stateContext = stateInspector.buildStateContext();
        String contextBlock = PromptTemplates.buildContextBlock(stateContext);

        // Build system prompt — use vision variant when sending an image
        String basePrompt = useVision
                ? PromptTemplates.getSystemPromptWithVision()
                : PromptTemplates.getSystemPrompt();
        String systemPrompt = basePrompt + "\n\n" + contextBlock;

        // Show image preview in chat when vision is active
        if (useVision && imageBytes != null) {
            showImagePreview(imageBytes, "Current image sent for analysis");
        }

        // Call LLM — use vision endpoint when we have an image
        LLMResponse response;
        if (useVision && imageBytes != null) {
            response = backend.chatWithVision(history, systemPrompt, imageBytes);
        } else {
            response = backend.chat(history, systemPrompt);
        }

        if (!response.isSuccess()) {
            showAssistantMessage("LLM error: " + response.getError());
            return;
        }

        String fullResponse = response.getContent();

        // Extract macros from the response
        List<String> macros = PromptTemplates.extractMacros(fullResponse);

        if (macros.isEmpty()) {
            // Pure conversational response, no macros
            history.add(Message.assistant(fullResponse));
            trimHistory();
            showAssistantMessage(fullResponse);
            return;
        }

        // Show the conversational part first
        String conversation = PromptTemplates.extractConversation(fullResponse);
        if (!conversation.isEmpty()) {
            showAssistantMessage(conversation);
        }

        // Add full response (with macros) to history
        history.add(Message.assistant(fullResponse));
        trimHistory();

        // Execute each macro block
        for (int i = 0; i < macros.size(); i++) {
            String macroCode = macros.get(i);
            executeMacroWithRetry(macroCode, systemPrompt, 0);
        }
    }

    /**
     * Execute a macro with self-correction loop on failure.
     *
     * @param macroCode   the macro code to execute
     * @param systemPrompt the system prompt (for retry calls)
     * @param attempt     current attempt number (0-based)
     */
    private void executeMacroWithRetry(String macroCode, String systemPrompt, int attempt) {
        showStatus("Executing macro" + (attempt > 0 ? " (retry " + attempt + ")" : "") + "...");

        ExecutionResult result = commandEngine.executeMacro(macroCode);

        if (result.isSuccess()) {
            // Build a summary of what happened
            StringBuilder summary = new StringBuilder();
            summary.append("Macro executed successfully");
            summary.append(" (").append(result.getExecutionTimeMs()).append("ms)");

            if (!result.getNewImages().isEmpty()) {
                summary.append("\nNew images: ");
                for (int i = 0; i < result.getNewImages().size(); i++) {
                    if (i > 0) summary.append(", ");
                    summary.append(result.getNewImages().get(i));
                }
            }

            if (result.getResultsTable() != null && !result.getResultsTable().isEmpty()) {
                summary.append("\nResults:\n").append(truncateResults(result.getResultsTable()));
            }

            String output = result.getOutput();
            if (output != null && !output.isEmpty()) {
                summary.append("\nOutput: ").append(output);
            }

            showStatus(summary.toString());

            // Post-execution visual verification
            if (settings.autoScreenshot && settings.visionEnabled && backend != null) {
                performPostExecutionVerification(macroCode, systemPrompt);
            }
            return;
        }

        // Macro failed
        String error = result.getError();

        if (attempt >= Constants.MAX_MACRO_RETRIES) {
            // Max retries exceeded
            showAssistantMessage("Macro failed after " + (attempt + 1) + " attempts.\nError: " + error);
            return;
        }

        // Ask the LLM to fix the macro
        showStatus("Macro failed, asking AI to fix (attempt " + (attempt + 1)
                + "/" + Constants.MAX_MACRO_RETRIES + ")...");

        String errorFeedback = "The macro I tried to execute failed with this error:\n"
                + "```\n" + error + "\n```\n"
                + "Original macro:\n"
                + "```\n" + macroCode + "\n```\n"
                + "Please fix the macro and provide corrected code in <macro> tags.";

        history.add(Message.user(errorFeedback));
        trimHistory();

        // Refresh state in case the failed macro changed something
        String stateContext = stateInspector.buildStateContext();
        String contextBlock = PromptTemplates.buildContextBlock(stateContext);
        String updatedSystemPrompt = PromptTemplates.getSystemPrompt() + "\n\n" + contextBlock;

        LLMResponse retryResponse = backend.chat(history, updatedSystemPrompt);

        if (!retryResponse.isSuccess()) {
            showAssistantMessage("Failed to get fix from LLM: " + retryResponse.getError());
            return;
        }

        String retryContent = retryResponse.getContent();
        history.add(Message.assistant(retryContent));
        trimHistory();

        // Show the conversational part of the retry
        String retryConversation = PromptTemplates.extractConversation(retryContent);
        if (!retryConversation.isEmpty()) {
            showAssistantMessage(retryConversation);
        }

        // Extract the corrected macro
        List<String> correctedMacros = PromptTemplates.extractMacros(retryContent);
        if (correctedMacros.isEmpty()) {
            showAssistantMessage("The AI could not produce a corrected macro.\nOriginal error: " + error);
            return;
        }

        // Retry with the corrected macro
        executeMacroWithRetry(correctedMacros.get(0), updatedSystemPrompt, attempt + 1);
    }

    /**
     * Show an assistant message in the chat panel (EDT-safe).
     */
    private void showAssistantMessage(final String text) {
        chatPanel.appendMessage("assistant", text);
    }

    /**
     * Show a status message in the chat panel status bar.
     */
    private void showStatus(final String text) {
        chatPanel.setStatus(text);
    }

    /**
     * Trim conversation history to keep within the configured max.
     * Always preserves the most recent messages.
     */
    private void trimHistory() {
        int max = settings.maxHistory;
        if (max <= 0) {
            max = Constants.MAX_CONVERSATION_HISTORY;
        }
        while (history.size() > max) {
            history.remove(0);
        }
    }

    /**
     * Truncate results table output for display (keep first N lines).
     */
    private String truncateResults(String csv) {
        if (csv == null) {
            return "";
        }
        String[] lines = csv.split("\n");
        if (lines.length <= 11) {
            return csv;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 11; i++) {
            sb.append(lines[i]).append("\n");
        }
        sb.append("... (").append(lines.length - 11).append(" more rows)");
        return sb.toString();
    }

    /**
     * Clear conversation history (e.g., when user clicks clear).
     */
    public void clearHistory() {
        history.clear();
    }

    /**
     * Determine whether the user message should trigger vision analysis.
     * Returns true if vision is enabled in settings AND the message contains
     * vision-related keywords.
     *
     * @param userMessage the user's message text
     * @return true if vision should be used
     */
    boolean shouldUseVision(String userMessage) {
        if (!settings.visionEnabled) {
            return false;
        }
        if (userMessage == null || userMessage.isEmpty()) {
            return false;
        }
        return VISION_KEYWORDS.matcher(userMessage).find();
    }

    /**
     * Show a base64-encoded image thumbnail in the chat panel.
     *
     * @param pngBytes the PNG image bytes
     * @param caption  a caption to display below the image
     */
    private void showImagePreview(byte[] pngBytes, String caption) {
        if (pngBytes == null || pngBytes.length == 0) {
            return;
        }
        String base64 = base64Encode(pngBytes);
        String html = "<div style='margin:4px 0;'>"
                + "<img src='data:image/png;base64," + base64 + "' "
                + "style='max-width:380px; border:1px solid #444;' />"
                + "<div style='color:#a0a0aa; font-size:11px; font-style:italic; margin-top:2px;'>"
                + escapeHtml(caption)
                + "</div></div>";
        chatPanel.appendHtml(html);
    }

    /**
     * Perform post-execution visual verification by capturing the current image
     * and sending it to the LLM for assessment.
     *
     * @param macroCode    the macro that was just executed
     * @param systemPrompt the current system prompt
     */
    private void performPostExecutionVerification(String macroCode, String systemPrompt) {
        byte[] postImage = ImageCapture.captureActiveImage();
        if (postImage == null) {
            return;
        }

        showImagePreview(postImage, "Post-execution capture");

        String verificationPrompt = "I just executed this ImageJ macro:\n```\n" + macroCode
                + "\n```\nHere is the resulting image. Does it look correct? Any issues?";

        history.add(Message.userWithImage(verificationPrompt, postImage));
        trimHistory();

        String visionPrompt = PromptTemplates.getSystemPromptWithVision() + "\n\n"
                + PromptTemplates.buildContextBlock(stateInspector.buildStateContext());

        LLMResponse verifyResponse = backend.chatWithVision(history, visionPrompt, postImage);

        if (verifyResponse.isSuccess()) {
            String assessment = verifyResponse.getContent();
            history.add(Message.assistant(assessment));
            trimHistory();
            showAssistantMessage(assessment);
        } else {
            System.err.println("[ImageJAI] Post-execution verification failed: "
                    + verifyResponse.getError());
        }
    }

    /**
     * Base64-encode a byte array. Uses javax.xml.bind on Java 8.
     */
    private static String base64Encode(byte[] data) {
        return javax.xml.bind.DatatypeConverter.printBase64Binary(data);
    }

    /**
     * Escape HTML special characters.
     */
    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
