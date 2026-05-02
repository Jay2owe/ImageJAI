package imagejai.engine.picker;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Joins curated entries with live {@code /models} results and (optionally)
 * user-local overrides into the dropdown's runtime list.
 *
 * <p>Conflict resolution per docs/multi_provider/02_curation_strategy.md §2:
 * <ul>
 *   <li>{@code context_window} — upstream wins (provider knows their own limits).</li>
 *   <li>{@code display_name}, {@code description}, {@code tier},
 *       {@code tool_call_reliability}, {@code notes} — curator wins
 *       (upstream payloads don't carry these consistently).</li>
 *   <li>{@code pinned} — curator default, overridden by user-local file.</li>
 * </ul>
 *
 * <p>Soft-deprecation is applied here on missed-curated rows, but only when the
 * upstream listing is treated as authoritative — see {@link LiveResult}.
 */
public final class MergeFunction {

    /**
     * One provider's outcome from the discovery pass.
     *
     * <p>{@link #successful} distinguishes "provider returned an empty list,
     * so we know our curated entries are missing" from "provider returned an
     * error, so we should not mark anything deprecated" — fix for risk #10 in
     * docs/multi_provider/07_implementation_plan.md §4.
     */
    public static final class LiveResult {
        private final boolean successful;
        private final Set<String> modelIds;
        private final Map<String, Map<String, Object>> meta;

        public LiveResult(boolean successful,
                          Set<String> modelIds,
                          Map<String, Map<String, Object>> meta) {
            this.successful = successful;
            this.modelIds = modelIds == null
                    ? Collections.emptySet()
                    : Collections.unmodifiableSet(new LinkedHashSet<String>(modelIds));
            this.meta = meta == null
                    ? Collections.emptyMap()
                    : Collections.unmodifiableMap(meta);
        }

        public boolean successful() { return successful; }
        public Set<String> modelIds() { return modelIds; }
        public Map<String, Map<String, Object>> meta() { return meta; }

        public static LiveResult success(Set<String> ids) {
            return new LiveResult(true, ids, Collections.<String, Map<String, Object>>emptyMap());
        }

        public static LiveResult failure() {
            return new LiveResult(false,
                    Collections.<String>emptySet(),
                    Collections.<String, Map<String, Object>>emptyMap());
        }
    }

    private MergeFunction() {
        // static-only utility
    }

    /**
     * Merge curated × live × user-local for the given canonical providers.
     *
     * @param curated   bundled curated catalogue (immutable input)
     * @param live      one entry per provider; failed providers may be omitted
     *                  or pass a {@link LiveResult#failure()} to mean "do not
     *                  mark deprecated, do not synthesise stubs".
     * @param overrides user-local pin / hide overrides keyed by
     *                  {@code provider + " " + model_id}; pass an empty map
     *                  when no user file exists.
     * @param today     dependency-injected for tests; production passes
     *                  {@code LocalDate.now()}.
     * @return immutable merged list, ready to be grouped by provider.
     */
    public static List<ModelEntry> merge(List<ModelEntry> curated,
                                         Map<String, LiveResult> live,
                                         Map<String, ModelsLocalLoader.Override> overrides,
                                         LocalDate today) {
        if (curated == null) curated = Collections.emptyList();
        if (live == null) live = Collections.emptyMap();
        if (overrides == null) overrides = Collections.emptyMap();

        Map<String, ModelEntry> byKey = new LinkedHashMap<String, ModelEntry>();
        for (ModelEntry e : curated) {
            byKey.put(key(e.providerId(), e.modelId()), e);
        }

        List<ModelEntry> out = new ArrayList<ModelEntry>();
        Set<String> renderedKeys = new LinkedHashSet<String>();

        for (ModelEntry curatedEntry : curated) {
            String pid = curatedEntry.providerId();
            String mid = curatedEntry.modelId();
            String k = key(pid, mid);
            renderedKeys.add(k);
            LiveResult result = live.get(pid);
            ModelEntry merged = curatedEntry;

            if (result == null || !result.successful()) {
                // No upstream signal — leave deprecation flag untouched.
            } else if (result.modelIds().contains(mid)) {
                merged = SoftDeprecationPolicy.clearAndRefresh(merged, today);
                Map<String, Object> meta = result.meta().get(mid);
                if (meta != null) {
                    Object cw = meta.get("context_length");
                    if (cw instanceof Number) {
                        int upstream = ((Number) cw).intValue();
                        if (upstream > 0 && upstream != merged.contextWindow()) {
                            merged = merged.withContextWindow(upstream);
                        }
                    }
                }
            } else {
                merged = SoftDeprecationPolicy.markIfMissing(merged, today);
            }

            ModelsLocalLoader.Override ovr = overrides.get(k);
            if (ovr != null && ovr.pinned() != null) {
                merged = merged.withPinned(ovr.pinned());
            }
            out.add(merged);
        }

        // Synthesise uncurated stubs for upstream-only entries.
        for (Map.Entry<String, LiveResult> entry : live.entrySet()) {
            String providerId = entry.getKey();
            LiveResult result = entry.getValue();
            if (!result.successful()) {
                continue;
            }
            for (String modelId : result.modelIds()) {
                String k = key(providerId, modelId);
                if (renderedKeys.contains(k)) {
                    continue;
                }
                ModelEntry stub = synthesiseStub(providerId, modelId,
                        result.meta().get(modelId), today);
                ModelsLocalLoader.Override ovr = overrides.get(k);
                if (ovr != null && ovr.pinned() != null) {
                    stub = stub.withPinned(ovr.pinned());
                }
                out.add(stub);
                renderedKeys.add(k);
            }
        }

        return Collections.unmodifiableList(out);
    }

    /**
     * Filter the merged list per the user-local visibility rules:
     * pinned-deprecated stays, unpinned-soft-deprecated past 30 days drops,
     * user-hidden rows drop unless pinned.
     */
    public static List<ModelEntry> applyVisibility(List<ModelEntry> merged,
                                                   Map<String, ModelsLocalLoader.Override> overrides,
                                                   LocalDate today) {
        if (merged == null) return Collections.emptyList();
        if (overrides == null) overrides = Collections.emptyMap();
        List<ModelEntry> out = new ArrayList<ModelEntry>(merged.size());
        for (ModelEntry e : merged) {
            String k = key(e.providerId(), e.modelId());
            ModelsLocalLoader.Override ovr = overrides.get(k);
            boolean userHidden = ovr != null
                    && Boolean.TRUE.equals(ovr.hidden())
                    && !e.pinned();
            if (userHidden) {
                continue;
            }
            if (SoftDeprecationPolicy.isHidden(e, today)) {
                continue;
            }
            out.add(e);
        }
        return Collections.unmodifiableList(out);
    }

    static ModelEntry synthesiseStub(String providerId,
                                     String modelId,
                                     Map<String, Object> meta,
                                     LocalDate today) {
        int contextWindow = 0;
        boolean visionInferred = false;
        if (meta != null) {
            Object cw = meta.get("context_length");
            if (cw instanceof Number) {
                contextWindow = ((Number) cw).intValue();
            }
            Object modality = meta.get("modality");
            if (modality != null
                    && modality.toString().toLowerCase().contains("image")) {
                visionInferred = true;
            }
        }
        if (!visionInferred) {
            visionInferred = inferVisionFromName(modelId);
        }
        return new ModelEntry(
                providerId,
                modelId,
                modelId,
                "Uncurated — pricing and capabilities unknown.",
                ModelEntry.Tier.UNCURATED,
                contextWindow,
                visionInferred,
                ModelEntry.Reliability.LOW,
                false,
                false,
                "",
                today,
                null,
                null);
    }

    private static boolean inferVisionFromName(String modelId) {
        String s = modelId.toLowerCase();
        return s.contains("vision")
                || s.contains("vl")
                || s.contains("gpt-4o")
                || s.contains("gpt-5")
                || s.contains("gemma3")
                || s.contains("gemma4")
                || s.contains("pixtral")
                || s.contains("llama-4-scout")
                || s.matches(".*claude-(opus|sonnet|haiku)-[4-9].*")
                || s.matches(".*gemini-[2-9].*");
    }

    static String key(String providerId, String modelId) {
        return providerId + " " + modelId;
    }
}
