package imagejai.engine.security;

import ij.IJ;

import java.nio.charset.StandardCharsets;

/**
 * Embedded-PTY outbound guard. It keeps a best-effort line buffer and, when
 * Enter is pressed, replaces any known sensitive substring with its token.
 */
public final class OutboundPromptScrubber {
    public interface Notifier {
        void pseudonymised(int replacementCount);
        void sentRaw();
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
            });

    private final PathTokenMap pathTokenMap;
    private final Notifier notifier;
    private final StringBuilder lineBuffer = new StringBuilder();
    private boolean nextEnterRaw;

    public OutboundPromptScrubber(PathTokenMap pathTokenMap, Notifier notifier) {
        this.pathTokenMap = pathTokenMap == null ? PathTokenMap.getInstance() : pathTokenMap;
        this.notifier = notifier;
    }

    public static OutboundPromptScrubber getInstance() {
        return INSTANCE;
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
                    if (notifier != null) notifier.sentRaw();
                    out.append(c);
                    continue;
                }
                Scrubbed scrubbed = scrubOutgoing(line);
                if (scrubbed.changed) {
                    if (notifier != null) notifier.pseudonymised(scrubbed.replacements);
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
        String out = userTyped == null ? "" : userTyped;
        int replacements = 0;
        for (java.util.Map.Entry<String, String> entry
                : pathTokenMap.snapshotSensitiveStringsLongestFirst()) {
            String original = entry.getKey();
            if (original == null || original.isEmpty()) {
                continue;
            }
            int count = countOccurrences(out, original);
            if (count > 0) {
                out = out.replace(original, entry.getValue());
                replacements += count;
            }
        }
        return new Scrubbed(out, replacements > 0, replacements);
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = value.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    private static final class Scrubbed {
        final String text;
        final boolean changed;
        final int replacements;

        Scrubbed(String text, boolean changed, int replacements) {
            this.text = text;
            this.changed = changed;
            this.replacements = replacements;
        }
    }
}
