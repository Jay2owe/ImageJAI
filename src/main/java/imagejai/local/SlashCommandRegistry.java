package imagejai.local;

import imagejai.engine.IntentRouter;
import imagejai.local.slash.ClearSlashCommand;
import imagejai.local.slash.CloseSlashCommand;
import imagejai.local.slash.ForgetSlashCommand;
import imagejai.local.slash.HelpSlashCommand;
import imagejai.local.slash.InfoSlashCommand;
import imagejai.local.slash.IntentsSlashCommand;
import imagejai.local.slash.MacrosSlashCommand;
import imagejai.local.slash.TeachSlashCommand;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Dispatch table for Local Assistant slash commands.
 */
public class SlashCommandRegistry {
    private final Map<String, SlashCommand> byName = new LinkedHashMap<String, SlashCommand>();
    private final Map<String, SlashCommand> byIntentId = new LinkedHashMap<String, SlashCommand>();

    public SlashCommandRegistry() {
        register(new HelpSlashCommand());
        register(new ClearSlashCommand());
        register(new MacrosSlashCommand());
        register(new InfoSlashCommand());
        register(new CloseSlashCommand());
        register(new TeachSlashCommand());
        register(new IntentsSlashCommand());
        register(new ForgetSlashCommand());
    }

    public void register(SlashCommand command) {
        if (command == null) {
            return;
        }
        byName.put(command.name().toLowerCase(Locale.ROOT), command);
        byIntentId.put(command.intentId(), command);
    }

    public AssistantReply dispatchSlash(String input, FijiBridge fiji, IntentLibrary library,
                                        IntentRouter router, ChatHistoryController chatHistory) {
        return dispatchSlash(input, fiji, library, router, chatHistory, null);
    }

    public AssistantReply dispatchSlash(String input, FijiBridge fiji, IntentLibrary library,
                                        IntentRouter router, ChatHistoryController chatHistory,
                                        Runnable clearPending) {
        Parsed parsed = parse(input);
        if (parsed == null) {
            return null;
        }
        SlashCommand command = byName.get(parsed.name);
        if (command == null) {
            return AssistantReply.text("Unknown command: /" + parsed.name + ". Type /help.");
        }
        return command.execute(new SlashCommandContext(parsed.args, fiji, library, router,
                chatHistory, clearPending));
    }

    public AssistantReply dispatchIntent(String intentId, String input, FijiBridge fiji,
                                         IntentLibrary library, IntentRouter router,
                                         ChatHistoryController chatHistory) {
        return dispatchIntent(intentId, input, fiji, library, router, chatHistory, null);
    }

    public AssistantReply dispatchIntent(String intentId, String input, FijiBridge fiji,
                                         IntentLibrary library, IntentRouter router,
                                         ChatHistoryController chatHistory,
                                         Runnable clearPending) {
        SlashCommand command = byIntentId.get(intentId);
        if (command == null) {
            return null;
        }
        String args = input == null ? "" : input.trim();
        return command.execute(new SlashCommandContext(args, fiji, library, router,
                chatHistory, clearPending));
    }

    public Collection<SlashCommand> commands() {
        return Collections.unmodifiableCollection(byName.values());
    }

    public static boolean isSlashInput(String input) {
        return input != null && input.trim().startsWith("/");
    }

    private static Parsed parse(String input) {
        if (!isSlashInput(input)) {
            return null;
        }
        String trimmed = input.trim();
        int split = -1;
        for (int i = 1; i < trimmed.length(); i++) {
            if (Character.isWhitespace(trimmed.charAt(i))) {
                split = i;
                break;
            }
        }
        String name = split < 0 ? trimmed.substring(1) : trimmed.substring(1, split);
        String args = split < 0 ? "" : trimmed.substring(split).trim();
        return new Parsed(name.toLowerCase(Locale.ROOT), args);
    }

    private static class Parsed {
        final String name;
        final String args;

        Parsed(String name, String args) {
            this.name = name;
            this.args = args;
        }
    }
}
