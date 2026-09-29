package imagejai.engine.security;

import ij.IJ;
import imagejai.config.PrivacyPosture;
import imagejai.engine.PostureController;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Embedded-PTY outbound guard. It keeps a best-effort line buffer and, when
 * Enter is pressed, replaces any known sensitive substring with its token.
 */
public final class OutboundPromptScrubber {
    public static final int MAX_PARTIAL_LINE_CHARS = 65_536;
    public static final int MAX_WRITE_BYTES = 262_144;
    public static final int MAX_FILTERED_WRITE_CHARS = 524_288;
    public static final int MAX_REPLACEMENTS_PER_LINE = 4_096;
    private static final int MAX_SCRUB_STEPS = 1_048_576;

    /** A rejected write is never forwarded to the PTY. */
    public static final class PromptLimitException extends IllegalArgumentException {
        PromptLimitException(String message) {
            super(message);
        }
    }

    public interface Notifier {
        void pseudonymised(int replacementCount);

        default void pseudonymised(List<Replacement> replacements) {
            pseudonymised(replacements == null ? 0 : replacements.size());
        }

        void sentRaw();
    }

    public static final class Replacement {
        private final String original;
        private final String token;

        public Replacement(String original, String token) {
            this.original = original == null ? "" : original;
            this.token = token == null ? "" : token;
        }

        public String original() {
            return original;
        }

        public String token() {
            return token;
        }
    }

    private static final OutboundPromptScrubber INSTANCE = new OutboundPromptScrubber(
            PathTokenMap.getInstance(),
            new Notifier() {
                @Override
                public void pseudonymised(int replacementCount) {
                    IJ.log("[ImageJAI-Term] Pseudonymised " + replacementCount
                            + " sensitive substring(s) before send.");
                }

                @Override
                public void sentRaw() {
                    IJ.log("[ImageJAI-Term] Sent raw embedded-terminal prompt by user override.");
                }
            },
            AuditLog.getInstance());

    private final PathTokenMap pathTokenMap;
    private final Notifier notifier;
    private final AuditLog auditLog;
    private final CopyOnWriteArrayList<Notifier> extraNotifiers;
    private final StringBuilder lineBuffer = new StringBuilder();
    private volatile MatcherSnapshot matcherSnapshot = MatcherSnapshot.empty();
    private boolean nextEnterRaw;

    public OutboundPromptScrubber(PathTokenMap pathTokenMap, Notifier notifier) {
        this(pathTokenMap, notifier, null);
    }

    public OutboundPromptScrubber(PathTokenMap pathTokenMap, Notifier notifier,
                                  AuditLog auditLog) {
        this(pathTokenMap, notifier, auditLog,
                new CopyOnWriteArrayList<Notifier>());
    }

    private OutboundPromptScrubber(PathTokenMap pathTokenMap, Notifier notifier,
                                   AuditLog auditLog,
                                   CopyOnWriteArrayList<Notifier> sharedNotifiers) {
        this.pathTokenMap = pathTokenMap == null ? PathTokenMap.getInstance() : pathTokenMap;
        this.notifier = notifier;
        this.auditLog = auditLog;
        this.extraNotifiers = sharedNotifiers == null
                ? new CopyOnWriteArrayList<Notifier>() : sharedNotifiers;
    }

    public static OutboundPromptScrubber getInstance() {
        return INSTANCE;
    }

    /**
     * Create a scrubber with an independent partial-line/raw-enter buffer for
     * one PTY session. Notification subscribers remain shared so the launcher
     * toast continues to observe every session without sharing decoder state.
     */
    public static OutboundPromptScrubber createSessionScrubber() {
        return new OutboundPromptScrubber(INSTANCE.pathTokenMap,
                INSTANCE.notifier, INSTANCE.auditLog, INSTANCE.extraNotifiers);
    }

    public AutoCloseable addNotifier(final Notifier notifier) {
        if (notifier == null) {
            return new AutoCloseable() {
                @Override
                public void close() {
                }
            };
        }
        extraNotifiers.addIfAbsent(notifier);
        return new AutoCloseable() {
            @Override
            public void close() {
                extraNotifiers.remove(notifier);
            }
        };
    }

    /** Two-phase filtered write so a failed PTY write can restore its buffer. */
    public static final class PreparedWrite {
        private final OutboundPromptScrubber owner;
        private final byte[] bytes;
        private final List<Undo> undo;
        private final boolean priorRaw;
        private final List<Scrubbed> scrubbed;
        private final List<String> rawLines;
        private boolean completed;

