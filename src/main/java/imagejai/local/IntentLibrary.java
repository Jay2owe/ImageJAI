package imagejai.local;

import imagejai.local.intents.HelpIntent;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registry of built-in Local Assistant intents.
 */
public class IntentLibrary {

    private final Map<String, Intent> intents = new LinkedHashMap<String, Intent>();

    public IntentLibrary() {
        register(new HelpIntent());
    }

    public Intent byId(String id) {
        return intents.get(id);
    }

    public Collection<Intent> all() {
        return java.util.Collections.unmodifiableCollection(intents.values());
    }

    public void register(Intent intent) {
        if (intent == null) {
            return;
        }
        intents.put(intent.id(), intent);
    }
}
