package imagejai.engine.picker;

import ij.IJ;
import imagejai.engine.AgentLauncher;
import imagejai.engine.AgentSession;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skeleton for the native Anthropic / Gemini transport. Phase C brings the
 * caching + server-side tool features online; Phase D leaves this dormant so
 * the dropdown can route to it once the dependency is satisfied.
 *
 * <p>Phase C plumbing: when called with a {@link ModelEntry} that carries a
 * {@code native_features} block in {@code agent/providers/models.yaml},
 * this launcher turns those entries into environment variables the Python
 * wrapper can pick up via {@code os.environ}. Defaults are absent — only
 * features explicitly enabled in YAML appear in the env. The mapping:
 *
 * <ul>
 *   <li>{@code prompt_caching} → {@code IMAGEJAI_NATIVE_PROMPT_CACHING}</li>
 *   <li>{@code thinking_budget} → {@code IMAGEJAI_NATIVE_THINKING_BUDGET}</li>
 *   <li>{@code server_tools} → {@code IMAGEJAI_NATIVE_SERVER_TOOLS}
 *       (comma-joined)</li>
 *   <li>{@code google_search} → {@code IMAGEJAI_NATIVE_GOOGLE_SEARCH}</li>
 *   <li>{@code code_execution} → {@code IMAGEJAI_NATIVE_CODE_EXECUTION}</li>
 * </ul>
 */
public class NativeAgentLauncher {

    public static final String ENV_PREFIX = "IMAGEJAI_NATIVE_";

    private final AgentLauncher cliLauncher;

    public NativeAgentLauncher() {
        this(null);
    }

    public NativeAgentLauncher(AgentLauncher cliLauncher) {
        this.cliLauncher = cliLauncher;
    }

    public AgentSession launch(ModelEntry entry, AgentLauncher.Mode mode) {
        return launch(entry, mode, null);
    }

    public AgentSession launch(ModelEntry entry, AgentLauncher.Mode mode,
                               Map<String, String> commonEnv) {
        // Native feature flags (prompt caching, server tools) flow to the
        // Python wrapper as IMAGEJAI_NATIVE_* env vars; the agent loop reads
        // them in agent.providers.agent_cli._native_opts(). commonEnv carries
        // cross-transport vars (proxy port, budget ceiling) from the panel.
        Map<String, String> env = new LinkedHashMap<String, String>(nativeFeatureEnv(entry));
        if (commonEnv != null) {
            env.putAll(commonEnv);
        }
        return ProviderAgentLaunch.launch(cliLauncher, entry, mode, env);
    }

    /**
     * Convert a {@link ModelEntry}'s {@code native_features} map into the env
     * vars the Python provider wrapper consumes. Returns an empty map for
     * non-native providers or models without a {@code native_features} block.
     */
    public static Map<String, String> nativeFeatureEnv(ModelEntry entry) {
        if (entry == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> features = entry.nativeFeatures();
        if (features == null || features.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> env = new LinkedHashMap<String, String>();
        for (Map.Entry<String, Object> f : features.entrySet()) {
            String key = f.getKey();
            Object value = f.getValue();
            if (key == null || value == null) {
                continue;
            }
            String envName = ENV_PREFIX + key.trim().toUpperCase().replace('-', '_');
            env.put(envName, encodeFeatureValue(value));
        }
        return Collections.unmodifiableMap(env);
    }

    private static String encodeFeatureValue(Object value) {
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue() ? "true" : "false";
        }
        if (value instanceof Collection) {
            List<String> parts = new ArrayList<String>();
            for (Object item : (Collection<?>) value) {
                if (item != null) {
                    String text = item.toString().trim();
                    if (!text.isEmpty()) {
                        parts.add(text);
                    }
                }
            }
            return String.join(",", parts);
        }
        return value.toString();
    }
}
