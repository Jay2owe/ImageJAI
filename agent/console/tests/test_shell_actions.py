"""Fiji shorthand is displayed as Fiji actions without running shell source."""
import pytest
import json

from agent.console import activity
from agent.console.shell_actions import fiji_actions


SCREENSHOT_COMMAND = r'''"C:\\WINDOWS\\System32\\WindowsPowerShell\\v1.0\\powershell.exe" -Command "@'
run(\"Blobs\");
'@ | py -3 ij.py macro --stdin | ConvertFrom-Json | ForEach-Object { [pscustomobject]@{ok="'$_.ok; success=$_.result.success; error=$_.result.error; newImages=($_.result.stateDelta.newImages -join '"', ')} } | Format-List"'''


@pytest.mark.parametrize("command,name,args", [
    ("python ij.py state", "get_state", {}),
    ("python ij.py info", "get_image_info", {}),
    ("python3 agent/ij.py histogram", "get_histogram", {}),
    ("py -3 ij.py results", "get_results", {}),
    ("python -m agent.ij dialogs", "get_dialogs", {}),
    ('& "C:\\Python\\python.exe" "C:\\agent workspace\\ij.py" open "C:\\data\\cells 1.tif"',
     "open_image", {"path": r"C:\data\cells 1.tif"}),
    ('python ij.py macro \'run("Blobs");\'', "run_macro", {"code": 'run("Blobs");'}),
    ("python ij.py macro --stdin", "run_macro", {"source": "stdin"}),
    ('python ij.py macro --file ".tmp/my macro.ijm"', "run_macro", {"file": ".tmp/my macro.ijm"}),
    ("python ij.py async --file work.ijm", "run_macro_async", {"file": "work.ijm"}),
    ("python ij.py run_patient --file work.ijm --timeout=30", "run_macro_async", {"file": "work.ijm", "timeout": "30"}),
    ('python ij.py probe "Gaussian Blur..."', "probe_plugin", {"command": "Gaussian Blur..."}),
    ("python ij.py script --lang groovy --file work.groovy", "run_script", {"language": "groovy", "file": "work.groovy"}),
    ("python ij.py capture after_blobs", "capture_image", {"name": "after_blobs"}),
    ("python ij.py ui click OK", "interact_dialog", {"action": "click", "arguments": ["OK"]}),
    ("python ij.py display", "get_display_state", {}),
    ("python ij.py rois", "get_roi_state", {}),
    ("python ij.py friction clear", "clear_friction_log", {"arguments": ["clear"]}),
    ("python ij.py macro --help", "ImageJAI help", {}),
    ('''python ij.py raw '{"command":"execute_macro","code":"run(\\"Blobs\\");"}' ''',
     "execute_macro", {"code": 'run("Blobs");'}),
    ('powershell -NoProfile -Command "python ij.py state"', "get_state", {}),
    ("bash -lc 'python ij.py histogram'", "get_histogram", {}),
])
def test_shorthand_resolves_to_submitted_action(command, name, args):
    actions = fiji_actions(command)
    assert len(actions) == 1
    assert (actions[0].name, actions[0].args) == (name, args)


@pytest.mark.parametrize("command", [
    "@'\nrun(\"Blobs\");\nprint(\"[red]literal[/red]\");\n'@ | python ij.py macro --stdin",
    'python ij.py macro --stdin <<\'IJM\'\nrun("Blobs");\nprint("[red]literal[/red]");\nIJM\n',
])
def test_stdin_macro_is_shown_verbatim(command):
    args = {"command": command}
    assert activity.tool_key("Shell", args) == "run_macro"
    assert activity.tool_activity("Shell", args) == activity.RUNNING
    shown = activity.tool_start_text("Shell", args).plain
    assert "run_macro(" in shown
    assert 'run("Blobs");\n' in shown and 'print("[red]literal[/red]");' in shown
    assert "Shell(" not in shown and "ij.py" not in shown and "--stdin" not in shown
    assert activity.tool_result_text("Shell", False, "Macro refused", args).plain == "  ✗ run_macro: Macro refused"
    assert args == {"command": command}


def test_screenshot_powershell_wrapper_displays_only_the_macro():
    args = {"command": SCREENSHOT_COMMAND}
    actions = fiji_actions(SCREENSHOT_COMMAND)
    assert len(actions) == 1
    assert (actions[0].name, actions[0].args) == ("run_macro", {"code": 'run("Blobs");\n'})
    assert activity.tool_activity("Shell", args) == activity.RUNNING
    text = activity.tool_start_text("Shell", args).plain
    assert 'run_macro(' in text and 'run("Blobs");' in text
    assert all(name not in text for name in ("Shell(", "powershell.exe", "ConvertFrom-Json", "ForEach-Object", "Format-List"))
    assert activity.tool_result_text("Shell", True, "Opened", args).plain == "  → run_macro: Macro completed"


@pytest.mark.parametrize("stage", [
    "Out-File private.txt", "Remove-Item private.txt",
    "ForEach-Object { Remove-Item private.txt }",
    "ForEach-Object { [pscustomobject]@{x=(curl)} }",
    "ForEach-Object { [pscustomobject]@{x=$_.Remove()} }",
    'ForEach-Object { [pscustomobject]@{x="$(Remove-Item private.txt)"} }',
    "ForEach-Object { [pscustomobject]@{x=(Remove-Item private.txt)} }",
])
def test_non_formatting_pipeline_steps_stay_visible(stage):
    actions = fiji_actions("python ij.py state | " + stage)
    assert [action.name for action in actions] == ["get_state", "Shell"]
    assert stage.split()[0] in actions[1].args["command"]


