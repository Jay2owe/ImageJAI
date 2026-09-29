package imagejai.install;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinReg;
import com.sun.jna.platform.win32.WinUser;
import imagejai.config.Constants;
import imagejai.config.Settings;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Installs the standalone ImageJAI Console from the runtime bundled in the
 * Fiji plugin. The environment is versioned and private; the global launcher
 * is switched only after a real {@code imagejai status} verification succeeds.
 */
public final class ConsoleBootstrap {
    public static final String MARKER = "ImageJAI Console managed launcher";
    private static final long PYTHON_CHECK_TIMEOUT_MS = 10_000L;
    private static final long VENV_TIMEOUT_MS = 120_000L;
    private static final long PIP_TIMEOUT_MS = 900_000L;
    private static final long VERIFY_TIMEOUT_MS = 60_000L;
    private static final String WINDOWS_ENV_KEY = "Environment";
    private static final int WM_SETTINGCHANGE = 0x001A;

    public enum State {
        NOT_INSTALLED,
        READY,
        UPDATE_AVAILABLE,
        BROKEN
    }

    public interface LogSink {
        void line(String line);
    }

    interface CommandRunner {
        CommandResult run(List<String> command, Path workingDirectory,
                          Map<String, String> environment, long timeoutMillis,
                          LogSink log) throws IOException, InterruptedException;
    }

    interface PathRegistrar {
        /** Return true only when this call added the directory. */
        boolean ensure(Path directory) throws IOException;
        void remove(Path directory) throws IOException;
    }

    interface WorkspaceInstaller {
        String ensureInstalled();
    }

    interface PythonFinder {
        List<String> find(LogSink log) throws Exception;
    }

    interface ManagedEnvironmentCreator {
        void create(Path versionRoot, Path environment, CommandRunner runner,
                    LogSink log) throws Exception;
    }

    static final class CommandResult {
        final int exitCode;
        final boolean timedOut;
        final String output;

        CommandResult(int exitCode, boolean timedOut, String output) {
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.output = output == null ? "" : output;
        }

        boolean ok() {
            return !timedOut && exitCode == 0;
        }
    }

    public static final class Inspection {
        public final State state;
        public final String installedVersion;
        public final String message;

        public Inspection(State state, String installedVersion, String message) {
            this.state = state;
            this.installedVersion = installedVersion == null ? "" : installedVersion;
            this.message = message;
        }
    }

    public static final class Plan {
        public final Path installRoot;
        public final Path launcher;
        public final String version;

        Plan(Path installRoot, Path launcher, String version) {
            this.installRoot = installRoot;
            this.launcher = launcher;
            this.version = version;
        }

        public String describe() {
            return "Install ImageJAI Console " + version + "?\n\n"
                    + "This will:\n"
                    + "- use a suitable installed Python, or download a private Python 3.12\n"
                    + "- create an isolated Python environment in\n  " + installRoot + "\n"
                    + "- download the declared Python dependencies\n"
                    + "- create the command\n  " + launcher + "\n"
                    + "- add its folder to your user PATH when needed\n"
                    + "- verify the installation with imagejai status\n\n"
                    + "No administrator access is requested. An internet connection is needed\n"
                    + "for first-time downloads. Provider sign-in remains separate.";
        }
    }

    public static final class InstallResult {
        public final Path launcher;
        public final String version;
        public final boolean pathAdded;

        InstallResult(Path launcher, String version, boolean pathAdded) {
            this.launcher = launcher;
            this.version = version;
            this.pathAdded = pathAdded;
        }
    }

    private static final class InstallState {
        String version;
        String launcher;
        String environment;
        String workspace;
        String installedAt;
        boolean pathAdded;
    }

    private final Path configRoot;
    private final Path consoleRoot;
    private final Path binDir;
    private final String version;
    private final boolean windows;
    private final CommandRunner commandRunner;
    private final PathRegistrar pathRegistrar;
    private final WorkspaceInstaller workspaceInstaller;
    private final PythonFinder pythonFinder;
    private final ManagedEnvironmentCreator managedEnvironmentCreator;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public ConsoleBootstrap() {
        this(Settings.getConfigDir(), Constants.VERSION,
                defaultBinDir(), new DefaultCommandRunner(), defaultPathRegistrar(),
                new WorkspaceInstaller() {
                    @Override public String ensureInstalled() {
                        Path workspace = Settings.getConfigDir().resolve("console")
                                .resolve(Constants.VERSION).resolve("workspace");
                        return BundledAgentWorkspace.ensureInstalled(workspace);
                    }
                });
    }

