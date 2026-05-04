package imagejai.engine.picker;

import javax.swing.SwingWorker;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

/**
 * Phase G: groups merged {@link ModelEntry}s by provider, after running the
 * curated catalogue through the {@link MergeFunction} layer (curated × user
 * overrides × live discovery).
 *
 * <p>Construction surfaces:
 * <ul>
 *   <li>{@link #loadBundled()} — production path; reads bundled
 *       {@code models.yaml}, applies user overrides from
 *       {@code %APPDATA%/imagejai/models_local.yaml}, no live discovery.</li>
 *   <li>{@link #loadFromPath(Path)} — explicit yaml path; tests + audit tool.</li>
 *   <li>{@link #fromMerged(List, LocalDate)} — Phase G's hot path: merge layer
 *       hands the registry an already-merged list to render.</li>
 * </ul>
 *
 * <p>Always loaded with SnakeYAML's {@code SafeConstructor} (risk #16 in
 * docs/multi_provider/07_implementation_plan.md §4).
 */
public final class ProviderRegistry {

    /** Canonical hyphenated provider keys — must match agent/providers/router.py. */
    public static final List<String> CANONICAL_PROVIDERS = Collections.unmodifiableList(
            Arrays.asList(
                    "anthropic",
                    "cerebras",
                    "deepseek",
                    "gemini",
                    "github-models",
                    "groq",
                    "huggingface",
                    "mistral",
                    "ollama",
                    "ollama-cloud",
                    "openai",
                    "openrouter",
                    "perplexity",
                    "together",
                    "xai"));

    private static final Map<String, String> DISPLAY_NAMES;
    static {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("anthropic", "Anthropic");
        m.put("cerebras", "Cerebras");
        m.put("deepseek", "DeepSeek");
        m.put("gemini", "Gemini");
        m.put("github-models", "GitHub Models");
        m.put("groq", "Groq");
        m.put("huggingface", "HuggingFace");
        m.put("mistral", "Mistral");
        m.put("ollama", "Ollama (local)");
        m.put("ollama-cloud", "Ollama Cloud");
        m.put("openai", "OpenAI");
        m.put("openrouter", "OpenRouter");
        m.put("perplexity", "Perplexity");
        m.put("together", "Together AI");
        m.put("xai", "xAI");
        DISPLAY_NAMES = Collections.unmodifiableMap(m);
    }

    /** Resource path used when loading from the bundled jar. */
    public static final String BUNDLED_RESOURCE = ModelsYamlLoader.BUNDLED_RESOURCE;

    private final Map<String, ProviderEntry> providersByKey;
    private final Map<String, ModelEntry> entriesByKey;

    private ProviderRegistry(Map<String, ProviderEntry> providersByKey,
                             Map<String, ModelEntry> entriesByKey) {
        this.providersByKey = providersByKey;
        this.entriesByKey = entriesByKey;
    }

    public Iterable<ProviderEntry> providers() {
        return providersByKey.values();
    }

    public List<ProviderEntry> providersList() {
        return new ArrayList<ProviderEntry>(providersByKey.values());
    }

    public ProviderEntry provider(String providerId) {
        return providersByKey.get(providerId);
    }

    public ModelEntry lookup(String providerId, String modelId) {
        if (providerId == null || modelId == null) {
            return null;
        }
        return entriesByKey.get(providerId + " " + modelId);
    }

    public int modelCount() {
        return entriesByKey.size();
    }

    /** Load the bundled {@code models.yaml} from the classpath. */
    public static ProviderRegistry loadBundled() {
        try (InputStream in = ProviderRegistry.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (in == null) {
                return empty();
            }
            return fromMerged(ModelsYamlLoader.loadFromStream(in), LocalDate.now());
        } catch (IOException ex) {
            return empty();
        }
    }

    /** Load from an explicit filesystem path — used by tests and fallback. */
    public static ProviderRegistry loadFromPath(Path yamlPath) throws IOException {
        return fromMerged(ModelsYamlLoader.loadFromPath(yamlPath), LocalDate.now());
    }

    /** Empty registry (no models, all canonical providers present as NEEDS_SETUP). */
    public static ProviderRegistry empty() {
        return fromMerged(Collections.<ModelEntry>emptyList(), LocalDate.now());
    }

    /**
     * Group an already-merged list of entries by canonical provider, dropping
     * any entry whose soft-deprecation lifecycle has hit {@code HIDDEN}. Phase G
     * callers run the merge in {@link MergeFunction#merge}, then post-filter
     * with {@link MergeFunction#applyVisibility} (or call this directly with
     * the unfiltered list to skip user-hidden — production wires both).
     */
    public static ProviderRegistry fromMerged(List<ModelEntry> merged, LocalDate today) {
        if (merged == null) merged = Collections.emptyList();
        if (today == null) today = LocalDate.now();
        return build(merged, today);
    }

    /**
     * Convenience for the bootstrap path: read curated, read user overrides,
     * run merge with live=empty (Phase G surface; Phase G's
     * {@link ProviderDiscovery} feeds {@link #fromMerged} directly).
     */
    public static ProviderRegistry loadWithUserOverrides(Path curatedYaml,
                                                          ModelsLocalLoader localLoader) throws IOException {
        List<ModelEntry> curated = ModelsYamlLoader.loadFromPath(curatedYaml);
        Map<String, ModelsLocalLoader.Override> overrides = localLoader == null
                ? Collections.<String, ModelsLocalLoader.Override>emptyMap()
                : localLoader.loadAsMap();
        LocalDate today = LocalDate.now();
        List<ModelEntry> merged = MergeFunction.merge(curated,
                Collections.<String, MergeFunction.LiveResult>emptyMap(), overrides, today);
        List<ModelEntry> visible = MergeFunction.applyVisibility(merged, overrides, today);
        return fromMerged(visible, today);
    }

