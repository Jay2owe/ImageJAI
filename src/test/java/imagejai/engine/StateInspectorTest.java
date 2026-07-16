package imagejai.engine;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

public class StateInspectorTest {

    @Test
    public void datasetHashCoversEveryCztPlaneButNotActiveSelectionOrTitle() {
        ImagePlus image = hyperstack();
        image.setPosition(2, 2, 2);
        String baseline = StateInspector.datasetHash(image);

        image.setPosition(1, 1, 1);
        image.setTitle("renamed without changing the dataset");
        assertEquals(baseline, StateInspector.datasetHash(image));

        image.setPosition(2, 2, 2);
        ((byte[]) image.getStack().getPixels(1))[0]++;
        assertNotEquals("a non-active plane must affect provenance",
                baseline, StateInspector.datasetHash(image));
    }

    @Test
    public void datasetHashIsLocaleIndependentAndCalibrationSensitive() {
        ImagePlus image = hyperstack();
        Locale previous = Locale.getDefault();
        String baseline;
        try {
            Locale.setDefault(Locale.US);
            baseline = StateInspector.datasetHash(image);
            Locale.setDefault(new Locale("ar", "EG"));
            assertEquals(baseline, StateInspector.datasetHash(image));
        } finally {
            Locale.setDefault(previous);
        }

        image.getCalibration().pixelWidth = 2.5;
        assertNotEquals(baseline, StateInspector.datasetHash(image));
    }

    @Test
    public void datasetHashHasStableSha256Shape() {
        String hash = StateInspector.datasetHash(hyperstack());
        assertNotNull(hash);
        assertEquals(64, hash.length());
    }

    private static ImagePlus hyperstack() {
        ImageStack stack = new ImageStack(2, 2);
        for (int plane = 0; plane < 8; plane++) {
            byte[] pixels = new byte[] {(byte) plane, (byte) (plane + 1),
                    (byte) (plane + 2), (byte) (plane + 3)};
            stack.addSlice("plane-" + plane, new ByteProcessor(2, 2, pixels, null));
        }
        ImagePlus image = new ImagePlus("dataset", stack);
        image.setDimensions(2, 2, 2);
        image.setOpenAsHyperStack(true);
        return image;
    }
}
