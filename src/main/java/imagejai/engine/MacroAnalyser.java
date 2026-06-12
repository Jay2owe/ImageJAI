package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Post-execution pattern-matching on a macro's source + its after-state,
 * introduced by {@code docs/tcp_upgrade/06_nresults_trap.md}. Each rule is a
 * pure {@code (code, state) -> Optional<Warning>} function; {@link #analyse}
 * concatenates the firing warnings into a single list that {@code
 * handleExecuteMacro} surfaces as a top-level {@code warnings[]} array when
 * {@code caps.warnings} is on.
 * <p>
 * The rule set is deliberately extensible: later steps (11 "measure on binary
 * mask", 13 "threshold after measure", etc.) add a new {@code
 * Optional<Warning>}-returning method and append it to the {@code analyse}
 * chain. No shared state, no ordering assumptions, no runtime dependencies
 * beyond whatever {@link PostExec} already captured.
 */
final class MacroAnalyser {

    private MacroAnalyser() {}

    /**
     * Snapshot of post-execution state the analyser rules may need. Kept as
     * a plain struct so the caller populates it once (from the data it
     * already collected for stateDelta) and we avoid racing a second
     * {@code ResultsTable.getCounter()} read against any late updates.
     */
    static final class PostExec {
        final int nResultsAfter;

        PostExec(int nResultsAfter) {
            this.nResultsAfter = nResultsAfter;
        }
    }

    /**
     * A single warning emitted by one of the rule functions. Serialises to
     * the JSON shape documented in step 06:
     * <pre>{@code
     * {"code": "NRESULTS_TRAP",
     *  "message": "...",
     *  "hint": "...",
     *  "affectedLines": [3]}
     * }</pre>
     * {@code hint} and {@code affectedLines} are omitted when absent.
     */
    static final class Warning {
        final String code;
        final String message;
        final String hint;
        final List<Integer> affectedLines;

        Warning(String code, String message, String hint, List<Integer> affectedLines) {
            this.code = code;
            this.message = message;
            this.hint = hint;
            this.affectedLines = affectedLines != null ? affectedLines : List.of();
        }

        JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("code", code);
            obj.addProperty("message", message);
            if (hint != null && !hint.isEmpty()) {
                obj.addProperty("hint", hint);
            }
            if (affectedLines != null && !affectedLines.isEmpty()) {
                JsonArray arr = new JsonArray();
                for (Integer line : affectedLines) arr.add(line);
                obj.add("affectedLines", arr);
            }
            return obj;
        }
    }

    /**
     * Run every registered rule against the macro source and post-state.
     * Returns an empty list when no rule fires — the caller omits the
     * {@code warnings[]} field in that case per the step 06 reply contract.
     */
    static List<Warning> analyse(String code, PostExec state) {
        List<Warning> out = new ArrayList<>();
        if (code == null || state == null) return out;
        nresultsTrap(code, state).ifPresent(out::add);
        // Future rules (step 11, 13, ...) append here with the same signature.
        return out;
    }

    // -----------------------------------------------------------------------
    // Rules
    // -----------------------------------------------------------------------

    /**
     * Matches any {@code run("Analyze Particles...", "OPTS")} call. Gemma
     * writes single- and double-quoted variants almost interchangeably, so
     * the regex accepts either for both arguments. The ellipsis is literal
     * (three dots) — "Analyze Particles..." is the canonical command name.
     */
    private static final Pattern ANALYZE_PARTICLES = Pattern.compile(
            "run\\s*\\(\\s*[\"']Analyze Particles\\.\\.\\.[\"']\\s*,\\s*[\"']([^\"']*)[\"']");

    /**
     * Fires when {@code Analyze Particles} was called without a flag that
     * writes to the Results table ({@code summarize}, {@code display},
     * {@code show=[...]}) AND the post-execution Results table is empty.
     * Those three conditions together pin down the exact pattern that
     * looks like a threshold-tuning bug to small models: the macro ran
     * correctly, {@code nResults == 0} is by design, and the agent is
     * about to spend seven turns re-running with smaller size bins.
     * <p>
     * The {@code add} option alone populates the ROI Manager but not the
     * Results table; agents who {@code add} then read {@code nResults} hit
     * the same trap, so we still fire and steer them toward
     * {@code roiManager('count')}.
     */
    static Optional<Warning> nresultsTrap(String code, PostExec state) {
        Matcher m = ANALYZE_PARTICLES.matcher(code);
        if (!m.find()) return Optional.empty();
        String opts = m.group(1);
        boolean writesResults = opts.contains("summarize")
                || opts.contains("display")
                || opts.contains("show=[");
        if (writesResults) return Optional.empty();
        if (state.nResultsAfter > 0) return Optional.empty();

        int line = lineOf(code, m.start());
        boolean roiOnly = opts.contains("add");
        String hint = roiOnly
                ? "You used 'add' — rows go to ROI Manager, not the Results "
                        + "table. Read roiManager('count') for the count."
                : "Add 'summarize' or 'display' to the options to populate "
                        + "Results, or use 'add' and read roiManager('count').";
        return Optional.of(new Warning(
                ErrorReply.CODE_NRESULTS_TRAP,
                "Analyze Particles was called without a flag that writes to "
                        + "Results; nResults is 0 by design.",
                hint,
                List.of(line)));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * 1-based line number containing the given character offset. A pure count
     * of {@code '\n'}s before the offset plus one — deliberately naive so the
     * analyser stays regex-only per step 06's scope bound.
     */
    static int lineOf(String code, int charOffset) {
        if (code == null || charOffset <= 0) return 1;
        int line = 1;
        int limit = Math.min(charOffset, code.length());
        for (int i = 0; i < limit; i++) {
            if (code.charAt(i) == '\n') line++;
        }
        return line;
    }
}
