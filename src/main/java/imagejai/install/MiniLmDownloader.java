package imagejai.install;

import imagejai.config.Settings;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * Downloads the optional MiniLM semantic model with streamed SHA-256 verification.
 */
public class MiniLmDownloader {

    public static final String MODEL_FILE = "all-MiniLM-L6-v2-int8.onnx";
    public static final String MODEL_URL =
            "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/onnx/model_qint8_avx512.onnx";

    public interface StreamOpener {
        InputStream open(String url) throws IOException;
    }

    private final StreamOpener streamOpener;
    private final String expectedSha256;

    public MiniLmDownloader(String expectedSha256) {
        this(expectedSha256, new StreamOpener() {
            @Override
            public InputStream open(String url) throws IOException {
                return new URL(url).openStream();
            }
        });
    }

    public MiniLmDownloader(String expectedSha256, StreamOpener streamOpener) {
        this.expectedSha256 = expectedSha256;
        this.streamOpener = streamOpener;
    }

    public Path defaultDestination() {
        return Settings.getConfigDir()
                .resolve("models")
                .resolve("all-MiniLM-L6-v2")
                .resolve(MODEL_FILE);
    }

    public Path download(BooleanSupplier keepGoing) throws IOException {
        return downloadTo(defaultDestination(), keepGoing);
    }

    public Path downloadTo(Path destination, BooleanSupplier keepGoing) throws IOException {
        try (InputStream in = streamOpener.open(MODEL_URL)) {
            return copyVerified(in, destination, expectedSha256, keepGoing);
        }
    }

    public static Path copyVerified(InputStream in,
                                    Path destination,
                                    String expectedSha256,
                                    BooleanSupplier keepGoing) throws IOException {
        Path parent = destination.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path partial = destination.resolveSibling(destination.getFileName().toString() + ".part");

        MessageDigest digest = sha256();
        byte[] buf = new byte[8192];
        try {
            try (InputStream input = in;
                 java.io.OutputStream out = Files.newOutputStream(partial)) {
                int n;
                while ((n = input.read(buf)) != -1) {
                    if (keepGoing != null && !keepGoing.getAsBoolean()) {
                        throw new IOException("Download cancelled");
                    }
                    out.write(buf, 0, n);
                    digest.update(buf, 0, n);
                }
            }

            String actual = toHex(digest.digest());
            if (!actual.equalsIgnoreCase(expectedSha256)) {
                Files.deleteIfExists(partial);
                throw new IOException("SHA-256 mismatch for MiniLM model: expected "
                        + expectedSha256 + " but got " + actual);
            }

            try {
                Files.move(partial, destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicMoveFailed) {
                Files.move(partial, destination, StandardCopyOption.REPLACE_EXISTING);
            }
            return destination;
        } catch (IOException e) {
            Files.deleteIfExists(partial);
            throw e;
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String toHex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        char[] hex = "0123456789abcdef".toCharArray();
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xff;
            chars[i * 2] = hex[v >>> 4];
            chars[i * 2 + 1] = hex[v & 0x0f];
        }
        return new String(chars).toLowerCase(Locale.ROOT);
    }
}
