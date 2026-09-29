"""Declarative model and action table for the console left rail.

Port of ``src/main/java/imagejai/ui/LeftRail.java`` into pure logic so the
Textual console can render the same rail without a Swing widget tree. The Java
class mixes the *what* (sections, labels, tooltips, macro strings, status text)
with the *how* (SwingWorker, JPopupMenu, EDT marshalling). Only the *what* is
portable, so this module keeps the rail as data plus a dispatch table of small
callables, and leaves every timer, popup, and focus concern to the TUI.

Why a dispatch table instead of methods on a widget: the console needs to run
rail actions from a worker thread, from a slash command, and from tests. A
plain ``{item_id: callable}`` map is the only shape that serves all three.

Feature ids (docs/console/FEATURES_embedded_swing_console.md PART 1):
S2.3-S2.15 rail sections/actions/status, S2.25-S2.26 governance and receipts
pane content rules, S1.15-S1.16 suggestion and clarification chips,
S1.25-S1.26 ROI flash and image focus.
"""
from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Iterable, Mapping, Sequence

__all__ = [
    "PreconditionError",
    "RailItem",
    "RailSection",
    "RailResult",
    "SECTIONS",
    "ACTIONS",
    "rail_items",
    "find_item",
    "dispatch",
    "item_state",
    "short_status",
    "readable_message",
    "is_stack",
    "sanitize_slug",
    "wip_note_path",
    "write_wip_note",
    "new_wip",
    "commands_popup",
    "new_agent_chat",
    "console_chats_popup",
    "close_all_images",
    "reset_roi_manager",
    "z_project_max",
    "audit_results",
    "save_recipe_prompt_action",
    "run_recipe_action",
    "highlight_roi_payload",
    "focus_image_payload",
    "highlight_roi",
    "focus_image",
    "Chip",
    "SuggestionEngine",
    "suggestion_chips",
    "clarification_chips",
    "chip_href",
    "parse_chip_href",
    "resolve_chip",
    "jaro",
    "jaro_winkler",
    "normalise",
    "sort_tokens",
    "audit_counter_line",
    "governance_model",
    "receipts_rows",
    "receipt_detail",
]


# --------------------------------------------------------------------------
# status line — LeftRail.java:1137, :1193, :1204, and the 2500 ms blank timer
# at LeftRail.java:160
# --------------------------------------------------------------------------

STATUS_MAX_CHARS = 28
STATUS_TRUNCATE_CHARS = 25
STATUS_CLEAR_MS = 2500
STATUS_IDLE = " "


def short_status(text: str | None) -> str:
    """Rail label text for a status message (LeftRail.java:1193).

    The rail label is one line wide, so long messages are cut at 25 chars
    plus an ellipsis; the caller keeps the full text as the tooltip.
    """
    if text is None:
        return STATUS_IDLE
    compact = text.strip()
    if len(compact) <= STATUS_MAX_CHARS:
        return compact if compact else STATUS_IDLE
    return compact[:STATUS_TRUNCATE_CHARS] + "..."


def readable_message(error: BaseException | str | None) -> str:
    """Human-readable text for an exception (LeftRail.java:1204).

    Walks to the root cause because the interesting message is usually on
    the wrapped IOError, and falls back to the class name so the status line
    is never blank.
    """
    if error is None:
        return ""
    if isinstance(error, str):
        return error
    message = str(error).strip()
    if not message:
        cause: BaseException = error
        seen: set[int] = set()
        while cause.__cause__ is not None and id(cause) not in seen:
            seen.add(id(cause))
            cause = cause.__cause__
        message = str(cause).strip()
    return message if message else type(error).__name__


class PreconditionError(RuntimeError):
    """A rail action refused to run because Fiji is not in the right state.

    Kept distinct from a failure so the status line can say "Open a stack
    first." instead of an error (LeftRail.java:1083, :1343).
    """


# --------------------------------------------------------------------------
# Fiji hotlines — LeftRail.java:67-70, :350
# --------------------------------------------------------------------------

CLOSE_ALL_MACRO = "run('Close All');"
RESET_ROI_MACRO = "roiManager('Reset');"
Z_PROJECT_MACRO = "run('Z Project...', 'projection=[Max Intensity]');"


def is_stack(info: Mapping[str, Any] | None) -> bool:
    """True when get_image_info describes a stack (LeftRail.java:1177)."""
    if not isinstance(info, Mapping):
        return False
    if bool(info.get("isStack")):
        return True
    try:
        slices = int(info.get("slices", 1) or 1)
    except (TypeError, ValueError):
        slices = 1
    try:
        frames = int(info.get("frames", 1) or 1)
    except (TypeError, ValueError):
        frames = 1
    return slices > 1 or frames > 1


# --------------------------------------------------------------------------
# Fiji facade adapters
#
# agent.console.fiji.FijiConnection exposes a subset of agent.ij. The rail
# needs get_image_info, run_script, and raw command dicts (macro "source"
# tagging), so these helpers use the public method when it exists and fall
# back to the connection's generic call. They raise instead of degrading
# silently, so a missing facade method is visible.
# --------------------------------------------------------------------------


def _result(reply: Any) -> dict:
    """Unwrap the ``{"ok": true, "result": {...}}`` envelope agent.ij returns."""
    if isinstance(reply, Mapping):
        inner = reply.get("result")
        if isinstance(inner, Mapping):
            return dict(inner)
        return dict(reply)
    return {}


