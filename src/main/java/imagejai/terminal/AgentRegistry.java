package imagejai.terminal;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.jediterm.terminal.model.LinesBuffer;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.JediTermWidget;
import ij.IJ;
import imagejai.engine.AgentLauncher;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Loader for bundled per-agent command, clear, and user-command registries.
 */
public final class AgentRegistry {
    private static final long USER_COMMAND_CACHE_MS = 5000L;
    public static final int MAX_USER_COMMAND_DIRECTORY_ENTRIES = 4096;
    public static final int MAX_USER_COMMANDS = 512;
    public static final int MAX_USER_COMMAND_CACHE_ENTRIES = 64;
    private static final String DEFAULT_ID = "default";
    private static final Map<String, CachedCommands> USER_COMMAND_CACHE =
            new LinkedHashMap<String, CachedCommands>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedCommands> eldest) {
                    return size() > MAX_USER_COMMAND_CACHE_ENTRIES;
                }
            };

    interface DirectorySource {
        DirectoryStream<Path> open(Path path) throws IOException;
    }

    interface PathProbe {
        BasicFileAttributes readAttributes(Path path) throws IOException;
    }

    private static final DirectorySource FILE_DIRECTORY_SOURCE = Files::newDirectoryStream;
    private static final PathProbe FILE_PATH_PROBE = AgentRegistry::readAttributes;

    private AgentRegistry() {
    }

    public static String agentId(AgentLauncher.AgentInfo info) {
        if (info == null || info.command == null || info.command.trim().isEmpty()) {
            return DEFAULT_ID;
        }
        String rawCommand = info.command.trim().toLowerCase(Locale.ROOT);
        String flags = info.contextFlags == null ? "" : info.contextFlags.toLowerCase(Locale.ROOT);
        if (isGemmaWrapper(rawCommand) && flags.contains("--style claude")) {
            return "gemma4_31b_claude";
        }
        if (isGemmaWrapper(rawCommand)) {
            return "gemma4_31b";
        }
        String provider = providerFromAgentCli(rawCommand);
        if (!provider.isEmpty()) {
            return "provider_" + slug(provider);
        }
        String command = rawCommand.split("\\s+")[0];
        if (command.endsWith("_agent")) {
            command = command.substring(0, command.length() - "_agent".length());
        }
        String slug = slug(command);
        return slug.isEmpty() ? DEFAULT_ID : slug;
    }

    private static boolean isGemmaWrapper(String command) {
        return "gemma4_31b_agent".equals(command)
                || "imagejai_agent".equals(command)
                || command.contains(" -m gemma4_31b")
                || command.contains(" -m agent.gemma4_31b");
    }

    private static String providerFromAgentCli(String command) {
        if (!command.contains("agent.providers.agent_cli")) {
            return "";
        }
        List<String> tokens = shellLikeTokens(command);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if ("--provider".equals(token)) {
                return i + 1 < tokens.size() ? tokens.get(i + 1) : "";
            }
            if (token.startsWith("--provider=")) {
                return token.substring("--provider=".length());
            }
        }
        return "";
    }

    private static List<String> shellLikeTokens(String text) {
        List<String> tokens = new ArrayList<String>();
        if (text == null) {
            return tokens;
        }
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == quote) {
                    quoted = false;
                } else {
                    current.append(c);
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quoted = true;
                quote = c;
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    private static String slug(String value) {
        return value == null ? "" : value.replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+", "")
                .replaceAll("_+$", "");
    }

    public static List<CommandEntry> builtInCommands(AgentLauncher.AgentInfo info) {
        String id = agentId(info);
        String resource = "/agents/" + id + "/commands.json";
        InputStream in = AgentRegistry.class.getResourceAsStream(resource);
        if (in == null) {
            return Collections.emptyList();
        }
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonArray array = JsonParser.parseReader(reader).getAsJsonArray();
            List<CommandEntry> commands = new ArrayList<CommandEntry>();
            for (JsonElement element : array) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject obj = element.getAsJsonObject();
                String command = stringValue(obj, "command");
                if (command.isEmpty()) {
                    continue;
                }
                commands.add(new CommandEntry(command, stringValue(obj, "description")));
            }
            return commands;
        } catch (Exception e) {
            IJ.log("[ImageJAI-Term] Failed to load command registry " + resource
                    + ": " + e.getMessage());
            return Collections.emptyList();
        }
    }

    public static Pattern clearPattern(AgentLauncher.AgentInfo info) {
        String id = agentId(info);
        String resource = "/agents/" + id + "/clear.json";
        InputStream in = AgentRegistry.class.getResourceAsStream(resource);
        if (in == null) {
            return null;
        }
        try (InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            String regex = stringValue(root, "match");
            return regex.isEmpty() ? null : Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            IJ.log("[ImageJAI-Term] Invalid clear matcher in " + resource + ": " + e.getMessage());
            return null;
        } catch (Exception e) {
            IJ.log("[ImageJAI-Term] Failed to load clear registry " + resource
                    + ": " + e.getMessage());
            return null;
        }
    }

    public static List<CommandEntry> userCommands(AgentLauncher.AgentInfo info, File workspace) {
        return userCommandsResult(info, workspace).commands();
    }

    public static UserCommandsResult userCommandsResult(AgentLauncher.AgentInfo info,
                                                         File workspace) {
        return userCommandsResult(info, workspace, FILE_DIRECTORY_SOURCE,
                FILE_PATH_PROBE, System.currentTimeMillis());
    }

    static UserCommandsResult userCommandsResult(AgentLauncher.AgentInfo info, File workspace,
                                                  DirectorySource directorySource, long now) {
        return userCommandsResult(info, workspace, directorySource, FILE_PATH_PROBE, now);
    }

    static UserCommandsResult userCommandsResult(AgentLauncher.AgentInfo info, File workspace,
                                                  DirectorySource directorySource,
                                                  PathProbe pathProbe, long now) {
        String id = agentId(info);
        String cacheKey = id + "|" + (workspace == null ? "" : workspace.getAbsolutePath());
        synchronized (USER_COMMAND_CACHE) {
            CachedCommands cached = USER_COMMAND_CACHE.get(cacheKey);
            if (cached != null && now - cached.loadedAtMs < USER_COMMAND_CACHE_MS) {
                return cached.result;
            }
        }

        UserCommandsResult result = scanUserCommands(
                id, workspace, directorySource, pathProbe);
        synchronized (USER_COMMAND_CACHE) {
            USER_COMMAND_CACHE.put(cacheKey, new CachedCommands(now, result));
        }
        return result;
    }

    @SuppressWarnings({"deprecation", "removal"})
    public static String readScrollback(JediTermWidget terminal, int limit) {
        if (terminal == null) {
            return "";
        }
        TerminalTextBuffer buffer = terminal.getTerminalTextBuffer();
        List<String> lines = new ArrayList<String>();
        buffer.lock();
        try {
            appendLast(lines, buffer.getHistoryBuffer(), limit);
            appendLast(lines, buffer.getScreenBuffer(), limit);
        } catch (RuntimeException e) {
            IJ.log("[ImageJAI-Term] Failed to read terminal scrollback: " + e.getMessage());
        } finally {
            buffer.unlock();
        }

        int start = Math.max(0, lines.size() - limit);
        StringBuilder out = new StringBuilder();
        for (int i = start; i < lines.size(); i++) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(lines.get(i));
        }
        return out.toString();
    }

    private static UserCommandsResult scanUserCommands(String id, File workspace,
                                                        DirectorySource directorySource,
                                                        PathProbe pathProbe) {
        List<CommandEntry> commands = new ArrayList<CommandEntry>();
        int[] inspected = new int[]{0};
        int[] directories = new int[]{0};
        if ("claude".equals(id) && workspace != null) {
            File dir = new File(workspace, ".claude" + File.separator + "commands");
            scanFiles(dir.toPath(), ".md", "/", commands, directorySource, pathProbe,
                    inspected, directories);
        } else if ("gemma4_31b".equals(id) || "gemma4_31b_claude".equals(id)) {
            File dir = new File(System.getProperty("user.home", ""),
                    ".config" + File.separator + "imagej-ai" + File.separator
                            + "gemma4_31b" + File.separator + ".ccommands");
            scanFiles(dir.toPath(), null, "/ccommands ", commands, directorySource, pathProbe,
                    inspected, directories);
        }
        return new UserCommandsResult(commands, directories[0], inspected[0]);
    }

    private static void scanFiles(Path dir, String requiredSuffix,
                                  String commandPrefix, List<CommandEntry> out,
                                  DirectorySource directorySource, PathProbe pathProbe,
                                  int[] inspected,
                                  int[] directories) {
        if (dir == null) {
            return;
        }
        try {
            if (!pathProbe.readAttributes(dir).isDirectory()) {
                throw new CommandScanException("not_a_directory", dir,
                        "Command path is not a directory.", inspected[0]);
            }
        } catch (NoSuchFileException e) {
            return;
        } catch (IOException e) {
            throw new CommandScanException("directory_unreadable", dir,
                    "Could not inspect command directory: " + message(e), e, inspected[0]);
        }
        List<Path> sorted = new ArrayList<Path>();
        directories[0]++;
        try (DirectoryStream<Path> stream = directorySource.open(dir)) {
            for (Path path : stream) {
                inspected[0]++;
                if (inspected[0] > MAX_USER_COMMAND_DIRECTORY_ENTRIES) {
                    throw new CommandScanException("directory_entry_cap", dir,
                            "Command directory contains more than "
                                    + MAX_USER_COMMAND_DIRECTORY_ENTRIES + " entries.",
                            inspected[0]);
                }
                BasicFileAttributes attributes = pathProbe.readAttributes(path);
                if (!attributes.isRegularFile()) {
                    continue;
                }
                String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
                if (requiredSuffix != null && !name.endsWith(requiredSuffix)) {
                    continue;
                }
                if (requiredSuffix == null
                        && !(name.endsWith(".md") || name.endsWith(".txt"))) {
                    continue;
                }
                if (sorted.size() >= MAX_USER_COMMANDS) {
                    throw new CommandScanException("command_cap", dir,
                            "Command directory contains more than "
                                    + MAX_USER_COMMANDS + " supported commands.", inspected[0]);
                }
                sorted.add(path);
            }
        } catch (NoSuchFileException e) {
            return;
        } catch (IOException e) {
            throw new CommandScanException("directory_unreadable", dir,
                    "Could not read command directory: " + message(e), e, inspected[0]);
        }
        Collections.sort(sorted, (a, b) -> a.getFileName().toString()
                .compareToIgnoreCase(b.getFileName().toString()));
        for (Path path : sorted) {
            String stem = stripExtension(path.getFileName().toString());
            out.add(new CommandEntry(commandPrefix + stem,
                    path.toAbsolutePath().normalize().toString()));
        }
    }

    private static String message(Exception e) {
        String value = e.getMessage();
        return value == null || value.trim().isEmpty()
                ? e.getClass().getSimpleName()
                : value;
    }

    private static BasicFileAttributes readAttributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class);
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    @SuppressWarnings("removal")
    private static void appendLast(List<String> target, LinesBuffer source, int limit) {
        int count = source.getLineCount();
        int start = Math.max(0, count - limit);
        for (int i = start; i < count; i++) {
            target.add(source.getLineText(i));
        }
    }

    private static String stringValue(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || !obj.get(key).isJsonPrimitive()) {
            return "";
        }
        return obj.get(key).getAsString();
    }

    public static final class CommandEntry {
        public final String command;
        public final String description;

        public CommandEntry(String command, String description) {
            this.command = command == null ? "" : command;
            this.description = description == null ? "" : description;
        }
    }

    public static final class UserCommandsResult {
        private final List<CommandEntry> commands;
        private final int directoriesRead;
        private final int entriesInspected;

        UserCommandsResult(List<CommandEntry> commands, int directoriesRead,
                           int entriesInspected) {
            this.commands = Collections.unmodifiableList(
                    new ArrayList<CommandEntry>(commands));
            this.directoriesRead = directoriesRead;
            this.entriesInspected = entriesInspected;
        }

        public List<CommandEntry> commands() { return commands; }
        public int directoriesRead() { return directoriesRead; }
        public int entriesInspected() { return entriesInspected; }
    }

    public static final class CommandScanException extends IllegalStateException {
        private final String code;
        private final Path path;
        private final int entriesInspected;

        CommandScanException(String code, Path path, String message,
                             int entriesInspected) {
            this(code, path, message, null, entriesInspected);
        }

        CommandScanException(String code, Path path, String message, Throwable cause,
                             int entriesInspected) {
            super(message, cause);
            this.code = code;
            this.path = path;
            this.entriesInspected = entriesInspected;
        }

        public String code() { return code; }
        public Path path() { return path; }
        public int entriesInspected() { return entriesInspected; }
    }

    private static final class CachedCommands {
        final long loadedAtMs;
        final UserCommandsResult result;

        CachedCommands(long loadedAtMs, UserCommandsResult result) {
            this.loadedAtMs = loadedAtMs;
            this.result = result;
        }
    }
}
