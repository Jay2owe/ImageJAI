package imagejai.local;

import imagejai.engine.MenuCommandRegistry;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Adds synthetic Local Assistant intents for the ImageJ/Fiji menu commands
 * visible in this JVM.
 */
public final class MenuIntentImporter {

    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Pattern EDGE_DOTS = Pattern.compile("^\\.+|\\.+$");
    private static final Pattern TRAILING_DOTS = Pattern.compile("\\.+$");

    private MenuIntentImporter() {
    }

    public static int importInto(IntentLibrary library) {
        return importInto(library, MenuCommandRegistry.allCommandNames(), false);
    }

    public static int importInto(IntentLibrary library, boolean expandPhrasebook) {
        return importInto(library, MenuCommandRegistry.allCommandNames(), expandPhrasebook);
    }

    static int importInto(IntentLibrary library, List<String> commandNames,
                          boolean expandPhrasebook) {
        if (library == null || commandNames == null) {
            return 0;
        }

        int added = 0;
        for (String commandName : commandNames) {
            if (commandName == null || commandName.trim().length() == 0) {
                continue;
            }
            String id = "menu." + slugify(commandName);
            if ("menu.".equals(id) || library.byId(id) != null) {
                continue;
            }
            library.register(new MenuIntent(id, commandName));
            if (expandPhrasebook) {
                addCommandPhrases(library, id, commandName);
            }
            added++;
        }
        return added;
    }

    static String slugify(String value) {
        if (value == null) {
            return "";
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return EDGE_DOTS.matcher(NON_ALNUM.matcher(lower).replaceAll(".")).replaceAll("");
    }

    private static void addCommandPhrases(IntentLibrary library, String id,
                                          String commandName) {
        String lower = commandName.toLowerCase(Locale.ROOT);
        library.addPhraseIfAbsent(lower, id);
        library.addPhraseIfAbsent(TRAILING_DOTS.matcher(lower).replaceAll(""), id);
    }

    private static final class MenuIntent implements Intent {
        private final String id;
        private final String commandName;

        MenuIntent(String id, String commandName) {
            this.id = id;
            this.commandName = commandName;
        }

        public String id() {
            return id;
        }

        public String description() {
            return "Open menu command: " + commandName;
        }

        public AssistantReply execute(Map<String, String> slots, FijiBridge fiji) {
            String macro = "run(\"" + macroQuote(commandName) + "\");";
            fiji.runMacro(macro);
            return AssistantReply.withMacro("Opened: " + commandName, macro);
        }

        private static String macroQuote(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
