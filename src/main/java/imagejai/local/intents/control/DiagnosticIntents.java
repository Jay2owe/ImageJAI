package imagejai.local.intents.control;

import ij.IJ;
import ij.ImagePlus;
import ij.Menus;
import ij.WindowManager;
import ij.plugin.frame.RoiManager;
import imagejai.engine.ConsoleCapture;
import imagejai.local.AssistantReply;
import imagejai.local.FijiBridge;

import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Window;
import java.util.Hashtable;
import java.util.Map;

class PluginCountIntent extends AbstractControlIntent {
    public String id() { return "diagnostics.plugins"; }
    public String description() { return "Report Fiji command count"; }
    protected boolean requiresImage() { return false; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        Hashtable commands = Menus.getCommands();
        int count = commands == null ? 0 : commands.keySet().size();
        return AssistantReply.text("Fiji has " + count
                + " menu commands registered. Ask for commands to see the Local Assistant command surface.");
    }
}

class OpenMacroRecorderIntent extends AbstractControlIntent {
    public String id() { return "diagnostics.open_recorder"; }
    public String description() { return "Open the macro recorder"; }
    protected boolean requiresImage() { return false; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        IJ.run("Record...");
        return AssistantReply.withMacro("Opened the macro recorder.", "run(\"Record...\");");
    }
}

class OpenRoiManagerIntent extends AbstractControlIntent {
    public String id() { return "diagnostics.open_roi_manager"; }
    public String description() { return "Open the ROI Manager"; }
    protected boolean requiresImage() { return false; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        RoiManager.getRoiManager();
        return AssistantReply.withMacro("Opened the ROI Manager.", "run(\"ROI Manager...\");");
    }
}

class OpenChannelsToolIntent extends AbstractControlIntent {
    public String id() { return "diagnostics.open_channels_tool"; }
    public String description() { return "Open the Channels Tool"; }
    protected boolean requiresImage() { return false; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        IJ.run("Channels Tool...");
        return AssistantReply.withMacro("Opened the Channels Tool.", "run(\"Channels Tool...\");");
    }
}

class ShowLogIntent extends AbstractControlIntent {
    public String id() { return "diagnostics.show_log"; }
    public String description() { return "Show the ImageJ Log"; }
    protected boolean requiresImage() { return false; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        IJ.log("");
        Frame log = WindowManager.getFrame("Log");
        if (log != null) {
            log.setVisible(true);
            log.toFront();
        }
        return AssistantReply.text("Showing the ImageJ Log window.");
    }
}

class ShowConsoleIntent extends AbstractControlIntent {
    public String id() { return "diagnostics.show_console"; }
    public String description() { return "Show recent Fiji console output"; }
    protected boolean requiresImage() { return false; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        String stdout = ConsoleCapture.tailStdout(1000);
        String stderr = ConsoleCapture.tailStderr(1000);
        if ((stdout == null || stdout.length() == 0) && (stderr == null || stderr.length() == 0)) {
            return AssistantReply.text("No captured console output is available.");
        }
        StringBuilder sb = new StringBuilder("Recent console output:");
        if (stdout != null && stdout.length() > 0) {
            sb.append("\nstdout:\n").append(stdout.trim());
        }
        if (stderr != null && stderr.length() > 0) {
            sb.append("\nstderr:\n").append(stderr.trim());
        }
        return AssistantReply.text(sb.toString());
    }
}

class OpenDialogsIntent extends AbstractControlIntent {
    public String id() { return "diagnostics.open_dialogs"; }
    public String description() { return "List open dialogs"; }
    protected boolean requiresImage() { return false; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        Window[] windows = WindowManager.getAllNonImageWindows();
        StringBuilder sb = new StringBuilder();
        int count = 0;
        if (windows != null) {
            for (Window window : windows) {
                if (window instanceof Dialog && window.isShowing()) {
                    Dialog dialog = (Dialog) window;
                    if (count == 0) {
                        sb.append("Open dialogs:");
                    }
                    count++;
                    sb.append("\n").append(count).append(". ").append(dialog.getTitle());
                }
            }
        }
        if (count == 0) {
            return AssistantReply.text("No open dialogs detected.");
        }
        return AssistantReply.text(sb.toString());
    }
}

class MemoryUsedIntent extends AbstractControlIntent {
    public String id() { return "diagnostics.memory"; }
    public String description() { return "Report memory usage"; }
    protected boolean requiresImage() { return false; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        return AssistantReply.text("Memory: " + IJ.freeMemory() + ".");
    }
}

class GarbageCollectIntent extends AbstractControlIntent {
    public String id() { return "diagnostics.garbage_collect"; }
    public String description() { return "Run garbage collection"; }
    protected boolean requiresImage() { return false; }
    protected AssistantReply executeChecked(Map<String, String> slots, FijiBridge fiji, ImagePlus imp) {
        System.gc();
        IJ.showStatus("Garbage collection requested");
        return AssistantReply.withMacro("Garbage collection requested. " + IJ.freeMemory() + ".",
                "run(\"Collect Garbage\");");
    }
}
