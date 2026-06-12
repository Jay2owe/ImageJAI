package imagejai.local.slash;

import ij.ImagePlus;
import ij.WindowManager;
import imagejai.local.AssistantReply;
import imagejai.local.SlashCommand;
import imagejai.local.SlashCommandContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class CloseSlashCommand implements SlashCommand {
    private final CloseProvider provider;

    public CloseSlashCommand() {
        this(new WindowManagerCloseProvider());
    }

    public CloseSlashCommand(CloseProvider provider) {
        this.provider = provider;
    }

    public String name() { return "close"; }
    public String intentId() { return "slash.close"; }
    public String description() { return "Close image windows while preserving the Log window"; }

    public AssistantReply execute(SlashCommandContext context) {
        return AssistantReply.text(close(context.args(), provider));
    }

    public static String close(String args, CloseProvider provider) {
        String mode = args == null ? "" : args.trim();
        if (provider == null) {
            return "Image windows are not available.";
        }
        List<CloseTarget> targets = provider.targets();
        if (targets == null || targets.isEmpty()) {
            return "No images are open.";
        }

        int activeId = provider.activeId();
        int closed = 0;
        String lower = mode.toLowerCase(Locale.ROOT);
        for (CloseTarget target : targets) {
            if (target == null || isLogWindow(target.title)) {
                continue;
            }
            boolean shouldClose;
            if (mode.length() == 0) {
                shouldClose = target.id == activeId;
            } else if ("all".equals(lower)) {
                shouldClose = true;
            } else if ("all but active".equals(lower) || "all except active".equals(lower)) {
                shouldClose = target.id != activeId;
            } else {
                shouldClose = target.title != null && target.title.toLowerCase(Locale.ROOT).contains(lower);
            }
            if (shouldClose && provider.close(target.id)) {
                closed++;
            }
        }

        if (closed == 0) {
            return mode.length() == 0 ? "No active image was closed." : "No matching images were closed.";
        }
        return "Closed " + closed + " image" + (closed == 1 ? "" : "s") + ".";
    }

    static boolean isLogWindow(String title) {
        return title != null && "log".equals(title.trim().toLowerCase(Locale.ROOT));
    }

    public interface CloseProvider {
        List<CloseTarget> targets();
        int activeId();
        boolean close(int id);
    }

    public static class CloseTarget {
        public final int id;
        public final String title;

        public CloseTarget(int id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    static class WindowManagerCloseProvider implements CloseProvider {
        public List<CloseTarget> targets() {
            List<CloseTarget> out = new ArrayList<CloseTarget>();
            int[] ids = WindowManager.getIDList();
            if (ids == null) {
                return out;
            }
            for (int id : ids) {
                ImagePlus imp = WindowManager.getImage(id);
                if (imp != null) {
                    out.add(new CloseTarget(id, imp.getTitle()));
                }
            }
            return out;
        }

        public int activeId() {
            ImagePlus imp = WindowManager.getCurrentImage();
            return imp == null ? Integer.MIN_VALUE : imp.getID();
        }

        public boolean close(int id) {
            ImagePlus imp = WindowManager.getImage(id);
            if (imp == null) {
                return false;
            }
            imp.close();
            return true;
        }
    }
}
