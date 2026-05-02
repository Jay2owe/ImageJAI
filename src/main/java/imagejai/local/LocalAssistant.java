package imagejai.local;

import imagejai.engine.CommandEngine;
import imagejai.engine.FrictionLog;

import java.util.Optional;

/**
 * Offline deterministic assistant entry point.
 */
public class LocalAssistant {

    private final IntentLibrary library;
    private final IntentMatcher matcher;
    private final FijiBridge fiji;
    private final FrictionLog frictionLog;

    public LocalAssistant() {
        this(IntentLibrary.load(), new FijiBridge(new CommandEngine()), new FrictionLog());
    }

    public LocalAssistant(IntentLibrary library, FijiBridge fiji, FrictionLog frictionLog) {
        this(library, new IntentMatcher(library), fiji, frictionLog);
    }

    public LocalAssistant(IntentLibrary library, IntentMatcher matcher, FijiBridge fiji) {
        this(library, matcher, fiji, new FrictionLog());
    }

    public LocalAssistant(IntentLibrary library, IntentMatcher matcher, FijiBridge fiji,
                          FrictionLog frictionLog) {
        this.library = library;
        this.matcher = matcher;
        this.fiji = fiji;
        this.frictionLog = frictionLog == null ? new FrictionLog() : frictionLog;
    }

    public AssistantReply handle(String input) {
        Optional<IntentMatcher.MatchedIntent> matched = matcher.match(input);
        if (matched.isPresent()) {
            IntentMatcher.MatchedIntent match = matched.get();
            Intent intent = library.byId(match.intentId());
            if (intent != null) {
                return intent.execute(match.slots(), fiji);
            }
        }
        frictionLog.record("local_assistant", input, "miss");
        return AssistantReply.text("I don't recognise \"" + input
                + "\". Type 'help' to see what I can do.");
    }
}
