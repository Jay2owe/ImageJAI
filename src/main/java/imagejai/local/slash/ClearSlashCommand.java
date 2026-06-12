package imagejai.local.slash;

import imagejai.local.AssistantReply;
import imagejai.local.ChatHistoryController;
import imagejai.local.SlashCommand;
import imagejai.local.SlashCommandContext;

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
