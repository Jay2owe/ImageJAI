package imagejai.engine;

import ij.IJ;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Shared recipe paths for Java launchers and Python agent environment.
 */
public final class RecipePaths {
    public static final String IMAGEJAI_DIR = "ImageJAI";
    public static final String RECIPES_DIR = "recipes";
    public static final String USER_RECIPES_ENV = "IMAGEJAI_USER_RECIPES_DIR";
    public static final String RECIPE_DIRS_ENV = "IMAGEJAI_RECIPE_DIRS";

    private RecipePaths() {}

    public static Path userRecipesDir() {
        Path root = imageJRoot();
        if (root != null) {
            return userRecipesDirForRoot(root);
        }
        String home = System.getProperty("user.home");
        if (home == null || home.trim().isEmpty()) {
            home = ".";
        }
        return Paths.get(home).resolve(IMAGEJAI_DIR).resolve(RECIPES_DIR);
    }

    public static Path userRecipesDirForRoot(Path imageJRoot) {
        return imageJRoot.resolve(IMAGEJAI_DIR).resolve(RECIPES_DIR);
    }

    public static Path imageJRoot() {
        String imagej = directory("imagej");
        if (imagej != null) {
            return Paths.get(imagej);
        }
        String macros = directory("macros");
        if (macros != null) {
            Path parent = Paths.get(macros).getParent();
            if (parent != null) {
                return parent;
            }
        }
        return null;
    }

    public static String pathList(List<Path> dirs) {
        StringBuilder sb = new StringBuilder();
        if (dirs == null) {
            return "";
        }
        for (Path dir : dirs) {
            if (dir == null) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(File.pathSeparator);
            }
            sb.append(dir.toAbsolutePath().normalize().toString());
        }
        return sb.toString();
    }

    private static String directory(String key) {
        try {
            String dir = IJ.getDirectory(key);
            return dir == null || dir.trim().isEmpty() ? null : dir;
        } catch (Throwable t) {
            return null;
        }
    }
}
