package imagejai.engine;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class ScriptGeneratorTest {

    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void installRecomputesSafeContainedUtf8FileName() throws Exception {
        ScriptGenerator generator = new ScriptGenerator(null);
        ScriptGenerator.GeneratedScript script = new ScriptGenerator.GeneratedScript();
        script.name = "../outside\r\nInjected";
        script.fileName = "../../escape.groovy\r\nforged";
        script.language = ScriptGenerator.ScriptLanguage.GROOVY;
        script.content = "println 'μ'\n";
        Path root = temp.newFolder("Fiji.app").toPath().toAbsolutePath().normalize();

        String installed = generator.installScript(script, root.toString());

        Path target = java.nio.file.Paths.get(installed).toAbsolutePath().normalize();
        Path expectedParent = root.resolve("scripts/Plugins/AI_Generated").normalize();
        assertEquals(expectedParent, target.getParent());
        assertEquals("outside_Injected.groovy", target.getFileName().toString());
        assertEquals(script.content,
                new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        assertFalse(Files.exists(root.getParent().resolve("escape.groovy")));
    }

    @Test
    public void generatedFileNameDoesNotDriftUnderTurkishLocale() {
        Locale original = Locale.getDefault();
        try {
            PipelineBuilder.Pipeline pipeline = new PipelineBuilder.Pipeline(
                    "IMAGE PIPELINE", Arrays.asList(
                            new PipelineBuilder.PipelineStep(1, "step", "run(\"Invert\");")));
            ScriptGenerator generator = new ScriptGenerator(null);
            Locale.setDefault(Locale.US);
            String baseline = generator.pipelineToScript(
                    pipeline, ScriptGenerator.ScriptLanguage.MACRO).fileName;
            Locale.setDefault(new Locale("tr", "TR"));
            String turkish = generator.pipelineToScript(
                    pipeline, ScriptGenerator.ScriptLanguage.MACRO).fileName;

            assertEquals("IMAGE_PIPELINE.ijm", baseline);
            assertEquals(baseline, turkish);
        } finally {
            Locale.setDefault(original);
        }
    }
}
