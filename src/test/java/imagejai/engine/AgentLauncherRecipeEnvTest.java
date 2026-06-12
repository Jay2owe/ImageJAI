package imagejai.engine;

import imagejai.config.Settings;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class AgentLauncherRecipeEnvTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void embeddedLaunchExportsRecipeDirectories() throws Exception {
        Path agent = tmp.newFolder("agent").toPath();
        AgentLauncher launcher = new AgentLauncher(
                agent.toString(), 7746, new Settings());
        AgentLauncher.AgentInfo info = new AgentLauncher.AgentInfo(
                "Dummy", "dummy", "test", null, "");

        AgentLaunchSpec spec = launcher.buildEmbeddedLaunchSpec(info);

        String userRecipes = RecipePaths.userRecipesDir()
                .toAbsolutePath().normalize().toString();
        String bundledRecipes = agent.resolve("recipes")
                .toAbsolutePath().normalize().toString();
        assertEquals(userRecipes, spec.env.get(RecipePaths.USER_RECIPES_ENV));
        assertArrayEquals(
                new String[] { userRecipes, bundledRecipes },
                spec.env.get(RecipePaths.RECIPE_DIRS_ENV)
                        .split(Pattern.quote(java.io.File.pathSeparator)));
    }
}
