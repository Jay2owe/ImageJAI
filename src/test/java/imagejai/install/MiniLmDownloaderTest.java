package imagejai.install;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MiniLmDownloaderTest {

    private Path tmpDir;
    private Path dest;

    @Before
    public void setUp() throws IOException {
        tmpDir = Files.createTempDirectory("minilm-downloader-test-");
        dest = tmpDir.resolve("all-MiniLM-L6-v2-int8.onnx");
    }

    @After
    public void tearDown() throws IOException {
        Files.deleteIfExists(dest);
        Files.deleteIfExists(tmpDir.resolve("all-MiniLM-L6-v2-int8.onnx.part"));
        Files.deleteIfExists(tmpDir);
    }

    @Test
    public void successAtomicMovesVerifiedFileAndDeletesPartial() throws Exception {
        byte[] bytes = "verified model bytes".getBytes(StandardCharsets.UTF_8);
        MiniLmDownloader.copyVerified(
                new ByteArrayInputStream(bytes),
                dest,
                sha256(bytes),
                () -> true);

        assertArrayEquals(bytes, Files.readAllBytes(dest));
        assertFalse(Files.exists(tmpDir.resolve("all-MiniLM-L6-v2-int8.onnx.part")));
    }

    @Test
    public void shaMismatchDeletesPartial() throws Exception {
        byte[] bytes = "wrong model bytes".getBytes(StandardCharsets.UTF_8);

        try {
            MiniLmDownloader.copyVerified(
                    new ByteArrayInputStream(bytes),
                    dest,
                    sha256("different".getBytes(StandardCharsets.UTF_8)),
                    () -> true);
            fail("expected SHA mismatch");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("SHA-256 mismatch"));
        }

        assertFalse(Files.exists(dest));
        assertFalse(Files.exists(tmpDir.resolve("all-MiniLM-L6-v2-int8.onnx.part")));
    }

    @Test
    public void cancelMidStreamDeletesPartial() throws Exception {
        byte[] bytes = new byte[9000];
        AtomicInteger calls = new AtomicInteger();

        try {
            MiniLmDownloader.copyVerified(
                    new ByteArrayInputStream(bytes),
                    dest,
                    sha256(bytes),
                    () -> calls.incrementAndGet() < 2);
            fail("expected cancellation");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("cancelled"));
        }

        assertFalse(Files.exists(dest));
        assertFalse(Files.exists(tmpDir.resolve("all-MiniLM-L6-v2-int8.onnx.part")));
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : hash) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
