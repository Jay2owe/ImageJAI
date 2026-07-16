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
import json
import os
import platform
import re
import subprocess
import sys
import tempfile

AGENT_DIR = os.path.dirname(os.path.abspath(__file__))
TMP_DIR = os.path.join(AGENT_DIR, ".tmp")


def _manifest_product_version():
    path = os.path.join(AGENT_DIR, "command_manifest.json")
    try:
        with open(path, "r", encoding="utf-8") as handle:
            value = json.load(handle).get("product_version")
    except (OSError, ValueError, AttributeError) as exc:
        raise RuntimeError("cannot read ImageJAI product version from %s: %s"
                           % (path, exc))
    if not isinstance(value, str) or not value.strip():
        raise RuntimeError("command manifest has no product_version: %s" % path)
    return value.strip()


IMAGEJAI_VERSION = _manifest_product_version()
MAX_INPUT_BYTES = 64 * 1024 * 1024
MAX_SESSION_LOG_CANDIDATES = 512


def load_json_file(path):
    """Read a methods/session input with a fixed upper bound."""
    size = os.path.getsize(path)
    if size > MAX_INPUT_BYTES:
        raise ValueError("methods input exceeds %d byte limit: %s"
                         % (MAX_INPUT_BYTES, path))
    with open(path, "rb") as handle:
        payload = handle.read(MAX_INPUT_BYTES + 1)
    if len(payload) > MAX_INPUT_BYTES:
        raise ValueError("methods input exceeds %d byte limit: %s"
                         % (MAX_INPUT_BYTES, path))
    return json.loads(payload.decode("utf-8"))


def resolve_bundle_log_path(bundle_path, log_path):
    """Resolve an offline bundle's relative log reference beside the bundle."""
    if not log_path or os.path.isabs(log_path):
        return log_path
    return os.path.join(os.path.dirname(os.path.abspath(bundle_path)), log_path)


def atomic_write_text(path, text):
    """Publish a complete UTF-8 document without exposing a partial file."""
    directory = os.path.dirname(os.path.abspath(path))
    os.makedirs(directory, exist_ok=True)
    temp_path = None
    try:
        with tempfile.NamedTemporaryFile(
                mode="w", encoding="utf-8", dir=directory,
                prefix=os.path.basename(path) + ".", suffix=".tmp",
                delete=False) as handle:
            temp_path = handle.name
            handle.write(text)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temp_path, path)
        temp_path = None
    finally:
        if temp_path:
            try:
                os.unlink(temp_path)
            except OSError:
                pass


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


def latest_session_log(client_session_id=None):
    """Return the newest log for a launcher session, or the newest log.

    If a launcher session is supplied, never attach another agent's log just
    because it was modified more recently.
    """
    files = []
    try:
        with os.scandir(TMP_DIR) as entries:
            for entry in entries:
                if (not entry.name.startswith("session_")
                        or not entry.name.endswith(".json")
                        or not entry.is_file()):
                    continue
                try:
                    files.append((entry.stat().st_mtime, entry.path))
                except OSError:
                    continue
                if len(files) > MAX_SESSION_LOG_CANDIDATES:
                    raise ValueError(
                        "session log candidates exceed %d file limit"
                        % MAX_SESSION_LOG_CANDIDATES)
    except FileNotFoundError:
        return None, None
    files.sort(key=lambda item: (-item[0], item[1]))
    for _modified, p in files:
        try:
            data = load_json_file(p)
            if not isinstance(data, dict):
                raise ValueError("session log root is not an object")
            if (client_session_id
                    and data.get("client_session_id") != client_session_id):
                continue
            return p, data
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
                                   info.get("nSlices", info.get("slices", "?")),
                                   info.get("nChannels", info.get("channels", "?")),
                                   info.get("nFrames", info.get("frames", "?")))
        put("Image dimensions (X x Y x Z x C x T)", dims)
        bit_depth = info.get("bitDepth") or _bit_depth_from_type(info.get("type"))
        put("Bit depth", (str(bit_depth) + "-bit") if bit_depth else None)
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
    put("TCP session ID", (info or {}).get("tcpSessionId"))
    put("Agent launch session ID", (info or {}).get("clientSessionId"))
    put("Session log ID", (info or {}).get("sessionLogId"))
    put("Session log path", (info or {}).get("sessionLogPath"))
    put("Initiating dataset title", (info or {}).get("title"))
    put("Initiating dataset path", (info or {}).get("filePath"))
    put("Initiating dataset identity", (info or {}).get("identity"))
    put("Initiating dataset hash", (info or {}).get("hash"))
    put("Operating system", platform.platform())
    put("Replayable macro export",
        "session_log.export_macro() — see agent/.tmp/replay_*.ijm")

    return fields


