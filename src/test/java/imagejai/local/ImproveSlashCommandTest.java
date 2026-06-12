package imagejai.local;

import org.junit.Test;
import imagejai.engine.FrictionLogJournal;
import imagejai.engine.FrictionLog;
import imagejai.engine.IntentRouter;
import imagejai.local.slash.ImproveSlashCommand;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ImproveSlashCommandTest {

    @Test
    public void improveAliasesFirstBucketSkipsSecondAndAppliesDiff() throws Exception {
        Path root = Files.createTempDirectory("imagejai-friction");
        writeMisses(root);
        FrictionLogJournal journal = new FrictionLogJournal(root);
        FrictionLog log = new FrictionLog();
        log.setJournal(journal);

        IntentLibrary library = IntentLibrary.load();
        IntentMatcher matcher = new IntentMatcher(library);
        List<RankedPhrase> closest = matcher.topK("how many frames", 1);
        assertFalse(closest.isEmpty());
        String closestIntent = closest.get(0).intentId();

        Path yaml = Files.createTempFile("imagejai-intents", ".yaml");
        write(yaml,
                "- id: " + closestIntent + "\n"
                        + "  description: Closest intent\n"
                        + "  seeds: [\"existing seed\"]\n"
                        + "\n"
                        + "- id: diagnostics.plugins\n"
                        + "  description: List plugins\n"
                        + "  seeds: [\"list commands\"]\n");

        SlashCommandRegistry registry = new SlashCommandRegistry();
        registry.register(new ImproveSlashCommand(yaml));
        LocalAssistant assistant = new LocalAssistant(library, matcher, null,
                log, new IntentRouter(), null, registry);

        AssistantReply start = assistant.handle("/improve");
        assertTrue(start.text().contains("Top 2 misses"));
        assertTrue(start.text().contains("\"how many frames\" (5x"));

        AssistantReply alias = assistant.handle("a");
        assertTrue(alias.text().contains("Aliased \"how many frames\" to " + closestIntent));
        assertTrue(alias.text().contains("\"potato salad\" (2x"));

        AssistantReply skip = assistant.handle("s");
        assertTrue(skip.text().contains("Diff against tools/intents.yaml"));
        assertTrue(skip.text().contains("+  seeds: [\"existing seed\", \"how many frames\"]"));

        AssistantReply apply = assistant.handle("y");
        assertTrue(apply.text().contains("Updated tools/intents.yaml"));
        assertTrue(apply.text().contains("python tools/phrasebook_build.py"));
        assertTrue(apply.text().contains("mvn package"));

        String updated = read(yaml);
        assertTrue(updated.contains("  seeds: [\"existing seed\", \"how many frames\"]"));
    }

    @Test
    public void emptyJournalReturnsNoMissesMessage() throws Exception {
        Path root = Files.createTempDirectory("imagejai-empty-friction");
        FrictionLogJournal journal = new FrictionLogJournal(root);
        FrictionLog log = new FrictionLog();
        log.setJournal(journal);
        IntentLibrary library = IntentLibrary.load();

        Path yaml = Files.createTempFile("imagejai-intents", ".yaml");
        write(yaml, "- id: image.stack_counts\n  seeds: [\"x\"]\n");
        SlashCommandRegistry registry = new SlashCommandRegistry();
        registry.register(new ImproveSlashCommand(yaml));
        LocalAssistant assistant = new LocalAssistant(library, new IntentMatcher(library),
                null, log, new IntentRouter(), null, registry);

        AssistantReply reply = assistant.handle("/improve");

        assertTrue(reply.text().contains("No misses to improve from."));
    }

    @Test
    public void missingYamlRefusesBeforeStartingSession() throws Exception {
        Path root = Files.createTempDirectory("imagejai-friction");
        write(root.resolve(FrictionLogJournal.FILE_NAME),
                jsonLine(1, "how many frames"));
        FrictionLogJournal journal = new FrictionLogJournal(root);
        FrictionLog log = new FrictionLog();
        log.setJournal(journal);
        IntentLibrary library = IntentLibrary.load();
        Path missing = root.resolve("missing").resolve("tools").resolve("intents.yaml");

        SlashCommandRegistry registry = new SlashCommandRegistry();
        registry.register(new ImproveSlashCommand(missing));
        LocalAssistant assistant = new LocalAssistant(library, new IntentMatcher(library),
                null, log, new IntentRouter(), null, registry);

        AssistantReply reply = assistant.handle("/improve");

        assertTrue(reply.text().contains("Cannot find tools/intents.yaml"));
        assertFalse(assistant.improveSessionForTest().isPresent());
    }

    private static void writeMisses(Path root) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            sb.append(jsonLine(100 + i, i == 0 ? "How many frames?" : "how many frames"));
        }
        for (int i = 0; i < 2; i++) {
            sb.append(jsonLine(200 + i, "potato salad"));
        }
        write(root.resolve(FrictionLogJournal.FILE_NAME), sb.toString());
    }

    private static String jsonLine(long ts, String argsSummary) {
        return "{\"ts\":" + ts
                + ",\"agent_id\":\"agent\",\"command\":\"local_assistant\""
                + ",\"args_summary\":\"" + argsSummary.replace("\"", "\\\"") + "\""
                + ",\"error\":\"miss\",\"normalised_error\":\"miss\"}\n";
    }

    private static void write(Path path, String text) throws Exception {
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
