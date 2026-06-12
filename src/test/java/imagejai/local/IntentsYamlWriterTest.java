package imagejai.local;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.assertTrue;

public class IntentsYamlWriterTest {

    @Test
    public void addAliasPreservesCommentsIntentOrderAndIndentation() throws Exception {
        Path yaml = Files.createTempFile("imagejai-intents", ".yaml");
        write(yaml,
                "# curated intents\n"
                        + "- id: image.stack_counts\n"
                        + "  description: Count stack axes\n"
                        + "  seeds: [\"how many channels\", \"number of z slices\"]\n"
                        + "\n"
                        + "# keep this comment\n"
                        + "- id: diagnostics.plugins\n"
                        + "  description: List plugins\n"
                        + "  seeds:\n"
                        + "    - \"list commands\"\n");

        new IntentsYamlWriter().addAlias(yaml, "image.stack_counts", "how many frames");

        String updated = read(yaml);
        assertTrue(updated.contains("# curated intents"));
        assertTrue(updated.contains("# keep this comment"));
        assertTrue(updated.indexOf("image.stack_counts") < updated.indexOf("diagnostics.plugins"));
        assertTrue(updated.contains("  seeds: [\"how many channels\", \"number of z slices\", \"how many frames\"]"));
    }

    @Test
    public void addNewIntentQuotesUserContentAndAppendsBlock() {
        String updated = new IntentsYamlWriter().addNewIntent(
                "- id: builtin.help\n"
                        + "  description: Help\n"
                        + "  seeds: [\"help\"]\n",
                "user.count.potato",
                "Count things: including # symbols",
                Arrays.asList("potato salad", "multi\nline"));

        assertTrue(updated.contains("- id: user.count.potato"));
        assertTrue(updated.contains("description: \"Count things: including # symbols\""));
        assertTrue(updated.contains("    - \"potato salad\""));
        assertTrue(updated.contains("    - \"multi\\nline\""));
    }

    @Test
    public void diffShowsUnifiedAddedSeed() throws Exception {
        Path yaml = Files.createTempFile("imagejai-intents", ".yaml");
        write(yaml,
                "- id: image.stack_counts\n"
                        + "  description: Count stack axes\n"
                        + "  seeds: [\"how many channels\"]\n");

        String diff = new IntentsYamlWriter().diff(yaml, Arrays.asList(
                IntentsYamlWriter.Change.alias("image.stack_counts", "how many frames")));

        assertTrue(diff.contains("--- "));
        assertTrue(diff.contains("+++ "));
        assertTrue(diff.contains("+  seeds: [\"how many channels\", \"how many frames\"]"));
    }

    private static void write(Path path, String text) throws Exception {
        Files.write(path, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
