package imagejai.engine.security;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class TagSuggestionEngineTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void defaultsExtractCommonBioimagingTokens() {
        TagSuggestionEngine engine = new TagSuggestionEngine();

        String tag = engine.suggest(Arrays.asList(
                "wt_8w_male_001",
                "wt_8w_male_002",
                "wt_8w_female_011"));
        Map<String, String> parsed = engine.parseLabel("ko_4w_f_003_control");

        assertEquals("8 weeks, wild-type", tag);
        assertEquals("4 weeks", parsed.get("timepoint"));
        assertEquals("knockout", parsed.get("genotype"));
        assertEquals("female", parsed.get("sex"));
        assertEquals("control", parsed.get("condition"));
    }

    @Test
    public void folderOverrideRulesAreLocalOnlyInputs() throws Exception {
        Path folder = temp.newFolder("study").toPath();
        Path override = folder.resolve(".imagejai-tags.yml");
        Files.write(override, Arrays.asList(
                "patterns:",
                "  - name: condition",
                "    pattern: '(SCN)(\\d+)'",
                "    format: '{1}-{2}'"),
                StandardCharsets.UTF_8);
        TagSuggestionEngine engine = new TagSuggestionEngine();

        String tag = engine.suggest(Arrays.asList("SCN42_cell_a", "SCN42_cell_b"),
                folder);

        assertEquals("SCN-42", tag);
        assertFalse("override contents must not be returned as a tag",
                tag.contains("pattern"));
    }
}
