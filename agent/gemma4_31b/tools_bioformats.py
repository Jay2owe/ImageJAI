"""Bio-Formats multi-series tools for LIF / CZI / ND2 / LSM files.

These files bundle many images (Leica LIF often has 50-200). The agent
used to open them with `run("Bio-Formats Importer", ...)` which silently
opens only the first series when multiple `series_N=true` tokens are
passed — so these two tools wrap the reliable Groovy paths:

  * list_lif_series  -- read series metadata without opening any pixels
  * open_lif_series  -- open a specific list of 0-indexed series

The Groovy is embedded here rather than living as a new TCP command so
this works against the existing plugin JAR — no Java rebuild needed.
"""

from __future__ import annotations

import json
import re

from .registry import send, tool


def _extract_script_output(resp: object) -> tuple[bool, str]:
    """Pull the string the Groovy script returned out of a run_script reply.

    Shape: {"ok": true, "result": {"success": true, "output": "..."}}.
    Returns (ok, text). On failure, text is a compact error string.
    """
    if not isinstance(resp, dict):
        return False, "non-dict reply: {}".format(resp)
    if not resp.get("ok", False):
        err = resp.get("error")
        return False, str(err) if err else "run_script failed"
    result = resp.get("result")
    if not isinstance(result, dict):
        return False, "run_script reply had no result object"
    if result.get("success") is False:
        err = result.get("error") or result.get("output") or "script threw"
        return False, str(err)
    output = result.get("output")
    if not isinstance(output, str):
        return False, "run_script returned no output string"
    return True, output


def _normalise_path(path: str) -> str:
    """Bio-Formats wants forward slashes even on Windows. Accept either."""
    return path.replace("\\", "/")


def _normalise_indices(indices: object) -> tuple[list[int], str | None]:
    """Coerce Gemma's indices argument to list[int].

    Accepts list, tuple, or comma-separated string (Gemma occasionally
    serialises arrays as strings). Returns (indices, error) — error is
    non-None when the input couldn't be parsed.
    """
    if isinstance(indices, (list, tuple)):
        raw = list(indices)
    elif isinstance(indices, int):
        raw = [indices]
    elif isinstance(indices, str):
        pieces = [p.strip() for p in indices.replace(";", ",").split(",")]
        raw = [p for p in pieces if p]
    else:
        return [], "indices must be a list of integers (got {})".format(type(indices).__name__)
    out: list[int] = []
    for v in raw:
        try:
            out.append(int(v))
        except (TypeError, ValueError):
            return [], "indices contained a non-integer value: {!r}".format(v)
    if not out:
        return [], "indices list is empty — pass at least one 0-indexed series number"
    if any(v < 0 for v in out):
        return [], "indices must be 0-based non-negative integers (got {})".format(out)
    return out, None


_LIST_SCRIPT = r"""
import loci.formats.ImageReader
import loci.formats.MetadataTools

def reader = new ImageReader()
def omeMeta = MetadataTools.createOMEXMLMetadata()
reader.setMetadataStore(omeMeta)
reader.setId(__PATH__)

def n = reader.getSeriesCount()
def entries = []
for (int i = 0; i < n; i++) {
    reader.setSeries(i)
    def name = omeMeta.getImageName(i)
    def esc = name == null ? "" : name.replace('\\', '\\\\').replace('"', '\\"')
    entries << '{"index":' + i +
               ',"name":"' + esc + '"' +
               ',"width":' + reader.getSizeX() +
               ',"height":' + reader.getSizeY() +
               ',"channels":' + reader.getSizeC() +
               ',"slices":' + reader.getSizeZ() +
               ',"frames":' + reader.getSizeT() +
               ',"pixelType":"' + reader.getPixelType() + '"}'
}
reader.close()
return '{"count":' + n + ',"series":[' + entries.join(',') + ']}'
""".strip()


_OPEN_SCRIPT = r"""
import loci.plugins.BF
import loci.plugins.in.ImporterOptions

def opts = new ImporterOptions()
opts.setId(__PATH__)
opts.setOpenAllSeries(false)
opts.setAutoscale(false)
opts.setColorMode(ImporterOptions.COLOR_MODE_DEFAULT)
opts.setVirtual(false)

def wanted = __INDICES__
wanted.each { opts.setSeriesOn((int) it, true) }

def imps = BF.openImagePlus(opts)
def titles = []
imps.each { imp ->
    imp.show()
    def t = imp.getTitle().replace('\\', '\\\\').replace('"', '\\"')
    titles << '"' + t + '"'
}
return '{"opened":' + imps.length + ',"titles":[' + titles.join(',') + ']}'
""".strip()


@tool
def list_lif_series(path: str) -> dict:
    """List every series in a multi-series file (.lif / .czi / .nd2 / .lsm) without opening pixels.

    Returns each series' 0-based index, name, dimensions, and pixel type.
    Use before `open_lif_series` to pick the right indices — opening all
    series of a 50+ series LIF blindly will exhaust memory.

    Args:
        path: Absolute path to the container file. Forward slashes on Windows.
    """
    groovy_path = json.dumps(_normalise_path(path))
    script = _LIST_SCRIPT.replace("__PATH__", groovy_path)
    resp = send("run_script", code=script, language="groovy")
    ok, text = _extract_script_output(resp)
    if not ok:
        return {"ok": False, "error": text}
    try:
        parsed = json.loads(text)
    except ValueError as exc:
        return {"ok": False, "error": "could not parse script output: {}".format(exc), "raw": text[:500]}
    return {"ok": True, **parsed}


@tool
def open_lif_series(path: str, indices: list) -> dict:
    """Open specific series from a multi-series file (.lif / .czi / .nd2 / .lsm).

    Uses the Bio-Formats Groovy API (reliable) rather than the
    `series_N=true` macro tokens (which silently open only series 1 when
    multiple are listed). Series are shown in Fiji; pixel values are
    preserved (autoscale is off).

    Args:
        path: Absolute path to the container file. Forward slashes on Windows.
        indices: List of 0-based series indices, e.g. [49, 50, 51]. Get
            these from `list_lif_series` first.
    """
    resolved, err = _normalise_indices(indices)
    if err:
        return {"ok": False, "error": err}
    groovy_path = json.dumps(_normalise_path(path))
    groovy_indices = json.dumps(resolved)
    script = _OPEN_SCRIPT.replace("__PATH__", groovy_path).replace("__INDICES__", groovy_indices)
    resp = send("run_script", code=script, language="groovy")
    ok, text = _extract_script_output(resp)
    if not ok:
        return {"ok": False, "error": text, "requested": resolved}
    try:
        parsed = json.loads(text)
    except ValueError as exc:
        return {"ok": False, "error": "could not parse script output: {}".format(exc), "raw": text[:500]}
    return {"ok": True, "requested": resolved, **parsed}
