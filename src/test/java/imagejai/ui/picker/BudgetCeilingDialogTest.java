package imagejai.ui.picker;

import org.junit.Test;

import java.awt.Dialog;
import java.awt.GraphicsEnvironment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

/**
 * Unit tests for {@link BudgetCeilingDialog}. Headless-safe — exercises
 * construction, modality, and the {@code newCeilingUsd()} default-doubling
 * convention from {@code 06 §6.3 step 5} via the package-private test seams.
 */
public class BudgetCeilingDialogTest {

    @Test
    public void defaultResult_isClose() {
        assumeFalse("headless build", GraphicsEnvironment.isHeadless());
        BudgetCeilingDialog dialog = new BudgetCeilingDialog(null, 1.04, 1.00);
        assertEquals(BudgetCeilingDialog.Result.CLOSE, getResult(dialog));
    }

    @Test
    public void modalityType_isDocumentModal() {
        assumeFalse("headless build", GraphicsEnvironment.isHeadless());
        BudgetCeilingDialog dialog = new BudgetCeilingDialog(null, 1.04, 1.00);
        assertEquals(Dialog.ModalityType.DOCUMENT_MODAL, dialog.getModalityType());
    }

    @Test
    public void titleAndCostsAreExposed() {
        assumeFalse("headless build", GraphicsEnvironment.isHeadless());
        BudgetCeilingDialog dialog = new BudgetCeilingDialog(null, 2.50, 2.00);
        assertNotNull(dialog.getTitle());
        assertEquals(2.50, dialog.sessionCostUsd(), 1e-9);
        assertEquals(2.00, dialog.currentCeilingUsd(), 1e-9);
    }

    @Test
    public void newCeiling_defaultsToCurrentTimesTwo() {
        assumeFalse("headless build", GraphicsEnvironment.isHeadless());
        BudgetCeilingDialog dialog = new BudgetCeilingDialog(null, 1.04, 0.50);
        // Spec: 06 §6.3 step 5 — "default = current ceiling × 2".
        assertEquals(1.00, dialog.newCeilingUsd(), 1e-9);
    }

    @Test
    public void invalidCurrentCeiling_clampsToOne() {
        assumeFalse("headless build", GraphicsEnvironment.isHeadless());
        BudgetCeilingDialog dialog = new BudgetCeilingDialog(null, 0.05, 0.0);
        // 0 / negative ceiling collapses to the default $1.00 sentinel rather
        // than producing a $0 doubled-ceiling that would re-pause immediately.
        assertEquals(1.00, dialog.currentCeilingUsd(), 1e-9);
        assertEquals(2.00, dialog.newCeilingUsd(), 1e-9);
    }

    @Test
    public void fallbackEstimate_isExposed() {
        assumeFalse("headless build", GraphicsEnvironment.isHeadless());
        BudgetCeilingDialog dialog = new BudgetCeilingDialog(null, 1.20, 1.00, true);
        assertTrue(dialog.fallbackEstimate());
    }

    @Test
    public void testSeam_canSetResultAndCeiling() {
        assumeFalse("headless build", GraphicsEnvironment.isHeadless());
        BudgetCeilingDialog dialog = new BudgetCeilingDialog(null, 1.04, 1.00);
        dialog.setResultForTest(BudgetCeilingDialog.Result.RESUME);
        dialog.setNewCeilingForTest(5.0);
        assertEquals(BudgetCeilingDialog.Result.RESUME, getResult(dialog));
        assertEquals(5.0, dialog.newCeilingUsd(), 1e-9);
    }

    private static BudgetCeilingDialog.Result getResult(BudgetCeilingDialog dialog) {
        try {
            java.lang.reflect.Field f = BudgetCeilingDialog.class.getDeclaredField("result");
            f.setAccessible(true);
            return (BudgetCeilingDialog.Result) f.get(dialog);
        } catch (Exception ex) {
            throw new AssertionError(ex);
        }
    }
}
