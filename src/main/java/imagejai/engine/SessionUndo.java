package imagejai.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Step 15 (docs/tcp_upgrade/15_undo_stack_api.md): server-scoped undo state.
 * Owns one or more named branches; each branch holds its own per-image
 * {@link UndoStack}. Frames pushed via {@link #pushFrame} land on the
 * currently-active branch.
 *
 * <p>Branches let an agent fork an exploration: from a known-good state, run
 * one path on the main branch, switch to a new branch and run the alternative,
 * compare, then keep whichever wins. The {@code branch_*} TCP commands map
 * one-to-one onto the methods here.
 *
 * <p>Memory accounting:
 * <ul>
 *   <li>Per-image cap: {@link UndoStack#MAX_FRAMES} frames or
 *       {@link UndoStack#MAX_BYTES} compressed bytes (whichever first).</li>
 *   <li>Per-session global cap: {@link #GLOBAL_CAP_BYTES}; LRU-evicts the
 *       oldest frame across all images / branches when exceeded.</li>
 *   <li>Branch count cap: {@link #MAX_BRANCHES} branches; the oldest branch
 *       (by creation time) is rejected with EVICT-on-create rather than
 *       silently overwriting.</li>
 * </ul>
 *
 * <p>Reads from this class are synchronized via the shared {@link #lock} so
 * a rewind in flight cannot race a concurrent push.
 */
public final class SessionUndo {

    /** Default-named branch present at construction time. */
    public static final String MAIN_BRANCH = "main";

    /** Plan §Memory management: 500 MB total session cap. */
    public static final long GLOBAL_CAP_BYTES = 500L * 1024L * 1024L;

    /** Plan §Failure modes: cap branch proliferation. */
    public static final int MAX_BRANCHES = 10;

    /** A named branch with its own per-image stacks. */
    public static final class Branch {
        public final String id;
        public final String baseCallId;
        public final long createdMs;
        // LinkedHashMap so {@link #framesSnapshot} returns titles in the order
        // they were first pushed onto the branch.
        final Map<String, UndoStack> byImageTitle = new LinkedHashMap<String, UndoStack>();

        Branch(String id, String baseCallId) {
            this.id = id;
            this.baseCallId = baseCallId;
            this.createdMs = System.currentTimeMillis();
        }

        public synchronized int totalFrames() {
            int n = 0;
            for (UndoStack s : byImageTitle.values()) n += s.size();
            return n;
        }

        public synchronized long totalBytes() {
            long b = 0L;
            for (UndoStack s : byImageTitle.values()) b += s.bytes();
            return b;
        }

        public synchronized List<String> imageTitles() {
            return new ArrayList<String>(byImageTitle.keySet());
        }
    }

    private final Object lock = new Object();
    private final Map<String, Branch> branches = new LinkedHashMap<String, Branch>();
    private String activeBranchId;
    private final AtomicLong branchSeq = new AtomicLong(0);
    /** Lifetime count of frames evicted by the global LRU sweep — primarily
     *  for telemetry / FrictionLog visibility. */
    private long globalEvictionCount = 0L;
    private long globalEvictedBytes = 0L;

    /** Optional sink invoked when the global LRU sweep evicts a frame.
     *  Plan §Memory management requires we surface this to FrictionLog so
     *  ops can spot under-tight caps. Wired by {@link TCPCommandServer} after
     *  construction so the SessionUndo class itself stays free of telemetry
     *  dependencies. The argument is a one-line summary suitable for
     *  FrictionLog.record(...). */
    private java.util.function.Consumer<String> evictionLogger;

    /** Mutable global cap; defaults to {@link #GLOBAL_CAP_BYTES}. Tunable via
     *  {@link #setGlobalCapBytesForTesting} so unit tests can drive the LRU
     *  sweep without allocating half a gigabyte. Production callers never
     *  touch this. */
    private long activeGlobalCapBytes = GLOBAL_CAP_BYTES;

    public SessionUndo() {
        Branch main = new Branch(MAIN_BRANCH, null);
        branches.put(MAIN_BRANCH, main);
        activeBranchId = MAIN_BRANCH;
    }

    // --------------------------------------------------------------------
    // Branch operations
    // --------------------------------------------------------------------

    public String activeBranchId() {
        synchronized (lock) {
            return activeBranchId;
        }
    }

    public List<Branch> listBranches() {
        synchronized (lock) {
            return new ArrayList<Branch>(branches.values());
        }
    }

    public Branch getBranch(String id) {
        synchronized (lock) {
            return branches.get(id);
        }
    }

    /** Create a new branch as a deep copy of the active branch's per-image
     *  stacks. {@code fromCallId} is recorded as the base point — agents can
     *  use it as a label even when the per-image walk-back semantics aren't
     *  exact (the v1 implementation preserves the active branch's full
     *  history rather than truncating to fromCallId, on the principle that
     *  history loss is worse than a slightly noisier branch base). Throws
     *  IllegalStateException when the branch cap is hit. */
    public Branch createBranch(String fromCallId) {
        synchronized (lock) {
            if (branches.size() >= MAX_BRANCHES) {
                throw new IllegalStateException(
                        "branch cap reached (" + MAX_BRANCHES
                      + ") — call branch_delete on an unused branch first");
            }
            String id = "b-" + branchSeq.incrementAndGet();
            Branch source = branches.get(activeBranchId);
            Branch fresh = new Branch(id, fromCallId);
            if (source != null) {
                for (Map.Entry<String, UndoStack> e : source.byImageTitle.entrySet()) {
                    fresh.byImageTitle.put(e.getKey(), e.getValue().copy());
                }
            }
            branches.put(id, fresh);
            return fresh;
        }
    }

    /** Activate a branch by id. Returns false when no such branch exists. */
    public boolean switchBranch(String id) {
        synchronized (lock) {
            if (id == null || !branches.containsKey(id)) return false;
            activeBranchId = id;
            return true;
        }
    }

    /** Drop a branch. The {@link #MAIN_BRANCH} cannot be deleted. Switches
     *  the active pointer back to MAIN when the deleted branch was active. */
    public boolean deleteBranch(String id) {
        synchronized (lock) {
            if (MAIN_BRANCH.equals(id)) return false;
            Branch removed = branches.remove(id);
            if (removed == null) return false;
            if (id.equals(activeBranchId)) activeBranchId = MAIN_BRANCH;
            return true;
        }
    }

    // --------------------------------------------------------------------
    // Frame push / rewind
    // --------------------------------------------------------------------

    /** Wire a sink for global-LRU eviction events. Pass null to clear. */
    public void setEvictionLogger(java.util.function.Consumer<String> logger) {
        synchronized (lock) {
            this.evictionLogger = logger;
        }
    }

    /** Tune the global byte cap for tests. Production must not call this. */
    void setGlobalCapBytesForTesting(long cap) {
        synchronized (lock) {
            this.activeGlobalCapBytes = Math.max(0L, cap);
        }
    }

    /** Push a script-boundary sentinel onto the active branch's per-image
     *  stack. The frame carries no pixels — its only purpose is to mark
     *  "the agent ran a Groovy/Jython script here, do not rewind past it"
     *  per plan §Out-of-scope. {@link #peekBoundaryWithin} and
     *  {@link #peekBoundaryBeforeCallId} surface the marker to the rewind
     *  handler. */
    public void pushBoundary(String imageTitle, String callId) {
        if (imageTitle == null) return;
        pushFrame(UndoFrame.boundary(callId, imageTitle));
    }

    /** Returns the script-boundary frame the rewind would cross when popping
     *  {@code n} frames from {@code imageTitle}'s stack on the active
     *  branch. Returns null when no boundary is in the popping range. */
    public UndoFrame peekBoundaryWithin(String imageTitle, int n) {
        synchronized (lock) {
            Branch b = branches.get(activeBranchId);
            if (b == null) return null;
            UndoStack stack = b.byImageTitle.get(imageTitle);
            if (stack == null) return null;
            for (int i = 0; i < n; i++) {
                UndoFrame f = stack.peek(i);
                if (f == null) break;
                if (f.scriptBoundary) return f;
            }
            return null;
        }
    }

    /** Returns the script-boundary frame that sits between the top of the
     *  {@code imageTitle} stack and the (exclusive) frame whose callId
     *  matches. The matched frame itself is allowed; only frames newer than
     *  it are inspected. Returns null when no boundary blocks the walk, or
     *  when the targeted callId is not present (the caller decides what
     *  UNDO_NOT_FOUND means then). */
    public UndoFrame peekBoundaryBeforeCallId(String imageTitle, String callId) {
        if (callId == null) return null;
        synchronized (lock) {
            Branch b = branches.get(activeBranchId);
            if (b == null) return null;
            UndoStack stack = b.byImageTitle.get(imageTitle);
            if (stack == null) return null;
            for (UndoFrame f : stack.framesSnapshot()) {
                if (callId.equals(f.callId)) return null;
                if (f.scriptBoundary) return f;
            }
            return null;
        }
    }

    /** Push a frame onto the active branch's per-image stack. Triggers a
     *  global LRU sweep if the session-wide cap is exceeded. */
    public void pushFrame(UndoFrame frame) {
        if (frame == null || frame.imageTitle == null) return;
        synchronized (lock) {
            Branch b = branches.get(activeBranchId);
            if (b == null) return;
            UndoStack stack = b.byImageTitle.get(frame.imageTitle);
            if (stack == null) {
                stack = new UndoStack();
                b.byImageTitle.put(frame.imageTitle, stack);
            }
            stack.push(frame);
            enforceGlobalCap();
        }
    }

    /** Pop the top {@code n} frames from the named title on the active
     *  branch. Returned in pop order (newest first). The frame returned is
     *  the one before the one to restore — i.e. {@code rewind(n)} should
     *  inspect the n-th popped frame to know what state to write back. */
    public List<UndoFrame> rewindByCount(String imageTitle, int n) {
        synchronized (lock) {
            Branch b = branches.get(activeBranchId);
            if (b == null) return new ArrayList<UndoFrame>();
            UndoStack stack = b.byImageTitle.get(imageTitle);
            if (stack == null) return new ArrayList<UndoFrame>();
            return stack.popN(n);
        }
    }

    /** Walk the named title's stack until the frame with the given callId is
     *  removed. The matched frame is the LAST entry in the returned list. */
    public List<UndoFrame> rewindByCallId(String imageTitle, String callId) {
        synchronized (lock) {
            Branch b = branches.get(activeBranchId);
            if (b == null) return new ArrayList<UndoFrame>();
            UndoStack stack = b.byImageTitle.get(imageTitle);
            if (stack == null) return new ArrayList<UndoFrame>();
            return stack.rewindByCallId(callId);
        }
    }

    /** Search every branch's every per-image stack for a frame with the
     *  given callId. Used by {@code rewind to_call_id} when the caller did
     *  not name an image — first match wins. */
    public ResolvedFrame resolveByCallId(String callId) {
        synchronized (lock) {
            Branch b = branches.get(activeBranchId);
            if (b == null) return null;
            for (Map.Entry<String, UndoStack> e : b.byImageTitle.entrySet()) {
                UndoFrame f = e.getValue().findByCallId(callId);
                if (f != null) return new ResolvedFrame(e.getKey(), f);
            }
            return null;
        }
    }

    /** Wrapper for {@link #resolveByCallId} so callers receive both the
     *  matched frame and the title whose stack holds it. */
    public static final class ResolvedFrame {
        public final String imageTitle;
        public final UndoFrame frame;
        ResolvedFrame(String imageTitle, UndoFrame frame) {
            this.imageTitle = imageTitle;
            this.frame = frame;
        }
    }

    /** Total bytes held across every branch and stack. */
    public long totalBytes() {
        synchronized (lock) {
            long total = 0L;
            for (Branch b : branches.values()) total += b.totalBytes();
            return total;
        }
    }

    /** Total frame count across every branch and stack. */
    public int totalFrames() {
        synchronized (lock) {
            int total = 0;
            for (Branch b : branches.values()) total += b.totalFrames();
            return total;
        }
    }

    public long globalEvictionCount() {
        synchronized (lock) {
            return globalEvictionCount;
        }
    }

    public long globalEvictedBytes() {
        synchronized (lock) {
            return globalEvictedBytes;
        }
    }

    /** Wipe every branch's state — primarily for tests. Resets the active
     *  pointer back to {@link #MAIN_BRANCH}. */
    public void reset() {
        synchronized (lock) {
            branches.clear();
            branches.put(MAIN_BRANCH, new Branch(MAIN_BRANCH, null));
            activeBranchId = MAIN_BRANCH;
            globalEvictionCount = 0L;
            globalEvictedBytes = 0L;
        }
    }

    // --------------------------------------------------------------------
    // Internals
    // --------------------------------------------------------------------

    private void enforceGlobalCap() {
        long bytes = 0L;
        for (Branch b : branches.values()) bytes += b.totalBytes();
        if (bytes <= activeGlobalCapBytes) return;
        // LRU sweep: walk every (branch, stack) pair and pick the stack whose
        // OLDEST frame is the oldest in the session. Drop one frame and loop
        // until under the cap. O(branches × images) per eviction is fine —
        // both are small.
        while (bytes > activeGlobalCapBytes) {
            UndoStack victim = pickOldestStack();
            if (victim == null) break; // nothing left to evict
            long reclaimed = victim.evictOldest();
            if (reclaimed <= 0) break;
            bytes -= reclaimed;
            globalEvictionCount++;
            globalEvictedBytes += reclaimed;
            // Plan §Memory management: surface the eviction so an over-tight
            // cap is visible. Best-effort; logger failures must not corrupt
            // the sweep. Snapshot the consumer under the lock then call out;
            // it does no I/O of its own beyond a FrictionLog.record call.
            java.util.function.Consumer<String> log = this.evictionLogger;
            if (log != null) {
                try {
                    log.accept("global LRU evicted "
                            + reclaimed + " bytes; remaining=" + bytes
                            + " cap=" + activeGlobalCapBytes);
                } catch (Throwable ignore) {
                    // Telemetry must never break the sweep.
                }
            }
        }
    }

    private UndoStack pickOldestStack() {
        UndoStack oldest = null;
        long oldestTs = Long.MAX_VALUE;
        for (Branch b : branches.values()) {
            for (UndoStack s : b.byImageTitle.values()) {
                List<UndoFrame> snap = s.framesSnapshot();
                if (snap.isEmpty()) continue;
                UndoFrame tail = snap.get(snap.size() - 1);
                if (tail.timestampMs < oldestTs) {
                    oldestTs = tail.timestampMs;
                    oldest = s;
                }
            }
        }
        return oldest;
    }
}
