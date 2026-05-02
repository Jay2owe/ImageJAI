package imagejai.local;

import imagejai.engine.CommandEngine;

import java.util.Optional;

/**
 * Offline deterministic assistant entry point.
 */
public class LocalAssistant {

    private final IntentLibrary library;
    private final IntentMatcher matcher;
    private final FijiBridge fiji;

    public LocalAssistant() {
        this(new IntentLibrary(), new IntentMatcher(), new FijiBridge(new CommandEngine()));
    }

    public LocalAssistant(IntentLibrary library, IntentMatcher matcher, FijiBridge fiji) {
        this.library = library;
        this.matcher = matcher;
        this.fiji = fiji;
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
        return AssistantReply.text("I don't recognise \"" + input
                + "\". Type 'help' to see what I can do.");
    }
}
