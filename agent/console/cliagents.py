"""CLI agent registry, discovery, and launching for the standalone console.

This is the headless port of the Fiji Swing panel's agent-launch path
(``src/main/java/imagejai/engine/AgentLauncher.java`` plus the
``imagejai.terminal`` registries). The console has no Swing, no pty4j, and
no JAR resources, so the module keeps three promises:

* Every rule that already exists in Java is copied with a ``file:line``
  citation instead of being re-invented, so both sides stay in step.
* Nothing here touches the UI, and every process, clock, and path is
  injectable, so the whole surface can be unit tested without spawning a
  real agent.
* Per-agent data that the plugin ships as JAR resources
  (``/agents/<id>/approval.json`` and friends) is embedded below, generated
  from ``src/main/resources/agents``. An on-disk directory can override it.

Covers feature ids S5.1-S5.18, S5.22-S5.26 and S5.30-S5.38 of
``docs/console/FEATURES_embedded_swing_console.md``.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
import threading
import time
import uuid
from collections import OrderedDict
from dataclasses import dataclass
from datetime import datetime
from enum import Enum
from pathlib import Path
from typing import Callable, Iterable, Iterator, Mapping, Sequence

# --------------------------------------------------------------------------
# Constants copied from Java
# --------------------------------------------------------------------------

#: AgentLauncher.java:34
LOCAL_ASSISTANT_NAME = "Local Assistant"
#: AgentLauncher.java:35
GEMMA_WRAPPER_COMMAND = "gemma4_31b_agent"
#: AgentLauncher.java:36
GEMMA_BUNDLED_MODULE = "gemma4_31b"
#: AgentLauncher.java:37
DANGEROUS_PERMISSION_CONSENT_REQUIRED = (
    "CLI permission bypass requires explicit consent for this launch."
)
#: AgentLauncher.java:124
CLOUD_OLLAMA_REFUSAL = (
    "On-premises mode cannot use cloud-hosted Ollama models. "
    "Switch to a local tag (e.g. gemma3:27b) or change the posture for this folder."
)
#: AgentLauncher.java:126
AGENT_WORKSPACE_ENV = "IMAGEJAI_AGENT_WORKSPACE"
#: AgentLauncher.java:39 (3000 ms)
PROCESS_PROBE_TIMEOUT_S = 3.0
#: AgentLauncher.java:40 (15000 ms)
CONTEXT_SYNC_TIMEOUT_S = 15.0
#: AgentLauncher.java:41
MAX_PROCESS_OUTPUT_BYTES = 64 * 1024
#: RecipePaths.java:16-17
USER_RECIPES_ENV = "IMAGEJAI_USER_RECIPES_DIR"
RECIPE_DIRS_ENV = "IMAGEJAI_RECIPE_DIRS"
#: PromptWatcher.java:35-36 (250 ms poll over the last 20 lines)
PROMPT_POLL_S = 0.25
PROMPT_TAIL_LINES = 20
#: AiRootPanel.java:1160 - session-exit poll interval (750 ms)
SESSION_EXIT_POLL_S = 0.75
#: EmbeddedAgentSession.java:131-146 - EOF, then 2 s, then force kill
EOF_BYTE = "\x04"
SHUTDOWN_GRACE_S = 2.0
#: EmbeddedAgentSession.java:27 - persisted transcript length
SCROLLBACK_LINES = 1000
#: EmbeddedAgentSession.java:29 - persisted transcript file stamp
SCROLLBACK_TIMESTAMP = "%Y%m%d_%H%M%S"
#: AgentRegistry.java:36-39
USER_COMMAND_CACHE_S = 5.0
MAX_USER_COMMAND_DIRECTORY_ENTRIES = 4096
MAX_USER_COMMANDS = 512
MAX_USER_COMMAND_CACHE_ENTRIES = 64
#: OutboundPromptScrubber limits quoted in FEATURES_embedded_swing_console.md 5.22
MAX_PARTIAL_LINE_CHARS = 65_536
MAX_WRITE_BYTES = 262_144
MAX_REPLACEMENTS_PER_LINE = 4_096

#: Settings.java:1104-1110 - shipped per-command launch flags.
DEFAULT_CLI_AGENT_ARGUMENTS: dict[str, str] = {
    "claude": "--dangerously-skip-permissions",
    "codex": "--yolo",
    "gemini": "--yolo",
}

#: AgentLauncher.java:809-818 - the tokens that count as a permission bypass.
DANGEROUS_PERMISSION_FLAGS = (
    "--dangerously-skip-permissions",
    "--dangerously-bypass-approvals-and-sandbox",
    "--yolo",
)

#: AgentLauncher.java:1119-1124 - Linux terminal emulators, in probe order.
LINUX_TERMINALS = (
    "gnome-terminal",
    "konsole",
    "xterm",
    "xfce4-terminal",
    "lxterminal",
)

#: sync_context.py:AGENT_FILES - which context file each CLI reads.
AGENT_CONTEXT_FILES: dict[str, str] = {
    "claude": "CLAUDE.md",
    "codex": "AGENTS.md",
    "gemini": "GEMINI.md",
    "aider": ".aider.conventions.md",
    "cline": ".clinerules",
    "cursor": ".cursorrules",
}


class LaunchMode(str, Enum):
    """AgentLauncher.java:44-49 - where the agent's terminal lives (S5.2)."""

    EMBEDDED = "embedded"
    EXTERNAL = "external"


class SessionAction(str, Enum):
    """AgentLauncher.java:52-57 - fresh conversation or resume (S5.7)."""

    NEW_SESSION = "new"
    RESUME_LATEST = "resume"


class ApprovalDecision(str, Enum):
    """ApprovalPolicy.java:22-26 - what to do with a detected prompt."""

    AUTO_CONFIRM = "auto_confirm"
    ESCALATE = "escalate"
    PENDING = "pending"


class PostureViolation(RuntimeError):
    """A launch the privacy posture or the consent rule refuses (S5.8, S5.10)."""


class ResumeUnavailable(RuntimeError):
    """The CLI has no known non-interactive resume form (S5.7)."""


class CommandScanError(RuntimeError):
    """A user-command directory that is too big, unreadable, or not a directory.

    Mirrors ``AgentRegistry.CommandScanException`` so the console can report
    the same machine-readable ``code`` the plugin reports (S5.31).
    """

    def __init__(self, code: str, path: Path, message: str, entries_inspected: int = 0):
        super().__init__(message)
        self.code = code
        self.path = Path(path)
        self.entries_inspected = entries_inspected


# --------------------------------------------------------------------------
# Small shared helpers
# --------------------------------------------------------------------------


def shell_like_tokens(text: str | None) -> list[str]:
    """Split a command string the way AgentLauncher.java:1055-1091 does.

    ``shlex`` is close but not identical: the Java splitter drops quote
    characters and never raises on an unbalanced quote. Argument validation
    has to see exactly the tokens the plugin sees, so the Java loop is
    reproduced instead of approximated.
    """
    tokens: list[str] = []
    if not text:
        return tokens
    current: list[str] = []
    quote = ""
    for char in text:
        if quote:
            if char == quote:
                quote = ""
            else:
                current.append(char)
            continue
        if char in ("'", '"'):
            quote = char
            continue
        if char.isspace():
            if current:
                tokens.append("".join(current))
                current = []
        else:
            current.append(char)
    if current:
        tokens.append("".join(current))
    return tokens


def _first_token(command: str | None) -> str:
    """AgentLauncher.java:673-683 - the executable part of a compound command."""
    if not command:
        return ""
    stripped = command.strip()
    return stripped.split()[0] if stripped else ""


def _slug(value: str | None) -> str:
    """AgentRegistry.java:135-140 - lower-case identifier slug."""
    if not value:
        return ""
    slugged = re.sub(r"[^a-z0-9]+", "_", value)
    return slugged.strip("_")


def _is_windows(os_name: str | None = None) -> bool:
    name = (os_name if os_name is not None else _os_name()).lower()
    return "win" in name and "darwin" not in name


def _os_name() -> str:
    """The Java ``os.name`` equivalent, kept in one place so tests can fake it."""
    return "Windows" if os.name == "nt" else ("Mac OS X" if sys.platform == "darwin" else "Linux")


def _is_blank(value: str | None) -> bool:
    return value is None or not value.strip()


def _clean_model_tag(tag: str | None) -> str:
    """AgentLauncher.java:1093-1105 - strip surrounding quotes from a model tag."""
    if not tag:
        return ""
    cleaned = tag.strip()
    if len(cleaned) >= 2 and cleaned[0] == cleaned[-1] and cleaned[0] in ("'", '"'):
        cleaned = cleaned[1:-1].strip()
    return cleaned


def shell_quote(value: str | None) -> str:
    """AgentLauncher.java:1235-1238 - POSIX single-quote escaping."""
    safe = value or ""
    return "'" + safe.replace("'", "'\"'\"'") + "'"


def quote_executable_for_shell(executable: str, os_name: str | None = None) -> str:
    """AgentLauncher.java:779-791 - quote a Python path for the launch shell.

    The Windows branch refuses ``"``, ``%``, ``!`` and ``^`` because
    ``cmd.exe`` would expand or unbalance them inside the ``/c`` string.
    """
    candidate = (executable or "").strip()
    if not candidate:
        raise ValueError("Python executable is required.")
    if _is_windows(os_name):
        if any(ch in candidate for ch in ('"', "%", "!", "^")):
            raise ValueError("Unsafe Python executable.")
        return '"' + candidate + '"'
    return shell_quote(candidate)


def python_executable(env: Mapping[str, str] | None = None, os_name: str | None = None) -> str:
    """AgentLauncher.java:769-777 - IMAGEJAI_PYTHON, else python / python3."""
    environ = os.environ if env is None else env
    configured = (environ.get("IMAGEJAI_PYTHON") or "").strip()
    if configured:
        return configured
    return "python" if _is_windows(os_name) else "python3"


