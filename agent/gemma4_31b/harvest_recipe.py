"""Phase 9 — harvest a successful workflow into a reusable YAML recipe.

The agent does not decide which numbers are generalisable. Every
literal number extracted from the captured macros is marked
image_specific: true unless the user explicitly names it in the
promote list passed to draft_recipe. This is deliberate: the
agent cannot tell whether `sigma=2` was a good default or a
lucky guess that happened to work on one image, and a silently
generalised bad number would poison future sessions.

Flow:
    read_audit_log  ->  filter_workflow  ->  extract_parameters
                                         ->  draft_recipe
                                         ->  save_recipe_file

draft_recipe orchestrates the previous three and leaves the
result in memory; save_recipe_file is the only writer, and it
dedupes filenames by appending _2, _3, ... rather than
overwriting anything already in the configured recipe directory.
"""

from __future__ import annotations

import datetime
import json
import os
import re
from pathlib import Path

import yaml

from . import active_image
from . import safety


# Read-only calls that do no real work. A macro whose only calls
# are in this set is dropped from the recipe workflow.
_READ_ONLY_CALLS = frozenset({
    "get_state",
    "capture_image",
    "get_log",
    "get_results",
    "get_open_windows",
    "get_dialogs",
    "get_histogram",
    "print",
})


_CALL_NAME_RE = re.compile(r"\b([A-Za-z_][A-Za-z0-9_]*)\s*\(")

_RUN_CALL_RE = re.compile(
    r'\brun\s*\(\s*"([^"]+)"\s*(?:,\s*"([^"]*)")?\s*\)',
    re.DOTALL,
)

_KEY_VALUE_RE = re.compile(
    r'([A-Za-z_][A-Za-z0-9_\-]*)\s*=\s*(\[[^\]]*\]|"[^"]*"|\S+)'
)

_NUMBER_RE = re.compile(r'(?<![0-9])-?\d+(?:\.\d+)?')

_SET_AUTO_THRESHOLD_RE = re.compile(r'\bsetAutoThreshold\s*\(\s*"([^"]*)"\s*\)')

_SET_MIN_MAX_RE = re.compile(r'\bsetMinAndMax\s*\(([^)]*)\)')

_IMAGE_TYPE_RE = re.compile(r"\b(8-bit|16-bit|32-bit|RGB)\b", re.IGNORECASE)
_CHANNEL_COUNT_RE = re.compile(r"\b(single channel|(\d+) channels?)\b", re.IGNORECASE)
_Z_COUNT_RE = re.compile(r"\b(single z|(\d+) z-slices?)\b", re.IGNORECASE)
_FRAME_COUNT_RE = re.compile(r"\b(single frame|(\d+) frames?)\b", re.IGNORECASE)


_DEFAULT_RECIPES_DIR = Path(__file__).resolve().parent.parent / "recipes"
_USER_RECIPES_ENV = "IMAGEJAI_USER_RECIPES_DIR"


def read_audit_log(export_folder: str) -> list[dict]:
    """Read audit.log and return one dict per macro/script entry, in order.

    Each line of audit.log is a JSON object with ts, session_id,
    source, code, and success fields. Malformed lines are skipped
    silently. When the file contains session-scoped records, only
    the current session is returned so old workflows in the same
    AI_Exports/ folder are not harvested by mistake.
    """
    entries: list[dict] = []
    scoped_entries: list[dict] = []
    current_session = safety.session_id()
    saw_scoped_record = False
    if not isinstance(export_folder, str) or not export_folder:
        return entries
    path = os.path.join(export_folder, "audit.log")
    try:
        with open(path, "r", encoding="utf-8") as fh:
            for line in fh:
                stripped = line.strip()
                if not stripped:
                    continue
                try:
                    record = json.loads(stripped)
                except ValueError:
                    continue
                if not isinstance(record, dict):
                    continue
                if record.get("source") not in ("macro", "script"):
                    continue
                entries.append(record)
                record_session = record.get("session_id")
                if isinstance(record_session, str) and record_session:
                    saw_scoped_record = True
                    if record_session == current_session:
                        scoped_entries.append(record)
    except OSError:
        return entries
    if saw_scoped_record:
        return scoped_entries
    return entries


