package imagejai.local.slash;

import imagejai.local.AssistantReply;
import imagejai.local.Intent;
import imagejai.local.IntentLibrary;
import imagejai.local.SlashCommand;
import imagejai.local.SlashCommandContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class HelpSlashCommand implements SlashCommand {
    public String name() { return "help"; }
    public String intentId() { return "slash.help"; }
    public String description() { return "List Local Assistant commands"; }

    public AssistantReply execute(SlashCommandContext context) {
        return AssistantReply.text(buildHelp(context.library()));
    }

    static String buildHelp(IntentLibrary library) {
        Map<String, List<Intent>> grouped = new LinkedHashMap<String, List<Intent>>();
        if (library != null) {
            for (Intent intent : library.all()) {
                String category = categoryFor(intent.id());
                List<Intent> list = grouped.get(category);
                if (list == null) {
                    list = new ArrayList<Intent>();
                    grouped.put(category, list);
                }
                list.add(intent);
            }
        }

        StringBuilder sb = new StringBuilder("Local Assistant commands:");
        for (Map.Entry<String, List<Intent>> entry : grouped.entrySet()) {
            sb.append("\n\n").append(entry.getKey()).append(":");
            List<Intent> intents = entry.getValue();
            Collections.sort(intents, new java.util.Comparator<Intent>() {
                public int compare(Intent a, Intent b) {
                    return a.id().compareTo(b.id());
                }
            });
            for (Intent intent : intents) {
                sb.append("\n- ").append(intent.id()).append(": ")
                        .append(intent.description());
            }
        }
        sb.append("\n\nSlash commands:");
        sb.append("\n- /help: list built-in intents grouped by category");
        sb.append("\n- /clear: clear the chat history");
        sb.append("\n- /macros: list Fiji and learned macros");
        sb.append("\n- /info: table of open images");
        sb.append("\n- /close [all|all but active|name]: close image windows");
        sb.append("\n- /teach <phrase> => <macro>: teach a literal phrase");
        sb.append("\n- /intents: list user-taught intents");
        sb.append("\n- /forget <phrase-or-pattern>: remove a user-taught intent");
        return sb.toString();
    }

    private static String categoryFor(String id) {
        if (id == null) return "Other";
        if (id.startsWith("preprocess.")) return "Preprocessing";
        if (id.startsWith("segmentation.")) return "Segmentation and counting";
        if (id.startsWith("measurement.")) return "Measurement and quantification";
        if (id.startsWith("roi.") || id.startsWith("results.")) return "ROIs and results I/O";
        if (id.startsWith("display.")) return "Display";
        if (id.startsWith("dialog.")) return "Common dialogs";
        if (id.startsWith("diagnostics.")) return "State and diagnostics";
        if (id.startsWith("builtin.")) return "Help and meta";
        if (id.startsWith("image.")) {
            if (id.startsWith("image.close_") || id.startsWith("image.duplicate_")
                    || id.startsWith("image.revert") || id.startsWith("image.save_")
                    || id.startsWith("image.next_") || id.startsWith("image.previous_")
                    || id.startsWith("image.switch_") || id.startsWith("image.jump_")
                    || id.startsWith("image.merge_") || id.startsWith("image.split_")
                    || id.startsWith("image.z_project_") || id.startsWith("image.make_")
                    || id.startsWith("image.crop_") || id.startsWith("image.scale_")
                    || id.startsWith("image.invert_") || id.startsWith("image.convert_")
                    || id.startsWith("image.set_scale")) {
                return "Image control";
            }
            return "Image inspection";
        }
        return "Other";
    }
}
