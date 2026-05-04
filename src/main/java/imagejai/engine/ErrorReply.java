package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.ArrayList;
import java.util.List;

/**
 * Structured error builder introduced by
 * {@code docs/tcp_upgrade/02_structured_errors.md}. Holds the category / code
 * / retry-safe / recovery-hint fields that make up a typed error reply, and
 * emits either a {@link JsonObject} (for clients that negotiated
 * {@code structured_errors} via {@code hello}) or a bare {@link JsonPrimitive}
 * string (legacy shape) depending on the caller's {@link
 * TCPCommandServer.AgentCaps}.
 * <p>
 * Call sites assemble the reply via a short fluent chain and hand the final
 * element directly to {@code JsonObject.add("error", ...)} — which accepts
 * either a nested object or a primitive.
 */
final class ErrorReply {

    // ---- Category constants ----
    static final String CAT_COMPILE   = "compile";
    static final String CAT_RUNTIME   = "runtime";
    static final String CAT_BLOCKED   = "blocked";
    static final String CAT_NOT_FOUND = "not_found";
    static final String CAT_STATE     = "state";

    // ---- Error code constants (initial set, step 02). Later steps add more. ----
    static final String CODE_MACRO_COMPILE_ERROR     = "MACRO_COMPILE_ERROR";
    static final String CODE_MACRO_RUNTIME_ERROR     = "MACRO_RUNTIME_ERROR";
    static final String CODE_MACRO_BLOCKED_ON_DIALOG = "MACRO_BLOCKED_ON_DIALOG";
    /**
     * Stage 04 (docs/safe_mode_v2/04_queue-storm-per-image.md): a second
     * macro arrived for an image whose previous macro is paused on a Fiji
     * modal dialog. Returned by {@code execute_macro} /
     * {@code execute_macro_async} when {@code caps.safeMode} and
     * {@code caps.safeModeOptions.queueStormGuard} are both on.
     */
    static final String CODE_QUEUE_STORM_BLOCKED    = "QUEUE_STORM_BLOCKED";
    /**
     * Stage 05 (docs/safe_mode_v2/05_destructive-scanner-expansion.md): the
     * scientific-integrity scanner found one or more {@code REJECT}-severity
     * findings — calibration loss, Z-project overwrite, microscopy →
     * PNG/JPEG overwrite, opt-in bit-depth narrowing, or opt-in normalize
     * contrast. Returned by {@code execute_macro} / {@code run_script}
     * when {@code caps.safeMode} and
     * {@code caps.safeModeOptions.scientificIntegrityScan} are both on.
     * The {@code operations[]} array on the structured-error reply names
     * each finding's rule_id, target, line, and message.
     */
    static final String CODE_DESTRUCTIVE_OP_BLOCKED  = "DESTRUCTIVE_OP_BLOCKED";
    static final String CODE_PLUGIN_NOT_FOUND        = "PLUGIN_NOT_FOUND";
    static final String CODE_IMAGE_NOT_OPEN          = "IMAGE_NOT_OPEN";
    static final String CODE_WRONG_IMAGE_TYPE        = "WRONG_IMAGE_TYPE";
    static final String CODE_NO_SELECTION            = "NO_SELECTION";
    /** Reserved for step 06 (Analyze Particles + no results-writing flag). */
    static final String CODE_NRESULTS_TRAP           = "NRESULTS_TRAP";
    /** Fallback for errors that haven't been classified yet. */
    static final String CODE_UNKNOWN                 = "UNKNOWN";

    private String code = CODE_UNKNOWN;
    private String category = CAT_RUNTIME;
    private boolean retrySafe = true;
    private String message = "";
    private String recoveryHint;
    private final List<JsonObject> suggested = new ArrayList<JsonObject>();
    private JsonObject sideEffects;

    ErrorReply() {}

    ErrorReply code(String c)        { this.code = c;        return this; }
    ErrorReply category(String c)    { this.category = c;    return this; }
    ErrorReply retrySafe(boolean b)  { this.retrySafe = b;   return this; }
    ErrorReply message(String m)     { this.message = m == null ? "" : m; return this; }
    ErrorReply recoveryHint(String h) { this.recoveryHint = h; return this; }

    ErrorReply addSuggested(JsonObject s) {
        if (s != null) this.suggested.add(s);
        return this;
    }

    ErrorReply sideEffects(JsonObject s) { this.sideEffects = s; return this; }

    String message() { return message; }

    /** Step 14: getter used by the ledger auto-attach on the macro-error path. */
    String code() { return code; }