    ConsoleBootstrap(Path configRoot, String version, Path binDir,
                     CommandRunner commandRunner, PathRegistrar pathRegistrar,
                     WorkspaceInstaller workspaceInstaller) {
        this(configRoot, version, binDir, commandRunner, pathRegistrar,
                workspaceInstaller, null, null);
    }

    ConsoleBootstrap(Path configRoot, String version, Path binDir,
                     CommandRunner commandRunner, PathRegistrar pathRegistrar,
                     WorkspaceInstaller workspaceInstaller, PythonFinder pythonFinder,
                     ManagedEnvironmentCreator managedEnvironmentCreator) {
        this.configRoot = configRoot.toAbsolutePath().normalize();
        this.consoleRoot = this.configRoot.resolve("console");
        this.version = version;
        this.binDir = binDir.toAbsolutePath().normalize();
        this.windows = isWindows();
        this.commandRunner = commandRunner;
        this.pathRegistrar = pathRegistrar;
        this.workspaceInstaller = workspaceInstaller;
        this.pythonFinder = pythonFinder == null ? this::findPython : pythonFinder;
        this.managedEnvironmentCreator = managedEnvironmentCreator == null
                ? new ManagedPythonRuntime()::createEnvironment : managedEnvironmentCreator;
    }

    public Plan plan() {
        return new Plan(versionRoot(), launcherPath(), version);
    }

    public Inspection inspect() {
        InstallState saved = readState();
        Path launcher = launcherPath();
        if (saved == null && !Files.isRegularFile(launcher)) {
            return new Inspection(State.NOT_INSTALLED, "", "not installed");
        }
        if (saved == null) {
            return new Inspection(State.BROKEN, "", "launcher exists but installation state is missing");
        }
        if (!version.equals(saved.version)) {
            return new Inspection(State.UPDATE_AVAILABLE, saved.version,
                    "update available: " + saved.version + " to " + version);
        }
        Path env = Paths.get(saved.environment == null ? "" : saved.environment);
        Path workspace = Paths.get(saved.workspace == null ? "" : saved.workspace);
        if (!Files.isRegularFile(launcher)
                || !Files.isRegularFile(venvPython(env))
                || !Files.isRegularFile(workspace.resolve("console").resolve("requirements.txt"))) {
            return new Inspection(State.BROKEN, saved.version, "installation is incomplete");
        }
        return new Inspection(State.READY, saved.version, "ready");
    }

    public InstallResult install(LogSink log) throws Exception {
        return install(false, log);
    }

    public InstallResult repair(LogSink log) throws Exception {
        return install(true, log);
    }

