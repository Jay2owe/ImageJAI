package imagejai.local;

import org.junit.Test;
import imagejai.engine.CommandEngine;
import imagejai.engine.FrictionLog;
import imagejai.engine.IntentRouter;
import imagejai.local.slash.CloseSlashCommand;
import imagejai.local.slash.InfoSlashCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SlashCommandStage08Test {

    @Test
    public void slashDispatchTakesPrecedenceAndNormaliseKeepsSlash() throws Exception {
        Path store = Files.createTempFile("imagejai-intents", ".json");
        Files.deleteIfExists(store);
        LocalAssistant assistant = assistantWith(new RecordingFijiBridge(), new IntentRouter(store), null);

        AssistantReply reply = assistant.handle("/help");

        assertTrue(reply.text().contains("Slash commands:"));
        assertEquals("/help", IntentMatcher.normalise("/help"));
    }

    @Test
    public void teachRoundTripRunsMacroAndForgetRemovesLiteralPhrase() throws Exception {
        Path store = Files.createTempFile("imagejai-intents", ".json");
        Files.deleteIfExists(store);
        IntentRouter router = new IntentRouter(store);
        RecordingFijiBridge fiji = new RecordingFijiBridge();
        LocalAssistant assistant = assistantWith(fiji, router, null);

        AssistantReply taught = assistant.handle("/teach hello world => print(\"hello world\");");
        assertTrue(taught.text().contains("Parsed regex: \\Qhello world\\E"));

        AssistantReply ran = assistant.handle("hello world");
        assertEquals("print(\"hello world\");", fiji.lastMacro);
        assertTrue(ran.text().contains("Ran user-taught intent"));

        AssistantReply listed = assistant.handle("/intents");
        assertTrue(listed.text().contains("\\Qhello world\\E"));
        assertTrue(listed.text().contains("| 1 |"));

        AssistantReply forgot = assistant.handle("/forget hello world");
        assertTrue(forgot.text().contains("Forgot"));
        assertFalse(assistant.handle("/intents").text().contains("\\Qhello world\\E"));
    }

    @Test
    public void infoFormatsOneRowPerOpenImageFromProvider() {
        InfoSlashCommand command = new InfoSlashCommand(new InfoSlashCommand.OpenImageProvider() {
            public List<InfoSlashCommand.OpenImageInfo> openImages() {
                return Arrays.asList(
                        new InfoSlashCommand.OpenImageInfo("A", 10, 20, 1, 1, 1,
                                8, "0.5 x 0.5 um", "C:\\data\\a.tif", "no"),
                        new InfoSlashCommand.OpenImageInfo("B", 30, 40, 2, 3, 4,
                                16, "1.0 x 1.0 pixels", "", "yes"));
            }
        });

        String text = command.execute(new SlashCommandContext("", null, null, null, null)).text();

        assertTrue(text.contains("Title | Dimensions | Bit depth | Pixel size | File path | Saturated?"));
        assertTrue(text.contains("A | 10 x 20 x 1 x 1 x 1 | 8-bit | 0.5 x 0.5 um | C:\\data\\a.tif | no"));
        assertTrue(text.contains("B | 30 x 40 x 2 x 3 x 4 | 16-bit"));
    }

    @Test
    public void closeAllSkipsLogWindow() {
        FakeCloseProvider provider = new FakeCloseProvider();
        provider.targets.add(new CloseSlashCommand.CloseTarget(1, "Cells"));
        provider.targets.add(new CloseSlashCommand.CloseTarget(2, "Log"));
        provider.targets.add(new CloseSlashCommand.CloseTarget(3, "Nuclei"));

        String text = CloseSlashCommand.close("all", provider);

        assertEquals("Closed 2 images.", text);
        assertEquals(Arrays.asList(1, 3), provider.closed);
    }

    private static LocalAssistant assistantWith(FijiBridge fiji, IntentRouter router,
                                                ChatHistoryController history) {
        IntentLibrary library = IntentLibrary.load();
        return new LocalAssistant(library, new IntentMatcher(library), fiji,
                new FrictionLog(), router, history);
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

    private static class FakeCloseProvider implements CloseSlashCommand.CloseProvider {
        final List<CloseSlashCommand.CloseTarget> targets = new ArrayList<CloseSlashCommand.CloseTarget>();
        final List<Integer> closed = new ArrayList<Integer>();

        public List<CloseSlashCommand.CloseTarget> targets() {
            return targets;
        }

        public int activeId() {
            return 1;
        }

        public boolean close(int id) {
            closed.add(id);
            return true;
        }
    }
}