# --------------------------------------------------------------------------
# S5.3 / S5.25 - the registry
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class CliAgent:
    """One CLI agent ImageJAI knows how to launch.

    Fields mirror a ``KNOWN_AGENTS`` row (AgentLauncher.java:112-122); the
    console adds ``runtime`` and ``install_command`` so it can explain a
    missing agent instead of just hiding it, which the Swing picker did.
    """

    name: str
    command: str
    description: str = ""
    context_flags: str = ""
    local: bool = False
    default_ollama_model: str = ""
    runtime: str = ""
    install_command: str = ""
    install_url: str = ""

    @property
    def agent_id(self) -> str:
        """Registry id used for approval, command, and clear data (S5.25)."""
        return agent_id(self.command, self.context_flags)

    @property
    def base_command(self) -> str:
        return _first_token(self.command)

    @property
    def is_ollama(self) -> bool:
        """AgentLauncher.java:88-93."""
        command = (self.command or "").lower()
        return (
            "ollama" in command
            or GEMMA_WRAPPER_COMMAND in command
            or bool(self.default_ollama_model.strip())
        )

    @property
    def supports_resume(self) -> bool:
        """AgentLauncher.java:485-487 - a known non-interactive resume form."""
        return self.base_command.lower() in ("claude", "gemini", "codex")

    @property
    def needs_consent(self) -> bool:
        """True when the shipped launch flags include a permission bypass.

        Derived from the defaults rather than hard-coded, so editing
        DEFAULT_CLI_AGENT_ARGUMENTS cannot silently drop a consent prompt
        (rule: AgentLauncher.java:805-818).
        """
        return contains_dangerous_permission_bypass(
            " ".join(
                part
                for part in (
                    self.context_flags,
                    DEFAULT_CLI_AGENT_ARGUMENTS.get(self.base_command.lower(), ""),
                )
                if part
            )
        )


# Settings.java:116-120 - the install commands the plugin already ships.
CLAUDE_INSTALL_COMMAND = "npm i -g @anthropic-ai/claude-code"
CODEX_INSTALL_COMMAND = "npm i -g @openai/codex"
AIDER_INSTALL_COMMAND = "pip install aider-chat"
GEMINI_INSTALL_COMMAND = "npm i -g @google/gemini-cli"
OLLAMA_INSTALL_URL = "https://ollama.com/download"

#: AgentLauncher.java:112-122 - the nine registered agents, in picker order.
#: ``runtime`` / ``install_command`` are console additions; agents with no
#: install command in Settings.java deliberately carry an empty string rather
#: than a guessed one.
KNOWN_AGENTS: tuple[CliAgent, ...] = (
    CliAgent("Claude Code", "claude", "Anthropic's Claude CLI agent",
             runtime="node", install_command=CLAUDE_INSTALL_COMMAND),
    CliAgent("Aider", "aider", "AI pair programming in your terminal",
             context_flags="--read .aider.conventions.md",
             runtime="python", install_command=AIDER_INSTALL_COMMAND),
    CliAgent("GitHub Copilot CLI", "gh copilot", "GitHub Copilot in the terminal",
             runtime="gh", install_url="https://cli.github.com"),
    CliAgent("Gemini CLI", "gemini", "Google's Gemini CLI agent",
             runtime="node", install_command=GEMINI_INSTALL_COMMAND),
    CliAgent("Open Interpreter", "interpreter", "Open-source code interpreter",
             runtime="python"),
    CliAgent("Cline", "cline", "Autonomous coding agent", runtime="node"),
    CliAgent("Codex CLI", "codex", "OpenAI Codex CLI",
             runtime="node", install_command=CODEX_INSTALL_COMMAND),
    CliAgent("Gemma 4 31B", GEMMA_WRAPPER_COMMAND, "Ollama-backed Gemma agent",
             local=True, default_ollama_model="gemma4:31b-cloud",
             runtime="ollama", install_url=OLLAMA_INSTALL_URL),
    CliAgent("Gemma 4 31B (Claude-style)", GEMMA_WRAPPER_COMMAND,
             "Gemma with Claude-style narrative prompt (A/B test)",
             context_flags="--style claude", local=True,
             default_ollama_model="gemma4:31b-cloud",
             runtime="ollama", install_url=OLLAMA_INSTALL_URL),
)

#: S5.38 - the built-in assistant is not a CLI: it has no executable at all.
LOCAL_ASSISTANT = CliAgent(
    LOCAL_ASSISTANT_NAME,
    "",
    "Built-in assistant: no CLI process, works offline",
    local=True,
)


def known_agents(include_local_assistant: bool = False) -> list[CliAgent]:
    """The candidate list, independent of what is installed."""
    agents = list(KNOWN_AGENTS)
    if include_local_assistant:
        agents.append(LOCAL_ASSISTANT)
    return agents


def find_agent(key: str, agents: Sequence[CliAgent] | None = None) -> CliAgent | None:
    """Look an agent up by registry id, command, or display name."""
    if not key:
        return None
    wanted = key.strip().lower()
    for agent in agents if agents is not None else known_agents(True):
        if wanted in (agent.agent_id, agent.command.lower(), agent.name.lower()):
            return agent
    return None


def agent_id(command: str | None, context_flags: str = "") -> str:
    """AgentRegistry.java:63-85 - the id every per-agent registry keys on (S5.25)."""
    if not command or not command.strip():
        return "default"
    raw = command.strip().lower()
    flags = (context_flags or "").lower()
    if _is_gemma_wrapper(raw) and "--style claude" in flags:
        return "gemma4_31b_claude"
    if _is_gemma_wrapper(raw):
        return "gemma4_31b"
    provider = _provider_from_agent_cli(raw)
    if provider:
        return "provider_" + _slug(provider)
    base = raw.split()[0]
    if base.endswith("_agent"):
        base = base[: -len("_agent")]
    return _slug(base) or "default"


def _is_gemma_wrapper(command: str) -> bool:
    """AgentRegistry.java:87-92."""
    return (
        command == GEMMA_WRAPPER_COMMAND
        or command == "imagejai_agent"
        or " -m gemma4_31b" in command
        or " -m agent.gemma4_31b" in command
    )


def _provider_from_agent_cli(command: str) -> str:
    """AgentRegistry.java:94-109 - provider id of a ``agent.providers.agent_cli`` run."""
    if "agent.providers.agent_cli" not in command:
        return ""
    tokens = shell_like_tokens(command)
    for index, token in enumerate(tokens):
        if token == "--provider":
            return tokens[index + 1] if index + 1 < len(tokens) else ""
        if token.startswith("--provider="):
            return token[len("--provider="):]
    return ""


# --------------------------------------------------------------------------
# S5.4 / S5.5 - executable discovery
# --------------------------------------------------------------------------

#: Human-readable runtime requirements used by the "not installed" message.
RUNTIME_HINTS: dict[str, str] = {
    "node": "Node.js 18+ (https://nodejs.org)",
    "python": "Python 3.10+",
    "ollama": "the Ollama runtime (" + OLLAMA_INSTALL_URL + ")",
    "gh": "the GitHub CLI (https://cli.github.com)",
}


@dataclass(frozen=True)
class ExecutableLookup:
    """The outcome of looking for one agent's executable."""

    agent_id: str
    command: str
    found: bool
    path: str | None = None
    source: str = ""
    message: str = ""


def executable_candidates(
    command: str,
    *,
    os_name: str | None = None,
    home: str | os.PathLike[str] | None = None,
) -> list[str]:
    """AgentLauncher.java:729-746 - the common install folders probed after PATH."""
    base = _first_token(command)
    if not base:
        return []
    # Keep the caller's string as given: pathlib would rewrite a POSIX home
    # into Windows separators when the console itself runs on Windows.
    root = (str(home) if home is not None else str(Path.home())).rstrip("/\\")
    # Join with the separator of the *target* OS, not the host: a Windows
    # console must still produce POSIX candidates when asked about Linux,
    # otherwise the table is untestable and wrong on the other platform.
    if _is_windows(os_name):
        def join(*parts: str) -> str:
            return "\\".join(parts)

        return [
            join(root, "AppData", "Roaming", "npm", base + ".cmd"),
            join(root, "AppData", "Local", "Programs", base, base + ".exe"),
            join(root, ".local", "bin", base + ".exe"),
            join("C:\\Program Files", base, base + ".exe"),
        ]
    return [
        "/".join((root, ".local", "bin", base)),
        "/usr/local/bin/" + base,
        "/".join((root, ".npm-global", "bin", base)),
    ]


def _default_is_executable(path: str) -> bool:
    """Java's ``File.exists() && File.canExecute()``."""
    return os.path.isfile(path) and os.access(path, os.X_OK)


def bundled_gemma_main(workspace: str | os.PathLike[str] | None) -> Path | None:
    """AgentLauncher.java:756-766 - ``<workspace>/gemma4_31b/__main__.py`` (S5.5)."""
    if workspace is None or not str(workspace).strip():
        return None
    return Path(workspace) / GEMMA_BUNDLED_MODULE / "__main__.py"


def not_installed_message(agent: CliAgent) -> str:
    """The console's replacement for silently hiding an uninstalled agent (S5.4).

    The Swing picker just dropped agents whose executable did not resolve.
    A terminal user needs to know why and what to type, so the message always
    names the runtime and, when known, the exact install command.
    """
    runtime = RUNTIME_HINTS.get(agent.runtime, "")
    parts = [f"{agent.name} is not installed."]
    if agent.install_command:
        parts.append(f"Install it with: {agent.install_command}")
    elif agent.install_url:
        parts.append(f"Install it from: {agent.install_url}")
    else:
        parts.append(
            f"No ImageJAI install command is configured for '{agent.base_command}'; "
            "see the vendor documentation."
        )
    if runtime:
        parts.append(f"It needs {runtime}.")
    return " ".join(parts)


def missing_runtime_message(agent: CliAgent) -> str:
    """Explain the runtime an agent needs when that runtime itself is absent."""
    runtime = RUNTIME_HINTS.get(agent.runtime)
    if not runtime:
        return f"{agent.name} needs a runtime ImageJAI does not know about."
    return f"{agent.name} needs {runtime}, which was not found on this machine."


def discover_executable(
    agent: CliAgent,
    *,
    workspace: str | os.PathLike[str] | None = None,
    path_lookup: Callable[[str], str | None] | None = None,
    is_executable: Callable[[str], bool] | None = None,
    os_name: str | None = None,
    home: str | os.PathLike[str] | None = None,
) -> ExecutableLookup:
    """Resolve one agent's executable (S5.4), or explain that it is missing.

    Order matches AgentLauncher.findExecutable (AgentLauncher.java:699-753):
    bundled Gemma module, then PATH, then the common install folders. The
    Java code shells out to ``where`` / ``which`` with a 3 s bound; the
    console calls ``shutil.which`` instead, which performs the same PATHEXT
    aware search in-process, so no probe process is needed at all.
    """
    lookup = path_lookup or shutil.which
    executable = is_executable or _default_is_executable
    base = agent.base_command

    if not base:
        return ExecutableLookup(
            agent.agent_id, agent.command, True, None, "builtin",
            f"{agent.name} runs inside the console; no executable is needed.",
        )

    if base == GEMMA_WRAPPER_COMMAND:
        main = bundled_gemma_main(workspace)
        if main is not None and executable_file_exists(main, is_executable=None):
            return ExecutableLookup(
                agent.agent_id, agent.command, True, str(main.resolve()), "bundled_module",
                f"Bundled module {GEMMA_BUNDLED_MODULE} found in the agent workspace.",
            )

    on_path = lookup(base)
    if on_path:
        return ExecutableLookup(agent.agent_id, agent.command, True, on_path, "path", "")

    for candidate in executable_candidates(base, os_name=os_name, home=home):
        if executable(candidate):
            # Candidates are already absolute for their target OS; running
            # abspath() here would prefix a POSIX path with the Windows cwd.
            return ExecutableLookup(
                agent.agent_id, agent.command, True, candidate, "install_dir", "",
            )

    return ExecutableLookup(
        agent.agent_id, agent.command, False, None, "", not_installed_message(agent),
    )


