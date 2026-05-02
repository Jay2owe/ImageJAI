package imagejai.local;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import imagejai.config.Settings;
import imagejai.local.intents.HelpIntent;
import imagejai.local.intents.PixelSizeIntent;
import imagejai.local.intents.analysis.AnalysisIntentFactory;
import imagejai.local.intents.control.ControlIntentFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of built-in Local Assistant intents.
 */
public class IntentLibrary {

    private final Map<String, Intent> intents = new LinkedHashMap<String, Intent>();
    private final Map<String, String> phraseToIntentId = new LinkedHashMap<String, String>();
    private final List<String> phrases = new ArrayList<String>();

    public IntentLibrary() {
        this(new Settings());
    }

    public IntentLibrary(Settings settings) {
        registerBuiltIns();
        loadPhrasebookResource();
        MenuIntentImporter.importInto(this, settings != null && settings.expandMenuPhrasebook);
    }

    public static IntentLibrary load() {
        return new IntentLibrary();
    }

    public static IntentLibrary load(Settings settings) {
        return new IntentLibrary(settings);
    }

    private void registerBuiltIns() {
        register(new HelpIntent());
        register(new PixelSizeIntent());
        for (Intent intent : ControlIntentFactory.createAll()) {
            register(intent);
        }
        for (Intent intent : AnalysisIntentFactory.createAll()) {
            register(intent);
        }
        addSlashAliases();
        addPhrase("help", "slash.help");
    }

    private void addSlashAliases() {
        addPhrase("what slash commands can I use", "slash.help");
        addPhrase("clear chat", "slash.clear");
        addPhrase("show my macros", "slash.macros");
        addPhrase("info", "slash.info");
        addPhrase("show open images", "slash.info");
        addPhrase("close images", "slash.close");
        addPhrase("teach a command", "slash.teach");
        addPhrase("show learned intents", "slash.intents");
        addPhrase("forget a learned intent", "slash.forget");
    }

    public Intent byId(String id) {
        return intents.get(id);
    }

    public Collection<Intent> all() {
        return Collections.unmodifiableCollection(intents.values());
    }

    public Map<String, String> phraseToIntentId() {
        return Collections.unmodifiableMap(phraseToIntentId);
    }

    public List<String> allPhrases() {
        return Collections.unmodifiableList(phrases);
    }

    public void register(Intent intent) {
        if (intent == null) {
            return;
        }
        intents.put(intent.id(), intent);
    }

    private void loadPhrasebookResource() {
        InputStream in = getClass().getResourceAsStream("/phrasebook.json");
        if (in == null) {
            throw new IllegalStateException("Missing /phrasebook.json resource");
        }

        try {
            Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            Phrasebook phrasebook = new Gson().fromJson(reader, Phrasebook.class);
            if (phrasebook == null || phrasebook.intents == null) {
                return;
            }
            for (PhrasebookIntent entry : phrasebook.intents) {
                if (entry == null || entry.id == null || entry.phrases == null) {
                    continue;
                }
                for (String phrase : entry.phrases) {
                    addPhrase(phrase, entry.id);
                }
            }
        } catch (JsonSyntaxException e) {
            throw new IllegalStateException("Invalid /phrasebook.json", e);
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
                // Nothing useful to recover here; the stream was already read.
            }
        }
    }

    private void addPhrase(String phrase, String intentId) {
        String normalised = IntentMatcher.normalise(phrase);
        if (normalised.length() == 0 || intentId == null) {
            return;
        }
        if (!phraseToIntentId.containsKey(normalised)) {
            phrases.add(normalised);
        }
        phraseToIntentId.put(normalised, intentId);
    }

    public void addPhraseIfAbsent(String phrase, String intentId) {
        String normalised = IntentMatcher.normalise(phrase);
        if (normalised.length() == 0 || intentId == null
                || phraseToIntentId.containsKey(normalised)) {
            return;
        }
        phrases.add(normalised);
        phraseToIntentId.put(normalised, intentId);
    }

    private static class Phrasebook {
        int version;
        List<PhrasebookIntent> intents;
    }

    private static class PhrasebookIntent {
        String id;
        String description;
        List<String> phrases;
    }
}