def filter_workflow(entries: list[dict]) -> list[dict]:
    """Drop read-only and print-only entries from an audit list.

    Keeps an entry whenever its code makes at least one call
    outside the read-only set (get_state, capture_image, get_log,
    get_results, get_open_windows, get_dialogs, get_histogram,
    print). Entries with no detectable calls at all are also
    dropped — a snippet that is pure arithmetic with no side
    effects is not a workflow step worth recording.
    """
    kept: list[dict] = []
    for entry in entries or []:
        if not isinstance(entry, dict):
            continue
        if entry.get("success") is False:
            continue
        code = entry.get("code")
        if not isinstance(code, str) or not code.strip():
            continue
        calls = _CALL_NAME_RE.findall(code)
        if not calls:
            continue
        if all(call in _READ_ONLY_CALLS for call in calls):
            continue
        kept.append(entry)
    return kept


def extract_parameters(code: str) -> list[dict]:
    """Pull literal numbers out of run(...), setAutoThreshold, setMinAndMax.

    Returns a list of {"name", "value", "note"} dicts, deduped by
    (name, value). The name comes from the key in a `key=value`
    pair inside run's argument string, or from a positional slot
    in setMinAndMax. The note is a short reason derived from the
    plugin or function name the number was used by.
    """
    if not isinstance(code, str) or not code:
        return []
    results: list[dict] = []
    seen: set[tuple[str, object]] = set()

    def _emit(name: str, value: object, note: str) -> None:
        key = (name, value)
        if key in seen:
            return
        seen.add(key)
        results.append({"name": name, "value": value, "note": note})

    for plugin_name, args in _iter_run_calls(code):
        note = 'Used by run("{}"). Re-check on new images.'.format(plugin_name)
        for key, value in _extract_key_number_pairs(args):
            _emit(key, value, note)

    for arg in _SET_AUTO_THRESHOLD_RE.findall(code):
        note = "Used by setAutoThreshold. Re-check on new images."
        numbers = _NUMBER_RE.findall(arg or "")
        if not numbers:
            continue
        for idx, num in enumerate(numbers, start=1):
            name = "threshold" if len(numbers) == 1 else "threshold_{}".format(idx)
            _emit(name, _parse_number(num), note)

    for body in _SET_MIN_MAX_RE.findall(code):
        note = "Used by setMinAndMax. Re-check on new images."
        numbers = _NUMBER_RE.findall(body or "")
        if not numbers:
            continue
        positional = ("min", "max")
        for idx, num in enumerate(numbers):
            if idx < len(positional):
                name = positional[idx]
            else:
                name = "arg_{}".format(idx + 1)
            _emit(name, _parse_number(num), note)

    return results


def draft_recipe(name: str, description: str, promote: list[str]) -> dict:
    """Assemble a recipe dict from the current session's audit log.

    Does NOT write to disk. Preconditions come from describe_image
    when Fiji is reachable; otherwise a placeholder note is left
    in place so a human reader knows the field was not filled.
    Parameters default to image_specific: true and are flipped to
    false only for names listed in the promote argument. The
    created.timestamp is a UTC ISO 8601 string with a Z suffix.
    """
    promote_set = {str(item) for item in (promote or []) if isinstance(item, str)}
    display_name = str(name or "").strip() or "untitled_recipe"
    description_text = str(description or "").strip()

    folder = active_image.current_export_folder()
    entries = read_audit_log(folder) if folder else []
    workflow = filter_workflow(entries)
    describe_text = _describe_image_text()

    seen_params: set[tuple[str, object]] = set()
    parameters_out: list[dict] = []
    for entry in workflow:
        code = entry.get("code", "")
        for param in extract_parameters(code):
            key = (param["name"], param["value"])
            if key in seen_params:
                continue
            seen_params.add(key)
            param_row = _build_parameter_row(
                param,
                image_specific=(param["name"] not in promote_set),
            )
            parameters_out.append(param_row)

    steps_out: list[dict] = []
    for idx, entry in enumerate(workflow, start=1):
        steps_out.append(_build_step_row(entry, idx))

    preconditions = _build_preconditions(describe_text)
    domain = _infer_domain(description_text, workflow)
    outputs = _build_outputs(workflow)
    known_issues = _build_known_issues(workflow, parameters_out)
    tags = _infer_tags(description_text, workflow, preconditions, domain)
    validation = _build_validation(outputs, workflow)

    recipe = {
        "schema_version": 1,
        "name": display_name,
        "id": _slugify(display_name),
        "description": description_text,
        "domain": domain,
        "difficulty": _infer_difficulty(workflow),
        "preconditions": preconditions,
        "parameters": parameters_out,
        "steps": steps_out,
        "outputs": outputs,
        "known_issues": known_issues,
        "tags": tags,
        "created": {
            "timestamp": _utc_now_iso(),
            "session_id": safety.session_id(),
        },
    }
    if validation:
        recipe["validation"] = validation
    return recipe