    private InstallResult install(boolean repair, LogSink sink) throws Exception {
        LogSink log = sink == null ? line -> { } : sink;
        log.line((repair ? "Repairing" : "Installing") + " ImageJAI Console " + version);

        String workspace = workspaceInstaller.ensureInstalled();
        if (workspace == null) {
            throw new IOException("The plugin could not extract its bundled agent workspace.");
        }
        Path actualWorkspace = Paths.get(workspace).toAbsolutePath().normalize();
        Path requirements = actualWorkspace.resolve("console").resolve("requirements.txt");
        if (!Files.isRegularFile(requirements)) {
            throw new IOException("Bundled console requirements are missing. Reinstall the plugin.");
        }

        Path environment = environmentPath();
        Path environmentPython = venvPython(environment);
        InstallState previousInstall = readState();
        if (repair || !Files.isRegularFile(environmentPython)
                || previousInstall == null || !version.equals(previousInstall.version)) {
            Files.createDirectories(versionRoot());
            if (!Files.isRegularFile(environmentPython)) {
                List<String> python = pythonFinder.find(log);
                if (python == null) {
                    managedEnvironmentCreator.create(versionRoot(), environment,
                            commandRunner, log);
                } else {
                    log.line("Creating isolated Python environment...");
                    List<String> create = new ArrayList<String>(python);
                    create.addAll(Arrays.asList("-m", "venv", environment.toString()));
                    requireSuccess(commandRunner.run(create, versionRoot(),
                            Collections.<String, String>emptyMap(), VENV_TIMEOUT_MS, log),
                            "Python environment creation");
                }
                if (!Files.isRegularFile(environmentPython)) {
                    throw new IOException("Private Python environment was not created. Retry Install or Repair.");
                }
            }
            log.line("Installing console dependencies...");
            requireSuccess(commandRunner.run(Arrays.asList(
                            environmentPython.toString(), "-m", "pip", "install",
                            "--disable-pip-version-check", "--upgrade", "-r",
                            requirements.toString()),
                    versionRoot(), Collections.<String, String>emptyMap(),
                    PIP_TIMEOUT_MS, log), "Dependency installation");
        }

        Map<String, String> runtimeEnv = runtimeEnvironment(actualWorkspace);
        log.line("Verifying the console runtime...");
        requireSuccess(commandRunner.run(Arrays.asList(
                        environmentPython.toString(), "-m", "agent.console",
                        "--workspace", actualWorkspace.toString(), "status"),
                actualWorkspace, runtimeEnv, VERIFY_TIMEOUT_MS, log),
                "Console verification");

        Files.createDirectories(binDir);
        InstallState previous = readState();
        boolean pathAdded = pathRegistrar.ensure(binDir);
        byte[] previousLauncher = Files.isRegularFile(launcherPath())
                ? Files.readAllBytes(launcherPath()) : null;
        writeLauncher(actualWorkspace, environmentPython);
        try {
            log.line("Verifying the installed command...");
            requireSuccess(commandRunner.run(
                            launcherVerificationCommand(), actualWorkspace,
                            Collections.<String, String>emptyMap(), VERIFY_TIMEOUT_MS, log),
                    "Installed command verification");

            InstallState saved = new InstallState();
            saved.version = version;
            saved.launcher = launcherPath().toString();
            saved.environment = environment.toString();
            saved.workspace = actualWorkspace.toString();
            saved.installedAt = Instant.now().toString();
            saved.pathAdded = pathAdded || (previous != null && previous.pathAdded);
            writeState(saved);
        } catch (Exception failure) {
            restoreLauncher(previousLauncher);
            if (pathAdded) pathRegistrar.remove(binDir);
            throw failure;
        }
        log.line("Ready. Open a new terminal and type: imagejai");
        return new InstallResult(launcherPath(), version, pathAdded);
    }

    public void uninstall(LogSink sink) throws IOException {
        LogSink log = sink == null ? line -> { } : sink;
        InstallState saved = readState();
        Path launcher = launcherPath();
        if (Files.isRegularFile(launcher) && isManagedLauncher(launcher)) {
            Files.deleteIfExists(launcher);
            log.line("Removed " + launcher);
        }
        if (saved != null && saved.pathAdded) {
            pathRegistrar.remove(binDir);
            log.line("Removed the ImageJAI command folder from the user PATH.");
        }
        deleteTree(consoleRoot);
        log.line("Removed the standalone console. Fiji credentials and chats were kept.");
    }

    public void launch() throws IOException {
        Inspection inspection = inspect();
        if (inspection.state != State.READY) {
            throw new IOException("ImageJAI Console is not ready: " + inspection.message);
        }
        Path launcher = launcherPath();
        if (windows) {
            String command = "start \"ImageJAI Console\" \"" + launcher + "\"";
            new ProcessBuilder("cmd.exe", "/d", "/c", command).start();
            return;
        }
        if (isMac()) {
            new ProcessBuilder("open", "-a", "Terminal", launcher.toString()).start();
            return;
        }
        String terminal = firstOnPath("x-terminal-emulator", "gnome-terminal", "konsole", "xterm");
        if (terminal == null) {
            throw new IOException("No supported terminal application was found.");
        }
        new ProcessBuilder(terminal, "-e", launcher.toString()).start();
    }

