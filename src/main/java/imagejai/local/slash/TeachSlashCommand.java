package imagejai.local.slash;

import imagejai.engine.IntentRouter;
import imagejai.local.AssistantReply;
import imagejai.local.SlashCommand;
import imagejai.local.SlashCommandContext;

import java.util.regex.Pattern;

public class TeachSlashCommand implements SlashCommand {
    public String name() { return "teach"; }
    public String intentId() { return "slash.teach"; }
    public String description() { return "Teach a literal phrase to run a macro"; }

    public AssistantReply execute(SlashCommandContext context) {
        String args = context.args();
        int sep = args.indexOf("=>");
        if (sep < 0) {
            return AssistantReply.text("usage: /teach <phrase> => <macro>");
        }
        String phrase = args.substring(0, sep).trim();
        String macro = args.substring(sep + 2).trim();
        if (phrase.length() == 0 || macro.length() == 0) {
            return AssistantReply.text("usage: /teach <phrase> => <macro>");
        }
        String regex = Pattern.quote(phrase);
        IntentRouter.Mapping mapping;
        try {
            mapping = context.intentRouter().teach(regex, macro, "User phrase: " + phrase);
        } catch (IllegalArgumentException e) {
            return AssistantReply.text(e.getMessage());
        }
        return AssistantReply.text("Parsed regex: " + mapping.patternSrc
                + "\nTaught \"" + phrase + "\".");
    }
}