def executable_file_exists(path: Path, is_executable: Callable[[str], bool] | None) -> bool:
    """A plain file check for the bundled module, which is read by Python, not run."""
    if is_executable is not None:
        return is_executable(str(path))
    return path.is_file()


def detect_agents(
    agents: Sequence[CliAgent] | None = None, **kwargs
) -> list[ExecutableLookup]:
    """Look every registered agent up; keep the misses so the UI can explain them."""
    return [discover_executable(agent, **kwargs) for agent in (agents or KNOWN_AGENTS)]


def installed_agents(
    agents: Sequence[CliAgent] | None = None, **kwargs
) -> list[CliAgent]:
    """AgentLauncher.java:162-188 - only agents with a resolved executable (S5.3)."""
    pool = list(agents or KNOWN_AGENTS)
    return [
        agent
        for agent, found in zip(pool, detect_agents(pool, **kwargs))
        if found.found
    ]


# --------------------------------------------------------------------------
# S5.6 - per-agent extra arguments from the shared settings file
# --------------------------------------------------------------------------


def shared_settings_path(config_dir: str | os.PathLike[str] | None = None) -> Path:
    """Settings.java:43 - the plugin writes ``~/.imagej-ai/config.json``.

    Both sides must read the same file, otherwise the console would launch
    ``claude`` with different flags than the Fiji panel does.
    """
    if config_dir is not None:
        return Path(config_dir) / "config.json"
    home = os.environ.get("IMAGEJAI_HOME")
    root = Path(home) if home else Path.home() / ".imagej-ai"
    return root / "config.json"


def load_cli_agent_arguments(
    settings_path: str | os.PathLike[str] | None = None,
) -> dict[str, str]:
    """Read ``cliAgentArguments`` from the shared settings file (S5.6).

    Missing or broken settings fall back to the shipped defaults, exactly as
    Settings.java:1104-1118 does, so a corrupt file cannot strip a user's
    expected launch flags without warning.
    """
    path = Path(settings_path) if settings_path is not None else shared_settings_path()
    merged = dict(DEFAULT_CLI_AGENT_ARGUMENTS)
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return merged
    stored = data.get("cliAgentArguments") if isinstance(data, dict) else None
    if isinstance(stored, dict):
        for key, value in stored.items():
            if isinstance(key, str) and isinstance(value, str):
                merged[_cli_command_key(key)] = value.strip()
    return merged


def _cli_command_key(command: str | None) -> str:
    """Settings.java:1127-1131 - keyed by the lower-cased first command token."""
    return _first_token(command).lower()


def configured_arguments(
    command: str, arguments: Mapping[str, str] | None = None
) -> str:
    """Settings.java:1112-1118 - the configured extra flags for one command."""
    table = dict(DEFAULT_CLI_AGENT_ARGUMENTS) if arguments is None else arguments
    return (table.get(_cli_command_key(command)) or "").strip()


def effective_arguments(
    agent: CliAgent, arguments: Mapping[str, str] | None = None
) -> str:
    """AgentLauncher.java:636-645 - built-in context flags plus configured flags."""
    built_in = (agent.context_flags or "").strip()
    configured = configured_arguments(agent.command, arguments)
    if not built_in:
        return configured
    if not configured or built_in == configured:
        return built_in
    return built_in + " " + configured


def contains_dangerous_permission_bypass(arguments: str | None) -> bool:
    """AgentLauncher.java:809-818 - token-exact, so ``--yolo-off`` does not match."""
    return any(token in DANGEROUS_PERMISSION_FLAGS for token in shell_like_tokens(arguments))


def contains_argument(arguments: str | None, expected: str) -> bool:
    """AgentLauncher.java:820-825."""
    return expected in shell_like_tokens(arguments)


# --------------------------------------------------------------------------
# S5.8 - one-launch consent for the Claude permission bypass
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class ConsentDecision:
    """The pure decision behind ``--dangerously-skip-permissions`` (S5.8)."""

    add_flag: bool
    requires_consent: bool
    allowed: bool
    clear_consent: bool
    message: str = ""


def decide_permission_bypass(
    agent: CliAgent,
    *,
    arguments: str = "",
    consent_granted: bool = False,
    planner_installed: bool = False,
) -> ConsentDecision:
    """Decide whether this one launch may add the Claude bypass flag.

    AgentLauncher.java:566-600. The flag is appended only when all four hold:
    the agent is ``claude``, the user granted consent for this launch
    (``settings.claudeUseGsdFlag``), the planner toolchain is installed, and
    the flag is not already present. Consent is then cleared, so it can never
    become a persistent default; a refused launch raises with
    DANGEROUS_PERMISSION_CONSENT_REQUIRED.
    """
    is_claude = agent.base_command.lower() == "claude"
    already_present = contains_argument(arguments, "--dangerously-skip-permissions")
    add_flag = is_claude and consent_granted and planner_installed and not already_present
    if not add_flag:
        return ConsentDecision(
            add_flag=False,
            requires_consent=False,
            allowed=True,
            clear_consent=False,
        )
    return ConsentDecision(
        add_flag=True,
        requires_consent=True,
        allowed=True,
        clear_consent=True,
        message="Permission bypass allowed for this launch only.",
    )


def requests_dangerous_permissions(
    agent: CliAgent,
    *,
    arguments: str = "",
    consent_granted: bool = False,
    planner_installed: bool = False,
) -> bool:
    """AgentLauncher.java:799-808 - does this launch ask for a permission bypass?"""
    if contains_dangerous_permission_bypass(arguments):
        return True
    return (
        agent.base_command.lower() == "claude" and consent_granted and planner_installed
    )


# --------------------------------------------------------------------------
# S5.9 - planner toolchain probe
# --------------------------------------------------------------------------


def planner_installed(
    *,
    gsd_skills_path: str | os.PathLike[str] | None = None,
    home: str | os.PathLike[str] | None = None,
    probe: Callable[[], bool] | None = None,
) -> bool:
    """AgentPlannerDetector.java:40-90 - is the planner skill set installed? (S5.9)

    The directory check is authoritative; the ``claude /gsd:help`` probe is
    only consulted when the directory is absent, and is injected here so no
    test ever runs a real CLI.
    """
    root = Path(gsd_skills_path) if gsd_skills_path else (
        Path(home) if home else Path.home()
    ) / ".claude" / "skills" / "gsd"
    if root.is_dir():
        return True
    return bool(probe()) if probe else False


# --------------------------------------------------------------------------
# S5.7 / S5.5 - launch command assembly
# --------------------------------------------------------------------------


def resolve_launch_command(
    agent: CliAgent,
    *,
    workspace: str | os.PathLike[str] | None = None,
    python: str | None = None,
    os_name: str | None = None,
) -> str:
    """AgentLauncher.java:489-502 - the executable part of the launch line.

    The bundled Gemma wrapper has no pip console script, so it is rewritten
    to ``"<python>" -m gemma4_31b`` whenever the module is in the workspace
    (S5.5).
    """
    if not agent.command:
        return ""
    if agent.command == GEMMA_WRAPPER_COMMAND:
        main = bundled_gemma_main(workspace)
        if main is not None and main.is_file():
            interpreter = python or python_executable(os_name=os_name)
            return (
                quote_executable_for_shell(interpreter, os_name)
                + " -m "
                + GEMMA_BUNDLED_MODULE
            )
    return agent.command


def resume_launch_command(agent: CliAgent, **kwargs) -> str:
    """AgentLauncher.java:605-621 - the vendor's own "continue last chat" form."""
    command = resolve_launch_command(agent, **kwargs)
    if not command:
        return ""
    base = agent.base_command.lower()
    if base == "claude":
        return command + " --continue"
    if base == "gemini":
        return command + " --resume latest"
    if base == "codex":
        return command + " resume --last"
    return ""


@dataclass(frozen=True)
class BuiltCommand:
    """One approved command line plus what approving it consumed."""

    text: str
    consent_consumed: bool = False
    dangerous_permissions: bool = False


def build_command_string(
    agent: CliAgent,
    *,
    session_action: SessionAction = SessionAction.NEW_SESSION,
    arguments: Mapping[str, str] | None = None,
    consent_granted: bool = False,
    planner_installed: bool = False,
    workspace: str | os.PathLike[str] | None = None,
    python: str | None = None,
    os_name: str | None = None,
) -> BuiltCommand:
    """AgentLauncher.java:540-604 - assemble ``<command> <flags> [bypass]``.

    Built exactly once per launch because construction consumes the
    one-launch consent; a caller that retries must reuse the returned text.
    """
    if session_action is SessionAction.RESUME_LATEST:
        head = resume_launch_command(
            agent, workspace=workspace, python=python, os_name=os_name
        )
        if not head:
            raise ResumeUnavailable(f"Resume is not available for {agent.name}.")
    else:
        head = resolve_launch_command(
            agent, workspace=workspace, python=python, os_name=os_name
        )
    if not head:
        raise ValueError(f"{agent.name} has no launch command.")

    flags = effective_arguments(agent, arguments)
    consent = decide_permission_bypass(
        agent,
        arguments=flags,
        consent_granted=consent_granted,
        planner_installed=planner_installed,
    )
    if consent.requires_consent and not consent.allowed:
        raise PostureViolation(DANGEROUS_PERMISSION_CONSENT_REQUIRED)

    parts = [head]
    if flags.strip():
        parts.append(flags.strip())
    if consent.add_flag:
        parts.append("--dangerously-skip-permissions")
    text = " ".join(parts)
    return BuiltCommand(
        text=text,
        consent_consumed=consent.clear_consent,
        dangerous_permissions=consent.add_flag
        or contains_dangerous_permission_bypass(flags),
    )


# --------------------------------------------------------------------------
# S5.10 - privacy posture filtering
# --------------------------------------------------------------------------

ON_PREMISES = "on-premises"