def _invoke(conn: Any, names: Sequence[str], *args: Any) -> Any:
    for name in names:
        method = getattr(conn, name, None)
        if callable(method):
            return method(*args)
    raise AttributeError(
        f"Fiji facade has none of {list(names)}; add it to "
        "agent/console/fiji.py instead of working around it here"
    )


def fetch_image_info(conn: Any) -> dict:
    """Read get_image_info through the facade (LeftRail.java:373)."""
    return _result(_invoke(conn, ("image_info", "get_image_info")))


def send_command(conn: Any, payload: Mapping[str, Any]) -> Any:
    """Send a raw command dict so macro "source" tags survive.

    LeftRail tags every run with a source id (``rail:my-macros`` and friends,
    MacroLibrary.java:35); execute_macro(code) alone cannot carry that.
    """
    return _invoke(conn, ("command", "request", "send"), dict(payload))


def execute_macro(conn: Any, code: str, source: str | None = None) -> Any:
    if source:
        return send_command(
            conn, {"command": "execute_macro", "code": code, "source": source}
        )
    return _invoke(conn, ("execute_macro",), code)


def run_script(conn: Any, language: str, code: str, source: str | None = None) -> Any:
    payload: dict[str, Any] = {
        "command": "run_script",
        "language": language,
        "code": code,
    }
    if source:
        payload["source"] = source
    try:
        return send_command(conn, payload)
    except AttributeError:
        return _invoke(conn, ("run_script",), code, language)


# --------------------------------------------------------------------------
# rail model
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class RailItem:
    """One clickable rail row, as data the TUI renders however it likes."""

    id: str
    label: str
    glyph: str
    help: str
    action: str
    section: str = ""


@dataclass(frozen=True)
class RailSection:
    """A muted bold heading plus its items (LeftRail.java:332)."""

    id: str
    title: str
    items: tuple[RailItem, ...]


@dataclass
class RailResult:
    """Outcome of a rail action.

    ``status`` is the rail status-line text, ``prompt`` is text to inject into
    the agent conversation, ``log`` mirrors the Java ``[ImageJAI-Term]`` log
    line, and ``data`` carries popup models for list-style items.
    """

    ok: bool = True
    status: str = ""
    log: str = ""
    prompt: str = ""
    data: Any = None
    skipped: bool = False


def _section(section_id: str, title: str, rows: Sequence[tuple[str, str, str, str, str]]) -> RailSection:
    return RailSection(
        id=section_id,
        title=title,
        items=tuple(
            RailItem(id=i, label=lab, glyph=g, help=h, action=a, section=section_id)
            for i, lab, g, h, a in rows
        ),
    )


# Sections and item order follow buildSessionSection / buildAgentSection /
# buildHotlineSection / buildGuidanceSection (LeftRail.java:212-401). The macro
# buttons and the session-history list really do live in the "Fiji hotlines"
# section in the Java build order, so they stay there.
SECTIONS: tuple[RailSection, ...] = (
    _section(
        "session",
        "Session",
        (
            (
                "session.new_wip",
                "New WIP",
                "\u2295",
                "Create a work-in-progress note",
                "new_wip",
            ),
        ),
    ),
    _section(
        "agent",
        "Agent",
        (
            (
                "agent.commands",
                "Commands",
                "/",
                "Show agent slash commands",
                "commands_popup",
            ),
            (
                "agent.new_chat",
                "New agent chat",
                "\u21bb",
                "Clear the agent chat; restart only if clear is not confirmed",
                "new_agent_chat",
            ),
            (
                "agent.console_chats",
                "Console chats",
                "\u2261",
                "Resume chats saved by the imagejai terminal",
                "console_chats_popup",
            ),
        ),
    ),
    _section(
        "hotlines",
        "Fiji hotlines",
        (
            (
                "fiji.close_all",
                "Close all images",
                "\u2715",
                CLOSE_ALL_MACRO,
                "close_all_images",
            ),
            (
                "fiji.reset_roi",
                "Reset ROI Manager",
                "\u25ef",
                RESET_ROI_MACRO,
                "reset_roi_manager",
            ),
            (
                "fiji.z_project",
                "Z-project (max)",
                "\u25a4",
                Z_PROJECT_MACRO,
                "z_project_max",
            ),
            (
                "macros.my",
                "My Macros",
                "\u2699",
                "Scan the ImageJ/Fiji folder for .ijm files",
                "my_macros_popup",
            ),
            (
                "macros.session",
                "Session Macros",
                "\u2699",
                "Run macros and scripts created this session",
                "session_macros_popup",
            ),
            (
                "macros.all",
                "All Macros",
                "\u2699",
                "Show ImageJ, session, and ImageJAI-saved macros",
                "all_macros_popup",
            ),
            (
                "macros.save",
                "Save Macro",
                "\u2b07",
                "Save a session macro to ImageJAI/macros",
                "save_macro_popup",
            ),
        ),
    ),
    _section(
        "guidance",
        "Guidance",
        (
            (
                "recipe.run",
                "Run recipe...",
                "\u25b6",
                "Run an agent recipe",
                "run_recipe_popup",
            ),
            (
                "recipe.save",
                "Save recipe",
                "\u2b07",
                "Ask the agent to save this session to Fiji.app/ImageJAI/recipes",
                "save_recipe_prompt_action",
            ),
            (
                "results.audit",
                "Audit my results",
                "\u2713",
                "Audit the current Results table",
                "audit_results",
            ),
        ),
    ),
)


