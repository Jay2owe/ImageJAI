package imagejai.engine;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link SessionUndo}. Frames are constructed directly so the
 * tests run headless. Cover branch isolation, rewind semantics, the global
 * memory cap eviction path, and the protected-main-branch failure mode.
 */
public class SessionUndoTest {

    private static UndoFrame frame(String callId, String title, long sizeBytes) {
        byte[] compressed = new byte[(int) Math.max(0, sizeBytes)];
        return new UndoFrame(callId, title,
                4, 4, 1, 1, 1, 8,
                compressed, compressed.length,
                Collections.<UndoFrame.RoiSnapshot>emptyList(),
                "",
                System.currentTimeMillis(), false);
    }

    @Test
    public void freshSessionHasMainBranchActive() {
        SessionUndo s = new SessionUndo();
        assertEquals(SessionUndo.MAIN_BRANCH, s.activeBranchId());
        assertEquals(1, s.listBranches().size());
        assertEquals(0, s.totalFrames());
    }

    @Test
    public void pushFramePopulatesActiveBranchPerImage() {
        SessionUndo s = new SessionUndo();
        s.pushFrame(frame("c-1", "img.tif", 100));
        s.pushFrame(frame("c-2", "img.tif", 100));
        s.pushFrame(frame("c-3", "other.tif", 100));

        assertEquals(3, s.totalFrames());
        SessionUndo.Branch main = s.getBranch(SessionUndo.MAIN_BRANCH);
        assertEquals(2, main.byImageTitle.get("img.tif").size());
        assertEquals(1, main.byImageTitle.get("other.tif").size());
    }

    @Test
    public void rewindByCountReturnsNewestFramesPoppedFromActiveBranch() {
        SessionUndo s = new SessionUndo();
        s.pushFrame(frame("c-1", "img.tif", 10));
        s.pushFrame(frame("c-2", "img.tif", 10));
        s.pushFrame(frame("c-3", "img.tif", 10));

        List<UndoFrame> popped = s.rewindByCount("img.tif", 2);
        assertEquals(2, popped.size());
        assertEquals("c-3", popped.get(0).callId);
        assertEquals("c-2", popped.get(1).callId);

        SessionUndo.Branch main = s.getBranch(SessionUndo.MAIN_BRANCH);
        assertEquals(1, main.byImageTitle.get("img.tif").size());
    }

    @Test
    public void rewindByCallIdResolvesAcrossImagesOnActiveBranch() {
        SessionUndo s = new SessionUndo();
        s.pushFrame(frame("c-1", "img.tif", 10));
        s.pushFrame(frame("c-2", "other.tif", 10));

        SessionUndo.ResolvedFrame rf = s.resolveByCallId("c-2");
        assertNotNull(rf);
        assertEquals("other.tif", rf.imageTitle);

        List<UndoFrame> popped = s.rewindByCallId(rf.imageTitle, "c-2");
        assertEquals(1, popped.size());
        assertEquals("c-2", popped.get(0).callId);
    }

    @Test
    public void createBranchDeepCopiesActiveStateAndIsolatesFutureMutations() {
        SessionUndo s = new SessionUndo();
        s.pushFrame(frame("c-1", "img.tif", 10));
        s.pushFrame(frame("c-2", "img.tif", 10));

        SessionUndo.Branch fresh = s.createBranch("c-2");
        assertEquals(2, fresh.totalFrames());
        s.switchBranch(fresh.id);
        s.pushFrame(frame("c-3", "img.tif", 10));

        // New branch sees 3 frames; main still sees the original 2.
        assertEquals(3, fresh.totalFrames());
        SessionUndo.Branch main = s.getBranch(SessionUndo.MAIN_BRANCH);
        assertEquals(2, main.totalFrames());
    }

    @Test
    public void switchBranchToUnknownIdReturnsFalse() {
        SessionUndo s = new SessionUndo();
        assertFalse(s.switchBranch("not-here"));
        assertEquals(SessionUndo.MAIN_BRANCH, s.activeBranchId());
    }

