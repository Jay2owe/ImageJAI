package imagejai.engine.budget;

import imagejai.engine.CostHeaderListener;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Java-side budget tracker. Subscribes to {@link CostHeaderListener}
 * (Phase A's LiteLLM cost-header pipe) and emits a structured pause signal
 * once the running total breaches the configured ceiling.
 *
 * <p>The Python tool loop owns the actual pause + {@code /resume}/{@code /switch}
 * REPL — see {@code agent/ollama_agent/budget_ceiling.py}. This Java side is
 * the long-running mirror that lets the Swing UI surface "ceiling reached"
 * banners without re-parsing the Python wrapper's stdout.
 *
 * <p>Free-tier model calls are intentionally <em>not</em> counted (06 §6.3).
 * Callers who do not know whether a call was free should default to
 * {@link #recordCost(double, boolean)} with {@code freeTier=false} — the cost
 * header from LiteLLM is already 0 for free-tier calls so accumulation stays
 * accurate either way.
 */
public final class BudgetCeilingTracker implements CostHeaderListener {

    public static final double DEFAULT_CEILING_USD = 1.00;

    /** Listener invoked when the running total first crosses the ceiling. */
    public interface BreachListener {
        void onCeilingBreached(double totalUsd, double ceilingUsd);
    }

    private final AtomicReference<Double> sessionCostUsd = new AtomicReference<>(0.0);
    private double ceilingUsd;
    private boolean enabled;
    private boolean breached;
    private final List<BreachListener> listeners = new ArrayList<>();

    public BudgetCeilingTracker() {
        this(false, DEFAULT_CEILING_USD);
    }

    public BudgetCeilingTracker(boolean enabled, double ceilingUsd) {
        this.enabled = enabled;
        this.ceilingUsd = ceilingUsd <= 0 ? DEFAULT_CEILING_USD : ceilingUsd;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            // Disabling resets breach state so toggling does not strand the loop.
            breached = false;
        }
    }

    public double ceilingUsd() {
        return ceilingUsd;
    }

    public void setCeilingUsd(double ceilingUsd) {
        if (ceilingUsd > 0) {
            this.ceilingUsd = ceilingUsd;
            // Raising the ceiling above the current total clears the breach
            // state so the next call can re-trip it (06 §6.3 /resume semantics).
            if (sessionCostUsd.get() < ceilingUsd) {
                breached = false;
            }
        }
    }

    public double sessionCostUsd() {
        return sessionCostUsd.get();
    }

    public boolean isBreached() {
        return breached;
    }

    /** Reset the running total — invoked at the start of each session. */
    public void resetSession() {
        sessionCostUsd.set(0.0);
        breached = false;
    }

    public void addListener(BreachListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(BreachListener listener) {
        listeners.remove(listener);
    }

    /**
     * Add {@code costUsd} to the running session cost. Free-tier calls do not
     * contribute regardless of the value LiteLLM reports.
     *
     * @return true if this call <em>just</em> crossed the ceiling (one shot).
     */
    public synchronized boolean recordCost(double costUsd, boolean freeTier) {
        if (freeTier || costUsd <= 0) {
            return false;
        }
        double previous = sessionCostUsd.get();
        double total = previous + costUsd;
        sessionCostUsd.set(total);
        if (!enabled) {
            return false;
        }
        if (!breached && total >= ceilingUsd) {
            breached = true;
            for (BreachListener listener : listeners) {
                try {
                    listener.onCeilingBreached(total, ceilingUsd);
                } catch (Exception ignore) {
                    // Listener bugs must not kill the tracker.
                }
            }
            return true;
        }
        return false;
    }

    /**
     * {@link CostHeaderListener} entry point — parses the raw header value the
     * Phase A sidecar emits. Treats malformed values as zero so a typo in the
     * upstream header never crashes the tool loop.
     */
    @Override
    public void onCostHeader(String costHeaderValue) {
        if (costHeaderValue == null || costHeaderValue.isEmpty()) {
            return;
        }
        try {
            double cost = Double.parseDouble(costHeaderValue.trim());
            recordCost(cost, false);
        } catch (NumberFormatException ignore) {
            // Drop silently — the structured pause message includes "may be
            // ±20%" wording for exactly this kind of header drift (risk #15).
        }
    }
}