def is_cloud_ollama_tag(tag: str | None) -> bool:
    """AgentLauncher.java:1010-1017."""
    cleaned = _clean_model_tag(tag).lower()
    return bool(cleaned) and (cleaned.endswith("-cloud") or cleaned.endswith(":cloud"))


def resolve_ollama_model_tag(
    agent: CliAgent, env: Mapping[str, str] | None = None
) -> str:
    """AgentLauncher.java:984-999 - OLLAMA_MODEL, then ``--model`` flags, then default."""
    from_env = (env or {}).get("OLLAMA_MODEL") or os.environ.get("OLLAMA_MODEL")
    if not _is_blank(from_env):
        return _clean_model_tag(from_env)
    from_flags = _model_from_flags(agent.context_flags)
    if not _is_blank(from_flags):
        return _clean_model_tag(from_flags)
    return _clean_model_tag(agent.default_ollama_model)


def _model_from_flags(flags: str | None) -> str:
    """AgentLauncher.java:1040-1053."""
    tokens = shell_like_tokens(flags)
    for index, token in enumerate(tokens):
        if token in ("--model", "-m"):
            return tokens[index + 1] if index + 1 < len(tokens) else ""
        if token.startswith("--model="):
            return token[len("--model="):]
    return ""


def posture_refusal(agent: CliAgent, posture: str | None) -> str:
    """AgentLauncher.java:846-856 - why a posture hides an agent, or ""."""
    if (posture or "").strip().lower() not in (ON_PREMISES, "on_premises", "onpremises"):
        return ""
    if agent.is_ollama and is_cloud_ollama_tag(resolve_ollama_model_tag(agent)):
        return CLOUD_OLLAMA_REFUSAL
    if not agent.local:
        return f"{agent.name} sends data off this machine, which on-premises posture forbids."
    return ""


def filter_agents_for_posture(
    agents: Iterable[CliAgent], posture: str | None
) -> list[CliAgent]:
    """AgentLauncher.java:827-838 - a refused agent is not offered (S5.10)."""
    return [agent for agent in agents if not posture_refusal(agent, posture)]


def model_endpoint_for(agent: CliAgent) -> str:
    """AgentLauncher.java:1007-1026 - the audit endpoint id put in the environment."""
    name = (agent.name or "").lower()
    command = (agent.command or "").lower()
    if "claude" in name or "claude" in command:
        return "anthropic.claude-code"
    if "codex" in name or "codex" in command:
        return "openai.codex"
    if "gemini" in name or "gemini" in command:
        return "google.gemini-cli"
    if agent.is_ollama:
        tag = resolve_ollama_model_tag(agent)
        host = "ollama.cloud:" if is_cloud_ollama_tag(tag) else "ollama.local:"
        return host + (tag.strip() or agent.command)
    return command or name


# --------------------------------------------------------------------------
# S5.17 / S5.18 - workspace discovery and context file sync
# --------------------------------------------------------------------------


def discover_workspace(explicit: str | os.PathLike[str] | None = None) -> Path | None:
    """Find the ``agent/`` workspace (S5.17).

    Delegates to :mod:`agent.console.workspace`, which already implements the
    console's discovery order. Duplicating it here would let the two copies
    drift, which is exactly the bug the house rule forbids.
    """
    if explicit:
        candidate = Path(explicit)
        return candidate if (candidate / "ij.py").is_file() else None
    try:
        from .workspace import find_workspace
    except ImportError:  # pragma: no cover - standalone import fallback
        from agent.console.workspace import find_workspace  # type: ignore
    return find_workspace()


@dataclass(frozen=True)
class ContextSyncResult:
    """What ``sync_context.py`` did before a launch (S5.18)."""

    ran: bool
    exit_code: int = 0
    timed_out: bool = False
    output: str = ""
    message: str = ""
    written: tuple[str, ...] = ()


def context_file_for(agent: CliAgent) -> str:
    """Which generated context file this agent reads on start (S5.18)."""
    return AGENT_CONTEXT_FILES.get(agent.agent_id, AGENT_CONTEXT_FILES["claude"])


def _default_sync_runner(argv: Sequence[str], cwd: str, timeout_s: float) -> tuple[int, str]:
    completed = subprocess.run(  # noqa: S603 - fixed argv, no shell
        list(argv),
        cwd=cwd,
        timeout=timeout_s,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        errors="replace",
    )
    return completed.returncode, (completed.stdout or "")[:MAX_PROCESS_OUTPUT_BYTES]


def sync_context_files(
    workspace: str | os.PathLike[str] | None,
    *,
    runner: Callable[[Sequence[str], str, float], tuple[int, str]] | None = None,
    python: str | None = None,
    timeout_s: float = CONTEXT_SYNC_TIMEOUT_S,
) -> ContextSyncResult:
    """AgentLauncher.java:1142-1178 - regenerate the per-CLI context files.

    Non-Claude agents read their own file (GEMINI.md, .clinerules, ...), so
    the workspace script is run before every launch, bounded to 15 s and
    64 KiB of output. ``written`` reports which context files exist after the
    run, which is what the caller actually cares about.
    """
    if workspace is None:
        return ContextSyncResult(False, message="No agent workspace; context sync skipped.")
    root = Path(workspace)
    script = root / "sync_context.py"
    if not script.is_file():
        return ContextSyncResult(
            False, message="sync_context.py is not in the workspace; nothing to sync."
        )
    interpreter = python or python_executable()
    run = runner or _default_sync_runner
    try:
        exit_code, output = run([interpreter, str(script)], str(root), timeout_s)
    except subprocess.TimeoutExpired:
        return ContextSyncResult(
            True,
            exit_code=-1,
            timed_out=True,
            message="Context sync timed out; owned process tree terminated",
        )
    except OSError as failure:
        return ContextSyncResult(
            True,
            exit_code=-1,
            message=f"Could not run sync_context.py ({type(failure).__name__})",
        )
    written = tuple(
        name for name in sorted(set(AGENT_CONTEXT_FILES.values())) if (root / name).is_file()
    )
    message = (
        "Context files synced for all agents"
        if exit_code == 0
        else f"Warning: sync_context.py exited {exit_code}"
    )
    return ContextSyncResult(
        True, exit_code=exit_code, output=output, message=message, written=written
    )


# --------------------------------------------------------------------------
# S5.15 / S5.16 - the environment handed to a launched agent
# --------------------------------------------------------------------------


def new_session_id(generator: Callable[[], str] | None = None) -> str:
    """AgentLauncher.java:1001-1003 - 8 hex characters from a UUID."""
    raw = generator() if generator else uuid.uuid4().hex
    return raw.replace("-", "")[:8]


def user_recipes_dir(imagej_root: str | os.PathLike[str] | None = None) -> Path:
    """RecipePaths.java:23-35 - ``<ImageJ root or home>/ImageJAI/recipes``."""
    root = Path(imagej_root) if imagej_root else Path.home()
    return root / "ImageJAI" / "recipes"


def build_environment(
    agent: CliAgent,
    *,
    workspace: str | os.PathLike[str] | None,
    tcp_port: int,
    safe_mode: bool = True,
    mode: LaunchMode = LaunchMode.EXTERNAL,
    session_id: str | None = None,
    imagej_root: str | os.PathLike[str] | None = None,
    host_env: Mapping[str, str] | None = None,
    extra: Mapping[str, str] | None = None,
) -> dict[str, str]:
    """AgentLauncher.java:419-470 - the ImageJAI additions for one launch (S5.15).

    Only ImageJAI keys are returned. The host environment is merged in at the
    spawn boundary by :func:`merge_environment`, so this dict stays safe to
    log, diff, and assert on without leaking credentials.
    """
    env: dict[str, str] = {
        "IMAGEJAI_TCP_PORT": str(tcp_port),
        "IMAGEJAI_SAFE_MODE": "1" if safe_mode else "0",
    }
    if mode is LaunchMode.EMBEDDED:
        # AgentLauncher.java:455-457 - the terminal hints the PTY path needs.
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        env["TERMINAL_EMULATOR"] = "JetBrains-JediTerm"

    if workspace is not None and str(workspace).strip():
        root = Path(workspace).absolute()
        env[AGENT_WORKSPACE_ENV] = str(root)
        # AgentLauncher.java:493-509 - the bundled Gemma module imports
        # through the ``agent.*`` namespace, so its parent must be importable.
        main = bundled_gemma_main(root)
        if agent.base_command == GEMMA_WRAPPER_COMMAND and main is not None and main.is_file():
            existing = (host_env or os.environ).get("PYTHONPATH", "")
            parent = str(root.parent)
            env["PYTHONPATH"] = (
                parent if not existing.strip() else parent + os.pathsep + existing
            )

    recipes = user_recipes_dir(imagej_root)
    env[USER_RECIPES_ENV] = str(recipes)
    recipe_dirs = [str(recipes)]
    if workspace is not None and str(workspace).strip():
        recipe_dirs.append(str(Path(workspace).absolute() / "recipes"))
    env[RECIPE_DIRS_ENV] = os.pathsep.join(recipe_dirs)

    env["IMAGEJAI_SESSION_ID"] = session_id or new_session_id()
    endpoint = model_endpoint_for(agent)
    if endpoint:
        env["IMAGEJAI_MODEL_ENDPOINT"] = endpoint
    if extra:
        env.update({str(k): str(v) for k, v in extra.items()})
    return env


def merge_environment(
    inherited: Mapping[str, str] | None, additions: Mapping[str, str] | None
) -> dict[str, str]:
    """EmbeddedPty.java:90-103 - host environment first, ImageJAI keys last (S5.16).

    pty4j replaces the whole child environment, so the merge has to happen at
    the spawn boundary; ``subprocess`` behaves the same way when ``env=`` is
    passed, which is why the console needs this too.
    """
    merged: dict[str, str] = {}
    if inherited:
        merged.update({str(k): str(v) for k, v in inherited.items()})
    if additions:
        merged.update({str(k): str(v) for k, v in additions.items()})
    return merged


# --------------------------------------------------------------------------
# S5.12 / S5.1 - launch specs
# --------------------------------------------------------------------------


def safe_terminal_title(name: str | None) -> str:
    """AgentLauncher.java:685-697 - ``[A-Za-z0-9 ._()-]``, cut to 64 characters."""
    raw = name or "ImageJAI Agent"
    safe = re.sub(r"[^A-Za-z0-9 ._()-]+", " ", raw)
    safe = re.sub(r"\s+", " ", safe).strip()
    if not safe:
        safe = "ImageJAI Agent"
    return safe[:64]


