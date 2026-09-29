package imagejai.terminal;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.TtyConnector;
import com.pty4j.PtyProcess;
import com.pty4j.WinSize;
import imagejai.engine.security.OutboundPromptScrubber;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal pty4j to JediTerm bridge.
 */
public final class ImageJAITtyConnector implements TtyConnector {
    private final PtyProcess process;
    private final InputStreamReader reader;
    private final OutputStream writer;
    private final OutboundPromptScrubber scrubber;
    private volatile boolean closed;

    public ImageJAITtyConnector(PtyProcess process) {
        this(process, null);
    }

    public ImageJAITtyConnector(PtyProcess process, OutboundPromptScrubber scrubber) {
        this.process = process;
        this.reader = new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8);
        this.writer = process.getOutputStream();
        this.scrubber = scrubber;
    }

    @Override
    public int read(char[] buf, int off, int len) throws IOException {
        return reader.read(buf, off, len);
    }

    @Override
    public synchronized void write(byte[] bytes) throws IOException {
        OutboundPromptScrubber.PreparedWrite prepared = scrubber == null
                ? null : scrubber.prepare(bytes);
        byte[] outgoing = prepared == null ? bytes : prepared.bytes();
        try {
            writer.write(outgoing);
            writer.flush();
            if (prepared != null) prepared.commit();
        } catch (IOException | RuntimeException failure) {
            if (prepared != null) prepared.rollback();
            throw failure;
        }
    }

    synchronized void writeRaw(byte[] bytes) throws IOException {
        writer.write(bytes);
        writer.flush();
    }

    @Override
    public void write(String string) throws IOException {
        write(string.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean isConnected() {
        return !closed && process.isAlive();
    }

    @Override
    public void resize(TermSize termSize) {
        process.setWinSize(new WinSize(termSize.getColumns(), termSize.getRows()));
    }

    @Override
    public int waitFor() throws InterruptedException {
        return process.waitFor();
    }

    @Override
    public boolean ready() throws IOException {
        return reader.ready();
    }

    @Override
    public String getName() {
        return "ImageJAI PTY";
    }

    @Override
    public void close() {
        closed = true;
    }
}
