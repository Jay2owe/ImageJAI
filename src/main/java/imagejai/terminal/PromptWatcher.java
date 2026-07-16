package imagejai.terminal;

import com.jediterm.terminal.model.LinesBuffer;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.JediTermWidget;
import ij.IJ;

import javax.swing.Timer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Polls visible terminal output for approval prompts and URLs.
 */
public final class PromptWatcher {
    public enum State { STOPPED, RUNNING }

    public interface ScrollbackReader {
        String readScrollback(int lineLimit);
    }

    public interface Listener {
        void onAutoConfirm(String promptText);
        void onEscalate(String promptText);
        void onPending(String promptText);
        void onPromptCleared();
        void onUrlSeen(String url);
        default void onWatcherState(State state) { }
        default void onWatcherFailure(String operation, String errorType) { }
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
    private volatile State state = State.STOPPED;
    private volatile long failureCount;
    private volatile String lastFailure = "";

    public PromptWatcher(JediTermWidget terminal,
                         ApprovalPolicy policy,
                         RawWriter writer,
                         Listener listener) {
        this(new ScrollbackReader() {
            @Override public String readScrollback(int lineLimit) {
                return readTerminalLastLines(terminal, lineLimit);
            }
        }, policy, writer, listener);
    }

    /** Injectable headless seam for lifecycle and failure-path tests. */
    public PromptWatcher(ScrollbackReader scrollbackReader,
                         ApprovalPolicy policy,
                         RawWriter writer,
                         Listener listener) {
        this.scrollbackReader = scrollbackReader;
        this.policy = policy;
        this.writer = writer;
        this.listener = listener;
        this.timer = new Timer(POLL_MS, e -> pollNow());
        this.timer.setRepeats(true);
    }

    public synchronized void start() {
        if (state == State.RUNNING) return;
        state = State.RUNNING;
        timer.start();
        notifyState(State.RUNNING);
        IJ.log("[ImageJAI-Term] Prompt watcher started for policy "
                + (policy == null ? "unknown" : policy.agentId()));
    }

    public synchronized void stop() {
        timer.stop();
        if (state != State.STOPPED) {
            state = State.STOPPED;
            notifyState(State.STOPPED);
            IJ.log("[ImageJAI-Term] Prompt watcher stopped");
        }
    }

    public State state() { return state; }
    public long failureCount() { return failureCount; }
    public String lastFailure() { return lastFailure; }

    /** Run one poll synchronously; the Swing timer calls this same path. */
    public void pollNow() {
        if (state != State.RUNNING) return;
        try {
            tick();
        } catch (Throwable failure) {
            lastTail = "";
            lastPrompt = "";
            recordFailure("poll", failure);
        }
    }

    private void tick() throws Exception {
        String tail = scrollbackReader == null ? ""
                : scrollbackReader.readScrollback(TAIL_LINES);
        if (tail == null) tail = "";
        if (tail.equals(lastTail)) {
            return;
        }
        lastTail = tail;

        String url = latestUrl(tail);
        if (url != null && !url.equals(lastUrl)) {
            lastUrl = url;
            if (listener != null) listener.onUrlSeen(url);
        }

        String prompt = promptCandidate(tail);
        if (prompt == null) {
            if (!lastPrompt.isEmpty()) {
                lastPrompt = "";
                if (listener != null) listener.onPromptCleared();
            }
            return;
        }
        if (prompt.equals(lastPrompt)) {
            return;
        }
        lastPrompt = prompt;

        ApprovalPolicy.Decision decision = policy == null
                ? ApprovalPolicy.Decision.ESCALATE : policy.decide(prompt);
        if (decision == ApprovalPolicy.Decision.AUTO_CONFIRM) {
            IJ.log("[ImageJAI-Term] Auto-confirming terminal prompt");
            try {
                if (writer == null) throw new IOException("terminal writer unavailable");
                writer.writeRaw("\r");
                if (listener != null) listener.onAutoConfirm(prompt);
            } catch (Exception failure) {
                // Let the same visible prompt be retried after a transient PTY
                // failure; never report it as auto-confirmed.
                lastTail = "";
                lastPrompt = "";
                if (listener != null) listener.onPending(prompt);
                recordFailure("write", failure);
            }
        } else if (decision == ApprovalPolicy.Decision.PENDING) {
            IJ.log("[ImageJAI-Term] Terminal prompt pending user action");
            if (listener != null) listener.onPending(prompt);
        } else {
            IJ.log("[ImageJAI-Term] Escalating terminal prompt to user");
            if (listener != null) listener.onEscalate(prompt);
        }
    }

    @SuppressWarnings({"deprecation", "removal"})
    private static String readTerminalLastLines(JediTermWidget terminal, int limit) {
        if (terminal == null) return "";
        TerminalTextBuffer buffer = terminal.getTerminalTextBuffer();
        List<String> lines = new ArrayList<String>();
        buffer.lock();
        try {
            appendLast(lines, buffer.getHistoryBuffer(), limit);
            appendLast(lines, buffer.getScreenBuffer(), limit);
        } finally {
            buffer.unlock();
        }

        int start = Math.max(0, lines.size() - limit);
        StringBuilder out = new StringBuilder();
        for (int i = start; i < lines.size(); i++) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(lines.get(i));
        }
        return out.toString();
    }

    private void recordFailure(String operation, Throwable failure) {
        failureCount++;
        String type = failure == null ? "unknown" : failure.getClass().getSimpleName();
        lastFailure = operation + ": " + type;
        IJ.log("[ImageJAI-Term] Prompt watcher " + operation + " failed (" + type + ")");
        if (listener != null) {
            try { listener.onWatcherFailure(operation, type); }
            catch (Throwable ignored) { }
        }
    }

    private void notifyState(State next) {
        if (listener != null) {
            try { listener.onWatcherState(next); }
            catch (Throwable failure) { recordFailure("lifecycle-listener", failure); }
        }
    }

    @SuppressWarnings("removal")
    private static void appendLast(List<String> target, LinesBuffer source, int limit) {
        int count = source.getLineCount();
        int start = Math.max(0, count - limit);
        for (int i = start; i < count; i++) {
            target.add(source.getLineText(i));
        }
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
        void writeRaw(String text) throws Exception;
    }
}
