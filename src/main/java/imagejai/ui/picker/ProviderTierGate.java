package imagejai.ui.picker;

import imagejai.config.Settings;
import imagejai.engine.picker.ModelEntry;

import java.awt.Frame;
import java.util.HashSet;
import java.util.Set;

/**
 * Tier-safety gate that runs between the dropdown click and the launch
 * dispatch. Holds the per-session "don't ask again" sets for the paid and
 * uncurated dialogs (06 §3.4) — never persisted.
 *
 * <p>Returns one of three outcomes per call:
 * <ul>
 *   <li>{@link Decision#PROCEED} — clear to launch.</li>
 *   <li>{@link Decision#CANCEL_PICK_FREE} — user backed out; the caller should
 *       re-open the picker filtered to free-tier models (06 §3.2).</li>
 *   <li>{@link Decision#CANCEL_PICK_CURATED} — user backed out from the
 *       uncurated dialog; re-open the picker filtered to curated rows.</li>
 * </ul>
 */
public class ProviderTierGate {

    public enum Decision {
        PROCEED,
        CANCEL_PICK_FREE,
        CANCEL_PICK_CURATED
    }

    /** Test seam so unit tests can stub the dialog without showing windows. */
    public interface DialogFactory {
        FirstUseDialog.Result show(Frame owner,
                                   ModelEntry entry,
                                   FirstUseDialog.Variant variant,
                                   boolean[] dontAskAgainOut);
    }

    private final Settings settings;
    private final Set<String> paidDontAsk = new HashSet<>();
    private final Set<String> uncuratedDontAsk = new HashSet<>();
    private final DialogFactory dialogFactory;

    public ProviderTierGate(Settings settings) {
        this(settings, defaultDialogFactory());
    }

    public ProviderTierGate(Settings settings, DialogFactory dialogFactory) {
        this.settings = settings;
        this.dialogFactory = dialogFactory == null ? defaultDialogFactory() : dialogFactory;
    }

    /**
     * Run the gate for one model launch. Stacks paid → uncurated when both
     * apply (06 §5.3). Cancel from either step short-circuits subsequent steps.
     */
    public Decision check(Frame owner, ModelEntry entry) {
        if (entry == null) {
            return Decision.PROCEED;
        }

        // 1) Paid / subscription dialog.
        if (shouldShowPaid(entry)) {
            FirstUseDialog.Variant variant =
                    entry.tier() == ModelEntry.Tier.REQUIRES_SUBSCRIPTION
                            ? FirstUseDialog.Variant.SUBSCRIPTION
                            : FirstUseDialog.Variant.PAID;
            boolean[] flag = new boolean[1];
            FirstUseDialog.Result result = dialogFactory.show(owner, entry, variant, flag);
            if (flag[0]) {
                paidDontAsk.add(entry.providerId());
            }
            if (result != FirstUseDialog.Result.CONTINUE) {
                return Decision.CANCEL_PICK_FREE;
            }
        }

        // 2) Uncurated dialog stacks after the paid one.
        if (shouldShowUncurated(entry)) {
            boolean[] flag = new boolean[1];
            FirstUseDialog.Result result = dialogFactory.show(
                    owner, entry, FirstUseDialog.Variant.UNCURATED, flag);
            if (flag[0]) {
                uncuratedDontAsk.add(entry.providerId());
            }
            if (result != FirstUseDialog.Result.CONTINUE) {
                return Decision.CANCEL_PICK_CURATED;
            }
        }

        return Decision.PROCEED;
    }

    private boolean shouldShowPaid(ModelEntry entry) {
        if (settings != null && !settings.confirmPaidModels) {
            return false;
        }
        if (entry.tier() != ModelEntry.Tier.PAID
                && entry.tier() != ModelEntry.Tier.REQUIRES_SUBSCRIPTION) {
            return false;
        }
        return !paidDontAsk.contains(entry.providerId());
    }

    private boolean shouldShowUncurated(ModelEntry entry) {
        if (settings != null && !settings.warnUncuratedModels) {
            return false;
        }
        // Either the curator marked it false, or its tier collapsed to UNCURATED
        // (auto-discovered subsection).
        boolean isUncurated = !entry.curated() || entry.tier() == ModelEntry.Tier.UNCURATED;
        if (!isUncurated) {
            return false;
        }
        return !uncuratedDontAsk.contains(entry.providerId());
    }

    /** Whether the user has previously ticked don't-ask-again for paid models. Visible for tests. */
    public boolean isPaidSuppressed(String providerId) {
        return paidDontAsk.contains(providerId);
    }

    /** Whether the user has previously ticked don't-ask-again for uncurated models. Visible for tests. */
    public boolean isUncuratedSuppressed(String providerId) {
        return uncuratedDontAsk.contains(providerId);
    }

    /** Test-only — clear both don't-ask-again sets (Fiji restart equivalent). */
    void clearForTest() {
        paidDontAsk.clear();
        uncuratedDontAsk.clear();
    }

    private static DialogFactory defaultDialogFactory() {
        return (owner, entry, variant, dontAskAgainOut) -> {
            FirstUseDialog dialog = new FirstUseDialog(owner, entry, variant);
            FirstUseDialog.Result result = dialog.showAndAwait();
            if (dontAskAgainOut != null && dontAskAgainOut.length > 0) {
                dontAskAgainOut[0] = dialog.dontAskAgainChecked();
            }
            return result;
        };
    }
}
