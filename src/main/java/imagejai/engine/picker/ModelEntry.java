package imagejai.engine.picker;

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
 * <p>Schema reference: docs/multi_provider/02_curation_strategy.md §1.
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