def rail_items() -> list[RailItem]:
    """Flatten SECTIONS in render order."""
    return [item for section in SECTIONS for item in section.items]


def find_item(item_id: str) -> RailItem:
    for item in rail_items():
        if item.id == item_id:
            return item
    raise KeyError(item_id)


# --------------------------------------------------------------------------
# enable / tooltip rules — LeftRail.java:403 (updateAgentButtons)
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class ItemState:
    enabled: bool
    tooltip: str


def item_state(
    item_id: str, *, session_alive: bool = False, has_commands: bool = False
) -> ItemState:
    """Enabled flag and tooltip for one item (LeftRail.java:403).

    Only three items are gated on a live agent session; everything else is
    always clickable because it talks to Fiji, not to the agent.
    """
    item = find_item(item_id)
    if item_id == "agent.commands":
        live = bool(session_alive and has_commands)
        return ItemState(live, "Show agent slash commands" if live else "no command list")
    if item_id in ("agent.new_chat", "recipe.save"):
        if not session_alive:
            return ItemState(False, "No embedded agent running")
        return ItemState(True, item.help)
    return ItemState(True, item.help)


# --------------------------------------------------------------------------
# S2.3 New WIP — LeftRail.java:559, :1289, :1300
# --------------------------------------------------------------------------

WIP_TEMPLATE = "# <slug>\n\n## Goal\n\n## Steps\n\n## Decisions\n\n## Open questions\n"
WIP_DIRNAME = "work_in_progress"
_SLUG_BAD = re.compile(r"[^a-z0-9._-]+")


def sanitize_slug(raw: str | None) -> str:
    """Lowercase slug with only ``[a-z0-9._-]`` (LeftRail.java:1289)."""
    if raw is None:
        return ""
    slug = _SLUG_BAD.sub("-", raw.strip().lower())
    return slug.strip("-")


def wip_note_path(workspace: str | Path | None, slug: str) -> Path:
    """``<workspace>/agent/work_in_progress/<slug>.md`` (LeftRail.java:578).

    The Java rail is handed the *plugin* workspace and appends ``agent/``; a
    workspace that already is the ``agent`` directory must not get a second
    one, which is the shape the console always has.
    """
    base = Path(workspace) if workspace else Path(".")
    root = base if base.name == "agent" else base / "agent"
    return root / WIP_DIRNAME / f"{slug}.md"


def write_wip_note(
    workspace: str | Path | None, slug: str, template: str | None = None
) -> tuple[Path, bool]:
    """Create the WIP note if missing; return ``(path, created)``.

    Existing notes are reused, never overwritten (LeftRail.java:581).
    """
    path = wip_note_path(workspace, slug)
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        return path, False
    body = (template if template is not None else WIP_TEMPLATE).replace("<slug>", slug)
    path.write_text(body, encoding="utf-8")
    return path, True


def wip_prompt(path: str | Path) -> str:
    """The fixed prompt injected after a WIP note is created."""
    return f"Start new WIP: read `{path}` and help me scope it."


def new_wip(
    conn: Any = None,
    *,
    slug: str,
    workspace: str | Path | None = None,
    session_alive: bool = False,
    template: str | None = None,
) -> RailResult:
    clean = sanitize_slug(slug)
    if not clean:
        return RailResult(ok=False, status="Enter a slug")
    try:
        path, created = write_wip_note(workspace, clean, template)
    except OSError as exc:
        message = readable_message(exc)
        return RailResult(
            ok=False, status=message, log=f"New WIP failed: {message}"
        )
    absolute = path.resolve()
    log = (
        f"Created WIP note: {absolute}" if created else f"Reusing existing WIP note: {absolute}"
    )
    if session_alive:
        return RailResult(
            status="WIP prompt sent", log=log, prompt=wip_prompt(absolute), data=absolute
        )
    return RailResult(status="WIP note created", log=log, data=absolute)


# --------------------------------------------------------------------------
# S2.4 Commands popup — LeftRail.java:421, :495
# --------------------------------------------------------------------------

NO_COMMAND_LIST = "No command list"
NO_AGENT_RUNNING = "No agent running"


def commands_popup(
    conn: Any = None,
    *,
    builtin: Iterable[Mapping[str, str] | tuple[str, str]] = (),
    user: Iterable[Mapping[str, str] | tuple[str, str]] = (),
    session_alive: bool = False,
) -> RailResult:
    """Grouped "Built-in" / "User" slash-command menu (LeftRail.java:421).

    The Java popup is disabled when there is no live session and shows only
    the built-in group when the user folder is empty; both groups are split by
    a separator entry.
    """
    if not session_alive:
        return RailResult(ok=False, status=NO_AGENT_RUNNING, data={"groups": []})
    builtins = _command_entries(builtin)
    if not builtins:
        return RailResult(ok=False, status=NO_COMMAND_LIST, data={"groups": []})
    groups = [{"title": "Built-in", "items": builtins}]
    users = _command_entries(user)
    if users:
        groups.append({"title": "User", "items": users, "separator_before": True})
    return RailResult(data={"groups": groups})


