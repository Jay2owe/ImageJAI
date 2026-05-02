package imagejai.ui.picker;

import org.junit.Before;
import org.junit.Test;
import imagejai.config.Settings;
import imagejai.engine.picker.ModelEntry;

import java.awt.Frame;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Phase H acceptance tests for {@link ProviderTierGate} per
 * docs/multi_provider/06_tier_safety.md §3.
 */
public class ProviderTierGateTest {

    private Settings settings;
    private List<FirstUseDialog.Variant> dialogsShown;
    private FirstUseDialog.Result nextResult;
    private boolean nextDontAskAgain;

    @Before
    public void setUp() {
        settings = new Settings();
        settings.confirmPaidModels = true;
        settings.warnUncuratedModels = true;
        dialogsShown = new ArrayList<>();
        nextResult = FirstUseDialog.Result.CONTINUE;
        nextDontAskAgain = false;
    }

    private ProviderTierGate buildGate() {
        return new ProviderTierGate(settings, (owner, entry, variant, dontAskAgainOut) -> {
            dialogsShown.add(variant);
            if (dontAskAgainOut != null && dontAskAgainOut.length > 0) {
                dontAskAgainOut[0] = nextDontAskAgain;
            }
            return nextResult;
        });
    }

    private static ModelEntry paid(String providerId, String modelId) {
        return new ModelEntry(providerId, modelId, modelId, "paid model",
                ModelEntry.Tier.PAID, 200_000, false,
                ModelEntry.Reliability.HIGH, false, true, "");
    }

    private static ModelEntry subscription() {
        return new ModelEntry("anthropic", "claude-pro", "Claude Pro", "subscription",
                ModelEntry.Tier.REQUIRES_SUBSCRIPTION, 200_000, false,
                ModelEntry.Reliability.HIGH, false, true, "");
    }

    private static ModelEntry uncurated() {
        return new ModelEntry("groq", "llama-3.2-90b-text-preview",
                "llama-3.2-90b-text-preview", "auto-detected",
                ModelEntry.Tier.UNCURATED, 0, false,
                ModelEntry.Reliability.LOW, false, false, "");
    }

    private static ModelEntry free() {
        return new ModelEntry("ollama", "llama3.2:3b", "Llama 3.2 3B", "local",
                ModelEntry.Tier.FREE, 128_000, false,
                ModelEntry.Reliability.MEDIUM, false, true, "");
    }

    @Test
    public void paid_firesPaidDialogOnce() {
        ProviderTierGate gate = buildGate();
        ProviderTierGate.Decision a = gate.check((Frame) null, paid("anthropic", "claude-sonnet-4-6"));
        assertEquals(ProviderTierGate.Decision.PROCEED, a);
        assertEquals(1, dialogsShown.size());
        assertEquals(FirstUseDialog.Variant.PAID, dialogsShown.get(0));
    }

    @Test
    public void paid_dontAskAgain_suppressesSubsequentPaidFromSameProvider() {
        ProviderTierGate gate = buildGate();
        nextDontAskAgain = true;
        ProviderTierGate.Decision a = gate.check((Frame) null, paid("anthropic", "claude-sonnet-4-6"));
        assertEquals(ProviderTierGate.Decision.PROCEED, a);
        // Second paid pick from same provider should not fire the dialog.
        nextDontAskAgain = false;  // even though the user wouldn't have it now
        ProviderTierGate.Decision b = gate.check((Frame) null, paid("anthropic", "claude-haiku-4-5"));
        assertEquals(ProviderTierGate.Decision.PROCEED, b);
        assertEquals(1, dialogsShown.size());
    }

    @Test
    public void paid_dontAskAgain_isPerProvider() {
        ProviderTierGate gate = buildGate();
        nextDontAskAgain = true;
        gate.check((Frame) null, paid("anthropic", "claude-sonnet-4-6"));
        // Different provider → dialog must fire again. The user does NOT tick
        // don't-ask-again on this second dialog, so openai stays unsuppressed.
        nextDontAskAgain = false;
        gate.check((Frame) null, paid("openai", "gpt-5-mini"));
        assertEquals(2, dialogsShown.size());
        assertTrue(gate.isPaidSuppressed("anthropic"));
        assertFalse(gate.isPaidSuppressed("openai"));
    }

    @Test
    public void subscription_dispatchesSubscriptionVariant() {
        ProviderTierGate gate = buildGate();
        gate.check((Frame) null, subscription());
        assertEquals(1, dialogsShown.size());
        assertEquals(FirstUseDialog.Variant.SUBSCRIPTION, dialogsShown.get(0));
    }

    @Test
    public void cancel_returnsCancelPickFree_andLeavesProviderUnsuppressed() {
        ProviderTierGate gate = buildGate();
        nextResult = FirstUseDialog.Result.PICK_FREE;
        ProviderTierGate.Decision decision = gate.check((Frame) null,
                paid("anthropic", "claude-sonnet-4-6"));
        assertEquals(ProviderTierGate.Decision.CANCEL_PICK_FREE, decision);
        assertFalse(gate.isPaidSuppressed("anthropic"));
    }

    @Test
    public void uncurated_firesAfterPaid_whenBothApply() {
        ProviderTierGate gate = buildGate();
        // Paid+uncurated synthetic: rare in production but the stack must hold.
        ModelEntry both = new ModelEntry("openai", "gpt-x-experimental",
                "Experimental", "", ModelEntry.Tier.PAID, 0, false,
                ModelEntry.Reliability.LOW, false, false, "");
        ProviderTierGate.Decision decision = gate.check((Frame) null, both);
        assertEquals(ProviderTierGate.Decision.PROCEED, decision);
        assertEquals(2, dialogsShown.size());
        assertEquals(FirstUseDialog.Variant.PAID, dialogsShown.get(0));
        assertEquals(FirstUseDialog.Variant.UNCURATED, dialogsShown.get(1));
    }

    @Test
    public void uncurated_dontAskAgain_persistsThroughSession() {
        ProviderTierGate gate = buildGate();
        nextDontAskAgain = true;
        gate.check((Frame) null, uncurated());
        assertTrue(gate.isUncuratedSuppressed("groq"));
        nextDontAskAgain = false;
        gate.check((Frame) null, uncurated());
        // Only one uncurated dialog despite two attempts — same-provider suppression.
        assertEquals(1, dialogsShown.size());
    }

    @Test
    public void free_skipsBothDialogs() {
        ProviderTierGate gate = buildGate();
        ProviderTierGate.Decision decision = gate.check((Frame) null, free());
        assertEquals(ProviderTierGate.Decision.PROCEED, decision);
        assertEquals(0, dialogsShown.size());
    }

    @Test
    public void confirmPaidModelsFalse_disablesPaidDialog() {
        settings.confirmPaidModels = false;
        ProviderTierGate gate = buildGate();
        gate.check((Frame) null, paid("anthropic", "claude-sonnet-4-6"));
        assertEquals(0, dialogsShown.size());
    }

    @Test
    public void warnUncuratedModelsFalse_disablesUncuratedDialog() {
        settings.warnUncuratedModels = false;
        ProviderTierGate gate = buildGate();
        gate.check((Frame) null, uncurated());
        assertEquals(0, dialogsShown.size());
    }
}
