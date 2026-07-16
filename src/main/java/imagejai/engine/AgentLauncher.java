package imagejai.engine;

import ij.IJ;
import imagejai.config.PrivacyPosture;
import imagejai.config.Settings;

import java.io.File;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

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
    public static final String GEMMA_WRAPPER_COMMAND = "gemma4_31b_agent";
    private static final String GEMMA_BUNDLED_MODULE = "gemma4_31b";
    static final long PROCESS_PROBE_TIMEOUT_MS = 3000L;
    static final long CONTEXT_SYNC_TIMEOUT_MS = 15000L;
    static final int MAX_PROCESS_OUTPUT_BYTES = 64 * 1024;

    /** How an agent should be launched. */
    public enum Mode {
        /** Detached external terminal — today's default. */
        EXTERNAL,
        /** Embedded PTY inside the plugin frame — landed in stage 05. */
        EMBEDDED
    }

    /** Which CLI conversation lifecycle action to request. */
    public enum SessionAction {
        /** Start a fresh CLI conversation. */
        NEW_SESSION,
        /** Ask the CLI to resume its latest conversation for this workspace. */
        RESUME_LATEST
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
        {"Open Interpreter", "interpreter", "Open-source code interpreter", "", "false", ""},
        {"Cline", "cline", "Autonomous coding agent", "", "false", ""},
        {"Codex CLI", "codex", "OpenAI Codex CLI", "--dangerously-bypass-approvals-and-sandbox", "false", ""},
        {"Gemma 4 31B", GEMMA_WRAPPER_COMMAND, "Ollama-backed Gemma agent", "", "true", "gemma4:31b-cloud"},
        {"Gemma 4 31B (Claude-style)", GEMMA_WRAPPER_COMMAND, "Gemma with Claude-style narrative prompt (A/B test)", "--style claude", "true", "gemma4:31b-cloud"},
    };

    static final String CLOUD_OLLAMA_REFUSAL =
            "On-premises mode cannot use cloud-hosted Ollama models. "
          + "Switch to a local tag (e.g. gemma3:27b) or change the posture for this folder.";
    static final String AGENT_WORKSPACE_ENV = "IMAGEJAI_AGENT_WORKSPACE";

    private final String agentWorkspace;
    private final int tcpPort;
    private final Settings settings;
    private final PostureController postureController;
    private List<AgentInfo> cachedAgents;
    private volatile String lastSessionId = "";

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
        return launch(agent, mode, SessionAction.NEW_SESSION);
    }

    /**
     * Launch an agent while choosing whether the CLI starts fresh or resumes
     * its latest saved conversation.
     */
    public AgentSession launch(AgentInfo agent, Mode mode, SessionAction sessionAction) {
        return launch(agent, mode, null, sessionAction);
    }

    /**
     * Launch with extra environment variables merged into the spec. Used by the
     * multi-provider proxy/native launchers to pass {@code PYTHONPATH} (so the
     * {@code agent} package resolves for {@code python -m agent.providers.agent_cli})
     * and {@code IMAGEJAI_NATIVE_*} feature flags into the agent process.
     */
    public AgentSession launch(AgentInfo agent, Mode mode, Map<String, String> extraEnv) {
        return launch(agent, mode, extraEnv, SessionAction.NEW_SESSION);
    }

    /**
     * Provider launch with an explicit dangerous-permission classification.
     * The caller must already have restricted the permission to a trusted
     * local provider; the flag keeps the central launch-policy audit accurate.
     */
    public AgentSession launch(AgentInfo agent, Mode mode, Map<String, String> extraEnv,
                               boolean dangerousPermissions) {
        return launch(agent, mode, extraEnv, SessionAction.NEW_SESSION,
                dangerousPermissions);
    }

    /**
     * Launch with extra environment variables and an explicit CLI session
     * action. Existing provider/native wrappers call the three-argument
     * overload, so only direct CLI launches can opt into resume.
     */
    public AgentSession launch(AgentInfo agent, Mode mode, Map<String, String> extraEnv,
                               SessionAction sessionAction) {
        return launch(agent, mode, extraEnv, sessionAction, false);
    }

    private AgentSession launch(AgentInfo agent, Mode mode,
                                Map<String, String> extraEnv,
                                SessionAction sessionAction,
                                boolean dangerousPermissions) {
        SessionAction action = sessionAction == null
                ? SessionAction.NEW_SESSION
                : sessionAction;
        try {
            LaunchPolicy.Decision decision = evaluateLaunch(
                    agent, extraEnv, dangerousPermissions).enforce();
            Map<String, String> permittedEnv = decision.permittedEnvironment();
            syncContextFiles();

            if (mode == Mode.EMBEDDED) {
                AgentLaunchSpec spec = buildEmbeddedLaunchSpec(agent, action);
                if (!permittedEnv.isEmpty()) {
                    spec.env.putAll(permittedEnv);
                }
                try {
                    return createEmbeddedSession(agent, spec);
                } catch (IOException e) {
                    return fallbackToExternalAfterEmbeddedFailure(
                            agent, permittedEnv, action, dangerousPermissions, e);
                } catch (RuntimeException e) {
                    if (e instanceof PostureViolation) {
                        throw e;
                    }
                    return fallbackToExternalAfterEmbeddedFailure(
                            agent, permittedEnv, action, dangerousPermissions, e);
                } catch (LinkageError e) {
                    return fallbackToExternalAfterEmbeddedFailure(
                            agent, permittedEnv, action, dangerousPermissions, e);
                }
            }

            if (dangerousPermissions) {
                return launchExternalSession(
                        agent, permittedEnv, "", action, true);
            }
            return launchExternalSession(agent, permittedEnv, "", action);
        } catch (IOException e) {
            IJ.log("[AgentLauncher] Approved agent process could not be started: "
                    + e.getClass().getSimpleName());
            return null;
        } catch (UnsupportedOperationException uoe) {
            IJ.log("[AgentLauncher] The requested launch mode is unavailable.");
            return null;
        }
    }

    AgentSession createEmbeddedSession(AgentInfo agent, AgentLaunchSpec spec) throws IOException {
        return new EmbeddedAgentSession(agent, spec);
    }

    AgentSession launchExternalSession(AgentInfo agent,
                                       Map<String, String> extraEnv,
                                       String notice) throws IOException {
        return launchExternalSession(agent, extraEnv, notice, SessionAction.NEW_SESSION);
    }

    AgentSession launchExternalSession(AgentInfo agent,
                                       Map<String, String> extraEnv,
                                       String notice,
                                       SessionAction sessionAction) throws IOException {
        return launchExternalSession(agent, extraEnv, notice, sessionAction, false);
    }

    private AgentSession launchExternalSession(AgentInfo agent,
                                                Map<String, String> extraEnv,
                                                String notice,
                                                SessionAction sessionAction,
                                                boolean dangerousPermissions)
            throws IOException {
        LaunchPolicy.Decision decision = evaluateLaunch(
                agent, extraEnv, dangerousPermissions).enforce();
        AgentLaunchSpec spec = buildExternalLaunchSpec(agent, sessionAction);
        if (!decision.permittedEnvironment().isEmpty()) {
            spec.env.putAll(decision.permittedEnvironment());
        }
        ProcessBuilder pb = new ProcessBuilder(spec.agentCommand);
        pb.directory(spec.workingDir);
        pb.environment().putAll(spec.env);
        pb.start();

        IJ.log("[AgentLauncher] Launched an approved agent session.");
        return new ExternalAgentSession(agent, true, notice);
    }

    private AgentSession fallbackToExternalAfterEmbeddedFailure(AgentInfo agent,
                                                               Map<String, String> extraEnv,
                                                               SessionAction sessionAction,
                                                               boolean dangerousPermissions,
                                                               Throwable failure)
            throws IOException {
        String reason = "Embedded terminal failed (" + safeFailureCategory(failure) + ")";
        IJ.log("[AgentLauncher] " + reason + ". Falling back to external terminal.");
        String notice = reason + ". Launching the approved agent in an external window.";
        if (!dangerousPermissions) {
            if (sessionAction == SessionAction.NEW_SESSION) {
                return launchExternalSession(agent, extraEnv, notice);
            }
            return launchExternalSession(agent, extraEnv, notice, sessionAction);
        }
        if (sessionAction == SessionAction.NEW_SESSION) {
            return launchExternalSession(agent, extraEnv, notice,
                    SessionAction.NEW_SESSION, dangerousPermissions);
        }
        return launchExternalSession(agent, extraEnv, notice, sessionAction,
                dangerousPermissions);
    }

    /**
     * Build the OS-specific command list that opens a new detached terminal
     * and runs the agent inside it. Kept distinct from embedded-launch spec
     * construction so the two paths share the same {@link AgentLaunchSpec}
     * shape without cross-contaminating shell quoting.
     */
    AgentLaunchSpec buildExternalLaunchSpec(AgentInfo agent) {
        return buildExternalLaunchSpec(agent, SessionAction.NEW_SESSION);
    }

    AgentLaunchSpec buildExternalLaunchSpec(AgentInfo agent, SessionAction sessionAction) {
        evaluateLaunch(agent, null).enforce();
        String fullCommand = buildAgentCommandString(agent, sessionAction);

        String os = System.getProperty("os.name", "").toLowerCase();
        List<String> cmd = new ArrayList<String>();

        if (os.contains("win")) {
            cmd.add("cmd.exe");
            cmd.add("/c");
            cmd.add("start");
            cmd.add("\"" + safeTerminalTitle(agent) + "\"");
            cmd.add("cmd.exe");
            cmd.add("/k");
            cmd.add(fullCommand);
        } else if (os.contains("mac")) {
            String shell = "cd " + shellQuote(agentWorkspace) + " && exec " + fullCommand;
            String script = "tell application \"Terminal\" to do script "
                    + appleScriptString(shell);
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
            cmd.add("bash");
            cmd.add("-lc");
            cmd.add("cd " + shellQuote(agentWorkspace)
                    + " && " + fullCommand + "; exec bash");
        }

        Map<String, String> env = new LinkedHashMap<>();
        env.put("IMAGEJAI_TCP_PORT", String.valueOf(tcpPort));
        env.put("IMAGEJAI_SAFE_MODE", settings.safeModeEnabled ? "1" : "0");
        addRunnerEnvironment(env);
        addRecipeEnvironment(env);
        addAuditEnvironment(env, agent);

        return new AgentLaunchSpec(agent, cmd, new File(agentWorkspace), env);
    }

    /**
     * Build the command for an embedded PTY. It intentionally goes through the
     * platform shell so compound commands and context flags behave like the
     * existing external-terminal path.
     */
    AgentLaunchSpec buildEmbeddedLaunchSpec(AgentInfo agent) {
        return buildEmbeddedLaunchSpec(agent, SessionAction.NEW_SESSION);
    }

    AgentLaunchSpec buildEmbeddedLaunchSpec(AgentInfo agent, SessionAction sessionAction) {
        evaluateLaunch(agent, null).enforce();
        String fullCommand = buildAgentCommandString(agent, sessionAction);

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

        // Process creation inherits the host environment itself. Keep only
        // ImageJAI-approved additions in the inspectable launch spec so keys
        // can never leak through tests, logs, caches, or diagnostics.
        Map<String, String> env = new LinkedHashMap<>();
        env.put("IMAGEJAI_TCP_PORT", String.valueOf(tcpPort));
        env.put("IMAGEJAI_SAFE_MODE", settings.safeModeEnabled ? "1" : "0");
        env.put("TERM", "xterm-256color");
        env.put("COLORTERM", "truecolor");
        env.put("TERMINAL_EMULATOR", "JetBrains-JediTerm");
        addRunnerEnvironment(env);
        addRecipeEnvironment(env);
        addAuditEnvironment(env, agent);

        return new AgentLaunchSpec(agent, cmd, new File(agentWorkspace), env);
    }

    private void addRunnerEnvironment(Map<String, String> env) {
        if (agentWorkspace == null || agentWorkspace.trim().isEmpty()) {
            return;
        }
        env.put(AGENT_WORKSPACE_ENV,
                new File(agentWorkspace).getAbsoluteFile().toPath()
                        .normalize().toString());
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

    public String lastSessionId() {
        return lastSessionId == null ? "" : lastSessionId;
    }

    /**
     * Whether this installed CLI has a known non-interactive "resume latest"
     * launch form. The plugin deliberately delegates transcript ownership to
     * the vendor CLI instead of trying to parse their session stores.
     */
    public boolean supportsResumeLatest(AgentInfo agent) {
        return !isBlank(resumeLaunchCommand(agent));
    }

    // --- Private helpers ---

    String buildAgentCommandString(AgentInfo agent) {
        return buildAgentCommandString(agent, SessionAction.NEW_SESSION);
    }

    String buildAgentCommandString(AgentInfo agent, SessionAction sessionAction) {
        SessionAction action = sessionAction == null
                ? SessionAction.NEW_SESSION
                : sessionAction;
        List<String> parts = new ArrayList<String>();
        if (action == SessionAction.RESUME_LATEST) {
            String resumeCommand = resumeLaunchCommand(agent);
            if (isBlank(resumeCommand)) {
                throw new UnsupportedOperationException(
                        "Resume latest is not available for " + displayName(agent) + ".");
            }
            parts.add(resumeCommand);
        } else {
            parts.add(resolveLaunchCommand(agent));
        }
        String flags = agent == null ? "" : agent.contextFlags;
        if (flags != null && !flags.trim().isEmpty()) {
            LaunchPolicy.requireSafeCommandText(flags, "agent arguments");
            validateEmbeddedIdentifiers(flags);
            parts.add(flags.trim());
        }
        if (isClaudeAgent(agent)
                && settings.claudeUseGsdFlag
                && AgentPlannerDetector.isInstalled(settings)) {
            LaunchPolicy.Decision decision = evaluateLaunch(agent, null, true).enforce();
            if (decision.dangerousPermissionsAllowed()) {
                parts.add("--dangerously-skip-permissions");
                // Consent is for this launch only, never a persistent default.
                settings.claudeUseGsdFlag = false;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (isBlank(part)) {
                continue;
            }
            if (sb.length() > 0) sb.append(' ');
            sb.append(part);
        }
        return sb.toString();
    }

    String resumeLaunchCommand(AgentInfo agent) {
        String command = resolveLaunchCommand(agent);
        if (isBlank(command)) {
            return "";
        }
        if (isClaudeAgent(agent)) {
            return command + " --continue";
        }
        if (isGeminiAgent(agent)) {
            return command + " --resume latest";
        }
        if (isCodexAgent(agent)) {
            return command + " resume --last";
        }
        return "";
    }

    private String resolveLaunchCommand(AgentInfo agent) {
        if (agent == null || agent.command == null) {
            return "";
        }
        if (GEMMA_WRAPPER_COMMAND.equals(agent.command) && bundledGemmaModuleAvailable()) {
            return LaunchPolicy.requireSafeCommandText(
                    pythonCommand() + " -m " + GEMMA_BUNDLED_MODULE,
                    "agent command");
        }
        String command = LaunchPolicy.requireSafeCommandText(agent.command, "agent command");
        validateEmbeddedIdentifiers(command);
        return command;
    }

    private static boolean isClaudeAgent(AgentInfo agent) {
        return agentMatches(agent, "claude");
    }

    private static boolean isGeminiAgent(AgentInfo agent) {
        return agentMatches(agent, "gemini");
    }

    private static boolean isCodexAgent(AgentInfo agent) {
        return agentMatches(agent, "codex");
    }

    private static boolean agentMatches(AgentInfo agent, String commandBase) {
        if (agent == null) {
            return false;
        }
        String command = firstCommandToken(agent.command).toLowerCase(Locale.ROOT);
        return commandBase.equals(command);
    }

    private static String firstCommandToken(String command) {
        if (command == null) {
            return "";
        }
        String trimmed = command.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        return trimmed.split("\\s+")[0];
    }

    private static String displayName(AgentInfo agent) {
        if (agent == null || isBlank(agent.name)) {
            return "this agent";
        }
        return agent.name;
    }

    private static String safeTerminalTitle(AgentInfo agent) {
        String name = agent == null || agent.name == null ? "ImageJAI Agent" : agent.name;
        String safe = name.replaceAll("[^A-Za-z0-9 ._()-]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (safe.isEmpty()) {
            safe = "ImageJAI Agent";
        }
        return safe.length() > 64 ? safe.substring(0, 64) : safe;
    }

    /**
     * Find an executable on PATH or in common locations.
     */
    String findExecutable(String command) {
        // Handle compound commands like "gh copilot"
        String baseCommand = command.split(" ")[0];

        // The bundled Gemma wrapper is the canonical path for ImageJAI. Treat
        // it as available even when the optional pip console script is absent.
        if (GEMMA_WRAPPER_COMMAND.equals(baseCommand) && bundledGemmaModuleAvailable()) {
            File main = bundledGemmaMain();
            return main == null ? null : main.getAbsolutePath();
        }

        // Check PATH using 'where' (Windows) or 'which' (Unix)
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            String whichCmd = os.contains("win") ? "where" : "which";

            ProcessResult result = runBoundedProcess(
                    new ProcessBuilder(whichCmd, baseCommand).redirectErrorStream(true),
                    PROCESS_PROBE_TIMEOUT_MS, 16 * 1024);
            if (!result.timedOut && result.exitCode == 0 && !result.output.trim().isEmpty()) {
                return result.output.trim().split("\\r?\\n")[0];
            }
        } catch (IOException ignore) {
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

    private boolean bundledGemmaModuleAvailable() {
        File main = bundledGemmaMain();
        return main != null && main.isFile();
    }

    private File bundledGemmaMain() {
        if (agentWorkspace == null || agentWorkspace.trim().isEmpty()) {
            return null;
        }
        return new File(new File(agentWorkspace, GEMMA_BUNDLED_MODULE), "__main__.py");
    }

    private static String pythonCommand() {
        String configured = System.getenv("IMAGEJAI_PYTHON");
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        return System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "python"
                : "python3";
    }

    private List<AgentInfo> filterAgentsForPosture(List<AgentInfo> agents) {
        List<AgentInfo> filtered = new ArrayList<AgentInfo>();
        for (AgentInfo agent : agents) {
            try {
                evaluateLaunch(agent, null).enforce();
                filtered.add(agent);
            } catch (PostureViolation denied) {
                // A denied launch is not offered in the picker.
            }
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

    private LaunchPolicy.Decision evaluateLaunch(AgentInfo agent, Map<String, String> env) {
        return evaluateLaunch(agent, env, false);
    }

    private LaunchPolicy.Decision evaluateLaunch(AgentInfo agent, Map<String, String> env,
                                                 boolean dangerousPermissions) {
        if (agent == null) {
            return LaunchPolicy.evaluate("cli", "", currentPosture(),
                    LaunchPolicy.RequestedCapabilities.builder(LaunchPolicy.Surface.CLI)
                            .egress(true)
                            .dangerousPermissions(dangerousPermissions)
                            .environment(env)
                            .build());
        }
        String provider = env == null ? "" : valueOrEmpty(env.get("IMAGEJAI_PROVIDER"));
        String model = env == null ? "" : valueOrEmpty(env.get("IMAGEJAI_MODEL"));
        if (isBlank(provider)) {
            provider = providerFor(agent);
        }
        if (isBlank(model) && agent.isOllama()) {
            model = resolveOllamaModelTag(agent, env);
        }
        boolean cloudOllama = agent.isOllama() && isCloudOllamaTag(model);
        LaunchPolicy.Surface surface = env != null && env.containsKey("IMAGEJAI_PROVIDER")
                ? LaunchPolicy.Surface.PROVIDER
                : LaunchPolicy.Surface.CLI;
        // Provider launches must derive locality from the provider identity,
        // never from a caller-controlled AgentInfo boolean.
        String ollamaHost = env == null ? null : env.get("OLLAMA_HOST");
        if (isBlank(ollamaHost) && agent.isOllama()) {
            ollamaHost = System.getenv("OLLAMA_HOST");
        }
        boolean local = surface == LaunchPolicy.Surface.PROVIDER
                ? LaunchPolicy.isLocalProviderEndpoint(provider, ollamaHost) && !cloudOllama
                : agent.isLocal() && !cloudOllama
                        && (isBlank(ollamaHost) || LaunchPolicy.isLoopbackEndpoint(ollamaHost));
        LaunchPolicy.Decision decision = LaunchPolicy.evaluate(provider, model,
                currentPosture(),
                LaunchPolicy.RequestedCapabilities.builder(surface)
                        .localProvider(local)
                        .egress(!local)
                        .mutation(true)
                        .safeMode(settings.safeModeEnabled)
                        .dangerousPermissions(dangerousPermissions)
                        .environment(env)
                        .build());
        if (!decision.allowed() && currentPosture() == PrivacyPosture.ON_PREMISES
                && cloudOllama) {
            throw new PostureViolation(CLOUD_OLLAMA_REFUSAL);
        }
        return decision;
    }

    private static String providerFor(AgentInfo agent) {
        if (agent == null) {
            return "cli";
        }
        if (agent.isOllama()) {
            String tag = agent.defaultOllamaModel();
            return isCloudOllamaTag(tag) ? "ollama-cloud" : "ollama";
        }
        String command = firstCommandToken(agent.command).toLowerCase(Locale.ROOT);
        if ("claude".equals(command)) return "anthropic";
        if ("gemini".equals(command)) return "gemini";
        if ("codex".equals(command)) return "openai";
        return "cli";
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static void validateEmbeddedIdentifiers(String text) {
        List<String> tokens = shellLikeTokens(text);
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (("--provider".equals(token) || "--model".equals(token))
                    && i + 1 < tokens.size()) {
                if ("--provider".equals(token)) {
                    LaunchPolicy.requireProviderId(tokens.get(++i));
                } else {
                    LaunchPolicy.requireModelId(tokens.get(++i));
                }
            } else if (token.startsWith("--provider=")) {
                LaunchPolicy.requireProviderId(token.substring("--provider=".length()));
            } else if (token.startsWith("--model=")) {
                LaunchPolicy.requireModelId(token.substring("--model=".length()));
            }
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
        if (isBlank(tag)) {
            return false;
        }
        String cleaned = cleanModelTag(tag).toLowerCase(Locale.ROOT);
        return cleaned.endsWith("-cloud") || cleaned.endsWith(":cloud");
    }

    private void addAuditEnvironment(Map<String, String> env, AgentInfo agent) {
        if (env == null) {
            return;
        }
        String sessionId = newAuditSessionId();
        lastSessionId = sessionId;
        env.put("IMAGEJAI_SESSION_ID", sessionId);
        String endpoint = modelEndpointFor(agent);
        if (!endpoint.isEmpty()) {
            env.put("IMAGEJAI_MODEL_ENDPOINT", endpoint);
        }
    }

    private void addRecipeEnvironment(Map<String, String> env) {
        if (env == null) {
            return;
        }
        Path userRecipes = RecipePaths.userRecipesDir();
        env.put(RecipePaths.USER_RECIPES_ENV,
                userRecipes.toAbsolutePath().normalize().toString());

        List<Path> dirs = new ArrayList<Path>();
        dirs.add(userRecipes);
        if (agentWorkspace != null && !agentWorkspace.trim().isEmpty()) {
            dirs.add(new File(agentWorkspace).toPath().resolve("recipes"));
        }
        env.put(RecipePaths.RECIPE_DIRS_ENV, RecipePaths.pathList(dirs));
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

    private static String safeFailureCategory(Throwable failure) {
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 6; depth++) {
            String className = current.getClass().getName();
            String message = current.getMessage();
            if ((className != null && className.toLowerCase(Locale.ROOT).contains("winpty"))
                    || (message != null && message.toLowerCase(Locale.ROOT).contains("winpty"))) {
                return "WinPty unavailable";
            }
            current = current.getCause();
        }
        return failure == null ? "unknown error" : failure.getClass().getSimpleName();
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
                ProcessResult result = runBoundedProcess(
                        new ProcessBuilder("which", term).redirectErrorStream(true),
                        PROCESS_PROBE_TIMEOUT_MS, 4096);
                if (!result.timedOut && result.exitCode == 0) {
                    return term;
                }
            } catch (IOException ignore) {
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
            ProcessBuilder pb = new ProcessBuilder(pythonCommand(), syncScript.getAbsolutePath());
            pb.directory(new File(agentWorkspace));
            pb.redirectErrorStream(true);
            ProcessResult result = runBoundedProcess(pb, CONTEXT_SYNC_TIMEOUT_MS,
                    MAX_PROCESS_OUTPUT_BYTES);
            if (result.timedOut) {
                IJ.log("[AgentLauncher] Context sync timed out; owned process tree terminated");
            } else if (result.exitCode == 0) {
                IJ.log("[AgentLauncher] Context files synced for all agents");
            } else {
                IJ.log("[AgentLauncher] Warning: sync_context.py exited "
                        + result.exitCode);
            }
        } catch (IOException e) {
            IJ.log("[AgentLauncher] Could not run sync_context.py ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    static final class ProcessResult {
        final int exitCode;
        final boolean timedOut;
        final String output;
        ProcessResult(int exitCode, boolean timedOut, String output) {
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.output = output == null ? "" : output;
        }
    }

    /** Start, continuously drain and bound one process owned by this launcher. */
    static ProcessResult runBoundedProcess(ProcessBuilder builder, long timeoutMs,
                                           int maxOutputBytes) throws IOException {
        if (builder == null) throw new IllegalArgumentException("process builder is required");
        builder.redirectErrorStream(true);
        final Process process = builder.start();
        final int cap = Math.max(0, Math.min(MAX_PROCESS_OUTPUT_BYTES, maxOutputBytes));
        final ByteArrayOutputStream captured = new ByteArrayOutputStream(Math.min(cap, 4096));
        final AtomicReference<IOException> drainFailure = new AtomicReference<IOException>();
        Thread drain = new Thread(new Runnable() {
            @Override public void run() {
                byte[] buffer = new byte[4096];
                try (InputStream input = process.getInputStream()) {
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        int room = cap - captured.size();
                        if (room > 0) captured.write(buffer, 0, Math.min(room, read));
                    }
                } catch (IOException failure) {
                    if (process.isAlive()) drainFailure.compareAndSet(null, failure);
                }
            }
        }, "imagejai-process-output-drain");
        drain.setDaemon(true);
        drain.start();

        boolean finished;
        try {
            finished = process.waitFor(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            terminateOwnedProcessTree(process);
            throw new IOException("interrupted while waiting for owned process", interrupted);
        }
        if (!finished) terminateOwnedProcessTree(process);
        try {
            drain.join(1000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        IOException failure = drainFailure.get();
        if (failure != null && finished) throw failure;
        int exit = finished ? process.exitValue() : -1;
        return new ProcessResult(exit, !finished,
                new String(captured.toByteArray(), StandardCharsets.UTF_8));
    }

    private static void terminateOwnedProcessTree(Process process) {
        if (process == null) return;
        try {
            List<ProcessHandle> descendants = new ArrayList<ProcessHandle>();
            process.toHandle().descendants().forEach(descendants::add);
            Collections.reverse(descendants);
            for (ProcessHandle child : descendants) child.destroy();
            process.destroy();
            try { process.waitFor(250L, TimeUnit.MILLISECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            for (ProcessHandle child : descendants) {
                if (child.isAlive()) child.destroyForcibly();
            }
            if (process.isAlive()) process.destroyForcibly();
        } catch (Throwable unavailable) {
            process.destroyForcibly();
        }
    }

    static String shellQuote(String value) {
        String safe = value == null ? "" : value;
        return "'" + safe.replace("'", "'\"'\"'") + "'";
    }

    private static String appleScriptString(String value) {
        String safe = value == null ? "" : value;
        return "\"" + safe.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
