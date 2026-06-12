package imagejai.engine;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.awt.Window;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Headless unit tests for step 10 — {@link PhantomDialogDetector}. The pure
 * halves (safe-button allow-list, likelyCause heuristics, null-input
 * contract) are exercised without a live Swing dialog. A live-modal test
 * would need a real AWT peer and is out of scope for the CI run. Per plan:
 * docs/tcp_upgrade/10_phantom_dialog_detector.md.
 */
public class PhantomDialogDetectorTest {

    // ------------------------------------------------------------------
    // pickSafeButton — auto-dismiss allow-list
    // ------------------------------------------------------------------

    @Test
    public void pickSafeButtonRejectsDestructiveLabels() {
        // Overwrite / Yes / OK / Save / Delete / Proceed are explicitly
        // excluded from the allow-list (plan §"Auto-dismiss allow-list").
        assertNull(PhantomDialogDetector.pickSafeButton(
                Arrays.asList("Yes", "Overwrite")));
        assertNull(PhantomDialogDetector.pickSafeButton(
                Arrays.asList("OK")));
        assertNull(PhantomDialogDetector.pickSafeButton(
                Arrays.asList("Save", "Delete", "Proceed")));
    }

    @Test
    public void pickSafeButtonPicksCancelVariants() {
        assertEquals("No", PhantomDialogDetector.pickSafeButton(
                Arrays.asList("Yes", "No", "Cancel")));
        assertEquals("Cancel", PhantomDialogDetector.pickSafeButton(
                Arrays.asList("OK", "Cancel")));
        assertEquals("Don't Save", PhantomDialogDetector.pickSafeButton(
                Arrays.asList("Save", "Don't Save", "Cancel")));
        assertEquals("Skip", PhantomDialogDetector.pickSafeButton(
                Arrays.asList("Skip", "Retry")));
    }

    @Test
    public void pickSafeButtonIsCaseSensitive() {
        // Allow-list uses exact match — locale-specific variants
        // ("Annuler") and miscased inputs ("cancel") are rejected by
        // design so an attacker-controlled plugin can't craft a button
        // labelled "cancel" that triggers the safe path.
        assertNull(PhantomDialogDetector.pickSafeButton(
                Arrays.asList("cancel", "no")));
        assertNull(PhantomDialogDetector.pickSafeButton(
                Arrays.asList("CANCEL", "NO")));
        assertNull(PhantomDialogDetector.pickSafeButton(
                Arrays.asList("Annuler")));
    }

    @Test
    public void pickSafeButtonHandlesNullAndEmpty() {
        assertNull(PhantomDialogDetector.pickSafeButton(null));
        assertNull(PhantomDialogDetector.pickSafeButton(
                Collections.<String>emptyList()));
        assertNull(PhantomDialogDetector.pickSafeButton(
                Arrays.asList((String) null)));
    }

    @Test
    public void pickSafeButtonPicksFirstSafeEntry() {
        // When multiple safe labels are present, the first in traversal order
        // wins — mirrors the order buttons appear in the AWT component tree.
        assertEquals("Cancel", PhantomDialogDetector.pickSafeButton(
                Arrays.asList("Yes", "Cancel", "No")));
    }

    @Test
    public void pickSafeButtonTrimsLabels() {
        // Callers pass button labels via getText().trim() upstream; guard
        // here anyway so a whitespace-laden label doesn't bypass the list.
        assertEquals("Cancel", PhantomDialogDetector.pickSafeButton(
                Arrays.asList("  Cancel  ")));
    }

    // ------------------------------------------------------------------
    // guessCause — likelyCause heuristic lookup
    // ------------------------------------------------------------------

    @Test
    public void guessCauseOverwriteMatchesTitle() {
        String cause = PhantomDialogDetector.guessCause(
                "Overwrite file?",
                "The file foo.tif already exists. Overwrite?",
                Arrays.asList("Yes", "No", "Cancel"));
        assertNotNull(cause);
        assertTrue(cause.contains("saveAs"));
    }

    @Test
    public void guessCauseAlreadyExistsMatchesBody() {
        String cause = PhantomDialogDetector.guessCause(
                "Save",
                "The output path already exists",
                Arrays.asList("OK"));
        assertNotNull(cause);
        assertTrue(cause.contains("saveAs"));
    }

