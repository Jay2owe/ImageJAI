package imagejai.ui.picker;

import imagejai.engine.picker.ModelEntry;
import imagejai.engine.picker.ProviderEntry;
import imagejai.engine.picker.ProviderRegistry;
import imagejai.engine.usage.UsageTracker;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fiji-startup tier-change check per docs/multi_provider/06_tier_safety.md §7.
 *
 * <p>Compares each <em>favourite</em> model (per {@link UsageTracker}) against
 * the current registry. Surfaces:
 * <ul>
 *   <li>Tier reclassification (e.g. 🟡 → 🔵).</li>
 *   <li>Price change ≥ 10% (input or output) using
 *       {@link UsageTracker.Record#lastSeenInputUsdPerMtok} as the reference.</li>
 *   <li>Pre-warning between T-7 and T-1 days for any
 *       {@link ScheduledChange#effectiveDate}.</li>
 * </ul>
 */
public final class MainNotificationCheck {

    public enum Severity { LOW, MEDIUM, HIGH }

    public static final class Notification {
        public final String key;        // provider/model_id, or "consolidated:N"
        public final Severity severity;
        public final String title;
        public final String body;

        public Notification(String key, Severity severity, String title, String body) {
            this.key = key;
            this.severity = severity == null ? Severity.LOW : severity;
            this.title = title == null ? "" : title;
            this.body = body == null ? "" : body;
        }

        public static Notification consolidated(int remaining) {
            return new Notification(
                    "consolidated:" + remaining,
                    Severity.MEDIUM,
                    "+" + remaining + " more pricing/tier changes since last launch",
                    "Open the Multi-Provider settings tab to see the full list.");
        }
    }

    /** Future-dated price change parsed from {@code pricing_changes:} in models.yaml. */
    public static final class ScheduledChange {
        public final String providerId;
        public final String modelId;
        public final LocalDate effectiveDate;
        public final Double newInputUsdPerMtok;
        public final Double newOutputUsdPerMtok;
        public final String reason;

        public ScheduledChange(String providerId, String modelId, LocalDate effectiveDate,
                               Double newInputUsdPerMtok, Double newOutputUsdPerMtok,
                               String reason) {
            this.providerId = providerId;
            this.modelId = modelId;
            this.effectiveDate = effectiveDate;
            this.newInputUsdPerMtok = newInputUsdPerMtok;
            this.newOutputUsdPerMtok = newOutputUsdPerMtok;
            this.reason = reason;
        }
    }

    /** Pre-warning window. T-7 inclusive (06 §7.3). */
    public static final long PRE_WARNING_DAYS = 7;

    /** Price-change threshold per 06 §7.3. */
    public static final double PRICE_CHANGE_THRESHOLD = 0.10;

    /** Bare interface so tests can drive a fake registry without YAML. */
    public interface RegistryView {
        ModelEntry lookup(String providerId, String modelId);
    }

    public static List<Notification> run(UsageTracker tracker,
                                         ProviderRegistry registry,
                                         List<ScheduledChange> scheduledChanges,
                                         LocalDate today) {
        return runAgainst(tracker, registry::lookup, scheduledChanges, today);
    }

    public static List<Notification> runAgainst(UsageTracker tracker,
                                                RegistryView registry,
                                                List<ScheduledChange> scheduledChanges,
                                                LocalDate today) {
        List<Notification> out = new ArrayList<>();
        if (tracker == null || registry == null || today == null) {
            return out;
        }
        for (String key : tracker.favourites()) {
            String[] parts = UsageTracker.split(key);
            String providerId = parts[0];
            String modelId = parts[1];
            UsageTracker.Record record = tracker.get(providerId, modelId);
            ModelEntry entry = registry.lookup(providerId, modelId);
            if (record == null || entry == null) {
                continue;
            }

            // Tier change?
            String lastSeenTier = record.lastSeenTier;
            String currentTier = entry.tier() == null ? null : entry.tier().yamlValue();
            if (lastSeenTier != null && currentTier != null && !lastSeenTier.equals(currentTier)) {
                out.add(new Notification(key, Severity.HIGH,
                        entry.displayName() + ": tier changed",
                        "Was " + lastSeenTier + ", now " + currentTier
                                + ". Open the dropdown to confirm before launching."));
                continue;
            }

            // Price change >= 10%?
            Double currentIn = pricingFromEntry(entry, "input");
            Double currentOut = pricingFromEntry(entry, "output");
            Double seenIn = record.lastSeenInputUsdPerMtok;
            Double seenOut = record.lastSeenOutputUsdPerMtok;
            boolean inChanged = priceCrossesThreshold(seenIn, currentIn);
            boolean outChanged = priceCrossesThreshold(seenOut, currentOut);
            if (inChanged || outChanged) {
                Severity severity = priceWentUp(seenIn, currentIn) || priceWentUp(seenOut, currentOut)
                        ? Severity.HIGH : Severity.LOW;
                out.add(new Notification(key, severity,
                        entry.displayName() + ": price changed",
                        priceDeltaSentence(seenIn, currentIn, seenOut, currentOut)));
            }
        }

        if (scheduledChanges != null) {
            for (ScheduledChange change : scheduledChanges) {
                if (change.effectiveDate == null) continue;
                long daysToChange = today.until(change.effectiveDate).getDays();
                if (daysToChange < 0 || daysToChange > PRE_WARNING_DAYS) continue;
                if (!tracker.isFavourite(change.providerId, change.modelId)) continue;
                String key = UsageTracker.key(change.providerId, change.modelId);
                String body = (change.reason == null || change.reason.isEmpty()
                        ? "Pricing change scheduled "
                        : change.reason + " — pricing change scheduled ")
                        + "for " + change.effectiveDate
                        + " (T-" + daysToChange + " days).";
                out.add(new Notification(key, Severity.MEDIUM,
                        change.providerId + "/" + change.modelId
                                + ": pricing change on " + change.effectiveDate,
                        body));
            }
        }

        return out;
    }

    /** Apply per-machine dismissals — caller owns durable storage. */
    public static List<Notification> filterDismissed(List<Notification> in,
                                                    java.util.Set<String> dismissed) {
        if (in == null) return new ArrayList<>();
        if (dismissed == null || dismissed.isEmpty()) return in;
        List<Notification> out = new ArrayList<>();
        for (Notification n : in) {
            if (!dismissed.contains(n.key)) out.add(n);
        }
        return out;
    }

    private static boolean priceCrossesThreshold(Double previous, Double current) {
        if (previous == null || current == null || previous <= 0) return false;
        double delta = Math.abs(current - previous) / previous;
        return delta >= PRICE_CHANGE_THRESHOLD;
    }

    private static boolean priceWentUp(Double previous, Double current) {
        if (previous == null || current == null) return false;
        return current > previous;
    }

    private static String priceDeltaSentence(Double seenIn, Double curIn,
                                             Double seenOut, Double curOut) {
        StringBuilder sb = new StringBuilder();
        if (seenIn != null && curIn != null) {
            sb.append("Input was $").append(seenIn).append("/M, now $").append(curIn).append("/M. ");
        }
        if (seenOut != null && curOut != null) {
            sb.append("Output was $").append(seenOut).append("/M, now $").append(curOut).append("/M.");
        }
        return sb.toString().trim();
    }

    @SuppressWarnings("unchecked")
    private static Double pricingFromEntry(ModelEntry entry, String which) {
        // Phase H reads the optional `pricing` block off `native_features` only
        // because the existing ModelEntry deserialiser doesn't surface it on
        // the public API. The block is forwarded under the same map.
        Map<String, Object> features = entry.nativeFeatures();
        if (features == null) return null;
        Object pricing = features.get("pricing");
        if (!(pricing instanceof Map)) return null;
        Map<String, Object> map = (Map<String, Object>) pricing;
        Object value = map.get(which.equals("input") ? "input_usd_per_mtok" : "output_usd_per_mtok");
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Convenience: parse an ISO date or return null on failure. */
    public static LocalDate parseDate(String iso) {
        if (iso == null || iso.isEmpty()) return null;
        try {
            return LocalDate.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    /** Pull all scheduled changes from a registry's nativeFeatures.pricing_changes block. */
    @SuppressWarnings("unchecked")
    public static List<ScheduledChange> scheduledChangesFor(ProviderRegistry registry) {
        List<ScheduledChange> out = new ArrayList<>();
        if (registry == null) return out;
        for (ProviderEntry provider : registry.providers()) {
            for (ModelEntry model : provider.models()) {
                Map<String, Object> features = model.nativeFeatures();
                if (features == null) continue;
                Object changes = features.get("pricing_changes");
                if (!(changes instanceof List)) continue;
                for (Object obj : (List<Object>) changes) {
                    if (!(obj instanceof Map)) continue;
                    Map<String, Object> m = (Map<String, Object>) obj;
                    LocalDate date = parseDate(asString(m.get("effective_date")));
                    if (date == null) continue;
                    Double in = asDouble(m.get("input_usd_per_mtok"));
                    Double outv = asDouble(m.get("output_usd_per_mtok"));
                    String reason = asString(m.get("reason"));
                    out.add(new ScheduledChange(model.providerId(), model.modelId(),
                            date, in, outv, reason));
                }
            }
        }
        return out;
    }

    private static String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private static Double asDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(value.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
