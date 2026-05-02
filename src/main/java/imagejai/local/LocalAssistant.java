package imagejai.local;

import imagejai.config.Settings;
import imagejai.engine.CommandEngine;
import imagejai.engine.FrictionLog;
import imagejai.engine.IntentRouter;

import java.util.List;
import java.util.Optional;

/**
 * Offline deterministic assistant entry point.
 */
public class LocalAssistant {

    private final IntentLibrary library;
    private final IntentMatcher matcher;
    private final FijiBridge fiji;
    private final FrictionLog frictionLog;
    private final IntentRouter intentRouter;
    private final SlashCommandRegistry slashCommands;
    private final ChatHistoryController chatHistory;
    private Optional<PendingTurn> pending = Optional.empty();

    public LocalAssistant() {
        this(IntentLibrary.load(), new FijiBridge(new CommandEngine()), new FrictionLog());
    }

    public LocalAssistant(Settings settings) {
        this(IntentLibrary.load(settings), new FijiBridge(new CommandEngine()), new FrictionLog(), settings);
    }

    public LocalAssistant(Settings settings, ChatHistoryController chatHistory) {
        this(IntentLibrary.load(settings), new FijiBridge(new CommandEngine()), new FrictionLog(),
                settings, new IntentRouter(), chatHistory);
    }

    public LocalAssistant(IntentLibrary library, FijiBridge fiji, FrictionLog frictionLog) {
        this(library, new IntentMatcher(library), fiji, frictionLog);
    }

    public LocalAssistant(IntentLibrary library, FijiBridge fiji, FrictionLog frictionLog,
                          Settings settings) {
        this(library, new IntentMatcher(library, settings), fiji, frictionLog);
    }

    public LocalAssistant(IntentLibrary library, FijiBridge fiji, FrictionLog frictionLog,
                          Settings settings, IntentRouter intentRouter,
                          ChatHistoryController chatHistory) {
        this(library, new IntentMatcher(library, settings), fiji, frictionLog,
                intentRouter, chatHistory);
    }

    public LocalAssistant(IntentLibrary library, IntentMatcher matcher, FijiBridge fiji) {
        this(library, matcher, fiji, new FrictionLog());
    }

    public LocalAssistant(IntentLibrary library, IntentMatcher matcher, FijiBridge fiji,
                          FrictionLog frictionLog) {
        this(library, matcher, fiji, frictionLog, new IntentRouter(), null, new SlashCommandRegistry());
    }

    public LocalAssistant(IntentLibrary library, IntentMatcher matcher, FijiBridge fiji,
                          FrictionLog frictionLog, IntentRouter intentRouter,
                          ChatHistoryController chatHistory) {
        this(library, matcher, fiji, frictionLog, intentRouter, chatHistory,
                new SlashCommandRegistry());
    }

    public LocalAssistant(IntentLibrary library, IntentMatcher matcher, FijiBridge fiji,
                          FrictionLog frictionLog, IntentRouter intentRouter,
                          ChatHistoryController chatHistory,
                          SlashCommandRegistry slashCommands) {
        this.library = library;
        this.matcher = matcher;
        this.fiji = fiji;
        this.frictionLog = frictionLog == null ? new FrictionLog() : frictionLog;
        this.intentRouter = intentRouter == null ? new IntentRouter() : intentRouter;
        this.chatHistory = chatHistory;
        this.slashCommands = slashCommands == null ? new SlashCommandRegistry() : slashCommands;
    }

    public AssistantReply handle(String input) {
        if (pending.isPresent()) {
            PendingTurn pendingTurn = pending.get();
            Optional<AssistantReply> resolved = tryResolve(pendingTurn, input);
            pending = Optional.empty();
            if (resolved.isPresent()) {
                return resolved.get();
            }
        }
        // Slash commands are dispatched before phrasebook lookup so a literal
        // /name command always wins over any built-in phrasebook alias.
        if (SlashCommandRegistry.isSlashInput(input)) {
            return slashCommands.dispatchSlash(input, fiji, library, intentRouter,
                    chatHistory, this::clearPending);
        }
        Optional<IntentMatcher.MatchedIntent> matched = matcher.match(input);
        if (matched.isPresent()) {
            IntentMatcher.MatchedIntent match = matched.get();
            AssistantReply slashAlias = slashCommands.dispatchIntent(match.intentId(), input,
                    fiji, library, intentRouter, chatHistory, this::clearPending);
            if (slashAlias != null) {
                return slashAlias;
            }
            Intent intent = library.byId(match.intentId());
            if (intent != null) {
                return intent.execute(match.slots(), fiji);
            }
        }
        Optional<IntentRouter.Resolved> userMatch = intentRouter.resolve(input);
        if (userMatch.isPresent()) {
            IntentRouter.Resolved resolved = userMatch.get();
            fiji.runMacro(resolved.macro);
            return AssistantReply.withMacro(
                    "Ran user-taught intent: " + resolved.mapping.patternSrc,
                    resolved.macro);
        }
        frictionLog.record("local_assistant", input, "miss");
        return AssistantReply.text("I don't recognise \"" + input
                + "\". Type 'help' to see what I can do.");
    }

    public List<RankedPhrase> topK(String input, int k) {
        return matcher.topK(input, k);
    }

    public void clearPending() {
        pending = Optional.empty();
    }

    Optional<PendingTurn> pendingTurnForTest() {
        return pending;
    }

    void parkPendingForTest(PendingTurn pendingTurn) {
        pending = Optional.ofNullable(pendingTurn);
    }

    Optional<AssistantReply> tryResolveForTest(PendingTurn pendingTurn, String input) {
        return tryResolve(pendingTurn, input);
    }

    private Optional<AssistantReply> tryResolve(PendingTurn pendingTurn, String input) {
        switch (pendingTurn.kind()) {
            case PARAMETER:
                return Optional.empty();
            case DISAMBIGUATION:
                return Optional.empty();
            default:
                return Optional.empty();
        }
    }
}
