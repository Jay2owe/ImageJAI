package imagejai.local;

import imagejai.engine.IntentRouter;

/**
 * Per-invocation state passed to slash-command implementations.
 */
public class SlashCommandContext {
    private final String args;
    private final FijiBridge fiji;
    private final IntentLibrary library;
    private final IntentRouter intentRouter;
    private final ChatHistoryController chatHistory;
    private final Runnable clearPending;

    public SlashCommandContext(String args, FijiBridge fiji, IntentLibrary library,
                               IntentRouter intentRouter, ChatHistoryController chatHistory) {
        this(args, fiji, library, intentRouter, chatHistory, null);
    }

    public SlashCommandContext(String args, FijiBridge fiji, IntentLibrary library,
                               IntentRouter intentRouter, ChatHistoryController chatHistory,
                               Runnable clearPending) {
        this.args = args == null ? "" : args;
        this.fiji = fiji;
        this.library = library;
        this.intentRouter = intentRouter;
        this.chatHistory = chatHistory;
        this.clearPending = clearPending;
    }

    public String args() {
        return args;
    }

    public FijiBridge fiji() {
        return fiji;
    }

    public IntentLibrary library() {
        return library;
    }

    public IntentRouter intentRouter() {
        return intentRouter;
    }

    public ChatHistoryController chatHistory() {
        return chatHistory;
    }

    public void clearPendingTurn() {
        if (clearPending != null) {
            clearPending.run();
        }
    }
}