    private List<String> findPython(LogSink log) throws Exception {
        List<List<String>> candidates = new ArrayList<List<String>>();
        String configured = System.getenv("IMAGEJAI_PYTHON");
        if (configured != null && !configured.trim().isEmpty()) {
            candidates.add(Collections.singletonList(configured.trim()));
        }
        String python = ProcessRunner.findOnPath("python");
        if (python != null) candidates.add(Collections.singletonList(python));
        String python3 = ProcessRunner.findOnPath("python3");
        if (python3 != null) candidates.add(Collections.singletonList(python3));
        if (windows) {
            String py = ProcessRunner.findOnPath("py");
            if (py != null) candidates.add(Arrays.asList(py, "-3"));
        }
        for (List<String> candidate : candidates) {
            List<String> command = new ArrayList<String>(candidate);
            command.addAll(Arrays.asList("-c",
                    "import sys; print('%d.%d' % sys.version_info[:2]); "
                            + "raise SystemExit(0 if (3,10) <= sys.version_info[:2] < (3,14) else 3)"));
            CommandResult result = commandRunner.run(command, configRoot,
                    Collections.<String, String>emptyMap(), PYTHON_CHECK_TIMEOUT_MS, line -> { });
            if (result.ok()) {
                log.line("Using Python " + result.output.trim() + ": " + candidate.get(0));
                return candidate;
            }
        }
        return null;
    }

    private void writeLauncher(Path workspace, Path python) throws IOException {
        Path launcher = launcherPath();
        String body;
        if (windows) {
            body = "@echo off\r\n"
                    + "rem " + MARKER + "\r\n"
                    + "setlocal\r\n"
                    + "set \"IMAGEJAI_AGENT_WORKSPACE=" + workspace + "\"\r\n"
                    + "set \"PYTHONPATH=" + workspace.getParent() + ";" + workspace
                    + ";%PYTHONPATH%\"\r\n"
                    + "\"" + python + "\" -m agent.console %*\r\n"
                    + "set \"IMAGEJAI_EXIT=%ERRORLEVEL%\"\r\n"
                    + "endlocal & exit /b %IMAGEJAI_EXIT%\r\n";
        } else {
            body = "#!/bin/sh\n"
                    + "# " + MARKER + "\n"
                    + "export IMAGEJAI_AGENT_WORKSPACE='" + shellQuote(workspace.toString()) + "'\n"
                    + "export PYTHONPATH='" + shellQuote(workspace.getParent().toString()) + ":"
                    + shellQuote(workspace.toString()) + "'${PYTHONPATH:+:$PYTHONPATH}\n"
                    + "exec '" + shellQuote(python.toString()) + "' -m agent.console \"$@\"\n";
        }
        writeAtomic(launcher, body.getBytes(StandardCharsets.UTF_8));
        if (!windows) {
            try {
                Set<PosixFilePermission> mode = EnumSet.of(
                        PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_EXECUTE,
                        PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_EXECUTE);
                Files.setPosixFilePermissions(launcher, mode);
            } catch (UnsupportedOperationException ignored) {
                launcher.toFile().setExecutable(true, false);
            }
        }
    }

    private List<String> launcherVerificationCommand() {
        if (windows) {
            String command = "\"" + launcherPath() + "\" status";
            return Arrays.asList("cmd.exe", "/d", "/c", command);
        }
        return Arrays.asList(launcherPath().toString(), "status");
    }

    private Map<String, String> runtimeEnvironment(Path workspace) {
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("IMAGEJAI_AGENT_WORKSPACE", workspace.toString());
        String existing = System.getenv("PYTHONPATH");
        String value = workspace.getParent() + java.io.File.pathSeparator + workspace;
        if (existing != null && !existing.isEmpty()) value += java.io.File.pathSeparator + existing;
        env.put("PYTHONPATH", value);
        return env;
    }

    private void restoreLauncher(byte[] previous) throws IOException {
        if (previous == null) Files.deleteIfExists(launcherPath());
        else writeAtomic(launcherPath(), previous);
    }

    private boolean isManagedLauncher(Path launcher) {
        try {
            String body = new String(Files.readAllBytes(launcher), StandardCharsets.UTF_8);
            return body.contains(MARKER);
        } catch (IOException ignored) {
            return false;
        }
    }

