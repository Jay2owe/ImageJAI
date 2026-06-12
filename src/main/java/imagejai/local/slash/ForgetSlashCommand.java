package imagejai.local.slash;

import imagejai.local.AssistantReply;
import imagejai.local.SlashCommand;
import imagejai.local.SlashCommandContext;

import java.util.regex.Pattern;

public class ForgetSlashCommand implements SlashCommand {
    public String name() { return "forget"; }
    public String intentId() { return "slash.forget"; }
    public String description() { return "Forget a user-taught intent"; }

    public AssistantReply execute(SlashCommandContext context) {
        String target = context.args().trim();
        if (target.length() == 0) {
            return AssistantReply.text("usage: /forget <phrase-or-pattern>");
        }
        boolean removed = context.intentRouter().forget(target);
        if (!removed) {
            removed = context.intentRouter().forget(Pattern.quote(target));
        }
        return AssistantReply.text(removed
                ? "Forgot: " + target
                : "No user-taught intent matched: " + target);
    }
}
