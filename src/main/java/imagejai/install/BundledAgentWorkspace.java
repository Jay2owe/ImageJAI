package imagejai.install;

import ij.IJ;
import imagejai.config.Settings;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Materialises the allowlisted Python agent runtime carried by the plugin JAR.
 *
 * <p>The Python launcher needs a real directory as its working directory; it
 * cannot execute the files directly from inside a JAR. Explicit development
 * and lab-installed workspaces still take precedence in {@code ImageJAIPlugin}.
 * This class is only the JAR-only fallback.</p>
 */
public final class BundledAgentWorkspace {

    private static final String RESOURCE_ROOT = "agent-runtime";
    private static final String RESOURCE_PREFIX = RESOURCE_ROOT + "/";

    private BundledAgentWorkspace() {
    }

    /** Extract the bundled workspace and return its absolute path, or null. */
    public static String ensureInstalled() {
        Path target = Settings.getConfigDir()
                .resolve("bundled-agent")
                .resolve("agent");
        return ensureInstalled(target);
    }

    /** Extract the bundled workspace to an explicit managed location. */
    public static String ensureInstalled(Path target) {
        try {
            install(target);
            IJ.log("[ImageJAI] Using bundled agent workspace: " + target);
            return target.toAbsolutePath().normalize().toString();
        } catch (Exception failure) {
            IJ.log("[ImageJAI] Bundled agent workspace unavailable ("
                    + failure.getClass().getSimpleName() + ").");
            return null;
        }
    }

    static Path install(Path target) throws Exception {
        if (target == null) {
            throw new IllegalArgumentException("Agent workspace target is required.");
        }
        URL location = BundledAgentWorkspace.class.getProtectionDomain()
                .getCodeSource().getLocation();
        URI uri = location.toURI();
        Path codeLocation = Paths.get(uri).toAbsolutePath().normalize();
        return install(target, codeLocation);
    }

    static Path install(Path target, Path codeLocation) throws IOException {
        if (target == null || codeLocation == null) {
            throw new IllegalArgumentException(
                    "Agent workspace target and code location are required.");
        }
        Path root = target.toAbsolutePath().normalize();
        createSafeDirectories(root);
        int copied;
        if (Files.isDirectory(codeLocation, LinkOption.NOFOLLOW_LINKS)) {
            copied = copyDirectoryResources(codeLocation.resolve(RESOURCE_ROOT), root);
        } else {
            copied = copyJarResources(codeLocation, root);
        }
        if (copied == 0 || !Files.isRegularFile(root.resolve("ij.py"),
                LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Bundled agent runtime is incomplete.");
        }
        return root;
    }

    private static int copyDirectoryResources(Path sourceRoot, Path targetRoot)
            throws IOException {
        if (!Files.isDirectory(sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
            return 0;
        }
        int[] copied = {0};
        try (Stream<Path> paths = Files.walk(sourceRoot)) {
            // Maven's output can contain file links when the source checkout is
            // on Dropbox. Follow file links in this trusted build directory;
            // destination paths are still normalised and checked below.
            paths.filter(path -> !path.equals(sourceRoot)
                            && !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .forEach(path -> {
                        Path relative = sourceRoot.relativize(path);
                        try (InputStream in = Files.newInputStream(path)) {
                            writeResource(targetRoot, relative.toString(), in);
                            copied[0]++;
                        } catch (IOException failure) {
                            throw new CopyFailure(failure);
                        }
                    });
        } catch (CopyFailure failure) {
            throw failure.ioException;
        }
        return copied[0];
    }

    private static int copyJarResources(Path jarPath, Path targetRoot)
            throws IOException {
        int copied = 0;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith(RESOURCE_PREFIX)) {
                    continue;
                }
                String relative = entry.getName().substring(RESOURCE_PREFIX.length());
                try (InputStream in = jar.getInputStream(entry)) {
                    writeResource(targetRoot, relative, in);
                }
                copied++;
            }
        }
        return copied;
    }

    private static void writeResource(Path root, String relative, InputStream in)
            throws IOException {
        if (relative == null || relative.trim().isEmpty()) {
            return;
        }
        Path destination = root.resolve(relative.replace('/', File.separatorChar))
                .toAbsolutePath().normalize();
        if (!destination.startsWith(root)) {
            throw new IOException("Bundled agent resource escaped its target.");
        }
        createSafeDirectories(destination.getParent());
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
                && (Files.isSymbolicLink(destination)
                || !Files.isRegularFile(destination, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Bundled agent target is redirected.");
        }

        Path temp = Files.createTempFile(destination.getParent(),
                ".imagejai-agent-", ".tmp");
        try {
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void createSafeDirectories(Path directory) throws IOException {
        if (directory == null) {
            throw new IOException("Bundled agent target has no parent.");
        }
        Path absolute = directory.toAbsolutePath().normalize();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS)) {
            existing = existing.getParent();
        }
        if (existing == null || Files.isSymbolicLink(existing)) {
            throw new IOException("Bundled agent target root is redirected.");
        }
        Path current = existing;
        Path remainder = existing.relativize(absolute);
        for (Path segment : remainder) {
            current = current.resolve(segment);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(current)
                        || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Bundled agent target is redirected.");
                }
            } else {
                Files.createDirectory(current);
            }
        }
    }

    private static final class CopyFailure extends RuntimeException {
        final IOException ioException;

        CopyFailure(IOException ioException) {
            super(ioException);
            this.ioException = ioException;
        }
    }
}
