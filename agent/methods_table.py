#!/usr/bin/env python
"""
QUAREP-LiMi WG11-aligned methods.md auto-emitter.

Walks the most recent session log + Bio-Formats metadata + the provenance
graph, and produces a Markdown methods table with [unknown] placeholders
for fields the data does not cover. The output is a draft for the author
to edit before journal submission, NOT an oracle.

Usage:
    python methods_table.py                   # live: read latest session log + ij.py
    python methods_table.py --dry-run         # print would-be output, no write
    python methods_table.py --from-file P.json  # offline: P.json contains
                                               # {"session": ..., "metadata": ...,
                                               #  "graph": ..., "info": ...}

Design notes:
- Stdlib only.
- Bio-Formats metadata strings come back wrapped in `[OME-XML: ...]` and
  `[META:<key>: ...]` envelopes (D7 sanitiser). strip_envelope() removes them
  before parsing. Idempotent on un-wrapped strings.
- Outputs to <image-dir>/AI_Exports/methods.md per house rule. Falls back
  to agent/.tmp/methods.md when no image is open and warns.
"""

import argparse
import glob
import json
import os
import platform
import re
import subprocess
import sys

AGENT_DIR = os.path.dirname(os.path.abspath(__file__))
TMP_DIR = os.path.join(AGENT_DIR, ".tmp")
IMAGEJAI_VERSION = "1.0.0-pre"  # bump on release; CITATION.cff is canonical


def strip_envelope(s):
    """Strip `[<TAG>: ... ]` envelope; idempotent / safe on unwrapped text."""
    if not isinstance(s, str):
        return s
    m = re.match(r"^\[([A-Za-z0-9_:\-]+):\s?(.*)\]\s*$", s, re.DOTALL)
    return m.group(2) if m else s


def _ij(args, timeout=10):
    """Call `python ij.py <args...>` and return stdout (str) or None on error."""
    try:
        cp = subprocess.run([sys.executable, os.path.join(AGENT_DIR, "ij.py")]
                            + list(args),
                            capture_output=True, text=True, timeout=timeout)
        if cp.returncode != 0:
            return None
        return cp.stdout
    except (OSError, subprocess.TimeoutExpired):
        return None


def fetch_metadata():
    """Live: pull metadata via `ij.py raw {get_metadata}`. Returns dict or None."""
    out = _ij(["raw", json.dumps({"command": "get_metadata"})])
    if not out:
        return None
    try:
        resp = json.loads(out)
    except ValueError:
        return None
    return resp.get("result") if resp.get("ok") else None


def fetch_graph():
    """Live: provenance graph snapshot. Returns dict or None."""
    out = _ij(["raw", json.dumps({"command": "get_image_graph"})])
    if not out:
        return None
    try:
        resp = json.loads(out)
    except ValueError:
        return None
    return resp.get("result") if resp.get("ok") else None


def fetch_state():
    """Live: ImageJ state for image dims / IJ version. Returns dict or None."""
    out = _ij(["raw", json.dumps({"command": "get_state"})])
    if not out:
        return None
    try:
        resp = json.loads(out)
    except ValueError:
        return None
    return resp.get("result") if resp.get("ok") else None


def latest_session_log():
    """Find the newest session_*.json under agent/.tmp/. Return (path, dict)."""
    pattern = os.path.join(TMP_DIR, "session_*.json")
    files = sorted(glob.glob(pattern), key=os.path.getmtime, reverse=True)
    for p in files:
        try:
            with open(p, "r", encoding="utf-8") as f:
                return p, json.load(f)
        except (IOError, OSError, ValueError):
            continue
    return None, None


# ---------------------------------------------------------------------------
# Field extractors — each returns (value_or_None, source_label)
# ---------------------------------------------------------------------------