    /**
     * Build the JSON form this reply should take for the given caps. If
     * {@code caps} is {@code null} or {@code caps.structuredErrors} is
     * {@code false}, return a plain {@link JsonPrimitive} carrying the message
     * — the pre-step-02 shape. Otherwise return a {@link JsonObject} matching
     * {@code docs/tcp_upgrade/02_structured_errors.md}.
     */
    JsonElement buildJsonElement(TCPCommandServer.AgentCaps caps) {
        if (caps != null && caps.structuredErrors) {
            JsonObject o = new JsonObject();
            o.addProperty("code", code);
            o.addProperty("category", category);
            o.addProperty("retry_safe", retrySafe);
            o.addProperty("message", message);
            if (recoveryHint != null && !recoveryHint.isEmpty()) {
                o.addProperty("recovery_hint", recoveryHint);
            }
            if (!suggested.isEmpty()) {
                JsonArray arr = new JsonArray();
                for (JsonObject s : suggested) arr.add(s);
                o.add("suggested", arr);
            }
            if (sideEffects != null) o.add("sideEffects", sideEffects);
            return o;
        }
        return new JsonPrimitive(message);
    }

    // -----------------------------------------------------------------------
    // Classification helpers for handleExecuteMacro / handleRunScript.
    //
    // Every concrete rawError string the macro / script pipeline produces gets
    // mapped onto (code, category, retrySafe, recoveryHint) here. Kept together
    // so the "why retry_safe defaults to X" rationale lives next to the rules.
    // -----------------------------------------------------------------------

    /** Build a reply from the raw macro/script error string. */
    static ErrorReply classifyMacroError(String rawError, boolean sideEffectsLanded) {
        String msg = rawError != null ? rawError : "Unknown macro error";
        ErrorReply r = new ErrorReply().message(msg);

        if (msg.startsWith("Macro paused on modal dialog")
                || msg.startsWith("Macro interrupted AFTER producing output")) {
            // Blocked-on-dialog: never retry with an identical macro — the
            // dialog either wants explicit args (run(name, args)) or side
            // effects already landed and the agent should inspect, not resend.
            r.code(CODE_MACRO_BLOCKED_ON_DIALOG).category(CAT_BLOCKED).retrySafe(false);
            if (sideEffectsLanded) {
                r.recoveryHint("Side effects already landed — inspect newImages/"
                        + "resultsTable in sideEffects; do NOT retry the same macro.");
            } else {
                r.recoveryHint("Dismiss the dialog or pass explicit args via "
                        + "run(name, args); probe the plugin for its parameter keys.");
            }
            return r;
        }

        if (msg.startsWith("Macro execution timed out")) {
            // Timeout is the one runtime case where a retry can succeed —
            // caller raises timeout_ms or splits the macro. Stays runtime
            // category but retry_safe flips true.
            r.code(CODE_MACRO_RUNTIME_ERROR).category(CAT_RUNTIME).retrySafe(true)
                    .recoveryHint("Retry with a larger timeout_ms, or split the macro.");
            return r;
        }

        String lower = msg.toLowerCase();
        if (lower.contains("out of memory") || lower.contains("heap space")) {
            // OOM: retry_safe true on the assumption that the agent frees
            // memory (close images / clear results) before retrying.
            r.code(CODE_MACRO_RUNTIME_ERROR).category(CAT_RUNTIME).retrySafe(true)
                    .recoveryHint("Close unused images or clear the results table, then retry.");
            return r;
        }

        if (lower.contains("unrecognized command")) {
            // Plugin name didn't match — fuzzy match (step 04) will populate
            // suggested[]. Mark not_found so agents branch correctly.
            r.code(CODE_PLUGIN_NOT_FOUND).category(CAT_NOT_FOUND).retrySafe(false)
                    .recoveryHint("Confirm the plugin exists via probe_command / "
                            + "Menus.getCommands(); fix the name before retrying.");
            return r;
        }

        if (lower.contains("syntax error") || lower.contains("undefined variable")
                || lower.contains("unrecognized identifier")) {
            // Parse-time failure in the macro text itself.
            r.code(CODE_MACRO_COMPILE_ERROR).category(CAT_COMPILE).retrySafe(false)
                    .recoveryHint("Re-read the macro source for syntax / typo bugs.");
            return r;
        }

        // Everything else — plugin-thrown runtime exception, log-detected error,
        // IJ.error dialog — defaults to runtime with retry_safe=false. The
        // agent has to change the macro (or the state it touches) before a
        // retry can succeed; per-category defaults documented in the plan.
        r.code(CODE_MACRO_RUNTIME_ERROR).category(CAT_RUNTIME).retrySafe(false)
                .recoveryHint("Inspect the error message; fix the macro or state before retrying.");
        return r;
    }
}