def _command_entries(
    entries: Iterable[Mapping[str, str] | tuple[str, str]]
) -> list[dict[str, str]]:
    out: list[dict[str, str]] = []
    for entry in entries or ():
        if isinstance(entry, Mapping):
            command = str(entry.get("command", "")).strip()
            description = str(entry.get("description", "") or "")
        else:
            command = str(entry[0]).strip()
            description = str(entry[1]) if len(entry) > 1 else ""
        if command:
            out.append({"command": command, "description": description})
    return out



# --------------------------------------------------------------------------
# S2.5 New agent chat — LeftRail.java:520
# --------------------------------------------------------------------------

def new_agent_chat(conn: Any = None, *, session_alive: bool = False) -> RailResult:
    """Ask the console conversation to clear itself.

    The Swing rail wrote ``/clear`` into a PTY, waited 1000 ms, read 40
    scrollback lines, and restarted the terminal when the agent's clear
    pattern did not appear (LeftRail.java:533). The console has no embedded
    terminal and owns its own conversation, so only the intent survives: the
    scrollback probe and the PTY relaunch are deliberately not ported.
    """
    if not session_alive:
        return RailResult(ok=False, status=NO_AGENT_RUNNING)
    return RailResult(status="Sent /clear", prompt="/clear")


# --------------------------------------------------------------------------
# S2.6 Console chats — LeftRail.java:443 / ConsoleSessionStore.java:41
# --------------------------------------------------------------------------

NO_CONSOLE_CHATS = "No console chats yet"
_SESSION_ID = re.compile(r"^[A-Za-z0-9-]{1,64}$")
DEFAULT_SESSION_TITLE = "New session"


def console_chats_popup(
    conn: Any = None, *, sessions: Iterable[Mapping[str, Any]] = ()
) -> RailResult:
    """List saved console sessions, newest first (ConsoleSessionStore.java:41).

    Ids are filtered to ``[A-Za-z0-9-]{1,64}`` because the id becomes a
    command-line argument; blank titles fall back to "New session".
    """
    rows: list[dict[str, str]] = []
    for raw in sessions or ():
        session_id = str(raw.get("id", "") or "")
        if not _SESSION_ID.match(session_id):
            continue
        title = str(raw.get("title", "") or "").strip() or DEFAULT_SESSION_TITLE
        provider = str(raw.get("provider", "") or "").strip()
        try:
            updated = float(raw.get("updated", 0) or 0)
        except (TypeError, ValueError):
            updated = 0.0
        rows.append(
            {
                "id": session_id,
                "title": title,
                "provider": provider,
                "label": f"{title}  [{provider}]" if provider else title,
                "tooltip": f"Resume ImageJAI Console session {session_id}",
                "updated": updated,
            }
        )
    rows.sort(key=lambda row: row["updated"], reverse=True)
    if not rows:
        return RailResult(ok=False, status=NO_CONSOLE_CHATS, data={"items": []})
    return RailResult(data={"items": rows})


# --------------------------------------------------------------------------
# S2.7 hotline actions — LeftRail.java:350, :1069
# --------------------------------------------------------------------------


def _hotline(label: str, run: Callable[[], Any]) -> RailResult:
    try:
        run()
    except PreconditionError as exc:
        message = str(exc)
        return RailResult(
            ok=False, skipped=True, status=message, log=f"Hotline skipped: {label} - {message}"
        )
    except Exception as exc:  # noqa: BLE001 - mirrors the Java catch-all
        message = readable_message(exc)
        return RailResult(ok=False, status=message, log=f"Hotline failed: {label} - {message}")
    return RailResult(status=f"Done: {label}", log=f"Hotline completed: {label}")


def close_all_images(conn: Any) -> RailResult:
    return _hotline("Close all images", lambda: execute_macro(conn, CLOSE_ALL_MACRO))


def reset_roi_manager(conn: Any) -> RailResult:
    return _hotline("Reset ROI Manager", lambda: execute_macro(conn, RESET_ROI_MACRO))


def z_project_max(conn: Any) -> RailResult:
    def run() -> None:
        if not is_stack(fetch_image_info(conn)):
            raise PreconditionError("Open a stack first.")
        execute_macro(conn, Z_PROJECT_MACRO)

    return _hotline("Z-project (max)", run)


# --------------------------------------------------------------------------
# S2.13 Save recipe prompt — LeftRail.java:931
# --------------------------------------------------------------------------


def save_recipe_prompt(recipes_dir: str | Path) -> str:
    """The fixed prompt the rail injects (LeftRail.java:942, verbatim)."""
    target = str(Path(recipes_dir).resolve())
    return (
        "Save this session as an ImageJAI recipe. "
        "Start the /save-recipe flow if your agent supports it. "
        "Use the same YAML schema as the bundled recipes in `recipes/`, "
        f"but save the approved recipe in `{target}` "
        "(Fiji.app/ImageJAI/recipes), not in the bundled agent recipes folder. "
        "Ask me for a short display name, a one-line description, and which "
        "numeric parameters should be reusable. Keep every other literal "
        "number marked image_specific: true. Show me the draft YAML before "
        "writing it, then tell me the saved filename."
    )