    private InstallState readState() {
        try {
            return gson.fromJson(new String(Files.readAllBytes(statePath()),
                    StandardCharsets.UTF_8), InstallState.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private void writeState(InstallState state) throws IOException {
        Files.createDirectories(consoleRoot);
        writeAtomic(statePath(), gson.toJson(state).getBytes(StandardCharsets.UTF_8));
    }

    private void requireSuccess(CommandResult result, String operation) throws IOException {
        if (result.ok()) return;
        String detail = result.timedOut ? "timed out" : "exited " + result.exitCode;
        String output = lastLines(result.output, 8);
        throw new IOException(operation + " " + detail
                + (output.isEmpty() ? "" : ":\n" + output));
    }

    private Path versionRoot() {
        return consoleRoot.resolve(version);
    }

    private Path environmentPath() {
        return versionRoot().resolve("venv");
    }

    private Path statePath() {
        return consoleRoot.resolve("install.json");
    }

    private Path launcherPath() {
        return binDir.resolve(windows ? "imagejai.cmd" : "imagejai");
    }

    private Path venvPython(Path environment) {
        return environment.resolve(windows ? "Scripts" : "bin")
                .resolve(windows ? "python.exe" : "python");
    }

    private static Path defaultBinDir() {
        if (isWindows()) {
            String local = System.getenv("LOCALAPPDATA");
            Path root = local == null || local.trim().isEmpty()
                    ? Paths.get(System.getProperty("user.home"), "AppData", "Local")
                    : Paths.get(local);
            return root.resolve("ImageJAI").resolve("bin");
        }
        return Paths.get(System.getProperty("user.home"), ".local", "bin");
    }

    private static PathRegistrar defaultPathRegistrar() {
        return isWindows() ? new WindowsPathRegistrar() : new PosixPathRegistrar();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    }

    private static String firstOnPath(String... commands) {
        for (String command : commands) {
            String found = ProcessRunner.findOnPath(command);
            if (found != null) return found;
        }
        return null;
    }

    private static String shellQuote(String value) {
        return value.replace("'", "'\"'\"'");
    }

    private static String lastLines(String value, int count) {
        if (value == null || value.trim().isEmpty()) return "";
        String[] lines = value.trim().split("\\r?\\n");
        StringBuilder out = new StringBuilder();
        for (int i = Math.max(0, lines.length - count); i < lines.length; i++) {
            if (out.length() > 0) out.append('\n');
            out.append(lines[i]);
        }
        return out.toString();
    }

    private static void writeAtomic(Path target, byte[] body) throws IOException {
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        Path temp = Files.createTempFile(target.getParent(), ".imagejai-console-", ".tmp");
        try {
            Files.write(temp, body);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private void deleteTree(Path target) throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        if (!absolute.startsWith(configRoot) || absolute.equals(configRoot)
                || !absolute.getFileName().toString().equals("console")) {
            throw new IOException("Refusing to remove unexpected console path: " + absolute);
        }
        if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) return;
        List<Path> paths = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> stream = Files.walk(absolute)) {
            stream.forEach(paths::add);
        }
        paths.sort(Comparator.reverseOrder());
        for (Path path : paths) Files.deleteIfExists(path);
    }

    private static final class DefaultCommandRunner implements CommandRunner {
        @Override
        public CommandResult run(List<String> command, Path workingDirectory,
                                 Map<String, String> environment, long timeoutMillis,
                                 LogSink log) throws IOException, InterruptedException {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (workingDirectory != null && Files.isDirectory(workingDirectory)) {
                builder.directory(workingDirectory.toFile());
            }
            builder.redirectErrorStream(true);
            builder.environment().putAll(environment);
            Process process = builder.start();
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            Thread pump = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        synchronized (captured) {
                            captured.write(line.getBytes(StandardCharsets.UTF_8));
                            captured.write('\n');
                        }
                        log.line(line);
                    }
                } catch (IOException ignored) {
                    // Process exit or forced timeout closes the stream.
                }
            }, "ImageJAI console installer output");
            pump.setDaemon(true);
            pump.start();
            boolean done = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!done) {
                process.destroy();
                if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            }
            pump.join(2_000L);
            String output;
            synchronized (captured) {
                output = new String(captured.toByteArray(), StandardCharsets.UTF_8);
            }
            return new CommandResult(done ? process.exitValue() : -1, !done, output);
        }
    }

    private static final class WindowsPathRegistrar implements PathRegistrar {
        @Override
        public boolean ensure(Path directory) throws IOException {
            try {
                String existing = readUserPath();
                if (containsPath(existing, directory, true)) return false;
                String updated = existing == null || existing.trim().isEmpty()
                        ? directory.toString()
                        : existing + ";" + directory;
                Advapi32Util.registrySetExpandableStringValue(
                        WinReg.HKEY_CURRENT_USER, WINDOWS_ENV_KEY, "Path", updated);
                broadcastEnvironmentChange();
                return true;
            } catch (RuntimeException failure) {
                throw new IOException("Could not update the Windows user PATH", failure);
            }
        }

        @Override
        public void remove(Path directory) throws IOException {
            try {
                String existing = readUserPath();
                if (existing == null) return;
                List<String> kept = new ArrayList<String>();
                for (String item : existing.split(";")) {
                    if (!samePath(item, directory, true) && !item.trim().isEmpty()) kept.add(item);
                }
                Advapi32Util.registrySetExpandableStringValue(
                        WinReg.HKEY_CURRENT_USER, WINDOWS_ENV_KEY, "Path", String.join(";", kept));
                broadcastEnvironmentChange();
            } catch (RuntimeException failure) {
                throw new IOException("Could not update the Windows user PATH", failure);
            }
        }

        private static String readUserPath() {
            if (!Advapi32Util.registryValueExists(
                    WinReg.HKEY_CURRENT_USER, WINDOWS_ENV_KEY, "Path")) return "";
            Object value = Advapi32Util.registryGetValue(
                    WinReg.HKEY_CURRENT_USER, WINDOWS_ENV_KEY, "Path");
            return value == null ? "" : String.valueOf(value);
        }

        private static void broadcastEnvironmentChange() {
            Memory environment = new Memory(("Environment".length() + 1L) * 2L);
            environment.setWideString(0, "Environment");
            User32.INSTANCE.SendMessageTimeout(
                    WinUser.HWND_BROADCAST, WM_SETTINGCHANGE,
                    new WinDef.WPARAM(0),
                    new WinDef.LPARAM(Pointer.nativeValue(environment)),
                    WinUser.SMTO_ABORTIFHUNG, 5_000,
                    new WinDef.DWORDByReference());
        }
    }

    private static final class PosixPathRegistrar implements PathRegistrar {
        private static final String START = "# >>> ImageJAI Console PATH >>>";
        private static final String END = "# <<< ImageJAI Console PATH <<<";

        @Override
        public boolean ensure(Path directory) throws IOException {
            String current = System.getenv("PATH");
            if (containsPath(current, directory, false)) return false;
            Path profile = profilePath();
            String existing = Files.isRegularFile(profile)
                    ? new String(Files.readAllBytes(profile), StandardCharsets.UTF_8) : "";
            if (existing.contains(START)) return false;
            String block = (existing.endsWith("\n") || existing.isEmpty() ? "" : "\n")
                    + START + "\nexport PATH=\"$HOME/.local/bin:$PATH\"\n" + END + "\n";
            writeAtomic(profile, (existing + block).getBytes(StandardCharsets.UTF_8));
            return true;
        }

        @Override
        public void remove(Path directory) throws IOException {
            Path profile = profilePath();
            if (!Files.isRegularFile(profile)) return;
            String existing = new String(Files.readAllBytes(profile), StandardCharsets.UTF_8);
            int start = existing.indexOf(START);
            int end = existing.indexOf(END);
            if (start < 0 || end < start) return;
            end += END.length();
            if (end < existing.length() && existing.charAt(end) == '\r') end++;
            if (end < existing.length() && existing.charAt(end) == '\n') end++;
            writeAtomic(profile, (existing.substring(0, start) + existing.substring(end))
                    .getBytes(StandardCharsets.UTF_8));
        }

        private static Path profilePath() {
            String name = isMac() ? ".zprofile" : ".profile";
            return Paths.get(System.getProperty("user.home"), name);
        }
    }

    static boolean containsPath(String pathList, Path expected, boolean caseInsensitive) {
        if (pathList == null) return false;
        String separator = caseInsensitive ? ";" : java.io.File.pathSeparator;
        for (String item : pathList.split(java.util.regex.Pattern.quote(separator))) {
            if (samePath(item, expected, caseInsensitive)) return true;
        }
        return false;
    }

    private static boolean samePath(String raw, Path expected, boolean caseInsensitive) {
        if (raw == null || raw.trim().isEmpty()) return false;
        String cleaned = raw.trim();
        if (cleaned.length() >= 2 && cleaned.startsWith("\"") && cleaned.endsWith("\"")) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        try {
            String left = Paths.get(cleaned).toAbsolutePath().normalize().toString();
            String right = expected.toAbsolutePath().normalize().toString();
            return caseInsensitive ? left.equalsIgnoreCase(right) : left.equals(right);
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
