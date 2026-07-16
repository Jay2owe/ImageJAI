package imagejai.engine;

import ij.ImagePlus;
import ij.ImageStack;
import ij.process.ByteProcessor;
import ij.measure.ResultsTable;
import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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

    @Test
    public void resultsCsvRetainsOnlyCompleteRowsWithinUtf8Budget() {
        ResultsTable table = ResultsTable.getResultsTable();
        table.reset();
        try {
            table.incrementCounter();
            table.addValue("Value", "small");
            table.incrementCounter();
            table.addValue("Value", repeat('é', 5000));
            StateInspector inspector = new StateInspector();

            StateInspector.BoundedCsv bounded =
                    inspector.getResultsTableCSVBounded(64);

            assertTrue(bounded.truncated());
            assertTrue(bounded.returnedBytes() <= 64);
            assertEquals(2, bounded.totalRows());
            assertEquals(1, bounded.returnedRows());
            assertTrue(bounded.originalBytes() > bounded.returnedBytes());
            assertFalsePartialMultibyte(bounded.text());
        } finally {
            table.reset();
        }
    }

    @Test
    public void exactCsvBoundaryRetainsAllRows() {
        ResultsTable table = ResultsTable.getResultsTable();
        table.reset();
        try {
            table.incrementCounter();
            table.addValue("Value", "one");
            StateInspector inspector = new StateInspector();
            StateInspector.BoundedCsv complete =
                    inspector.getResultsTableCSVBounded(1024);
            StateInspector.BoundedCsv exact = inspector.getResultsTableCSVBounded(
                    complete.returnedBytes());

            assertEquals(complete.text(), exact.text());
            assertEquals(1, exact.returnedRows());
            assertTrue(!exact.truncated());
        } finally {
            table.reset();
        }
    }

    private static void assertFalsePartialMultibyte(String text) {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(text, new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
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
