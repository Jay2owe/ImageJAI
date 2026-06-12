package imagejai.local.slash;

import imagejai.engine.FrictionLogJournal;
import imagejai.local.AssistantReply;
import imagejai.local.ImproveAnalysis;
import imagejai.local.ImproveAnalysis.MissBucket;
import imagejai.local.ImproveSession;
import imagejai.local.IntentsYamlWriter;
import imagejai.local.SlashCommand;
import imagejai.local.SlashCommandContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class ImproveSlashCommand implements SlashCommand {
    private static final int MAX_BUCKETS = 20;

    private final Path intentsYamlPath;
    private final IntentsYamlWriter writer;

    public ImproveSlashCommand() {
        this(null, new IntentsYamlWriter());
    }

    public ImproveSlashCommand(Path intentsYamlPath) {
        this(intentsYamlPath, new IntentsYamlWriter());
    }

    ImproveSlashCommand(Path intentsYamlPath, IntentsYamlWriter writer) {
        this.intentsYamlPath = intentsYamlPath;
        this.writer = writer == null ? new IntentsYamlWriter() : writer;
    }

    public String name() {
        return "improve";
    }

    public String intentId() {
        return "slash.improve";
    }

    public String description() {
        return "Review missed Local Assistant phrases and update tools/intents.yaml";
    }

    public AssistantReply execute(SlashCommandContext context) {
        if (context == null || context.matcher() == null || !context.canStartImproveSession()) {
            return AssistantReply.text("Cannot run /improve in this context.");
        }
        FrictionLogJournal journal = context.frictionJournal();
        if (journal == null) {
            return AssistantReply.text("No friction journal is configured.");
        }
        List<MissBucket> buckets = ImproveAnalysis.fromJournal(journal, context.matcher());
        if (buckets.isEmpty()) {
            return AssistantReply.text("No misses to improve from.");
        }

        Path yaml = intentsYamlPath == null ? IntentsYamlWriter.defaultIntentsPath() : intentsYamlPath;
        yaml = yaml.toAbsolutePath().normalize();
        if (!Files.exists(yaml)) {
            return AssistantReply.text("Cannot find tools/intents.yaml at " + yaml
                    + ". Run /improve from a development Fiji install with the ImageJAI source checked out there.");
        }
        if (!Files.isRegularFile(yaml)) {
            return AssistantReply.text("Refusing to write tools/intents.yaml because " + yaml
                    + " is not a regular file.");
        }

        List<MissBucket> top = buckets.subList(0, Math.min(MAX_BUCKETS, buckets.size()));
        ImproveSession session = ImproveSession.start(top, yaml, writer);
        context.startImproveSession(session);
        return AssistantReply.text(session.firstPrompt());
    }
}