_OME_RE = {
    "objective_model": re.compile(r"<Objective[^>]*\bModel=\"([^\"]+)\"", re.I),
    "objective_mfg": re.compile(r"<Objective[^>]*\bManufacturer=\"([^\"]+)\"", re.I),
    "objective_na": re.compile(r"<Objective[^>]*\bLensNA=\"([^\"]+)\"", re.I),
    "objective_immersion": re.compile(r"<Objective[^>]*\bImmersion=\"([^\"]+)\"", re.I),
    "objective_mag": re.compile(r"<Objective[^>]*\bNominalMagnification=\"([^\"]+)\"", re.I),
    "microscope_mfg": re.compile(r"<Microscope[^>]*\bManufacturer=\"([^\"]+)\"", re.I),
    "microscope_model": re.compile(r"<Microscope[^>]*\bModel=\"([^\"]+)\"", re.I),
    "microscope_type": re.compile(r"<Microscope[^>]*\bType=\"([^\"]+)\"", re.I),
    "detector_model": re.compile(r"<Detector[^>]*\bModel=\"([^\"]+)\"", re.I),
    "detector_mfg": re.compile(r"<Detector[^>]*\bManufacturer=\"([^\"]+)\"", re.I),
    "detector_type": re.compile(r"<Detector[^>]*\bType=\"([^\"]+)\"", re.I),
    "ex_wavelength": re.compile(r"\bExcitationWavelength=\"([^\"]+)\"", re.I),
    "em_wavelength": re.compile(r"\bEmissionWavelength=\"([^\"]+)\"", re.I),
    "exposure": re.compile(r"\bExposureTime=\"([^\"]+)\"", re.I),
    "physical_x": re.compile(r"\bPhysicalSizeX=\"([^\"]+)\"", re.I),
    "physical_y": re.compile(r"\bPhysicalSizeY=\"([^\"]+)\"", re.I),
    "physical_z": re.compile(r"\bPhysicalSizeZ=\"([^\"]+)\"", re.I),
    "time_inc": re.compile(r"\bTimeIncrement=\"([^\"]+)\"", re.I),
    "channel_name": re.compile(r"<Channel[^>]*\bName=\"([^\"]+)\"", re.I),
    "channel_fluor": re.compile(r"<Channel[^>]*\bFluor=\"([^\"]+)\"", re.I),
    "lightsource": re.compile(r"<(Laser|Arc|Filament|LightEmittingDiode)\b", re.I),
}


def _ome_text(metadata):
    if not metadata:
        return ""
    return strip_envelope(metadata.get("info") or "")


def _ome_first(ome, key):
    rx = _OME_RE.get(key)
    if not rx or not ome:
        return None
    m = rx.search(ome)
    return m.group(1) if m else None


def _ome_all(ome, key):
    rx = _OME_RE.get(key)
    if not rx or not ome:
        return []
    return [m.group(1) for m in rx.finditer(ome)]


