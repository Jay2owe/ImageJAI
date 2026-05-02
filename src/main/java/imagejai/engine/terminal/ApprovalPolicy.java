package imagejai.engine.terminal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import ij.IJ;
import imagejai.engine.AgentLauncher;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Per-agent approval policy for terminal prompts.
 */
public final class ApprovalPolicy {
    public enum Decision {
        AUTO_CONFIRM,
        ESCALATE,
        PENDING
    }

    private static final String DEFAULT_ID = "default";

    private final String agentId;
    private final List<Pattern> autoConfirm;
    private final List<Pattern> alwaysEscalate;
    private final List<Pattern> pending;

    private ApprovalPolicy(String agentId,
                           List<Pattern> autoConfirm,
                           List<Pattern> alwaysEscalate,
                           List<Pattern> pending) {
        this.agentId = agentId;
        this.autoConfirm = Collections.unmodifiableList(autoConfirm);
        this.alwaysEscalate = Collections.unmodifiableList(alwaysEscalate);
        this.pending = Collections.unmodifiableList(pending);
    }

    public static ApprovalPolicy loadForAgent(AgentLauncher.AgentInfo info) {
        String id = AgentRegistry.agentId(info);
        ApprovalPolicy policy = load(id);
        if (policy != null) {
            return policy;
        }
        ApprovalPolicy fallback = load(DEFAULT_ID);
        if (fallback != null) {
            return fallback;
        }
        IJ.log("[ImageJAI-Term] No approval policy found; escalating all prompts");
        return new ApprovalPolicy(DEFAULT_ID, new ArrayList<Pattern>(),
                new ArrayList<Pattern>(), new ArrayList<Pattern>());
    }

    public String agentId() {
        return agentId;
    }

    public Decision decide(String promptText) {
        String prompt = promptText == null ? "" : promptText;
        if (matches(alwaysEscalate, prompt)) {
            return Decision.ESCALATE;
        }
        if (matches(autoConfirm, prompt)) {
            return Decision.AUTO_CONFIRM;
        }
        if (matches(pending, prompt)) {
            return Decision.PENDING;
        }
        return Decision.ESCALATE;
    }

    private static ApprovalPolicy load(String id) {
        String resource = "/agents/" + id + "/approval.json";
        InputStream in = ApprovalPolicy.class.getResourceAsStream(resource);
        if (in == null) {
            return null;
        }

        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            ApprovalPolicy policy = new ApprovalPolicy(id,
                    compileList(root, "auto_confirm"),
                    compileList(root, "always_escalate"),
                    compileList(root, "pending"));
            IJ.log("[ImageJAI-Term] Loaded approval policy: " + resource);
            return policy;
        } catch (Exception e) {
            IJ.log("[ImageJAI-Term] Failed to load approval policy " + resource
                    + "; escalating all prompts: " + e.getMessage());
            return new ApprovalPolicy(id, new ArrayList<Pattern>(),
                    new ArrayList<Pattern>(), new ArrayList<Pattern>());
        }
    }

    private static List<Pattern> compileList(JsonObject root, String key) {
        List<Pattern> patterns = new ArrayList<Pattern>();
        if (root == null || !root.has(key) || !root.get(key).isJsonArray()) {
            return patterns;
        }
        JsonArray array = root.getAsJsonArray(key);
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive()) {
                continue;
            }
            String regex = element.getAsString();
            try {
                patterns.add(Pattern.compile(regex));
            } catch (PatternSyntaxException e) {
                IJ.log("[ImageJAI-Term] Ignoring invalid approval regex in "
                        + key + ": " + e.getMessage());
            }
        }
        return patterns;
    }

    private static boolean matches(List<Pattern> patterns, String text) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

}
