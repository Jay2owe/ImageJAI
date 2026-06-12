package imagejai.local;

import imagejai.engine.IntentRouter;
import imagejai.engine.FrictionLogJournal;

import java.util.function.BooleanSupplier;
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
    private final IntentMatcher matcher;
    private final FrictionLogJournal frictionJournal;
    private final BooleanSupplier canStartImproveSession;
    private final Consumer<ImproveSession> improveSessionStarter;

    public SlashCommandContext(String args, FijiBridge fiji, IntentLibrary library,
                               IntentRouter intentRouter, ChatHistoryController chatHistory) {
        this(args, fiji, library, intentRouter, chatHistory, null, null, null, null);
    }

    public SlashCommandContext(String args, FijiBridge fiji, IntentLibrary library,
                               IntentRouter intentRouter, ChatHistoryController chatHistory,
                               IntentMatcher matcher, FrictionLogJournal frictionJournal,
                               BooleanSupplier canStartImproveSession,
                               Consumer<ImproveSession> improveSessionStarter) {
        this.args = args == null ? "" : args;
        this.fiji = fiji;
        this.library = library;
        this.intentRouter = intentRouter;
        this.chatHistory = chatHistory;
        this.matcher = matcher;
        this.frictionJournal = frictionJournal;
        this.canStartImproveSession = canStartImproveSession;
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

    public IntentMatcher matcher() {
        return matcher;
    }

    public FrictionLogJournal frictionJournal() {
        return frictionJournal;
    }

    public boolean canStartImproveSession() {
        return canStartImproveSession != null && canStartImproveSession.getAsBoolean();
    }

    public void startImproveSession(ImproveSession session) {
        if (improveSessionStarter != null) {
            improveSessionStarter.accept(session);
        }
    }
}
