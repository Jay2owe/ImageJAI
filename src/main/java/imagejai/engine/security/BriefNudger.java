package imagejai.engine.security;

import imagejai.engine.AgentSession;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.util.List;
import java.util.Map;

/**
 * Agent-agnostic delivery nudge for pending browse briefs.
 */
public final class BriefNudger {
    public enum NudgeMechanism {
        CLIPBOARD,
        TCP_POLLING
    }

    public interface ClipboardWriter {
        void write(String text) throws Exception;
    }

    public interface Toast {
        void show(String message);
    }

    private final ClipboardWriter clipboardWriter;
    private final Toast toast;

    public BriefNudger() {
        this(new ClipboardWriter() {
            @Override
            public void write(String text) {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(text), null);
            }
        }, new Toast() {
            @Override
            public void show(String message) {
                // UI callers may supply a non-modal toast. The default remains
                // quiet so engine tests and headless runs are safe.
            }
        });
    }

    public BriefNudger(ClipboardWriter clipboardWriter, Toast toast) {
        this.clipboardWriter = clipboardWriter == null ? new ClipboardWriter() {
            @Override
            public void write(String text) {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(text), null);
            }
        } : clipboardWriter;
        this.toast = toast == null ? new Toast() {
            @Override public void show(String message) { }
        } : toast;
    }

    public void deliver(Brief brief, NudgeMechanism mechanism, AgentSession session) {
        if (brief == null || mechanism == null) {
            return;
        }
        String text = composeNudge(brief);
        if (mechanism == NudgeMechanism.TCP_POLLING) {
            return;
        }
        try {
            clipboardWriter.write(text);
            toast.show("Selection ready - Ctrl+V into your agent terminal.");
        } catch (Exception e) {
            toast.show("Could not copy selection nudge to clipboard: " + e.getMessage());
        }
    }

    public String composeNudge(Brief brief) {
        StringBuilder sb = new StringBuilder();
        List<String> tokens = brief.tokens();
        sb.append("I've selected ").append(tokens.size())
                .append(tokens.size() == 1 ? " image/series" : " images/series")
                .append(" for analysis: ");
        for (int i = 0; i < tokens.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(tokens.get(i));
        }
        if (!brief.tag().isEmpty()) {
            sb.append(" (tagged \"").append(brief.tag()).append("\"");
            String metadata = metadataSummary(brief.metadata());
            if (!metadata.isEmpty()) {
                sb.append(", ").append(metadata);
            }
            sb.append(')');
        }
        sb.append(". Please call get_pending_brief for details.");
        return sb.toString();
    }

    private static String metadataSummary(Map<String, Object> metadata) {
        if (metadata == null) {
            return "";
        }
        Object channels = metadata.get("channels");
        Object dimensions = metadata.get("dimensions");
        StringBuilder out = new StringBuilder();
        if (channels instanceof Number && ((Number) channels).intValue() > 0) {
            out.append(((Number) channels).intValue()).append("-channel");
        }
        if (dimensions != null && !dimensions.toString().trim().isEmpty()) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(dimensions.toString().trim());
        }
        return out.toString();
    }
}
