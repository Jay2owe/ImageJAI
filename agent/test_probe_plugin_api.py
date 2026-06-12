from __future__ import annotations

import importlib.util
import json
from pathlib import Path


PROBE_PLUGIN_PATH = Path(__file__).with_name("probe_plugin.py")
SPEC = importlib.util.spec_from_file_location(
    "probe_plugin_under_test", PROBE_PLUGIN_PATH
)
probe_plugin = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(probe_plugin)


def write_cached_probe(cache_dir: Path, plugin_name: str, payload: dict) -> Path:
    path = cache_dir / probe_plugin.cache_key(plugin_name)
    path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    return path


def test_public_api_exports_new_helpers_and_compatibility_names():
    expected = {
        "cache_key",
        "probe_plugin",
        "lookup_cached_probe",
        "search_cached_probes",
        "list_cached_probes",
        "format_probe_result",
        "probe_plugins",
        "probe",
        "lookup",
        "search",
        "list_cached",
        "format_result",
        "send",
    }

    assert expected.issubset(set(probe_plugin.__all__))
    for name in expected:
        assert hasattr(probe_plugin, name)

    assert probe_plugin.probe is probe_plugin.probe_plugin
    assert probe_plugin.lookup is probe_plugin.lookup_cached_probe
    assert probe_plugin.search is probe_plugin.search_cached_probes
    assert probe_plugin.list_cached is probe_plugin.list_cached_probes
    assert probe_plugin.format_result is probe_plugin.format_probe_result


def test_cache_key_keeps_existing_safe_filename_shape():
    assert probe_plugin.cache_key("Gaussian Blur...") == "Gaussian_Blur.json"
    assert probe_plugin.cache_key("Analyze Particles...") == "Analyze_Particles.json"
    assert probe_plugin.cache_key("CLIJ2-GPU Filter") == "CLIJ2-GPU_Filter.json"


def test_probe_plugin_reads_cache_without_contacting_fiji(tmp_path, monkeypatch):
    cache_dir = tmp_path / "plugin_args"
    cache_dir.mkdir()
    monkeypatch.setattr(probe_plugin, "CACHE_DIR", str(cache_dir))
    payload = {
        "plugin": "Gaussian Blur...",
        "fields": [{"label": "Radius", "unit": "\u00b5m"}],
    }
    write_cached_probe(cache_dir, "Gaussian Blur...", payload)

    def fail_probe(_plugin_name):
        raise AssertionError("probe should not run when cache is fresh")

    monkeypatch.setattr(probe_plugin, "_request_probe_command", fail_probe)

    assert probe_plugin.probe_plugin("Gaussian Blur...") == payload
    assert probe_plugin.lookup_cached_probe("Gaussian Blur...") == payload


def test_probe_plugin_fallback_sends_expected_payload_and_caches(tmp_path, monkeypatch):
    monkeypatch.setattr(probe_plugin, "CACHE_DIR", str(tmp_path))
    monkeypatch.setattr(probe_plugin, "_load_ij_probe_command", lambda: None)
    calls = []
    result = {
        "plugin": "Median...",
        "hasDialog": True,
        "fields": [{"label": "Radius", "macro_key": "radius"}],
        "macro_syntax": 'run("Median...", "radius=2");',
    }

    def fake_send(cmd):
        calls.append(cmd)
        return {"ok": True, "result": result}

    monkeypatch.setattr(probe_plugin, "send", fake_send)

    assert probe_plugin.probe_plugin("Median...") == result
    assert calls == [{"command": "probe_command", "plugin": "Median..."}]
    cache_path = tmp_path / probe_plugin.cache_key("Median...")
    assert json.loads(cache_path.read_text(encoding="utf-8")) == result

    calls.clear()
    assert probe_plugin.probe_plugin("Median...") == result
    assert calls == []

    assert probe_plugin.probe_plugin("Median...", force=True) == result
    assert calls == [{"command": "probe_command", "plugin": "Median..."}]


def test_probe_plugin_prefers_ij_probe_command_when_available(tmp_path, monkeypatch):
    monkeypatch.setattr(probe_plugin, "CACHE_DIR", str(tmp_path))
    calls = []
    result = {"plugin": "Subtract Background...", "hasDialog": True}

    def fake_probe_command(plugin_name):
        calls.append(plugin_name)
        return {"ok": True, "result": result}

    def fail_send(_cmd):
        raise AssertionError("send fallback should not run when ij helper exists")

    monkeypatch.setattr(
        probe_plugin, "_load_ij_probe_command", lambda: fake_probe_command
    )
    monkeypatch.setattr(probe_plugin, "send", fail_send)

    assert probe_plugin.probe_plugin("Subtract Background...") == result
    assert calls == ["Subtract Background..."]


def test_probe_plugin_returns_errors_without_writing_cache(tmp_path, monkeypatch):
    monkeypatch.setattr(probe_plugin, "CACHE_DIR", str(tmp_path))
    monkeypatch.setattr(probe_plugin, "_load_ij_probe_command", lambda: None)

    def fake_send(_cmd):
        return {"ok": False, "error": "No image open"}

    monkeypatch.setattr(probe_plugin, "send", fake_send)

    assert probe_plugin.probe_plugin("Analyze Particles...") == {
        "plugin": "Analyze Particles...",
        "error": "No image open",
    }
    assert not (tmp_path / probe_plugin.cache_key("Analyze Particles...")).exists()


