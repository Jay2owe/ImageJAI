package imagejai.engine.security;

import imagejai.config.PrivacyPosture;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AuditLogTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void firstAppendWritesHeaderThenRow() throws Exception {
        Path csv = tmp.newFolder("audit").toPath().resolve(AuditLog.FILE_NAME);
        AuditLog log = new AuditLog(csv);

        log.append(row("s1", "ping", "", "note"));
        log.flushForTest();

        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        assertEquals(AuditLog.HEADER, lines.get(0));
        assertEquals(2, lines.size());
        assertEquals("ping", AuditRow.fromCsvLine(lines.get(1)).command());
        log.shutdownAndAwait(100);
    }

    @Test
    public void headerMatchesStage04ColumnOrderExactly() {
        assertEquals("timestamp_utc,session_id,command,posture,model_endpoint,"
                        + "capture_source,bytes_out,bytes_in,image_hash,"
                        + "redaction_applied,fields_redacted,notes",
                AuditLog.HEADER);
        assertEquals(12, AuditRow.parseCsvLine(AuditLog.HEADER).size());
    }

    @Test
    public void subsequentAppendsDoNotRewriteHeader() throws Exception {
        Path csv = tmp.newFolder("audit").toPath().resolve(AuditLog.FILE_NAME);
        AuditLog log = new AuditLog(csv);

        log.append(row("s1", "ping", "", ""));
        log.append(row("s1", "get_state", "", ""));
        log.flushForTest();

        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        int headerCount = 0;
        for (String line : lines) {
            if (AuditLog.HEADER.equals(line)) {
                headerCount++;
            }
        }
        assertEquals(1, headerCount);
        assertEquals(3, lines.size());
        log.shutdownAndAwait(100);
    }

    @Test
    public void concurrentAppendsAreBatchedAndProduceWellFormedRows() throws Exception {
        Path csv = tmp.newFolder("audit").toPath().resolve(AuditLog.FILE_NAME);
        final AuditLog log = new AuditLog(csv);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        final CountDownLatch ready = new CountDownLatch(2);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch writerStarted = new CountDownLatch(1);
        final CountDownLatch releaseWriter = new CountDownLatch(1);
        log.setBeforeWriterDrainHookForTest(new Runnable() {
            @Override
            public void run() {
                writerStarted.countDown();
                try {
                    releaseWriter.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        try {
            for (int t = 0; t < 2; t++) {
                final int threadId = t;
                pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        ready.countDown();
                        try {
                            start.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        for (int i = 0; i < 100; i++) {
                            log.append(row("s" + threadId, "cmd_" + i, "", ""));
                        }
                    }
                });
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertTrue(writerStarted.await(5, TimeUnit.SECONDS));
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            releaseWriter.countDown();
            log.flushForTest();

            List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
            assertEquals(201, lines.size());
            for (int i = 1; i < lines.size(); i++) {
                assertEquals(12, AuditRow.parseCsvLine(lines.get(i)).size());
            }
            assertEquals("queued rows should share one lock/force cycle",
                    1L, log.completedWriterBatchCountForTest());
        } finally {
            start.countDown();
            releaseWriter.countDown();
            pool.shutdownNow();
            log.shutdownAndAwait(1000);
        }
    }

    @Test
    public void queuedRowsStayBoundToImagePathCapturedAtAppend() throws Exception {
        Path first = tmp.newFolder("first-image").toPath()
                .resolve("AI_Exports").resolve(AuditLog.FILE_NAME);
        Path second = tmp.newFolder("second-image").toPath()
                .resolve("AI_Exports").resolve(AuditLog.FILE_NAME);
        final AtomicReference<Path> activePath = new AtomicReference<Path>(first);
        final AuditLog log = new AuditLog(new AuditLog.PathResolver() {
            @Override public Path csvPath() { return activePath.get(); }
        });
        final CountDownLatch writerBlocked = new CountDownLatch(1);
        final CountDownLatch releaseWriter = new CountDownLatch(1);
        log.setBeforeWriterDrainHookForTest(new Runnable() {
            @Override public void run() {
                writerBlocked.countDown();
                try {
                    releaseWriter.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        try {
            log.append(row("s", "first-image-command", "", ""));
            assertTrue(writerBlocked.await(5, TimeUnit.SECONDS));
            activePath.set(second);
            log.append(row("s", "second-image-command", "", ""));
            releaseWriter.countDown();
            log.flushForTest();

            List<String> firstLines = Files.readAllLines(first, StandardCharsets.UTF_8);
            List<String> secondLines = Files.readAllLines(second, StandardCharsets.UTF_8);
            assertEquals(2, firstLines.size());
            assertEquals(2, secondLines.size());
            assertEquals("first-image-command",
                    AuditRow.fromCsvLine(firstLines.get(1)).command());
            assertEquals("second-image-command",
                    AuditRow.fromCsvLine(secondLines.get(1)).command());
        } finally {
            releaseWriter.countDown();
            log.shutdownAndAwait(1000L);
        }
    }

    @Test
    public void appendAtDrainExitIsSubmittedBeforeShutdown() throws Exception {
        Path csv = tmp.newFolder("drain-exit").toPath().resolve(AuditLog.FILE_NAME);
        final AuditLog log = new AuditLog(csv);
        final CountDownLatch drainAtIdleHandoff = new CountDownLatch(1);
        final CountDownLatch releaseIdleHandoff = new CountDownLatch(1);
        final CountDownLatch appendAttempted = new CountDownLatch(1);
        log.setBeforeWriterIdleHookForTest(new Runnable() {
            @Override
            public void run() {
                drainAtIdleHandoff.countDown();
                try {
                    releaseIdleHandoff.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        ExecutorService appender = Executors.newSingleThreadExecutor();
        try {
            log.append(row("s", "first", "", ""));
            assertTrue(drainAtIdleHandoff.await(5, TimeUnit.SECONDS));

            Future<?> secondAppend = appender.submit(new Runnable() {
                @Override
                public void run() {
                    appendAttempted.countDown();
                    log.append(row("s", "second", "", ""));
                }
            });
            assertTrue(appendAttempted.await(5, TimeUnit.SECONDS));
            try {
                secondAppend.get(100, TimeUnit.MILLISECONDS);
                throw new AssertionError("append bypassed the locked idle handoff");
            } catch (TimeoutException expected) {
                // The idle transition and append admission share one lock.
            }

            releaseIdleHandoff.countDown();
            secondAppend.get(5, TimeUnit.SECONDS);
            log.shutdownAndAwait(5000L);

            List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
            assertEquals(3, lines.size());
            assertEquals("first", AuditRow.fromCsvLine(lines.get(1)).command());
            assertEquals("second", AuditRow.fromCsvLine(lines.get(2)).command());
            assertEquals(0L, log.droppedWriterTaskCount());

            log.append(row("s", "after-shutdown", "", ""));
            assertEquals(1L, log.droppedWriterTaskCount());
        } finally {
            releaseIdleHandoff.countDown();
            appender.shutdownNow();
            log.shutdownAndAwait(1000L);
        }
    }

    @Test
    public void appendDoesNotWaitForSlowReceiptListener() throws Exception {
        Path csv = tmp.newFolder("audit").toPath().resolve(AuditLog.FILE_NAME);
        final AuditLog log = new AuditLog(csv);
        final CountDownLatch releaseListener = new CountDownLatch(1);
        AutoCloseable subscription = log.subscribeRecent(new AuditLog.Listener() {
            @Override
            public void auditRowsUpdated(List<AuditRow> recentRows) {
                if (recentRows.isEmpty()) {
                    return;
                }
                try {
                    releaseListener.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        ExecutorService caller = Executors.newSingleThreadExecutor();

        Future<?> append = caller.submit(new Runnable() {
            @Override
            public void run() {
                log.append(row("s1", "ping", "", ""));
            }
        });

        try {
            append.get(500, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            releaseListener.countDown();
            throw new AssertionError("AuditLog.append blocked on a receipt listener", e);
        } finally {
            releaseListener.countDown();
            caller.shutdownNow();
            subscription.close();
            log.shutdownAndAwait(1000);
        }
    }

    @Test
    public void csvEscapesNotesWithCommaAndQuotes() {
        AuditRow original = row("s1", "capture_image", "active_image_content",
                "reason='contains, comma and \"quote\"'");

        AuditRow parsed = AuditRow.fromCsvLine(original.toCsvLine());

        assertEquals(original.notes(), parsed.notes());
        assertEquals("active_image_content", parsed.captureSource());
        assertFalse(parsed.fieldsRedacted().isEmpty());
    }

    @Test
    public void captureSourceIsBlankForNonCaptureRows() {
        AuditRow nonCapture = row("s1", "ping", "", "");
        AuditRow capture = row("s1", "capture_image", "active_image_content", "");

        assertEquals("", AuditRow.fromCsvLine(nonCapture.toCsvLine()).captureSource());
        assertEquals("active_image_content",
                AuditRow.fromCsvLine(capture.toCsvLine()).captureSource());
    }

    @Test
    public void summaryForCountsRowsAndGovernanceEvents() throws Exception {
        Path folder = tmp.newFolder("image-folder").toPath();
        Path csv = folder.resolve("AI_Exports").resolve(AuditLog.FILE_NAME);
        AuditLog log = new AuditLog(csv);
        log.append(row("s1", "ping", "", ""));
        log.append(new AuditRow(Instant.parse("2026-05-22T12:00:01Z"),
                "s1", "visual.granted", PrivacyPosture.PSEUDONYMISED,
                "", "", 0, 0, "", false,
                Collections.<String>emptyList(), "reason='inspect focus'"));
        log.append(new AuditRow(Instant.parse("2026-05-22T12:00:02Z"),
                "s1", "posture.downshift", PrivacyPosture.PSEUDONYMISED,
                "", "", 0, 0, "", false,
                Collections.<String>emptyList(), "from=Standard to=Pseudonymised"));
        log.flushForTest();

        AuditSummary summary = AuditLog.summaryFor(folder);

        assertEquals(3, summary.totalRows());
        assertEquals(3, summary.rowCount());
        assertEquals(1, summary.pseudonymised());
        assertEquals(Instant.parse("2026-05-22T12:00:00Z"), summary.first());
        assertEquals(Instant.parse("2026-05-22T12:00:02Z"), summary.last());
        assertEquals(1, summary.visualGrantRows());
        assertEquals(1, summary.visualOverrideGrants());
        assertEquals(1, summary.postureEventRows());
        assertEquals(1, summary.downshifts());
        assertEquals(Integer.valueOf(1), summary.commandCounts().get("ping"));
        log.shutdownAndAwait(100);
    }

    @Test
    public void subscribeRecentReceivesRows() throws Exception {
        Path csv = tmp.newFolder("audit").toPath().resolve(AuditLog.FILE_NAME);
        AuditLog log = new AuditLog(csv);
        final int[] sizes = new int[] {0};
        AutoCloseable subscription = log.subscribeRecent(new AuditLog.Listener() {
            @Override
            public void auditRowsUpdated(List<AuditRow> recentRows) {
                sizes[0] = recentRows.size();
            }
        });

        log.append(row("s1", "ping", "", ""));
        log.flushForTest();

        assertEquals(1, sizes[0]);
        subscription.close();
        log.shutdownAndAwait(100);
    }

    @Test
    public void flushDoesNotOvertakeCoalescedListenerUpdate() throws Exception {
        Path csv = tmp.newFolder("notification-fence").toPath()
                .resolve(AuditLog.FILE_NAME);
        final AuditLog log = new AuditLog(csv);
        final CountDownLatch firstNotificationStarted = new CountDownLatch(1);
        final CountDownLatch releaseFirstNotification = new CountDownLatch(1);
        final CountDownLatch finalNotificationSeen = new CountDownLatch(1);
        final AtomicBoolean firstNonEmpty = new AtomicBoolean(true);
        final AtomicInteger lastSize = new AtomicInteger(0);
        AutoCloseable subscription = log.subscribeRecent(new AuditLog.Listener() {
            @Override
            public void auditRowsUpdated(List<AuditRow> recentRows) {
                if (recentRows.isEmpty()) {
                    return;
                }
                lastSize.set(recentRows.size());
                if (firstNonEmpty.compareAndSet(true, false)) {
                    firstNotificationStarted.countDown();
                    try {
                        releaseFirstNotification.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (recentRows.size() >= 2) {
                    finalNotificationSeen.countDown();
                }
            }
        });
        ExecutorService flusher = Executors.newSingleThreadExecutor();
        try {
            log.append(row("s", "first", "", ""));
            assertTrue(firstNotificationStarted.await(5, TimeUnit.SECONDS));
            log.append(row("s", "second", "", ""));

            Future<?> flush = flusher.submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        log.flushForTest();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            });
            try {
                flush.get(100, TimeUnit.MILLISECONDS);
                throw new AssertionError("flush overtook the blocked notification drain");
            } catch (TimeoutException expected) {
                // The fence must remain behind the active, coalescing drain.
            }

            releaseFirstNotification.countDown();
            flush.get(5, TimeUnit.SECONDS);
            assertTrue(finalNotificationSeen.await(1, TimeUnit.SECONDS));
            assertEquals(2, lastSize.get());
        } finally {
            releaseFirstNotification.countDown();
            flusher.shutdownNow();
            subscription.close();
            log.shutdownAndAwait(1000L);
        }
    }

    @Test
    public void recentReturnsRequestedTailAndZeroRowsForZeroLimit() throws Exception {
        Path csv = tmp.newFolder("audit").toPath().resolve(AuditLog.FILE_NAME);
        AuditLog log = new AuditLog(csv);

        log.append(row("s1", "first", "", ""));
        log.append(row("s1", "second", "", ""));
        log.append(row("s1", "third", "", ""));

        List<AuditRow> recent = log.recent(2);
        assertEquals(2, recent.size());
        assertEquals("second", recent.get(0).command());
        assertEquals("third", recent.get(1).command());
        assertEquals(0, log.recent(0).size());
        log.shutdownAndAwait(100);
    }

    @Test
    public void formulaLikeCellsAreProtectedButRoundTripLosslessly() {
        AuditRow dangerous = row("=SUM(A1:A2)", "+cmd", "@capture", "-1+2");
        String csv = dangerous.toCsvLine();

        assertTrue(csv.contains("'=SUM(A1:A2)"));
        assertTrue(csv.contains("'+cmd"));
        assertTrue(csv.contains("'@capture"));
        assertTrue(csv.contains("'-1+2"));
        assertEquals(dangerous, AuditRow.fromCsvLine(csv));
    }

    @Test
    public void summaryReportsMalformedRowsAndKeepsDeterministicOrdering() throws Exception {
        Path csv = tmp.newFolder("malformed").toPath().resolve(AuditLog.FILE_NAME);
        AuditRow z = row("s", "z-command", "", "line one\nline two");
        AuditRow a = row("s", "a-command", "", "");
        String invalid = "not-a-time,s,ping,pseudonymised,model,source,1,2,hash,false,,note";
        Files.writeString(csv, AuditLog.HEADER + "\n" + z.toCsvLine() + "\n"
                + a.toCsvLine() + "\n" + invalid + "\n\"unterminated",
                StandardCharsets.UTF_8);

        AuditSummary summary = AuditLog.summaryFor(csv);

        assertEquals(2, summary.totalRows());
        assertEquals(2, summary.malformedRows());
        assertEquals(2, summary.malformedDiagnostics().size());
        assertTrue(summary.malformedDiagnostics().get(0).contains("line"));
        assertEquals(Arrays.asList("a-command", "z-command"),
                new ArrayList<String>(summary.commandCounts().keySet()));
    }

    @Test
    public void recentRowsAndListenersRemainBoundedUnderFlood() throws Exception {
        AuditLog log = new AuditLog(new AuditLog.PathResolver() {
            @Override public Path csvPath() { return null; }
        });
        List<AutoCloseable> subscriptions = new ArrayList<AutoCloseable>();
        try {
            for (int i = 0; i < AuditLog.MAX_LISTENERS + 10; i++) {
                subscriptions.add(log.subscribeRecent(new AuditLog.Listener() {
                    @Override public void auditRowsUpdated(List<AuditRow> rows) { }
                }));
            }
            for (int i = 0; i < 700; i++) {
                log.append(row("s", "cmd-" + i, "", ""));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5L);
            while ((log.writerQueueSize() > 0 || log.notifierQueueSize() > 0)
                    && System.nanoTime() < deadline) {
                Thread.sleep(10L);
            }
            log.flushForTest();

            assertEquals(AuditLog.MAX_LISTENERS, log.listenerCount());
            assertEquals(10L, log.rejectedListenerCount());
            assertEquals(500, log.recent(1000).size());
            assertEquals(200L, log.droppedRecentRowCount());
            assertTrue(log.writerQueueSize() <= AuditLog.WRITER_QUEUE_CAPACITY);
            assertTrue(log.notifierQueueSize() <= AuditLog.NOTIFIER_QUEUE_CAPACITY);
        } finally {
            for (AutoCloseable subscription : subscriptions) subscription.close();
            log.shutdownAndAwait(1000L);
        }
    }

    @Test
    public void summaryRejectsOversizeFileInsteadOfReadingItAll() throws Exception {
        Path csv = tmp.newFolder("oversize").toPath().resolve(AuditLog.FILE_NAME);
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(
                csv.toFile(), "rw")) {
            file.setLength(AuditLog.MAX_SUMMARY_BYTES + 1L);
        }

        try {
            AuditLog.summaryFor(csv);
            throw new AssertionError("Expected oversize audit summary to fail");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("exceeds summary limit"));
        }
    }

    @Test
    public void summaryRejectsFileThatGrowsAfterSizePrecheck() throws Exception {
        Path csv = tmp.newFolder("growing").toPath().resolve(AuditLog.FILE_NAME);
        Files.writeString(csv, AuditLog.HEADER + "\n", StandardCharsets.UTF_8);
        AuditLog.setBeforeSummaryReadHookForTest(new Runnable() {
            @Override public void run() {
                try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(
                        csv.toFile(), "rw")) {
                    file.setLength(AuditLog.MAX_SUMMARY_BYTES + 1L);
                } catch (java.io.IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });
        try {
            AuditLog.summaryFor(csv);
            throw new AssertionError("Expected growing audit summary to fail");
        } catch (java.io.IOException expected) {
            assertTrue(expected.getMessage().contains("exceeds summary limit"));
        } finally {
            AuditLog.setBeforeSummaryReadHookForTest(null);
        }
    }

    @Test
    public void massiveSingleRowIsBoundedBeforeRecentRetention() {
        AuditLog log = new AuditLog(new AuditLog.PathResolver() {
            @Override public Path csvPath() { return null; }
        });
        List<String> fields = new ArrayList<String>();
        for (int i = 0; i < AuditLog.MAX_ROW_FIELDS + 500; i++) {
            fields.add(repeat('f', AuditLog.MAX_ROW_FIELD_CHARS + 100));
        }
        AuditRow huge = new AuditRow(Instant.now(), repeat('s', 1000),
                repeat('c', 1000), PrivacyPosture.PSEUDONYMISED,
                repeat('m', 1000), repeat('x', 1000), 1, 2,
                repeat('h', 1000), true, fields,
                repeat('n', AuditLog.MAX_ROW_TEXT_CHARS * 4));
        try {
            log.append(huge);
            AuditRow retained = log.recent(1).get(0);
            assertTrue(retained.notes().length() <= AuditLog.MAX_ROW_TEXT_CHARS);
            assertEquals(AuditLog.MAX_ROW_FIELDS, retained.fieldsRedacted().size());
            assertEquals("__truncated_fields__", retained.fieldsRedacted().get(
                    retained.fieldsRedacted().size() - 1));
            assertTrue(retained.sessionId().length() <= AuditLog.MAX_ROW_FIELD_CHARS);
        } finally {
            log.shutdownAndAwait(1000L);
        }
    }

    private static AuditRow row(String session, String command,
                                String captureSource, String notes) {
        return new AuditRow(Instant.parse("2026-05-22T12:00:00Z"),
                session,
                command,
                PrivacyPosture.PSEUDONYMISED,
                "anthropic.claude-code",
                captureSource,
                42,
                21,
                "sha256:test",
                true,
                Collections.singletonList("path"),
                notes);
    }

    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