    @Test
    public void deleteBranchProtectsMain() {
        SessionUndo s = new SessionUndo();
        assertFalse(s.deleteBranch(SessionUndo.MAIN_BRANCH));
        assertEquals(1, s.listBranches().size());
    }

    @Test
    public void deleteBranchSwitchesActiveBackToMainWhenActiveBranchDeleted() {
        SessionUndo s = new SessionUndo();
        SessionUndo.Branch fresh = s.createBranch(null);
        s.switchBranch(fresh.id);
        assertTrue(s.deleteBranch(fresh.id));
        assertEquals(SessionUndo.MAIN_BRANCH, s.activeBranchId());
    }

    @Test(expected = IllegalStateException.class)
    public void createBranchEnforcesBranchCap() {
        SessionUndo s = new SessionUndo();
        // main is the first; we can create up to MAX_BRANCHES total.
        for (int i = 1; i < SessionUndo.MAX_BRANCHES; i++) {
            s.createBranch(null);
        }
        // The (MAX_BRANCHES + 1)-th throws.
        s.createBranch(null);
    }

    @Test
    public void resolveByCallIdReturnsNullForUnknown() {
        SessionUndo s = new SessionUndo();
        s.pushFrame(frame("c-1", "img.tif", 10));
        assertNull(s.resolveByCallId("never"));
    }

    @Test
    public void resetClearsAllBranchesBackToMain() {
        SessionUndo s = new SessionUndo();
        s.pushFrame(frame("c-1", "img.tif", 10));
        s.createBranch(null);
        s.reset();
        assertEquals(1, s.listBranches().size());
        assertEquals(SessionUndo.MAIN_BRANCH, s.activeBranchId());
        assertEquals(0, s.totalFrames());
    }

    @Test
    public void peekBoundaryWithinReportsScriptBoundaryWithinPoppingRange() {
        SessionUndo s = new SessionUndo();
        s.pushFrame(frame("c-1", "img.tif", 10));
        s.pushBoundary("img.tif", "boundary-1");
        s.pushFrame(frame("c-2", "img.tif", 10));

        // Top-of-stack frame (n=1) is the macro frame — no boundary in range.
        assertNull(s.peekBoundaryWithin("img.tif", 1));
        // Two-frame walk crosses the boundary.
        UndoFrame b = s.peekBoundaryWithin("img.tif", 2);
        assertNotNull(b);
        assertEquals("boundary-1", b.callId);
    }

    @Test
    public void peekBoundaryBeforeCallIdRefusesWalkPastBoundary() {
        SessionUndo s = new SessionUndo();
        s.pushFrame(frame("c-1", "img.tif", 10));
        s.pushBoundary("img.tif", "boundary-1");
        s.pushFrame(frame("c-2", "img.tif", 10));
        // Targeting c-1 requires crossing the boundary — refuse.
        UndoFrame b = s.peekBoundaryBeforeCallId("img.tif", "c-1");
        assertNotNull(b);
        assertEquals("boundary-1", b.callId);
        // Targeting c-2 (top frame) is fine — no boundary in front.
        assertNull(s.peekBoundaryBeforeCallId("img.tif", "c-2"));
    }

    @Test
    public void evictionLoggerFiresOnGlobalCapBreach() {
        SessionUndo s = new SessionUndo();
        // Tune the cap small so the sweep fires after a few small frames —
        // unit tests must not allocate 500 MB.
        s.setGlobalCapBytesForTesting(150L);

        final java.util.List<String> events = new java.util.ArrayList<String>();
        s.setEvictionLogger(new java.util.function.Consumer<String>() {
            @Override
            public void accept(String summary) { events.add(summary); }
        });

        // Push 4 × 100-byte frames across distinct images — per-stack cap
        // (5 frames) does not fire, global cap (150 bytes) does.
        for (int i = 0; i < 4; i++) {
            s.pushFrame(frame("c-" + i, "img-" + i + ".tif", 100));
        }
        assertTrue("expected at least one global-eviction event, got "
                + events.size(), events.size() >= 1);
        for (String e : events) {
            assertTrue("event mentions evict: " + e,
                    e.toLowerCase().contains("evict"));
        }
        assertTrue(s.globalEvictionCount() >= 1L);
    }
}