def extract_fields(session, metadata, graph, info):
    """Walk every WG11 field. Return ordered list of (label, value) tuples
    where value is either the populated string or '[unknown]'."""
    ome = _ome_text(metadata)
    cal = (metadata or {}).get("calibration") or {}
    fields = []
    UNKNOWN = "[unknown]"

    def put(label, val):
        fields.append((label, val if val else UNKNOWN))

    # Microscope
    mfg = _ome_first(ome, "microscope_mfg")
    model = _ome_first(ome, "microscope_model")
    if mfg and model:
        put("Manufacturer & model", "%s %s" % (mfg, model))
    elif mfg or model:
        put("Manufacturer & model", mfg or model)
    else:
        put("Manufacturer & model", None)
    put("Microscope type", _ome_first(ome, "microscope_type"))
    put("Stage / sample holder", None)

    # Optics
    obj_mfg = _ome_first(ome, "objective_mfg")
    obj_model = _ome_first(ome, "objective_model")
    obj_mag = _ome_first(ome, "objective_mag")
    parts = [p for p in (obj_mfg, obj_model,
                         (obj_mag and ("%sx" % obj_mag))) if p]
    put("Objective lens", " ".join(parts) if parts else None)
    put("Numerical aperture (NA)", _ome_first(ome, "objective_na"))
    put("Immersion medium", _ome_first(ome, "objective_immersion"))
    put("Working distance", None)

    # Illumination
    sources = _ome_all(ome, "lightsource")
    put("Light source(s)", ", ".join(sorted(set(sources))) if sources else None)
    ex = _ome_all(ome, "ex_wavelength")
    put("Excitation wavelength(s) (nm)",
        ", ".join(ex) if ex else None)
    put("Excitation power / setting", None)

    # Detection
    det_parts = [_ome_first(ome, "detector_mfg"),
                 _ome_first(ome, "detector_model"),
                 _ome_first(ome, "detector_type")]
    det_parts = [p for p in det_parts if p]
    put("Detector(s)", " ".join(det_parts) if det_parts else None)
    em = _ome_all(ome, "em_wavelength")
    put("Emission wavelength(s) / filter(s) (nm)",
        ", ".join(em) if em else None)
    put("Detector gain / offset", None)

    # Acquisition
    px_w = cal.get("pixelWidth") or _ome_first(ome, "physical_x")
    px_h = cal.get("pixelHeight") or _ome_first(ome, "physical_y")
    unit = cal.get("unit") or ""
    if px_w and px_h:
        put("Pixel size (calibrated)",
            "%s x %s %s" % (px_w, px_h, unit))
    else:
        put("Pixel size (calibrated)", None)
    px_d = cal.get("pixelDepth") or _ome_first(ome, "physical_z")
    put("Z step / voxel depth",
        ("%s %s" % (px_d, unit)) if px_d else None)
    fi = cal.get("frameInterval") or _ome_first(ome, "time_inc")
    tu = cal.get("timeUnit") or ""
    put("Frame interval (time-series)",
        ("%s %s" % (fi, tu)) if fi else None)
    exposure = _ome_all(ome, "exposure")
    put("Exposure time", ", ".join(exposure) if exposure else None)
    if info:
        dims = "%sx%sx%sx%sx%s" % (info.get("width", "?"), info.get("height", "?"),
                                   info.get("nSlices", "?"), info.get("nChannels", "?"),
                                   info.get("nFrames", "?"))
        put("Image dimensions (X x Y x Z x C x T)", dims)
        put("Bit depth", str(info.get("bitDepth", "?")) + "-bit")
    else:
        put("Image dimensions (X x Y x Z x C x T)", None)
        put("Bit depth", None)
    put("Acquisition rationale", "[unknown - human only]")

    # Sample
    chan_names = _ome_all(ome, "channel_name") or _ome_all(ome, "channel_fluor")
    put("Specimen description", None)
    put("Fluorescent labels / stains per channel",
        ", ".join(chan_names) if chan_names else None)
    put("Mounting medium", None)
    put("Fixation", None)

    # Analysis
    ij_ver = (info or {}).get("imagejVersion") or "(ImageJ build [unknown])"
    put("Analysis tool", "ImageJAI %s / Fiji %s" % (IMAGEJAI_VERSION, ij_ver))
    plugins, params = _summarise_session(session)
    put("Plugins / commands invoked",
        "; ".join(plugins) if plugins else None)
    put("Parameters", "; ".join(params) if params else None)
    n_imgs = _graph_image_count(graph)
    put("Number of images processed",
        str(n_imgs) if n_imgs else None)
    put("Statistical-test protocol", "[unknown - human only]")

    # Software
    put("ImageJAI version", IMAGEJAI_VERSION)
    put("Fiji / ImageJ version", (info or {}).get("imagejVersion"))
    put("Operating system", platform.platform())
    put("Replayable macro export",
        "session_log.export_macro() — see agent/.tmp/replay_*.ijm")

    return fields


def _summarise_session(session):
    """Pull distinct run("X") plugin invocations + their argument strings."""
    if not session:
        return [], []
    plugin_re = re.compile(r"""run\(\s*["']([^"']+)["']\s*(?:,\s*["']([^"']*)["'])?\s*\)""")
    plugins, params = [], []
    seen = set()
    for entry in session.get("entries", []) or []:
        cmd = (entry.get("command") or {})
        code = cmd.get("code") or ""
        for m in plugin_re.finditer(code):
            name, args = m.group(1), (m.group(2) or "")
            if name in seen:
                continue
            seen.add(name)
            plugins.append(name)
            if args:
                params.append('%s: "%s"' % (name, args))
    return plugins, params


