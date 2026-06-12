package imagejai.engine;

import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FrictionLogJournalTest {

    private final Set<Path> cleanupRoots = new HashSet<Path>();

    @After
    public void cleanup() throws IOException {
        for (Path root : cleanupRoots) {
            deleteRecursively(root);
        }
    }

    @Test
    public void appendReturnsWhileWriterIsBlocked() throws Exception {
        Path root = newRoot();
        CountDownLatch writerEntered = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        BlockingJournal journal = new BlockingJournal(root, writerEntered, releaseWriter);
        try {
            journal.append(entry(1, "first"));
            assertTrue(writerEntered.await(5, TimeUnit.SECONDS));

            long start = System.nanoTime();
            journal.append(entry(2, "second"));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue("append should only enqueue work, elapsed=" + elapsedMs + " ms", elapsedMs < 20);
            releaseWriter.countDown();
            journal.awaitIdle(5, TimeUnit.SECONDS);
        } finally {
            releaseWriter.countDown();
            journal.close();
        }
    }

    @Test
    public void rotatesWhenLiveFileReachesTenMegabytes() throws Exception {
        Path root = newRoot();
        FrictionLogJournal journal = new FrictionLogJournal(root);
        try {
            Files.createDirectories(root);
            writeSizedFile(root.resolve(FrictionLogJournal.FILE_NAME), FrictionLogJournal.ROTATE_BYTES);

            journal.append(entry(1, "after-threshold"));
            journal.awaitIdle(5, TimeUnit.SECONDS);

            assertTrue(Files.exists(root.resolve("friction.jsonl.1")));
            assertTrue(Files.size(root.resolve("friction.jsonl")) < 1024 * 1024);
        } finally {
            journal.close();
        }
    }

    @Test
    public void capsRotationsAtFiveGenerations() throws Exception {
        Path root = newRoot();
        FrictionLogJournal journal = new FrictionLogJournal(root);
        try {
            Files.createDirectories(root);
            for (int i = 0; i < 7; i++) {
                writeSizedFile(root.resolve(FrictionLogJournal.FILE_NAME), FrictionLogJournal.ROTATE_BYTES);
                journal.append(entry(i, "rotation-" + i));
                journal.awaitIdle(5, TimeUnit.SECONDS);
            }

            for (int i = 1; i <= FrictionLogJournal.MAX_GENERATIONS; i++) {
                assertTrue("missing generation ." + i, Files.exists(root.resolve("friction.jsonl." + i)));
            }
            assertFalse(Files.exists(root.resolve("friction.jsonl.6")));
            assertFalse(Files.exists(root.resolve("friction.jsonl.7")));
        } finally {
            journal.close();
        }
    }

    @Test
    public void readsBackEntriesWrittenByAppend() throws Exception {
        Path root = newRoot();
        FrictionLogJournal journal = new FrictionLogJournal(root);
        try {
            for (int i = 0; i < 100; i++) {
                journal.append(entry(i, "command-" + i));
            }
            journal.awaitIdle(5, TimeUnit.SECONDS);

            List<FrictionLog.FailureEntry> entries;
            try (Stream<FrictionLog.FailureEntry> stream = journal.streamEntries()) {
                entries = stream.collect(Collectors.toList());
            }

            assertEquals(100, entries.size());
            assertEquals("command-0", entries.get(0).command);
            assertEquals("command-99", entries.get(99).command);
        } finally {
            journal.close();
        }
    }

    @Test
    public void recreatedJournalCanReadExistingRows() throws Exception {
        Path root = newRoot();
        FrictionLogJournal first = new FrictionLogJournal(root);
        for (int i = 0; i < 50; i++) {
            first.append(entry(i, "durable-" + i));
        }
        first.awaitIdle(5, TimeUnit.SECONDS);

        FrictionLogJournal second = new FrictionLogJournal(root);
        try {
            try (Stream<FrictionLog.FailureEntry> stream = second.streamEntries()) {
                assertEquals(50, stream.count());
            }
        } finally {
            first.close();
            second.close();
        }
    }

    @Test
    public void unwritableRootDoesNotThrowToCaller() throws Exception {
        Path rootFile = newRoot();
        Files.write(rootFile, new byte[]{1});
        FrictionLogJournal journal = new FrictionLogJournal(rootFile);
        try {
            journal.append(entry(1, "permission-denied"));
            journal.awaitIdle(5, TimeUnit.SECONDS);
        } finally {
            journal.close();
        }
    }

    @Test
    public void clearOnlyResetsRingBufferAndLeavesJournalOnDisk() throws Exception {
        Path root = newRoot();
        FrictionLogJournal journal = new FrictionLogJournal(root);
        FrictionLog log = new FrictionLog();
        log.setJournal(journal);
        try {
            log.record("agent", "local_assistant", "potato salad", "miss");
            journal.awaitIdle(5, TimeUnit.SECONDS);
            assertTrue(Files.exists(journal.file()));

            log.clear();

            assertEquals(0, log.size());
            assertTrue(Files.exists(journal.file()));
            try (Stream<FrictionLog.FailureEntry> stream = journal.streamEntries()) {
                assertEquals(1, stream.count());
            }
        } finally {
            journal.close();
        }
    }

    /**
     * safe_mode_v2 stage 08: writing an entry with all four structured
     * columns must round-trip through JSONL with the values preserved
     * (no string coercion, no field collision with the legacy keys).
     */
    @Test
    public void structuredEntryRoundTripsThroughJsonl() throws Exception {
        Path root = newRoot();
        FrictionLogJournal journal = new FrictionLogJournal(root);
        try {
            FrictionLog.FailureEntry written = new FrictionLog.FailureEntry(
                    42L, "claude", "execute_macro", "code=run(\"X\")",
                    "blocked: roi_wipe @ line 3",
                    "blocked", "L1_reject", "roi_wipe", "ROI Manager");
            journal.append(written);
            journal.awaitIdle(5, TimeUnit.SECONDS);

            List<FrictionLog.FailureEntry> entries;
            try (Stream<FrictionLog.FailureEntry> stream = journal.streamEntries()) {
                entries = stream.collect(Collectors.toList());
            }
            assertEquals(1, entries.size());
            FrictionLog.FailureEntry read = entries.get(0);
            assertEquals("blocked", read.outcome);
            assertEquals("L1_reject", read.severity);
            assertEquals("roi_wipe", read.ruleId);
            assertEquals("ROI Manager", read.target);
            assertEquals("claude", read.agentId);
            assertEquals("execute_macro", read.command);
        } finally {
            journal.close();
        }
    }

    /**
     * safe_mode_v2 stage 08: reading a JSONL line written by stage-07-or-
     * earlier code (no {@code outcome} / {@code severity} / {@code rule_id}
     * / {@code target} keys) must succeed, with the four new fields all
     * deserialising to {@code null}. This is the on-disk back-compat
     * guarantee.
     */
    @Test
    public void prestageEightJsonlReadsBackWithNullStructuredFields() throws Exception {
        Path root = newRoot();
        Files.createDirectories(root);
        Path file = root.resolve(FrictionLogJournal.FILE_NAME);
        // Hand-crafted line with the pre-stage-08 schema only.
        String legacyLine = "{\"ts\":7,\"agent_id\":\"old\",\"command\":\"execute_macro\","
                + "\"args_summary\":\"\",\"error\":\"boom\",\"normalised_error\":\"boom\"}\n";
        Files.write(file, legacyLine.getBytes(StandardCharsets.UTF_8));

        FrictionLogJournal journal = new FrictionLogJournal(root);
        try {
            List<FrictionLog.FailureEntry> entries;
            try (Stream<FrictionLog.FailureEntry> stream = journal.streamEntries()) {
                entries = stream.collect(Collectors.toList());
            }
            assertEquals(1, entries.size());
            FrictionLog.FailureEntry e = entries.get(0);
            assertEquals("old", e.agentId);
            assertEquals("execute_macro", e.command);
            assertEquals("boom", e.error);
            assertNull("outcome should be null on legacy rows", e.outcome);
            assertNull("severity should be null on legacy rows", e.severity);
            assertNull("rule_id should be null on legacy rows", e.ruleId);
            assertNull("target should be null on legacy rows", e.target);
        } finally {
            journal.close();
        }
    }

    /**
     * safe_mode_v2 stage 08: a {@link FailureEntry} written via the legacy
     * five-arg constructor (no structured fields) serialises *without*
     * {@code outcome} / {@code severity} / {@code rule_id} / {@code target}
     * keys. Keeps the JSONL compact and lets readers distinguish
     * "wasn't classified" from "classified, value empty".
     */
    @Test
    public void legacyEntryOmitsStructuredKeysFromJsonl() throws Exception {
        Path root = newRoot();
        FrictionLogJournal journal = new FrictionLogJournal(root);
        try {
            journal.append(new FrictionLog.FailureEntry(
                    1L, "claude", "ping", "", "miss"));
            journal.awaitIdle(5, TimeUnit.SECONDS);

            List<String> lines = Files.readAllLines(journal.file(), StandardCharsets.UTF_8);
            assertEquals(1, lines.size());
            String line = lines.get(0);
            assertFalse("outcome key should be absent: " + line, line.contains("\"outcome\""));
            assertFalse("severity key should be absent: " + line, line.contains("\"severity\""));
            assertFalse("rule_id key should be absent: " + line, line.contains("\"rule_id\""));
            assertFalse("target key should be absent: " + line, line.contains("\"target\""));
        } finally {
            journal.close();
        }
    }

    @Test
    public void thirtyMissesWriteValidJsonl() throws Exception {
        Path root = newRoot();
        FrictionLogJournal journal = new FrictionLogJournal(root);
        try {
            for (int i = 0; i < 31; i++) {
                journal.append(entry(i, "local_assistant"));
            }
            journal.awaitIdle(5, TimeUnit.SECONDS);

            assertTrue(Files.exists(journal.file()));
            List<String> lines = Files.readAllLines(journal.file(), StandardCharsets.UTF_8);
            assertEquals(31, lines.size());
            for (String line : lines) {
                assertTrue(new JsonParser().parse(line).isJsonObject());
                assertFalse(line.contains("\r"));
            }
        } finally {
            journal.close();
        }
    }

    private static FrictionLog.FailureEntry entry(long ts, String command) {
        return new FrictionLog.FailureEntry(ts, "agent", command, "args", "miss");
    }

    private Path newRoot() {
        Path root = FrictionLogJournal.defaultRoot().resolve("junit-friction-log-" + UUID.randomUUID());
        cleanupRoots.add(root);
        return root;
    }

    private static void writeSizedFile(Path file, long bytes) throws IOException {
        byte[] block = new byte[1024 * 1024];
        long remaining = bytes;
        try (java.io.OutputStream out = Files.newOutputStream(file)) {
            while (remaining > 0) {
                int n = (int) Math.min(block.length, remaining);
                out.write(block, 0, n);
                remaining -= n;
            }
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> paths = walk.sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            for (Path path : paths) {
                Files.deleteIfExists(path);
            }
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
    }

    private static class BlockingJournal extends FrictionLogJournal {
        private final CountDownLatch writerEntered;
        private final CountDownLatch releaseWriter;
        private boolean first = true;

        BlockingJournal(Path root, CountDownLatch writerEntered, CountDownLatch releaseWriter) {
            super(root);
            this.writerEntered = writerEntered;
            this.releaseWriter = releaseWriter;
        }

        @Override
        protected void writeLine(FrictionLog.FailureEntry e) {
            if (first) {
                first = false;
                writerEntered.countDown();
                try {
                    releaseWriter.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            super.writeLine(e);
        }
    }
}
