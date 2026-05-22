package imagejai.engine;

import ij.IJ;
import imagejai.config.PrivacyPosture;
import imagejai.config.Settings;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Detects and launches external AI CLI agents (Claude Code, Aider, etc.)
 * in a terminal window with the agent workspace as working directory.
 *
 * <p>Launch contract (stage 02 of embedded-agent-widget): callers use
 * {@link #launch(AgentInfo, Mode)}, which returns an {@link AgentSession}
 * handle. The legacy {@link #launchAgent(AgentInfo)} boolean return is
 * preserved as a deprecated delegate while call sites migrate.
 */
public class AgentLauncher {

    public static final String LOCAL_ASSISTANT_NAME = "Local Assistant";

    /** How an agent should be launched. */
    public enum Mode {
        /** Detached external terminal — today's default. */
        EXTERNAL,
        /** Embedded PTY inside the plugin frame — landed in stage 05. */
        EMBEDDED
    }

    /**
     * Represents a detected CLI agent.
     */
    public static class AgentInfo {
        public final String name;
        public final String command;
        public final String description;
        public final String executablePath;
        public final String contextFlags;
        private final boolean local;
        private final String defaultOllamaModel;

        public AgentInfo(String name, String command, String description,
                         String executablePath, String contextFlags) {
            this(name, command, description, executablePath, contextFlags, false, "");
        }

        public AgentInfo(String name, String command, String description,
                         String executablePath, String contextFlags,
                         boolean local, String defaultOllamaModel) {
            this.name = name;
            this.command = command;
            this.description = description;
            this.executablePath = executablePath;
            this.contextFlags = contextFlags;
            this.local = local;
            this.defaultOllamaModel = defaultOllamaModel == null ? "" : defaultOllamaModel;
        }

        public boolean isLocal() {
            return local;
        }

        public boolean isOllama() {
            String commandText = command == null ? "" : command.toLowerCase(Locale.ROOT);
            return commandText.contains("ollama")
                    || commandText.contains("gemma4_31b_agent")
                    || !defaultOllamaModel.trim().isEmpty();
        }

        public String defaultOllamaModel() {
            return defaultOllamaModel;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    // Known CLI agents: {display name, command, description, context flags, local, default Ollama model}
    // Context flags tell the agent where to find project context.
    // Empty string means the agent auto-reads its own file (e.g., CLAUDE.md, GEMINI.md).
    private static final String[][] KNOWN_AGENTS = {
        {"Claude Code", "claude", "Anthropic's Claude CLI agent", "", "false", ""},
        {"Aider", "aider", "AI pair programming in your terminal", "--read .aider.conventions.md", "false", ""},
        {"GitHub Copilot CLI", "gh copilot", "GitHub Copilot in the terminal", "", "false", ""},
        {"Gemini CLI", "gemini", "Google's Gemini CLI agent", "--yolo", "false", ""},
        {"Open Interpreter", "interpreter", "Open-source code interpreter", "--system_message \"$(cat CLAUDE.md)\"", "false", ""},
        {"Cline", "cline", "Autonomous coding agent", "", "false", ""},
        {"Codex CLI", "codex", "OpenAI Codex CLI", "--full-auto", "false", ""},
        {"Gemma 4 31B", "gemma4_31b_agent", "Ollama-backed Gemma agent", "", "true", "gemma4:31b-cloud"},
        {"Gemma 4 31B (Claude-style)", "gemma4_31b_agent", "Gemma with Claude-style narrative prompt (A/B test)", "--style claude", "true", "gemma4:31b-cloud"},
    };

    static final String CLOUD_OLLAMA_REFUSAL =
            "On-premises mode cannot use cloud-hosted Ollama models. "
          + "Switch to a local tag (e.g. gemma3:27b) or change the posture for this folder.";

    private final String agentWorkspace;
    private final int tcpPort;
    private final Settings settings;
    private final PostureController postureController;
    private List<AgentInfo> cachedAgents;

    /**
     * @param agentWorkspace the directory to use as working directory for launched agents
     * @param tcpPort the ImageJAI TCP server port to expose to launched agents
     */
    public AgentLauncher(String agentWorkspace, int tcpPort) {
        this(agentWorkspace, tcpPort, Settings.load());
    }

    public AgentLauncher(String agentWorkspace, int tcpPort, Settings settings) {
        this(agentWorkspace, tcpPort, settings, PostureController.getInstance());
    }

    AgentLauncher(String agentWorkspace, int tcpPort, Settings settings,
                  PostureController postureController) {
        this.agentWorkspace = agentWorkspace;
        this.tcpPort = tcpPort;
        this.settings = settings == null ? Settings.load() : settings;
        this.postureController = postureController == null
                ? PostureController.getInstance()
                : postureController;
    }

    /**
     * Detect all available CLI agents on the system.
     * Checks PATH and common install locations.
     */
    public List<AgentInfo> detectAgents() {
        if (cachedAgents == null) {
            List<AgentInfo> agents = new ArrayList<AgentInfo>();

            for (String[] known : KNOWN_AGENTS) {
                String name = known[0];
                String command = known[1];
                String description = known[2];
                String flags = known.length > 3 ? known[3] : "";
                boolean local = known.length > 4 && Boolean.parseBoolean(known[4]);
                String defaultOllamaModel = known.length > 5 ? known[5] : "";

                String path = findExecutable(command);
                if (path != null) {
                    agents.add(new AgentInfo(name, command, description, path,
                            flags, local, defaultOllamaModel));
                }
            }

            cachedAgents = agents;
        }

        return filterAgentsForPosture(cachedAgents);
    }

    /**
     * Force re-scan for agents (e.g., after user installs something new).
     */
    public List<AgentInfo> rescanAgents() {
        cachedAgents = null;
        return detectAgents();
    }

    /**
     * Launch an agent in the requested mode. Returns a live session handle,
     * or {@code null} if the spawn failed.
     *
     * <p>{@link Mode#EMBEDDED} will start working once the terminal primitive
     * lands (stage 05); until then it throws {@link UnsupportedOperationException}.
     */
    public AgentSession launch(AgentInfo agent, Mode mode) {
        try {
            refuseCloudTagIfOnPremises(agent, null);
            syncContextFiles();

            if (mode == Mode.EMBEDDED) {
                AgentLaunchSpec spec = buildEmbeddedLaunchSpec(agent);
                return new EmbeddedAgentSession(agent, spec);
            }

            AgentLaunchSpec spec = buildExternalLaunchSpec(agent);
            ProcessBuilder pb = new ProcessBuilder(spec.agentCommand);
            pb.directory(spec.workingDir);
            pb.environment().putAll(spec.env);
            pb.start();

            IJ.log("[AgentLauncher] Launched: " + agent.name + " (" + agent.command + ")");
            return new ExternalAgentSession(agent, true);
        } catch (IOException e) {
            IJ.log("[AgentLauncher] Failed to launch " + agent.name + ": " + e.getMessage());
            return null;
        } catch (UnsupportedOperationException uoe) {
            IJ.log("[AgentLauncher] " + uoe.getMessage());
            return null;
        }
    }

    /**
     * Build the OS-specific command list that opens a new detached terminal
     * and runs the agent inside it. Kept distinct from embedded-launch spec
     * construction so the two paths share the same {@link AgentLaunchSpec}
     * shape without cross-contaminating shell quoting.
     */
    AgentLaunchSpec buildExternalLaunchSpec(AgentInfo agent) {
        refuseCloudTagIfOnPremises(agent, null);
        String fullCommand = buildAgentCommandString(agent);

        String os = System.getProperty("os.name", "").toLowerCase();
        List<String> cmd = new ArrayList<String>();

        if (os.contains("win")) {
            cmd.add("cmd.exe");
            cmd.add("/c");
            cmd.add("start");
            cmd.add("\"" + agent.name + "\"");
            cmd.add("cmd.exe");
            cmd.add("/k");
            cmd.add(fullCommand);
        } else if (os.contains("mac")) {
            String script = "tell application \"Terminal\" to do script "
                    + "\"cd '" + agentWorkspace + "' && " + fullCommand + "\"";
            cmd.add("osascript");
            cmd.add("-e");
            cmd.add(script);
        } else {
            String terminal = findLinuxTerminal();
            if (terminal == null) {
                throw new UnsupportedOperationException(
                        "No terminal emulator found (gnome-terminal / konsole / xterm / ...).");
            }
            cmd.add(terminal);
            cmd.add("-e");
            cmd.add("bash -c 'cd \"" + agentWorkspace + "\" && " + fullCommand + "; bash'");
        }

        Map<String, String> env = new LinkedHashMap<>();
        env.put("IMAGEJAI_TCP_PORT", String.valueOf(tcpPort));
        addAuditEnvironment(env, agent);

        return new AgentLaunchSpec(agent, cmd, new File(agentWorkspace), env);
    }

    /**
     * Build the command for an embedded PTY. It intentionally goes through the
     * platform shell so compound commands and context flags behave like the
     * existing external-terminal path.
     */
    AgentLaunchSpec buildEmbeddedLaunchSpec(AgentInfo agent) {
        refuseCloudTagIfOnPremises(agent, null);
        String fullCommand = buildAgentCommandString(agent);

        String os = System.getProperty("os.name", "").toLowerCase();
        List<String> cmd = new ArrayList<String>();
        if (os.contains("win")) {
            cmd.add("cmd.exe");
            cmd.add("/c");
            cmd.add(fullCommand);
        } else {
            cmd.add("bash");
            cmd.add("-lc");
            cmd.add("exec " + fullCommand);
        }

        Map<String, String> env = new LinkedHashMap<>(System.getenv());
        env.put("IMAGEJAI_TCP_PORT", String.valueOf(tcpPort));
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        env.put("TERMINAL_EMULATOR", "JetBrains-JediTerm");
        addAuditEnvironment(env, agent);

        return new AgentLaunchSpec(agent, cmd, new File(agentWorkspace), env);
    }

    /**
     * Launch an agent in a new terminal window.
     *
     * @deprecated since stage 02 — use {@link #launch(AgentInfo, Mode)} which
     *     returns a session handle. Kept only for source compatibility; this
     *     overload will be removed once all call sites migrate.
     * @param agent the agent to launch
     * @return true if launch was successful
     */
    @Deprecated
    public boolean launchAgent(AgentInfo agent) {
        return launch(agent, Mode.EXTERNAL) != null;
    }

    /**
     * Get the agent workspace path.
     */
    public String getAgentWorkspace() {
        return agentWorkspace;
    }

    // --- Private helpers ---

    String buildAgentCommandString(AgentInfo agent) {
        List<String> parts = new ArrayList<String>();
        parts.add(agent.command);
        if (agent.contextFlags != null && !agent.contextFlags.trim().isEmpty()) {
            parts.add(agent.contextFlags.trim());
        }
        if ("Claude Code".equals(agent.name)
                && settings.claudeUseGsdFlag
                && AgentPlannerDetector.isInstalled(settings)) {
            parts.add("--dangerously-skip-permissions");
        }
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(part);
        }
        return sb.toString();
    }

    /**
     * Find an executable on PATH or in common locations.
     */
    String findExecutable(String command) {
        // Handle compound commands like "gh copilot"
        String baseCommand = command.split(" ")[0];

        // Check PATH using 'where' (Windows) or 'which' (Unix)
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            String whichCmd = os.contains("win") ? "where" : "which";

            ProcessBuilder pb = new ProcessBuilder(whichCmd, baseCommand);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            int exit = proc.waitFor();
            if (exit == 0) {
                byte[] buf = new byte[4096];
                int len = proc.getInputStream().read(buf);
                if (len > 0) {
                    String path = new String(buf, 0, len).trim().split("\\r?\\n")[0];
                    return path;
                }
            }
        } catch (Exception ignore) {
            // where/which not available or failed
        }

        // Check common install locations
        String home = System.getProperty("user.home", "");
        String os = System.getProperty("os.name", "").toLowerCase();

        String[][] extraPaths;
        if (os.contains("win")) {
            extraPaths = new String[][] {
                {home + "\\AppData\\Roaming\\npm\\" + baseCommand + ".cmd"},
                {home + "\\AppData\\Local\\Programs\\" + baseCommand + "\\" + baseCommand + ".exe"},
                {home + "\\.local\\bin\\" + baseCommand + ".exe"},
                {"C:\\Program Files\\" + baseCommand + "\\" + baseCommand + ".exe"},
            };
        } else {
            extraPaths = new String[][] {
                {home + "/.local/bin/" + baseCommand},
                {"/usr/local/bin/" + baseCommand},
                {home + "/.npm-global/bin/" + baseCommand},
            };
        }

        for (String[] paths : extraPaths) {
            for (String path : paths) {
                File f = new File(path);
                if (f.exists() && f.canExecute()) {
                    return f.getAbsolutePath();
                }
            }
        }

        return null;
    }

    private List<AgentInfo> filterAgentsForPosture(List<AgentInfo> agents) {
        List<AgentInfo> filtered = new ArrayList<AgentInfo>();
        PrivacyPosture posture = currentPosture();
        for (AgentInfo agent : agents) {
            if (posture == PrivacyPosture.ON_PREMISES && !agent.isLocal()) {
                continue;
            }
            filtered.add(agent);
        }
        return filtered;
    }

    private PrivacyPosture currentPosture() {
        if (postureController != null) {
            return postureController.current();
        }
        return settings.getPrivacyPosture();
    }

    void refuseCloudTagIfOnPremises(AgentInfo agent, Map<String, String> env) {
        if (currentPosture() != PrivacyPosture.ON_PREMISES || agent == null
                || !agent.isOllama()) {
            return;
        }
        String tag = resolveOllamaModelTag(agent, env);
        if (isCloudOllamaTag(tag)) {
            throw new PostureViolation(CLOUD_OLLAMA_REFUSAL);
        }
    }

    String resolveOllamaModelTag(AgentInfo agent, Map<String, String> env) {
        String fromEnv = env == null ? null : env.get("OLLAMA_MODEL");
        if (isBlank(fromEnv)) {
            fromEnv = System.getenv("OLLAMA_MODEL");
        }
        if (!isBlank(fromEnv)) {
            return cleanModelTag(fromEnv);
        }

        String fromFlags = modelFromFlags(agent == null ? null : agent.contextFlags);
        if (!isBlank(fromFlags)) {
            return cleanModelTag(fromFlags);
        }

        return cleanModelTag(agent == null ? null : agent.defaultOllamaModel());
    }

    static boolean isCloudOllamaTag(String tag) {
        return !isBlank(tag)
                && cleanModelTag(tag).toLowerCase(Locale.ROOT).endsWith("-cloud");
    }

    private void addAuditEnvironment(Map<String, String> env, AgentInfo agent) {
        if (env == null) {
            return;
        }
        env.put("IMAGEJAI_SESSION_ID", newAuditSessionId());
        String endpoint = modelEndpointFor(agent);
        if (!endpoint.isEmpty()) {
            env.put("IMAGEJAI_MODEL_ENDPOINT", endpoint);
        }
    }

    private static String newAuditSessionId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    private String modelEndpointFor(AgentInfo agent) {
        if (agent == null) {
            return "";
        }
        String name = agent.name == null ? "" : agent.name.toLowerCase(Locale.ROOT);
        String command = agent.command == null ? "" : agent.command.toLowerCase(Locale.ROOT);
        if (name.contains("claude") || command.contains("claude")) {
            return "anthropic.claude-code";
        }
        if (name.contains("codex") || command.contains("codex")) {
            return "openai.codex";
        }
        if (name.contains("gemini") || command.contains("gemini")) {
            return "google.gemini-cli";
        }
        if (agent.isOllama()) {
            String tag = resolveOllamaModelTag(agent, null);
            String host = isCloudOllamaTag(tag) ? "ollama.cloud:" : "ollama.local:";
            return host + (tag == null || tag.trim().isEmpty() ? agent.command : tag.trim());
        }
        return command.isEmpty() ? name : command;
    }

    private static String modelFromFlags(String flags) {
        if (isBlank(flags)) {
            return "";
        }
        List<String> tokens = shellLikeTokens(flags);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if ("--model".equals(token) || "-m".equals(token)) {
                return i + 1 < tokens.size() ? tokens.get(i + 1) : "";
            }
            if (token.startsWith("--model=")) {
                return token.substring("--model=".length());
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

    private static String cleanModelTag(String tag) {
        if (tag == null) {
            return "";
        }
        String cleaned = tag.trim();
        if (cleaned.length() >= 2
                && ((cleaned.startsWith("\"") && cleaned.endsWith("\""))
                || (cleaned.startsWith("'") && cleaned.endsWith("'")))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * Find an available terminal emulator on Linux.
     */
    private String findLinuxTerminal() {
        String[] terminals = {
            "gnome-terminal", "konsole", "xterm", "xfce4-terminal", "lxterminal"
        };
        for (String term : terminals) {
            try {
                Process p = new ProcessBuilder("which", term).start();
                if (p.waitFor() == 0) {
                    return term;
                }
            } catch (Exception ignore) {
                // continue
            }
        }
        return null;
    }

    /**
     * Run sync_context.py to generate context files for non-Claude agents.
     * Reads CLAUDE.md and creates GEMINI.md, .clinerules, .cursorrules, etc.
     */
    private void syncContextFiles() {
        File syncScript = new File(agentWorkspace, "sync_context.py");
        if (!syncScript.exists()) {
            return; // No sync script available
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("python", syncScript.getAbsolutePath());
            pb.directory(new File(agentWorkspace));
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            boolean finished = proc.waitFor() == 0;
            if (finished) {
                IJ.log("[AgentLauncher] Context files synced for all agents");
            } else {
                IJ.log("[AgentLauncher] Warning: sync_context.py exited with errors");
            }
        } catch (Exception e) {
            IJ.log("[AgentLauncher] Could not run sync_context.py: " + e.getMessage());
        }
    }
}
