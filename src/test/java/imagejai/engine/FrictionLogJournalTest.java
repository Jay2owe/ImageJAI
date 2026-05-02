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
