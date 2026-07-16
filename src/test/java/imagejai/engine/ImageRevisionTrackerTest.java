package imagejai.engine;

import ij.ImagePlus;
import ij.ImageStack;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Regression coverage for no-throw, O(1), weak-identity image epochs. */
public class ImageRevisionTrackerTest {

    @Test
    public void emptyOrFlushedProcessorNeverEscapesListenerCallback() {
        ImageRevisionTracker tracker = ImageRevisionTracker.getInstance();
        ImagePlus image = new ImagePlus();
        ImageRevisionTracker.Snapshot before = tracker.snapshot(image);

        tracker.imageUpdated(image);
        ImageRevisionTracker.Snapshot after = tracker.snapshot(image);

        assertNotNull(before);
        assertNotNull(after);
        assertTrue(after.imageRevision > before.imageRevision);
        assertTrue(after.displayRevision > before.displayRevision);
        tracker.imageClosed(image);
    }

    @Test
    public void contentSamplingDoesNotCallMutatingStackAccessors() {
        AtomicBoolean unsafeGetterCalled = new AtomicBoolean(false);
        ImagePlus image = new ImagePlus("safe-stack", new ByteProcessor(2, 2)) {
            @Override public ImageStack getStack() {
                unsafeGetterCalled.set(true);
                throw new AssertionError("getStack applies calibration as a side effect");
            }

            @Override public ImageStack getImageStack() {
                unsafeGetterCalled.set(true);
                throw new AssertionError(
                        "getImageStack updates/constructs stack as a side effect");
            }
        };

        ImageRevisionTracker.Snapshot snapshot =
                ImageRevisionTracker.getInstance().snapshot(image);

        assertNotNull(snapshot);
        assertFalse(unsafeGetterCalled.get());
        ImageRevisionTracker.getInstance().imageClosed(image);
    }

    @Test
    public void transientCalibrationFailureBumpsEpochAndNeverEscapesUpdate() {
        ThrowingCalibration calibration = new ThrowingCalibration();
        CalibrationImage image = new CalibrationImage(calibration);
        ImageRevisionTracker tracker = ImageRevisionTracker.getInstance();
        ImageRevisionTracker.Snapshot before = tracker.snapshot(image);

        calibration.fail = true;
        tracker.imageUpdated(image);
        ImageRevisionTracker.Snapshot duringFailure = tracker.snapshot(image);

        assertTrue(duringFailure.imageRevision > before.imageRevision);
        assertFalse(tracker.isCurrent(before));

        calibration.fail = false;
        ImageRevisionTracker.Snapshot recovered = tracker.snapshot(image);
        assertNotNull(recovered);
        tracker.imageClosed(image);
    }

    @Test
    public void calibrationCoefficientChangeAdvancesContentEpoch() {
        Calibration calibration = new Calibration();
        calibration.setFunction(Calibration.STRAIGHT_LINE,
                new double[] {1.0, 2.0}, "intensity");
        CalibrationImage image = new CalibrationImage(calibration);
        ImageRevisionTracker tracker = ImageRevisionTracker.getInstance();
        ImageRevisionTracker.Snapshot before = tracker.snapshot(image);

        calibration.setFunction(Calibration.STRAIGHT_LINE,
                new double[] {1.0, 3.0}, "intensity");
        ImageRevisionTracker.Snapshot after = tracker.snapshot(image);

        assertTrue(after.imageRevision > before.imageRevision);
        tracker.imageClosed(image);
    }

    @Test
    public void overriddenEqualityCannotAliasDistinctImageStates() throws Exception {
        ImageRevisionTracker tracker = ImageRevisionTracker.getInstance();
        EqualImage first = new EqualImage("first");
        EqualImage second = new EqualImage("second");
        java.awt.EventQueue.invokeAndWait(() -> { });

        ImageRevisionTracker.Snapshot firstSnapshot = tracker.snapshot(first);
        ImageRevisionTracker.Snapshot secondSnapshot = tracker.snapshot(second);

        assertNotEquals(firstSnapshot.imageId, secondSnapshot.imageId);
        tracker.markContentChanged(first);
        ImageRevisionTracker.Snapshot firstChanged = tracker.snapshot(first);
        ImageRevisionTracker.Snapshot secondUnchanged = tracker.snapshot(second);
        assertTrue(firstChanged.imageRevision > firstSnapshot.imageRevision);
        assertEquals(secondSnapshot.imageRevision, secondUnchanged.imageRevision);
        tracker.imageClosed(first);
        tracker.imageClosed(second);
    }

    @Test
    public void closeRemovesTrackedIdentityState() throws Exception {
        ImageRevisionTracker tracker = ImageRevisionTracker.getInstance();
        ImagePlus image = new ImagePlus("closed", new ByteProcessor(1, 1));
        java.awt.EventQueue.invokeAndWait(() -> { });
        tracker.imageClosed(image);
        assertFalse(tracker.isTrackedForTest(image));
        tracker.snapshot(image);
        assertTrue(tracker.isTrackedForTest(image));

        tracker.imageClosed(image);

        assertFalse(tracker.isTrackedForTest(image));
    }

    private static final class ThrowingCalibration extends Calibration {
        volatile boolean fail;

        @Override public double[] getCoefficients() {
            if (fail) throw new NullPointerException("transient calibration table");
            return super.getCoefficients();
        }
    }

    private static final class CalibrationImage extends ImagePlus {
        private final Calibration calibration;

        CalibrationImage(Calibration calibration) {
            super("calibrated", new ByteProcessor(2, 2));
            this.calibration = calibration;
        }

        @Override public Calibration getCalibration() { return calibration; }
    }

    private static final class EqualImage extends ImagePlus {
        EqualImage(String title) {
            super(title, new ByteProcessor(1, 1));
        }

        @Override public boolean equals(Object other) {
            return other instanceof EqualImage;
        }

        @Override public int hashCode() { return 1; }
    }
}
