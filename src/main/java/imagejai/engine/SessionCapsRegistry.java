package imagejai.engine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * Bounded, expiring storage for protocol sessions and their negotiated caps.
 *
 * <p>Session identifiers are bearer-independent: a caller must present both
 * the random identifier and the same installation token used at hello. Only a
 * digest of that token is retained. Expiry uses a monotonic ticker so wall
 * clock changes cannot extend a session.</p>
 */
final class SessionCapsRegistry<C> {

    static final int DEFAULT_CAPACITY = 1024;
    static final long DEFAULT_TTL_MILLIS = 30L * 60L * 1000L;

    interface Ticker {
        long nanoTime();
    }

    interface WallClock {
        long currentTimeMillis();
    }

    interface IdSource {
        String nextId();
    }

    enum Status {
        VALID,
        MISSING,
        UNKNOWN,
        EXPIRED,
        TOKEN_MISMATCH,
        REVOKED
    }

    static final class Created<C> {
        private final String id;
        private final long expiresAtEpochMillis;
        private final C caps;

        Created(String id, long expiresAtEpochMillis, C caps) {
            this.id = id;
            this.expiresAtEpochMillis = expiresAtEpochMillis;
            this.caps = caps;
        }

        String id() {
            return id;
        }

        long expiresAtEpochMillis() {
            return expiresAtEpochMillis;
        }

        C caps() {
            return caps;
        }
    }

    static final class Lookup<C> {
        private final Status status;
        private final C caps;

        private Lookup(Status status, C caps) {
            this.status = status;
            this.caps = caps;
        }

        static <C> Lookup<C> of(Status status) {
            return new Lookup<C>(status, null);
        }

        static <C> Lookup<C> valid(C caps) {
            return new Lookup<C>(Status.VALID, caps);
        }

        Status status() {
            return status;
        }

        C caps() {
            return caps;
        }
    }

    static final class CapacityException extends Exception {
        CapacityException() {
            super("session capacity reached");
        }
    }

    private static final class Entry<C> {
        final C caps;
        final byte[] tokenDigest;
        final long expiresAtNanos;
        final long expiresAtEpochMillis;

        Entry(C caps, byte[] tokenDigest, long expiresAtNanos,
              long expiresAtEpochMillis) {
            this.caps = caps;
            this.tokenDigest = tokenDigest;
            this.expiresAtNanos = expiresAtNanos;
            this.expiresAtEpochMillis = expiresAtEpochMillis;
        }
    }

    private final int capacity;
    private final long ttlNanos;
    private final long ttlMillis;
    private final Ticker ticker;
    private final WallClock wallClock;
    private final IdSource idSource;
    private final Map<String, Entry<C>> entries =
            new HashMap<String, Entry<C>>();
    private volatile boolean accepting = true;

    SessionCapsRegistry() {
        this(DEFAULT_CAPACITY, DEFAULT_TTL_MILLIS,
                new Ticker() {
                    @Override
                    public long nanoTime() {
                        return System.nanoTime();
                    }
                },
                new WallClock() {
                    @Override
                    public long currentTimeMillis() {
                        return System.currentTimeMillis();
                    }
                },
                secureIdSource());
    }

    SessionCapsRegistry(int capacity, long ttlMillis, Ticker ticker,
                        WallClock wallClock, IdSource idSource) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        if (ttlMillis <= 0L) throw new IllegalArgumentException("ttlMillis must be positive");
        if (ticker == null || wallClock == null || idSource == null) {
            throw new NullPointerException("clock and id source are required");
        }
        this.capacity = capacity;
        this.ttlMillis = ttlMillis;
        this.ttlNanos = millisToNanosSaturated(ttlMillis);
        this.ticker = ticker;
        this.wallClock = wallClock;
        this.idSource = idSource;
    }

    synchronized Created<C> create(C caps, String token) throws CapacityException {
        if (caps == null) throw new NullPointerException("caps");
        if (!accepting) throw new IllegalStateException("registry is revoked");
        long nowNanos = ticker.nanoTime();
        removeExpired(nowNanos);
        if (entries.size() >= capacity) throw new CapacityException();

        byte[] digest = digest(token);
        long expiresNanos = addSaturated(nowNanos, ttlNanos);
        long expiresEpoch = addSaturated(wallClock.currentTimeMillis(), ttlMillis);
        for (int attempt = 0; attempt < 16; attempt++) {
            String id = idSource.nextId();
            if (id == null || id.length() < 32) continue;
            Entry<C> entry = new Entry<C>(caps, digest, expiresNanos, expiresEpoch);
            if (!entries.containsKey(id)) {
                entries.put(id, entry);
                return new Created<C>(id, expiresEpoch, caps);
            }
        }
        throw new IllegalStateException("could not allocate a unique session id");
    }

    synchronized Lookup<C> lookup(String id, String token) {
        if (!accepting) return Lookup.of(Status.REVOKED);
        if (id == null || id.trim().isEmpty()) return Lookup.of(Status.MISSING);
        Entry<C> entry = entries.get(id);
        if (entry == null) return Lookup.of(Status.UNKNOWN);
        if (ticker.nanoTime() - entry.expiresAtNanos >= 0L) {
            entries.remove(id);
            return Lookup.of(Status.EXPIRED);
        }
        if (!MessageDigest.isEqual(entry.tokenDigest, digest(token))) {
            return Lookup.of(Status.TOKEN_MISMATCH);
        }
        return Lookup.valid(entry.caps);
    }

    synchronized void revokeAll() {
        accepting = false;
        entries.clear();
    }

    synchronized void activate() {
        entries.clear();
        accepting = true;
    }

    synchronized int size() {
        removeExpired(ticker.nanoTime());
        return entries.size();
    }

    private void removeExpired(long nowNanos) {
        Iterator<Map.Entry<String, Entry<C>>> iterator =
                entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Entry<C>> item = iterator.next();
            Entry<C> entry = item.getValue();
            if (nowNanos - entry.expiresAtNanos >= 0L) {
                iterator.remove();
            }
        }
    }

    private static byte[] digest(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String value = token == null ? "" : token;
            return digest.digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static IdSource secureIdSource() {
        final SecureRandom random = new SecureRandom();
        return new IdSource() {
            @Override
            public String nextId() {
                byte[] bytes = new byte[32];
                random.nextBytes(bytes);
                return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            }
        };
    }

    private static long millisToNanosSaturated(long millis) {
        if (millis > Long.MAX_VALUE / 1_000_000L) return Long.MAX_VALUE;
        return millis * 1_000_000L;
    }

    private static long addSaturated(long a, long b) {
        if (b > 0L && a > Long.MAX_VALUE - b) return Long.MAX_VALUE;
        if (b < 0L && a < Long.MIN_VALUE - b) return Long.MIN_VALUE;
        return a + b;
    }
}
