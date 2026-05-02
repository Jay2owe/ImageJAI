package imagejai.local;

/**
 * Command implemented by a Local Assistant slash-command handler.
 */
public interface SlashCommand {
    String name();
    String intentId();
    String description();
    AssistantReply execute(SlashCommandContext context);
}