    private static ProviderRegistry build(List<ModelEntry> entries, LocalDate today) {
        Map<String, List<ModelEntry>> byProvider = new LinkedHashMap<String, List<ModelEntry>>();
        for (String key : CANONICAL_PROVIDERS) {
            byProvider.put(key, new ArrayList<ModelEntry>());
        }
        Set<String> known = new java.util.HashSet<String>(CANONICAL_PROVIDERS);
        Map<String, ModelEntry> entriesByKey = new LinkedHashMap<String, ModelEntry>();
        for (ModelEntry e : entries) {
            if (e == null || !known.contains(e.providerId())) {
                continue;
            }
            byProvider.get(e.providerId()).add(e);
            entriesByKey.put(e.providerId() + " " + e.modelId(), e);
        }

        Map<String, ProviderEntry> providersByKey = new LinkedHashMap<String, ProviderEntry>();
        for (Map.Entry<String, List<ModelEntry>> p : byProvider.entrySet()) {
            String key = p.getKey();
            ProviderEntry.Status status;
            if ("ollama".equals(key)) {
                status = ProviderEntry.Status.READY;
            } else if (p.getValue().isEmpty()) {
                status = ProviderEntry.Status.NEEDS_SETUP;
            } else {
                status = ProviderEntry.Status.NEEDS_SETUP;
            }
            providersByKey.put(key, new ProviderEntry(
                    key, DISPLAY_NAMES.get(key), status, "", p.getValue()));
        }
        return new ProviderRegistry(
                Collections.unmodifiableMap(providersByKey),
                Collections.unmodifiableMap(entriesByKey));
    }

    /**
     * Convenience for the cache loader: convert the {@link ModelsCache} TTL
     * check against the registry's "now". Production code passes
     * {@code Instant.now()}.
     */
    public static LocalDate today(Instant now) {
        return now.atZone(ZoneId.systemDefault()).toLocalDate();
    }

    /**
     * Returns a new registry where the named provider's {@link ProviderEntry}
     * has been replaced with {@code newEntry}. All other providers and their
     * model entries remain identical. Callers (typically {@link ModelPickerButton}
     * after a successful {@link RefreshWorker} fetch) use this to swap a single
     * provider's models without rebuilding the whole popup — the flicker-risk
     * mitigation called out in docs/multi_provider/05_ui_design.md §3.9 and
     * Phase D acceptance.
     *
     * <p>Unknown {@code providerId} (not in {@link #CANONICAL_PROVIDERS}) returns
     * {@code this} unchanged so callers don't have to pre-validate.
     */
    public ProviderRegistry refreshProvider(String providerId, ProviderEntry newEntry) {
        if (providerId == null || newEntry == null) {
            return this;
        }
        if (!providersByKey.containsKey(providerId)) {
            return this;
        }
        Map<String, ProviderEntry> newProviders =
                new LinkedHashMap<String, ProviderEntry>(providersByKey);
        newProviders.put(providerId, newEntry);

        Map<String, ModelEntry> newEntries = new LinkedHashMap<String, ModelEntry>();
        for (Map.Entry<String, ModelEntry> e : entriesByKey.entrySet()) {
            // Drop the old provider's keys; we'll add the new ones below.
            if (!providerId.equals(e.getValue().providerId())) {
                newEntries.put(e.getKey(), e.getValue());
            }
        }
        for (ModelEntry e : newEntry.models()) {
            if (e == null || !providerId.equals(e.providerId())) {
                continue;
            }
            newEntries.put(e.providerId() + " " + e.modelId(), e);
        }
        return new ProviderRegistry(
                Collections.unmodifiableMap(newProviders),
                Collections.unmodifiableMap(newEntries));
    }

    /**
     * SwingWorker that fetches one provider's {@link ProviderEntry} off the EDT
     * and hands the result back to a caller-supplied applier on
     * {@link #done()} so {@link ModelPickerButton} can swap a single
     * {@link ProviderMenu}'s children without rebuilding the whole popup.
     *
     * <p>Phase D acceptance: per-provider refresh; whole-popup rebuild avoided.
     * The actual fetch logic is injected — Phase G's
     * {@link ProviderDiscovery} owns the network calls; Phase D ships this
     * dispatch shell so the picker has a stable hook to call.
     */
    public static final class RefreshWorker extends SwingWorker<ProviderEntry, Void> {

        /** Callback invoked on the EDT once {@link #doInBackground} returns. */
        public interface Applier {
            void apply(String providerId, ProviderEntry newEntry, Throwable error);
        }

        private final String providerId;
        private final Callable<ProviderEntry> fetcher;
        private final Applier applier;

        public RefreshWorker(String providerId,
                             Callable<ProviderEntry> fetcher,
                             Applier applier) {
            this.providerId = providerId;
            this.fetcher = fetcher;
            this.applier = applier;
        }

        public String providerId() {
            return providerId;
        }

        @Override
        protected ProviderEntry doInBackground() throws Exception {
            return fetcher == null ? null : fetcher.call();
        }

        @Override
        protected void done() {
            if (applier == null) {
                return;
            }
            try {
                applier.apply(providerId, get(), null);
            } catch (Exception ex) {
                applier.apply(providerId, null, ex);
            }
        }
    }
}
