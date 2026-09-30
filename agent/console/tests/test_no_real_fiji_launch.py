"""Console tests must never launch the user's real Fiji (regression: settings tests did)."""
import pytest


def test_launching_a_fiji_outside_tmp_path_is_refused(tmp_path_factory):
    from agent.console import fiji_startup

    outside = tmp_path_factory.mktemp("elsewhere") / "Fiji.app"
    outside.mkdir()
    (outside / "ImageJ-win64.exe").write_bytes(b"not a launcher")
    with pytest.raises(AssertionError, match="real Fiji"):
        fiji_startup.launch_fiji(outside)