        private PreparedWrite(OutboundPromptScrubber owner, byte[] bytes,
                              List<Undo> undo, boolean priorRaw,
                              List<Scrubbed> scrubbed, List<String> rawLines) {
            this.owner = owner;
            this.bytes = bytes;
            this.undo = undo;
            this.priorRaw = priorRaw;
            this.scrubbed = scrubbed;
            this.rawLines = rawLines;
        }

        public byte[] bytes() { return bytes; }
        public void commit() { owner.complete(this, true); }
        public void rollback() { owner.complete(this, false); }
    }

    public byte[] filter(byte[] bytes) {
        PreparedWrite prepared = prepare(bytes);
        prepared.commit();
        return prepared.bytes();
    }

    public synchronized PreparedWrite prepare(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return new PreparedWrite(this, bytes, Collections.<Undo>emptyList(), nextEnterRaw,
                    Collections.<Scrubbed>emptyList(), Collections.<String>emptyList());
        }
        if (bytes.length > MAX_WRITE_BYTES) {
            throw new PromptLimitException("Terminal write exceeds "
                    + MAX_WRITE_BYTES + " bytes");
        }
        boolean priorRaw = nextEnterRaw;
        List<Undo> undo = new ArrayList<Undo>();
        List<Scrubbed> scrubbedEvents = new ArrayList<Scrubbed>();
        List<String> rawEvents = new ArrayList<String>();
        String text = new String(bytes, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(Math.min(
                text.length() + 64, MAX_FILTERED_WRITE_CHARS));
        try {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\r' || c == '\n') {
                    String line = lineBuffer.toString();
                    undo.add(new ClearUndo(line));
                    lineBuffer.setLength(0);
                    if (nextEnterRaw) {
                        nextEnterRaw = false;
                        rawEvents.add(line);
                        appendBounded(out, c);
                        continue;
                    }
                    Scrubbed scrubbed = scrubOutgoing(line);
                    if (scrubbed.changed) {
                        scrubbedEvents.add(scrubbed);
                        appendBounded(out, '\u0015');
                        appendBounded(out, scrubbed.text);
                    }
                    appendBounded(out, c);
                } else if (c == '\b' || c == 0x7f) {
                    if (lineBuffer.length() > 0) {
                        char removed = lineBuffer.charAt(lineBuffer.length() - 1);
                        lineBuffer.setLength(lineBuffer.length() - 1);
                        undo.add(new DeleteUndo(removed));
                    }
                    appendBounded(out, c);
                } else {
                    if (lineBuffer.length() >= MAX_PARTIAL_LINE_CHARS) {
                        throw new PromptLimitException("Terminal line exceeds "
                                + MAX_PARTIAL_LINE_CHARS + " characters");
                    }
                    lineBuffer.append(c);
                    recordAppend(undo);
                    appendBounded(out, c);
                }
            }
        } catch (RuntimeException failure) {
            rollbackUndo(undo);
            nextEnterRaw = priorRaw;
            throw failure;
        }
        return new PreparedWrite(this,
                out.toString().getBytes(StandardCharsets.UTF_8),
                Collections.unmodifiableList(new ArrayList<Undo>(undo)),
                priorRaw, scrubbedEvents, rawEvents);
    }

    private synchronized void complete(PreparedWrite prepared, boolean committed) {
        if (prepared == null || prepared.owner != this || prepared.completed) return;
        prepared.completed = true;
        if (!committed) {
            rollbackUndo(prepared.undo);
            nextEnterRaw = prepared.priorRaw;
            return;
        }
        for (Scrubbed event : prepared.scrubbed) notifyPseudonymised(event);
        for (String line : prepared.rawLines) notifySentRaw(line);
    }

    public synchronized String scrub(String userTyped) {
        if (userTyped != null && userTyped.length() > MAX_PARTIAL_LINE_CHARS) {
            throw new PromptLimitException("Terminal line exceeds "
                    + MAX_PARTIAL_LINE_CHARS + " characters");
        }
        return scrubOutgoing(userTyped).text;
    }

    public synchronized void sendNextEnterRaw() {
        nextEnterRaw = true;
    }

    private Scrubbed scrubOutgoing(String userTyped) {
        String input = userTyped == null ? "" : userTyped;
        if (input.length() > MAX_PARTIAL_LINE_CHARS) {
            throw new PromptLimitException("Terminal line exceeds "
                    + MAX_PARTIAL_LINE_CHARS + " characters");
        }
        MatcherSnapshot snapshot = matcherForCurrentVersion();
        if (snapshot.root.children.isEmpty()) {
            return new Scrubbed(input, Collections.<Replacement>emptyList());
        }
        StringBuilder out = null;
        List<Replacement> replacements = new ArrayList<Replacement>();
        WorkBudget work = new WorkBudget(MAX_SCRUB_STEPS);
        int i = 0;
        while (i < input.length()) {
            TrieMatch match = snapshot.root.longestMatch(input, i, work);
            if (match != null) {
                if (replacements.size() >= MAX_REPLACEMENTS_PER_LINE) {
                    throw new PromptLimitException("Terminal line contains too many "
                            + "sensitive replacements");
                }
                if (out == null) {
                    out = new StringBuilder(Math.min(input.length() + 64,
                            MAX_FILTERED_WRITE_CHARS));
                    appendBounded(out, input.substring(0, i));
                }
                appendBounded(out, match.replacement);
                String original = input.substring(i, i + match.length);
                replacements.add(new Replacement(original, match.replacement));
                i += match.length;
            } else {
                if (out != null) appendBounded(out, input.charAt(i));
                i++;
            }
        }
        return out == null
                ? new Scrubbed(input, Collections.<Replacement>emptyList())
                : new Scrubbed(out.toString(), replacements);
    }

    private MatcherSnapshot matcherForCurrentVersion() {
        long version = pathTokenMap.sensitiveVersion();
        MatcherSnapshot cached = matcherSnapshot;
        if (cached.version == version) return cached;
        synchronized (this) {
            cached = matcherSnapshot;
            version = pathTokenMap.sensitiveVersion();
            if (cached.version == version) return cached;
            PathTokenMap.SensitiveSnapshot source = pathTokenMap.sensitiveSnapshot();
            TrieNode root = new TrieNode();
            for (Map.Entry<String, String> entry : source.entries) {
                if (entry.getKey() != null && !entry.getKey().isEmpty()) {
                    root.insert(entry.getKey(), entry.getValue());
                }
            }
            cached = new MatcherSnapshot(source.version, root);
            matcherSnapshot = cached;
            return cached;
        }
    }

    private static void appendBounded(StringBuilder out, char value) {
        if (out.length() >= MAX_FILTERED_WRITE_CHARS) {
            throw new PromptLimitException("Filtered terminal write exceeds "
                    + MAX_FILTERED_WRITE_CHARS + " characters");
        }
        out.append(value);
    }

    private static void appendBounded(StringBuilder out, String value) {
        String safe = value == null ? "" : value;
        if ((long) out.length() + safe.length() > MAX_FILTERED_WRITE_CHARS) {
            throw new PromptLimitException("Filtered terminal write exceeds "
                    + MAX_FILTERED_WRITE_CHARS + " characters");
        }
        out.append(safe);
    }

    private static void recordAppend(List<Undo> undo) {
        if (!undo.isEmpty() && undo.get(undo.size() - 1) instanceof AppendUndo) {
            ((AppendUndo) undo.get(undo.size() - 1)).count++;
        } else {
            undo.add(new AppendUndo(1));
        }
    }

    private void rollbackUndo(List<Undo> undo) {
        for (int i = undo.size() - 1; i >= 0; i--) {
            undo.get(i).apply(lineBuffer);
        }
    }

    private void notifyPseudonymised(Scrubbed scrubbed) {
        if (scrubbed == null || !scrubbed.changed) {
            return;
        }
        appendPromptAudit(scrubbed);
        if (notifier != null) {
            safePseudonymised(notifier, scrubbed.replacements);
        }
        for (Notifier listener : extraNotifiers) {
            safePseudonymised(listener, scrubbed.replacements);
        }
    }

    private void notifySentRaw(String line) {
        appendRawOverrideAudit(line);
        if (notifier != null) {
            safeSentRaw(notifier);
        }
        for (Notifier listener : extraNotifiers) {
            safeSentRaw(listener);
        }
    }

    private void appendPromptAudit(Scrubbed scrubbed) {
        if (auditLog == null || scrubbed == null || !scrubbed.changed) {
            return;
        }
        try {
            JsonObject payload = new JsonObject();
            payload.addProperty("command", "prompt.outbound");
            payload.addProperty("replacement_count", scrubbed.replacements.size());
            payload.addProperty("redacted_prompt", scrubbed.text);
            JsonArray tokens = new JsonArray();
            for (Replacement replacement : scrubbed.replacements) {
                tokens.add(replacement.token());
            }
            payload.add("tokens", tokens);
            auditLog.append(new AuditRow(
                    Instant.now(),
                    "",
                    "prompt.outbound",
                    currentPosture(),
                    "",
                    "",
                    bytes(scrubbed.text),
                    0,
                    "",
                    true,
                    Collections.singletonList("prompt"),
                    "replacements=" + scrubbed.replacements.size(),
                    payload.toString()));
        } catch (Throwable ignore) {
        }
    }

    private void appendRawOverrideAudit(String line) {
        if (auditLog == null) {
            return;
        }
        try {
            auditLog.append(new AuditRow(
                    Instant.now(),
                    "",
                    "prompt.raw_override",
                    currentPosture(),
                    "",
                    "",
                    bytes(line),
                    0,
                    "",
                    false,
                    Collections.<String>emptyList(),
                    "user_override=sent_raw"));
        } catch (Throwable ignore) {
        }
    }

    private static PrivacyPosture currentPosture() {
        try {
            return PostureController.getInstance().current();
        } catch (Throwable t) {
            return PrivacyPosture.defaultPosture();
        }
    }

    private static int bytes(String value) {
        return (value == null ? "" : value).getBytes(StandardCharsets.UTF_8).length;
    }

    private static void safePseudonymised(Notifier notifier,
                                          List<Replacement> replacements) {
        try {
            notifier.pseudonymised(replacements);
        } catch (Throwable ignore) {
        }
    }

    private static void safeSentRaw(Notifier notifier) {
        try {
            notifier.sentRaw();
        } catch (Throwable ignore) {
        }
    }

    private interface Undo {
        void apply(StringBuilder buffer);
    }

    private static final class AppendUndo implements Undo {
        int count;
        AppendUndo(int count) { this.count = count; }
        @Override public void apply(StringBuilder buffer) {
            buffer.setLength(Math.max(0, buffer.length() - count));
        }
    }

    private static final class DeleteUndo implements Undo {
        final char removed;
        DeleteUndo(char removed) { this.removed = removed; }
        @Override public void apply(StringBuilder buffer) { buffer.append(removed); }
    }

    private static final class ClearUndo implements Undo {
        final String cleared;
        ClearUndo(String cleared) { this.cleared = cleared; }
        @Override public void apply(StringBuilder buffer) { buffer.append(cleared); }
    }

    private static final class MatcherSnapshot {
        final long version;
        final TrieNode root;
        MatcherSnapshot(long version, TrieNode root) {
            this.version = version;
            this.root = root;
        }
        static MatcherSnapshot empty() {
            return new MatcherSnapshot(-1L, new TrieNode());
        }
    }

    private static final class TrieMatch {
        final int length;
        final String replacement;
        TrieMatch(int length, String replacement) {
            this.length = length;
            this.replacement = replacement;
        }
    }

    private static final class WorkBudget {
        int remaining;
        WorkBudget(int remaining) { this.remaining = remaining; }
        void consume() {
            if (--remaining < 0) {
                throw new PromptLimitException("Terminal scrub work limit exceeded");
            }
        }
    }

    private static final class TrieNode {
        final Map<Character, TrieNode> children = new HashMap<Character, TrieNode>();
        String replacement;

        void insert(String original, String token) {
            TrieNode node = this;
            for (int i = 0; i < original.length(); i++) {
                Character key = Character.valueOf(original.charAt(i));
                TrieNode next = node.children.get(key);
                if (next == null) {
                    next = new TrieNode();
                    node.children.put(key, next);
                }
                node = next;
            }
            node.replacement = token;
        }

        TrieMatch longestMatch(String text, int start, WorkBudget work) {
            TrieNode node = this;
            TrieMatch best = null;
            for (int i = start; i < text.length(); i++) {
                work.consume();
                node = node.children.get(Character.valueOf(text.charAt(i)));
                if (node == null) break;
                if (node.replacement != null) {
                    best = new TrieMatch(i - start + 1, node.replacement);
                }
            }
            return best;
        }
    }

    private static final class Scrubbed {
        final String text;
        final boolean changed;
        final List<Replacement> replacements;

        Scrubbed(String text, List<Replacement> replacements) {
            this.text = text;
            this.replacements = Collections.unmodifiableList(
                    new ArrayList<Replacement>(replacements));
            this.changed = !this.replacements.isEmpty();
        }
    }
}
