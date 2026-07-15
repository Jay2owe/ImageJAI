"""Opt-in smoke test for the real Fiji/ImageJ TCP endpoint.

This module is deliberately outside pytest's default test roots. Run it only
after starting Fiji with ImageJAI enabled::

    python test-scripts/live_fiji_smoke.py

The macro creates one uniquely named in-memory image and closes it before it
asserts the result, so a failed assertion cannot leave the fixture open.
"""

from __future__ import annotations

import json
import sys
import uuid
from pathlib import Path


PROJECT_DIR = Path(__file__).resolve().parents[1]
AGENT_DIR = PROJECT_DIR / "agent"
if str(AGENT_DIR) not in sys.path:
    sys.path.insert(0, str(AGENT_DIR))

from ij import imagej_command  # noqa: E402


def run_smoke() -> None:
    ping = imagej_command({"command": "ping"}, timeout=5)
    assert ping.get("ok") is True, (
        "Fiji/ImageJAI is not reachable on the configured loopback endpoint: "
        + json.dumps(ping, sort_keys=True)
    )

    title = "__ImageJAI_smoke_{}".format(uuid.uuid4().hex)
    marker = "IMAGEJAI_SMOKE_OK"
    macro = """
newImage("{title}", "8-bit black", 4, 4, 1);
setPixel(1, 1, 173);
value = getPixel(1, 1);
close();
if (value != 173) exit("Smoke pixel round-trip failed: " + value);
print("{marker}");
""".format(title=title, marker=marker)

    response = imagej_command(
        {"command": "execute_macro", "code": macro, "source": "live_fiji_smoke"},
        timeout=30,
    )
    assert response.get("ok") is True, json.dumps(response, indent=2)
    result = response.get("result", response)
    assert result.get("success") is True, json.dumps(response, indent=2)
    assert marker in str(result.get("output", "")), json.dumps(response, indent=2)


def main() -> int:
    try:
        run_smoke()
    except (AssertionError, OSError) as exc:
        print("LIVE FIJI SMOKE FAILED: {}".format(exc), file=sys.stderr)
        return 1
    print("LIVE FIJI SMOKE PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