def find_linux_terminal(
    path_lookup: Callable[[str], str | None] | None = None
) -> str | None:
    """AgentLauncher.java:1119-1134 - first available terminal emulator."""
    lookup = path_lookup or shutil.which
    for terminal in LINUX_TERMINALS:
        if lookup(terminal):
            return terminal
    return None


@dataclass(frozen=True)
class LaunchSpec:
    """AgentLaunchSpec.java - argv, working directory, and ImageJAI env."""

    agent: CliAgent
    argv: tuple[str, ...]
    cwd: str
    env: dict[str, str]
    command_text: str
    mode: LaunchMode


def build_external_launch_spec(
    agent: CliAgent,
    command_text: str,
    *,
    workspace: str | os.PathLike[str],
    env: Mapping[str, str],
    os_name: str | None = None,
    path_lookup: Callable[[str], str | None] | None = None,
) -> LaunchSpec:
    """AgentLauncher.java:384-416 - open a detached OS terminal (S5.12)."""
    workdir = str(workspace)
    if _is_windows(os_name):
        argv = [
            "cmd.exe", "/c", "start",
            '"' + safe_terminal_title(agent.name) + '"',
            "cmd.exe", "/k", command_text,
        ]
    elif "mac" in (os_name or _os_name()).lower() or "darwin" in (os_name or _os_name()).lower():
        shell = "cd " + shell_quote(workdir) + " && exec " + command_text
        script = 'tell application "Terminal" to do script ' + _applescript_string(shell)
        argv = ["osascript", "-e", script]
    else:
        terminal = find_linux_terminal(path_lookup)
        if terminal is None:
            raise RuntimeError(
                "No terminal emulator found (gnome-terminal / konsole / xterm / ...)."
            )
        argv = [
            terminal, "-e", "bash", "-lc",
            "cd " + shell_quote(workdir) + " && " + command_text + "; exec bash",
        ]
    return LaunchSpec(agent, tuple(argv), workdir, dict(env), command_text,
                      LaunchMode.EXTERNAL)


def build_inprocess_launch_spec(
    agent: CliAgent,
    command_text: str,
    *,
    workspace: str | os.PathLike[str],
    env: Mapping[str, str],
    os_name: str | None = None,
) -> LaunchSpec:
    """AgentLauncher.java:445-462 - run through the platform shell, no new window.

    This is the console's stand-in for the embedded PTY: the same shell form
    the plugin gives pty4j, but wired to a plain pipe so the console can
    stream the output itself.
    """
    if _is_windows(os_name):
        argv = ["cmd.exe", "/c", command_text]
    else:
        argv = ["bash", "-lc", "exec " + command_text]
    return LaunchSpec(agent, tuple(argv), str(workspace), dict(env), command_text,
                      LaunchMode.EMBEDDED)


def _applescript_string(value: str) -> str:
    """AgentLauncher.java:1240-1243."""
    safe = value or ""
    return '"' + safe.replace("\\", "\\\\").replace('"', '\\"') + '"'


def plan_launch(
    agent: CliAgent,
    *,
    workspace: str | os.PathLike[str],
    tcp_port: int,
    mode: LaunchMode = LaunchMode.EXTERNAL,
    session_action: SessionAction = SessionAction.NEW_SESSION,
    arguments: Mapping[str, str] | None = None,
    consent_granted: bool = False,
    planner_installed: bool = False,
    posture: str | None = None,
    safe_mode: bool = True,
    session_id: str | None = None,
    host_env: Mapping[str, str] | None = None,
    extra_env: Mapping[str, str] | None = None,
    os_name: str | None = None,
    path_lookup: Callable[[str], str | None] | None = None,
) -> LaunchSpec:
    """One approved plan: posture check, command, environment, argv.

    Kept separate from the runners so a caller can inspect, log, or refuse a
    launch before any process exists (AgentLauncher.java:256-320).
    """
    refusal = posture_refusal(agent, posture)
    if refusal:
        raise PostureViolation(refusal)
    built = build_command_string(
        agent,
        session_action=session_action,
        arguments=arguments,
        consent_granted=consent_granted,
        planner_installed=planner_installed,
        workspace=workspace,
        os_name=os_name,
    )
    env = build_environment(
        agent,
        workspace=workspace,
        tcp_port=tcp_port,
        safe_mode=safe_mode,
        mode=mode,
        session_id=session_id,
        host_env=host_env,
        extra=extra_env,
    )
    if mode is LaunchMode.EXTERNAL:
        return build_external_launch_spec(
            agent, built.text, workspace=workspace, env=env,
            os_name=os_name, path_lookup=path_lookup,
        )
    return build_inprocess_launch_spec(
        agent, built.text, workspace=workspace, env=env, os_name=os_name
    )


# --------------------------------------------------------------------------
# S5.26 / S5.33 / S5.34 - sessions
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class WriteResult:
    """EmbeddedAgentSession.java:38-52 - did the write reach the agent?"""

    ok: bool
    message: str = ""


@dataclass(frozen=True)
class SessionEnd:
    """AiRootPanel.java:1160-1185 - the agent process exited (S5.33)."""

    agent_id: str
    exit_code: int
    message: str


class DetachedSession:
    """A launch that owns its own OS terminal window (S5.12).

    ExternalAgentSession.java:51-70: the plugin cannot type into, interrupt,
    or kill a detached terminal, and must say so rather than pretend.
    """

    controllable = False

    def __init__(self, agent: CliAgent, pid: int | None, notice: str = ""):
        self.agent = agent
        self.pid = pid
        self.notice = notice

    def is_alive(self) -> bool:
        return False

    @property
    def exit_code(self) -> int | None:
        return None

    def write(self, text: str) -> WriteResult:
        return WriteResult(False, "interrupt unsupported on detached external terminal")

    def interrupt(self) -> WriteResult:
        return WriteResult(False, "interrupt unsupported on detached external terminal")

    def end(self, **_kwargs) -> WriteResult:
        return WriteResult(False, "destroy unsupported on detached external terminal")


class StreamingSession:
    """An in-process agent whose stdout the console streams itself.

    The process object is injected, so tests drive a fake with no OS process.
    Write failures carry the plugin's retry wording (S5.26) and shutdown
    follows EmbeddedAgentSession.destroy (S5.34).
    """

    controllable = True

    def __init__(self, agent: CliAgent, process, *, on_output: Callable[[str], None] | None = None):
        self.agent = agent
        self.process = process
        self._on_output = on_output
        self._lines: list[str] = []
        self._reader: threading.Thread | None = None

    # -- output -----------------------------------------------------------
    def stream(self) -> Iterator[str]:
        """Yield stdout lines as they arrive; also keeps them for the transcript."""
        stdout = getattr(self.process, "stdout", None)
        if stdout is None:
            return
        for line in stdout:
            text = line.decode("utf-8", "replace") if isinstance(line, bytes) else line
            text = text.rstrip("\n")
            self._lines.append(text)
            del self._lines[:-SCROLLBACK_LINES]
            if self._on_output:
                self._on_output(text)
            yield text

    def start_reader(self) -> threading.Thread:
        """Drain stdout on a daemon thread for callers that only want callbacks."""
        if self._reader is None:
            self._reader = threading.Thread(
                target=lambda: [None for _ in self.stream()],
                name="imagejai-console-agent-output",
                daemon=True,
            )
            self._reader.start()
        return self._reader

    def scrollback(self, limit: int = SCROLLBACK_LINES) -> str:
        return "\n".join(self._lines[-limit:])

    # -- lifecycle --------------------------------------------------------
    def is_alive(self) -> bool:
        return self.process.poll() is None

    @property
    def exit_code(self) -> int | None:
        return self.process.poll()

    def write(self, text: str, *, raw: bool = False) -> WriteResult:
        """EmbeddedAgentSession.java:85-101 - never lose a prompt on a failed write."""
        operation = "raw input" if raw else "input"
        if not self.is_alive():
            return WriteResult(False, "The terminal session is no longer running.")
        payload = text if raw else (text or "") + "\r"
        try:
            stdin = self.process.stdin
            stdin.write(payload)
            stdin.flush()
        except (OSError, ValueError, AttributeError) as failure:
            return WriteResult(
                False,
                f"PTY {operation} write failed ({type(failure).__name__}). "
                "Retry without clearing the prompt.",
            )
        return WriteResult(True)

    def interrupt(self) -> WriteResult:
        """Ctrl+C as a byte, matching the toolbar's Interrupt button."""
        return self.write("\x03", raw=True)

    def poll_end(self) -> SessionEnd | None:
        """AiRootPanel.java:1160-1185 - one non-blocking session-end check (S5.33)."""
        code = self.process.poll()
        if code is None:
            return None
        return SessionEnd(
            self.agent.agent_id,
            code,
            f"Embedded agent exited with code {code}: {self.agent.name}",
        )

    def end(self, *, grace_s: float = SHUTDOWN_GRACE_S) -> WriteResult:
        """EmbeddedAgentSession.java:131-150 - EOF, wait, then force kill (S5.34)."""
        if self.is_alive():
            self.write(EOF_BYTE, raw=True)
        try:
            self.process.wait(timeout=grace_s)
        except Exception:  # noqa: BLE001 - any wait failure means force kill
            try:
                self.process.kill()
            except Exception:  # noqa: BLE001 - already gone
                pass
        return WriteResult(True)


def launch_detached(
    spec: LaunchSpec,
    *,
    spawn: Callable[..., object] | None = None,
    host_env: Mapping[str, str] | None = None,
) -> DetachedSession:
    """Start the agent in a new OS terminal window (S5.12)."""
    starter = spawn or subprocess.Popen
    process = starter(
        list(spec.argv),
        cwd=spec.cwd,
        env=merge_environment(host_env if host_env is not None else os.environ, spec.env),
    )
    return DetachedSession(spec.agent, getattr(process, "pid", None))


def launch_streaming(
    spec: LaunchSpec,
    *,
    spawn: Callable[..., object] | None = None,
    host_env: Mapping[str, str] | None = None,
    on_output: Callable[[str], None] | None = None,
) -> StreamingSession:
    """Start the agent as a child process with piped, merged output."""
    starter = spawn or subprocess.Popen
    process = starter(
        list(spec.argv),
        cwd=spec.cwd,
        env=merge_environment(host_env if host_env is not None else os.environ, spec.env),
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
    )
    return StreamingSession(spec.agent, process, on_output=on_output)


# --------------------------------------------------------------------------
# S5.13 / S5.37 - user-facing launch messages
# --------------------------------------------------------------------------


