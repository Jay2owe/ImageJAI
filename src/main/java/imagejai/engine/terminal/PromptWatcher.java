package imagejai.engine.terminal;

import ij.IJ;

import javax.swing.Timer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Polls visible terminal output for approval prompts and URLs.
 */
public final class PromptWatcher {
    public interface ScrollbackReader {
        String readScrollback(int lineLimit);
    }

    public interface Listener {
        void onAutoConfirm(String promptText);
        void onEscalate(String promptText);
        void onPending(String promptText);
        void onPromptCleared();
        void onUrlSeen(String url);
    }

    private static final int POLL_MS = 250;
    private static final int TAIL_LINES = 20;
    private static final Pattern PROMPT_TRAILER =
            Pattern.compile("(?is).*(\\?|:|\\[y/n])\\s*$");
    private static final Pattern URL =
            Pattern.compile("(https?://\\S+|file://\\S+)");

    private final ScrollbackReader scrollbackReader;
    private final ApprovalPolicy policy;
    private final RawWriter writer;
    private final Listener listener;
    private final Timer timer;

    private String lastTail = "";
    private String lastPrompt = "";
    private String lastUrl = "";

    public PromptWatcher(ScrollbackReader scrollbackReader,
                         ApprovalPolicy policy,
                         RawWriter writer,
                         Listener listener) {
        this.scrollbackReader = scrollbackReader;
        this.policy = policy;
        this.writer = writer;
        this.listener = listener;
        this.timer = new Timer(POLL_MS, e -> tick());
        this.timer.setRepeats(true);
    }

    public void start() {
        timer.start();
        IJ.log("[ImageJAI-Term] Prompt watcher started for policy " + policy.agentId());
    }

    public void stop() {
        timer.stop();
    }

    void tick() {
        String tail = readLastLines(TAIL_LINES);
        if (tail.equals(lastTail)) {
            return;
        }
        lastTail = tail;

        String url = latestUrl(tail);
        if (url != null && !url.equals(lastUrl)) {
            lastUrl = url;
            listener.onUrlSeen(url);
        }

        String prompt = promptCandidate(tail);
        if (prompt == null) {
            if (!lastPrompt.isEmpty()) {
                lastPrompt = "";
                listener.onPromptCleared();
            }
            return;
        }
        if (prompt.equals(lastPrompt)) {
            return;
        }
        lastPrompt = prompt;

        ApprovalPolicy.Decision decision = policy.decide(prompt);
        if (decision == ApprovalPolicy.Decision.AUTO_CONFIRM) {
            IJ.log("[ImageJAI-Term] Auto-confirming terminal prompt");
            writer.writeRaw("\r");
            listener.onAutoConfirm(prompt);
        } else if (decision == ApprovalPolicy.Decision.PENDING) {
            IJ.log("[ImageJAI-Term] Terminal prompt pending user action");
            listener.onPending(prompt);
        } else {
            IJ.log("[ImageJAI-Term] Escalating terminal prompt to user");
            listener.onEscalate(prompt);
        }
    }

    private String readLastLines(int limit) {
        try {
            return scrollbackReader == null ? "" : scrollbackReader.readScrollback(limit);
        } catch (RuntimeException e) {
            IJ.log("[ImageJAI-Term] Failed to read terminal buffer: " + e.getMessage());
        }
        return "";
    }

    private static String promptCandidate(String tail) {
        String[] split = tail.split("\\R");
        List<String> nonBlank = new ArrayList<String>();
        for (String line : split) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                nonBlank.add(trimmed);
            }
        }
        if (nonBlank.isEmpty()) {
            return null;
        }
        int start = Math.max(0, nonBlank.size() - 2);
        StringBuilder candidate = new StringBuilder();
        for (int i = start; i < nonBlank.size(); i++) {
            if (candidate.length() > 0) {
                candidate.append('\n');
            }
            candidate.append(nonBlank.get(i));
        }
        String prompt = candidate.toString();
        return PROMPT_TRAILER.matcher(prompt).matches() ? prompt : null;
    }

    private static String latestUrl(String tail) {
        Matcher matcher = URL.matcher(tail);
        String latest = null;
        while (matcher.find()) {
            latest = cleanupUrl(matcher.group(1));
        }
        return latest;
    }

    private static String cleanupUrl(String url) {
        String cleaned = url;
        while (cleaned.endsWith(".") || cleaned.endsWith(",")
                || cleaned.endsWith(")") || cleaned.endsWith("]")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1);
        }
        return cleaned;
    }

    public interface RawWriter {
        void writeRaw(String text);
    }
}
