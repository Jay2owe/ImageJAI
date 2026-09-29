package imagejai.config;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConsoleSessionStoreTest {
    @Test
    public void listsNewestSessionAndRendersTranscript() throws Exception {
        Path dir = Files.createTempDirectory("console-sessions");
        Files.write(dir.resolve("older.json"), (
                "{\"id\":\"older\",\"title\":\"Older\",\"updated\":1," +
                "\"provider\":\"ollama\",\"model\":\"qwen\",\"messages\":[]}")
                .getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("newer.json"), (
                "{\"id\":\"newer\",\"title\":\"Count cells\",\"updated\":2," +
                "\"provider\":\"codex-subscription\",\"model\":\"default\"," +
                "\"messages\":[{\"role\":\"system\",\"content\":\"hidden\"}," +
                "{\"role\":\"user\",\"content\":\"count cells\"}," +
                "{\"role\":\"assistant\",\"content\":\"214\"}]}")
                .getBytes(StandardCharsets.UTF_8));

        ConsoleSessionStore store = new ConsoleSessionStore(dir);
        List<ConsoleSessionStore.Summary> sessions = store.list();
        assertEquals(2, sessions.size());
        assertEquals("newer", sessions.get(0).id);
        String transcript = store.transcript(sessions.get(0));
        assertTrue(transcript.contains("user: count cells"));
        assertTrue(transcript.contains("assistant: 214"));
        assertFalse(transcript.contains("hidden"));
    }

    @Test
    public void ignoresMalformedAndUnsafeSessionIds() throws Exception {
        Path dir = Files.createTempDirectory("console-sessions-invalid");
        Files.write(dir.resolve("bad.json"), "not json".getBytes(StandardCharsets.UTF_8));
        Files.write(dir.resolve("unsafe.json"),
                "{\"id\":\"../unsafe\",\"title\":\"Unsafe\"}"
                        .getBytes(StandardCharsets.UTF_8));
        assertTrue(new ConsoleSessionStore(dir).list().isEmpty());
    }
}
