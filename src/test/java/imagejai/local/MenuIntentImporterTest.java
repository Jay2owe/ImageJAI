package imagejai.local;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import imagejai.config.Settings;
import imagejai.engine.CommandEngine;
import imagejai.engine.FrictionLog;
import imagejai.engine.MenuCommandRegistry;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class MenuIntentImporterTest {

    @Before
    public void setUp() {
        MenuCommandRegistry.setForTesting(new HashMap<String, String>());
    }

    @After
    public void tearDown() {
        MenuCommandRegistry.setForTesting(new HashMap<String, String>());
    }

    @Test
    public void importsMenuRegistryCommandsAsSyntheticIntents() {
        MenuCommandRegistry.setForTesting(sampleRegistry());

        IntentLibrary library = IntentLibrary.load();

        assertNotNull(library.byId("menu.fft"));
        assertNotNull(library.byId("menu.properties"));
        assertNotNull(library.byId("menu.bio.formats.importer"));
        assertEquals(10, countMenuIntents(library));
    }

    @Test
    public void localAssistantRunsCanonicalMenuCommandMacro() {
        MenuCommandRegistry.setForTesting(sampleRegistry());
        Settings settings = new Settings();
        settings.expandMenuPhrasebook = true;
        IntentLibrary library = IntentLibrary.load(settings);
        RecordingFijiBridge fiji = new RecordingFijiBridge();
        LocalAssistant assistant = new LocalAssistant(
                library,
                new IntentMatcher(library, settings),
                fiji,
                new FrictionLog());

        AssistantReply fft = assistant.handle("FFT");
        assertEquals("run(\"FFT\");", fiji.lastMacro);
        assertEquals("run(\"FFT\");", fft.macroEcho());
        assertEquals("Opened: FFT", fft.text());

        AssistantReply properties = assistant.handle("Properties...");
        assertEquals("run(\"Properties...\");", fiji.lastMacro);
        assertEquals("run(\"Properties...\");", properties.macroEcho());
        assertEquals("Opened: Properties...", properties.text());
    }

    @Test
    public void importerDoesNotShadowExistingHandWrittenMenuIntentId() {
        MenuCommandRegistry.setForTesting(new HashMap<String, String>());
        IntentLibrary library = IntentLibrary.load();
        library.register(new StubIntent("menu.fft"));

        int added = MenuIntentImporter.importInto(library, Arrays.asList("FFT"), true);

        assertEquals(0, added);
        assertEquals("hand-written", library.byId("menu.fft").description());
    }

    @Test
    public void quotesInMenuCommandsAreEscapedInMacroEcho() {
        IntentLibrary library = IntentLibrary.load();
        MenuIntentImporter.importInto(library, Arrays.asList("Say \"Hi\"..."), true);
        RecordingFijiBridge fiji = new RecordingFijiBridge();
        LocalAssistant assistant = new LocalAssistant(
                library,
                new IntentMatcher(library),
                fiji,
                new FrictionLog());

        AssistantReply reply = assistant.handle("Say Hi");

        assertEquals("run(\"Say \\\"Hi\\\"...\");", fiji.lastMacro);
        assertEquals("run(\"Say \\\"Hi\\\"...\");", reply.macroEcho());
    }

    private static Map<String, String> sampleRegistry() {
        Map<String, String> commands = new HashMap<String, String>();
        commands.put("FFT", "ij.plugin.FFT");
        commands.put("Properties...", "ij.plugin.Commands");
        commands.put("Threshold...", "ij.plugin.frame.ThresholdAdjuster");
        commands.put("Analyze Particles...", "ij.plugin.filter.ParticleAnalyzer");
        commands.put("Bio-Formats Importer", "loci.plugins.LociImporter");
        commands.put("StarDist 2D", "de.csbdresden.stardist.StarDist2D");
        commands.put("TrackMate", "fiji.plugin.trackmate.TrackMatePlugIn");
        commands.put("Gaussian Blur...", "ij.plugin.filter.GaussianBlur");
        commands.put("ROI Manager...", "ij.plugin.frame.RoiManager");
        commands.put("Find Maxima...", "ij.plugin.filter.MaximumFinder");
        return commands;
    }

    private static int countMenuIntents(IntentLibrary library) {
        int count = 0;
        for (Intent intent : library.all()) {
            if (intent.id().startsWith("menu.")) {
                count++;
            }
        }
        return count;
    }

    private static class RecordingFijiBridge extends FijiBridge {
        String lastMacro;

        RecordingFijiBridge() {
            super(new CommandEngine());
        }

        @Override
        public void runMacro(String code) {
            lastMacro = code;
        }
    }

    private static class StubIntent implements Intent {
        private final String id;

        StubIntent(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public String description() {
            return "hand-written";
        }

        public AssistantReply execute(Map<String, String> slots, FijiBridge fiji) {
            return AssistantReply.text("hand-written");
        }
    }
}
