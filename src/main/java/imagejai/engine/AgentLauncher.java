package imagejai.engine;

import ij.IJ;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detects and launches external AI CLI agents (Claude Code, Aider, etc.)
 * in a terminal window with the agent workspace as working directory.
 */
public class AgentLauncher {

    /**
     * Represents a detected CLI agent.
     */
    public static class AgentInfo {
        public final String name;
        public final String command;
        public final String description;
        public final String executablePath;
        public final String contextFlags;

        public AgentInfo(String name, String command, String description,
                         String executablePath, String contextFlags) {
            this.name = name;
            this.command = command;
            this.description = description;
            this.executablePath = executablePath;
            this.contextFlags = contextFlags;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    // Known CLI agents: {display name, command, description, context flags}
    // Context flags tell the agent where to find project context.
    // Empty string means the agent auto-reads its own file (e.g., CLAUDE.md, GEMINI.md).
    private static final String[][] KNOWN_AGENTS = {
        {"Claude Code", "claude", "Anthropic's Claude CLI agent", "--dangerously-skip-permissions"},
        {"Aider", "aider", "AI pair programming in your terminal", "--read .aider.conventions.md"},
        {"GitHub Copilot CLI", "gh copilot", "GitHub Copilot in the terminal", ""},
        {"Gemini CLI", "gemini", "Google's Gemini CLI agent", "--yolo"},
        {"Open Interpreter", "interpreter", "Open-source code interpreter", "--system_message \"$(cat CLAUDE.md)\""},
        {"Cline", "cline", "Autonomous coding agent", ""},
        {"Codex CLI", "codex", "OpenAI Codex CLI", "--full-auto"},
    };

    private final String agentWorkspace;
    private List<AgentInfo> cachedAgents;

    /**
     * @param agentWorkspace the directory to use as working directory for launched agents
     */
    public AgentLauncher(String agentWorkspace) {
        this.agentWorkspace = agentWorkspace;
    }

    /**
     * Detect all available CLI agents on the system.
     * Checks PATH and common install locations.
     */
    public List<AgentInfo> detectAgents() {
        if (cachedAgents != null) {
            return cachedAgents;
        }

        List<AgentInfo> agents = new ArrayList<AgentInfo>();

        for (String[] known : KNOWN_AGENTS) {
            String name = known[0];
            String command = known[1];
            String description = known[2];
            String flags = known.length > 3 ? known[3] : "";

            String path = findExecutable(command);
            if (path != null) {
                agents.add(new AgentInfo(name, command, description, path, flags));
            }
        }

        cachedAgents = agents;
        return agents;
    }

    /**
     * Force re-scan for agents (e.g., after user installs something new).
     */
    public List<AgentInfo> rescanAgents() {
        cachedAgents = null;
        return detectAgents();
    }

    /**
     * Launch an agent in a new terminal window.
     *
     * @param agent the agent to launch
     * @return true if launch was successful
     */
    public boolean launchAgent(AgentInfo agent) {
        try {
            // Sync context files for non-Claude agents before launching
            syncContextFiles();

            // Build the full command with context flags
            String fullCommand = agent.command;
            if (agent.contextFlags != null && !agent.contextFlags.isEmpty()) {
                fullCommand = agent.command + " " + agent.contextFlags;
            }

            String os = System.getProperty("os.name", "").toLowerCase();
            List<String> cmd = new ArrayList<String>();

            if (os.contains("win")) {
                // Windows: use cmd /c start to open a new terminal
                cmd.add("cmd.exe");
                cmd.add("/c");
                cmd.add("start");
                cmd.add("\"" + agent.name + "\"");  // window title
                cmd.add("cmd.exe");
                cmd.add("/k");
                cmd.add(fullCommand);
            } else if (os.contains("mac")) {
                // macOS: use osascript to open Terminal
                String script = "tell application \"Terminal\" to do script "
                        + "\"cd '" + agentWorkspace + "' && " + fullCommand + "\"";
                cmd.add("osascript");
                cmd.add("-e");
                cmd.add(script);
            } else {
                // Linux: try common terminal emulators
                String terminal = findLinuxTerminal();
                if (terminal != null) {
                    cmd.add(terminal);
                    cmd.add("-e");
                    cmd.add("bash -c 'cd \"" + agentWorkspace + "\" && " + fullCommand + "; bash'");
                } else {
                    IJ.log("[AgentLauncher] No terminal emulator found");
                    return false;
                }
            }

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new File(agentWorkspace));
            pb.start();

            IJ.log("[AgentLauncher] Launched: " + agent.name + " (" + agent.command + ")");
            return true;

        } catch (IOException e) {
            IJ.log("[AgentLauncher] Failed to launch " + agent.name + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Get the agent workspace path.
     */
    public String getAgentWorkspace() {
        return agentWorkspace;
    }

    // --- Private helpers ---

    /**
     * Find an executable on PATH or in common locations.
     */
    private String findExecutable(String command) {
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
