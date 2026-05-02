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
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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
    private static final String DEFAULT_ID = "default";
    private static final Map<String, CachedCommands> USER_COMMAND_CACHE =
            new HashMap<String, CachedCommands>();

    private AgentRegistry() {
    }

    public static String agentId(AgentLauncher.AgentInfo info) {
        if (info == null || info.command == null || info.command.trim().isEmpty()) {
            return DEFAULT_ID;
        }
        String command = info.command.trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
        String flags = info.contextFlags == null ? "" : info.contextFlags.toLowerCase(Locale.ROOT);
        if ("gemma4_31b_agent".equals(command) && flags.contains("--style claude")) {
            return "gemma4_31b_claude";
        }
        if (command.endsWith("_agent")) {
            command = command.substring(0, command.length() - "_agent".length());
        }
        String slug = command.replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+", "")
                .replaceAll("_+$", "");
        return slug.isEmpty() ? DEFAULT_ID : slug;
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
        String id = agentId(info);
        String cacheKey = id + "|" + (workspace == null ? "" : workspace.getAbsolutePath());
        long now = System.currentTimeMillis();
        synchronized (USER_COMMAND_CACHE) {
            CachedCommands cached = USER_COMMAND_CACHE.get(cacheKey);
            if (cached != null && now - cached.loadedAtMs < USER_COMMAND_CACHE_MS) {
                return cached.commands;
            }
        }

        List<CommandEntry> commands = scanUserCommands(id, workspace);
        List<CommandEntry> immutable = Collections.unmodifiableList(commands);
        synchronized (USER_COMMAND_CACHE) {
            USER_COMMAND_CACHE.put(cacheKey, new CachedCommands(now, immutable));
        }
        return immutable;
    }

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

    private static List<CommandEntry> scanUserCommands(String id, File workspace) {
        List<CommandEntry> commands = new ArrayList<CommandEntry>();
        if ("claude".equals(id) && workspace != null) {
            File dir = new File(workspace, ".claude" + File.separator + "commands");
            scanFiles(dir, ".md", "/", commands);
        } else if ("gemma4_31b".equals(id) || "gemma4_31b_claude".equals(id)) {
            File dir = new File(System.getProperty("user.home", ""),
                    ".config" + File.separator + "imagej-ai" + File.separator
                            + "gemma4_31b" + File.separator + ".ccommands");
            scanFiles(dir, null, "/ccommands ", commands);
        }
        return commands;
    }

    private static void scanFiles(File dir, String requiredSuffix,
                                  String commandPrefix, List<CommandEntry> out) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        List<File> sorted = new ArrayList<File>();
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            String name = file.getName().toLowerCase(Locale.ROOT);
            if (requiredSuffix != null && !name.endsWith(requiredSuffix)) {
                continue;
            }
            if (requiredSuffix == null && !(name.endsWith(".md") || name.endsWith(".txt"))) {
                continue;
            }
            sorted.add(file);
        }
        Collections.sort(sorted, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for (File file : sorted) {
            String stem = stripExtension(file.getName());
            out.add(new CommandEntry(commandPrefix + stem, file.getAbsolutePath()));
        }
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

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

    private static final class CachedCommands {
        final long loadedAtMs;
        final List<CommandEntry> commands;

        CachedCommands(long loadedAtMs, List<CommandEntry> commands) {
            this.loadedAtMs = loadedAtMs;
            this.commands = commands;
        }
    }
}
