package imagejai.install;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConsoleBootstrapTest {

    @Test
    public void installCreatesVerifiedGlobalLauncherAndState() throws Exception {
        Fixture fixture = new Fixture(false);
        List<String> log = new ArrayList<String>();

        ConsoleBootstrap.InstallResult result = fixture.bootstrap.install(log::add);

        assertTrue(Files.isRegularFile(result.launcher));
        String launcher = new String(Files.readAllBytes(result.launcher), StandardCharsets.UTF_8);
        assertTrue(launcher.contains(ConsoleBootstrap.MARKER));
        assertTrue(launcher.contains("-m agent.console"));
        assertEquals(1, fixture.paths.ensureCalls);
        assertEquals(ConsoleBootstrap.State.READY, fixture.bootstrap.inspect().state);
        assertTrue(log.stream().anyMatch(line -> line.contains("Ready")));
    }

    @Test
    public void missingSystemPythonUsesPrivateManagedRuntime() throws Exception {
        Fixture fixture = new Fixture(false, true);
        List<String> log = new ArrayList<String>();

        fixture.bootstrap.install(log::add);

        assertEquals(1, fixture.managedCreates);
        assertEquals(ConsoleBootstrap.State.READY, fixture.bootstrap.inspect().state);
        assertTrue(log.stream().anyMatch(line -> line.contains("managed Python")));
    }

    @Test
    public void failedManagedInstallNeverActivatesLauncher() throws Exception {
        Fixture fixture = new Fixture(false, true, true);

        boolean failed = false;
        try {
            fixture.bootstrap.install(line -> { });
        } catch (java.io.IOException expected) {
            failed = true;
        }

        assertTrue(failed);
        assertFalse(Files.exists(fixture.bin.resolve(isWindows() ? "imagejai.cmd" : "imagejai")));
        assertFalse(Files.exists(fixture.config.resolve("console").resolve("install.json")));
        assertEquals(0, fixture.paths.ensureCalls);
    }

    @Test
    public void retriesDependenciesAfterInterruptedFirstInstall() throws Exception {
        Fixture fixture = new Fixture(false, false, false, true);
        try {
            fixture.bootstrap.install(line -> { });
        } catch (java.io.IOException expected) {
            // The first dependency install leaves a Python environment behind.
        }

        fixture.bootstrap.install(line -> { });

        assertEquals(2, fixture.runner.pipCalls);
        assertEquals(ConsoleBootstrap.State.READY, fixture.bootstrap.inspect().state);
    }

    @Test
    public void liveJarOnlyInstallWithoutSystemPythonWhenExplicitlyEnabled() throws Exception {
        if (!Boolean.getBoolean("imagejai.testLiveConsoleInstall")) return;
        Path jar = Paths.get("target", "imagej-ai-0.5.0.jar").toAbsolutePath();
        assertTrue(Files.isRegularFile(jar));
        Path root = Files.createTempDirectory("imagejai-clean-install-");
        try {
            Path workspace = root.resolve("agent");
            try (JarFile bundled = new JarFile(jar.toFile())) {
                java.util.Enumeration<JarEntry> entries = bundled.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (!entry.getName().startsWith("agent-runtime/") || entry.isDirectory()) continue;
                    Path destination = workspace.resolve(
                            entry.getName().substring("agent-runtime/".length())).normalize();
                    assertTrue(destination.startsWith(workspace));
                    Files.createDirectories(destination.getParent());
                    try (java.io.InputStream input = bundled.getInputStream(entry)) {
                        Files.copy(input, destination);
                    }
                }
            }
            assertTrue(Files.isRegularFile(workspace.resolve("console/requirements.txt")));
            ConsoleBootstrap.CommandRunner realRunner = (command, cwd, variables, timeout, log) -> {
                Path outputFile = Files.createTempFile(root, "console-command-", ".log");
                try {
                    ProcessBuilder builder = new ProcessBuilder(command)
                            .directory(cwd.toFile()).redirectErrorStream(true)
                            .redirectOutput(outputFile.toFile());
                    builder.environment().putAll(variables);
                    builder.environment().put("IMAGEJAI_HOME", root.resolve("user-home").toString());
                    Process child = builder.start();
                    boolean done = child.waitFor(timeout, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (!done) child.destroyForcibly();
                    String output = Files.readString(outputFile);
                    return new ConsoleBootstrap.CommandResult(done ? child.exitValue() : -1,
                            !done, output);
                } finally {
                    Files.deleteIfExists(outputFile);
                }
            };
            ConsoleBootstrap bootstrap = new ConsoleBootstrap(root.resolve("config"),
                    "9.8.7", root.resolve("bin"), realRunner, new FakePathRegistrar(),
                    () -> workspace.toString(), log -> null, null);

            bootstrap.install(line -> { });

            assertEquals(ConsoleBootstrap.State.READY, bootstrap.inspect().state);
            assertTrue(Files.isRegularFile(root.resolve("bin")
                    .resolve(isWindows() ? "imagejai.cmd" : "imagejai")));
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(root)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try { Files.deleteIfExists(path); }
                    catch (java.io.IOException failure) { throw new RuntimeException(failure); }
                });
            }
        }
    }

    @Test
    public void failedVerificationRestoresPreviousLauncherAndPath() throws Exception {
        Fixture fixture = new Fixture(true);
        Path launcher = fixture.bin.resolve(isWindows() ? "imagejai.cmd" : "imagejai");
        Files.createDirectories(launcher.getParent());
        byte[] previous = (isWindows() ? "@echo old\r\n" : "#!/bin/sh\necho old\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(launcher, previous);

        boolean failed = false;
        try {
            fixture.bootstrap.install(line -> { });
        } catch (Exception expected) {
            failed = true;
        }

        assertTrue(failed);
        assertEquals(new String(previous, StandardCharsets.UTF_8),
                new String(Files.readAllBytes(launcher), StandardCharsets.UTF_8));
        assertEquals(1, fixture.paths.removeCalls);
        assertFalse(Files.exists(fixture.config.resolve("console").resolve("install.json")));
    }

    @Test
    public void failedStateWriteRestoresPreviousLauncherAndPath() throws Exception {
        Fixture fixture = new Fixture(false);
        Path launcher = fixture.bin.resolve(isWindows() ? "imagejai.cmd" : "imagejai");
        Files.createDirectories(launcher.getParent());
        byte[] previous = (isWindows() ? "@echo old\r\n" : "#!/bin/sh\necho old\n")
                .getBytes(StandardCharsets.UTF_8);
        Files.write(launcher, previous);
        Files.createDirectories(fixture.config.resolve("console").resolve("install.json"));

        boolean failed = false;
        try {
            fixture.bootstrap.install(line -> { });
        } catch (Exception expected) {
            failed = true;
        }

        assertTrue(failed);
        assertEquals(new String(previous, StandardCharsets.UTF_8),
                new String(Files.readAllBytes(launcher), StandardCharsets.UTF_8));
        assertEquals(1, fixture.paths.removeCalls);
    }

    @Test
    public void pathDetectionIsNormalisedAndCaseInsensitiveOnWindows() throws Exception {
        Path expected = Files.createTempDirectory("imagejai-bin");
        String differentCase = expected.toString().toUpperCase();
        assertTrue(ConsoleBootstrap.containsPath(
                "C:\\Elsewhere;" + differentCase, expected, true));
        assertFalse(ConsoleBootstrap.containsPath("C:\\Elsewhere", expected, true));
    }

    @Test
    public void uninstallRemovesManagedRuntimeButKeepsChatsAndCredentials() throws Exception {
        Fixture fixture = new Fixture(false);
        fixture.bootstrap.install(line -> { });
        Path chat = fixture.config.resolve("sessions").resolve("chat.json");
        Path credential = fixture.config.resolve("secrets").resolve("openai.cred");
        Files.createDirectories(chat.getParent());
        Files.createDirectories(credential.getParent());
        Files.write(chat, "{}".getBytes(StandardCharsets.UTF_8));
        Files.write(credential, "protected".getBytes(StandardCharsets.UTF_8));

        fixture.bootstrap.uninstall(line -> { });

        assertFalse(Files.exists(fixture.config.resolve("console")));
        assertTrue(Files.exists(chat));
        assertTrue(Files.exists(credential));
    }

    private static final class Fixture {
        final Path root = Files.createTempDirectory("imagejai-console-bootstrap");
        final Path config = root.resolve("config");
        final Path workspace = root.resolve("bundled-agent").resolve("agent");
        final Path bin = root.resolve("bin");
        final FakePathRegistrar paths = new FakePathRegistrar();
        final FakeRunner runner;
        final ConsoleBootstrap bootstrap;
        int managedCreates;

        Fixture(boolean failInstalledCommand) throws Exception {
            this(failInstalledCommand, false);
        }

        Fixture(boolean failInstalledCommand, boolean noPython) throws Exception {
            this(failInstalledCommand, noPython, false);
        }

        Fixture(boolean failInstalledCommand, boolean noPython,
                boolean failManagedCreation) throws Exception {
            this(failInstalledCommand, noPython, failManagedCreation, false);
        }

        Fixture(boolean failInstalledCommand, boolean noPython,
                boolean failManagedCreation, boolean failPipOnce) throws Exception {
            Files.createDirectories(workspace.resolve("console"));
            Files.write(workspace.resolve("ij.py"), "# client".getBytes(StandardCharsets.UTF_8));
            Files.write(workspace.resolve("console").resolve("requirements.txt"),
                    "textual>=6.2,<7\n".getBytes(StandardCharsets.UTF_8));
            runner = new FakeRunner(failInstalledCommand, failPipOnce);
            if (noPython) {
                bootstrap = new ConsoleBootstrap(config, "9.8.7", bin, runner, paths,
                        () -> workspace.toString(), log -> null,
                        (root, env, commandRunner, log) -> {
                            managedCreates++;
                            log.line("Using managed Python");
                            if (failManagedCreation) throw new java.io.IOException("download failed");
                            Path python = env.resolve(isWindows() ? "Scripts" : "bin")
                                    .resolve(isWindows() ? "python.exe" : "python");
                            Files.createDirectories(python.getParent());
                            Files.write(python, new byte[] {0});
                        });
            } else {
                bootstrap = new ConsoleBootstrap(config, "9.8.7", bin, runner, paths,
                        () -> workspace.toString());
            }
        }
    }

    private static final class FakeRunner implements ConsoleBootstrap.CommandRunner {
        private final boolean failInstalledCommand;
        private final boolean failPipOnce;
        int pipCalls;

        FakeRunner(boolean failInstalledCommand) {
            this(failInstalledCommand, false);
        }

        FakeRunner(boolean failInstalledCommand, boolean failPipOnce) {
            this.failInstalledCommand = failInstalledCommand;
            this.failPipOnce = failPipOnce;
        }

        @Override
        public ConsoleBootstrap.CommandResult run(
                List<String> command, Path cwd, Map<String, String> environment,
                long timeout, ConsoleBootstrap.LogSink log) throws java.io.IOException {
            int venv = command.indexOf("venv");
            if (venv >= 0 && venv + 1 < command.size()) {
                Path environmentPath = java.nio.file.Paths.get(command.get(venv + 1));
                Path python = environmentPath.resolve(isWindows() ? "Scripts" : "bin")
                        .resolve(isWindows() ? "python.exe" : "python");
                Files.createDirectories(python.getParent());
                Files.write(python, new byte[] {0});
            }
            boolean installedCommand = (isWindows() && "cmd.exe".equalsIgnoreCase(command.get(0)))
                    || (!isWindows() && command.get(0).endsWith("imagejai"));
            if (installedCommand && failInstalledCommand) {
                return new ConsoleBootstrap.CommandResult(2, false, "verification failed");
            }
            if (command.contains("pip") && ++pipCalls == 1 && failPipOnce) {
                return new ConsoleBootstrap.CommandResult(2, false, "download interrupted");
            }
            String output = command.contains("-c") ? "3.12\n" : "ok\n";
            log.line(output.trim());
            return new ConsoleBootstrap.CommandResult(0, false, output);
        }
    }

    private static final class FakePathRegistrar implements ConsoleBootstrap.PathRegistrar {
        int ensureCalls;
        int removeCalls;

        @Override public boolean ensure(Path directory) {
            ensureCalls++;
            return true;
        }

        @Override public void remove(Path directory) {
            removeCalls++;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
