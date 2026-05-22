package imagejai.ui;

import imagejai.config.PrivacyPosture;
import imagejai.engine.security.AuditLog;
import imagejai.engine.security.AuditRow;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ReceiptsPaneTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void tablePopulatesFromAuditSubscriptionAndDetailRenders() throws Exception {
        Path csv = tmp.newFolder("audit").toPath().resolve(AuditLog.FILE_NAME);
        AuditLog log = new AuditLog(csv);
        final ReceiptsPane[] pane = new ReceiptsPane[1];
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
                pane[0] = new ReceiptsPane(log);
            }
        });

        log.append(row("{\"ok\":true,\"path\":\"image-1234.lif\"}"));
        log.flushForTest();
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
            }
        });

        assertEquals(1, pane[0].rowCountForTest());
        JComponent detail = pane[0].createReceiptDetailPanelForTest(0);
        assertNotNull(detail);
        assertTrue(row(largePayload()).redactedPayloadJson()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                <= AuditRow.MAX_REDACTED_PAYLOAD_BYTES);

        pane[0].dispose();
        log.shutdownAndAwait(100);
    }

    private static AuditRow row(String payload) {
        return new AuditRow(Instant.parse("2026-05-22T12:00:00Z"),
                "s1",
                "get_state",
                PrivacyPosture.PSEUDONYMISED,
                "anthropic.claude-code",
                "",
                42,
                21,
                "sha256:test",
                true,
                Collections.singletonList("path"),
                "",
                payload);
    }

    private static String largePayload() {
        StringBuilder out = new StringBuilder();
        out.append("{\"ok\":true,\"payload\":\"");
        for (int i = 0; i < 12000; i++) {
            out.append('x');
        }
        out.append("\"}");
        return out.toString();
    }
}