def save_recipe_file(recipe: dict) -> str:
    """Write a recipe dict to the configured recipe folder.

    Slug is derived from recipe["name"] — lowercase, every
    non-alphanumeric character replaced with '_'. If a file
    already exists at <slug>.yaml, appends _2, _3, ... until a
    free filename is found. Never overwrites an existing recipe.
    """
    name = ""
    if isinstance(recipe, dict):
        name = str(recipe.get("name") or "").strip()
    if not name:
        name = "untitled_recipe"
    base_slug = _slugify(name) or "untitled_recipe"

    recipes_dir = _recipes_dir()
    recipes_dir.mkdir(parents=True, exist_ok=True)

    target = recipes_dir / "{}.yaml".format(base_slug)
    counter = 2
    while target.exists():
        target = recipes_dir / "{}_{}.yaml".format(base_slug, counter)
        counter += 1

    payload = recipe if isinstance(recipe, dict) else {}
    with open(target, "w", encoding="utf-8") as fh:
        yaml.safe_dump(payload, fh, sort_keys=False, allow_unicode=True)
    return str(target.resolve())


# ---- helpers ----------------------------------------------------------


def _recipes_dir() -> Path:
    configured = os.environ.get(_USER_RECIPES_ENV, "").strip()
    if configured:
        return Path(configured).expanduser()
    return _DEFAULT_RECIPES_DIR


def _describe_image_text() -> str | None:
    """Return the current describe_image paragraph, or None when unavailable."""
    try:
        from . import describe_image as _desc
    except ImportError:
        return None
    try:
        paragraph = _desc.describe_image()
    except Exception:
        return None
    if not isinstance(paragraph, str):
        return None
    text = paragraph.strip()
    if not text or text.lower().startswith("describe_image:"):
        return None
    return text


def _build_parameter_row(param: dict, image_specific: bool) -> dict:
    """Expand one extracted parameter into the richer recipe schema."""
    name = str(param.get("name") or "").strip() or "parameter"
    value = param.get("value")
    row = {
        "name": name,
        "label": _humanise_name(name),
        "type": _parameter_type(value),
        "default": value,
        "value": value,
        "image_specific": bool(image_specific),
        "note": str(param.get("note") or "").strip(),
        "description": _parameter_description(name, value, image_specific, str(param.get("note") or "")),
    }
    range_hint = _parameter_range(name, value)
    if range_hint is not None:
        row["range"] = range_hint
    return row


def _build_step_row(entry: dict, idx: int) -> dict:
    """Convert one audit-log entry into a recipe step."""
    source = str(entry.get("source") or "macro").strip().lower()
    code = str(entry.get("code") or "")
    step = {
        "id": idx,
        "description": _step_description(code, source, idx),
        "source": source,
        "notes": "Captured automatically from a successful ImageJAI session.",
    }
    if source == "script":
        step["type"] = "script"
        step["code"] = code
        language = str(entry.get("language") or "").strip().lower()
        step["language"] = language or "groovy"
    else:
        step["type"] = "macro"
        step["macro"] = code
    return step


def _workflow_text(workflow: list[dict]) -> str:
    """Concatenate all workflow code into one lowercase string."""
    parts = []
    for entry in workflow or []:
        if not isinstance(entry, dict):
            continue
        code = entry.get("code")
        if isinstance(code, str) and code.strip():
            parts.append(code.lower())
    return "\n".join(parts)


