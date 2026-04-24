package imagejai.engine;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pure-function tests for {@link RecorderCapture} introduced by
 * {@code docs/tcp_upgrade/03_canonical_macro_echo.md}. Exercises the
 * {@code normalise} / {@code differs} logic that decides whether the
 * Recorder delta is worth echoing back to the agent.
 * <p>
 * The live-Recorder paths (begin / getDelta / close) need a running Fiji
 * and are exercised via integration when the TCP server runs — not here.
 */
public class RecorderCaptureTest {

    // ---- normalise ----

    @Test
    public void normalise_nullBecomesEmpty() {
        assertEquals("", RecorderCapture.normalise(null));
    }

    @Test
    public void normalise_collapsesRunsOfWhitespace() {
        assertEquals("run(\"X\");",
                RecorderCapture.normalise("run(\"X\");"));
        assertEquals("run(\"X\");",
                RecorderCapture.normalise("  run(\"X\");  "));
        assertEquals("run(\"X\"); run(\"Y\");",
                RecorderCapture.normalise("run(\"X\");\n\trun(\"Y\");"));
    }

    @Test
    public void normalise_preservesQuoteContents() {
        // This is the whole point — Otzu vs Default must remain distinguishable.
        assertFalse(RecorderCapture.normalise("setAutoThreshold(\"Otzu\");")
                .equals(RecorderCapture.normalise("setAutoThreshold(\"Default\");")));
    }

    @Test
    public void normalise_preservesPunctuationAndArgs() {
        assertEquals("run(\"Gaussian Blur...\", \"sigma=2\");",
                RecorderCapture.normalise("run(\"Gaussian Blur...\", \"sigma=2\");"));
    }

    // ---- differs ----

    @Test
    public void differs_emptyRan_returnsFalse() {
        assertFalse("no ran text = nothing to echo",
                RecorderCapture.differs("run(\"X\");", ""));
    }

    @Test
    public void differs_nullRan_returnsFalse() {
        assertFalse(RecorderCapture.differs("run(\"X\");", null));
    }

    @Test
    public void differs_identical_returnsFalse() {
        assertFalse("same macro string → no diff",
                RecorderCapture.differs("run(\"X\");", "run(\"X\");"));
    }

    @Test
    public void differs_onlyWhitespaceChange_returnsFalse() {
        assertFalse("whitespace-only change is not material",
                RecorderCapture.differs("run(\"X\");",
                                        "run(\"X\");\n"));
        assertFalse("indent change is not material",
                RecorderCapture.differs("run(\"X\");",
                                        "  run(\"X\");"));
    }

    @Test
    public void differs_realNormalisation_returnsTrue() {
        // The canonical bug — submit Otzu, ImageJ records Default.
        assertTrue("typo correction is a material diff",
                RecorderCapture.differs(
                        "setAutoThreshold(\"Otzu\");",
                        "setAutoThreshold(\"Default\");"));
    }

    @Test
    public void differs_argInjection_returnsTrue() {
        // Submit run("X"), plugin opens a dialog and records run("X", "args...")
        assertTrue("arg injection is a material diff",
                RecorderCapture.differs(
                        "run(\"Gaussian Blur...\");",
                        "run(\"Gaussian Blur...\", \"sigma=2\");"));
    }

    @Test
    public void differs_commandNameChange_returnsTrue() {
        // Submit lowercase command, ImageJ matches a different canonical one.
        assertTrue(RecorderCapture.differs(
                "run(\"gaussian blur...\", \"sigma=2\");",
                "run(\"Gaussian Blur...\", \"sigma=2\");"));
    }
}
