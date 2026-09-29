from __future__ import annotations

from pathlib import Path

from agent.console.browse import PathTokenMap, mention_candidates
from agent.console.tokens import PluginTokenMap


class RemoteMinter:
    def __init__(self) -> None:
        self.calls: list[list[str]] = []

    def pseudonymise_paths(self, paths):
        values = [str(p) for p in paths]
        self.calls.append(values)
        return {"ok": True, "result": {"pseudonymised": True, "mappings": [
            {"index": i, "path": f"image-plugin{i:02x}.tif", "token": f"image-plugin{i:02x}.tif"}
            for i, _ in enumerate(values)
        ]}}


def test_plugin_token_is_cached_and_reversible(tmp_path: Path):
    image = tmp_path / "patient Alice.tif"
    image.write_bytes(b"pixels")
    remote = RemoteMinter()
    tokens = PluginTokenMap(remote)

    first = tokens.token_for_path(image)
    second = tokens.token_for_path(image)

    assert first == second == "image-plugin00.tif"
    assert remote.calls == [[str(image.resolve())]]
    assert tokens.remote_mints == 1 and tokens.local_mints == 0
    assert tokens.resolve(first).path == image.resolve()
    assert tokens.reverse(f'open("{first}")') == f'open("{image.resolve()}")'


def test_plugin_failure_falls_back_to_local_token(tmp_path: Path):
    image = tmp_path / "cell.tif"
    image.write_bytes(b"pixels")

    class Offline:
        def pseudonymise_paths(self, paths):
            raise ConnectionError("Fiji is offline")

    tokens = PluginTokenMap(Offline(), salt=b"x" * 32)
    token = tokens.token_for_path(image)
    assert token.startswith("image-")
    assert token != str(image.resolve())
    assert tokens.remote_mints == 0 and tokens.local_mints == 1
    assert tokens.last_error == "Fiji is offline"
    assert tokens.resolve(token).path == image.resolve()


def test_mentions_use_plugin_tokens_and_never_show_real_path(tmp_path: Path):
    image = tmp_path / "patient-name.tif"
    image.write_bytes(b"pixels")
    tokens = PluginTokenMap(RemoteMinter())

    rows = mention_candidates(tmp_path, token_map=tokens, posture="PSEUDONYMISED")

    assert len(rows) == 1
    assert rows[0].insert_text == "image-plugin00.tif"
    assert "patient-name" not in rows[0].insert_text
    assert tokens.mapping()["image-plugin00.tif"] == image.resolve()


def test_adopt_rejects_conflicting_mappings(tmp_path: Path):
    first = tmp_path / "one.tif"
    second = tmp_path / "two.tif"
    token_map = PathTokenMap(salt=b"y" * 32)
    token_map.adopt("image-external.tif", first)

    import pytest
    with pytest.raises(ValueError, match="another path"):
        token_map.adopt("image-external.tif", second)
    with pytest.raises(ValueError, match="another token"):
        token_map.adopt("image-other.tif", first)


def test_fiji_connection_exposes_plugin_minter_without_reversing_input(tmp_path: Path):
    from agent.console.fiji import FijiConnection

    image = tmp_path / "real.tif"
    image.write_bytes(b"pixels")
    seen = []

    class IJ:
        @staticmethod
        def pseudonymise_paths(paths):
            seen.append(paths)
            return {"ok": True, "result": {"mappings": [
                {"path": "image-jvm.tif", "token": "image-jvm.tif"}
            ]}}

    connection = FijiConnection("127.0.0.1", 7746)
    connection._module = lambda: IJ
    # Even if there is a local map, registration sends the real path.
    local = PathTokenMap(salt=b"z" * 32)
    local_token = local.token_for_path(image)
    connection.token_map = local

    response = connection.pseudonymise_paths([str(image.resolve())])

    assert seen == [[str(image.resolve())]]
    assert response["result"]["mappings"][0]["token"] == "image-jvm.tif"
    assert local_token not in str(seen)
