package imagejai.local.slash;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import imagejai.local.AssistantReply;
import imagejai.local.SlashCommand;
import imagejai.local.SlashCommandContext;

public class IntentsSlashCommand implements SlashCommand {
    public String name() { return "intents"; }
    public String intentId() { return "slash.intents"; }
    public String description() { return "List user-taught intents"; }

    public AssistantReply execute(SlashCommandContext context) {
        JsonObject listed = context.intentRouter().list();
        JsonArray mappings = listed.getAsJsonArray("mappings");
        JsonArray quarantined = listed.getAsJsonArray("quarantined");
        if ((mappings == null || mappings.size() == 0)
                && (quarantined == null || quarantined.size() == 0)) {
            return AssistantReply.text("No user-taught intents.");
        }
        StringBuilder sb = new StringBuilder("User-taught intents:\nPattern | Hits | Last used | Macro");
        if (mappings != null) {
            for (JsonElement element : mappings) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject m = element.getAsJsonObject();
                sb.append("\n").append(str(m, "pattern"))
                        .append(" | ").append(str(m, "hits"))
                        .append(" | ").append(str(m, "last_used"))
                        .append(" | ").append(str(m, "macro"));
            }
        }
        if (quarantined != null && quarantined.size() > 0) {
            sb.append("\n\nQuarantined:");
            for (JsonElement element : quarantined) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject q = element.getAsJsonObject();
                sb.append("\n").append(str(q, "pattern"))
                        .append(" | ").append(str(q, "error"));
            }
        }
        return AssistantReply.text(sb.toString());
    }

    private static String str(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || element.isJsonNull()) {
            return "-";
        }
        return element.getAsString();
    }
}
