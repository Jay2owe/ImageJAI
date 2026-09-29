"""Tests for the console's CLI agent registry and launcher (S5.*).

No test starts a real process, reads the real ~/.imagej-ai, or touches the
network: every process, clock, path root, and lookup is injected.
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
from datetime import datetime
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[3]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from agent.console import cliagents as ca  # noqa: E402


@pytest.fixture(autouse=True)
def _no_command_cache():
    ca.clear_user_command_cache()
    yield
    ca.clear_user_command_cache()


# ---------------------------------------------------------------------------
# S5.3 / S5.25 registry
# ---------------------------------------------------------------------------


def test_registry_matches_the_nine_java_agents():
    # AgentLauncher.java:112-122
    assert [(a.name, a.command) for a in ca.KNOWN_AGENTS] == [
        ("Claude Code", "claude"),
        ("Aider", "aider"),
        ("GitHub Copilot CLI", "gh copilot"),
        ("Gemini CLI", "gemini"),
        ("Open Interpreter", "interpreter"),
        ("Cline", "cline"),
        ("Codex CLI", "codex"),
        ("Gemma 4 31B", "gemma4_31b_agent"),
        ("Gemma 4 31B (Claude-style)", "gemma4_31b_agent"),
    ]


def test_registry_carries_flags_runtimes_and_install_commands():
    aider = ca.find_agent("aider")
    assert aider.context_flags == "--read .aider.conventions.md"
    assert aider.runtime == "python"
    assert aider.install_command == "pip install aider-chat"
    gemma = ca.find_agent("gemma4_31b")
    assert gemma.local and gemma.default_ollama_model == "gemma4:31b-cloud"
    assert gemma.runtime == "ollama"
    assert ca.find_agent("claude").install_command == "npm i -g @anthropic-ai/claude-code"


@pytest.mark.parametrize(
    "command,flags,expected",
    [
        ("gemma4_31b_agent", "--style claude", "gemma4_31b_claude"),
        ("gemma4_31b_agent", "", "gemma4_31b"),
        ("python -m agent.providers.agent_cli --provider groq", "", "provider_groq"),
        ("my_agent", "", "my"),
        ("gh copilot", "", "gh"),
        ("", "", "default"),
        (None, "", "default"),
    ],
)
def test_agent_id_resolution(command, flags, expected):
    # AgentRegistry.java:63-85
    assert ca.agent_id(command, flags) == expected


def test_resume_and_consent_flags_per_agent():
    assert ca.find_agent("claude").supports_resume
    assert ca.find_agent("codex").supports_resume
    assert not ca.find_agent("aider").supports_resume
    assert ca.find_agent("claude").needs_consent
    assert ca.find_agent("gemini").needs_consent
    assert not ca.find_agent("aider").needs_consent


def test_local_assistant_has_no_executable():
    assert ca.LOCAL_ASSISTANT.command == ""
    assert ca.LOCAL_ASSISTANT.name == ca.LOCAL_ASSISTANT_NAME
    assert ca.LOCAL_ASSISTANT not in ca.KNOWN_AGENTS
    assert ca.find_agent("local assistant") is ca.LOCAL_ASSISTANT


# ---------------------------------------------------------------------------
# S5.4 / S5.5 discovery
# ---------------------------------------------------------------------------


def fake_path(table):
    return lambda command: table.get(command)


def test_discovery_uses_path_first():
    found = ca.discover_executable(
        ca.find_agent("claude"),
        path_lookup=fake_path({"claude": r"C:\npm\claude.cmd"}),
    )
    assert found.found and found.source == "path"
    assert found.path == r"C:\npm\claude.cmd"


def test_windows_candidate_folders_match_java():
    # AgentLauncher.java:731-737
    assert ca.executable_candidates("codex", os_name="Windows 11", home=r"C:\test-home") == [
        r"C:\test-home\AppData\Roaming\npm\codex.cmd",
        r"C:\test-home\AppData\Local\Programs\codex\codex.exe",
        r"C:\test-home\.local\bin\codex.exe",
        r"C:\Program Files\codex\codex.exe",
    ]


def test_unix_candidate_folders_match_java():
    # AgentLauncher.java:739-744
    assert ca.executable_candidates("aider", os_name="Linux", home="/home/x") == [
        "/home/x/.local/bin/aider",
        "/usr/local/bin/aider",
        "/home/x/.npm-global/bin/aider",
    ]


def test_discovery_falls_back_to_install_folder():
    wanted = "/home/x/.local/bin/aider"
    found = ca.discover_executable(
        ca.find_agent("aider"),
        path_lookup=fake_path({}),
        is_executable=lambda path: path == wanted,
        os_name="Linux",
        home="/home/x",
    )
    assert found.found and found.source == "install_dir"
    assert found.path.endswith("aider")


def test_missing_agent_explains_the_install_command():
    found = ca.discover_executable(
        ca.find_agent("claude"), path_lookup=fake_path({}), is_executable=lambda p: False
    )
    assert not found.found
    assert "npm i -g @anthropic-ai/claude-code" in found.message
    assert "Node.js" in found.message


def test_missing_agent_without_install_command_says_so():
    found = ca.discover_executable(
        ca.find_agent("cline"), path_lookup=fake_path({}), is_executable=lambda p: False
    )
    assert "No ImageJAI install command is configured for 'cline'" in found.message


def test_missing_runtime_message_names_the_runtime():
    assert "Ollama" in ca.missing_runtime_message(ca.find_agent("gemma4_31b"))
    assert "Node.js" in ca.missing_runtime_message(ca.find_agent("codex"))


def test_bundled_gemma_is_found_without_a_console_script(tmp_path):
    # AgentLauncher.java:702-709 (S5.5)
    module = tmp_path / "gemma4_31b"
    module.mkdir()
    (module / "__main__.py").write_text("", encoding="utf-8")
    found = ca.discover_executable(
        ca.find_agent("gemma4_31b"),
        workspace=tmp_path,
        path_lookup=fake_path({}),
        is_executable=lambda p: False,
    )
    assert found.found and found.source == "bundled_module"


def test_installed_agents_keeps_only_resolved_ones():
    installed = ca.installed_agents(
        path_lookup=fake_path({"claude": "/usr/bin/claude"}),
        is_executable=lambda p: False,
    )
    assert [a.command for a in installed] == ["claude"]


# ---------------------------------------------------------------------------
# S5.6 arguments from the shared settings file
# ---------------------------------------------------------------------------


def test_arguments_default_when_settings_file_is_absent(tmp_path):
    table = ca.load_cli_agent_arguments(tmp_path / "config.json")
    assert table == ca.DEFAULT_CLI_AGENT_ARGUMENTS


def test_arguments_read_the_plugin_settings_file(tmp_path):
    path = tmp_path / "config.json"
    path.write_text(
        json.dumps({"cliAgentArguments": {"Claude": " --verbose ", "aider": "--no-git"}}),
        encoding="utf-8",
    )
    table = ca.load_cli_agent_arguments(path)
    assert table["claude"] == "--verbose"       # key is the lower-cased first token
    assert table["aider"] == "--no-git"
    assert table["codex"] == "--yolo"           # shipped default survives


def test_broken_settings_file_falls_back_to_defaults(tmp_path):
    path = tmp_path / "config.json"
    path.write_text("{not json", encoding="utf-8")
    assert ca.load_cli_agent_arguments(path) == ca.DEFAULT_CLI_AGENT_ARGUMENTS


@pytest.mark.parametrize(
    "agent_key,table,expected",
    [
        ("claude", {}, ""),
        ("claude", {"claude": "--foo"}, "--foo"),
        ("aider", {}, "--read .aider.conventions.md"),
        ("aider", {"aider": "--no-git"}, "--read .aider.conventions.md --no-git"),
        ("aider", {"aider": "--read .aider.conventions.md"}, "--read .aider.conventions.md"),
    ],
)
def test_effective_arguments(agent_key, table, expected):
    # AgentLauncher.java:636-645
    assert ca.effective_arguments(ca.find_agent(agent_key), table) == expected


@pytest.mark.parametrize(
    "arguments,expected",
    [
        ("--dangerously-skip-permissions", True),
        ("--yolo", True),
        ("--dangerously-bypass-approvals-and-sandbox", True),
        ("--yolo-off", False),
        ("--model '--yolo'", True),
        ("", False),
    ],
)
def test_dangerous_flag_detection_is_token_exact(arguments, expected):
    assert ca.contains_dangerous_permission_bypass(arguments) is expected


# ---------------------------------------------------------------------------
# S5.8 one-launch consent
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "agent_key,arguments,consent,planner,add_flag",
    [
        ("claude", "", True, True, True),
        ("claude", "", False, True, False),
        ("claude", "", True, False, False),
        ("claude", "--dangerously-skip-permissions", True, True, False),
        ("codex", "", True, True, False),
        ("aider", "", True, True, False),
    ],
)
def test_consent_decision_table(agent_key, arguments, consent, planner, add_flag):
    # AgentLauncher.java:566-600
    decision = ca.decide_permission_bypass(
        ca.find_agent(agent_key),
        arguments=arguments,
        consent_granted=consent,
        planner_installed=planner,
    )
    assert decision.add_flag is add_flag
    assert decision.clear_consent is add_flag


def test_consent_is_consumed_by_building_the_command():
    built = ca.build_command_string(
        ca.find_agent("claude"), arguments={}, consent_granted=True, planner_installed=True
    )
    assert built.text == "claude --dangerously-skip-permissions"
    assert built.consent_consumed is True
    assert built.dangerous_permissions is True


def test_planner_probe_prefers_the_skills_directory(tmp_path):
    (tmp_path / ".claude" / "skills" / "gsd").mkdir(parents=True)
    assert ca.planner_installed(home=tmp_path, probe=lambda: False) is True
    assert ca.planner_installed(home=tmp_path / "empty", probe=lambda: True) is True
    assert ca.planner_installed(home=tmp_path / "empty", probe=None) is False


# ---------------------------------------------------------------------------
# S5.7 command assembly
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "agent_key,expected",
    [
        ("claude", "claude --dangerously-skip-permissions"),
        ("codex", "codex --yolo"),
        ("gemini", "gemini --yolo"),
        ("aider", "aider --read .aider.conventions.md"),
        ("gh copilot", "gh copilot"),
    ],
)
def test_command_assembly_per_agent(agent_key, expected):
    built = ca.build_command_string(
        ca.find_agent(agent_key), arguments=ca.DEFAULT_CLI_AGENT_ARGUMENTS
    )
    assert built.text == expected


@pytest.mark.parametrize(
    "agent_key,expected",
    [
        ("claude", "claude --continue"),
        ("gemini", "gemini --resume latest"),
        ("codex", "codex resume --last"),
    ],
)
def test_resume_commands(agent_key, expected):
    # AgentLauncher.java:605-621
    built = ca.build_command_string(
        ca.find_agent(agent_key), session_action=ca.SessionAction.RESUME_LATEST, arguments={}
    )
    assert built.text.startswith(expected)


def test_resume_refuses_agents_without_a_known_form():
    with pytest.raises(ca.ResumeUnavailable):
        ca.build_command_string(
            ca.find_agent("aider"), session_action=ca.SessionAction.RESUME_LATEST
        )
    assert ca.resume_unavailable_message(ca.find_agent("aider")) == (
        "Resume is not available for Aider."
    )


def test_bundled_gemma_command_uses_python_module(tmp_path):
    module = tmp_path / "gemma4_31b"
    module.mkdir()
    (module / "__main__.py").write_text("", encoding="utf-8")
    built = ca.build_command_string(
        ca.find_agent("gemma4_31b_claude"),
        arguments={},
        workspace=tmp_path,
        python="python",
        os_name="Windows 11",
    )
    assert built.text == '"python" -m gemma4_31b --style claude'


def test_unsafe_python_executable_is_refused():
    with pytest.raises(ValueError):
        ca.quote_executable_for_shell('py"thon', "Windows 11")


# ---------------------------------------------------------------------------
# S5.10 posture filtering
# ---------------------------------------------------------------------------


def test_on_premises_hides_cloud_agents_with_the_java_message():
    agents = ca.known_agents()
    kept = ca.filter_agents_for_posture(agents, ca.ON_PREMISES)
    assert kept == []
    assert ca.posture_refusal(ca.find_agent("gemma4_31b"), ca.ON_PREMISES) == (
        ca.CLOUD_OLLAMA_REFUSAL
    )
    assert ca.filter_agents_for_posture(agents, "standard") == agents


@pytest.mark.parametrize(
    "tag,expected",
    [("gemma4:31b-cloud", True), ("gpt:cloud", True), ("gemma3:27b", False), ("", False)],
)
def test_cloud_ollama_tag_detection(tag, expected):
    assert ca.is_cloud_ollama_tag(tag) is expected


@pytest.mark.parametrize(
    "agent_key,expected",
    [
        ("claude", "anthropic.claude-code"),
        ("codex", "openai.codex"),
        ("gemini", "google.gemini-cli"),
        ("gemma4_31b", "ollama.cloud:gemma4:31b-cloud"),
    ],
)
def test_model_endpoint_ids(agent_key, expected):
    assert ca.model_endpoint_for(ca.find_agent(agent_key)) == expected


# ---------------------------------------------------------------------------
# S5.15 / S5.16 environment
# ---------------------------------------------------------------------------


def test_external_environment_contents(tmp_path):
    env = ca.build_environment(
        ca.find_agent("claude"),
        workspace=tmp_path,
        tcp_port=7746,
        safe_mode=True,
        session_id="abcd1234",
        imagej_root=tmp_path / "Fiji.app",
    )
    assert env["IMAGEJAI_TCP_PORT"] == "7746"
    assert env["IMAGEJAI_SAFE_MODE"] == "1"
    assert env[ca.AGENT_WORKSPACE_ENV] == str(tmp_path)
    assert env["IMAGEJAI_SESSION_ID"] == "abcd1234"
    assert env["IMAGEJAI_MODEL_ENDPOINT"] == "anthropic.claude-code"
    assert env[ca.USER_RECIPES_ENV].endswith(os.path.join("ImageJAI", "recipes"))
    assert str(tmp_path / "recipes") in env[ca.RECIPE_DIRS_ENV]
    assert "TERM" not in env


def test_embedded_environment_adds_terminal_hints(tmp_path):
    env = ca.build_environment(
        ca.find_agent("claude"), workspace=tmp_path, tcp_port=7747,
        safe_mode=False, mode=ca.LaunchMode.EMBEDDED,
    )
    assert env["TERM"] == "xterm-256color"
    assert env["COLORTERM"] == "truecolor"
    assert env["TERMINAL_EMULATOR"] == "JetBrains-JediTerm"
    assert env["IMAGEJAI_SAFE_MODE"] == "0"


def test_bundled_gemma_gets_pythonpath(tmp_path):
    module = tmp_path / "gemma4_31b"
    module.mkdir()
    (module / "__main__.py").write_text("", encoding="utf-8")
    env = ca.build_environment(
        ca.find_agent("gemma4_31b"), workspace=tmp_path, tcp_port=7746,
        host_env={"PYTHONPATH": "/existing"},
    )
    assert env["PYTHONPATH"] == str(tmp_path.parent) + os.pathsep + "/existing"


def test_session_id_is_eight_hex_characters(tmp_path):
    env = ca.build_environment(ca.find_agent("codex"), workspace=tmp_path, tcp_port=1)
    assert len(env["IMAGEJAI_SESSION_ID"]) == 8


def test_environment_merge_puts_imagejai_keys_last():
    # EmbeddedPty.java:90-103
    merged = ca.merge_environment({"PATH": "/bin", "TERM": "dumb"}, {"TERM": "xterm-256color"})
    assert merged["PATH"] == "/bin"
    assert merged["TERM"] == "xterm-256color"


# ---------------------------------------------------------------------------
# S5.18 context file sync
# ---------------------------------------------------------------------------


def test_context_sync_runs_the_workspace_script_and_reports_files(tmp_path):
    (tmp_path / "sync_context.py").write_text("", encoding="utf-8")
    calls = []

    def runner(argv, cwd, timeout_s):
        calls.append((argv, cwd, timeout_s))
        for name in ("CLAUDE.md", "GEMINI.md", ".clinerules"):
            (Path(cwd) / name).write_text("x", encoding="utf-8")
        return 0, "ok"

    result = ca.sync_context_files(tmp_path, runner=runner, python="python3")
    assert result.ran and result.exit_code == 0
    assert calls[0][0][0] == "python3"
    assert calls[0][2] == ca.CONTEXT_SYNC_TIMEOUT_S
    assert set(result.written) == {"CLAUDE.md", "GEMINI.md", ".clinerules"}


def test_context_sync_skipped_without_the_script(tmp_path):
    result = ca.sync_context_files(tmp_path, runner=lambda *a: (0, ""))
    assert result.ran is False
    assert "nothing to sync" in result.message


def test_context_sync_timeout_message(tmp_path):
    (tmp_path / "sync_context.py").write_text("", encoding="utf-8")

    def runner(argv, cwd, timeout_s):
        raise subprocess.TimeoutExpired(cmd=argv, timeout=timeout_s)

    result = ca.sync_context_files(tmp_path, runner=runner)
    assert result.timed_out and "timed out" in result.message


def test_each_agent_reads_its_own_context_file():
    assert ca.context_file_for(ca.find_agent("gemini")) == "GEMINI.md"
    assert ca.context_file_for(ca.find_agent("aider")) == ".aider.conventions.md"
    assert ca.context_file_for(ca.find_agent("claude")) == "CLAUDE.md"


# ---------------------------------------------------------------------------
# S5.12 launch specs
# ---------------------------------------------------------------------------


def test_windows_external_terminal_argv(tmp_path):
    spec = ca.build_external_launch_spec(
        ca.find_agent("claude"), "claude --continue",
        workspace=tmp_path, env={}, os_name="Windows 11",
    )
    assert list(spec.argv) == [
        "cmd.exe", "/c", "start", '"Claude Code"', "cmd.exe", "/k", "claude --continue",
    ]
    assert spec.mode is ca.LaunchMode.EXTERNAL


def test_linux_external_terminal_argv(tmp_path):
    spec = ca.build_external_launch_spec(
        ca.find_agent("aider"), "aider", workspace="/ws", env={},
        os_name="Linux", path_lookup=fake_path({"konsole": "/usr/bin/konsole"}),
    )
    assert spec.argv[0] == "konsole"
    assert "cd '/ws' && aider; exec bash" in spec.argv[-1]


def test_linux_without_a_terminal_emulator_is_reported(tmp_path):
    with pytest.raises(RuntimeError, match="No terminal emulator found"):
        ca.build_external_launch_spec(
            ca.find_agent("aider"), "aider", workspace="/ws", env={},
            os_name="Linux", path_lookup=fake_path({}),
        )


def test_inprocess_spec_uses_the_platform_shell(tmp_path):
    windows = ca.build_inprocess_launch_spec(
        ca.find_agent("codex"), "codex --yolo", workspace=tmp_path, env={}, os_name="Windows 11"
    )
    assert list(windows.argv) == ["cmd.exe", "/c", "codex --yolo"]
    posix = ca.build_inprocess_launch_spec(
        ca.find_agent("codex"), "codex --yolo", workspace=tmp_path, env={}, os_name="Linux"
    )
    assert list(posix.argv) == ["bash", "-lc", "exec codex --yolo"]


@pytest.mark.parametrize(
    "name,expected",
    [
        ("Gemma 4 31B (Claude-style)", "Gemma 4 31B (Claude-style)"),
        ("bad;name&here", "bad name here"),
        ("", "ImageJAI Agent"),
        ("x" * 80, "x" * 64),
    ],
)
def test_terminal_title_is_sanitised(name, expected):
    # AgentLauncher.java:685-697
    assert ca.safe_terminal_title(name) == expected


def test_plan_launch_builds_command_and_environment(tmp_path):
    spec = ca.plan_launch(
        ca.find_agent("claude"), workspace=tmp_path, tcp_port=7746,
        arguments=ca.DEFAULT_CLI_AGENT_ARGUMENTS, os_name="Windows 11",
    )
    assert spec.command_text == "claude --dangerously-skip-permissions"
    assert spec.env["IMAGEJAI_TCP_PORT"] == "7746"
    assert spec.cwd == str(tmp_path)


def test_plan_launch_refuses_a_posture_violation(tmp_path):
    with pytest.raises(ca.PostureViolation):
        ca.plan_launch(
            ca.find_agent("gemma4_31b"), workspace=tmp_path, tcp_port=7746,
            posture=ca.ON_PREMISES,
        )


# ---------------------------------------------------------------------------
# S5.26 / S5.33 / S5.34 sessions - all with fake processes
# ---------------------------------------------------------------------------


class FakeStdin:
    def __init__(self, fail=False):
        self.written = []
        self.fail = fail

    def write(self, text):
        if self.fail:
            raise OSError("pipe closed")
        self.written.append(text)

    def flush(self):
        pass


class FakeProcess:
    def __init__(self, lines=(), exit_code=None, stdin_fails=False):
        self.stdout = iter(list(lines))
        self.stdin = FakeStdin(stdin_fails)
        self._exit = exit_code
        self.pid = 4242
        self.killed = False
        self.waited = None

    def poll(self):
        return self._exit

    def wait(self, timeout=None):
        self.waited = timeout
        if self._exit is None:
            self._exit = 0
        return self._exit

    def kill(self):
        self.killed = True
        self._exit = -9


def test_streaming_session_streams_and_writes():
    session = ca.StreamingSession(ca.find_agent("claude"), FakeProcess(["a\n", "b\n"]))
    assert list(session.stream()) == ["a", "b"]
    assert session.scrollback() == "a\nb"
    assert session.write("hello").ok
    assert session.process.stdin.written == ["hello\r"]
    assert session.interrupt().ok
    assert session.process.stdin.written[-1] == "\x03"


def test_write_failure_keeps_the_prompt_retryable():
    # EmbeddedAgentSession.java:85-101 (S5.26)
    session = ca.StreamingSession(ca.find_agent("claude"), FakeProcess(stdin_fails=True))
    result = session.write("y")
    assert not result.ok
    assert result.message == (
        "PTY input write failed (OSError). Retry without clearing the prompt."
    )


def test_write_to_a_dead_session_says_so():
    session = ca.StreamingSession(ca.find_agent("claude"), FakeProcess(exit_code=0))
    assert session.write("y").message == "The terminal session is no longer running."


def test_session_end_detection():
    # AiRootPanel.java:1160-1185 (S5.33)
    process = FakeProcess()
    session = ca.StreamingSession(ca.find_agent("claude"), process)
    assert session.poll_end() is None
    process._exit = 3
    ended = session.poll_end()
    assert ended == ca.SessionEnd("claude", 3, "Embedded agent exited with code 3: Claude Code")


def test_clean_shutdown_sends_eof_then_waits():
    # EmbeddedAgentSession.java:131-150 (S5.34)
    process = FakeProcess()
    session = ca.StreamingSession(ca.find_agent("claude"), process)
    session.end(grace_s=ca.SHUTDOWN_GRACE_S)
    assert process.stdin.written == [ca.EOF_BYTE]
    assert process.waited == ca.SHUTDOWN_GRACE_S
    assert not process.killed


def test_detached_session_reports_what_it_cannot_do():
    session = ca.DetachedSession(ca.find_agent("claude"), pid=1)
    assert session.is_alive() is False
    assert "unsupported on detached external terminal" in session.write("x").message
    assert "unsupported on detached external terminal" in session.end().message


def test_runners_merge_the_host_environment(tmp_path):
    captured = {}

    def spawn(argv, **kwargs):
        captured["argv"] = argv
        captured.update(kwargs)
        return FakeProcess()

    spec = ca.plan_launch(
        ca.find_agent("codex"), workspace=tmp_path, tcp_port=7746,
        arguments={}, os_name="Windows 11",
    )
    ca.launch_detached(spec, spawn=spawn, host_env={"PATH": "/bin"})
    assert captured["env"]["PATH"] == "/bin"
    assert captured["env"]["IMAGEJAI_TCP_PORT"] == "7746"

    session = ca.launch_streaming(spec, spawn=spawn, host_env={"PATH": "/bin"})
    assert captured["stdout"] is subprocess.PIPE
    assert isinstance(session, ca.StreamingSession)


# ---------------------------------------------------------------------------
# S5.13 / S5.37 messages
# ---------------------------------------------------------------------------


def test_fallback_notice_names_winpty():
    failure = RuntimeError("Failed to load winpty library")
    assert ca.fallback_notice(failure) == (
        "Embedded terminal failed (WinPty unavailable). "
        "Launching the approved agent in an external window."
    )
    assert "ValueError" in ca.fallback_notice(ValueError("boom"))


def test_launch_messages():
    claude = ca.find_agent("claude")
    assert ca.launch_progress_message(claude, ca.SessionAction.NEW_SESSION) == (
        "Launching Claude Code..."
    )
    assert ca.launch_progress_message(claude, ca.SessionAction.RESUME_LATEST) == (
        "Resuming latest Claude Code..."
    )
    assert ca.launched_message(claude, ca.LaunchMode.EMBEDDED, "/ws") == (
        "Launched Claude Code inside the plugin window."
    )
    assert ca.launched_message(claude, ca.LaunchMode.EXTERNAL, "/ws") == (
        "Launched Claude Code in: /ws"
    )


# ---------------------------------------------------------------------------
# S5.30 / S5.31 slash-command palette
# ---------------------------------------------------------------------------


def test_builtin_commands_for_claude():
    commands = {entry.command for entry in ca.builtin_commands("claude")}
    assert {"/help", "/clear", "/compact", "/cost", "/model", "/status"} <= commands
    assert all(entry.description for entry in ca.builtin_commands("claude"))


def test_every_registered_agent_has_a_palette():
    for agent in ca.KNOWN_AGENTS:
        if agent.agent_id in ("claude", "codex", "gemini", "aider",
                              "gemma4_31b", "gemma4_31b_claude"):
            assert ca.builtin_commands(agent), agent.agent_id


def test_user_commands_are_read_from_the_claude_folder(tmp_path):
    folder = tmp_path / ".claude" / "commands"
    folder.mkdir(parents=True)
    (folder / "Zebra.md").write_text("", encoding="utf-8")
    (folder / "alpha.md").write_text("", encoding="utf-8")
    (folder / "ignored.txt").write_text("", encoding="utf-8")
    result = ca.user_commands("claude", tmp_path, use_cache=False)
    assert [entry.command for entry in result.commands] == ["/alpha", "/Zebra"]
    assert result.commands[0].description.endswith("alpha.md")
    assert result.entries_inspected == 3


def test_user_commands_for_gemma_use_the_config_folder(tmp_path):
    folder = tmp_path / ".config" / "imagej-ai" / "gemma4_31b" / ".ccommands"
    folder.mkdir(parents=True)
    (folder / "review.txt").write_text("", encoding="utf-8")
    result = ca.user_commands("gemma4_31b", None, home=tmp_path, use_cache=False)
    assert [entry.command for entry in result.commands] == ["/ccommands review"]


def test_missing_user_command_folder_is_not_an_error(tmp_path):
    assert ca.user_commands("claude", tmp_path, use_cache=False).commands == ()


def test_user_command_scan_refuses_a_file_path(tmp_path):
    path = tmp_path / ".claude" / "commands"
    path.parent.mkdir(parents=True)
    path.write_text("", encoding="utf-8")
    with pytest.raises(ca.CommandScanError) as failure:
        ca.user_commands("claude", tmp_path, use_cache=False)
    assert failure.value.code == "not_a_directory"


def test_user_command_cap_is_enforced(tmp_path, monkeypatch):
    folder = tmp_path / ".claude" / "commands"
    folder.mkdir(parents=True)
    for index in range(4):
        (folder / f"c{index}.md").write_text("", encoding="utf-8")
    monkeypatch.setattr(ca, "MAX_USER_COMMANDS", 2)
    with pytest.raises(ca.CommandScanError) as failure:
        ca.user_commands("claude", tmp_path, use_cache=False)
    assert failure.value.code == "command_cap"


def test_user_commands_are_cached_for_five_seconds(tmp_path):
    folder = tmp_path / ".claude" / "commands"
    folder.mkdir(parents=True)
    (folder / "one.md").write_text("", encoding="utf-8")
    first = ca.user_commands("claude", tmp_path, now=100.0)
    (folder / "two.md").write_text("", encoding="utf-8")
    assert ca.user_commands("claude", tmp_path, now=102.0).commands == first.commands
    later = ca.user_commands("claude", tmp_path, now=100.0 + ca.USER_COMMAND_CACHE_S + 1)
    assert len(later.commands) == 2


def test_palette_reports_a_scan_error_instead_of_hiding_it(tmp_path):
    path = tmp_path / ".claude" / "commands"
    path.parent.mkdir(parents=True)
    path.write_text("", encoding="utf-8")
    palette = ca.command_palette("claude", tmp_path, use_cache=False)
    assert palette.builtin
    assert palette.user == ()
    assert "not_a_directory" in palette.user_error


def test_injected_command_ends_with_return():
    assert ca.injected_command_text(ca.CommandEntry("/clear")) == "/clear\r"


# ---------------------------------------------------------------------------
# S5.32 new agent chat
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "scrollback,expected",
    [
        ("... conversation cleared", "cleared"),
        ("history reset now", "cleared"),
        ("nothing useful here", "pty_restart"),
        ("", "pty_restart"),
    ],
)
def test_clear_verdict(scrollback, expected):
    assert ca.clear_verdict("claude", scrollback) == expected


# ---------------------------------------------------------------------------
# S5.23 / S5.24 approval prompts
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "prompt,expected",
    [
        ("Do you trust the files in this folder?", ca.ApprovalDecision.AUTO_CONFIRM),
        ("Quick safety check", ca.ApprovalDecision.AUTO_CONFIRM),
        ("rm -rf /data", ca.ApprovalDecision.ESCALATE),
        ("git reset --hard", ca.ApprovalDecision.ESCALATE),
        ("Allow this edit? ", ca.ApprovalDecision.PENDING),
        ("something else entirely", ca.ApprovalDecision.ESCALATE),
    ],
)
def test_claude_approval_policy(prompt, expected):
    assert ca.ApprovalPolicy.load_for_agent("claude").decide(prompt) is expected


def test_unknown_agent_falls_back_to_the_default_policy():
    policy = ca.ApprovalPolicy.load_for_agent("no-such-agent")
    assert policy.agent_id == "default"
    # default/approval.json ships escalate rules only, so anything unmatched
    # reaches the human rather than being auto-answered.
    assert policy.decide("Allow this? ") is ca.ApprovalDecision.ESCALATE
    assert policy.decide("drop table users") is ca.ApprovalDecision.ESCALATE


def test_policy_can_be_overridden_from_disk(tmp_path):
    folder = tmp_path / "claude"
    folder.mkdir()
    (folder / "approval.json").write_text(
        json.dumps({"auto_confirm": ["(?i)press any key"]}), encoding="utf-8"
    )
    policy = ca.ApprovalPolicy.load_for_agent("claude", resource_dir=tmp_path)
    assert policy.decide("Press any key") is ca.ApprovalDecision.AUTO_CONFIRM
    assert policy.decide("Do you trust this folder?") is ca.ApprovalDecision.ESCALATE


@pytest.mark.parametrize(
    "tail,expected",
    [
        ("one\n\nAllow write? ", "one\nAllow write?"),
        ("just a sentence.", None),
        ("continue [y/n]", "continue [y/n]"),
        ("", None),
    ],
)
def test_prompt_candidate(tail, expected):
    assert ca.prompt_candidate(tail) == expected


def test_latest_url_strips_trailing_punctuation():
    assert ca.latest_url("see http://a.example/x and https://b.example/y).") == (
        "https://b.example/y"
    )
    assert ca.latest_url("no links") is None


def test_watcher_auto_confirms_and_reports_failures():
    policy = ca.ApprovalPolicy.load_for_agent("claude")
    written = []
    watcher = ca.PromptWatcher(policy, lambda text: (written.append(text), ca.WriteResult(True))[1])
    event = watcher.poll("Do you trust the files in this folder?")
    assert event.decision is ca.ApprovalDecision.AUTO_CONFIRM
    assert written == ["\r"]
    assert watcher.poll("Do you trust the files in this folder?").decision is None

    failing = ca.PromptWatcher(policy, lambda text: ca.WriteResult(False, "pipe closed"))
    failed = failing.poll("Do you trust the files in this folder?")
    assert failed.decision is ca.ApprovalDecision.PENDING
    assert failed.write_failed and failing.failure_count == 1
    assert failing.last_prompt == ""


def test_watcher_reports_cleared_prompts_and_urls():
    watcher = ca.PromptWatcher(ca.ApprovalPolicy.load_for_agent("claude"))
    watcher.poll("Allow this edit? ")
    event = watcher.poll("done, see https://example.com/report")
    assert event.cleared is True
    assert event.url == "https://example.com/report"


# ---------------------------------------------------------------------------
# S5.22 outbound scrubbing
# ---------------------------------------------------------------------------


def test_scrubber_replaces_then_commits():
    scrubber = ca.OutboundScrubber({"C:/secret/patient": "<PATH_1>"})
    prepared = scrubber.prepare("open C:/secret/patient/img.tif")
    assert prepared.text == "open <PATH_1>/img.tif"
    assert prepared.replacements == 1
    scrubber.commit()
    assert scrubber.replacements == 1
    assert "Pseudonymised 1 sensitive substring(s) before send." in scrubber.log


def test_raw_override_applies_to_one_send_only():
    scrubber = ca.OutboundScrubber({"secret": "<TOKEN>"})
    scrubber.request_raw_override()
    prepared = scrubber.prepare("keep secret")
    assert prepared.raw_override and prepared.text == "keep secret"
    scrubber.commit()
    assert scrubber.prepare("keep secret").text == "keep <TOKEN>"


def test_rollback_keeps_the_override_armed():
    scrubber = ca.OutboundScrubber({"secret": "<TOKEN>"})
    scrubber.request_raw_override()
    scrubber.prepare("secret")
    scrubber.rollback()
    assert scrubber.prepare("secret").raw_override is True


def test_oversized_write_is_refused():
    scrubber = ca.OutboundScrubber()
    with pytest.raises(ValueError):
        scrubber.prepare("x" * (ca.MAX_WRITE_BYTES + 1))


# ---------------------------------------------------------------------------
# S5.35 transcript
# ---------------------------------------------------------------------------


def test_transcript_is_off_by_default(tmp_path):
    assert ca.persist_scrollback("hello", tmp_path, "claude", enabled=False) is None


def test_transcript_lands_under_ai_exports(tmp_path):
    stamp = datetime(2024, 5, 6, 7, 8, 9)
    path = ca.persist_scrollback(
        "line1\nline2", tmp_path, ca.find_agent("claude"), enabled=True, now=stamp
    )
    assert path == tmp_path / "AI_Exports" / ".session" / "log" / "claude_20240506_070809.log"
    assert path.read_text(encoding="utf-8") == "line1\nline2"


def test_transcript_is_trimmed_to_the_line_limit(tmp_path):
    text = "\n".join(str(index) for index in range(ca.SCROLLBACK_LINES + 50))
    path = ca.persist_scrollback("x\n" + text, tmp_path, "claude", enabled=True)
    assert len(path.read_text(encoding="utf-8").splitlines()) == ca.SCROLLBACK_LINES
