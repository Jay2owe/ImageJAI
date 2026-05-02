package imagejai.local;

import ij.ImagePlus;
import ij.WindowManager;
import ij.io.FileInfo;
import ij.measure.ResultsTable;
import ij.plugin.frame.RoiManager;
import imagejai.engine.CommandEngine;
import imagejai.engine.ExecutionResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Facade for direct Fiji/ImageJ access used by deterministic local intents.
 */
public class FijiBridge {

    private final CommandEngine commandEngine;

    public FijiBridge(CommandEngine commandEngine) {
        this.commandEngine = commandEngine;
    }

    public ImagePlus requireOpenImage() {
        return WindowManager.getCurrentImage();
    }

    public Path resolveAiExportsDir() {
        ImagePlus imp = requireOpenImage();
        if (imp == null) {
            throw new IllegalStateException("No image is open.");
        }
        FileInfo fi = imp.getOriginalFileInfo();
        if (fi == null || fi.directory == null || fi.directory.trim().length() == 0) {
            throw new IllegalStateException("The active image has no file-backed directory.");
        }
        Path dir = Paths.get(fi.directory).resolve("AI_Exports");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create AI_Exports directory: " + e.getMessage(), e);
        }
        return dir;
    }

    public void runMacro(String code) {
        ExecutionResult result = commandEngine.executeMacro(code);
        if (!result.isSuccess()) {
            throw new IllegalStateException(result.getError());
        }
    }

    public ResultsTable currentResults() {
        return ResultsTable.getResultsTable();
    }

    public RoiManager currentRoiManager() {
        return RoiManager.getInstance();
    }
}
