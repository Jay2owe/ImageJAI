package imagejai.ui;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MacroLibraryTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void userScanRecursesImageJRootAndSkipsImageJaiSavedFolder() throws Exception {
        Path root = tmp.newFolder("Fiji.app").toPath();
        write(root.resolve("macros").resolve("cell_count.ijm"));
        write(root.resolve("scripts").resolve("Toolsets").resolve("roi_tools.ijm"));
        write(root.resolve("ImageJAI").resolve("macros").resolve("saved_by_ai.ijm"));
        write(root.resolve("scripts").resolve("ignore.groovy"));

        List<MacroLibrary.MacroItem> items = MacroLibrary.scanUserMacros(
                root, root.resolve("ImageJAI").resolve("macros"));

        Set<String> names = names(items);
        assertTrue(names.contains("cell_count.ijm"));
        assertTrue(names.contains("roi_tools.ijm"));
        assertFalse(names.contains("saved_by_ai.ijm"));
        assertFalse(names.contains("ignore.groovy"));
    }

    @Test
    public void savedScanIncludesScriptsAndLegacyDirectories() throws Exception {
        Path saved = tmp.newFolder("saved").toPath();
        Path legacy = tmp.newFolder("legacy").toPath();
        write(saved.resolve("segment.groovy"));
        write(saved.resolve("measure.py"));
        write(legacy.resolve("old_macro.ijm"));
        write(saved.resolve("notes.md"));

        List<MacroLibrary.MacroItem> items = MacroLibrary.scanSavedMacros(
                Arrays.asList(saved, legacy));

        Set<String> names = names(items);
        assertTrue(names.contains("segment.groovy"));
        assertTrue(names.contains("measure.py"));
        assertTrue(names.contains("old_macro.ijm"));
        assertFalse(names.contains("notes.md"));
        assertEquals("groovy", find(items, "segment.groovy").language);
        assertEquals("jython", find(items, "measure.py").language);
    }

    @Test
    public void defaultExtensionsFollowScriptLanguage() {
        assertEquals("groovy", MacroLibrary.extensionForLanguage("groovy"));
        assertEquals("py", MacroLibrary.extensionForLanguage("python"));
        assertEquals("js", MacroLibrary.extensionForLanguage("javascript"));
        assertEquals("ijm", MacroLibrary.extensionForLanguage("ijm"));
    }

    private static void write(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        Files.write(path, "print(\"ok\");\n".getBytes(StandardCharsets.UTF_8));
    }

    private static Set<String> names(List<MacroLibrary.MacroItem> items) {
        Set<String> names = new HashSet<String>();
        for (MacroLibrary.MacroItem item : items) {
            names.add(item.name);
        }
        return names;
    }

    private static MacroLibrary.MacroItem find(List<MacroLibrary.MacroItem> items, String name) {
        for (MacroLibrary.MacroItem item : items) {
            if (name.equals(item.name)) {
                return item;
            }
        }
        throw new AssertionError("Missing " + name);
    }
}
