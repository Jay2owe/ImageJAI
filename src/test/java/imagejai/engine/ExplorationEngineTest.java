package imagejai.engine;

import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.OvalRoi;
import ij.gui.Roi;
import ij.measure.ResultsTable;
import ij.plugin.filter.Analyzer;
import ij.plugin.frame.RoiManager;
import ij.process.ByteProcessor;
import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ExplorationEngineTest {

    @After
    public void resetGlobals() throws Exception {
        WindowManager.setTempCurrentImage(null);
        Analyzer.setResultsTable(null);
        Analyzer.setMeasurements(0);
        Analyzer.setPrecision(3);
        RoiManager manager = RoiManager.getRawInstance();
        if (manager != null) {
            manager.reset();
            manager.close();
        }
        setRawRoiManager(null);
    }

    @Test
    public void successRestoresCurrentImageRoisResultsAndMeasurementPreferences()
            throws Exception {
        ImagePlus original = image("source", 101, 7);
        WindowManager.setTempCurrentImage(original);
        RoiManager manager = installManager();
        OvalRoi originalRoi = new OvalRoi(2, 0, 4, 1);
        originalRoi.setName("original-oval");
        manager.addRoi(originalRoi);
        ResultsTable originalResults = results("Original", 17.0);
        Analyzer.setResultsTable(originalResults);
        Analyzer.setMeasurements(37);
        Analyzer.setPrecision(5);

        FakeCommandEngine command = new FakeCommandEngine();
        ExplorationEngine engine = new ExplorationEngine(command);
        Map<String, String> variants = new LinkedHashMap<String, String>();
        variants.put("plain", "MAKE_NON_BINARY");
        ExplorationEngine.ExplorationReport report = engine.explore(variants);

        assertEquals(1, report.results.size());
        ExplorationEngine.ExplorationResult result = report.results.get(0);
        assertTrue(result.success);
        assertFalse(result.binaryMask);
        assertTrue(Double.isNaN(result.coverage));
        assertEquals("mean intensity", result.metricLabel);
        assertSame(original, WindowManager.getCurrentImage());
        assertEquals(37, Analyzer.getMeasurements());
        assertEquals(5, Analyzer.getPrecision());
        assertResults("Original", 17.0);
        assertEquals(1, manager.getCount());
        assertTrue(manager.getRoi(0) instanceof OvalRoi);
        assertEquals("original-oval", manager.getName(0));
        assertEquals(7, ((byte[]) original.getProcessor().getPixels())[0] & 0xff);
        assertEquals(1, command.targets.size());
        assertNotSame(original, command.targets.get(0));
    }

    @Test
    public void failureAlsoRestoresGlobalsAndDoesNotTouchPredictablyNamedUserImage()
            throws Exception {
        ImagePlus original = image("source", 20, 4);
        ImagePlus preExisting = image("explore_Otsu", 20, 55);
        WindowManager.setTempCurrentImage(original);
        RoiManager manager = installManager();
        Roi roi = new Roi(1, 0, 3, 1);
        roi.setName("keep-me");
        manager.addRoi(roi);
        Analyzer.setResultsTable(results("Keep", 3.0));
        Analyzer.setMeasurements(11);
        Analyzer.setPrecision(4);

        FakeCommandEngine command = new FakeCommandEngine();
        ExplorationEngine engine = new ExplorationEngine(command);
        Map<String, String> variants = new LinkedHashMap<String, String>();
        variants.put("Otsu", "FAIL_AFTER_GLOBAL_MUTATION");
        ExplorationEngine.ExplorationReport report = engine.explore(variants);

        assertFalse(report.results.get(0).success);
        assertSame(original, WindowManager.getCurrentImage());
        assertEquals(11, Analyzer.getMeasurements());
        assertEquals(4, Analyzer.getPrecision());
        assertResults("Keep", 3.0);
        assertEquals(1, manager.getCount());
        assertEquals("keep-me", manager.getName(0));
        assertEquals(55, ((byte[]) preExisting.getProcessor().getPixels())[0] & 0xff);
        assertFalse(command.targets.contains(preExisting));
        engine.cleanup();
        assertEquals(0, engine.trackedTemporaryCountForTest());
        assertEquals(55, ((byte[]) preExisting.getProcessor().getPixels())[0] & 0xff);
    }

    @Test
    public void thresholdRejectsSingleNonBinaryPixelAcrossFullPlane() {
        ImagePlus original = image("source", 1001, 0);
        WindowManager.setTempCurrentImage(original);
        FakeCommandEngine command = new FakeCommandEngine();
        command.makeThresholdNonBinary = true;

        ExplorationEngine.ExplorationResult result =
                new ExplorationEngine(command).exploreThresholds(
                        new String[]{"Otsu"}).results.get(0);

        assertFalse(result.success);
        assertFalse(result.binaryMask);
        assertTrue(Double.isNaN(result.coverage));
        assertTrue(result.summary.contains("not a complete 8-bit binary mask"));
    }

    @Test
    public void binaryCoverageCountsEveryPixelAndIsTruthfullyLabelled() {
        ImagePlus original = image("source", 10, 0);
        WindowManager.setTempCurrentImage(original);
        FakeCommandEngine command = new FakeCommandEngine();

        ExplorationEngine.ExplorationResult result =
                new ExplorationEngine(command).exploreThresholds(
                        new String[]{"Otsu"}).results.get(0);

        assertTrue(result.success);
        assertTrue(result.binaryMask);
        assertEquals(0.5, result.coverage, 0.0);
        assertEquals(0.5, result.metricValue, 0.0);
        assertEquals("binary coverage", result.metricLabel);
        assertEquals(2, result.objectCount);
    }

    @Test
    public void repeatedExplorationImmediatelyReleasesClosedTemporaryImages() {
        ImagePlus original = image("source", 12, 3);
        WindowManager.setTempCurrentImage(original);
        FakeCommandEngine command = new FakeCommandEngine();
        ExplorationEngine engine = new ExplorationEngine(command);
        Map<String, String> variants = new LinkedHashMap<String, String>();
        variants.put("plain", "MAKE_NON_BINARY");

        for (int attempt = 0; attempt < 25; attempt++) {
            ExplorationEngine.ExplorationReport report = engine.explore(variants);
            assertTrue(report.results.get(0).success);
            assertEquals("closed duplicate retained after attempt " + attempt,
                    0, engine.trackedTemporaryCountForTest());
            command.targets.clear();
        }

        assertSame(original, WindowManager.getCurrentImage());
    }

    private static final class FakeCommandEngine extends CommandEngine {
        final List<ImagePlus> targets = new java.util.ArrayList<ImagePlus>();
        boolean makeThresholdNonBinary;

        @Override
        ExecutionResult executeMacroOnImage(String code, ImagePlus image) {
            targets.add(image);
            WindowManager.setTempCurrentImage(image);
            mutateGlobals();
            if (code.contains("FAIL_AFTER_GLOBAL_MUTATION")) {
                return ExecutionResult.failure("deliberate failure", 1L);
            }
            if (code.contains("Analyze Particles")) {
                ResultsTable particles = new ResultsTable();
                particles.incrementCounter();
                particles.addValue("Area", 2.0);
                particles.addValue("Circ.", 0.5);
                particles.incrementCounter();
                particles.addValue("Area", 4.0);
                particles.addValue("Circ.", 0.7);
                Analyzer.setResultsTable(particles);
                return success();
            }

            byte[] pixels = (byte[]) image.getProcessor().getPixels();
            if (code.contains("setAutoThreshold")) {
                java.util.Arrays.fill(pixels, (byte) 0);
                for (int i = 0; i < pixels.length / 2; i++) pixels[i] = (byte) 255;
                if (makeThresholdNonBinary) pixels[pixels.length - 1] = 1;
            } else {
                for (int i = 0; i < pixels.length; i++) pixels[i] = (byte) (i % 23);
            }
            return success();
        }

        private void mutateGlobals() {
            Analyzer.setMeasurements(999);
            Analyzer.setPrecision(9);
            Analyzer.setResultsTable(results("Changed", 999.0));
            RoiManager manager = RoiManager.getRawInstance();
            if (manager != null) {
                manager.reset();
                Roi changed = new Roi(0, 0, 1, 1);
                changed.setName("changed");
                manager.addRoi(changed);
            }
        }

        private ExecutionResult success() {
            return ExecutionResult.success("", null,
                    Collections.<String>emptyList(), 1L);
        }
    }

    private static ImagePlus image(String title, int width, int fill) {
        byte[] pixels = new byte[width];
        java.util.Arrays.fill(pixels, (byte) fill);
        return new ImagePlus(title, new ByteProcessor(width, 1, pixels, null));
    }

    private static ResultsTable results(String label, double value) {
        ResultsTable table = new ResultsTable();
        table.incrementCounter();
        table.addLabel(label);
        table.addValue("Value", value);
        return table;
    }

    private static void assertResults(String label, double value) {
        ResultsTable table = Analyzer.getResultsTable();
        assertEquals(1, table.getCounter());
        assertEquals(label, table.getLabel(0));
        assertEquals(value, table.getValue("Value", 0), 0.0);
    }

    private static RoiManager installManager() throws Exception {
        RoiManager manager = new RoiManager(true);
        setRawRoiManager(manager);
        return manager;
    }

    private static void setRawRoiManager(RoiManager manager) throws Exception {
        Field instance = RoiManager.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, manager);
    }
}