def _infer_domain(description_text: str, workflow: list[dict]) -> str:
    """Pick a broad recipe domain from the workflow operations."""
    haystack = "{}\n{}".format(description_text or "", _workflow_text(workflow))
    text = haystack.lower()
    if any(token in text for token in ("correct 3d drift", "register", "registration", "stackreg", "sift")):
        return "registration"
    if any(token in text for token in ("trackmate", "tracking", "track")):
        return "tracking"
    if any(token in text for token in ("analyze particles", "set measurements", "measure", "results")):
        if any(token in text for token in ("threshold", "convert to mask", "watershed", "make binary")):
            return "segmentation"
        return "measurement"
    if any(token in text for token in ("threshold", "convert to mask", "watershed", "make binary", "segment")):
        return "segmentation"
    if any(token in text for token in ("gaussian blur", "median", "subtract background", "enhance contrast", "crop", "smooth", "sharpen")):
        return "preprocessing"
    if any((entry.get("source") == "script") for entry in workflow if isinstance(entry, dict)):
        return "automation"
    return "general"


def _infer_difficulty(workflow: list[dict]) -> str:
    """Estimate recipe difficulty from workflow size and tool mix."""
    count = len(workflow or [])
    text = _workflow_text(workflow)
    has_script = any(
        isinstance(entry, dict) and str(entry.get("source") or "").lower() == "script"
        for entry in workflow or []
    )
    if any(token in text for token in ("trackmate", "correct 3d drift", "stardist")):
        return "intermediate"
    if has_script or count >= 5:
        return "intermediate"
    return "beginner"


def _build_outputs(workflow: list[dict]) -> list[dict]:
    """Infer likely outputs from the saved workflow steps."""
    text = _workflow_text(workflow)
    outputs: list[dict] = []
    if any(token in text for token in ("analyze particles", "set measurements", 'run("measure"', 'roimanager("measure")', 'saveas("results"')):
        outputs.append({
            "type": "results_table",
            "description": "Measurement or counting table generated by the workflow.",
        })
    if any(token in text for token in ("duplicate", "convert to mask", "make binary", "watershed", "correct 3d drift", "rgb color", "gaussian blur", "median", "subtract background", "enhance contrast", "smooth", "sharpen", "saveas(", "save(")):
        outputs.append({
            "type": "image",
            "description": "Processed image, mask, or derived ImageJ window created by the workflow.",
        })
    if any(
        isinstance(entry, dict) and str(entry.get("source") or "").lower() == "script"
        for entry in workflow or []
    ):
        outputs.append({
            "type": "log",
            "description": "Script-side status messages or diagnostic output in the ImageJ Log window.",
        })
    if not outputs:
        outputs.append({
            "type": "workflow",
            "description": "Captured ImageJ workflow steps ready to replay on a similar image.",
        })
    return outputs


def _build_known_issues(workflow: list[dict], parameters: list[dict]) -> list[dict]:
    """Generate a few conservative known-issue notes for the saved recipe."""
    text = _workflow_text(workflow)
    issues: list[dict] = []
    if parameters:
        issues.append({
            "condition": "Parameter values were captured from one successful session",
            "symptom": "The workflow runs on a new image but over- or under-processes it",
            "fix": "Review every image_specific parameter and retune it before trusting the result on new data.",
        })
    if any(token in text for token in ("threshold", "convert to mask", "make binary", "watershed")):
        issues.append({
            "condition": "Thresholding settings are reused on images with different contrast or background",
            "symptom": "Foreground is over-segmented, under-segmented, or merged into one mask",
            "fix": "Inspect the mask, then adjust the threshold-related parameters or choose a different threshold method.",
        })
    if any(token in text for token in ("correct 3d drift", "register", "registration", "stackreg")):
        issues.append({
            "condition": "Registration is driven from a channel with moving structures or weak landmarks",
            "symptom": "Frames stay misaligned or the correction introduces edge artifacts",
            "fix": "Use a stable reference channel or frame content, then re-run the registration step.",
        })
    if not issues:
        issues.append({
            "condition": "The workflow is replayed on a different image without review",
            "symptom": "A step completes successfully but the output no longer matches the intended analysis",
            "fix": "Run the recipe step by step the first time and verify the intermediate outputs before batch reuse.",
        })
    return issues[:3]


