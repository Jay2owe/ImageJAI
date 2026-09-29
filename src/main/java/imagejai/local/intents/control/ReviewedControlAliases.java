package imagejai.local.intents.control;

import imagejai.local.AssistantReply;
import imagejai.local.FijiBridge;
import imagejai.local.Intent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Compatibility handlers for the reviewed aggregate IDs in tools/intents.yaml.
 *
 * <p>Each handler delegates to the corresponding split implementation. This
 * keeps the original phrasebook contract without introducing a second action
 * implementation for the same operation.</p>
 */
final class ReviewedControlAliases {

    private ReviewedControlAliases() {
    }

    static List<Intent> createAll() {
        List<Intent> aliases = new ArrayList<Intent>();
        aliases.add(alias("image.stack_counts",
                "Report the number of channels, slices, and frames in the active image",
                new ImageDimensionsIntent()));
        aliases.add(combined("image.position",
                "Report the active channel, slice, and frame number",
                new ActiveChannelIntent(), new ActiveSliceIntent(), new ActiveFrameIntent()));
        aliases.add(combined("image.title_path",
                "Report the active image title and file path",
                new ImageTitleIntent(), new FilePathIntent()));
        aliases.add(alias("image.duplicate", "Duplicate the active image",
                new DuplicateActiveIntent()));

        Map<String, Intent> saveFormats = new LinkedHashMap<String, Intent>();
        saveFormats.put("tiff", new SaveAsTiffIntent());
        saveFormats.put("png", new SaveAsPngIntent());
        saveFormats.put("jpeg", new SaveAsJpegIntent());
        aliases.add(select("image.save_as",
                "Save the active image as TIFF, PNG, or JPEG under AI_Exports next to the opened image",
                "format", "tiff", saveFormats));

        Map<String, Intent> projectionMethods = new LinkedHashMap<String, Intent>();
        projectionMethods.put("max", new ZProjectMaxIntent());
        projectionMethods.put("mean", new ZProjectMeanIntent());
        projectionMethods.put("sum", new ZProjectSumIntent());
        projectionMethods.put("sd", new ZProjectSdIntent());
        aliases.add(select("image.z_project",
                "Create a z projection using max, mean, sum, standard deviation, or another projection method",
                "projection", "max", projectionMethods));

        aliases.add(alias("image.scale", "Scale the active image by a numeric factor",
                new ScaleByFactorIntent()));
        aliases.add(alias("image.invert", "Invert the active image pixel values",
                new InvertImageIntent()));

        Map<String, Intent> imageTypes = new LinkedHashMap<String, Intent>();
        imageTypes.put("8bit", new ConvertTo8BitIntent());
        imageTypes.put("16bit", new ConvertTo16BitIntent());
        imageTypes.put("32bit", new ConvertTo32BitIntent());
        imageTypes.put("rgb", new ConvertToRgbIntent());
        imageTypes.put("composite", new ConvertToCompositeIntent());
        aliases.add(select("image.convert_type",
                "Convert the active image to 8-bit, 16-bit, 32-bit, RGB, or composite display",
                "image_type", "8bit", imageTypes));

        aliases.add(alias("builtin.agent", "Report which assistant or agent is active",
                new CurrentAgentIntent()));
        return Collections.unmodifiableList(aliases);
    }

    private static Intent alias(String id, String description, Intent target) {
        return new DelegatingIntent(id, description, target);
    }

    private static Intent combined(String id, String description, Intent... targets) {
        return new CombinedIntent(id, description, targets);
    }

    private static Intent select(String id, String description, String slot,
                                 String fallback, Map<String, Intent> targets) {
        return new SelectingIntent(id, description, slot, fallback, targets);
    }

    private static class DelegatingIntent implements Intent {
        private final String id;
        private final String description;
        private final Intent target;

        DelegatingIntent(String id, String description, Intent target) {
            this.id = id;
            this.description = description;
            this.target = target;
        }

        public String id() {
            return id;
        }

        public String description() {
            return description;
        }

        public AssistantReply execute(Map<String, String> slots, FijiBridge fiji) {
            return target.execute(slots, fiji);
        }
    }

    private static final class SelectingIntent extends DelegatingIntent {
        private final String slot;
        private final String fallback;
        private final Map<String, Intent> targets;

        SelectingIntent(String id, String description, String slot, String fallback,
                        Map<String, Intent> targets) {
            super(id, description, targets.get(fallback));
            this.slot = slot;
            this.fallback = fallback;
            this.targets = Collections.unmodifiableMap(new LinkedHashMap<String, Intent>(targets));
        }

        @Override
        public AssistantReply execute(Map<String, String> slots, FijiBridge fiji) {
            String selected = slots == null ? null : slots.get(slot);
            Intent target = targets.get(selected == null ? fallback : selected);
            if (target == null) {
                target = targets.get(fallback);
            }
            return target.execute(slots, fiji);
        }
    }

    private static final class CombinedIntent implements Intent {
        private final String id;
        private final String description;
        private final Intent[] targets;

        CombinedIntent(String id, String description, Intent... targets) {
            this.id = id;
            this.description = description;
            this.targets = targets.clone();
        }

        public String id() {
            return id;
        }

        public String description() {
            return description;
        }

        public AssistantReply execute(Map<String, String> slots, FijiBridge fiji) {
            StringBuilder text = new StringBuilder();
            StringBuilder macros = new StringBuilder();
            for (Intent target : targets) {
                AssistantReply reply = target.execute(slots, fiji);
                if (reply.text().length() > 0) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(reply.text());
                }
                if (reply.macroEcho() != null && reply.macroEcho().length() > 0) {
                    if (macros.length() > 0) {
                        macros.append('\n');
                    }
                    macros.append(reply.macroEcho());
                }
            }
            return macros.length() == 0
                    ? AssistantReply.text(text.toString())
                    : AssistantReply.withMacro(text.toString(), macros.toString());
        }
    }
}
