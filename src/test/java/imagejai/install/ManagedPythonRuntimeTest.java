package imagejai.install;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ManagedPythonRuntimeTest {
    @Test
    public void selectsPinnedArchivesForMainstreamPlatforms() {
        assertEquals("uv-x86_64-pc-windows-msvc.zip",
                ManagedPythonRuntime.artifactFor("Windows 11", "amd64").name);
        assertEquals("uv-aarch64-pc-windows-msvc.zip",
                ManagedPythonRuntime.artifactFor("Windows 11", "aarch64").name);
        assertEquals("uv-aarch64-apple-darwin.tar.gz",
                ManagedPythonRuntime.artifactFor("Mac OS X", "arm64").name);
        assertEquals("uv-x86_64-unknown-linux-gnu.tar.gz",
                ManagedPythonRuntime.artifactFor("Linux", "x86_64").name);
        assertTrue(ManagedPythonRuntime.artifactFor("Plan 9", "mips") == null);
    }

    @Test
    public void verifiesArchiveBeforeInstallingTool() throws Exception {
        Path root = Files.createTempDirectory("imagejai-uv-verify");
        byte[] archive = zip("uv.exe", "verified tool");
        Path original = root.resolve("original.zip");
        Files.write(original, archive);
        String checksum = ManagedPythonRuntime.sha256(original);
        ManagedPythonRuntime runtime = new ManagedPythonRuntime((url, destination) ->
                Files.copy(original, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING));
        ManagedPythonRuntime.Artifact good = new ManagedPythonRuntime.Artifact(
                "uv-test.zip", checksum, true);

        Path installed = runtime.ensureUv(root.resolve("good"), good, line -> { });
        assertEquals("verified tool", new String(Files.readAllBytes(installed),
                StandardCharsets.UTF_8));

        ManagedPythonRuntime.Artifact bad = new ManagedPythonRuntime.Artifact(
                "uv-test.zip", "0".repeat(64), true);
        boolean failed = false;
        try {
            runtime.ensureUv(root.resolve("bad"), bad, line -> { });
        } catch (IOException expected) {
            failed = true;
        }
        assertTrue(failed);
        assertFalse(Files.exists(root.resolve("bad").resolve("runtime-tools").resolve("uv.exe")));
    }

    @Test
    public void liveReleaseArchiveWorksWhenExplicitlyEnabled() throws Exception {
        if (!Boolean.getBoolean("imagejai.testLiveUvDownload")) return;
        Path root = Files.createTempDirectory("imagejai-live-uv-");
        try {
            ManagedPythonRuntime runtime = new ManagedPythonRuntime();
            ManagedPythonRuntime.Artifact artifact = ManagedPythonRuntime.artifactFor(
                    System.getProperty("os.name"), System.getProperty("os.arch"));
            Path uv = runtime.ensureUv(root, artifact, line -> { });
            Process process = new ProcessBuilder(uv.toString(), "--version")
                    .redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor());
            assertTrue(output.contains(ManagedPythonRuntime.UV_VERSION));
            Path environment = root.resolve("venv");
            runtime.createEnvironment(root, environment, (command, cwd, variables, timeout, log) -> {
                Path logFile = Files.createTempFile(root, "uv-command-", ".log");
                try {
                    ProcessBuilder builder = new ProcessBuilder(command)
                            .directory(cwd.toFile()).redirectErrorStream(true)
                            .redirectOutput(logFile.toFile());
                    builder.environment().putAll(variables);
                    Process child = builder.start();
                    boolean done = child.waitFor(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (!done) child.destroyForcibly();
                    String result = Files.readString(logFile);
                    return new ConsoleBootstrap.CommandResult(done ? child.exitValue() : -1,
                            !done, result);
                } finally {
                    Files.deleteIfExists(logFile);
                }
            }, line -> { });
            Path python = environment.resolve(System.getProperty("os.name", "")
                    .toLowerCase().contains("win") ? "Scripts/python.exe" : "bin/python");
            assertTrue(Files.isRegularFile(python));
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); }
                    catch (IOException failure) { throw new RuntimeException(failure); }
                });
            }
        }
    }

    private static byte[] zip(String name, String contents) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(contents.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return output.toByteArray();
    }
}