def save_recipe_prompt_action(
    conn: Any = None, *, recipes_dir: str | Path, session_alive: bool = False
) -> RailResult:
    if not session_alive:
        return RailResult(ok=False, status=NO_AGENT_RUNNING)
    try:
        Path(recipes_dir).mkdir(parents=True, exist_ok=True)
    except OSError as exc:
        message = readable_message(exc)
        return RailResult(
            ok=False, status=message, log=f"Save recipe prompt failed: {message}"
        )
    target = str(Path(recipes_dir).resolve())
    return RailResult(
        status="Recipe prompt sent",
        prompt=save_recipe_prompt(recipes_dir),
        log=f"Sent recipe-save prompt for {target}",
    )


# --------------------------------------------------------------------------
# S2.12 Run recipe — LeftRail.java:875 (process plumbing stays in the TUI)
# --------------------------------------------------------------------------


def run_recipe_action(
    conn: Any = None,
    *,
    recipe: Any,
    agent_dir: str | Path,
    port: int = 7746,
    user_recipes_dir: str | Path | None = None,
    python: str | None = None,
    env: Mapping[str, str] | None = None,
) -> RailResult:
    """Build the ``run_recipe.py`` launch plan; the TUI streams the output.

    Delegates to :func:`agent.console.macros.recipe_run_command` so the
    command, cwd, and recipe environment live in one place.
    """
    from .macros import recipe_run_command

    plan = recipe_run_command(
        recipe,
        agent_dir,
        port=port,
        user_recipes_dir=user_recipes_dir,
        python=python,
        env=env,
    )
    return RailResult(status="Running recipe...", data=plan)


RECIPE_DONE = "Recipe done"
RECIPE_FAILED = "Recipe failed"


def recipe_finish_status(exit_code: int) -> str:
    """Terminal status line for a recipe run (LeftRail.java:919)."""
    return RECIPE_DONE if exit_code == 0 else RECIPE_FAILED


# --------------------------------------------------------------------------
# S2.14 Audit my results — LeftRail.java:964
# --------------------------------------------------------------------------

_CALIBRATION = re.compile(r"([0-9.]+)\s+(.+)/px")
AUDIT_PROMPT_PREFIX = "Audit my results:"


def parse_bit_depth(type_string: str | None) -> int | None:
    """8 / 16 / 32 from a Fiji type string (AUDIT_SCRIPT, LeftRail.java:79)."""
    text = str(type_string or "")
    for depth in ("8", "16", "32"):
        if depth in text:
            return int(depth)
    return None


def parse_calibration(calibration: str | None) -> tuple[float | None, str | None]:
    """Pixel size and unit from ``"0.325 micron/px"`` (LeftRail.java:83)."""
    match = _CALIBRATION.match(str(calibration or ""))
    if not match:
        return None, None
    try:
        return float(match.group(1)), match.group(2)
    except ValueError:
        return None, None


def audit_results(
    conn: Any, *, auditor: Callable[..., Mapping[str, Any]] | None = None
) -> RailResult:
    """Audit the Results table and hand the summary to the agent.

    The Java rail shells out to ``python -c <AUDIT_SCRIPT>``; in the console we
    are already in Python, so ``agent.auditor.audit_results`` is imported and
    called directly. ``auditor`` is injectable so tests never import the
    workspace.
    """
    try:
        table = _invoke(conn, ("results", "get_results_table"))
        info = fetch_image_info(conn)
    except Exception as exc:  # noqa: BLE001
        message = readable_message(exc)
        return RailResult(ok=False, status=message, log=f"Audit failed: {message}")

    csv = table
    if isinstance(table, Mapping):
        payload = _result(table)
        csv = payload.get("results_table") or payload.get("csv") or payload.get("table") or ""
    csv = "" if csv is None else str(csv)

    if auditor is None:
        try:
            from auditor import audit_results as auditor  # type: ignore
        except ImportError:
            try:
                from agent.auditor import audit_results as auditor  # type: ignore
            except ImportError as exc:
                message = readable_message(exc)
                return RailResult(
                    ok=False, status=message, log=f"Audit failed: {message}"
                )

    pixel_size, unit = parse_calibration(info.get("calibration"))
    try:
        summary_obj = auditor(
            csv,
            pixel_size=pixel_size,
            unit=unit,
            bit_depth=parse_bit_depth(info.get("type")),
        )
    except Exception as exc:  # noqa: BLE001
        message = readable_message(exc)
        return RailResult(ok=False, status=message, log=f"Audit failed: {message}")

    summary = ""
    if isinstance(summary_obj, Mapping):
        summary = str(summary_obj.get("summary") or "")
    elif summary_obj is not None:
        summary = str(summary_obj)
    summary = summary.strip()
    if not summary:
        return RailResult(ok=False, status="Audit returned no summary.")
    return RailResult(
        status="Audit sent",
        prompt=f"{AUDIT_PROMPT_PREFIX}\n{summary}",
        log="Audit summary sent to agent",
        data=summary,
    )


# --------------------------------------------------------------------------
# S1.25 / S1.26 ROI flash and window focus — ChatView.java:679, :730
#
# The server already implements both as gui_action types, so the console sends
# the same payload agent.ij.gui_highlight_roi / gui_focus send. Flash geometry
# (cyan 0,200,255, width 2, 200 ms x 6 ticks) lives in the Java plugin.
# --------------------------------------------------------------------------

ROI_FLASH_COLOR = (0, 200, 255)
ROI_FLASH_STROKE_WIDTH = 2
ROI_FLASH_INTERVAL_MS = 200
ROI_FLASH_TICKS = 6


