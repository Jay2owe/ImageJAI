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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Embedded-PTY outbound guard. It keeps a best-effort line buffer and, when
 * Enter is pressed, replaces any known sensitive substring with its token.
 */
public final class OutboundPromptScrubber {
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
    private final CopyOnWriteArrayList<Notifier> extraNotifiers =
            new CopyOnWriteArrayList<Notifier>();
    private final StringBuilder lineBuffer = new StringBuilder();
    private boolean nextEnterRaw;

    public OutboundPromptScrubber(PathTokenMap pathTokenMap, Notifier notifier) {
        this(pathTokenMap, notifier, null);
    }

    public OutboundPromptScrubber(PathTokenMap pathTokenMap, Notifier notifier,
                                  AuditLog auditLog) {
        this.pathTokenMap = pathTokenMap == null ? PathTokenMap.getInstance() : pathTokenMap;
        this.notifier = notifier;
        this.auditLog = auditLog;
    }

    public static OutboundPromptScrubber getInstance() {
        return INSTANCE;
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

    public synchronized byte[] filter(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return bytes;
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n') {
                String line = lineBuffer.toString();
                lineBuffer.setLength(0);
                if (nextEnterRaw) {
                    nextEnterRaw = false;
                    notifySentRaw(line);
                    out.append(c);
                    continue;
                }
                Scrubbed scrubbed = scrubOutgoing(line);
                if (scrubbed.changed) {
                    notifyPseudonymised(scrubbed);
                    out.append('\u0015'); // terminal line kill before sending replacement
                    out.append(scrubbed.text);
                }
                out.append(c);
            } else if (c == '\b' || c == 0x7f) {
                if (lineBuffer.length() > 0) {
                    lineBuffer.setLength(lineBuffer.length() - 1);
                }
                out.append(c);
            } else {
                lineBuffer.append(c);
                out.append(c);
            }
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    public synchronized String scrub(String userTyped) {
        return scrubOutgoing(userTyped).text;
    }

    public synchronized void sendNextEnterRaw() {
        nextEnterRaw = true;
    }

    private Scrubbed scrubOutgoing(String userTyped) {
        String input = userTyped == null ? "" : userTyped;
        StringBuilder out = new StringBuilder(input.length());
        List<Replacement> replacements = new ArrayList<Replacement>();
        java.util.List<java.util.Map.Entry<String, String>> entries =
                pathTokenMap.snapshotSensitiveStringsLongestFirst();

        int i = 0;
        while (i < input.length()) {
            java.util.Map.Entry<String, String> match = null;
            for (java.util.Map.Entry<String, String> entry : entries) {
                String original = entry.getKey();
                if (original == null || original.isEmpty()) {
                    continue;
                }
                if (startsWithAt(input, original, i)) {
                    match = entry;
                    break;
                }
            }
            if (match != null) {
                out.append(match.getValue());
                i += match.getKey().length();
                replacements.add(new Replacement(match.getKey(), match.getValue()));
            } else {
                out.append(input.charAt(i));
                i++;
            }
        }
        return new Scrubbed(out.toString(), replacements);
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

    private static boolean startsWithAt(String value, String needle, int index) {
        int n = needle.length();
        if (index < 0 || n == 0 || index + n > value.length()) {
            return false;
        }
        return value.regionMatches(index, needle, 0, n);
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
