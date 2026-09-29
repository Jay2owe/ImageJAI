package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class SessionCodeJournalTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void persistenceUsesInitiatingDatasetAfterAsyncSwitch() throws Exception {
        Path fallback = tmp.newFolder("fallback").toPath();
        Path datasetA = tmp.newFolder("dataset-a").toPath();
        Path datasetB = tmp.newFolder("dataset-b").toPath();
        SessionCodeJournal journal = new SessionCodeJournal(fallback, true);
        SessionCodeJournal.DatasetBinding binding = new SessionCodeJournal.DatasetBinding(
                "img-a", "hash-a", "A", "A.tif", datasetA);
        String code = "run(\"Gaussian Blur...\", \"sigma=2\"); // initiating A";

        journal.record(binding, "ijm", code, "test", 1L,
                1000L, 25L, true, null);
        // A simulated active-dataset switch must not redirect the queued write.
        Files.createDirectories(datasetB);
        journal.awaitWritesForTest();

        SessionCodeJournal.Entry entry = journal.snapshot().get(0);
        assertEquals("img-a", entry.datasetIdentity);
        assertEquals("hash-a", entry.datasetHash);
        assertTrue(Files.isRegularFile(datasetA.resolve(entry.fileName)));
        assertTrue(Files.isRegularFile(datasetA.resolve("INDEX.json")));
        assertFalse(Files.exists(fallback.resolve(entry.fileName)));
        assertFalse(Files.exists(datasetB.resolve(entry.fileName)));
        journal.shutdownForTest();
    }

    @Test
    public void entriesAreImmutableAndNamesDoNotCollideWithinOneMillisecond() throws Exception {
        Path dir = tmp.newFolder("journal").toPath();
        SessionCodeJournal journal = new SessionCodeJournal(dir, true);
        SessionCodeJournal.DatasetBinding binding = new SessionCodeJournal.DatasetBinding(
                "img", "hash", "title", null, dir);
        journal.record(binding, "ijm", "run(\"A command with enough text\");", "test",
                0L, 1234L, 1L, true, null);
        journal.record(binding, "ijm", "run(\"A different command with enough text\");", "test",
                0L, 1234L, 1L, true, null);
        journal.awaitWritesForTest();

        List<SessionCodeJournal.Entry> snapshot = journal.snapshot();
        assertEquals(2, snapshot.size());
        assertNotEquals(snapshot.get(0).persistentId, snapshot.get(1).persistentId);
        assertNotEquals(snapshot.get(0).fileName, snapshot.get(1).fileName);
        try {
            snapshot.clear();
            fail("journal snapshots must be immutable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
        JsonArray index = JsonParser.parseString(Files.readString(
                dir.resolve("INDEX.json"), StandardCharsets.UTF_8)).getAsJsonArray();
        assertEquals(snapshot.get(0).persistentId,
                index.get(0).getAsJsonObject().get("persistentId").getAsString());
        journal.shutdownForTest();
    }

    @Test
    public void corruptIndexIsQuarantinedAndBlocksReplacement() throws Exception {
        Path dir = tmp.newFolder("corrupt").toPath();
        Path index = dir.resolve("INDEX.json");
        Files.writeString(index, "[broken", StandardCharsets.UTF_8);
        SessionCodeJournal journal = new SessionCodeJournal(dir, true);

        try {
            journal.snapshot();
            fail("corrupt history must return an explicit error");
        } catch (SessionCodeJournal.PersistenceException expected) {
            assertEquals("CORRUPT_HISTORY_BLOCKED", expected.code);
        }
        assertTrue(journal.isWriteBlockedForTest());
        assertNotNull(journal.quarantinedIndexForTest());
        assertFalse(Files.exists(index));
        assertEquals("[broken", Files.readString(journal.quarantinedIndexForTest(),
                StandardCharsets.UTF_8));
        try {
            journal.record(new SessionCodeJournal.DatasetBinding(
                            "img", "hash", "title", null, dir),
                    "ijm", "run(\"Long enough command for journal\");", "test",
                    0L, 1L, 1L, true, null);
            fail("invalid history must block replacement");
        } catch (SessionCodeJournal.PersistenceException expected) {
            assertEquals("CORRUPT_HISTORY_BLOCKED", expected.code);
        }
        assertFalse(Files.exists(index));
        journal.shutdownForTest();
    }

    @Test
    public void oversizedCodeFailsSynchronously() throws Exception {
        Path dir = tmp.newFolder("oversized").toPath();
        SessionCodeJournal journal = new SessionCodeJournal(dir, true);
        char[] chars = new char[SessionCodeJournal.MAX_CODE_BYTES + 1];
        java.util.Arrays.fill(chars, 'x');
        try {
            journal.record(new SessionCodeJournal.DatasetBinding(
                            "img", "hash", "title", null, dir),
                    "ijm", new String(chars), "test", 0L, 1L, 1L, true, null);
            fail("oversized code must fail");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("code exceeds"));
        }
        assertTrue(journal.snapshot().isEmpty());
        journal.shutdownForTest();
    }
}
