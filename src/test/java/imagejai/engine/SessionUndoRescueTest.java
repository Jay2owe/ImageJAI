package imagejai.engine;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Stage 03 (docs/safe_mode_v2/03_auto-snapshot-rescue.md): unit tests for
 * {@link SessionUndo.RescueHandle}. Frames are constructed directly so the
 * tests run headless; the wiring in {@code TCPCommandServer} is covered by
 * the integration-style tests under that handler.
 *
 * <p>Mirrors the structure of {@link SessionUndoTest} — the rescue path is
 * a thin layer over {@link SessionUndo#pushFrame}, so the contracts under
 * test are: commit pushes once, release drops cleanly, and close()/release
 * is idempotent so a try-with-resources block does not double-fire.
 */
public class SessionUndoRescueTest {

    private static UndoFrame frame(String callId, String title) {
        byte[] compressed = new byte[16];
        return new UndoFrame(callId, title,
                4, 4, 1, 1, 1, 8,
                compressed, compressed.length,
                Collections.<UndoFrame.RoiSnapshot>emptyList(),
                "",
                System.currentTimeMillis(), false);
    }

    @Test
    public void wrapRescueFrameReturnsHandleWithoutPushing() {
        SessionUndo s = new SessionUndo();
        SessionUndo.RescueHandle h = s.wrapRescueFrame(frame("c-1", "img.tif"));
        assertNotNull(h);
        assertFalse(h.isCommitted());
        assertFalse(h.isReleased());
        // Wrapping alone does NOT push — the active branch is empty.
        assertEquals(0, s.totalFrames());
    }

    @Test
    public void wrapRescueFrameReturnsNullForNullFrame() {
        SessionUndo s = new SessionUndo();
        assertNull(s.wrapRescueFrame(null));
    }

    @Test
    public void commitPushesFrameOnce() {
        SessionUndo s = new SessionUndo();
        SessionUndo.RescueHandle h = s.wrapRescueFrame(frame("c-1", "img.tif"));
        h.commit();
        assertTrue(h.isCommitted());
        assertEquals(1, s.totalFrames());

        // Idempotent — a second commit must not double-push.
        h.commit();
        assertEquals(1, s.totalFrames());
    }

    @Test
    public void releaseDoesNotPush() {
        SessionUndo s = new SessionUndo();
        SessionUndo.RescueHandle h = s.wrapRescueFrame(frame("c-1", "img.tif"));
        h.release();
        assertTrue(h.isReleased());
        assertFalse(h.isCommitted());
        assertEquals(0, s.totalFrames());
    }

    @Test
    public void releaseAfterCommitIsNoOp() {
        SessionUndo s = new SessionUndo();
        SessionUndo.RescueHandle h = s.wrapRescueFrame(frame("c-1", "img.tif"));
        h.commit();
        h.release();
        assertTrue(h.isCommitted());
        assertFalse(h.isReleased());
        assertEquals(1, s.totalFrames());
    }

    @Test
    public void commitAfterReleaseIsNoOp() {
        SessionUndo s = new SessionUndo();
        SessionUndo.RescueHandle h = s.wrapRescueFrame(frame("c-1", "img.tif"));
        h.release();
        h.commit();
        assertTrue(h.isReleased());
        assertFalse(h.isCommitted());
        assertEquals(0, s.totalFrames());
    }

    @Test
    public void tryWithResourcesReleasesUncommittedHandle() {
        SessionUndo s = new SessionUndo();
        try (SessionUndo.RescueHandle h = s.wrapRescueFrame(frame("c-1", "img.tif"))) {
            // Simulate a successful macro path: caller does nothing because
            // the surrounding code never decided to commit. close() must
            // drop the frame so the undo ring stays empty.
            assertNotNull(h);
        }
        assertEquals(0, s.totalFrames());
    }

    @Test
    public void tryWithResourcesPreservesExplicitCommit() {
        SessionUndo s = new SessionUndo();
        try (SessionUndo.RescueHandle h = s.wrapRescueFrame(frame("c-1", "img.tif"))) {
            h.commit();
        }
        // Explicit commit survives the close()-time release attempt.
        assertEquals(1, s.totalFrames());
    }

    @Test
    public void committedFrameLandsOnActiveBranchUnderItsTitle() {
        SessionUndo s = new SessionUndo();
        SessionUndo.RescueHandle h = s.wrapRescueFrame(frame("c-1", "img.tif"));
        h.commit();
        SessionUndo.Branch main = s.getBranch(SessionUndo.MAIN_BRANCH);
        assertNotNull(main);
        assertEquals(1, main.byImageTitle.get("img.tif").size());
    }

    @Test
    public void committedFrameIsResolvableByCallId() {
        SessionUndo s = new SessionUndo();
        SessionUndo.RescueHandle h = s.wrapRescueFrame(frame("rescue-c-1", "img.tif"));
        h.commit();
        SessionUndo.ResolvedFrame rf = s.resolveByCallId("rescue-c-1");
        assertNotNull(rf);
        assertEquals("img.tif", rf.imageTitle);
        assertEquals("rescue-c-1", rf.frame.callId);
    }
}
