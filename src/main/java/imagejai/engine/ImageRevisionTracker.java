package imagejai.engine;

import ij.CompositeImage;
import ij.ImageListener;
import ij.ImagePlus;
import ij.gui.Overlay;
import ij.gui.Roi;
import ij.measure.Calibration;

import java.awt.Rectangle;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * O(1) image/update epochs for multi-call scientific reads.
 *
 * <p>ImageJ's {@link ImageListener#imageUpdated(ImagePlus)} callback is the
 * authoritative pixel-dirty signal. Structural, calibration and display
 * signatures are also sampled in constant time so changes which replace an
 * image processor, move the active plane, or alter the active ROI cannot be
 * silently mixed across replies. Direct writes through a retained raw pixel
 * array which never call an ImageJ update method are outside ImageJ's event
 * model and therefore require the caller to call {@link #markContentChanged}
 * (or use an API which calls {@code updateAndDraw()}).</p>
 */
final class ImageRevisionTracker implements ImageListener {

    private static final int MAX_CALIBRATION_COEFFICIENTS = 64;
    private static final int MAX_CALIBRATION_UNIT_CHARS = 256;

    private static final ImageRevisionTracker INSTANCE = new ImageRevisionTracker();

    static ImageRevisionTracker getInstance() { return INSTANCE; }

    static final class Snapshot {
        final ImagePlus image;
        final String imageId;
        final long imageRevision;
        final long displayRevision;
        final long contentSignature;
        final long displaySignature;

        Snapshot(ImagePlus image, String imageId, long imageRevision,
                 long displayRevision, long contentSignature,
                 long displaySignature) {
            this.image = image;
            this.imageId = imageId;
            this.imageRevision = imageRevision;
            this.displayRevision = displayRevision;
            this.contentSignature = contentSignature;
            this.displaySignature = displaySignature;
        }
    }

    private static final class State {
        long imageRevision;
        long displayRevision;
        long contentSignature;
        long displaySignature;
        String imageId;
        boolean imageIdKnown;
        boolean contentKnown;
        boolean displayKnown;
        boolean initialized;
    }

    private final ReferenceQueue<ImagePlus> stateQueue =
            new ReferenceQueue<ImagePlus>();
    private final Map<WeakImageKey, State> states =
            new HashMap<WeakImageKey, State>();
    private long sequence;

    /** Weak identity key: ImagePlus subclasses may override equals/hashCode. */
    private static final class WeakImageKey extends WeakReference<ImagePlus> {
        private final int identityHash;

        WeakImageKey(ImagePlus image, ReferenceQueue<ImagePlus> queue) {
            super(image, queue);
            identityHash = System.identityHashCode(image);
        }

        @Override public int hashCode() { return identityHash; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof WeakImageKey)) return false;
            ImagePlus mine = get();
            return mine != null && mine == ((WeakImageKey) other).get();
        }
    }

    private ImageRevisionTracker() {
        ImagePlus.addImageListener(this);
    }

    synchronized Snapshot snapshot(ImagePlus image) {
        if (image == null) return null;
        State state = stateFor(image);
        refreshImageId(state, image);
        Long content = tryContentSignature(image);
        Long display = tryDisplaySignature(image);
        if (!state.initialized) {
            state.imageRevision = nextRevision();
            state.displayRevision = nextRevision();
            initializeImageId(state, image);
            if (content != null) {
                state.contentSignature = content.longValue();
                state.contentKnown = true;
            }
            if (display != null) {
                state.displaySignature = display.longValue();
                state.displayKnown = true;
            }
            state.initialized = true;
        } else {
            if (content == null) {
                state.imageRevision = nextRevision();
            } else if (!state.contentKnown
                    || state.contentSignature != content.longValue()) {
                state.contentSignature = content.longValue();
                state.contentKnown = true;
                state.imageRevision = nextRevision();
            }
            if (display == null) {
                state.displayRevision = nextRevision();
            } else if (!state.displayKnown
                    || state.displaySignature != display.longValue()) {
                state.displaySignature = display.longValue();
                state.displayKnown = true;
                state.displayRevision = nextRevision();
            }
        }
        return new Snapshot(image, state.imageId,
                state.imageRevision, state.displayRevision,
                state.contentSignature, state.displaySignature);
    }

    synchronized boolean isCurrent(Snapshot expected) {
        if (expected == null || expected.image == null) return false;
        Snapshot current = snapshot(expected.image);
        return current.imageRevision == expected.imageRevision
                && current.displayRevision == expected.displayRevision
                && current.contentSignature == expected.contentSignature
                && current.displaySignature == expected.displaySignature;
    }

    synchronized void markContentChanged(ImagePlus image) {
        if (image == null) return;
        State state = stateFor(image);
        initializeIfNeeded(state, image);
        state.imageRevision = nextRevision();
        Long content = tryContentSignature(image);
        if (content != null) {
            state.contentSignature = content.longValue();
            state.contentKnown = true;
        }
    }

    synchronized void markDisplayChanged(ImagePlus image) {
        if (image == null) return;
        State state = stateFor(image);
        initializeIfNeeded(state, image);
        state.displayRevision = nextRevision();
        Long display = tryDisplaySignature(image);
        if (display != null) {
            state.displaySignature = display.longValue();
            state.displayKnown = true;
        }
    }

    @Override public synchronized void imageOpened(ImagePlus image) {
        try {
            removeState(image);
            if (image != null) snapshot(image);
        } catch (Throwable ignored) {
            // ImageJ invokes listeners on AWT/startup paths. Never let a
            // partially initialized image break the global listener chain.
            conservativelyBump(image, true, true);
        }
    }

    @Override public synchronized void imageClosed(ImagePlus image) {
        try {
            removeState(image);
        } catch (Throwable ignored) {
            // Closing images may already have torn down their stack/processor.
        }
    }

    @Override public synchronized void imageUpdated(ImagePlus image) {
        if (image == null) return;
        try {
            State state = stateFor(image);
            initializeIfNeeded(state, image);
            // The event itself is authoritative even when transient state
            // prevents a safe signature sample.
            state.imageRevision = nextRevision();
            state.displayRevision = nextRevision();
            Long content = tryContentSignature(image);
            if (content != null) {
                state.contentSignature = content.longValue();
                state.contentKnown = true;
            }
            Long display = tryDisplaySignature(image);
            if (display != null) {
                state.displaySignature = display.longValue();
                state.displayKnown = true;
            }
        } catch (Throwable ignored) {
            conservativelyBump(image, true, true);
        }
    }

    synchronized int trackedImageCountForTest() {
        expungeCollectedStates();
        return states.size();
    }

    synchronized boolean isTrackedForTest(ImagePlus image) {
        expungeCollectedStates();
        return image != null
                && states.containsKey(new WeakImageKey(image, null));
    }

    private State stateFor(ImagePlus image) {
        expungeCollectedStates();
        WeakImageKey lookup = new WeakImageKey(image, null);
        State state = states.get(lookup);
        if (state == null) {
            state = new State();
            states.put(new WeakImageKey(image, stateQueue), state);
        }
        return state;
    }

    private void removeState(ImagePlus image) {
        expungeCollectedStates();
        if (image != null) states.remove(new WeakImageKey(image, null));
    }

    private void expungeCollectedStates() {
        WeakImageKey collected;
        while ((collected = (WeakImageKey) stateQueue.poll()) != null) {
            states.remove(collected);
        }
    }

    private void initializeIfNeeded(State state, ImagePlus image) {
        if (state.initialized) return;
        state.imageRevision = nextRevision();
        state.displayRevision = nextRevision();
        initializeImageId(state, image);
        Long content = tryContentSignature(image);
        if (content != null) {
            state.contentSignature = content.longValue();
            state.contentKnown = true;
        }
        Long display = tryDisplaySignature(image);
        if (display != null) {
            state.displaySignature = display.longValue();
            state.displayKnown = true;
        }
        state.initialized = true;
    }

    private void conservativelyBump(ImagePlus image, boolean content,
                                    boolean display) {
        if (image == null) return;
        try {
            State state = stateFor(image);
            initializeIfNeeded(state, image);
            if (content) state.imageRevision = nextRevision();
            if (display) state.displayRevision = nextRevision();
        } catch (Throwable ignored) {
            // WeakHashMap/Object identity failures are not expected, but the
            // listener boundary must remain no-throw under all host states.
        }
    }

    private long nextRevision() {
        sequence++;
        if (sequence <= 0L) sequence = 1L;
        return sequence;
    }

    private static Long tryContentSignature(ImagePlus image) {
        try {
            return Long.valueOf(contentSignature(image));
        } catch (Throwable transientState) {
            return null;
        }
    }

    private static Long tryDisplaySignature(ImagePlus image) {
        try {
            return Long.valueOf(displaySignature(image));
        } catch (Throwable transientState) {
            return null;
        }
    }

    private void initializeImageId(State state, ImagePlus image) {
        String canonical = tryImageId(image);
        state.imageId = canonical != null ? canonical
                : "img-transient-" + Integer.toHexString(
                        System.identityHashCode(image));
        state.imageIdKnown = canonical != null;
    }

    private void refreshImageId(State state, ImagePlus image) {
        if (!state.initialized) return;
        String canonical = tryImageId(image);
        if (canonical == null) return;
        if (!state.imageIdKnown || !canonical.equals(state.imageId)) {
            state.imageId = canonical;
            state.imageIdKnown = true;
            state.imageRevision = nextRevision();
            state.displayRevision = nextRevision();
        }
    }

    private static String tryImageId(ImagePlus image) {
        try {
            String identity = ImageGraph.stableIdentity(image);
            if (identity != null && !identity.isEmpty()) return identity;
        } catch (Throwable ignored) {}
        return null;
    }

    private static long contentSignature(ImagePlus image) {
        long hash = 0xcbf29ce484222325L;
        hash = mix(hash, image.getWidth());
        hash = mix(hash, image.getHeight());
        hash = mix(hash, image.getType());
        hash = mix(hash, image.getNChannels());
        hash = mix(hash, image.getNSlices());
        hash = mix(hash, image.getNFrames());
        // Do not call getStack()/getImageStack(): both mutate ImagePlus and
        // may re-enter a transient Calibration table. getStackSize() is a
        // side-effect-free structural fact; imageUpdated covers replacement.
        hash = mix(hash, image.getStackSize());
        Calibration calibration = image.getCalibration();
        if (calibration == null) return mix(hash, 0L);
        hash = mix(hash, 1L);
        hash = mix(hash, Double.doubleToLongBits(calibration.pixelWidth));
        hash = mix(hash, Double.doubleToLongBits(calibration.pixelHeight));
        hash = mix(hash, Double.doubleToLongBits(calibration.pixelDepth));
        hash = mix(hash, Double.doubleToLongBits(calibration.xOrigin));
        hash = mix(hash, Double.doubleToLongBits(calibration.yOrigin));
        hash = mix(hash, Double.doubleToLongBits(calibration.zOrigin));
        hash = mix(hash, Double.doubleToLongBits(calibration.frameInterval));
        hash = mix(hash, calibration.getFunction());
        hash = mixBoundedString(hash, calibration.getXUnit());
        hash = mixBoundedString(hash, calibration.getYUnit());
        hash = mixBoundedString(hash, calibration.getZUnit());
        hash = mixBoundedString(hash, calibration.getTimeUnit());
        hash = mixBoundedString(hash, calibration.getValueUnit());
        double[] coefficients = calibration.getCoefficients();
        if (coefficients != null
                && coefficients.length > MAX_CALIBRATION_COEFFICIENTS) {
            throw new IllegalStateException("calibration coefficient array is unbounded");
        }
        hash = mix(hash, coefficients == null ? -1L : coefficients.length);
        if (coefficients != null) {
            for (double coefficient : coefficients) {
                hash = mix(hash, Double.doubleToLongBits(coefficient));
            }
        }
        hash = mix(hash, calibration.isSigned16Bit() ? 1L : 0L);
        hash = mix(hash, calibration.zeroClip() ? 1L : 0L);
        hash = mix(hash, calibration.getInvertY() ? 1L : 0L);
        return hash;
    }

    private static long displaySignature(ImagePlus image) {
        long hash = 0x84222325cbf29ce4L;
        hash = mix(hash, image.getC());
        hash = mix(hash, image.getZ());
        hash = mix(hash, image.getT());
        // getProcessor() mutates ROI/calibration state. These ImagePlus
        // accessors directly read the current processor and are contained by
        // tryDisplaySignature when a closing image has no processor.
        hash = mix(hash, Double.doubleToLongBits(image.getDisplayRangeMin()));
        hash = mix(hash, Double.doubleToLongBits(image.getDisplayRangeMax()));
        Roi roi = image.getRoi();
        if (roi == null) {
            hash = mix(hash, 0L);
        } else {
            hash = mix(hash, System.identityHashCode(roi));
            hash = mix(hash, roi.getType());
            hash = mix(hash, roi.getPosition());
            Rectangle bounds = roi.getBounds();
            if (bounds != null) {
                hash = mix(hash, bounds.x);
                hash = mix(hash, bounds.y);
                hash = mix(hash, bounds.width);
                hash = mix(hash, bounds.height);
            }
        }
        Overlay overlay = image.getOverlay();
        hash = mix(hash, overlay == null ? 0L : System.identityHashCode(overlay));
        hash = mix(hash, overlay == null ? 0L : overlay.size());
        if (image instanceof CompositeImage) {
            CompositeImage composite = (CompositeImage) image;
            hash = mix(hash, composite.getMode());
        }
        return hash;
    }

    private static long mixBoundedString(long hash, String value) {
        if (value == null) return mix(hash, 0L);
        if (value.length() > MAX_CALIBRATION_UNIT_CHARS) {
            throw new IllegalStateException("calibration unit string is unbounded");
        }
        hash = mix(hash, value.length());
        for (int i = 0; i < value.length(); i++) {
            hash = mix(hash, value.charAt(i));
        }
        return hash;
    }

    private static long mix(long hash, long value) {
        hash ^= value;
        return hash * 0x100000001b3L;
    }
}
