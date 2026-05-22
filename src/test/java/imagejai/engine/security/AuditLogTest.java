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
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
    public void concurrentAppendsProduceWellFormedRows() throws Exception {
        Path csv = tmp.newFolder("audit").toPath().resolve(AuditLog.FILE_NAME);
        final AuditLog log = new AuditLog(csv);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        final CountDownLatch ready = new CountDownLatch(2);
        final CountDownLatch start = new CountDownLatch(1);

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
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        log.flushForTest();

        List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        assertEquals(201, lines.size());
        for (int i = 1; i < lines.size(); i++) {
            assertEquals(12, AuditRow.parseCsvLine(lines.get(i)).size());
        }
        log.shutdownAndAwait(100);
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
        assertEquals(1, summary.visualGrantRows());
        assertEquals(1, summary.postureEventRows());
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
}
