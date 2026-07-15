package imagejai.local;

import ij.plugin.frame.RoiManager;
import imagejai.config.Settings;
import imagejai.engine.CommandEngine;
import imagejai.engine.FuzzyMatcher;
import imagejai.engine.FrictionLog;
import imagejai.engine.IntentRouter;
import imagejai.engine.LaunchPolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final Settings settings;
    private final ConversationContext ctx;
    private final PronounResolver pronouns;
    private Optional<PendingTurn> pending = Optional.empty();
    private ImproveSession improveSession;

    public LocalAssistant() {
        this(new Settings());
    }

    public LocalAssistant(Settings settings) {
        this(IntentLibrary.load(settings), new FijiBridge(new CommandEngine()), new FrictionLog(), settings);
    }

    public LocalAssistant(Settings settings, ChatHistoryController chatHistory) {
        this(IntentLibrary.load(settings), new FijiBridge(new CommandEngine()), new FrictionLog(),
                settings, new IntentRouter(), chatHistory);
    }

    public LocalAssistant(IntentLibrary library, FijiBridge fiji, FrictionLog frictionLog) {
        this(library, fiji, frictionLog, new Settings());
    }

    public LocalAssistant(IntentLibrary library, FijiBridge fiji, FrictionLog frictionLog,
                          Settings settings) {
        this(library, new IntentMatcher(library, settings), fiji, frictionLog, settings);
    }

    public LocalAssistant(IntentLibrary library, FijiBridge fiji, FrictionLog frictionLog,
                          Settings settings, IntentRouter intentRouter,
                          ChatHistoryController chatHistory) {
        this(library, new IntentMatcher(library, settings), fiji, frictionLog,
                intentRouter, chatHistory, new SlashCommandRegistry(), settings);
    }

    public LocalAssistant(IntentLibrary library, IntentMatcher matcher, FijiBridge fiji) {
        this(library, matcher, fiji, new FrictionLog());
    }

    public LocalAssistant(IntentLibrary library, IntentMatcher matcher, FijiBridge fiji,
                          FrictionLog frictionLog) {
        this(library, matcher, fiji, frictionLog, new Settings());
    }

    public LocalAssistant(IntentLibrary library, IntentMatcher matcher, FijiBridge fiji,
                          FrictionLog frictionLog, Settings settings) {
        this(library, matcher, fiji, frictionLog, new IntentRouter(), null,
                new SlashCommandRegistry(), settings);
    }

    public LocalAssistant(IntentLibrary library, IntentMatcher matcher, FijiBridge fiji,
                          FrictionLog frictionLog, IntentRouter intentRouter,
                          ChatHistoryController chatHistory) {
        this(library, matcher, fiji, frictionLog, intentRouter, chatHistory,
                new SlashCommandRegistry(), new Settings());
    }

    public LocalAssistant(IntentLibrary library, IntentMatcher matcher, FijiBridge fiji,
                          FrictionLog frictionLog, IntentRouter intentRouter,
                          ChatHistoryController chatHistory,
                          SlashCommandRegistry slashCommands) {
        this(library, matcher, fiji, frictionLog, intentRouter, chatHistory,
                slashCommands, new Settings());
    }

    public LocalAssistant(IntentLibrary library, IntentMatcher matcher, FijiBridge fiji,
                          FrictionLog frictionLog, IntentRouter intentRouter,
                          ChatHistoryController chatHistory,
                          SlashCommandRegistry slashCommands,
                          Settings settings) {
        this(library, matcher, fiji, frictionLog, intentRouter, chatHistory,
                slashCommands, settings, new ConversationContext(), new PronounResolver());
    }

    public LocalAssistant(IntentLibrary library, IntentMatcher matcher, FijiBridge fiji,
                          FrictionLog frictionLog, IntentRouter intentRouter,
                          ChatHistoryController chatHistory,
                          SlashCommandRegistry slashCommands,
                          Settings settings,
                          ConversationContext ctx,
                          PronounResolver pronouns) {
        this.library = library;
        this.matcher = matcher;
        this.fiji = fiji == null ? new FijiBridge(new CommandEngine()) : fiji;
        this.frictionLog = frictionLog == null ? new FrictionLog() : frictionLog;
        this.intentRouter = intentRouter == null ? new IntentRouter() : intentRouter;
        this.chatHistory = chatHistory;
        this.slashCommands = slashCommands == null ? new SlashCommandRegistry() : slashCommands;
        this.settings = settings == null ? new Settings() : settings;
        this.ctx = ctx == null ? new ConversationContext() : ctx;
        this.pronouns = pronouns == null ? new PronounResolver() : pronouns;
    }

    public AssistantReply handle(String input) {
        LaunchPolicy.Decision policy = LaunchPolicy.evaluate(
                "local-assistant", "builtin", settings.getPrivacyPosture(),
                LaunchPolicy.RequestedCapabilities.builder(
                                LaunchPolicy.Surface.LOCAL_ASSISTANT)
                        .localProvider(true)
                        .egress(false)
                        .mutation(true)
                        .safeMode(settings.safeModeEnabled)
                        .build());
        if (!policy.allowed()) {
            return AssistantReply.text("Local Assistant is blocked by the current policy: "
                    + policy.reason());
        }
        if (improveSession != null) {
            if (isClearCommand(input)) {
                improveSession = null;
            } else {
                AssistantReply reply = improveSession.handle(input);
                if (improveSession.isDone()) {
                    improveSession = null;
                }
                return reply;
            }
        }

        if (pending.isPresent()) {
            PendingTurn pendingTurn = pending.get();
            pending = Optional.empty();
            Optional<AssistantReply> resolved = tryResolve(pendingTurn, input);
            if (resolved.isPresent()) {
                return resolved.get();
            }
        }

        if (!SlashCommandRegistry.isSlashInput(input)) {
            Optional<PronounResolver.Rewrite> rewrite = pronouns.resolve(input, ctx);
            if (rewrite.isPresent()) {
                Intent target = library.byId(rewrite.get().intentId());
                if (target != null) {
                    return executeOrPrompt(target, rewrite.get().slots());
                }
            }
        }

        if (isClearCommand(input)) {
            clearConversation();
        }

        if (improveSession != null) {
            AssistantReply reply = improveSession.handle(input);
            if (improveSession.isDone()) {
                improveSession = null;
            }
            return reply;
        }

        // Slash commands are dispatched before phrasebook lookup so a literal
        // /name command always wins over any built-in phrasebook alias.
        if (SlashCommandRegistry.isSlashInput(input)) {
            return slashCommands.dispatchSlash(input, fiji, library, intentRouter, chatHistory,
                    matcher, frictionLog.journal(),
                    () -> improveSession == null,
                    session -> improveSession = session);
        }
        Optional<IntentMatcher.MatchedIntent> matched = matcher.match(input);
        if (matched.isPresent()) {
            IntentMatcher.MatchedIntent match = matched.get();
            AssistantReply slashAlias = slashCommands.dispatchIntent(match.intentId(), input,
                    fiji, library, intentRouter, chatHistory,
                    matcher, frictionLog.journal(),
                    () -> improveSession == null,
                    session -> improveSession = session);
            if (slashAlias != null) {
                if ("slash.clear".equals(match.intentId())) {
                    clearConversation();
                }
                return slashAlias;
            }
            Match2Result match2 = matcher.match2(input,
                    settings.getLocalAssistantDisambiguationMargin());
            if (match2.isAmbiguous()) {
                List<RankedPhrase> candidates = new ArrayList<RankedPhrase>();
                candidates.add(match2.best());
                candidates.add(match2.runnerUp());
                pending = Optional.of(PendingTurn.disambiguation("Did you mean:", candidates));
                frictionLog.record("local_assistant", input, "event=disambiguation_shown");
                return AssistantReply.clarifying("Did you mean:", candidates);
            }
            Intent intent = library.byId(match.intentId());
            if (intent != null) {
                return executeOrPrompt(intent, match.slots());
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

    public Optional<ImproveSession> improveSessionForTest() {
        return Optional.ofNullable(improveSession);
    }

    public List<RankedPhrase> topK(String input, int k) {
        return matcher.topK(input, k);
    }

    public void clearPending() {
        pending = Optional.empty();
        improveSession = null;
    }

    public void clearConversation() {
        pending = Optional.empty();
        improveSession = null;
        ctx.clear();
    }

    public void parkImproveSession(ImproveSession session) {
        improveSession = session;
    }

    Optional<PendingTurn> pendingTurnForTest() {
        return pending;
    }

    void parkPendingForTest(PendingTurn pendingTurn) {
        pending = Optional.ofNullable(pendingTurn);
    }

    ConversationContext conversationContextForTest() {
        return ctx;
    }

    Optional<AssistantReply> tryResolveForTest(PendingTurn pendingTurn, String input) {
        return tryResolve(pendingTurn, input);
    }

    private Optional<AssistantReply> tryResolve(PendingTurn pendingTurn, String input) {
        if (pendingTurn == null) {
            return Optional.empty();
        }
        switch (pendingTurn.kind()) {
            case PARAMETER:
                return resolveParameter(pendingTurn, input);
            case DISAMBIGUATION:
                return resolveDisambiguation(pendingTurn, input);
            default:
                return Optional.empty();
        }
    }

    private Optional<AssistantReply> resolveDisambiguation(PendingTurn pendingTurn, String input) {
        String key = IntentMatcher.normalise(input);
        for (RankedPhrase candidate : pendingTurn.candidates()) {
            if (key.equals(IntentMatcher.normalise(candidate.phrase()))) {
                return executeDisambiguationCandidate(candidate);
            }
        }

        RankedPhrase best = null;
        RankedPhrase runner = null;
        double bestScore = 0.0;
        double runnerScore = 0.0;
        for (RankedPhrase candidate : pendingTurn.candidates()) {
            double score = FuzzyMatcher.jaroWinkler(key,
                    IntentMatcher.normalise(candidate.phrase()));
            if (score > bestScore) {
                runnerScore = bestScore;
                runner = best;
                bestScore = score;
                best = candidate;
            } else if (score > runnerScore) {
                runnerScore = score;
                runner = candidate;
            }
        }
        double threshold = settings.getLocalAssistantFuzzyThreshold();
        double margin = settings.getLocalAssistantDisambiguationMargin();
        if (best != null && bestScore >= threshold
                && ((bestScore - runnerScore) >= margin
                    || sameCandidatePhrase(best, runner))) {
            return executeDisambiguationCandidate(best);
        }
        return Optional.empty();
    }

    private static boolean sameCandidatePhrase(RankedPhrase a, RankedPhrase b) {
        return a != null && b != null
                && IntentMatcher.normalise(a.phrase())
                        .equals(IntentMatcher.normalise(b.phrase()));
    }

    private Optional<AssistantReply> executeDisambiguationCandidate(RankedPhrase candidate) {
        Intent intent = library.byId(candidate.intentId());
        if (intent == null) {
            return Optional.empty();
        }
        return Optional.of(executeOrPrompt(intent, Collections.<String, String>emptyMap()));
    }

    private Optional<AssistantReply> resolveParameter(PendingTurn pendingTurn, String input) {
        String value = stripLeadingWords(input == null ? "" : input.trim());
        if (value.length() == 0) {
            if (pendingTurn.defaultValue() == null) {
                return Optional.empty();
            }
            value = pendingTurn.defaultValue();
        }
        if (!isNumeric(value)) {
            return Optional.empty();
        }

        Intent intent = library.byId(pendingTurn.intentId());
        if (intent == null) {
            return Optional.empty();
        }

        Map<String, String> slots = new LinkedHashMap<String, String>(pendingTurn.filledSlots());
        slots.put(pendingTurn.missingSlot(), value);
        return Optional.of(executeOrPrompt(intent, slots));
    }

    private AssistantReply executeOrPrompt(Intent intent, Map<String, String> slots) {
        Map<String, String> filled = slots == null
                ? Collections.<String, String>emptyMap()
                : slots;
        List<SlotSpec> missing = computeMissing(intent.requiredSlots(), filled);
        if (!missing.isEmpty()) {
            SlotSpec first = missing.get(0);
            String question = parameterQuestion(first);
            pending = Optional.of(PendingTurn.parameter(
                    intent.id(),
                    filled,
                    first.name(),
                    question,
                    first.defaultValue()));
            return AssistantReply.text(question);
        }

        AssistantReply reply = intent.execute(filled, fiji);
        if (shouldRecordIntentRun(reply)) {
            ctx.recordIntentRun(intent.id(), filled);
            recordSelectedRoi();
        }
        return reply;
    }

    private boolean shouldRecordIntentRun(AssistantReply reply) {
        if (reply == null) {
            return false;
        }
        String text = reply.text() == null ? "" : reply.text().trim().toLowerCase(java.util.Locale.ROOT);
        if ("no image is open.".equals(text) || "no images are open.".equals(text)) {
            return false;
        }
        if (text.startsWith("could not ") || text.startsWith("cannot ")
                || text.endsWith(" is empty.") || text.contains(" cancelled.")
                || text.startsWith("tell me ") || text.startsWith("add ")
                || text.startsWith("draw ")) {
            return false;
        }
        return true;
    }

    private void recordSelectedRoi() {
        try {
            RoiManager rm = fiji.currentRoiManager();
            if (rm != null && rm.getSelectedIndex() >= 0) {
                ctx.recordRoiSelection(rm.getSelectedIndex());
            }
        } catch (RuntimeException ignored) {
            // ROI context is best-effort; intent success should not depend on it.
        }
    }

    private static List<SlotSpec> computeMissing(List<SlotSpec> required,
                                                 Map<String, String> filled) {
        List<SlotSpec> missing = new ArrayList<SlotSpec>();
        if (required == null) {
            return missing;
        }
        for (SlotSpec spec : required) {
            String value = filled == null ? null : filled.get(spec.name());
            if (value == null || value.trim().length() == 0) {
                missing.add(spec);
            }
        }
        return missing;
    }

    private static String parameterQuestion(SlotSpec slot) {
        String prompt = "What " + slot.prompt() + "? ";
        if (slot.hasDefault()) {
            prompt += "Default: " + slot.defaultValue() + ". ";
        }
        return prompt + "(or type a new request to cancel)";
    }

    private static boolean isClearCommand(String input) {
        return "/clear".equalsIgnoreCase(input == null ? "" : input.trim());
    }

    private static String stripLeadingWords(String value) {
        return value.replaceAll("^[A-Za-z ]+", "").trim();
    }

    private static boolean isNumeric(String value) {
        return value.matches("^-?\\d+(?:\\.\\d+)?$");
    }

}
