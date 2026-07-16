package imagejai.engine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;

/** Small, package-local helpers for crash-safe persistent state. */
public final class SafeFileIO {
    private SafeFileIO() {}

    public static String uniqueToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static Path quarantine(Path source, String reason) throws IOException {
        if (source == null || !Files.exists(source)) return null;
        String suffix = ".corrupt-" + Instant.now().toEpochMilli() + "-" + uniqueToken();
        Path target = source.resolveSibling(source.getFileName().toString() + suffix);
        try {
            return Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            return Files.move(source, target);
        }
    }

    public static String readUtf8Bounded(Path path, long maxBytes) throws IOException {
        long size = Files.size(path);
        if (size > maxBytes) {
            throw new IOException("file exceeds " + maxBytes + " bytes: " + size);
        }
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    public static void writeUtf8Atomically(Path target, String content) throws IOException {
        Path dir = target.toAbsolutePath().getParent();
        if (dir != null) Files.createDirectories(dir);
        Path actualDir = dir == null ? target.toAbsolutePath().getParent() : dir;
        if (actualDir == null) throw new IOException("target has no parent: " + target);
        Path tmp = Files.createTempFile(actualDir, target.getFileName().toString() + ".", ".tmp");
        boolean moved = false;
        try {
            byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(tmp,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                moved = true;
                return;
            } catch (AtomicMoveNotSupportedException unsupported) {
                // Keep a recoverable copy before a non-atomic replacement.
            }

            replaceWithBackup(tmp, target);
            moved = true;
        } finally {
            if (!moved) Files.deleteIfExists(tmp);
        }
    }

    static void replaceWithBackup(Path source, Path target) throws IOException {
        Path backup = null;
        if (Files.exists(target)) {
            backup = target.resolveSibling(target.getFileName().toString()
                    + ".backup-" + uniqueToken());
            Files.copy(target, backup, StandardCopyOption.COPY_ATTRIBUTES);
        }
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            if (backup != null) Files.deleteIfExists(backup);
        } catch (IOException failure) {
            if (backup != null && !Files.exists(target)) {
                Files.move(backup, target, StandardCopyOption.REPLACE_EXISTING);
            }
            throw failure;
        }
    }

    /** Publish a newly-created file without ever replacing an existing name. */
    public static void moveNewAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target);
        }
    }
}
