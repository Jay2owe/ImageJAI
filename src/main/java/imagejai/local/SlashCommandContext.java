package imagejai.local;

import imagejai.engine.IntentRouter;
import imagejai.engine.FrictionLog;
import imagejai.engine.FrictionLogJournal;

import java.util.function.Consumer;

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
    private final IntentMatcher matcher;
    private final FrictionLog frictionLog;
    private final Consumer<ImproveSession> improveSessionStarter;

    public SlashCommandContext(String args, FijiBridge fiji, IntentLibrary library,
                               IntentRouter intentRouter, ChatHistoryController chatHistory) {
        this(args, fiji, library, intentRouter, chatHistory, null);
    }

    public SlashCommandContext(String args, FijiBridge fiji, IntentLibrary library,
                               IntentRouter intentRouter, ChatHistoryController chatHistory,
                               Runnable clearPending) {
        this(args, fiji, library, intentRouter, chatHistory, clearPending,
                null, null, null);
    }

    public SlashCommandContext(String args, FijiBridge fiji, IntentLibrary library,
                               IntentRouter intentRouter, ChatHistoryController chatHistory,
                               Runnable clearPending, IntentMatcher matcher,
                               FrictionLog frictionLog,
                               Consumer<ImproveSession> improveSessionStarter) {
        this.args = args == null ? "" : args;
        this.fiji = fiji;
        this.library = library;
        this.intentRouter = intentRouter;
        this.chatHistory = chatHistory;
        this.clearPending = clearPending;
        this.matcher = matcher;
        this.frictionLog = frictionLog;
        this.improveSessionStarter = improveSessionStarter;
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

    public IntentMatcher matcher() {
        return matcher;
    }

    public FrictionLog frictionLog() {
        return frictionLog;
    }

    public FrictionLogJournal frictionJournal() {
        return frictionLog == null ? null : frictionLog.journal();
    }

    public boolean canStartImproveSession() {
        return improveSessionStarter != null;
    }

    public void startImproveSession(ImproveSession session) {
        if (improveSessionStarter != null) {
            improveSessionStarter.accept(session);
        }
    }
}