def test_search_and_list_cached_probes_use_temp_cache(tmp_path, monkeypatch):
    monkeypatch.setattr(probe_plugin, "CACHE_DIR", str(tmp_path))
    alpha = {
        "plugin": "Alpha Threshold",
        "fields": [{"label": "Method", "options": ["Otsu"]}],
    }
    beta = {"plugin": "Beta Blur", "fields": [{"label": "Sigma"}]}
    write_cached_probe(tmp_path, "Alpha Threshold", alpha)
    write_cached_probe(tmp_path, "Beta Blur", beta)
    (tmp_path / "notes.txt").write_text("ignored", encoding="utf-8")

    assert probe_plugin.search_cached_probes("threshold") == [alpha]
    assert probe_plugin.search_cached_probes("SIGMA") == [beta]
    assert probe_plugin.list_cached_probes() == ["Alpha Threshold", "Beta Blur"]


def test_probe_plugins_preserves_order_and_force(monkeypatch):
    calls = []

    def fake_probe_plugin(plugin_name, force=False):
        calls.append((plugin_name, force))
        return {"plugin": plugin_name}

    monkeypatch.setattr(probe_plugin, "probe_plugin", fake_probe_plugin)

    assert probe_plugin.probe_plugins(["B", "A"], force=True) == [
        {"plugin": "B"},
        {"plugin": "A"},
    ]
    assert calls == [("B", True), ("A", True)]


def test_main_routes_single_probe_through_clear_helper(monkeypatch, capsys):
    calls = []

    def fake_probe_plugin(plugin_name):
        calls.append(plugin_name)
        return {"plugin": plugin_name}

    monkeypatch.setattr(
        probe_plugin.sys, "argv", ["probe_plugin.py", "Gaussian", "Blur..."]
    )
    monkeypatch.setattr(probe_plugin, "probe_plugin", fake_probe_plugin)
    monkeypatch.setattr(
        probe_plugin,
        "format_probe_result",
        lambda result: "formatted {}".format(result["plugin"]),
    )

    probe_plugin.main()

    assert calls == ["Gaussian Blur..."]
    assert capsys.readouterr().out == "formatted Gaussian Blur...\n"


def test_main_routes_batch_and_force_through_clear_helper(monkeypatch, capsys):
    calls = []

    def fake_probe_plugin(plugin_name, force=False):
        calls.append((plugin_name, force))
        return {"plugin": plugin_name}

    monkeypatch.setattr(probe_plugin, "probe_plugin", fake_probe_plugin)
    monkeypatch.setattr(
        probe_plugin,
        "format_probe_result",
        lambda result: "formatted {}".format(result["plugin"]),
    )

    monkeypatch.setattr(
        probe_plugin.sys, "argv", ["probe_plugin.py", "--batch", "A", "B"]
    )
    probe_plugin.main()
    assert capsys.readouterr().out == "formatted A\nformatted B\n"

    monkeypatch.setattr(probe_plugin.sys, "argv", ["probe_plugin.py", "--force", "C"])
    probe_plugin.main()
    assert capsys.readouterr().out == "formatted C\n"
    assert calls == [("A", False), ("B", False), ("C", True)]


def test_main_routes_cache_only_branches_through_clear_helpers(monkeypatch, capsys):
    calls = []

    def fake_lookup_cached_probe(plugin_name):
        calls.append(("lookup", plugin_name))
        if plugin_name == "A":
            return {"plugin": "A"}
        return None

    def fake_search_cached_probes(keyword):
        calls.append(("search", keyword))
        return [{"plugin": "A"}]

    def fake_list_cached_probes():
        calls.append(("list",))
        return ["A"]

    monkeypatch.setattr(
        probe_plugin, "lookup_cached_probe", fake_lookup_cached_probe
    )
    monkeypatch.setattr(
        probe_plugin, "search_cached_probes", fake_search_cached_probes
    )
    monkeypatch.setattr(probe_plugin, "list_cached_probes", fake_list_cached_probes)
    monkeypatch.setattr(
        probe_plugin,
        "format_probe_result",
        lambda result: "formatted {}".format(result["plugin"]),
    )

    monkeypatch.setattr(
        probe_plugin.sys, "argv", ["probe_plugin.py", "--lookup", "A", "B"]
    )
    probe_plugin.main()
    assert capsys.readouterr().out == "formatted A\nNot cached: B\n"

    monkeypatch.setattr(
        probe_plugin.sys, "argv", ["probe_plugin.py", "--search", "threshold"]
    )
    probe_plugin.main()
    assert capsys.readouterr().out == (
        "Found 1 cached plugins matching 'threshold':\nformatted A\n"
    )

    monkeypatch.setattr(probe_plugin.sys, "argv", ["probe_plugin.py", "--list"])
    probe_plugin.main()
    assert capsys.readouterr().out == "Cached plugins (1):\n  - A\n"
    assert calls == [
        ("lookup", "A"),
        ("lookup", "B"),
        ("search", "threshold"),
        ("list",),
    ]
