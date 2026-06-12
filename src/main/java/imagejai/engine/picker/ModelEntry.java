package imagejai.engine.picker;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable description of one provider/model row in the cascading dropdown.
 * Keyed by (providerId, modelId); equality matches that pair.
 *
 * <p>Mapped from a single entry in {@code agent/providers/models.yaml}. Display
 * fields (display_name, description, tier, pinned, vision_capable) are read
 * here; context-loader fields used by the Python loader are intentionally
 * ignored — the file is the unified source of truth and each consumer only
 * deserialises what it needs.
 *
 * <p>Phase C adds {@link #nativeFeatures()} — an opaque map mirroring the
 * optional {@code native_features} block on Anthropic / Gemini entries. Carries
 * keys like {@code prompt_caching}, {@code thinking_budget},
 * {@code server_tools}, {@code google_search}, {@code code_execution}. Forwarded
 * verbatim to the Python wrapper by {@link NativeAgentLauncher}; ignored when
 * absent or non-native.
 *
 * <p>Schema reference: docs/multi_provider/02_curation_strategy.md §1 +
 * docs/multi_provider/07_implementation_plan.md §C.
 */
public final class ModelEntry {

    /** Curated tier classification — drives badge colour. */
    public enum Tier {
        FREE("free"),
        FREE_WITH_LIMITS("free-with-limits"),
        PAID("paid"),
        REQUIRES_SUBSCRIPTION("requires-subscription"),
        UNCURATED("uncurated");

        private final String yamlValue;

        Tier(String yamlValue) {
            this.yamlValue = yamlValue;
        }

        public String yamlValue() {
            return yamlValue;
        }

        public static Tier fromYaml(String value) {
            if (value == null) {
                return UNCURATED;
            }
            String trimmed = value.trim().toLowerCase();
            for (Tier t : values()) {
                if (t.yamlValue.equals(trimmed)) {
                    return t;
                }
            }
            return UNCURATED;
        }
    }

    /** Tool-call reliability — drives weak-model warning. */
    public enum Reliability {
        HIGH, MEDIUM, LOW;

        public static Reliability fromYaml(String value) {
            if (value == null) {
                return LOW;
            }
            switch (value.trim().toLowerCase()) {
                case "high": return HIGH;
                case "medium": return MEDIUM;
                case "low": return LOW;
                default: return LOW;
            }
        }
    }

    private final String providerId;
    private final String modelId;
    private final String displayName;
    private final String description;
    private final Tier tier;
    private final int contextWindow;
    private final boolean visionCapable;
    private final Reliability toolCallReliability;
    private final boolean pinned;
    private final boolean curated;
    private final String notes;
    private final LocalDate lastVerified;
    private final LocalDate deprecatedSince;
    private final String replacement;
    private final Map<String, Object> nativeFeatures;

    public ModelEntry(String providerId,
                      String modelId,
                      String displayName,
                      String description,
                      Tier tier,
                      int contextWindow,
                      boolean visionCapable,
                      Reliability toolCallReliability,
                      boolean pinned,
                      boolean curated,
                      String notes) {
        this(providerId, modelId, displayName, description, tier, contextWindow,
                visionCapable, toolCallReliability, pinned, curated, notes,
                null, null, null, null);
    }

    public ModelEntry(String providerId,
                      String modelId,
                      String displayName,
                      String description,
                      Tier tier,
                      int contextWindow,
                      boolean visionCapable,
                      Reliability toolCallReliability,
                      boolean pinned,
                      boolean curated,
                      String notes,
                      LocalDate lastVerified,
                      LocalDate deprecatedSince,
                      String replacement) {
        this(providerId, modelId, displayName, description, tier, contextWindow,
                visionCapable, toolCallReliability, pinned, curated, notes,
                lastVerified, deprecatedSince, replacement, null);
    }

    public ModelEntry(String providerId,
                      String modelId,
                      String displayName,
                      String description,
                      Tier tier,
                      int contextWindow,
                      boolean visionCapable,
                      Reliability toolCallReliability,
                      boolean pinned,
                      boolean curated,
                      String notes,
                      LocalDate lastVerified,
                      LocalDate deprecatedSince,
                      String replacement,
                      Map<String, Object> nativeFeatures) {
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.modelId = Objects.requireNonNull(modelId, "modelId");
        this.displayName = displayName == null ? modelId : displayName;
        this.description = description == null ? "" : description;
        this.tier = tier == null ? Tier.UNCURATED : tier;
        this.contextWindow = contextWindow;
        this.visionCapable = visionCapable;
        this.toolCallReliability = toolCallReliability == null
                ? Reliability.LOW
                : toolCallReliability;
        this.pinned = pinned;
        this.curated = curated;
        this.notes = notes == null ? "" : notes;
        this.lastVerified = lastVerified;
        this.deprecatedSince = deprecatedSince;
        this.replacement = replacement;
        this.nativeFeatures = nativeFeatures == null || nativeFeatures.isEmpty()
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(nativeFeatures));
    }

    public String providerId() { return providerId; }
    public String modelId() { return modelId; }
    public String displayName() { return displayName; }
    public String description() { return description; }
    public Tier tier() { return tier; }
    public int contextWindow() { return contextWindow; }
    public boolean visionCapable() { return visionCapable; }
    public Reliability toolCallReliability() { return toolCallReliability; }
    public boolean pinned() { return pinned; }
    public boolean curated() { return curated; }
    public String notes() { return notes; }
    public LocalDate lastVerified() { return lastVerified; }
    public LocalDate deprecatedSince() { return deprecatedSince; }
    public String replacement() { return replacement; }
    public Map<String, Object> nativeFeatures() { return nativeFeatures; }

    /** Return a copy with a different pinned flag — used when applying user overrides. */
    public ModelEntry withPinned(boolean newPinned) {
        return new ModelEntry(providerId, modelId, displayName, description, tier,
                contextWindow, visionCapable, toolCallReliability, newPinned,
                curated, notes, lastVerified, deprecatedSince, replacement, nativeFeatures);
    }

    /** Return a copy with the given last-verified date — used after a successful refresh. */
    public ModelEntry withLastVerified(LocalDate when) {
        return new ModelEntry(providerId, modelId, displayName, description, tier,
                contextWindow, visionCapable, toolCallReliability, pinned,
                curated, notes, when, deprecatedSince, replacement, nativeFeatures);
    }

    /** Return a copy with deprecation cleared — used when an entry comes back upstream. */
    public ModelEntry withDeprecatedSince(LocalDate when) {
        return new ModelEntry(providerId, modelId, displayName, description, tier,
                contextWindow, visionCapable, toolCallReliability, pinned,
                curated, notes, lastVerified, when, replacement, nativeFeatures);
    }

    /** Return a copy with overridden context window — used when upstream knows better than curator. */
    public ModelEntry withContextWindow(int newContextWindow) {
        return new ModelEntry(providerId, modelId, displayName, description, tier,
                newContextWindow, visionCapable, toolCallReliability, pinned,
                curated, notes, lastVerified, deprecatedSince, replacement, nativeFeatures);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ModelEntry)) return false;
        ModelEntry other = (ModelEntry) o;
        return providerId.equals(other.providerId) && modelId.equals(other.modelId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(providerId, modelId);
    }

    @Override
    public String toString() {
        return providerId + ":" + modelId;
    }
}
