package imagejai.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/**
 * Step 15: bounded per-image rolling stack of {@link UndoFrame}s. Newest
 * frame at the head of the deque. Eviction happens on push when either of
 * two caps is exceeded:
 *
 * <ul>
 *   <li>{@link #MAX_FRAMES} frames retained (oldest dropped first)</li>
 *   <li>{@link #MAX_BYTES} cumulative compressed bytes (oldest dropped first)</li>
 * </ul>
 *
 * The stack is intentionally minimal — branch state and global accounting
 * live one level up in {@link SessionUndo}. The two evictions tracked here
 * are a per-image safety net; the global cap kicks in before they normally
 * would for any reasonable session.
 *
 * <p>All public methods are synchronized — multiple TCP handlers may push or
 * read concurrently from different sockets.
 */
public final class UndoStack {

    /** Per-image frame count cap. Plan §Memory management: 5 frames. */
    public static final int MAX_FRAMES = 5;

    /** Per-image byte cap. Plan §Memory management: 100 MB compressed. */
    public static final long MAX_BYTES = 100L * 1024L * 1024L;

    /** Bytes evicted by the per-stack caps so far (lifetime). Read by
     *  {@link SessionUndo} for telemetry. */
    private long evictedBytes = 0L;

    private final Deque<UndoFrame> frames = new ArrayDeque<UndoFrame>();
    private long currentBytes = 0L;

    public synchronized void push(UndoFrame f) {
        if (f == null) return;
        frames.addFirst(f);
        currentBytes += f.sizeBytes;
        enforceCaps();
    }

    /** Pop the newest frame. Returns null if empty. */
    public synchronized UndoFrame pop() {
        if (frames.isEmpty()) return null;
        UndoFrame f = frames.removeFirst();
        currentBytes -= f.sizeBytes;
        if (currentBytes < 0) currentBytes = 0;
        return f;
    }

    /** Look at the n-th newest frame without removing it. n=0 is the top.
     *  Returns null when n is out of range. */
    public synchronized UndoFrame peek(int n) {
        if (n < 0 || n >= frames.size()) return null;
        Iterator<UndoFrame> it = frames.iterator();
        UndoFrame f = null;
        for (int i = 0; i <= n && it.hasNext(); i++) f = it.next();
        return f;
    }

    /** Find the frame whose callId matches. O(n) scan from newest to oldest.
     *  Returns null when not found (LRU-evicted or never recorded). */
    public synchronized UndoFrame findByCallId(String id) {
        if (id == null) return null;
        for (UndoFrame f : frames) {
            if (id.equals(f.callId)) return f;
        }
        return null;
    }

    /**
     * Drop frames newer than (or equal to) the given callId. Used by
     * {@link #rewindByCallId(String)} so the rewind target itself is consumed
     * — its successors are gone, the next push lands on top of the rewind
     * target.
     */
    public synchronized List<UndoFrame> dropDownTo(String callId) {
        List<UndoFrame> dropped = new ArrayList<UndoFrame>();
        if (callId == null) return dropped;
        Iterator<UndoFrame> it = frames.iterator();
        while (it.hasNext()) {
            UndoFrame f = it.next();
            dropped.add(f);
            currentBytes -= f.sizeBytes;
            it.remove();
            if (callId.equals(f.callId)) break;
        }
        if (currentBytes < 0) currentBytes = 0;
        return dropped;
    }

    /** Pop the top {@code n} frames and return them in pop order (newest
     *  first). When fewer than n are available the result is shorter. */
    public synchronized List<UndoFrame> popN(int n) {
        List<UndoFrame> out = new ArrayList<UndoFrame>();
        for (int i = 0; i < n; i++) {
            UndoFrame f = pop();
            if (f == null) break;
            out.add(f);
        }
        return out;
    }

    /** Walk back to and including the frame whose callId matches. The
     *  matched frame is the LAST entry in the returned list (it is the one
     *  to restore from). Returns an empty list when no match. */
    public synchronized List<UndoFrame> rewindByCallId(String callId) {
        if (callId == null || findByCallId(callId) == null) {
            return new ArrayList<UndoFrame>();
        }
        return dropDownTo(callId);
    }

    public synchronized int size() { return frames.size(); }
    public synchronized boolean isEmpty() { return frames.isEmpty(); }
    public synchronized long bytes() { return currentBytes; }
    public synchronized long evictedBytes() { return evictedBytes; }

    /** Snapshot the frames newest-first. Frames themselves are immutable so
     *  the returned list is safe to inspect concurrently. */
    public synchronized List<UndoFrame> framesSnapshot() {
        return new ArrayList<UndoFrame>(frames);
    }

    /** Forcibly drop the oldest frame; returns the bytes reclaimed. Used by
     *  the global LRU sweep in {@link SessionUndo}. Returns 0 when empty. */
    synchronized long evictOldest() {
        if (frames.isEmpty()) return 0L;
        UndoFrame removed = frames.removeLast();
        long reclaimed = removed.sizeBytes;
        currentBytes -= reclaimed;
        evictedBytes += reclaimed;
        if (currentBytes < 0) currentBytes = 0;
        return reclaimed;
    }

    /** Deep-copy the stack — frames are shared (immutable) but the deque is
     *  duplicated. Used by {@code branch_create} so the new branch's history
     *  is independent of the source. */
    public synchronized UndoStack copy() {
        UndoStack out = new UndoStack();
        // Walk newest -> oldest so the destination ends up with the same order
        // (addLast preserves the original head-first sequence).
        Iterator<UndoFrame> it = frames.iterator();
        while (it.hasNext()) {
            UndoFrame f = it.next();
            out.frames.addLast(f);
            out.currentBytes += f.sizeBytes;
        }
        return out;
    }

    private void enforceCaps() {
        // Frame-count cap.
        while (frames.size() > MAX_FRAMES) {
            UndoFrame removed = frames.removeLast();
            currentBytes -= removed.sizeBytes;
            evictedBytes += removed.sizeBytes;
        }
        // Byte cap — drop oldest until we fit.
        while (currentBytes > MAX_BYTES && frames.size() > 1) {
            UndoFrame removed = frames.removeLast();
            currentBytes -= removed.sizeBytes;
            evictedBytes += removed.sizeBytes;
        }
        if (currentBytes < 0) currentBytes = 0;
    }
}
