package imagejai.engine;

/**
 * Captures the text delta appended to ImageJ's Recorder singleton over a
 * single macro run, so the server can echo the canonical (post-interpreter)
 * macro back to the agent. Introduced by
 * {@code docs/tcp_upgrade/03_canonical_macro_echo.md}.
 * <p>
 * Contract:
 * <ol>
 *   <li>{@link #begin()} snapshots the Recorder's current text length and
 *       opens an invisible Recorder if none is live.</li>
 *   <li>The caller runs the macro.</li>
 *   <li>{@link #getDelta()} returns the text appended since {@link #begin()}.</li>
 *   <li>{@link #close()} releases the Recorder if this instance opened it.
 *       A user-opened Recorder is left untouched.</li>
 * </ol>
 * <p>
 * All interactions with the Recorder are guarded — on any {@link Throwable}
 * the methods degrade to no-ops and empty strings rather than propagating.
 * This lets callers wrap the capture lifecycle without extra defensive code,
 * and means Fiji configurations where the Recorder is disabled globally
 * simply yield an empty {@code ranCode}.
 * <p>
 * The static {@link #normalise(String)} and {@link #differs(String, String)}
 * helpers are pure-function utilities used to decide whether the captured
 * text differs meaningfully from what the agent submitted — exercised by
 * {@code RecorderCaptureTest} without requiring a live Fiji.
 */
final class RecorderCapture implements AutoCloseable {

    private final ij.plugin.frame.Recorder rec;
    private final boolean weOpenedIt;
    private final int lenBefore;

    private RecorderCapture(ij.plugin.frame.Recorder rec,
                            boolean weOpenedIt, int lenBefore) {
        this.rec = rec;
        this.weOpenedIt = weOpenedIt;
        this.lenBefore = lenBefore;
    }

    /**
     * Begin a capture session. Returns {@code null} if the Recorder cannot be
     * acquired in this Fiji environment (headless tests, Recorder disabled,
     * class not on the classpath) — callers should treat null as "no capture".
     */
    static RecorderCapture begin() {
        try {
            ij.plugin.frame.Recorder existing = ij.plugin.frame.Recorder.getInstance();
            boolean opened = false;
            ij.plugin.frame.Recorder rec = existing;
            if (rec == null) {
                try {
                    // showFrame=false — don't flash a Recorder window at the user
                    // for every agent-driven macro. The text buffer still works.
                    new ij.plugin.frame.Recorder(false);
                } catch (Throwable t) {
                    return null;
                }
                rec = ij.plugin.frame.Recorder.getInstance();
                opened = rec != null;
            }
            if (rec == null) return null;
            int lenBefore = textLengthOf(rec);
            return new RecorderCapture(rec, opened, lenBefore);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Return the text appended to the Recorder since {@link #begin()}. */
    String getDelta() {
        String full = textOf(rec);
        if (full.length() <= lenBefore) return "";
        return full.substring(lenBefore).trim();
    }

    /**
     * Close this capture session. Only closes the Recorder when this instance
     * opened it; a user-opened Recorder is left running.
     */
    @Override
    public void close() {
        if (weOpenedIt) {
            try { rec.close(); } catch (Throwable ignore) {}
        }
    }

    // -----------------------------------------------------------------------
    // Pure-function helpers (unit-tested without live Fiji).
    // -----------------------------------------------------------------------

    /**
     * Conservative whitespace normalisation for diff comparison. Collapses
     * runs of whitespace and trims — but keeps quote contents, identifiers,
     * and punctuation intact. {@code "Otzu"} vs {@code "Default"} survives
     * as a diff; trailing newlines or extra spaces do not.
     */
    static String normalise(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+", " ").trim();
    }

    /**
     * True when {@code ran} is materially different from {@code submitted}.
     * Empty or null {@code ran} returns false — if the Recorder produced
     * nothing, there's nothing to echo.
     */
    static boolean differs(String submitted, String ran) {
        if (ran == null || ran.isEmpty()) return false;
        return !normalise(submitted).equals(normalise(ran));
    }

    // -----------------------------------------------------------------------
    // Internal plumbing, defensive against Recorder API variation.
    // -----------------------------------------------------------------------

    private static String textOf(ij.plugin.frame.Recorder rec) {
        try {
            String t = rec.getText();
            return t == null ? "" : t;
        } catch (Throwable t) {
            return "";
        }
    }

    private static int textLengthOf(ij.plugin.frame.Recorder rec) {
        return textOf(rec).length();
    }
}