    @Test
    public void guessCauseDontSaveImpliesUnsavedChanges() {
        String cause = PhantomDialogDetector.guessCause(
                "Changes",
                "Save changes to the active image?",
                Arrays.asList("Save", "Don't Save", "Cancel"));
        assertNotNull(cause);
        assertTrue(cause.contains("unsaved changes"));
    }

    @Test
    public void guessCauseMacroErrorTitle() {
        String cause = PhantomDialogDetector.guessCause(
                "Macro Error",
                "Unrecognized command: foobar",
                Arrays.asList("OK"));
        assertNotNull(cause);
        assertTrue(cause.contains("macro error"));
    }

    @Test
    public void guessCauseFallbackReturnsNull() {
        // Dialog that matches nothing on the pattern list — returns null so
        // the reply emits an explicit null under "likelyCause".
        assertNull(PhantomDialogDetector.guessCause(
                "Some random plugin prompt",
                "Choose an option",
                Arrays.asList("Apply")));
    }

    @Test
    public void guessCauseHandlesNullInputs() {
        // No NPE when the dialog exposed no title/body. Body-only would still
        // need to trip a content-based rule ("already exists"); everything
        // falls through to null otherwise.
        assertNull(PhantomDialogDetector.guessCause(null, null, null));
        assertNull(PhantomDialogDetector.guessCause(null, null,
                Collections.<String>emptyList()));
    }

    // ------------------------------------------------------------------
    // detect — contract against empty / null / equal snapshots
    // ------------------------------------------------------------------

    @Test
    public void detectReturnsEmptyWhenNoNewModals() {
        // Headless environment — currentModalWindows returns an empty set.
        // Passing the same (empty) snapshot as "before" means nothing is
        // new, so detect returns Optional.empty regardless of the dismiss
        // flag. This is the hot path: every macro pays this contract and
        // must return cheaply with zero work when there is no phantom.
        Set<Window> empty = Collections.emptySet();
        Optional<JsonObject> out = PhantomDialogDetector.detect(empty, false);
        assertFalse(out.isPresent());
        Optional<JsonObject> outAutoDismiss =
                PhantomDialogDetector.detect(empty, true);
        assertFalse(outAutoDismiss.isPresent());
    }

    @Test
    public void detectHandlesNullPreSnapshot() {
        // A caller that never snapshotted before running the macro is a bug,
        // but the detector must not NPE — treat null as empty so production
        // code keeps going even if a future refactor forgets the snapshot.
        Optional<JsonObject> out = PhantomDialogDetector.detect(null, false);
        assertFalse(out.isPresent());
    }

    @Test
    public void currentModalWindowsReturnsNonNull() {
        // Headless environment: no modal dialogs exist. The API must return
        // an empty (but non-null) set so callers can feed it back into
        // detect without additional null-guarding.
        Set<Window> set = PhantomDialogDetector.currentModalWindows();
        assertNotNull(set);
        assertTrue("headless set should be empty", set.isEmpty());
    }

    // ------------------------------------------------------------------
    // Integration with auto-dismiss allow-list + guess-cause
    // (matrix from plan's Tests section)
    // ------------------------------------------------------------------

    @Test
    public void allowListIgnoresBodyContent() {
        // Per plan: the word "delete" in the body does NOT change the
        // allow-list. Button label is the only criterion, so a prompt with
        // buttons [Yes, No] and body "Delete all files?" still auto-
        // dismisses with "No". We assert via pickSafeButton + guessCause
        // together: both operate independently, which is the contract.
        List<String> buttons = Arrays.asList("Yes", "No");
        assertEquals("No", PhantomDialogDetector.pickSafeButton(buttons));
        // guessCause sees no matching pattern for the body here, so returns
        // null — proving it doesn't accidentally classify "delete" as
        // destructive-and-should-not-dismiss. The safe path is label-gated.
        assertNull(PhantomDialogDetector.guessCause(
                "Confirm", "Delete all files?", buttons));
    }

    @Test
    public void saveDialogWithFullButtonSetPicksDontSave() {
        // Matrix entry: macro with caps.autoDismissPhantoms=true, buttons
        // ["Save", "Don't Save", "Cancel"] — dismissal must pick "Don't Save".
        List<String> buttons = Arrays.asList("Save", "Don't Save", "Cancel");
        String safe = PhantomDialogDetector.pickSafeButton(buttons);
        // Either "Don't Save" or "Cancel" is technically safe; traversal
        // order in AWT places "Don't Save" before "Cancel" in Fiji's Save
        // prompt, so we assert the first-match choice.
        assertEquals("Don't Save", safe);
    }
}
