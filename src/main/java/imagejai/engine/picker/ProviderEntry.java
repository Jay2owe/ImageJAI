package imagejai.engine.picker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable description of a provider in the cascading dropdown.
 *
 * <p>Carries a header status (✓/⚠/✗) plus the curated models that hang off
 * this provider. Auto-discovery (Phase G) will append uncurated entries; the
 * Phase D registry only ships curated rows from the bundled
 * {@code agent/providers/models.yaml}.
 */
public final class ProviderEntry {

    /** Configuration / reachability state shown by the provider header. */
    public enum Status {
        /** Configured and recently reachable. */
        READY,
        /** No credential present — clicking opens the installer. */
        NEEDS_SETUP,
        /** Credential present but the upstream errored on last refresh. */
        UNAVAILABLE
    }

    private final String providerId;
    private final String displayName;
    private final Status status;
    private final String lastError;
    private final List<ModelEntry> models;

    public ProviderEntry(String providerId,
                         String displayName,
                         Status status,
                         String lastError,
                         List<ModelEntry> models) {
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.displayName = displayName == null ? providerId : displayName;
        this.status = status == null ? Status.NEEDS_SETUP : status;
        this.lastError = lastError == null ? "" : lastError;
        List<ModelEntry> copy = models == null
                ? new ArrayList<ModelEntry>()
                : new ArrayList<ModelEntry>(models);
        this.models = Collections.unmodifiableList(copy);
    }

    public String providerId() { return providerId; }
    public String displayName() { return displayName; }
    public Status status() { return status; }
    public String lastError() { return lastError; }
    public List<ModelEntry> models() { return models; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ProviderEntry)) return false;
        return providerId.equals(((ProviderEntry) o).providerId);
    }

    @Override
    public int hashCode() {
        return providerId.hashCode();
    }
}
