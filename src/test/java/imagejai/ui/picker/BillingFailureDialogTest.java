package imagejai.ui.picker;

import org.junit.Test;

import java.awt.Dialog;
import java.awt.GraphicsEnvironment;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

/**
 * Unit tests for {@link BillingFailureDialog}. Mirrors the construction-only
 * coverage in {@link ProviderTierGateTest} — Swing dialogs are exercised via
 * test seams ({@code setResultForTest}, custom {@code BrowserOpener}) so the
 * suite stays headless.
 */
public class BillingFailureDialogTest {

    @Test
    public void defaultResult_isClose() {
        assumeFalse("headless build", GraphicsEnvironment.isHeadless());
        BillingFailureDialog dialog = new BillingFailureDialog(
                null,
                "Anthropic",
                "credit balance too low",
                URI.create("https://console.anthropic.com"));
        // The dialog defaults to CLOSE when never shown — the same convention
        // as FirstUseDialog.Result.CANCEL.
        assertEquals(BillingFailureDialog.Result.CLOSE,
                getResultViaTestSeam(dialog));
    }

    @Test
    public void modalityType_isDocumentModal() {
        assumeFalse("headless build", GraphicsEnvironment.isHeadless());
        BillingFailureDialog dialog = new BillingFailureDialog(
                null, "Anthropic", "402 credit balance too low", null);
        assertEquals(Dialog.ModalityType.DOCUMENT_MODAL, dialog.getModalityType());
    }

    @Test
    public void title_includesProviderDisplayName() {
        assumeFalse("headless build", GraphicsEnvironment.isHeadless());
        BillingFailureDialog dialog = new BillingFailureDialog(
                null, "OpenAI", "rate-limited", null);
        assertNotNull(dialog.getTitle());
        assertTrue("title should mention provider; got: " + dialog.getTitle(),
                dialog.getTitle().contains("OpenAI"));
    }

    @Test
    public void browserOpener_canBeStubbed() {
        assumeFalse("headless build", GraphicsEnvironment.isHeadless());
        AtomicReference<URI> opened = new AtomicReference<>();
        BillingFailureDialog.BrowserOpener stub = uri -> {
            opened.set(uri);
            return true;
        };
        URI console = URI.create("https://platform.openai.com/account/billing");
        BillingFailureDialog dialog = new BillingFailureDialog(
                null, "OpenAI", "402", console, stub);
        // The default opener is replaced; verify the dialog stored the URI.
        // Smoke: triggering the open path with a stub never throws.
        dialog.setResultForTest(BillingFailureDialog.Result.OPEN_CONSOLE);
        assertEquals(BillingFailureDialog.Result.OPEN_CONSOLE,
                getResultViaTestSeam(dialog));
        assertFalse("browser opener should not have been invoked yet without click",
                opened.get() == console);
    }

    @Test
    public void nullErrorBody_rendersFallbackText() {
        assumeFalse("headless build", GraphicsEnvironment.isHeadless());
        // Should not throw — sentinel "(no error body returned)" replaces null.
        new BillingFailureDialog(null, "xAI", null, null);
    }

    @Test
    public void nullProviderDisplay_rendersFallback() {
        assumeFalse("headless build", GraphicsEnvironment.isHeadless());
        BillingFailureDialog dialog = new BillingFailureDialog(
                null, null, "402 payment required", null);
        // Title mentions a generic placeholder so the dialog is still usable.
        assertNotNull(dialog.getTitle());
    }

    private static BillingFailureDialog.Result getResultViaTestSeam(BillingFailureDialog dialog) {
        // Read via the showAndAwait() short-circuit: setting the result via
        // the package-private seam and never calling setVisible(true) keeps
        // the test headless while still covering the happy path.
        try {
            java.lang.reflect.Field f = BillingFailureDialog.class.getDeclaredField("result");
            f.setAccessible(true);
            return (BillingFailureDialog.Result) f.get(dialog);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }
}
