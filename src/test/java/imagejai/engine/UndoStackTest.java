package imagejai.engine;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link UndoStack}. All frames are constructed directly via
 * the {@link UndoFrame} constructor so the tests run headless without a live
 * ImagePlus.
 */
public class UndoStackTest {

    private static UndoFrame frame(String callId, long sizeBytes) {
        // sizeBytes is derived from compressed length + csv length; pad the
        // compressed buffer so the constructor's accounting matches.
        byte[] compressed = new byte[(int) Math.max(0, sizeBytes)];
        return new UndoFrame(callId, "img.tif",
                4, 4, 1, 1, 1, 8,
                compressed, compressed.length,
                Collections.<UndoFrame.RoiSnapshot>emptyList(),
                "",
                System.currentTimeMillis(), false);
    }

    @Test
    public void pushAndPeekReturnsFramesNewestFirst() {
        UndoStack s = new UndoStack();
        s.push(frame("c-1", 10));
        s.push(frame("c-2", 10));
        s.push(frame("c-3", 10));

        assertEquals(3, s.size());
        assertEquals("c-3", s.peek(0).callId);
        assertEquals("c-2", s.peek(1).callId);
        assertEquals("c-1", s.peek(2).callId);
        assertNull(s.peek(3));
    }

    @Test
    public void popRemovesNewestFirst() {
        UndoStack s = new UndoStack();
        s.push(frame("c-1", 10));
        s.push(frame("c-2", 10));

        assertEquals("c-2", s.pop().callId);
        assertEquals(1, s.size());
        assertEquals("c-1", s.pop().callId);
        assertEquals(0, s.size());
        assertNull(s.pop());
    }

    @Test
    public void findByCallIdScansAllFrames() {
        UndoStack s = new UndoStack();
        s.push(frame("a", 10));
        s.push(frame("b", 10));
        s.push(frame("c", 10));

        assertEquals("a", s.findByCallId("a").callId);
        assertEquals("b", s.findByCallId("b").callId);
        assertEquals("c", s.findByCallId("c").callId);
        assertNull(s.findByCallId("never"));
    }

    @Test
    public void pushOverFiveFramesEvictsOldest() {
        UndoStack s = new UndoStack();
        for (int i = 1; i <= UndoStack.MAX_FRAMES + 1; i++) {
            s.push(frame("c-" + i, 10));
        }
        assertEquals(UndoStack.MAX_FRAMES, s.size());
        // First-pushed frame is evicted; oldest still present is c-2.
        assertNull(s.findByCallId("c-1"));
        assertNotNull(s.findByCallId("c-2"));
    }

    @Test
    public void rewindByCallIdConsumesFramesUpToTarget() {
        UndoStack s = new UndoStack();
        s.push(frame("a", 10));
        s.push(frame("b", 10));
        s.push(frame("c", 10));
        s.push(frame("d", 10));

        List<UndoFrame> popped = s.rewindByCallId("b");
        assertEquals(3, popped.size());
        // newest-first: d, c, b
        assertEquals("d", popped.get(0).callId);
        assertEquals("c", popped.get(1).callId);
        assertEquals("b", popped.get(2).callId);
        // a remains
        assertEquals(1, s.size());
        assertEquals("a", s.peek(0).callId);
    }

    @Test
    public void rewindByCallIdMissingReturnsEmptyAndKeepsStack() {
        UndoStack s = new UndoStack();
        s.push(frame("a", 10));
        s.push(frame("b", 10));

        List<UndoFrame> popped = s.rewindByCallId("never");
        assertTrue(popped.isEmpty());
        assertEquals(2, s.size());
    }

    @Test
    public void copyProducesIndependentStack() {
        UndoStack s = new UndoStack();
        s.push(frame("a", 10));
        s.push(frame("b", 10));
        UndoStack other = s.copy();
        // Mutating one must not touch the other.
        s.pop();
        assertEquals(1, s.size());
        assertEquals(2, other.size());
        assertEquals("b", other.peek(0).callId);
    }

    @Test
    public void evictOldestReturnsBytesAndDecrementsAccounting() {
        UndoStack s = new UndoStack();
        s.push(frame("a", 50));
        s.push(frame("b", 30));
        long before = s.bytes();
        long reclaimed = s.evictOldest();
        // Oldest is "a"; reclaimed >= 50 (sizeBytes also counts csv etc.).
        assertTrue("reclaimed at least the compressed bytes",
                reclaimed >= 50);
        assertEquals(before - reclaimed, s.bytes());
        assertEquals(1, s.size());
        assertEquals("b", s.peek(0).callId);
        assertTrue(s.evictedBytes() >= reclaimed);
    }
}