def _graph_image_count(graph):
    if not graph:
        return None
    nodes = graph.get("nodes")
    return len(nodes) if isinstance(nodes, list) else None


# ---------------------------------------------------------------------------
# Output rendering
# ---------------------------------------------------------------------------

SECTION_BREAKS = {
    "Manufacturer & model": "## Microscope",
    "Objective lens": "## Optics",
    "Light source(s)": "## Illumination",
    "Detector(s)": "## Detection",
    "Pixel size (calibrated)": "## Acquisition",
    "Specimen description": "## Sample",
    "Analysis tool": "## Analysis",
    "ImageJAI version": "## Software",
}


def render(fields, header_path):
    lines = ["# Methods Table (QUAREP-LiMi WG11 aligned)", ""]
    if header_path:
        lines.append("Generated by `agent/methods_table.py` from session log + Bio-Formats")
        lines.append("metadata + provenance graph at `%s`." % header_path)
        lines.append("")
    lines.append("This is a **draft** — the exporter populates fields it can read")
    lines.append("from data and marks the rest `[unknown]`. Author edits before")
    lines.append("journal submission.")
    lines.append("")
    populated = 0
    for label, val in fields:
        if label in SECTION_BREAKS:
            lines.append(SECTION_BREAKS[label])
        is_human_only = "[unknown - human only]" in val
        if val and not val.startswith("[unknown]") and not is_human_only:
            populated += 1
        lines.append("- %s: %s" % (label, val))
    total = len(fields)
    unknown = total - populated
    lines.append("")
    lines.append("---")
    lines.append("")
    lines.append("Field coverage: %d/%d WG11 fields populated; %d marked [unknown]."
                 % (populated, total, unknown))
    return "\n".join(lines) + "\n", populated, total


def output_path(metadata, info):
    """Resolve <image-dir>/AI_Exports/methods.md or fallback to agent/.tmp/."""
    src = None
    if info and info.get("filePath"):
        src = info["filePath"]
    elif info and info.get("directory") and info.get("fileName"):
        src = os.path.join(info["directory"], info["fileName"])
    if src and os.path.isabs(src) and os.path.isdir(os.path.dirname(src)):
        out_dir = os.path.join(os.path.dirname(src), "AI_Exports")
        os.makedirs(out_dir, exist_ok=True)
        return os.path.join(out_dir, "methods.md"), False
    os.makedirs(TMP_DIR, exist_ok=True)
    return os.path.join(TMP_DIR, "methods.md"), True


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--dry-run", action="store_true",
                    help="print methods.md to stdout without writing")
    ap.add_argument("--from-file", metavar="PATH",
                    help="offline: read {session,metadata,graph,info} dict from JSON")
    ap.add_argument("--out", metavar="PATH", help="override output path")
    args = ap.parse_args()

    if args.from_file:
        with open(args.from_file, "r", encoding="utf-8") as f:
            bundle = json.load(f)
        session = bundle.get("session")
        metadata = bundle.get("metadata")
        graph = bundle.get("graph")
        info = bundle.get("info")
        header_path = args.from_file
    else:
        path, session = latest_session_log()
        metadata = fetch_metadata()
        graph = fetch_graph()
        state = fetch_state()
        info = (state or {}).get("activeImage") if state else None
        header_path = path

    fields = extract_fields(session, metadata, graph, info)
    text, populated, total = render(fields, header_path)

    if args.dry_run:
        sys.stdout.write(text)
        print("\nemitted methods.md (dry-run): %d/%d WG11 fields populated, "
              "%d marked [unknown]" % (populated, total, total - populated))
        return 0

    out, fallback = (args.out, False) if args.out else output_path(metadata, info)
    with open(out, "w", encoding="utf-8") as f:
        f.write(text)
    if fallback:
        sys.stderr.write("warning: no active image; wrote to fallback %s\n" % out)
    print("emitted methods.md: %d/%d WG11 fields populated, %d marked [unknown] -> %s"
          % (populated, total, total - populated, out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
