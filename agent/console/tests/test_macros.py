"""Macro list, journal, script-editor, and recipe tests (S2.8-S2.13, S2.16-S2.18).

Everything happens under tmp_path; no ImageJ root, no Fiji, no network.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[3]
if str(REPO_ROOT) not in sys.path:
    sys.path.insert(0, str(REPO_ROOT))

from agent.console import macros


@pytest.fixture()
def imagej_root(tmp_path):
    """A miniature Fiji tree: user macros plus an ImageJAI saved folder."""
    root = tmp_path / "Fiji.app"
    (root / "macros" / "tools").mkdir(parents=True)
    (root / "macros" / "count.ijm").write_text("// count cells\n", encoding="utf-8")
    (root / "macros" / "tools" / "zoom.ijm").write_text("run('Zoom');\n", encoding="utf-8")
    (root / "macros" / "notes.txt").write_text("ignored\n", encoding="utf-8")
    saved = root / "ImageJAI" / "macros"
    saved.mkdir(parents=True)
    (saved / "saved.ijm").write_text("run('Measure');\n", encoding="utf-8")
    (saved / "helper.groovy").write_text("def x = 1\n", encoding="utf-8")
    return root


# ------------------------------------------------------- languages and names


@pytest.mark.parametrize(
    "language,extension",
    [
        ("groovy", "groovy"),
        ("jython", "py"),
        ("python", "py"),
        ("javascript", "js"),
        ("beanshell", "bsh"),
        ("clojure", "clj"),
        ("ruby", "rb"),
        ("ijm", "ijm"),
        (None, "ijm"),
    ],
)
def test_extension_for_language(language, extension):
    assert macros.extension_for_language(language) == extension


def test_language_for_path():
    assert macros.language_for_path("a.py") == "jython"
    assert macros.language_for_path("a.ijm") == "ijm"
    assert macros.language_for_path("a.unknown") == "ijm"


def test_is_imagej_macro():
    assert macros.is_imagej_macro("ijm")
    assert macros.is_imagej_macro("")
    assert not macros.is_imagej_macro("groovy")


@pytest.mark.parametrize(
    "raw,expected",
    [
        ('  bad:name*here?  ', "bad_name_here"),
        ("two words", "two_words"),
        ("..hidden", "hidden"),
        ("__lead__", "lead"),
        ("", ""),
    ],
)
def test_sanitize_file_name(raw, expected):
    assert macros.sanitize_file_name(raw) == expected


def test_default_file_name_adds_the_language_extension():
    item = macros.MacroItem(macros.MacroSource.SESSION, "count cells", language="groovy")
    assert macros.default_file_name(item) == "count_cells.groovy"
    keeps = macros.MacroItem(macros.MacroSource.SESSION, "x.ijm", language="ijm")
    assert macros.default_file_name(keeps) == "x.ijm"


# ------------------------------------------------------------ the three lists


def test_list_user_macros_is_ijm_only_and_skips_the_imagejai_folder(imagej_root):
    items = macros.list_user_macros(imagej_root)
    names = [item.name for item in items]
    assert names == ["count.ijm", "zoom.ijm"]
    assert all(item.source == macros.MacroSource.USER for item in items)
    assert "saved.ijm" not in names


def test_user_macro_detail_is_a_forward_slash_relative_path(imagej_root):
    zoom = next(i for i in macros.list_user_macros(imagej_root) if i.name == "zoom.ijm")
    assert zoom.detail == "macros/tools/zoom.ijm"


def test_list_saved_macros_covers_every_supported_extension(imagej_root, tmp_path):
    workspace = tmp_path / "agent"
    (workspace / "macro_sets").mkdir(parents=True)
    (workspace / "macro_sets" / "set.py").write_text("print(1)\n", encoding="utf-8")
    items = macros.list_saved_macros(imagej_root, workspace, home=tmp_path / "home")
    names = sorted(item.name for item in items)
    assert names == ["helper.groovy", "saved.ijm", "set.py"]


def test_saved_macros_are_deduplicated_by_absolute_path(imagej_root, tmp_path):
    items = macros.list_saved_macros(imagej_root, imagej_root / "ImageJAI", home=tmp_path)
    assert len([i for i in items if i.name == "saved.ijm"]) == 1


def test_list_all_macros_order_is_session_saved_user(imagej_root, tmp_path):
    journal = macros.SessionCodeJournal()
    journal.record("run('Gaussian Blur...', 'sigma=2'); // long enough", "ijm")
    items = macros.list_all_macros(journal, imagej_root, home=tmp_path)
    sources = [item.source for item in items]
    assert sources[0] == macros.MacroSource.SESSION
    assert macros.MacroSource.SAVED in sources
    assert sources[-1] == macros.MacroSource.USER


def test_popup_model_empty_states(tmp_path):
    flat = macros.macro_popup_model([], empty_text=macros.EMPTY_MY_MACROS)
    assert flat["empty"] == "No .ijm files found in the ImageJ folder"
    assert flat["groups"] == []


def test_popup_model_groups_use_the_java_captions(imagej_root, tmp_path):
    journal = macros.SessionCodeJournal()
    journal.record("run('Subtract Background...', 'rolling=50');", "ijm")
    items = macros.list_all_macros(journal, imagej_root, home=tmp_path)
    model = macros.macro_popup_model(items, grouped=True)
    titles = [group["title"] for group in model["groups"]]
    assert titles == ["Session Macros", "Saved ImageJAI Macros", "My Macros"]
    assert model["groups"][1]["separator_before"] is True


def test_popup_action_status_lines(imagej_root, tmp_path):
    found = macros.popup_action("my", None, imagej_root=imagej_root)
    assert found.status == "2 macros found"
    empty = macros.popup_action("session", None, journal=macros.SessionCodeJournal())
    assert not empty.ok and empty.status == "No session macros yet"
    with pytest.raises(KeyError):
        macros.popup_action("nope", None)


# -------------------------------------------------------------- run payloads


def test_run_payload_tags_the_rail_source(imagej_root):
    item = next(i for i in macros.list_user_macros(imagej_root) if i.name == "count.ijm")
    payload = macros.run_payload(item)
    assert payload["command"] == "execute_macro"
    assert payload["source"] == "rail:my-macros"
    assert payload["code"] == "// count cells\n"


def test_run_payload_uses_run_script_for_other_languages(imagej_root, tmp_path):
    item = next(
        i
        for i in macros.list_saved_macros(imagej_root, home=tmp_path)
        if i.name == "helper.groovy"
    )
    payload = macros.run_payload(item)
    assert payload["command"] == "run_script"
    assert payload["language"] == "groovy"
    assert payload["source"] == "rail:saved-macros"


def test_menu_text_and_tooltip(imagej_root):
    item = next(i for i in macros.list_user_macros(imagej_root) if i.name == "count.ijm")
    assert item.menu_text() == "count.ijm (macros/count.ijm)"
    assert item.menu_text(include_source=True).startswith("My Macros: ")
    assert item.tooltip().endswith("count.ijm")

    session = macros.MacroItem(macros.MacroSource.SESSION, "x", detail="x", language="ijm")
    assert session.menu_text() == "x"
    assert session.tooltip() == "ijm session entry"


# ------------------------------------------------------------ S2.9 context menu


def test_context_menu_labels_depend_on_having_a_file(imagej_root, tmp_path):
    file_item = macros.list_user_macros(imagej_root)[0]
    assert [row["label"] for row in macros.macro_context_menu(file_item)] == [
        "Open in Script Editor",
        "Open containing folder",
    ]
    session = macros.MacroItem(macros.MacroSource.SESSION, "s", code="x")
    assert macros.macro_context_menu(session)[1]["label"] == "Open macros folder"
    assert macros.context_menu_folder(session, imagej_root) == (
        imagej_root / "ImageJAI" / "macros"
    )


def test_open_context_folder_reveals_file_or_creates_session_folder(imagej_root, tmp_path):
    opened = []
    item = macros.list_user_macros(imagej_root)[0]
    assert macros.open_context_folder(item, opener=opened.append) == item.path.parent
    assert opened[-1] == item.path.parent

    session = macros.MacroItem(macros.MacroSource.SESSION, "new", code="run('X');")
    root = tmp_path / "empty-fiji"
    folder = macros.open_context_folder(session, root, opener=opened.append)
    assert folder == root / "ImageJAI" / "macros"
    assert folder.is_dir()
    assert opened[-1] == folder


def test_config_directory_can_be_passed_without_double_imagejai_suffix(tmp_path):
    config_dir = tmp_path / ".imagej-ai"
    assert macros.saved_macro_directory(home=config_dir) == config_dir / "macros"
    assert macros.legacy_macro_directory(home=config_dir) == config_dir / "learned_macros"


# ------------------------------------------------------------- S2.11 save macro


def test_saved_macro_path_and_write(imagej_root, tmp_path):
    item = macros.MacroItem(
        macros.MacroSource.SESSION, "count cells", language="ijm", code="run('Measure');"
    )
    dest = macros.saved_macro_path(item, "count cells", imagej_root)
    assert dest == imagej_root / "ImageJAI" / "macros" / "count_cells.ijm"
    macros.write_macro_item(item, dest)
    assert dest.read_text(encoding="utf-8") == "run('Measure');"


def test_saved_macro_path_falls_back_to_the_home_folder(tmp_path):
    item = macros.MacroItem(macros.MacroSource.SESSION, "a", code="x")
    dest = macros.saved_macro_path(item, "a", None, home=tmp_path)
    assert dest == tmp_path / ".imagej-ai" / "macros" / "a.ijm"


def test_saved_macro_path_rejects_an_empty_name(imagej_root):
    with pytest.raises(ValueError, match="Enter a macro name"):
        macros.saved_macro_path(None, "   ", imagej_root)


def test_write_macro_item_refuses_to_overwrite_silently(tmp_path):
    item = macros.MacroItem(macros.MacroSource.SESSION, "a", code="body")
    dest = tmp_path / "a.ijm"
    macros.write_macro_item(item, dest)
    with pytest.raises(FileExistsError, match="Overwrite a.ijm"):
        macros.write_macro_item(item, dest)
    macros.write_macro_item(
        macros.MacroItem(macros.MacroSource.SESSION, "a", code="new"), dest, overwrite=True
    )
    assert dest.read_text(encoding="utf-8") == "new"


def test_write_macro_item_rejects_empty_code(tmp_path):
    empty = macros.MacroItem(macros.MacroSource.SESSION, "a", code="   ")
    with pytest.raises(ValueError, match="Selected macro is empty"):
        macros.write_macro_item(empty, tmp_path / "a.ijm")


# ------------------------------------------------------- S2.10 script editor


def test_script_editor_payload_opens_an_existing_file(imagej_root):
    item = macros.list_user_macros(imagej_root)[0]
    payload = macros.script_editor_payload(item)
    assert payload["command"] == "run_script"
    assert payload["language"] == "groovy"
    assert macros.SCRIPT_EDITOR_CLASS in payload["code"]
    assert "SwingUtilities.invokeAndWait" in payload["code"]
    assert "Class.forName(name, true, pluginLoader)" in payload["code"]
    assert 'getMethod("open", java.io.File.class)' in payload["code"]
    assert "count.ijm" in payload["code"]


def test_script_editor_payload_creates_a_document_for_session_code():
    item = macros.MacroItem(
        macros.MacroSource.SESSION, "blur", language="ijm", code='run("Blur");'
    )
    code = macros.script_editor_payload(item)["code"]
    assert 'createNewDocument' in code
    assert '\\"Blur\\"' in code
    assert '"blur.ijm"' in code
    assert 'getMethod("setFileName", String.class)' in code
    assert '.invoke(pane, "blur.ijm")' in code
    assert 'SwingUtilities.invokeLater' in code
    assert '.invoke(editor, "*blur.ijm")' in code


def test_script_editor_failure_messages():
    assert macros.script_editor_failure(macros.SCRIPT_EDITOR_MISSING) == (
        "Fiji's Script Editor is not installed"
    )
    assert macros.script_editor_failure("boom") == (
        "Could not open Fiji's Script Editor: boom"
    )
    assert macros.script_editor_failure("") == (
        "Could not open Fiji's Script Editor: unknown error"
    )
    assert macros.script_editor_failure(
        "Java error: Fiji's Script Editor is not installed"
    ) == "Fiji's Script Editor is not installed"


# -------------------------------------------------------------- auto-naming


def test_name_from_leading_comment():
    assert macros.name_for("ijm", "// Spine density pass\nrun('X');", "120000") == (
        "spine_density_pass"
    )


def test_boilerplate_comment_is_ignored():
    name = macros.name_for("ijm", "// auto-generated\nrun(\"Measure\");", "120000")
    assert name == "measure"


def test_name_from_up_to_three_run_calls():
    code = (
        'run("Gaussian Blur...");\nrun("Close All");\n'
        'run("Threshold...");\nrun("Analyze Particles...");\nrun("Measure");'
    )
    assert macros.name_for("ijm", code, "120000") == (
        "gaussian_blur__threshold__analyze_particles"
    )


def test_plumbing_only_code_is_flagged():
    name, plumbing = macros.describe_for("ijm", 'run("Close All");', "120000")
    assert name == "close_all" and plumbing is True


def test_name_falls_back_to_the_timestamp():
    assert macros.name_for("ijm", "1 + 1;", "143215") == "macro_143215"
    assert macros.name_for("groovy", "1 + 1;", "143215") == "script_143215"


def test_slug_is_truncated_at_a_word_boundary():
    slug = macros.slugify("a" * 30 + " " + "b" * 30)
    assert len(slug) <= macros.MAX_SLUG_LEN


# ---------------------------------------------------- S2.16-S2.18 journal


def test_journal_records_short_code_and_skips_empty_code():
    journal = macros.SessionCodeJournal()
    code = 'run("Blobs");'
    assert journal.record(code, "ijm").code == code
    assert journal.record(" \r\n\t", "ijm") is None
    assert journal.record("", "ijm") is None
    assert journal.record(None, "ijm") is None
    assert [entry.code for entry in journal.snapshot()] == [code]


@pytest.mark.parametrize("tool", ["execute_macro", "execute_macro_async", "run_macro", "run_macro_async", "run_script"])
def test_journal_records_executed_tool_source_and_failures(tool):
    journal = macros.SessionCodeJournal()
    language = "groovy" if tool == "run_script" else "ijm"
    code = "print(1)" if tool == "run_script" else 'run("Blobs");'
    args = {"code": code, "language": language}
    entry = journal.record_tool_run(tool, args, True, "ok")
    assert entry.code == code and entry.language == language and entry.success
    again = journal.record_tool_run(tool, args, False, "macro error")
    assert again is entry and again.run_count == 2
    assert again.success is False and again.failure_message == "macro error"


def test_journal_skips_refused_calls_editor_previews_and_unrelated_tools():
    journal = macros.SessionCodeJournal()
    args = {"code": 'run("Blobs");'}
    assert journal.record_tool_run("run_macro", args, False, "Refused: approval required") is None
    assert journal.record_tool_run("run_script", args, False, "  Refused: approval required") is None
    assert journal.record_tool_run("execute_macro", {**args, "source": "rail:script-editor"}, True, "opened editor") is None
    assert journal.record_tool_run("get_state", args, True, "state") is None
    assert journal.record_tool_run("run_macro", {"code": None}, True, "ok") is None
    assert journal.snapshot() == []


@pytest.mark.parametrize("tool,field", [("Shell", "command"), ("Bash", "command"), ("exec_command", "cmd")])
def test_journal_recovers_literal_macro_from_vendor_shell(tool, field):
    journal = macros.SessionCodeJournal()
    command = '@\'\nrun("Blobs");\n\'@ | py -3 ij.py macro --stdin'
    entry = journal.record_tool_run(tool, {field: command}, True, "ok")
    assert entry.code == 'run("Blobs");\n'
    assert entry.language == "ijm"
    assert journal.record_tool_run(tool, {field: 'echo \'run("Blobs");\''}, True, "ok") is None
    assert journal.record_tool_run(tool, {field: command + "\npython ij.py state"}, True, "ok") is None
    assert journal.record_tool_run(tool, {field: "python ij.py macro --file analysis.ijm"}, True, "ok") is None


def test_journal_restores_only_completed_runs_with_original_times():
    journal = macros.SessionCodeJournal()
    code = 'run("Blobs");'
    def call(cid, tool="run_macro", args=None):
        return {"type": "tool_call", "payload": {
            "correlation_id": cid, "tool": tool,
            "arguments": args if args is not None else {"code": code},
        }}
    def result(cid, ok=True, text="ok", at="1970-01-01T00:01:40Z"):
        return {"type": "tool_result", "timestamp": at, "payload": {
            "correlation_id": cid, "ok": ok, "result": text,
        }}
    journal.restore_tool_runs([
        call("one"), result("one"),
        call("two"), result("two", False, "Macro failed", "1970-01-01T00:03:20Z"),
        result("two"),  # duplicate result is not another run
        call("never-finished"),
        call("refused"), result("refused", False, "Refused: approval required"),
        call("editor", "execute_macro", {"code": code, "source": "rail:script-editor"}), result("editor"),
        result("unknown"), {"type": "tool_result", "payload": None},
    ])
    entry, = journal.snapshot()
    assert entry.code == code and entry.run_count == 2
    assert entry.started_at == 100 and entry.last_run_at == 200
    assert entry.success is False and entry.failure_message == "Macro failed"


def test_journal_dedups_and_promotes():
    clock = iter([100.0, 200.0, 300.0])
    journal = macros.SessionCodeJournal(clock=lambda: next(clock))
    first = journal.record('run("Gaussian Blur...", "sigma=2");', "ijm")
    journal.record('run("Subtract Background...", "rolling=50");', "ijm")
    again = journal.record('run("Gaussian Blur...",   "sigma=2");  ', "ijm")
    assert again is first
    assert again.run_count == 2
    assert journal.snapshot()[0] is first


def test_journal_ring_is_capped():
    journal = macros.SessionCodeJournal()
    for i in range(macros.HISTORY_RING_CAP + 25):
        journal.record(f'run("Plugin {i}", "arg=long enough to record");', "ijm")
    assert len(journal.snapshot()) == macros.HISTORY_RING_CAP


def test_journal_row_text_and_failure_glyph():
    journal = macros.SessionCodeJournal(clock=lambda: 0.0)
    entry = journal.record(
        'run("Analyze Particles...", "size=10");\nrun("Measure");', "ijm"
    )
    row = entry.row_text(localtime=lambda _t: __import__("time").gmtime(0))
    assert row.startswith("00:00:00  ")
    assert row.endswith("  x1  2L")

    entry.success = False
    entry.failure_message = "macro error"
    assert entry.row_text(localtime=lambda _t: __import__("time").gmtime(0)).startswith(
        "\u26a0 "
    )
    assert entry.tooltip() == "macro error"


def test_journal_plumbing_filter():
    journal = macros.SessionCodeJournal()
    journal.record('run("Close All"); // housekeeping only', "ijm")
    journal.record('run("Analyze Particles...", "size=10-Infinity");', "ijm")
    assert len(journal.snapshot()) == 2
    kept = journal.snapshot(exclude_plumbing=True)
    assert len(kept) == 1
    assert "analyze" in kept[0].name


def test_journal_remove_and_clear():
    journal = macros.SessionCodeJournal()
    entry = journal.record('run("Measure All The Things", "arg=1");', "ijm")
    assert journal.find(entry.id) is entry
    assert journal.remove_from_ring(entry.id)
    assert not journal.remove_from_ring(entry.id)
    journal.record('run("Measure All The Things", "arg=1");', "ijm")
    journal.clear_ring()
    assert journal.snapshot() == []


def test_journal_index_roundtrip(tmp_path):
    journal = macros.SessionCodeJournal(clock=lambda: 1000.0)
    journal.record('run("Analyze Particles...", "size=10-Infinity");', "ijm")
    index = journal.save_index(tmp_path)
    assert index.name == "INDEX.json"
    assert json.loads(index.read_text(encoding="utf-8"))[0]["runCount"] == 1

    restored = macros.SessionCodeJournal()
    assert restored.load_from_index_if_present(tmp_path) == 1
    assert restored.snapshot()[0].name == journal.snapshot()[0].name

    journal.clear_ring_and_delete_files(tmp_path)
    assert not index.exists()
    assert macros.SessionCodeJournal().load_from_index_if_present(tmp_path) == 0


def test_corrupt_index_is_ignored(tmp_path):
    (tmp_path / "INDEX.json").write_text("{not json", encoding="utf-8")
    assert macros.SessionCodeJournal().load_from_index_if_present(tmp_path) == 0


def test_session_macro_detail_shows_the_run_count():
    journal = macros.SessionCodeJournal()
    journal.record('run("Gaussian Blur...", "sigma=2");', "groovy")
    journal.record('run("Gaussian Blur...", "sigma=2");', "groovy")
    item = macros.list_session_macros(journal)[0]
    assert item.detail == "groovy x2"
    assert item.code.startswith("run(")


# -------------------------------------------------------------- S2.12 recipes


@pytest.fixture()
def recipe_dirs(tmp_path):
    user = tmp_path / "Fiji.app" / "ImageJAI" / "recipes"
    bundled = tmp_path / "agent" / "recipes"
    user.mkdir(parents=True)
    bundled.mkdir(parents=True)
    (user / "zebra.yaml").write_text("name: Zebra\n", encoding="utf-8")
    (bundled / "alpha.yml").write_text(
        "name: Alpha\nid: alpha\ndescription: counts\nparameters:\n  - name: sigma\n",
        encoding="utf-8",
    )
    (bundled / "README.md").write_text("not a recipe\n", encoding="utf-8")
    (tmp_path / "agent" / "run_recipe.py").write_text("#\n", encoding="utf-8")
    return user, bundled


def test_list_recipes_saved_first_then_bundled(recipe_dirs):
    user, bundled = recipe_dirs
    items = macros.list_recipes(user, bundled)
    assert [(i.name, i.source) for i in items] == [
        ("zebra", "Saved"),
        ("alpha", "Bundled"),
    ]


def test_recipe_popup_model_groups_and_empty_state(recipe_dirs):
    user, bundled = recipe_dirs
    model = macros.recipe_popup_model(macros.list_recipes(user, bundled))
    assert [group["title"] for group in model["groups"]] == ["Saved", "Bundled"]
    assert macros.recipe_popup_model([])["empty"] == "No recipes found"


def test_recipe_run_command_environment(recipe_dirs, tmp_path):
    user, bundled = recipe_dirs
    recipe = macros.list_recipes(user, bundled)[1]
    plan = macros.recipe_run_command(
        recipe, tmp_path, port=7777, user_recipes_dir=user, python="python3.11"
    )
    assert plan["argv"][:3] == ["python3.11", "-u", "run_recipe.py"]
    assert plan["argv"][3].endswith("alpha.yml")
    assert plan["cwd"] == str((tmp_path / "agent").resolve())
    assert plan["env"]["IMAGEJAI_TCP_PORT"] == "7777"
    assert plan["env"]["IMAGEJAI_USER_RECIPES_DIR"] == str(user.resolve())
    assert str(user.resolve()) in plan["env"]["IMAGEJAI_RECIPE_DIRS"]


def test_python_command_prefers_the_environment_override():
    assert macros.python_command({"IMAGEJAI_PYTHON": " py -3 "}) == "py -3"
    assert macros.python_command({}) in ("python", "python3")


def test_load_recipe_and_summary(recipe_dirs):
    _user, bundled = recipe_dirs
    summary = macros.recipe_summary(bundled / "alpha.yml")
    assert summary["name"] == "Alpha"
    assert summary["parameters"] == ["sigma"]

    broken = bundled / "broken.yaml"
    broken.write_text("- just\n- a list\n", encoding="utf-8")
    assert "_error" in macros.load_recipe(broken)


def test_bundled_repo_recipes_all_parse():
    """The shipped recipes must stay loadable — the rail lists them by name."""
    folder = REPO_ROOT / "agent" / "recipes"
    items = macros.list_recipes(None, folder)
    assert len(items) > 5
    for item in items[:5]:
        assert "_error" not in macros.load_recipe(item.path)
