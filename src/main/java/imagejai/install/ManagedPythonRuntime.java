package imagejai.install;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs a private Python for the console when no suitable system Python exists. */
final class ManagedPythonRuntime {
    static final String UV_VERSION = "0.12.19";
    private static final String RELEASE_ROOT = "https://releases.astral.sh/github/uv/releases/download/"
            + UV_VERSION + "/";
    private static final long MAX_ARCHIVE_BYTES = 80L * 1024L * 1024L;
    private static final long UV_TIMEOUT_MS = 900_000L;

    interface Downloader {
        void download(URL url, Path destination) throws IOException;
    }

    static final class Artifact {
        final String name;
        final String sha256;
        final boolean windows;

        Artifact(String name, String sha256, boolean windows) {
            this.name = name;
            this.sha256 = sha256;
            this.windows = windows;
        }
    }

    private final Downloader downloader;

    ManagedPythonRuntime() {
        this(new HttpDownloader());
    }

    ManagedPythonRuntime(Downloader downloader) {
        this.downloader = downloader;
    }

    void createEnvironment(Path versionRoot, Path environment,
                           ConsoleBootstrap.CommandRunner runner,
                           ConsoleBootstrap.LogSink log) throws Exception {
        Artifact artifact = artifactFor(System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""));
        if (artifact == null) {
            throw new IOException("Automatic Python setup is not available for this computer ("
                    + System.getProperty("os.name") + ", " + System.getProperty("os.arch")
                    + "). Install Python 3.10-3.13 and click Repair.");
        }
        Path uv = ensureUv(versionRoot, artifact, log);
        Map<String, String> variables = new LinkedHashMap<String, String>();
        variables.put("UV_PYTHON_INSTALL_DIR", versionRoot.resolve("python").toString());
        variables.put("UV_PYTHON_BIN_DIR", versionRoot.resolve("python-bin").toString());
        variables.put("UV_CACHE_DIR", versionRoot.resolve("uv-cache").toString());
        variables.put("UV_NO_CONFIG", "1");

