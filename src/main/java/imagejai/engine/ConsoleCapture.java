package imagejai.engine;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;

/**
 * Captures recent bytes written to {@link System#out} and {@link System#err}
 * into bounded ring buffers so the {@code get_console} TCP command can surface
 * Groovy / Jython stack traces that {@code IJ.getLog()} never sees.
 * <p>
 * Per plan: {@code docs/tcp_upgrade/07_gemma_tools_server.md}.
 * <p>
 * The tee forwards every byte to the original stream so Fiji's own UI still
 * prints normally; we only duplicate into the ring buffer for later tail
 * reads. 64 KB per stream is plenty for the last several stack traces and
 * avoids meaningful heap pressure.
 * <p>
 * {@link #install()} is idempotent and safe to call from multiple server
 * lifecycles — the first call snapshots the original {@code System.out} /
 * {@code System.err} so {@link #uninstall()} can restore them exactly.
 */
public final class ConsoleCapture {

    /** Ring-buffer size per stream. Bigger buffers aren't worth it. */
    private static final int BUFFER_SIZE = 64 * 1024;

    private static final Object LOCK = new Object();
    private static volatile boolean installed;
    private static volatile PrintStream originalOut;
    private static volatile PrintStream originalErr;
    private static volatile RingBuffer stdoutBuf;
    private static volatile RingBuffer stderrBuf;

    private ConsoleCapture() {
        // Utility class — all state is static.
    }

    /**
     * Wrap {@link System#out} and {@link System#err} with tees that duplicate
     * output into bounded ring buffers. Idempotent: repeat calls are no-ops
     * once installed.
     */
    public static void install() {
        synchronized (LOCK) {
            if (installed) return;
            originalOut = System.out;
            originalErr = System.err;
            stdoutBuf = new RingBuffer(BUFFER_SIZE);
            stderrBuf = new RingBuffer(BUFFER_SIZE);
            try {
                System.setOut(new PrintStream(
                        new TeeOutputStream(originalOut, stdoutBuf), true, "UTF-8"));
                System.setErr(new PrintStream(
                        new TeeOutputStream(originalErr, stderrBuf), true, "UTF-8"));
            } catch (UnsupportedEncodingException e) {
                // UTF-8 is a required charset on every JVM.
                throw new IllegalStateException("UTF-8 not supported", e);
            }
            installed = true;
        }
    }

    /**
     * Restore the original {@link System#out} and {@link System#err}. Safe to
     * call when {@link #install()} was never run — then it's a no-op.
     */
    public static void uninstall() {
        synchronized (LOCK) {
            if (!installed) return;
            if (originalOut != null) System.setOut(originalOut);
            if (originalErr != null) System.setErr(originalErr);
            installed = false;
            stdoutBuf = null;
            stderrBuf = null;
            originalOut = null;
            originalErr = null;
        }
    }

    public static boolean isInstalled() {
        return installed;
    }

    /**
     * Tail of captured {@code System.out} bytes as UTF-8. Returns empty string
     * if capture was never installed. {@code maxBytes < 0} means "everything
     * currently buffered".
     */
    public static String tailStdout(int maxBytes) {
        RingBuffer b = stdoutBuf;
        return (b == null) ? "" : b.readTail(maxBytes);
    }

    /** Tail of captured {@code System.err}. */
    public static String tailStderr(int maxBytes) {
        RingBuffer b = stderrBuf;
        return (b == null) ? "" : b.readTail(maxBytes);
    }

    /** Number of bytes currently buffered for {@code System.out}. */
    public static long stdoutSize() {
        RingBuffer b = stdoutBuf;
        return (b == null) ? 0L : b.size();
    }

    /** Number of bytes currently buffered for {@code System.err}. */
    public static long stderrSize() {
        RingBuffer b = stderrBuf;
        return (b == null) ? 0L : b.size();
    }

    // -----------------------------------------------------------------------
    // Test hooks — package-private
    // -----------------------------------------------------------------------

    /**
     * Append raw text directly into the stdout ring buffer without routing
     * through {@link System#out}. Lets unit tests exercise
     * {@link #tailStdout(int)} without hijacking the JVM streams.
     */
    static void appendStdoutForTests(String s) {
        ensureInstalled();
        if (s != null) stdoutBuf.append(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static void appendStderrForTests(String s) {
        ensureInstalled();
        if (s != null) stderrBuf.append(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Install if needed then clear both buffers — test-only convenience so a
     * test sets a deterministic starting state without fighting {@link
     * #install()}'s idempotent guard.
     */
    static void resetForTests() {
        synchronized (LOCK) {
            if (!installed) install();
            stdoutBuf.clear();
            stderrBuf.clear();
        }
    }

    private static void ensureInstalled() {
        if (!installed) install();
    }

    // -----------------------------------------------------------------------
    // Internal plumbing
    // -----------------------------------------------------------------------

    /**
     * Byte-ring that retains the last {@link #BUFFER_SIZE} bytes written.
     * {@link #readTail(int)} decodes the tail as UTF-8 — partial code points
     * at the very start are tolerated by {@link String}'s replacement policy,
     * which is fine for surfacing the last stack trace to an LLM.
     */
    static final class RingBuffer {
        private final byte[] buf;
        private int pos;
        private long written;

        RingBuffer(int size) {
            this.buf = new byte[size];
        }

        synchronized void append(int b) {
            buf[pos] = (byte) b;
            pos = (pos + 1) % buf.length;
            written++;
        }

        synchronized void append(byte[] src) {
            append(src, 0, src.length);
        }

        synchronized void append(byte[] src, int off, int len) {
            for (int i = 0; i < len; i++) {
                buf[pos] = src[off + i];
                pos = (pos + 1) % buf.length;
            }
            written += len;
        }

        synchronized String readTail(int maxBytes) {
            int available = (int) Math.min(written, (long) buf.length);
            int take = (maxBytes < 0 || maxBytes > available) ? available : maxBytes;
            if (take == 0) return "";
            byte[] out = new byte[take];
            int startPos = ((pos - take) % buf.length + buf.length) % buf.length;
            for (int i = 0; i < take; i++) {
                out[i] = buf[(startPos + i) % buf.length];
            }
            return new String(out, java.nio.charset.StandardCharsets.UTF_8);
        }

        synchronized long size() {
            return Math.min(written, (long) buf.length);
        }

        synchronized long totalWritten() {
            return written;
        }

        synchronized void clear() {
            pos = 0;
            written = 0;
        }
    }

    /**
     * Pass-through {@link OutputStream} that writes every byte to the primary
     * stream (the original {@code System.out} / {@code System.err}) and also
     * appends it to the ring buffer. Never closes the primary — we are
     * borrowing a JVM-owned stream.
     */
    private static final class TeeOutputStream extends OutputStream {
        private final OutputStream primary;
        private final RingBuffer buffer;

        TeeOutputStream(OutputStream primary, RingBuffer buffer) {
            this.primary = primary;
            this.buffer = buffer;
        }

        @Override
        public void write(int b) throws IOException {
            primary.write(b);
            buffer.append(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            primary.write(b, off, len);
            buffer.append(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            primary.flush();
        }

        @Override
        public void close() throws IOException {
            // Never close the primary — it's a JVM-owned stream.
            primary.flush();
        }
    }
}