def failure_category(failure: BaseException | None) -> str:
    """AgentLauncher.java:1098-1112 - name the failure without leaking detail."""
    current = failure
    for _ in range(6):
        if current is None:
            break
        text = (type(current).__name__ + " " + str(current)).lower()
        if "winpty" in text:
            return "WinPty unavailable"
        current = current.__cause__ or current.__context__
    return type(failure).__name__ if failure is not None else "unknown error"


def fallback_notice(failure: BaseException | None) -> str:
    """AgentLauncher.java:348-360 - the yellow bar text after an embedded failure."""
    return (
        f"Embedded terminal failed ({failure_category(failure)}). "
        "Launching the approved agent in an external window."
    )


def launch_progress_message(agent: CliAgent, session_action: SessionAction) -> str:
    """AiRootPanel.java:1042-1066 (S5.37)."""
    if session_action is SessionAction.RESUME_LATEST:
        return f"Resuming latest {agent.name}..."
    return f"Launching {agent.name}..."


def launched_message(agent: CliAgent, mode: LaunchMode, workspace: str | os.PathLike[str]) -> str:
    """AiRootPanel.java:1117-1130 (S5.37)."""
    if mode is LaunchMode.EMBEDDED:
        return f"Launched {agent.name} inside the plugin window."
    return f"Launched {agent.name} in: {workspace}"


def resume_unavailable_message(agent: CliAgent) -> str:
    """AiRootPanel.java:1030 (S5.7)."""
    return f"Resume is not available for {agent.name}."


# --------------------------------------------------------------------------
# S5.24 / S5.30 - per-agent data shipped as JAR resources by the plugin
# --------------------------------------------------------------------------

# Generated from src/main/resources/agents/<id>/{approval,commands,clear}.json.
# The console has no JAR to read, so the same data is embedded verbatim; an
# on-disk directory with the same layout overrides it for local edits.
_AGENT_RESOURCES: dict = {'aider': {'approval': {'auto_confirm': ['(?i)press\\s+enter\\s+to\\s+confirm\\s+paste',
                                         '(?i)pasted\\s+\\d+\\s+lines'],
                        'always_escalate': ['(?i)rm\\s+-rf',
                                            '(?i)git\\s+reset\\s+--hard',
                                            '(?i)drop\\s+table',
                                            '(?i)delete\\s+.*\\b(AI_Exports|agent|src|docs)\\b'],
                        'pending': ['(?i)\\b(allow|approve|permit|continue|proceed)\\b.*(\\?|:|\\[y/n\\])\\s*$']},
           'commands': [{'command': '/add', 'description': 'Add files to the chat.'},
                        {'command': '/ask',
                         'description': 'Ask a question without editing files.'},
                        {'command': '/chat-mode', 'description': 'Switch chat mode.'},
                        {'command': '/clear', 'description': 'Clear the chat history.'},
                        {'command': '/code', 'description': 'Ask for code changes.'},
                        {'command': '/commit', 'description': 'Commit changes.'},
                        {'command': '/diff', 'description': 'Show the current diff.'},
                        {'command': '/drop',
                         'description': 'Remove files from the chat.'},
                        {'command': '/exit', 'description': 'Exit Aider.'},
                        {'command': '/git', 'description': 'Run a git command.'},
                        {'command': '/help', 'description': 'Show help.'},
                        {'command': '/lint', 'description': 'Lint files.'},
                        {'command': '/ls', 'description': 'List known files.'},
                        {'command': '/model', 'description': 'Switch model.'},
                        {'command': '/models',
                         'description': 'Search available models.'},
                        {'command': '/quit', 'description': 'Exit Aider.'},
                        {'command': '/read-only',
                         'description': 'Add files as read-only context.'},
                        {'command': '/reset', 'description': 'Reset the current chat.'},
                        {'command': '/run', 'description': 'Run a shell command.'},
                        {'command': '/save',
                         'description': 'Save the chat transcript.'},
                        {'command': '/settings', 'description': 'Show settings.'},
                        {'command': '/test', 'description': 'Run tests.'},
                        {'command': '/tokens', 'description': 'Show token usage.'},
                        {'command': '/undo', 'description': 'Undo the last change.'},
                        {'command': '/voice', 'description': 'Record a voice prompt.'},
                        {'command': '/web',
                         'description': 'Scrape a web page into context.'}],
           'clear': {'match': '(?i)(cleared|chat\\s+history\\s+cleared|conversation\\s+history\\s+cleared)',
                     'on_miss': 'pty_restart'}},
 'claude': {'approval': {'auto_confirm': ['(?i)do\\s+you\\s+trust\\s+.*folder',
                                          '(?i)yes,?\\s+i\\s+trust\\s+this\\s+folder',
                                          '(?i)quick\\s+safety\\s+check',
                                          '(?i)^allow\\s+read\\s+access\\s+to\\s+.*CLAUDE\\.md',
                                          '(?i)^permit\\s+access\\s+to\\s+.*/AI_Exports/',
                                          '(?i)press\\s+enter\\s+to\\s+confirm\\s+paste',
                                          '(?i)pasted\\s+\\d+\\s+lines'],
                         'always_escalate': ['(?i)rm\\s+-rf',
                                             '(?i)git\\s+reset\\s+--hard',
                                             '(?i)drop\\s+table',
                                             '(?i)delete\\s+.*\\b(AI_Exports|agent|src|docs)\\b',
                                             '(?i)overwrite\\s+.*\\b(src|docs|agent)\\b'],
                         'pending': ['(?i)\\b(allow|approve|permit|continue|proceed)\\b.*(\\?|:|\\[y/n\\])\\s*$',
                                     '(?i)do\\s+you\\s+want\\s+.*(\\?|:|\\[y/n\\])\\s*$']},
            'commands': [{'command': '/help',
                          'description': 'Show help and available commands.'},
                         {'command': '/clear',
                          'description': 'Clear conversation history.'},
                         {'command': '/compact',
                          'description': 'Compact the conversation into a smaller '
                                         'summary.'},
                         {'command': '/cost',
                          'description': 'Show token and cost usage.'},
                         {'command': '/doctor',
                          'description': 'Check Claude Code installation and '
                                         'environment.'},
                         {'command': '/exit', 'description': 'Exit Claude Code.'},
                         {'command': '/ide', 'description': 'Manage IDE integration.'},
                         {'command': '/init',
                          'description': 'Create or refresh project instructions.'},
                         {'command': '/login',
                          'description': 'Authenticate Claude Code.'},
                         {'command': '/logout',
                          'description': 'Sign out of Claude Code.'},
                         {'command': '/mcp', 'description': 'Manage MCP servers.'},
                         {'command': '/memory',
                          'description': 'Edit project or user memory.'},
                         {'command': '/model',
                          'description': 'Select or view the active model.'},
                         {'command': '/pr_comments',
                          'description': 'Fetch pull request comments.'},
                         {'command': '/review',
                          'description': 'Ask Claude to review changes.'},
                         {'command': '/status', 'description': 'Show session status.'},
                         {'command': '/terminal-setup',
                          'description': 'Configure terminal integration.'},
                         {'command': '/vim', 'description': 'Toggle Vim mode.'}],
            'clear': {'match': '(?i)(conversation\\s+(cleared|reset)|history\\s+(cleared|reset)|context\\s+cleared)',
                      'on_miss': 'pty_restart'}},
 'codex': {'approval': {'auto_confirm': ['(?i)press\\s+enter\\s+to\\s+confirm\\s+paste',
                                         '(?i)pasted\\s+\\d+\\s+lines'],
                        'always_escalate': ['(?i)rm\\s+-rf',
                                            '(?i)git\\s+reset\\s+--hard',
                                            '(?i)drop\\s+table',
                                            '(?i)delete\\s+.*\\b(AI_Exports|agent|src|docs)\\b'],
                        'pending': ['(?i)\\b(allow|approve|permit|continue|proceed)\\b.*(\\?|:|\\[y/n\\])\\s*$']},
           'commands': [{'command': '/help',
                         'description': 'Show help and slash commands.'},
                        {'command': '/clear',
                         'description': 'Start a fresh conversation.'},
                        {'command': '/compact',
                         'description': 'Compact the conversation context.'},
                        {'command': '/diff', 'description': 'Show current changes.'},
                        {'command': '/exit', 'description': 'Exit Codex CLI.'},
                        {'command': '/init',
                         'description': 'Create project guidance files.'},
                        {'command': '/model',
                         'description': 'Select or view the active model.'},
                        {'command': '/status', 'description': 'Show session status.'},
                        {'command': '/quit', 'description': 'Exit Codex CLI.'},
                        {'command': '/review', 'description': 'Review code changes.'}],
           'clear': {'match': '(?i)(cleared|conversation\\s+(cleared|reset)|new\\s+conversation)',
                     'on_miss': 'pty_restart'}},
 'default': {'approval': {'auto_confirm': [],
                          'always_escalate': ['(?i)rm\\s+-rf',
                                              '(?i)git\\s+reset\\s+--hard',
                                              '(?i)drop\\s+table'],
                          'pending': []}},
 'gemini': {'approval': {'auto_confirm': ['(?i)press\\s+enter\\s+to\\s+confirm\\s+paste',
                                          '(?i)pasted\\s+\\d+\\s+lines'],
                         'always_escalate': ['(?i)rm\\s+-rf',
                                             '(?i)git\\s+reset\\s+--hard',
                                             '(?i)drop\\s+table',
                                             '(?i)delete\\s+.*\\b(AI_Exports|agent|src|docs)\\b'],
                         'pending': ['(?i)\\b(allow|approve|permit|continue|proceed)\\b.*(\\?|:|\\[y/n\\])\\s*$']},
            'commands': [{'command': '/help',
                          'description': 'Show help and slash commands.'},
                         {'command': '/clear',
                          'description': 'Clear conversation context.'},
                         {'command': '/chat', 'description': 'Manage chat state.'},
                         {'command': '/compress',
                          'description': 'Compress the conversation context.'},
                         {'command': '/editor',
                          'description': 'Open or configure editor integration.'},
                         {'command': '/exit', 'description': 'Exit Gemini CLI.'},
                         {'command': '/mcp', 'description': 'Manage MCP servers.'},
                         {'command': '/memory', 'description': 'Show or edit memory.'},
                         {'command': '/restore',
                          'description': 'Restore a previous chat.'},
                         {'command': '/stats',
                          'description': 'Show session statistics.'},
                         {'command': '/theme', 'description': 'Change terminal theme.'},
                         {'command': '/tools', 'description': 'List available tools.'},
                         {'command': '/quit', 'description': 'Exit Gemini CLI.'}],
            'clear': {'match': '(?i)(cleared|context\\s+cleared|conversation\\s+(cleared|reset))',
                      'on_miss': 'pty_restart'}},
 'gemma4_31b': {'approval': {'auto_confirm': ['(?i)press\\s+enter\\s+to\\s+confirm\\s+paste',
                                              '(?i)pasted\\s+\\d+\\s+lines'],
                             'always_escalate': ['(?i)rm\\s+-rf',
                                                 '(?i)git\\s+reset\\s+--hard',
                                                 '(?i)drop\\s+table',
                                                 '(?i)delete\\s+.*\\b(AI_Exports|agent|src|docs)\\b'],
                             'pending': ['(?i)\\b(allow|approve|permit|continue|proceed)\\b.*(\\?|:|\\[y/n\\])\\s*$']},
                'commands': [{'command': '/help',
                              'description': 'Show available slash commands.'},
                             {'command': '/clear',
                              'description': 'Reset the conversation history.'},
                             {'command': '/queue <text>',
                              'description': 'Queue a prompt to run after the current '
                                             'turn.'},
                             {'command': '/interrupt [text]',
                              'description': 'Abort the current turn and optionally '
                                             'queue replacement text.'},
                             {'command': '/think [on|off|auto]',
                              'description': 'Force thinking mode on/off, or return to '
                                             'auto. No arg = show state.'},
                             {'command': '/mode [<name>|auto]',
                              'description': 'Lock sampling mode '
                                             '(tool/plan/recover/explain/recipe) or '
                                             'return to auto.'},
                             {'command': '/ccommands [name]',
                              'description': 'List or load custom command prompts.'},
                             {'command': '/save-recipe',
                              'description': 'Offer to save the current workflow as a '
                                             'reusable recipe.'}],
                'clear': {'match': '(?i)(conversation history cleared|conversation '
                                   'reset|history reset|cleared)',
                          'on_miss': 'pty_restart'}},
 'gemma4_31b_claude': {'approval': {'auto_confirm': ['(?i)press\\s+enter\\s+to\\s+confirm\\s+paste',
                                                     '(?i)pasted\\s+\\d+\\s+lines'],
                                    'always_escalate': ['(?i)rm\\s+-rf',
                                                        '(?i)git\\s+reset\\s+--hard',
                                                        '(?i)drop\\s+table',
                                                        '(?i)delete\\s+.*\\b(AI_Exports|agent|src|docs)\\b'],
                                    'pending': ['(?i)\\b(allow|approve|permit|continue|proceed)\\b.*(\\?|:|\\[y/n\\])\\s*$']},
                       'commands': [{'command': '/help',
                                     'description': 'Show available slash commands.'},
                                    {'command': '/clear',
                                     'description': 'Reset the conversation history.'},
                                    {'command': '/queue <text>',
                                     'description': 'Queue a prompt to run after the '
                                                    'current turn.'},
                                    {'command': '/interrupt [text]',
                                     'description': 'Abort the current turn and '
                                                    'optionally queue replacement '
                                                    'text.'},
                                    {'command': '/think [on|off|auto]',
                                     'description': 'Force thinking mode on/off, or '
                                                    'return to auto. No arg = show '
                                                    'state.'},
                                    {'command': '/mode [<name>|auto]',
                                     'description': 'Lock sampling mode '
                                                    '(tool/plan/recover/explain/recipe) '
                                                    'or return to auto.'},
                                    {'command': '/ccommands [name]',
                                     'description': 'List or load custom command '
                                                    'prompts.'},
                                    {'command': '/save-recipe',
                                     'description': 'Offer to save the current '
                                                    'workflow as a reusable recipe.'}],
                       'clear': {'match': '(?i)(conversation history '
                                          'cleared|conversation reset|history '
                                          'reset|cleared)',
                                 'on_miss': 'pty_restart'}}}


