package imagejai;

import imagejai.config.Constants;
import imagejai.config.Settings;
import imagejai.engine.CommandEngine;
import imagejai.engine.ExecutionResult;
import imagejai.engine.StateInspector;
import imagejai.knowledge.PromptTemplates;
import imagejai.llm.BackendFactory;
import imagejai.llm.LLMBackend;
import imagejai.llm.LLMResponse;
import imagejai.llm.Message;
import imagejai.ui.ChatPanel;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;

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

        // Add user message to history
        history.add(Message.user(userText));
        trimHistory();

        // Build state context
        String stateContext = stateInspector.buildStateContext();
        String contextBlock = PromptTemplates.buildContextBlock(stateContext);

        // Build system prompt with current state
        String systemPrompt = PromptTemplates.getSystemPrompt() + "\n\n" + contextBlock;

        // Call LLM
        LLMResponse response = backend.chat(history, systemPrompt);

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
}
