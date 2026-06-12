package imagejai.engine.picker;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Lifecycle states for an entry that disappears from a provider's live
 * {@code /models} listing.
 *
 * <p>Per docs/multi_provider/02_curation_strategy.md §3:
 * <ul>
 *   <li><b>Active</b> — present upstream; normal row.</li>
 *   <li><b>Soft-deprecated</b> — first 30 days after upstream drop; visible
 *       with strikethrough.</li>
 *   <li><b>Pinned-deprecated</b> — past day 30 but the curator or user has
 *       pinned the entry; rendered with the red {@code RETIRED} badge.</li>
 *   <li><b>Hidden</b> — past day 30 and unpinned; filtered out before render
 *       (still in {@code models.yaml} so the audit tool reports it).</li>
 * </ul>
 *
 * <p>Phase G implementation guard: only mark deprecated on a <em>successful</em>
 * endpoint call that returns <em>without</em> the model — never on a failed
 * call. Otherwise transient endpoint outages would cascade. The guard is
 * enforced by {@link MergeFunction} which only invokes
 * {@link #markIfMissing(ModelEntry, LocalDate)} on a successful refresh.
 */
public final class SoftDeprecationPolicy {

    /** Soft-deprecation window before a non-pinned entry is hidden, per 02 §3. */
    public static final int WINDOW_DAYS = 30;

    public enum State {
        ACTIVE,
        SOFT_DEPRECATED,
        PINNED_DEPRECATED,
        HIDDEN
    }

    private SoftDeprecationPolicy() {
        // static-only utility
    }

    /**
     * Stamp {@code deprecated_since} onto {@code entry} the first time the
     * upstream listing comes back without it. Idempotent: subsequent successful
     * refreshes that still don't see the model leave the original date alone
     * so the 30-day window keeps counting from the first miss.
     */
    public static ModelEntry markIfMissing(ModelEntry entry, LocalDate today) {
        if (entry == null) {
            return null;
        }
        if (entry.deprecatedSince() != null) {
            return entry;
        }
        return entry.withDeprecatedSince(today);
    }

    /**
     * If a model that was marked deprecated returns upstream, clear the marker
     * and refresh {@code last_verified}.
     */
    public static ModelEntry clearAndRefresh(ModelEntry entry, LocalDate today) {
        if (entry == null) {
            return null;
        }
        ModelEntry refreshed = entry.withLastVerified(today);
        if (refreshed.deprecatedSince() == null) {
            return refreshed;
        }
        return refreshed.withDeprecatedSince(null);
    }

    /** Compute the lifecycle state of an entry given today's date. */
    public static State stateOf(ModelEntry entry, LocalDate today) {
        if (entry == null || entry.deprecatedSince() == null) {
            return State.ACTIVE;
        }
        long days = ChronoUnit.DAYS.between(entry.deprecatedSince(), today);
        if (days <= WINDOW_DAYS) {
            return State.SOFT_DEPRECATED;
        }
        return entry.pinned() ? State.PINNED_DEPRECATED : State.HIDDEN;
    }

    /** True iff the entry should be filtered out of the dropdown. */
    public static boolean isHidden(ModelEntry entry, LocalDate today) {
        return stateOf(entry, today) == State.HIDDEN;
    }
}