#: Optional override root with the same ``<id>/<kind>.json`` layout as the JAR.
AGENT_RESOURCE_DIR_ENV = "IMAGEJAI_AGENT_RESOURCES"


def _resource(kind: str, agent_key: str, resource_dir: str | os.PathLike[str] | None = None):
    """ApprovalPolicy.java:79-99 / AgentRegistry.java:155-182 - load ``/agents/<id>/<kind>.json``."""
    ident = str(agent_key)
    root = resource_dir or os.environ.get(AGENT_RESOURCE_DIR_ENV)
    if root:
        candidate = Path(root) / ident / f"{kind}.json"
        if candidate.is_file():
            try:
                return json.loads(candidate.read_text(encoding="utf-8"))
            except (OSError, ValueError):
                return None
    return _AGENT_RESOURCES.get(ident, {}).get(kind)


def _agent_key(agent_or_id: "CliAgent | str") -> str:
    return agent_or_id.agent_id if isinstance(agent_or_id, CliAgent) else str(agent_or_id)


@dataclass(frozen=True)
class CommandEntry:
    """AgentRegistry.CommandEntry - one palette row."""

    command: str
    description: str = ""


def builtin_commands(
    agent_or_id: "CliAgent | str", *, resource_dir: str | os.PathLike[str] | None = None
) -> list[CommandEntry]:
    """AgentRegistry.java:155-182 - the agent's own slash commands (S5.30)."""
    data = _resource("commands", _agent_key(agent_or_id), resource_dir)
    if not isinstance(data, list):
        return []
    entries = []
    for item in data:
        if isinstance(item, dict) and str(item.get("command", "")).strip():
            entries.append(
                CommandEntry(str(item["command"]), str(item.get("description", "")))
            )
    return entries


def clear_pattern(
    agent_or_id: "CliAgent | str", *, resource_dir: str | os.PathLike[str] | None = None
) -> re.Pattern | None:
    """AgentRegistry.java:184-205 - the "chat really cleared" matcher (S5.32)."""
    data = _resource("clear", _agent_key(agent_or_id), resource_dir)
    if not isinstance(data, dict):
        return None
    regex = str(data.get("match", "")).strip()
    if not regex:
        return None
    try:
        return re.compile(regex, re.IGNORECASE)
    except re.error:
        return None


def clear_verdict(
    agent_or_id: "CliAgent | str",
    scrollback: str,
    *,
    resource_dir: str | os.PathLike[str] | None = None,
) -> str:
    """LeftRail.java:520-560 - ``"cleared"`` or ``"pty_restart"`` (S5.32).

    ``/clear`` is trusted only when the agent says it worked; otherwise the
    contract is ``on_miss: pty_restart``, because a half-cleared context is
    worse than a restart.
    """
    pattern = clear_pattern(agent_or_id, resource_dir=resource_dir)
    if pattern is not None and pattern.search(scrollback or ""):
        return "cleared"
    return "pty_restart"


# --------------------------------------------------------------------------
# S5.31 - user-defined commands from disk
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class UserCommandsResult:
    """AgentRegistry.UserCommandsResult - commands plus what the scan touched."""

    commands: tuple[CommandEntry, ...] = ()
    directories_read: int = 0
    entries_inspected: int = 0


_USER_COMMAND_CACHE: "OrderedDict[str, tuple[float, UserCommandsResult]]" = OrderedDict()


def user_command_dir(
    agent_or_id: "CliAgent | str",
    workspace: str | os.PathLike[str] | None,
    *,
    home: str | os.PathLike[str] | None = None,
) -> tuple[Path | None, tuple[str, ...], str]:
    """AgentRegistry.java:268-287 - directory, accepted suffixes, command prefix."""
    ident = _agent_key(agent_or_id)
    if ident == "console" and workspace is not None:
        return Path(workspace) / "commands", (".md", ".txt"), "/"
    if ident == "claude" and workspace is not None:
        return Path(workspace) / ".claude" / "commands", (".md",), "/"
    if ident in ("gemma4_31b", "gemma4_31b_claude"):
        root = Path(home) if home is not None else Path.home()
        return (
            root / ".config" / "imagej-ai" / "gemma4_31b" / ".ccommands",
            (".md", ".txt"),
            "/ccommands ",
        )
    return None, (), ""


def user_commands(
    agent_or_id: "CliAgent | str",
    workspace: str | os.PathLike[str] | None = None,
    *,
    home: str | os.PathLike[str] | None = None,
    now: float | None = None,
    use_cache: bool = True,
) -> UserCommandsResult:
    """AgentRegistry.java:289-380 - scan the user's command folder (S5.31).

    Caps are enforced because a mistyped path (a home directory, a mounted
    share) must fail loudly and cheaply instead of walking a huge tree.
    """
    ident = _agent_key(agent_or_id)
    clock = time.monotonic() if now is None else now
    key = ident + "|" + (str(Path(workspace).absolute()) if workspace else "")
    if use_cache:
        cached = _USER_COMMAND_CACHE.get(key)
        if cached is not None and clock - cached[0] < USER_COMMAND_CACHE_S:
            _USER_COMMAND_CACHE.move_to_end(key)
            return cached[1]

    directory, suffixes, prefix = user_command_dir(ident, workspace, home=home)
    result = _scan_user_commands(directory, suffixes, prefix)
    if use_cache:
        _USER_COMMAND_CACHE[key] = (clock, result)
        _USER_COMMAND_CACHE.move_to_end(key)
        while len(_USER_COMMAND_CACHE) > MAX_USER_COMMAND_CACHE_ENTRIES:
            _USER_COMMAND_CACHE.popitem(last=False)
    return result


def clear_user_command_cache() -> None:
    """Drop the 5 s scan cache; used by tests and by an explicit rescan."""
    _USER_COMMAND_CACHE.clear()


def _scan_user_commands(
    directory: Path | None, suffixes: Sequence[str], prefix: str
) -> UserCommandsResult:
    if directory is None:
        return UserCommandsResult()
    if not directory.exists():
        # AgentRegistry.java:322-324 - a missing folder is normal, not an error.
        return UserCommandsResult()
    if not directory.is_dir():
        raise CommandScanError("not_a_directory", directory, "Command path is not a directory.")

    inspected = 0
    picked: list[Path] = []
    try:
        for entry in directory.iterdir():
            inspected += 1
            if inspected > MAX_USER_COMMAND_DIRECTORY_ENTRIES:
                raise CommandScanError(
                    "directory_entry_cap",
                    directory,
                    "Command directory contains more than "
                    f"{MAX_USER_COMMAND_DIRECTORY_ENTRIES} entries.",
                    inspected,
                )
            if not entry.is_file():
                continue
            if suffixes and not entry.name.lower().endswith(tuple(suffixes)):
                continue
            if len(picked) >= MAX_USER_COMMANDS:
                raise CommandScanError(
                    "command_cap",
                    directory,
                    "Command directory contains more than "
                    f"{MAX_USER_COMMANDS} supported commands.",
                    inspected,
                )
            picked.append(entry)
    except OSError as failure:
        raise CommandScanError(
            "directory_unreadable",
            directory,
            f"Could not read command directory: {failure}",
            inspected,
        ) from failure

    picked.sort(key=lambda path: path.name.lower())
    commands = tuple(
        CommandEntry(prefix + path.stem, str(path.resolve())) for path in picked
    )
    return UserCommandsResult(commands, 1, inspected)