@pytest.mark.parametrize("provider", ["codex-subscription", "claude-subscription"])
def test_screenshot_wrapper_reaches_visible_callbacks_as_macro(provider):
    from agent.console.vendor_events import VendorEvents
    from agent.console.agent_loop import TurnCallbacks
    starts, records = [], []
    stream = VendorEvents(provider, TurnCallbacks(
        on_tool_start=lambda *args: starts.append(args),
        on_tool_record=lambda *args: records.append(args),
    ))
    if provider == "codex-subscription":
        item = {"id": "macro-1", "type": "command_execution", "command": SCREENSHOT_COMMAND}
        stream.feed(json.dumps({"type": "item.started", "item": item}))
        stream.feed(json.dumps({"type": "item.completed", "item": {**item, "status": "completed", "exit_code": 0, "aggregated_output": "Opened"}}))
    else:
        stream.feed(json.dumps({"type": "assistant", "message": {"content": [{"type": "tool_use", "id": "macro-1", "name": "Bash", "input": {"command": SCREENSHOT_COMMAND}}]}}))
        stream.feed(json.dumps({"type": "user", "message": {"content": [{"type": "tool_result", "tool_use_id": "macro-1", "content": "Opened"}]}}))
    assert starts == [("run_macro", {"code": 'run("Blobs");\n'})]
    assert len(records) == 1 and records[0][2] == {"command": SCREENSHOT_COMMAND}


def test_multiple_actions_preserve_order_and_other_shell_steps():
    command = 'git status; python ij.py state;\n@\'\nrun("Blobs");\n\'@ | python ij.py macro --stdin\npython ij.py histogram'
    actions = fiji_actions(command)
    assert [action.name for action in actions] == ["Shell", "get_state", "run_macro", "get_histogram"]
    assert actions[0].args == {"command": "git status"}
    shown = activity.tool_start_text("Bash", {"command": command}).plain
    assert "git status" in shown
    assert shown.index("get_state(") < shown.index("run_macro(") < shown.index("get_histogram(")
    assert activity.tool_activity("Bash", {"command": command}) == activity.RUNNING


@pytest.mark.parametrize("command", [
    'echo "python ij.py state"', 'rg "ij.py" .', 'python another.py ij.py state',
    'python ij.py macro "unterminated', 'git status', 'echo get_state',
])
def test_mentions_and_unrecognised_source_remain_shell_commands(command):
    assert fiji_actions(command) == []
    assert "Shell(" in activity.tool_start_text("Shell", {"command": command}).plain


def test_file_display_never_reads_or_runs_source(monkeypatch):
    def forbidden(*args, **kwargs):
        pytest.fail("The display parser must not open files or execute source")
    monkeypatch.setattr("builtins.open", forbidden)
    monkeypatch.setattr("builtins.eval", forbidden)
    monkeypatch.setattr("subprocess.run", forbidden)
    assert fiji_actions("python ij.py macro --file private.ijm")[0].args == {"file": "private.ijm"}


def test_result_uses_the_same_fiji_name_and_histogram_colours():
    args = {"command": "python ij.py histogram"}
    shown = activity.tool_start_text("Bash", args)
    assert "get_histogram(" in shown.plain and "▁▃█▃▁" in shown.plain
    assert len({span.style for span in shown.spans}) >= 5
    assert activity.tool_result_text("Bash", True, "256 bins", args).plain == "  → get_histogram: 256 bins"
    assert activity.tool_activity("Bash", args) == activity.INSPECTING


@pytest.mark.parametrize("provider", ["codex-subscription", "claude-subscription"])
def test_vendor_results_keep_action_identity_and_original_evidence(provider):
    from agent.console.vendor_events import VendorEvents
    from agent.console.agent_loop import TurnCallbacks
    starts, results, records = [], [], []
    stream = VendorEvents(provider, TurnCallbacks(
        on_tool_start=lambda *args: starts.append(args),
        on_tool_result=lambda *args: results.append(args),
        on_tool_record=lambda *args: records.append(args),
    ))
    calls = [("state-1", "python ij.py state"), ("hist-1", "python ij.py histogram")]
    for ident, command in calls:
        event = ({"type": "item.started", "item": {"id": ident, "type": "command_execution", "command": command}}
                 if provider == "codex-subscription" else
                 {"type": "assistant", "message": {"id": ident, "content": [{"type": "tool_use", "id": ident, "name": "Bash", "input": {"command": command}}]}})
        stream.feed(json.dumps(event))
    for ident, command in reversed(calls):
        event = ({"type": "item.completed", "item": {"id": ident, "type": "command_execution", "command": command,
                 "status": "completed", "exit_code": 0, "aggregated_output": ident}}
                 if provider == "codex-subscription" else
                 {"type": "user", "message": {"content": [{"type": "tool_result", "tool_use_id": ident, "content": ident}]}})
        stream.feed(json.dumps(event))
        stream.feed(json.dumps(event))  # duplicate completion is ignored
    assert starts == [("get_state", {}), ("get_histogram", {})]
    assert results == [("get_histogram", True, "hist-1"), ("get_state", True, "state-1")]
    assert len(records) == 2
    assert records[0] == ("hist-1", "Shell" if provider == "codex-subscription" else "Bash",
                          {"command": "python ij.py histogram"}, True, "hist-1")