def highlight_roi_payload(title: str, rect: Sequence[int]) -> dict:
    x, y, width, height = (int(v) for v in rect)
    return {
        "command": "gui_action",
        "type": "highlight_roi",
        "title": title,
        "roi": [x, y, width, height],
    }


def focus_image_payload(title: str) -> dict:
    return {"command": "gui_action", "type": "focus_image", "title": title}


def highlight_roi(conn: Any, title: str, rect: Sequence[int]) -> RailResult:
    try:
        send_command(conn, highlight_roi_payload(title, rect))
    except Exception as exc:  # noqa: BLE001
        return RailResult(ok=False, status=readable_message(exc))
    return RailResult(status=f"Highlighted {title}")


def focus_image(conn: Any, title: str) -> RailResult:
    try:
        send_command(conn, focus_image_payload(title))
    except Exception as exc:  # noqa: BLE001
        return RailResult(ok=False, status=readable_message(exc))
    return RailResult(status=f"Focused {title}")


# --------------------------------------------------------------------------
# S1.15 suggestion chips — ChatView.java:1310 + IntentMatcher.topK
# --------------------------------------------------------------------------

MAX_SUGGESTION_CHIPS = 3
MAX_TOP_K = 20
SUGGESTION_FLOOR = 0.70
CHIP_DEBOUNCE_MS = 100

_PUNCT = re.compile(r"[^a-z0-9 ]")
_WS = re.compile(r"\s+")


def normalise(text: str | None) -> str:
    """Lowercase, strip punctuation, collapse spaces (IntentMatcher.normalise).

    A leading ``/`` marks a slash command: the command word keeps its slash
    and only the arguments are whitespace-collapsed.
    """
    if text is None:
        return ""
    trimmed = text.strip()
    if trimmed.startswith("/"):
        split = next((i for i, c in enumerate(trimmed) if c.isspace()), -1)
        command = trimmed if split < 0 else trimmed[:split]
        args = "" if split < 0 else _WS.sub(" ", trimmed[split:].strip())
        return command.lower() if not args else f"{command.lower()} {args}"
    lowered = text.lower()
    return _WS.sub(" ", _PUNCT.sub(" ", lowered)).strip()


def sort_tokens(text: str | None) -> str:
    """Word-order-insensitive key (IntentMatcher.sortTokens)."""
    normalised = normalise(text)
    if not normalised:
        return ""
    return " ".join(sorted(normalised.split(" ")))