@dataclass(frozen=True)
class CommandPalette:
    """LeftRail.java:421-510 - the popup's two sections (S5.30, S5.31)."""

    builtin: tuple[CommandEntry, ...] = ()
    user: tuple[CommandEntry, ...] = ()
    user_error: str = ""

    def all_commands(self) -> tuple[CommandEntry, ...]:
        return self.builtin + self.user


def command_palette(
    agent_or_id: "CliAgent | str",
    workspace: str | os.PathLike[str] | None = None,
    *,
    home: str | os.PathLike[str] | None = None,
    now: float | None = None,
    use_cache: bool = True,
    resource_dir: str | os.PathLike[str] | None = None,
) -> CommandPalette:
    """Built-in commands plus user commands, with scan errors surfaced not swallowed."""
    builtin = tuple(builtin_commands(agent_or_id, resource_dir=resource_dir))
    try:
        found = user_commands(
            agent_or_id, workspace, home=home, now=now, use_cache=use_cache
        )
        return CommandPalette(builtin, found.commands)
    except CommandScanError as failure:
        return CommandPalette(builtin, (), f"[{failure.code}] {failure}")


def injected_command_text(entry: CommandEntry) -> str:
    """LeftRail.java:500-510 - a chosen command is sent with a trailing return."""
    return entry.command + "\r"


# --------------------------------------------------------------------------
# S5.23 / S5.24 - approval prompts
# --------------------------------------------------------------------------

#: PromptWatcher.java:37-38
PROMPT_TRAILER = re.compile(r"(?is).*(\?|:|\[y/n])\s*$")
URL_PATTERN = re.compile(r"(https?://\S+|file://\S+)")


class ApprovalPolicy:
    """ApprovalPolicy.java - decide what to do with one detected prompt (S5.24)."""

    def __init__(
        self,
        agent_id: str,
        auto_confirm: Sequence[str] = (),
        always_escalate: Sequence[str] = (),
        pending: Sequence[str] = (),
    ):
        self.agent_id = agent_id
        self.auto_confirm = self._compile(auto_confirm)
        self.always_escalate = self._compile(always_escalate)
        self.pending = self._compile(pending)

    @staticmethod
    def _compile(patterns: Sequence[str]) -> tuple[re.Pattern, ...]:
        compiled = []
        for raw in patterns or ():
            try:
                compiled.append(re.compile(raw))
            except re.error:
                # ApprovalPolicy.java:114-118 - a bad regex is skipped, not fatal.
                continue
        return tuple(compiled)

    @classmethod
    def load_for_agent(
        cls,
        agent_or_id: "CliAgent | str",
        *,
        resource_dir: str | os.PathLike[str] | None = None,
    ) -> "ApprovalPolicy":
        """ApprovalPolicy.java:45-58 - per-agent file, else ``default``, else escalate all."""
        ident = _agent_key(agent_or_id)
        data = _resource("approval", ident, resource_dir)
        if not isinstance(data, dict):
            ident, data = "default", _resource("approval", "default", resource_dir)
        if not isinstance(data, dict):
            return cls("default")
        return cls(
            ident,
            data.get("auto_confirm", ()),
            data.get("always_escalate", ()),
            data.get("pending", ()),
        )

    def decide(self, prompt_text: str | None) -> ApprovalDecision:
        """ApprovalPolicy.java:65-77 - escalate first, then auto, then pending.

        Anything unmatched escalates: an unknown prompt must reach the human.
        """
        prompt = prompt_text or ""
        if any(p.search(prompt) for p in self.always_escalate):
            return ApprovalDecision.ESCALATE
        if any(p.search(prompt) for p in self.auto_confirm):
            return ApprovalDecision.AUTO_CONFIRM
        if any(p.search(prompt) for p in self.pending):
            return ApprovalDecision.PENDING
        return ApprovalDecision.ESCALATE


def prompt_candidate(tail: str | None) -> str | None:
    """PromptWatcher.java:217-241 - last two non-blank lines ending ``?``/``:``/``[y/n]``."""
    lines = [line.strip() for line in re.split(r"\r\n|\r|\n", tail or "") if line.strip()]
    if not lines:
        return None
    candidate = "\n".join(lines[-2:])
    return candidate if PROMPT_TRAILER.match(candidate) else None


def latest_url(tail: str | None) -> str | None:
    """PromptWatcher.java:243-258 - last URL in the tail, trailing punctuation removed."""
    found = URL_PATTERN.findall(tail or "")
    if not found:
        return None
    return found[-1].rstrip(".,)]")


@dataclass(frozen=True)
class PromptEvent:
    """One watcher poll result; ``None`` fields mean "nothing changed"."""

    decision: ApprovalDecision | None = None
    prompt: str = ""
    url: str | None = None
    cleared: bool = False
    write_failed: bool = False
    message: str = ""


class PromptWatcher:
    """PromptWatcher.java - pure polling logic over terminal tail text (S5.23).

    The Swing version owns a 250 ms timer; here the caller supplies each tail
    and owns the schedule, so the decision table is testable with plain
    strings and no clock.
    """

    def __init__(
        self,
        policy: ApprovalPolicy,
        writer: Callable[[str], WriteResult] | None = None,
    ):
        self.policy = policy
        self.writer = writer
        self.last_tail = ""
        self.last_prompt = ""
        self.last_url = ""
        self.failure_count = 0

    def poll(self, tail: str | None) -> PromptEvent:
        text = tail or ""
        if text == self.last_tail:
            return PromptEvent()
        self.last_tail = text

        url = latest_url(text)
        new_url = url if url and url != self.last_url else None
        if new_url:
            self.last_url = new_url

        prompt = prompt_candidate(text)
        if prompt is None:
            if self.last_prompt:
                self.last_prompt = ""
                return PromptEvent(url=new_url, cleared=True)
            return PromptEvent(url=new_url)
        if prompt == self.last_prompt:
            return PromptEvent(url=new_url)
        self.last_prompt = prompt

        decision = self.policy.decide(prompt)
        if decision is ApprovalDecision.AUTO_CONFIRM:
            result = self.writer("\r") if self.writer else WriteResult(
                False, "terminal writer unavailable"
            )
            if not result.ok:
                # PromptWatcher.java:149-166 - never report a failed write as
                # confirmed; forget the tail so the same prompt is retried.
                self.last_tail = ""
                self.last_prompt = ""
                self.failure_count += 1
                return PromptEvent(
                    decision=ApprovalDecision.PENDING,
                    prompt=prompt,
                    url=new_url,
                    write_failed=True,
                    message=result.message,
                )
        return PromptEvent(decision=decision, prompt=prompt, url=new_url)


# --------------------------------------------------------------------------
# S5.22 - outbound prompt scrubbing with a raw override
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class ScrubResult:
    """What one prepared write will actually send."""

    text: str
    replacements: int = 0
    raw_override: bool = False


class OutboundScrubber:
    """Replace sensitive substrings before text reaches the agent (S5.22).

    The token map is injected because the mapping itself belongs to the
    privacy layer, not to the launcher. ``prepare``/``commit``/``rollback``
    mirror ImageJAITtyConnector so a failed write never advances the
    replacement bookkeeping. ``Ctrl+Shift+Enter`` sets the one-send raw
    override, which the connector logs as a deliberate user action.
    """

    def __init__(self, tokens: Mapping[str, str] | None = None):
        self.tokens = dict(tokens or {})
        self.raw_once = False
        self.replacements = 0
        self._pending: ScrubResult | None = None
        self.log: list[str] = []

    def request_raw_override(self) -> None:
        self.raw_once = True

    def prepare(self, text: str) -> ScrubResult:
        if len(text) > MAX_WRITE_BYTES:
            raise ValueError(
                f"Outbound write exceeds {MAX_WRITE_BYTES} bytes; refusing to send."
            )
        if self.raw_once:
            self._pending = ScrubResult(text, 0, True)
            return self._pending
        scrubbed = text
        count = 0
        for secret, token in self.tokens.items():
            if not secret:
                continue
            hits = scrubbed.count(secret)
            if hits:
                if count + hits > MAX_REPLACEMENTS_PER_LINE:
                    raise ValueError(
                        f"More than {MAX_REPLACEMENTS_PER_LINE} replacements in one line."
                    )
                scrubbed = scrubbed.replace(secret, token)
                count += hits
        self._pending = ScrubResult(scrubbed, count, False)
        return self._pending

    def commit(self) -> None:
        pending, self._pending = self._pending, None
        if pending is None:
            return
        if pending.raw_override:
            self.raw_once = False
            self.log.append("Sent raw embedded-terminal prompt by user override.")
        elif pending.replacements:
            self.replacements += pending.replacements
            self.log.append(
                f"Pseudonymised {pending.replacements} sensitive substring(s) before send."
            )

    def rollback(self) -> None:
        """A failed write keeps the override armed so the retry still honours it."""
        self._pending = None


# --------------------------------------------------------------------------
# S5.35 - optional terminal transcript on disk
# --------------------------------------------------------------------------


def scrollback_log_path(
    agent_or_id: "CliAgent | str",
    root: str | os.PathLike[str],
    *,
    now: datetime | None = None,
) -> Path:
    """EmbeddedAgentSession.java:152-184 - ``AI_Exports/.session/log/<id>_<stamp>.log``."""
    ident = re.sub(r"[^A-Za-z0-9_.-]+", "_", _agent_key(agent_or_id))
    stamp = (now or datetime.now()).strftime(SCROLLBACK_TIMESTAMP)
    return Path(root) / "AI_Exports" / ".session" / "log" / f"{ident}_{stamp}.log"


def persist_scrollback(
    text: str,
    root: str | os.PathLike[str] | None,
    agent_or_id: "CliAgent | str",
    *,
    enabled: bool = False,
    now: datetime | None = None,
    limit: int = SCROLLBACK_LINES,
) -> Path | None:
    """Write the transcript only when the user asked for it (S5.35).

    Off by default because scrollback can contain pasted keys; the file lands
    under ``AI_Exports/`` as the house rule requires.
    """
    if not enabled or root is None or not (text or "").strip():
        return None
    path = scrollback_log_path(agent_or_id, root, now=now)
    path.parent.mkdir(parents=True, exist_ok=True)
    body = "\n".join((text or "").splitlines()[-limit:])
    path.write_text(body, encoding="utf-8")
    return path
