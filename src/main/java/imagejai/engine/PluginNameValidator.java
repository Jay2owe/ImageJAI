package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pre-execution validator for {@code run("PluginName", ...)} calls in a
 * submitted macro string. Introduced by
 * {@code docs/tcp_upgrade/04_fuzzy_plugin_registry.md}.
 * <p>
 * The pipeline:
 * <ol>
 *   <li>Regex-scan the macro for every {@code run(...)} call.</li>
 *   <li>For each plugin name, consult {@link MenuCommandRegistry}:
 *     <ul>
 *       <li>Exact match → no change.</li>
 *       <li>Jaro-Winkler &ge; 0.95 against a single clear winner (next
 *           candidate at least 0.05 lower) → auto-correct, record a
 *           {@link Correction}.</li>
 *       <li>Otherwise → record a {@link Rejection} with the top-3 matches.</li>
 *     </ul>
 *   </li>
 *   <li>If any rejection was found, surface the first one as an error; the
 *       macro never runs. Only {@code Correction}s applied produce a mutated
 *       macro that then runs normally.</li>
 * </ol>
 * <p>
 * Intentionally narrow: matches only double-quoted and single-quoted literal
 * plugin names. Obfuscated forms ({@code run("Plug" + "in")}, variable
 * substitution) bypass validation. That's safe — an agent that obfuscates
 * is already outside the hallucination failure mode this targets.
 */
final class PluginNameValidator {

    /**
     * Pattern matches: {@code run(<ws>"name"} or {@code run(<ws>'name'}.
     * Capture group 1 is the plugin name (without quotes).
     */
    private static final Pattern RUN_CALL =
            Pattern.compile("run\\s*\\(\\s*[\"']([^\"'\\n\\r]+)[\"']");

    static final class Correction {
        final String from;
        final String to;
        final double score;
        Correction(String from, String to, double score) {
            this.from = from; this.to = to; this.score = score;
        }
    }

    static final class Rejection {
        final String used;
        final List<MenuCommandRegistry.Match> topMatches;
        final double bestScore;
        Rejection(String used, List<MenuCommandRegistry.Match> top, double best) {
            this.used = used; this.topMatches = top; this.bestScore = best;
        }
    }

    static final class Result {
        final String patchedCode;
        final List<Correction> corrections;
        final List<Rejection> rejections;
        Result(String patched, List<Correction> corr, List<Rejection> rej) {
            this.patchedCode = patched;
            this.corrections = corr;
            this.rejections = rej;
        }
        boolean hasRejections() { return !rejections.isEmpty(); }
        boolean hasCorrections() { return !corrections.isEmpty(); }
    }

    /** Thresholds mirror the plan — conservative to avoid Laplacian-DoG false-fix. */
    static final double AUTO_CORRECT_MIN = 0.95;
    static final double AUTO_CORRECT_MARGIN = 0.05;
    static final double SUGGEST_MIN = 0.85;
    static final int SUGGEST_TOP_N = 3;

    private PluginNameValidator() {}

    /**
     * Validate every {@code run("name", ...)} call against the command
     * registry. Returns a result describing any corrections applied and
     * rejections found.
     */
    static Result validate(String code) {
        if (code == null || code.isEmpty()) {
            return new Result("", Collections.<Correction>emptyList(),
                              Collections.<Rejection>emptyList());
        }
        MenuCommandRegistry reg = MenuCommandRegistry.get();
        if (reg.size() == 0) {
            // Nothing to validate against — registry isn't loaded (unit test
            // without setForTesting, or too-early access). Pass through.
            return new Result(code, Collections.<Correction>emptyList(),
                              Collections.<Rejection>emptyList());
        }

        List<Correction> corrections = new ArrayList<Correction>();
        List<Rejection> rejections = new ArrayList<Rejection>();
        StringBuilder patched = new StringBuilder(code.length());
        Matcher m = RUN_CALL.matcher(code);
        int last = 0;
        while (m.find()) {
            String used = m.group(1);
            patched.append(code, last, m.start(1));
            String replacement = used;

            if (!reg.exists(used)) {
                List<MenuCommandRegistry.Match> top = reg.findClosest(used, SUGGEST_TOP_N);
                double best = top.isEmpty() ? 0.0 : top.get(0).score();
                double second = top.size() > 1 ? top.get(1).score() : 0.0;
                if (best >= AUTO_CORRECT_MIN
                        && (best - second) >= AUTO_CORRECT_MARGIN) {
                    // Unique clear winner — auto-correct.
                    replacement = top.get(0).name();
                    corrections.add(new Correction(used, replacement, best));
                } else {
                    rejections.add(new Rejection(used, top, best));
                }
            }

            patched.append(replacement);
            last = m.end(1);
        }
        patched.append(code, last, code.length());
        return new Result(patched.toString(), corrections, rejections);
    }

    /**
     * Build the {@code autocorrected[]} JSON array the execute_macro reply
     * carries when at least one fuzzy correction was applied. Each entry
     * names the original spelling, the canonical replacement, the fuzzy
     * score (rounded to two decimals), and a rule tag so agents can
     * distinguish this from future rewrite rules.
     */
    static JsonArray buildAutocorrectedArray(List<Correction> corrections) {
        JsonArray arr = new JsonArray();
        for (Correction c : corrections) {
            JsonObject o = new JsonObject();
            o.addProperty("from", c.from);
            o.addProperty("to", c.to);
            o.addProperty("score", round2(c.score));
            o.addProperty("rule", "fuzzy_unique_95");
            arr.add(o);
        }
        return arr;
    }

    /**
     * Build the structured {@link ErrorReply} when at least one rejection
     * fires. Uses {@link ErrorReply#CODE_PLUGIN_NOT_FOUND} and embeds the
     * top-3 suggestions as {@code {plugin_name, score}} pairs per the plan.
     * Caller is responsible for attaching it to the final response envelope.
     */
    static ErrorReply buildPluginNotFoundError(List<Rejection> rejections) {
        Rejection first = rejections.get(0);
        String message = "'" + first.used + "' is not a registered Fiji command.";

        StringBuilder hint = new StringBuilder();
        if (first.topMatches.isEmpty()) {
            hint.append("No close matches in the plugin registry. ")
                .append("Use list_commands to browse, or probe_command before retrying.");
        } else {
            hint.append(first.bestScore >= SUGGEST_MIN
                    ? "Closest matches: "
                    : "No strong match. Closest: ");
            for (int i = 0; i < first.topMatches.size(); i++) {
                if (i > 0) hint.append(", ");
                hint.append("'").append(first.topMatches.get(i).name()).append("'");
            }
            hint.append(". Probe with probe_command before using.");
        }

        ErrorReply err = new ErrorReply()
                .code(ErrorReply.CODE_PLUGIN_NOT_FOUND)
                .category(ErrorReply.CAT_NOT_FOUND)
                .retrySafe(false)
                .message(message)
                .recoveryHint(hint.toString());

        for (MenuCommandRegistry.Match match : first.topMatches) {
            JsonObject s = new JsonObject();
            s.addProperty("plugin_name", match.name());
            s.addProperty("score", round2(match.score()));
            err.addSuggested(s);
        }
        return err;
    }

    private static double round2(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
