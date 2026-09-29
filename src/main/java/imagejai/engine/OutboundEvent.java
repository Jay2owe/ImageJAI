package imagejai.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Typed in-JVM signal for bytes leaving ImageJAI toward an agent process.
 */
public final class OutboundEvent {
    public interface Listener {
        void outboundEvent(OutboundEvent event);
    }

    private static final CopyOnWriteArrayList<Listener> LISTENERS =
            new CopyOnWriteArrayList<Listener>();

    private final String command;
    private final String topic;
    private final String identityHash;
    private final int bytesOut;
    private final long timestampMillis;

    private OutboundEvent(String command, String topic, String identityHash,
                          int bytesOut, long timestampMillis) {
        this.command = command == null ? "" : command;
        this.topic = topic == null ? "" : topic;
        this.identityHash = identityHash == null ? "" : identityHash;
        this.bytesOut = Math.max(0, bytesOut);
        this.timestampMillis = timestampMillis;
    }

    public static AutoCloseable subscribe(final Listener listener) {
        if (listener == null) {
            return new AutoCloseable() {
                @Override
                public void close() {
                }
            };
        }
        LISTENERS.addIfAbsent(listener);
        return new AutoCloseable() {
            @Override
            public void close() {
                LISTENERS.remove(listener);
            }
        };
    }

    public static void publish(String command, int bytesOut) {
        OutboundEvent event = new OutboundEvent(command, "", "", bytesOut,
                System.currentTimeMillis());
        notifyListeners(event);
    }

    /**
     * Publish stream egress without putting raw image/job/dialog identity into
     * the in-JVM observability signal. Known protocol topic names are retained;
     * custom topics are labelled {@code custom}. Identity is one-way hashed.
     */
    public static void publishStream(String topic, String identity, int bytesOut) {
        OutboundEvent event = new OutboundEvent("stream", safeTopic(topic),
                digest(identity), bytesOut, System.currentTimeMillis());
        notifyListeners(event);
    }

    private static void notifyListeners(OutboundEvent event) {
        for (Listener listener : LISTENERS) {
            try {
                listener.outboundEvent(event);
            } catch (Throwable ignore) {
            }
        }
    }

    public String command() {
        return command;
    }

    public String topic() {
        return topic;
    }

    public String identityHash() {
        return identityHash;
    }

    public int bytesOut() {
        return bytesOut;
    }

    public long timestampMillis() {
        return timestampMillis;
    }

    private static String safeTopic(String topic) {
        String value = topic == null ? "" : topic;
        String[] prefixes = {
                "image.", "job.", "dialog.", "macro.", "results.",
                "memory.", "safe_mode.", "gui_action.",
                "data_governance.", "reactive."
        };
        if ("heartbeat".equals(value) || "subscribed".equals(value)
                || "event_dropped".equals(value)) {
            return value;
        }
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)
                    && value.matches("[a-z0-9_.-]{1,128}")) {
                return value;
            }
        }
        return value.isEmpty() ? "" : "custom";
    }

    private static String digest(String identity) {
        if (identity == null || identity.isEmpty()) return "";
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(16);
            for (byte value : bytes) {
                out.append(String.format("%02x", value & 0xff));
                if (out.length() >= 16) break;
            }
            return out.toString();
        } catch (Exception impossible) {
            return "";
        }
    }
}
