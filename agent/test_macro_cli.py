"""Macro source must survive shell transport without changing safety checks."""
from __future__ import annotations

import io
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

from agent import ij
from agent.console import subscriptions


SOURCE = 'run("Blobs");\nprint("quotes stay intact");\n'


@pytest.mark.parametrize("command", ["macro", "async", "run_patient"])
@pytest.mark.parametrize("mode", ["stdin", "file", "inline"])
def test_macro_source_reaches_each_dispatcher(command, mode, monkeypatch, tmp_path, capsys):
    args = ["ij.py", command]
    if mode == "stdin":
        monkeypatch.setattr(sys, "stdin", io.StringIO(SOURCE))
        args += ["--stdin"]
    elif mode == "file":
        path = tmp_path / "macro with spaces.ijm"
        path.write_text(SOURCE, encoding="utf-8-sig")
        args += ["--file", str(path)]
    else:
        args += [SOURCE]
    if command == "run_patient":
        args += ["--timeout", "5"]
    monkeypatch.setattr(sys, "argv", args)
    sent = []

    def execute(code):
        sent.append(code)
        return {"ok": True, "result": {"job_id": "job-1"}}

    monkeypatch.setattr(ij, "execute_macro", execute)
    monkeypatch.setattr(ij, "submit_async", execute)
    waited = []
    monkeypatch.setattr(ij, "wait_for_job", lambda job_id, **kw:
                        (waited.append((job_id, kw)) or {"ok": True}))
    ij.main()
    assert sent == [SOURCE]
    assert len(waited) == (1 if command == "run_patient" else 0)
    if waited:
        assert waited[0] == ("job-1", {"timeout": 5.0, "reconnect": True})


@pytest.mark.parametrize("args,stdin", [
    ([], ""), (["--stdin"], " \n"), (["--stdin", "extra"], SOURCE),
    (["--file"], ""), (["--file", "a", "b"], ""), (["--unknown"], ""),
])
def test_bad_source_options_fail_before_sending(args, stdin, monkeypatch, capsys):
    monkeypatch.setattr(sys, "argv", ["ij.py", "macro", *args])
    monkeypatch.setattr(sys, "stdin", io.StringIO(stdin))
    sent = []
    monkeypatch.setattr(ij, "execute_macro", sent.append)
    with pytest.raises(SystemExit) as stopped:
        ij.main()
    assert stopped.value.code == 1
    assert sent == []
    assert "ERROR:" in capsys.readouterr().out


@pytest.mark.parametrize("command", ["macro", "async", "run_patient"])
def test_macro_help_does_not_execute(command, monkeypatch, capsys):
    monkeypatch.setattr(sys, "argv", ["ij.py", command, "--help"])
    sent = []
    monkeypatch.setattr(ij, "execute_macro", sent.append)
    monkeypatch.setattr(ij, "submit_async", sent.append)
    ij.main()
    assert sent == []
    assert "--stdin" in capsys.readouterr().out


@pytest.mark.parametrize("nested", [False, True])
def test_quote_loss_hint_preserves_refusal_and_never_rewrites_source(nested, monkeypatch, capsys):
    damaged = 'run(Blobs);'
    error = "Macro blocked by safe-mode scanner: host_code_execution @ line 1"
    refusal = ({"ok": True, "result": {"success": False, "error": error}}
               if nested else {"ok": False, "error": error})
    monkeypatch.setattr(sys, "argv", ["ij.py", "macro", damaged])
    sent = []
    monkeypatch.setattr(ij, "execute_macro", lambda code: (sent.append(code) or refusal))
    with pytest.raises(SystemExit) as stopped:
        ij.main()
    assert stopped.value.code == 1
    reply = json.loads(capsys.readouterr().out)
    assert sent == [damaged]
    assert {k: v for k, v in reply.items() if k != "client_hint"} == refusal
    assert "--stdin" in reply["client_hint"]
    assert "client_hint" not in refusal
    assert ij._macro_cli_reply(SOURCE, refusal) == refusal
    assert ij._macro_cli_reply(damaged, {"ok": True}) == {"ok": True}


def test_vendor_clients_send_macros_as_json_actions_not_shell(monkeypatch, tmp_path):
    # Wrapped subscription agents never run ij.py through a shell, so Windows
    # argument quoting cannot strip macro quotes; the macro travels as JSON.
    monkeypatch.setattr(subscriptions, "_executable", lambda provider: provider)
    monkeypatch.setattr(subscriptions, "subscription_status", lambda _: (True, "ready"))
    monkeypatch.setattr(subscriptions, "ensure_importable", lambda: tmp_path)
    for provider in ("codex-subscription", "claude-subscription"):
        agent = subscriptions.SubscriptionAgent(provider, external_session_id="existing-session")
        prompts = []

        def run(command, workspace, stdin=None, **kw):
            prompts.append(stdin)  # both clients receive the prompt on stdin
            return 0, '{"result":"done"}', ""

        monkeypatch.setattr(agent, "_run", run)
        if provider == "codex-subscription":
            agent._codex_turn("open blobs")
        else:
            agent._claude_turn("open blobs")
        assert len(prompts) == 1
        assert "Do not call ij.py, run Fiji commands through a shell" in prompts[0]
        assert '"code": "run(\\"Blobs\\");"' in prompts[0]
        # No contradictory shell advice alongside the action-only rule.
        assert "macro --stdin" not in prompts[0]
        assert prompts[0].endswith("Conversation input:\nopen blobs")
        assert agent.messages == []


@pytest.mark.skipif(os.name != "nt", reason="Windows native argument quoting regression")
def test_real_powershell_pipe_preserves_quoted_multiline_macro(tmp_path):
    shell = shutil.which("powershell")
    if not shell:
        pytest.skip("PowerShell is unavailable")
    driver = tmp_path / "macro helper.py"
    driver.write_text(
        "import sys\n"
        f"sys.path.insert(0, {str(Path(__file__).resolve().parent)!r})\n"
        "import ij\n"
        "ij.execute_macro = lambda code: {'ok': True, 'result': {'code': code}}\n"
        "ij.main()\n", encoding="utf-8")
    quote = lambda text: "'" + str(text).replace("'", "''") + "'"
    command = "@'\n" + SOURCE + "'@ | & " + quote(sys.executable) + " " + quote(driver) + " macro --stdin"
    result = subprocess.run(
        [shell, "-NoProfile", "-NonInteractive", "-Command", command],
        capture_output=True, text=True, timeout=15,
    )
    assert result.returncode == 0, result.stderr
    assert json.loads(result.stdout)["result"]["code"] == SOURCE