def jaro(a: str, b: str) -> float:
    """Pure Jaro similarity (FuzzyMatcher.java:jaro)."""
    len1, len2 = len(a), len(b)
    if len1 == 0 and len2 == 0:
        return 1.0
    if len1 == 0 or len2 == 0:
        return 0.0
    window = max(max(len1, len2) // 2 - 1, 0)
    matches1 = [False] * len1
    matches2 = [False] * len2
    matches = 0
    for i in range(len1):
        start = max(0, i - window)
        end = min(i + window + 1, len2)
        for k in range(start, end):
            if matches2[k] or a[i] != b[k]:
                continue
            matches1[i] = matches2[k] = True
            matches += 1
            break
    if matches == 0:
        return 0.0
    transpositions = 0
    k = 0
    for i in range(len1):
        if not matches1[i]:
            continue
        while not matches2[k]:
            k += 1
        if a[i] != b[k]:
            transpositions += 1
        k += 1
    m = float(matches)
    return (m / len1 + m / len2 + (m - transpositions / 2.0) / m) / 3.0


def jaro_winkler(a: str | None, b: str | None) -> float:
    """Jaro-Winkler similarity, case-insensitive (FuzzyMatcher.java:37)."""
    if a is None or b is None:
        return 0.0
    s1, s2 = a.lower(), b.lower()
    if s1 == s2:
        return 1.0
    if not s1 or not s2:
        return 0.0
    score = jaro(s1, s2)
    prefix = 0
    for i in range(min(4, len(s1), len(s2))):
        if s1[i] != s2[i]:
            break
        prefix += 1
    return score + 0.1 * prefix * (1.0 - score)


@dataclass(frozen=True)
class Chip:
    """One suggestion or clarification chip (RankedPhrase.java)."""

    phrase: str
    intent_id: str = ""
    score: float = 0.0


def _rank_key(chip: Chip) -> tuple:
    # RANK_ORDER in IntentMatcher: score desc, then shortest phrase, then
    # intent id, then phrase — a total order so chips never flicker.
    return (-chip.score, len(chip.phrase), chip.intent_id, chip.phrase)


class SuggestionEngine:
    """Fuzzy phrasebook ranker behind the live suggestion chips.

    Ported from ``IntentMatcher.topK``: score every phrase against the typed
    text, keep the best phrase per intent, sort by the Java rank order, and
    return nothing at all when the top score is under the 0.70 suggestion
    floor. Deliberately no new rules — the console shows the same chips the
    Swing panel showed.
    """

    def __init__(self, phrases: Mapping[str, Iterable[str]] | Iterable[tuple[str, str]]):
        corpus: list[tuple[str, str, str]] = []
        if isinstance(phrases, Mapping):
            pairs: Iterable[tuple[str, str]] = (
                (phrase, intent)
                for intent, group in phrases.items()
                for phrase in group
            )
        else:
            pairs = phrases
        for phrase, intent_id in pairs:
            key = normalise(phrase)
            if not key or not intent_id:
                continue
            corpus.append((key, sort_tokens(key), str(intent_id)))
        self._corpus = tuple(corpus)

    def __len__(self) -> int:
        return len(self._corpus)

    def _similarity(self, key: str, sorted_key: str, phrase: str, sorted_phrase: str) -> float:
        direct = jaro_winkler(key, phrase)
        if sorted_key == key and sorted_phrase == phrase:
            return direct
        return max(direct, jaro_winkler(sorted_key, sorted_phrase))

    def top_k(self, text: str | None, k: int = MAX_SUGGESTION_CHIPS) -> list[Chip]:
        if k <= 0:
            return []
        key = normalise(text)
        if not key:
            return []
        limit = min(k, MAX_TOP_K)
        sorted_key = sort_tokens(key)
        best: dict[str, Chip] = {}
        for phrase, sorted_phrase, intent_id in self._corpus:
            chip = Chip(
                phrase=phrase,
                intent_id=intent_id,
                score=self._similarity(key, sorted_key, phrase, sorted_phrase),
            )
            previous = best.get(intent_id)
            if previous is None or _rank_key(chip) < _rank_key(previous):
                best[intent_id] = chip
        ranked = sorted(best.values(), key=_rank_key)[:limit]
        if not ranked or ranked[0].score < SUGGESTION_FLOOR:
            return []
        return ranked


def suggestion_chips(
    text: str | None,
    engine: SuggestionEngine | None,
    *,
    k: int = MAX_SUGGESTION_CHIPS,
    enabled: bool = True,
) -> list[Chip]:
    """Chips for the current editor text (ChatView.java:1310).

    Empty text, a disabled input, or a missing engine all clear the row; the
    Swing version does exactly this before it touches the executor.
    """
    if not enabled or engine is None:
        return []
    if text is None or not text.strip():
        return []
    # AutocompleteChipRow.setCandidates takes min(3, size) whatever topK gave.
    limit = min(k, MAX_SUGGESTION_CHIPS)
    return engine.top_k(text, limit)[:limit]


def accept_first(chips: Sequence[Chip]) -> str | None:
    """Ctrl+Space behaviour (ChatView.java:1229, AutocompleteChipRow.acceptFirst)."""
    return chips[0].phrase if chips else None


# --------------------------------------------------------------------------
# S1.16 clarification chips — ChatView.java:1433, :1475
# --------------------------------------------------------------------------

MAX_CLARIFICATION_CHIPS = 2
CHIP_HREF_PREFIX = "ijai-chip:"


def clarification_chips(candidates: Sequence[Chip | str] | None) -> list[Chip]:
    """At most two clarification chips (ChatView.java:1436)."""
    out: list[Chip] = []
    for candidate in (candidates or ())[:MAX_CLARIFICATION_CHIPS]:
        out.append(candidate if isinstance(candidate, Chip) else Chip(phrase=str(candidate)))
    return out


def chip_href(index: int) -> str:
    return f"{CHIP_HREF_PREFIX}{index}"


def parse_chip_href(href: str | None) -> int | None:
    """Index from ``ijai-chip:<n>``; None for anything malformed."""
    if not href or not href.startswith(CHIP_HREF_PREFIX):
        return None
    try:
        return int(href[len(CHIP_HREF_PREFIX):])
    except ValueError:
        return None


def resolve_chip(href: str | None, chips: Sequence[Chip]) -> str | None:
    """Phrase a clicked chip link should send (ChatView.java:1480)."""
    index = parse_chip_href(href)
    if index is None or index < 0 or index >= len(chips):
        return None
    return chips[index].phrase


# --------------------------------------------------------------------------
# S2.25 Data Governance pane — ConfigurationPane.java:100, :180, :226
# --------------------------------------------------------------------------

POSTURE_CHOICES = ("Standard", "Pseudonymised", "On-premises")
DEFAULT_POSTURE = "Standard"
AUDIT_RELATIVE_PATH = "AI_Exports/imagejai_audit.csv"
AUDIT_COUNTER_ROWS = 500
POSTURE_CHANGE_REASON = "Changed in Data Governance configuration pane"
NO_PROJECT_FOLDER_WARNING = (
    "Open an image from the project folder before generating the "
    "Data Handling Statement."
)


def _is_visual(command: str) -> bool:
    # ConfigurationPane.java:226 — visual overrides are the request_visual
    # command plus anything in the visual.* namespace.
    return command.startswith("visual.") or command == "request_visual"


def audit_counter_line(rows: Sequence[Mapping[str, Any]] | None) -> str:
    """``"<total> (<n> pseudonymised, <n> visual overrides)"``.

    Counts the last 500 audit rows only, matching the Java pane so a long
    project history cannot make the pane slow.
    """
    recent = list(rows or ())[-AUDIT_COUNTER_ROWS:]
    total = len(recent)
    pseudonymised = 0
    visual = 0
    for row in recent:
        posture = str(row.get("posture", "") or "").strip().upper()
        if posture == "PSEUDONYMISED" or bool(row.get("redaction_applied")):
            pseudonymised += 1
        if _is_visual(str(row.get("command", "") or "")):
            visual += 1
    return f"{total} ({pseudonymised} pseudonymised, {visual} visual overrides)"


def governance_model(
    *,
    posture: str = DEFAULT_POSTURE,
    image_folder: str | Path | None = None,
    audit_rows: Sequence[Mapping[str, Any]] | None = None,
) -> dict:
    """Content of the Data Governance pane (ConfigurationPane.java:85)."""
    folder = Path(image_folder) if image_folder else None
    audit_path = folder / AUDIT_RELATIVE_PATH if folder else None
    return {
        "posture": posture if posture in POSTURE_CHOICES else DEFAULT_POSTURE,
        "posture_choices": list(POSTURE_CHOICES),
        "posture_change_reason": POSTURE_CHANGE_REASON,
        "audit_label": AUDIT_RELATIVE_PATH,
        "audit_path": str(audit_path) if audit_path else "",
        "outbound_calls": audit_counter_line(audit_rows),
        "can_generate_statement": folder is not None,
        "generate_warning": None if folder is not None else NO_PROJECT_FOLDER_WARNING,
    }


# --------------------------------------------------------------------------
# S2.26 Receipts pane — ReceiptsPane.java:50, :117, :266
# --------------------------------------------------------------------------

RECEIPTS_LIMIT = 50
RECEIPT_COLUMNS = ("time", "command", "bytes_out", "redacted", "show")
RECEIPT_SHOW_CELL = "[show]"
RECEIPT_NOTE = (
    "Full response body was not cached; this receipt stores redacted audit "
    "metadata only."
)


def receipts_rows(rows: Sequence[Mapping[str, Any]] | None) -> list[dict[str, Any]]:
    """The last 50 outbound calls, newest last (ReceiptsPane.java:50)."""
    recent = list(rows or ())[-RECEIPTS_LIMIT:]
    out: list[dict[str, Any]] = []
    for row in recent:
        out.append(
            {
                "time": str(row.get("time", "") or ""),
                "command": str(row.get("command", "") or ""),
                "bytes_out": row.get("bytes_out", 0),
                "redacted": bool(row.get("redaction_applied")),
                "show": RECEIPT_SHOW_CELL,
                "raw": dict(row),
            }
        )
    return out


def receipt_detail(row: Mapping[str, Any] | None) -> dict:
    """Redacted JSON + fields_redacted for one receipt (ReceiptsPane.java:266).

    When no response body was cached the detail falls back to audit metadata
    plus the fixed receipt note, so the pane never implies it kept a payload
    it does not have.
    """
    source = dict(row or {})
    fields = source.get("fields_redacted") or []
    if isinstance(fields, str):
        fields = [part.strip() for part in fields.split(",") if part.strip()]
    body = source.get("redacted_payload") or source.get("payload")
    if not body:
        body = {
            "command": str(source.get("command", "") or ""),
            "posture": str(source.get("posture", "") or ""),
            "bytes_out": source.get("bytes_out", 0),
            "redaction_applied": bool(source.get("redaction_applied")),
            "fields_redacted": list(fields),
        }
        notes = source.get("notes")
        if notes:
            body["notes"] = str(notes)
        body["receipt_note"] = RECEIPT_NOTE
    return {"redacted_json": body, "fields_redacted": list(fields) or None}


# --------------------------------------------------------------------------
# dispatch table
# --------------------------------------------------------------------------


def _macro_popup(kind: str) -> Callable[..., RailResult]:
    def action(conn: Any = None, **kwargs: Any) -> RailResult:
        from . import macros as macros_module

        return macros_module.popup_action(kind, conn, **kwargs)

    action.__name__ = f"{kind}_popup"
    action.__doc__ = f"Build the {kind} popup model (delegates to macros.py)."
    return action


def _recipe_popup(conn: Any = None, **kwargs: Any) -> RailResult:
    """Build the Run recipe popup model (delegates to macros.py)."""
    from . import macros as macros_module

    return macros_module.popup_action("recipes", conn, **kwargs)


ACTIONS: dict[str, Callable[..., RailResult]] = {
    "new_wip": new_wip,
    "commands_popup": commands_popup,
    "new_agent_chat": new_agent_chat,
    "console_chats_popup": console_chats_popup,
    "close_all_images": close_all_images,
    "reset_roi_manager": reset_roi_manager,
    "z_project_max": z_project_max,
    "my_macros_popup": _macro_popup("my"),
    "session_macros_popup": _macro_popup("session"),
    "all_macros_popup": _macro_popup("all"),
    "save_macro_popup": _macro_popup("save"),
    "run_recipe_popup": _recipe_popup,
    "save_recipe_prompt_action": save_recipe_prompt_action,
    "audit_results": audit_results,
    "run_recipe": run_recipe_action,
    "highlight_roi": highlight_roi,
    "focus_image": focus_image,
}


def dispatch(item_id: str, conn: Any = None, **kwargs: Any) -> RailResult:
    """Run the action bound to a rail item id.

    Raises ``KeyError`` for an unknown item so a typo in the TUI fails loudly
    instead of silently doing nothing.
    """
    item = find_item(item_id) if any(i.id == item_id for i in rail_items()) else None
    action_name = item.action if item is not None else item_id
    try:
        action = ACTIONS[action_name]
    except KeyError as exc:
        raise KeyError(f"no rail action for {item_id!r}") from exc
    return action(conn, **kwargs)