        log.line("Downloading private Python 3.12 if needed...");
        requireSuccess(runner.run(Arrays.asList(uv.toString(), "python", "install", "3.12"),
                versionRoot, variables, UV_TIMEOUT_MS, log), "Private Python setup");
        log.line("Creating private Python environment...");
        requireSuccess(runner.run(Arrays.asList(uv.toString(), "venv", "--seed", "--python",
                        "3.12", "--managed-python", environment.toString()),
                versionRoot, variables, UV_TIMEOUT_MS, log), "Private environment setup");
    }

    Path ensureUv(Path versionRoot, Artifact artifact, ConsoleBootstrap.LogSink log)
            throws IOException, InterruptedException {
        Path toolDir = versionRoot.resolve("runtime-tools");
        Path executable = toolDir.resolve(artifact.windows ? "uv.exe" : "uv");
        if (Files.isRegularFile(executable)) return executable;

        Files.createDirectories(toolDir);
        Path archive = Files.createTempFile(toolDir, ".uv-download-", ".tmp");
        Path extracted = Files.createTempFile(toolDir, ".uv-extract-", ".tmp");
        try {
            log.line("Downloading verified Python setup tool for this computer...");
            downloader.download(new URL(RELEASE_ROOT + artifact.name), archive);
            if (Files.size(archive) > MAX_ARCHIVE_BYTES
                    || !artifact.sha256.equalsIgnoreCase(sha256(archive))) {
                throw new IOException("Python setup download failed its integrity check. Retry Install or Repair.");
            }
            if (artifact.windows) extractUvZip(archive, extracted);
            else extractUvTar(archive, extracted);
            if (Files.size(extracted) == 0) {
                throw new IOException("Python setup download did not contain the expected executable.");
            }
            if (!artifact.windows && !extracted.toFile().setExecutable(true, true)) {
                throw new IOException("Could not make the private Python setup tool executable.");
            }
            Files.move(extracted, executable, StandardCopyOption.REPLACE_EXISTING);
            return executable;
        } finally {
            Files.deleteIfExists(archive);
            Files.deleteIfExists(extracted);
        }
    }

    static Artifact artifactFor(String osName, String architecture) {
        String os = osName.toLowerCase(Locale.ROOT);
        String arch = architecture.toLowerCase(Locale.ROOT);
        boolean arm = arch.equals("aarch64") || arch.equals("arm64");
        boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
        if (os.contains("win")) {
            if (arm) return new Artifact("uv-aarch64-pc-windows-msvc.zip",
                    "115b54cb823bc48260670f5782001add6067ac8d98d18c8263a833704e287de9", true);
            if (x64) return new Artifact("uv-x86_64-pc-windows-msvc.zip",
                    "6dbb02d79e419522f1c500f0adb1cddcff0cda7d59b0d66ea7f5e3b4a1b2f5f0", true);
        } else if (os.contains("mac")) {
            if (arm) return new Artifact("uv-aarch64-apple-darwin.tar.gz",
                    "a9a8df1eedeb192f2e47e40e2faabfb387db4b850209118786d42f89dde3e0ba", false);
            if (x64) return new Artifact("uv-x86_64-apple-darwin.tar.gz",
                    "cb5fa57bafe68fc0fb94b17f06bee0b0b9a7feb94ccbd110445afa0696e39273", false);
        } else if (os.contains("linux")) {
            if (arm) return new Artifact("uv-aarch64-unknown-linux-gnu.tar.gz",
                    "0804e9b164c64b6914182d5920c08551958a095986f10a3731056df701126436", false);
            if (x64) return new Artifact("uv-x86_64-unknown-linux-gnu.tar.gz",
                    "23bf5552d220e0842b65c862097b2ebaeba0064b74eda5e565e77fd25969d8c8", false);
        }
        return null;
    }

    private static void extractUvZip(Path archive, Path destination) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (!entry.isDirectory() && (name.equals("uv.exe") || name.endsWith("/uv.exe"))) {
                    Files.copy(zip, destination, StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
            }
        }
        throw new IOException("Python setup archive does not contain uv.exe.");
    }

    private static void extractUvTar(Path archive, Path destination)
            throws IOException, InterruptedException {
        Process listed = new ProcessBuilder("tar", "-tzf", archive.toString())
                .redirectErrorStream(true).start();
        String listing;
        try (InputStream input = listed.getInputStream()) {
            listing = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        if (!listed.waitFor(60, TimeUnit.SECONDS) || listed.exitValue() != 0) {
            throw new IOException("Could not read the private Python setup archive.");
        }
        String uvEntry = null;
        for (String entry : listing.split("\\r?\\n")) {
            if (entry.startsWith("/") || entry.startsWith("../")
                    || entry.contains("/../") || entry.contains("\\\\")) {
                throw new IOException("Python setup archive has an unsafe path.");
            }
            if (entry.equals("uv") || entry.endsWith("/uv")) uvEntry = entry;
        }
        if (uvEntry == null) throw new IOException("Python setup archive does not contain uv.");
        Process extracted = new ProcessBuilder("tar", "-xOzf", archive.toString(), uvEntry)
                .redirectOutput(destination.toFile()).start();
        if (!extracted.waitFor(60, TimeUnit.SECONDS) || extracted.exitValue() != 0) {
            throw new IOException("Could not extract the private Python setup tool.");
        }
    }

    private static void requireSuccess(ConsoleBootstrap.CommandResult result, String operation)
            throws IOException {
        if (result.ok()) return;
        throw new IOException(operation + (result.timedOut ? " timed out" : " failed")
                + (result.output.trim().isEmpty() ? "" : ":\n" + result.output.trim()));
    }

    static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            }
            StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) hex.append(String.format("%02x", value & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable", impossible);
        }
    }

    private static final class HttpDownloader implements Downloader {
        @Override public void download(URL url, Path destination) throws IOException {
            if (!"https".equalsIgnoreCase(url.getProtocol())) {
                throw new IOException("Refusing an insecure Python setup download.");
            }
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(20_000);
            connection.setReadTimeout(60_000);
            connection.setInstanceFollowRedirects(true);
            try {
                if (connection.getResponseCode() != 200) {
                    throw new IOException("Python setup download returned HTTP "
                            + connection.getResponseCode() + ". Retry Install or Repair.");
                }
                long total = 0;
                try (InputStream input = connection.getInputStream();
                     java.io.OutputStream output = Files.newOutputStream(destination)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        total += read;
                        if (total > MAX_ARCHIVE_BYTES) {
                            throw new IOException("Python setup download is larger than expected.");
                        }
                        output.write(buffer, 0, read);
                    }
                }
            } finally {
                connection.disconnect();
            }
        }
    }
}
