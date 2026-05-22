package imagejai.engine;

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
    private final int bytesOut;
    private final long timestampMillis;

    private OutboundEvent(String command, int bytesOut, long timestampMillis) {
        this.command = command == null ? "" : command;
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
        OutboundEvent event = new OutboundEvent(command, bytesOut,
                System.currentTimeMillis());
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

    public int bytesOut() {
        return bytesOut;
    }

    public long timestampMillis() {
        return timestampMillis;
    }
}