def _bit_depth_from_type(image_type):
    """Translate the real get_state.activeImage `type` field to bit depth."""
    if not isinstance(image_type, str):
        return None
    match = re.search(r"(8|16|24|32)\s*[- ]?bit", image_type, re.I)
    if match:
        return int(match.group(1))
    if image_type.upper() in ("RGB", "RGB COLOR"):
        return 24
    return None


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
    ap.add_argument("--dataset-json", metavar="JSON",
                    help="initiating dataset captured by the server at admission")
    args = ap.parse_args()

    initiating = None
    if args.dataset_json:
        initiating = json.loads(args.dataset_json)
        if not isinstance(initiating, dict):
            raise ValueError("--dataset-json must decode to an object")
    initiating_client_session = (initiating or {}).get("clientSessionId")

    if args.from_file:
        bundle = load_json_file(args.from_file)
        session = bundle.get("session")
        metadata = bundle.get("metadata")
        graph = bundle.get("graph")
        info = bundle.get("info")
        header_path = bundle.get("session_log_path") or bundle.get("log_path")
        header_path = resolve_bundle_log_path(args.from_file, header_path)
    else:
        path, session = latest_session_log(initiating_client_session)
        metadata = fetch_metadata()
        graph = fetch_graph()
        state = fetch_state()
        info = (state or {}).get("activeImage") if state else None
        header_path = path

    info = dict(info) if isinstance(info, dict) else {}
    if header_path:
        info["sessionLogPath"] = os.path.abspath(header_path)
    if isinstance(session, dict) and session.get("session_id"):
        info["sessionLogId"] = session["session_id"]
    client_session_id = os.environ.get("IMAGEJAI_SESSION_ID", "").strip()
    if client_session_id:
        info.setdefault("clientSessionId", client_session_id)

    if initiating is not None:
        live = info
        bound_path = initiating.get("filePath")
        live_path = live.get("filePath")
        same_dataset = False
        if bound_path and live_path:
            same_dataset = (os.path.normcase(os.path.abspath(bound_path))
                            == os.path.normcase(os.path.abspath(live_path)))
        elif initiating.get("identity") and live.get("identity"):
            same_dataset = initiating["identity"] == live["identity"]
        if not same_dataset:
            # Never describe the dataset that happened to become current while
            # the subprocess was starting. Unknown metadata is safer than
            # confidently attaching another image's acquisition details.
            metadata = None
            preserved_provenance = {
                key: live[key] for key in (
                    "sessionLogPath", "sessionLogId", "clientSessionId"
                ) if key in live
            }
            live = preserved_provenance
        info = dict(live)
        info.update({key: value for key, value in initiating.items()
                     if value is not None and value != -1})

    fields = extract_fields(session, metadata, graph, info)
    text, populated, total = render(fields, header_path)

    if args.dry_run:
        sys.stdout.write(text)
        print("\nemitted methods.md (dry-run): %d/%d WG11 fields populated, "
              "%d marked [unknown]" % (populated, total, total - populated))
        return 0

    out, fallback = (args.out, False) if args.out else output_path(metadata, info)
    atomic_write_text(out, text)
    if fallback:
        sys.stderr.write("warning: no active image; wrote to fallback %s\n" % out)
    print("emitted methods.md: %d/%d WG11 fields populated, %d marked [unknown] -> %s"
          % (populated, total, total - populated, out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
