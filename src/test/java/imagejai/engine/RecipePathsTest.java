package imagejai.engine;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RecipePathsTest {
    @Test
    public void userRecipesDirLivesUnderFijiImageJaiFolder() {
        Path root = Paths.get("C:", "Fiji.app");

        Path recipes = RecipePaths.userRecipesDirForRoot(root);

        assertEquals(root.resolve("ImageJAI").resolve("recipes"), recipes);
    }

    @Test
    public void pathListUsesPlatformSeparatorAndAbsolutePaths() {
        Path a = Paths.get("agent").resolve("recipes");
        Path b = Paths.get("Fiji.app").resolve("ImageJAI").resolve("recipes");

        String value = RecipePaths.pathList(Arrays.asList(a, b));

        assertTrue(value.contains(a.toAbsolutePath().normalize().toString()));
        assertTrue(value.contains(b.toAbsolutePath().normalize().toString()));
        assertTrue(value.contains(java.io.File.pathSeparator));
    }
}