def _infer_tags(description_text: str, workflow: list[dict], preconditions: dict, domain: str) -> list[str]:
    """Generate searchable tags from the workflow and image summary."""
    tags: list[str] = []
    if domain and domain != "general":
        tags.append(domain)

    text = "{}\n{}".format(description_text or "", _workflow_text(workflow)).lower()
    for tag, keywords in (
        ("threshold", ("threshold", "convert to mask", "make binary")),
        ("watershed", ("watershed",)),
        ("analyze_particles", ("analyze particles",)),
        ("measurement", ("measure", "set measurements", "results")),
        ("registration", ("correct 3d drift", "register", "registration", "stackreg", "sift")),
        ("tracking", ("trackmate", "tracking", "track")),
        ("preprocessing", ("gaussian blur", "median", "subtract background", "enhance contrast", "crop")),
    ):
        if any(keyword in text for keyword in keywords):
            tags.append(tag)
    if any(
        isinstance(entry, dict) and str(entry.get("source") or "").lower() == "script"
        for entry in workflow or []
    ):
        tags.append("script")

    image_types = preconditions.get("image_type")
    if isinstance(image_types, list):
        for image_type in image_types:
            token = _slugify(str(image_type)).replace("_bit", "-bit")
            if token:
                tags.append(token)

    min_channels = preconditions.get("min_channels")
    if min_channels == 1:
        tags.append("single_channel")
    elif isinstance(min_channels, int) and min_channels > 1:
        tags.append("multi_channel")

    return _dedupe_preserve(tags)


def _build_validation(outputs: list[dict], workflow: list[dict]) -> list[dict]:
    """Create generic validation checks suited to the inferred outputs."""
    checks: list[dict] = []
    output_types = {str(item.get("type") or "") for item in outputs if isinstance(item, dict)}
    if "results_table" in output_types:
        checks.append({
            "check": "Results table exists and contains plausible rows",
            "method": "Inspect the Results window after the final measurement step.",
        })
    if "image" in output_types:
        checks.append({
            "check": "Derived image or mask matches the intended structures",
            "method": "Visually compare the processed image to the source before batch reuse.",
        })
    if any(
        isinstance(entry, dict) and str(entry.get("source") or "").lower() == "script"
        for entry in workflow or []
    ):
        checks.append({
            "check": "Script step completed without ImageJ errors",
            "method": "Read the ImageJ Log window and confirm the expected output window or table appeared.",
        })
    return checks


def _iter_run_calls(code: str) -> list[tuple[str, str]]:
    """Return (plugin_name, args_string) pairs for every run(...) call."""
    results: list[tuple[str, str]] = []
    for match in _RUN_CALL_RE.finditer(code):
        plugin = match.group(1)
        args = match.group(2) or ""
        results.append((plugin, args))
    return results


def _extract_key_number_pairs(args: str) -> list[tuple[str, object]]:
    """Pull (key, number) pairs from key=value tokens inside a run arg string."""
    out: list[tuple[str, object]] = []
    if not isinstance(args, str) or not args:
        return out
    for match in _KEY_VALUE_RE.finditer(args):
        key = match.group(1).strip()
        value_str = match.group(2).strip()
        for num in _NUMBER_RE.findall(value_str):
            out.append((key, _parse_number(num)))
    return out


def _parse_number(text: str) -> object:
    """Parse a numeric literal as int when it has no '.', float otherwise."""
    try:
        if "." in text:
            return float(text)
        return int(text)
    except (TypeError, ValueError):
        try:
            return float(text)
        except (TypeError, ValueError):
            return 0


def _humanise_name(name: str) -> str:
    """Convert a snake_case parameter name into a short label."""
    text = str(name or "").strip().replace("_", " ")
    return text[:1].upper() + text[1:] if text else "Parameter"


def _parameter_type(value: object) -> str:
    """Return the recipe parameter type for a captured literal."""
    if isinstance(value, bool):
        return "boolean"
    if isinstance(value, (int, float)):
        return "numeric"
    return "text"


def _parameter_range(name: str, value: object) -> list[object] | None:
    """Guess a broad numeric range for a parameter from its name and value."""
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    numeric = float(value)
    lname = str(name or "").lower()
    if any(token in lname for token in ("circularity", "fraction", "ratio")):
        lower, upper = 0.0, 1.0
    elif numeric >= 0:
        lower = 0.0
        if any(token in lname for token in ("sigma", "radius", "blur", "smooth")):
            upper = max(5.0, numeric * 3.0)
        elif any(token in lname for token in ("threshold", "min", "max", "size", "area", "width", "height")):
            upper = max(10.0, numeric * 4.0)
        else:
            upper = max(10.0, numeric * 3.0)
    else:
        lower = numeric * 2.0
        upper = abs(numeric) * 2.0
    if isinstance(value, int):
        return [int(round(lower)), int(round(upper))]
    return [round(lower, 3), round(upper, 3)]


