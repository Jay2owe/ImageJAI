package uk.ac.ucl.imagej.ai.local.slash;

import uk.ac.ucl.imagej.ai.local.AssistantReply;
import uk.ac.ucl.imagej.ai.local.ChatHistoryController;
import uk.ac.ucl.imagej.ai.local.SlashCommand;
import uk.ac.ucl.imagej.ai.local.SlashCommandContext;

public class ClearSlashCommand implements SlashCommand {
    public String name() { return "clear"; }
    public String intentId() { return "slash.clear"; }
    public String description() { return "Clear the chat history"; }

    public AssistantReply execute(SlashCommandContext context) {
        ChatHistoryController history = context.chatHistory();
        if (history == null) {
            return AssistantReply.text("Chat history is not available to clear.");
        }
        if (!history.canClear()) {
            return AssistantReply.text("Cannot clear while a Local Assistant turn is running.");
        }
        history.clear();
        return AssistantReply.text("");
    }
}