def _parameter_description(name: str, value: object, image_specific: bool, note: str) -> str:
    """Build a concise parameter description for the saved recipe."""
    pieces = []
    if note:
        pieces.append(note.rstrip("."))
    pieces.append("Captured default: {}.".format(value))
    if image_specific:
        pieces.append("Treat this as image-specific until a user promotes it.")
    else:
        pieces.append("User approved this parameter for reuse.")
    return " ".join(pieces)


def _clean_plugin_name(name: str) -> str:
    """Strip Fiji ellipsis and surrounding whitespace from a plugin name."""
    return str(name or "").strip().rstrip(".").strip()


def _step_description(code: str, source: str, idx: int) -> str:
    """Generate a readable step description from captured macro or script code."""
    if source == "script":
        return "Run captured script step {}".format(idx)
    text = str(code or "")
    lower = text.lower()
    if "setautothreshold" in lower and "convert to mask" in lower:
        return "Threshold the image and convert it to a mask"
    if "analyze particles" in lower:
        return "Count and measure particles"
    if "correct 3d drift" in lower:
        return "Run Correct 3D Drift"
    plugins = [_clean_plugin_name(name) for name, _args in _iter_run_calls(text)]
    plugins = [name for name in plugins if name]
    if plugins:
        first = plugins[:2]
        if len(first) == 1:
            return "Run {}".format(first[0])
        return "Run {} then {}".format(first[0], first[1])
    calls = [call for call in _CALL_NAME_RE.findall(text) if call not in {"run", "print"}]
    if calls:
        return "Execute {} step".format(calls[0])
    return "Captured workflow step {}".format(idx)


def _extract_count(text: str | None, pattern: re.Pattern[str]) -> int | None:
    """Parse 'single X' or 'N Xs' fragments from describe_image output."""
    if not isinstance(text, str):
        return None
    match = pattern.search(text)
    if not match:
        return None
    if match.group(1) and match.group(1).lower().startswith("single"):
        return 1
    if match.lastindex and match.lastindex >= 2 and match.group(2):
        try:
            return int(match.group(2))
        except ValueError:
            return None
    return None


def _dedupe_preserve(values: list[str]) -> list[str]:
    """Deduplicate a string list while preserving order."""
    out: list[str] = []
    seen: set[str] = set()
    for value in values:
        text = str(value or "").strip()
        if not text or text in seen:
            continue
        seen.add(text)
        out.append(text)
    return out


def _slugify(name: str) -> str:
    """Lowercase and replace every non-alphanumeric run with a single '_'."""
    text = re.sub(r"[^A-Za-z0-9]+", "_", str(name or "").strip().lower())
    return text.strip("_")


def _utc_now_iso() -> str:
    """Return the current UTC time as an ISO 8601 string ending in Z."""
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _build_preconditions(describe_text: str | None) -> dict:
    """Fill preconditions from describe_image when reachable, else leave a note."""
    placeholder = {
        "notes": (
            "describe_image did not return a usable paragraph this session - "
            "run it manually and paste the result here before the recipe is "
            "published."
        )
    }
    if not isinstance(describe_text, str) or not describe_text.strip():
        return placeholder
    text = describe_text.strip()
    preconditions: dict = {"notes": text}

    image_type = _IMAGE_TYPE_RE.search(text)
    if image_type:
        token = image_type.group(1)
        if isinstance(token, str) and token:
            canonical = token.upper() if token.upper() == "RGB" else token.lower()
            preconditions["image_type"] = [canonical]

    channels = _extract_count(text, _CHANNEL_COUNT_RE)
    if channels is not None:
        preconditions["min_channels"] = channels

    slices = _extract_count(text, _Z_COUNT_RE)
    if slices is not None:
        preconditions["needs_stack"] = slices > 1

    frames = _extract_count(text, _FRAME_COUNT_RE)
    if frames is not None:
        preconditions["needs_time"] = frames > 1

    if "uncalibrated" in text.lower():
        preconditions["needs_calibration"] = False

    return preconditions
