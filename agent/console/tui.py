"""ImageJAI Console — the TUI.

A Prime-Agent-style terminal workspace:

    ┌─────────────────────────────────────────────────────────────┐
    │ ImageJAI Console        anthropic / claude-opus…   Fiji ●   │
    ├───────────┬─────────────────────────────────┬───────────────┤
    │ SESSIONS  │  chat log (markdown-ish)        │ FIJI STATE    │
    │  · list   │                                 │  active image │
    │ FIJI      │                                 │  memory       │
    │  · open   ├─────────────────────────────────┤ EVENTS        │
    │  · quick  │  > input                        │  live stream  │
    ├───────────┴─────────────────────────────────┴───────────────┤
    │ ^l login  ^n new  ^o open  ^f rail  ^g panel  ^t tools  ^q  │
    └─────────────────────────────────────────────────────────────┘

Run from anywhere; the workspace is located by agent.console.workspace.
"""
from __future__ import annotations

import json
import os
import re
import threading
import time
from dataclasses import replace
from pathlib import Path
from typing import Any

from rich.markup import escape as rich_escape
from rich.text import Text

from textual import work
from textual.app import App, ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal, Vertical, VerticalScroll
from textual.screen import ModalScreen
from textual.widgets import Button, Input, Label, ListItem, ListView, OptionList, RichLog, Select, Static
from textual.widgets.option_list import Option

from . import __version__, config as console_config
from .agent_loop import AbortFlag, ConsoleAgent, TurnCallbacks, create_agent
from .catalog import CatalogEngine
from .config import ConsoleConfig, load_secret
from . import browse, macros, rail
from . import activity
from .tool_results import ResultSummary, summarize_result
from .replay import replay_entries, conversation_for_switch
from .tool_results_ui import ToolResultDetail, ToolResultScreen
from .history_ui import HistoryEntryScreen, HistorySection
from .list_ui import NavigableOptionList
from .safety_ui import SafetyEventsScreen, SafetyState
from .shell_actions import fiji_actions
from .tokens import PluginTokenMap
from .browse_ui import BrowseFilesScreen
from .confirm_ui import ConfirmationScreen, MAX_OPTIONS as MAX_CONFIRM_OPTIONS
from .suggestions import load_suggestion_engine
from .harness import HarnessStore
from .knowledge import KnowledgeIndex
from .markdown_ui import code_blocks, render_markdown
from .governance_ui import GovernanceScreen, ReceiptsScreen
from .skills import SkillCatalog, discover_skills
from .cliagents import is_cloud_ollama_tag
from .command_files import (
    CommandFileError, PromptCommand, discover_commands, load_prompt, match_command,
)
from .rail import SECTIONS as RAIL_SECTIONS
from .mention_ui import MentionOverlay
from .slash_ui import SlashOverlay
from .picker_ui import EffortPickerScreen, ModelPickerScreen
from .model_choice import DEFAULT_EFFORT, effort_levels
from .fiji_startup import candidates as fiji_candidates, ensure_fiji, fiji_root
from .fiji_ui import FijiPathScreen
from .settings_ui import SettingsScreen
from .picklist_ui import ListPickerScreen, MacroNameScreen, _label_of, rows_from_model
from .posture import (
    EgressLamp,
    Posture,
    PostureController,
    badge_for,
    footer_text,
    is_local_provider,
)
from .receipts import (
    ReceiptsLog,
    RedactionStatus,
    generate_data_handling_statement,
    statement_path,
    summarise_audit,
)
from .fiji import FijiConnection, FijiError, summarize_state
from .providers import (
    PROVIDER_LABELS,
    SUBSCRIPTION_PROVIDERS,
    ModelEntry,
    load_models_yaml,
    models_for,
    provider_has_key,
    provider_needs_key,
    validate_login,
)
from .sessions import Session, SessionStore, sessions_dir
from .evidence import (
    ArtifactStore, EvidenceJournal, build_scientific_checkpoint,
)
from .event_feed import EventFeed
from .usage import estimate_usage, UsageLedger
from .cost_notices import CostNoticeScreen, cost_variant, notice_text, changed_notices
from .catalog import snapshot_of
from .cost_ui import BudgetCeilingScreen, BillingFailureScreen, failure_kind, failure_summary, interruptible_choice, positive_limit
from .workspace import find_workspace

EVIDENCE_INLINE_TEXT_CHARS = 32_000


def _fiji_result_error(reply: Any) -> str | None:
    """Extract an operation failure from a successful TCP envelope."""
    body = reply.get("result", reply) if isinstance(reply, dict) else reply
    if not isinstance(body, dict) or body.get("success") is not False:
        return None
    error = body.get("error") or body.get("message") or "operation failed"
    if isinstance(error, dict):
        error = error.get("message") or error.get("code") or "operation failed"
    return str(error)

# Transcript and input bounds, matching ChatView.java:64-67 so the two
# surfaces refuse the same things. The evidence journal keeps the full text.
MAX_INPUT_CHARS = 65_536
MAX_TRANSCRIPT_ENTRIES = 500
LIVE_TEXT_MIN_INTERVAL_S = 0.05

# ---------------------------------------------------------------------------
# Small helpers
# ---------------------------------------------------------------------------


def _safe_query(node, selector: str, wtype=None):
    """Query that returns None instead of raising (cross-thread races, unmount)."""
    try:
        if wtype is not None:
            return node.query_one(selector, wtype)
        return node.query_one(selector)
    except Exception:
        return None


def _fmt_bytes(n: Any) -> str:
    try:
        n = int(n)
    except (TypeError, ValueError):
        return "—"
    for unit in ("B", "KB", "MB", "GB"):
        if n < 1024:
            return f"{n:.0f}{unit}" if unit == "B" else f"{n:.1f}{unit}"
        n /= 1024
    return f"{n:.1f}TB"


# ---------------------------------------------------------------------------
# Capture thumbnails
# ---------------------------------------------------------------------------

# Slash commands the console itself understands; the rail "Commands" item
# shows this list when no vendor agent supplies its own.
SLASH_COMMANDS = (
    ("/steer", "correct the active task at the next tool boundary"),
    ("/queue", "queue the next task, list pending messages, or clear them"),
    ("/python", "use, inspect, or reset the persistent Python scratchpad"),
    ("/jobs", "show tracked background macro jobs"),
    ("/reload", "reload skills, saved commands, and folder instructions"),
    ("/fork", "branch the conversation without restoring Fiji pixels"),
    ("/btw", "ask a side question outside the main analysis history"),
    ("/validate", "check or run a frozen image-validation manifest"),
    ("/memory-review", "review expired, conflicting, or instrument-dependent knowledge"),
    ("/instrument", "inspect or review the current instrument configuration"),
    ("/commands", "browse every console command"),
    ("/help", "keys and commands"),
    ("/login", "change provider or key"),
    ("/model", "choose model and reasoning effort"),
    ("/effort", "change reasoning effort for the current model"),
    ("/fiji", "choose or start a Fiji installation"),
    ("/settings", "edit agent, Fiji, privacy, budget, and display settings"),
    ("/new", "new session"),
    ("/resume", "open a saved chat or the provider's latest chat"),
    ("/rename", "rename the current session"),
    ("/delete-session", "delete a session and say what happens to its evidence"),
    ("/state", "Fiji state"),
    ("/capture", "capture the current view"),
    ("/browse", "select local image tokens for the agent"),
    ("/results", "results table"),
    ("/rois", "ROI manager"),
    ("/console", "Fiji console tail"),
    ("/friction", "repeated failures"),
    ("/safety", "Fiji safe-mode status and recent blocks"),
    ("/tools", "list Fiji tools"),
    ("/tool-results", "open a full tool return using the keyboard"),
    ("/budget", "session usage and spending limit"),
    ("/cost-notices", "review and dismiss model price changes"),
    ("/compact", "compact old context without altering evidence"),
    ("/memory", "inspect, approve, deprecate, or roll back knowledge"),
    ("/remember", "propose reviewed knowledge; never auto-promotes"),
    ("/refine", "draft session learnings for human review"),
    ("/knowledge", "search the shipped recipes and references"),
    ("/reference", "read one reference document on demand"),
    ("/fixes", "confirmed fixes for an error"),
    ("/skills", "list progressive guidance skills"),
    ("/skill", "explicitly load one guidance skill"),
    ("/export", "write a Markdown transcript"),
    ("/posture", "show or set the privacy posture"),
    ("/images", "control whether captures are sent to the model"),
    ("/roi", "flash an ROI rectangle on an image in Fiji"),
    ("/focus", "bring an image window to the front in Fiji"),
    ("/receipts", "what this session sent out"),
    ("/governance", "privacy posture, counters and the audit log"),
    ("/statement", "write a Data Handling Statement"),
    ("/audit", "read the audit log next to the images"),
    ("/clear", "clear conversation and provider context"),
    ("/quit", "exit"),
)

_THUMB_MAX_COLS = 44
_THUMB_MAX_ROWS = 12  # half-block rows (2 px per row)
_THUMB_MAX_BYTES = 8 * 1024 * 1024


_PNG_IN_TEXT = re.compile(r"""["'`]?((?:[A-Za-z]:[\\/]|[\\/]|\.{1,2}[\\/])[^"'`\n\r]*?\.png)["'`]?""",
                          re.IGNORECASE)


def _find_png_path(summary: str) -> str | None:
    """Pull a readable .png path out of a tool summary.

    Tool summaries are free text; the old code assumed the whole first line was
    the path, which failed for "saved to X" wording and for paths with spaces
    on later lines. Candidates are checked against the filesystem, so a wrong
    guess costs nothing.
    """
    if not summary:
        return None
    for match in _PNG_IN_TEXT.finditer(summary[:4000]):
        candidate = match.group(1).strip()
        if os.path.isfile(candidate):
            return candidate
    for line in summary[:4000].splitlines():
        candidate = line.strip().strip('"\'`')
        if candidate.lower().endswith(".png") and os.path.isfile(candidate):
            return candidate
    return None


def render_thumbnail_markup(path: str) -> str | None:
    """Low-res ANSI preview of a PNG capture (Pillow half-blocks).

    Returns Rich markup or None when the file is missing/too large/unreadable
    or Pillow is unavailable. Never raises.
    """
    try:
        if not path or not path.lower().endswith(".png"):
            return None
        if not os.path.isfile(path) or os.path.getsize(path) > _THUMB_MAX_BYTES:
            return None
        from PIL import Image
        with Image.open(path) as im:
            im = im.convert("RGB")
            # 2 px stack per half-block row
            scale = min(
                _THUMB_MAX_COLS / max(1, im.width),
                (_THUMB_MAX_ROWS * 2) / max(1, im.height),
            )
            if scale < 1.0:
                im = im.resize(
                    (max(1, round(im.width * scale)), max(1, round(im.height * scale))),
                    Image.Resampling.BOX,
                )
            px = im.load()
            w, h = im.size
            rows: list[str] = []
            for y in range(0, h - 1, 2):
                line: list[str] = []
                for x in range(w):
                    top = px[x, y]
                    bot = px[x, y + 1] if y + 1 < h else (0, 0, 0)
                    line.append(
                        f"[#{top[0]:02x}{top[1]:02x}{top[2]:02x} on "
                        f"#{bot[0]:02x}{bot[1]:02x}{bot[2]:02x}]▀[/]"
                    )
                rows.append("".join(line))
            if h % 2:  # leftover odd row
                y = h - 1
                line = []
                for x in range(w):
                    top = px[x, y]
                    line.append(f"[#{top[0]:02x}{top[1]:02x}{top[2]:02x} on #000000]▀[/]")
                rows.append("".join(line))
            return "\n".join(rows)
    except Exception:
        return None


# ---------------------------------------------------------------------------
# Modal screens
# ---------------------------------------------------------------------------


class LoginScreen(ModalScreen[dict]):
    """Provider -> (key) -> model -> validate; returns a config dict."""
    BINDINGS = [Binding("escape", "cancel_login", "Cancel", priority=True)]

    CSS = """
    LoginScreen { align: center middle; }
    #login-box {
        width: 78; max-width: 96%; height: 90%; border: round $accent;
        background: $surface; padding: 1 2;
    }
    #login-title { text-style: bold; margin-bottom: 1; }
    #provider-list, #model-list { height: 1fr; min-height: 3; max-height: 12; border: round $panel; margin-bottom: 1; }
    #key-row { height: auto; margin-bottom: 0; }
    #key-row Label { width: 8; }
    #key-input { border: round $accent; width: 1fr; }
    #login-status { height: auto; color: $text-muted; margin-bottom: 1; }
    #login-buttons { height: 3; dock: bottom; background: $surface; align-horizontal: right; }
    #key-row Button { min-width: 10; width: auto; }
    .hidden { display: none; }
    """

    def __init__(self, config: ConsoleConfig) -> None:
        super().__init__()
        self.config = config
        self.chosen_provider: str | None = None
        self.validated = False
        self.models: list[ModelEntry] = []
        self.free_model_mode = False
        self._login_generation = 0

    def compose(self) -> ComposeResult:
        with VerticalScroll(id="login-box"):
            yield Label("Login to ImageJAI Console", id="login-title")
            yield Label("Provider:")
            yield ListView(id="provider-list")
            yield Horizontal(id="key-row", classes="hidden")
            yield Label("Model:", id="model-label", classes="hidden")
            yield NavigableOptionList(id="model-list", classes="hidden")
            yield Input(placeholder="model id (e.g. gemma3:27b)", id="free-model", classes="hidden")
            yield Label("", id="login-status")
            with Horizontal(id="login-buttons"):
                yield Button("Connect", variant="primary", id="btn-connect")
                yield Button("Cancel", id="btn-cancel")

    def on_mount(self) -> None:
        from .providers import PROVIDER_KEYS
        provider_list = self.query_one("#provider-list", ListView)
        for key in PROVIDER_KEYS:
            if key in SUBSCRIPTION_PROVIDERS:
                status = "checking…"
            else:
                status = "key ✓" if provider_has_key(key) else ("local" if not provider_needs_key(key) else "no key")
            provider_list.append(ListItem(Label(self._row_label(key, status)), name=key))
        # highlight the first row so Enter works without pressing Down first
        if provider_list.children:
            provider_list.index = 0
        provider_list.focus()
        self._set_status("Enter picks a provider. Tab moves between fields.")
        self._probe_subscriptions()

    @staticmethod
    def _row_label(provider: str, status: str) -> str:
        return f"{PROVIDER_LABELS.get(provider, provider)}  [{status}]"

    @work(thread=True, group="subscription-probe")
    def _probe_subscriptions(self) -> None:
        """Ask the vendor clients whether they are signed in, off the UI thread.

        Each check spawns a Node-based CLI and can take seconds, so it must
        never run while the login screen is being mounted.
        """
        from .providers import PROVIDER_KEYS
        from .subscriptions import subscription_status
        app = self.app
        for key in PROVIDER_KEYS:
            if key not in SUBSCRIPTION_PROVIDERS:
                continue
            try:
                signed_in = subscription_status(key)[0]
                status = "signed in" if signed_in else "login needed"
            except Exception:
                status = "login needed"
            try:
                app.call_from_thread(self._set_provider_status, key, status)
            except Exception:
                return

    def _set_provider_status(self, provider: str, status: str) -> None:
        try:
            provider_list = self.query_one("#provider-list", ListView)
        except Exception:
            return
        for item in provider_list.children:
            if item.name != provider:
                continue
            for label in item.query(Label):
                label.update(self._row_label(provider, status))
            break

    @staticmethod
    def _row_provider(item: ListItem) -> str:
        return item.name or ""

    def on_list_view_selected(self, event: ListView.Selected) -> None:
        if event.list_view.id == "provider-list":
            self._login_generation += 1
            key = _safe_query(self, "#key-input", Input)
            if key is not None:
                key.value = ""
            self.chosen_provider = self._row_provider(event.item)
            self.query_one("#btn-connect", Button).disabled = False
            self._refresh_stages()

    def on_option_list_option_selected(self, event: OptionList.OptionSelected) -> None:
        if event.option_list.id == "model-list":
            event.stop()
            self._connect()

    def _refresh_stages(self) -> None:
        p = self.chosen_provider
        key_row = self.query_one("#key-row", Horizontal)
        model_label = self.query_one("#model-label", Label)
        model_list = self.query_one("#model-list", NavigableOptionList)
        free_model = self.query_one("#free-model", Input)
        if not p:
            return
        # key stage
        has_key = provider_has_key(p)
        needs = provider_needs_key(p) and not has_key
        show_key_row = needs or (provider_needs_key(p) and has_key)
        key_row.remove_class("hidden") if show_key_row else key_row.add_class("hidden")
        if show_key_row and not key_row.children:
            from textual.widgets import Input as WInput
            key_row.mount(
                Label("API key:"),
                WInput(placeholder="paste key (stored locally)", password=True, id="key-input"),
                Button("Forget key", id="btn-forget", variant="error"),
            )
        forget_button = _safe_query(self, "#btn-forget", Button)
        if forget_button is not None:
            forget_button.disabled = not has_key
            forget_button.display = provider_needs_key(p)
        key_input = _safe_query(self, "#key-input", Input)
        if key_input is not None:
            key_input.placeholder = (
                "a key is stored — paste a new one to replace it"
                if has_key else "paste key (stored locally)"
            )
        # model stage
        self.models = models_for(p)
        self.free_model_mode = not self.models
        model_label.remove_class("hidden")
        model_list.clear_options()
        if self.free_model_mode:
            model_list.add_class("hidden")
            free_model.remove_class("hidden")
        else:
            model_list.remove_class("hidden")
            free_model.add_class("hidden")
            model_list.add_options([Option(Text(m.label)) for m in self.models])
            if model_list.option_count:
                model_list.index = 0
        self._set_status("Enter connects. Shift+Tab goes back to the provider list.")
        self.call_after_refresh(self._focus_next_stage)

    def _focus_next_stage(self) -> None:
        """Focus the first field the user still has to fill in."""
        p = self.chosen_provider
        if p and provider_needs_key(p) and not provider_has_key(p):
            try:
                self.query_one("#key-input", Input).focus()
                return
            except Exception:
                pass
        try:
            if self.free_model_mode:
                self.query_one("#free-model", Input).focus()
            else:
                self.query_one("#model-list", NavigableOptionList).focus()
        except Exception:
            pass

    def _connect(self) -> None:
        if self.query_one("#btn-connect", Button).disabled:
            return
        p = self.chosen_provider
        model: str | None = None
        if self.free_model_mode:
            model = self.query_one("#free-model", Input).value.strip() or None
        else:
            rows = self.query_one("#model-list", NavigableOptionList)
            if rows.index is not None and 0 <= rows.index < len(self.models):
                model = self.models[rows.index].model_id
        if not model and self.models:
            model = self.models[0].model_id
        if not p or not model:
            self._set_status("Pick a provider and model first.")
            return
        key = None
        try:
            key_input = self.query_one("#key-input", Input)
            key = key_input.value.strip() or None
        except Exception:
            key = None
        self._login_generation += 1
        self.query_one("#btn-connect", Button).disabled = True
        self._set_status(f"Validating {rich_escape(p)} / {rich_escape(model)} …")
        self._validate_connection(self._login_generation, p, model, key)

    @work(thread=True, exclusive=True, group="login-validation")
    def _validate_connection(self, generation, provider, model, key):
        app = self.app
        try:
            ok, msg = validate_login(provider, model, api_key=key, save_key=bool(key))
        except Exception as exc:
            ok, msg = False, str(exc)
        app.call_from_thread(self._finish_connection, generation, provider, model, ok, msg)

    def _finish_connection(self, generation, provider, model, ok, message):
        if not self.is_attached or self not in self.app.screen_stack or generation != self._login_generation:
            return
        self.query_one("#btn-connect", Button).disabled = False
        color, mark = ("green", "✓") if ok else ("red", "✗")
        self._set_status(f"[{color}]{mark} {rich_escape(str(message))}[/{color}]")
        if ok:
            self.dismiss({"provider": provider, "model": model})

    def _set_status(self, text: str) -> None:
        self.query_one("#login-status", Label).update(text)

    def on_button_pressed(self, event: Button.Pressed) -> None:
        if event.button.id == "btn-connect":
            self._connect()
        elif event.button.id == "btn-forget":
            self._forget_key()
        elif event.button.id == "btn-cancel":
            self._login_generation += 1
            self.dismiss(None)

    def _forget_key(self) -> None:
        """Delete the stored key for the selected provider."""
        p = self.chosen_provider
        if not p:
            return
        from .config import forget_secret
        self._login_generation += 1
        try:
            forget_secret(p)
        except Exception as exc:
            self._set_status(f"[red]could not remove the key: {rich_escape(str(exc))}[/red]")
            return
        self._set_provider_status(p, "no key")
        self._refresh_stages()
        self._set_status(f"[yellow]stored key for {p} removed[/yellow]")

    def on_input_submitted(self, event: Input.Submitted) -> None:
        if event.input.id in ("key-input", "free-model"):
            self._connect()

    def key_escape(self) -> None:
        self._login_generation += 1
        self.dismiss(None)

    action_cancel_login = key_escape


class PathScreen(ModalScreen[str]):
    BINDINGS = [Binding("escape", "close_path", "Cancel", priority=True)]
    """Ask for a filesystem path (open image)."""

    CSS = """
    PathScreen { align: center middle; }
    #path-box { width: 72; height: auto; border: round $accent; background: $surface; padding: 1 2; }
    #path-input { border: round $accent; margin: 1 0; }
    """

    def __init__(self, placeholder: str = "C:/path/to/image.tif") -> None:
        super().__init__()
        self._placeholder = placeholder

    def compose(self) -> ComposeResult:
        with Vertical(id="path-box"):
            yield Label("Open image in Fiji")
            yield Input(placeholder=self._placeholder, id="path-input")
            yield Label("Bio-Formats picks up .tif/.czi/.nd2/.lif/.ome.tif …", id="path-hint")

    def on_input_submitted(self, event: Input.Submitted) -> None:
        self.dismiss(event.value.strip())

    def key_escape(self) -> None:
        self.dismiss(None)

    action_close_path = key_escape


class ApprovalScreen(ModalScreen[str]):
    """Host-code tool approval.

    Returns "once", "always" or "deny". The three buttons used to collapse to
    one boolean, which silently turned "Allow once" into a session-wide grant.
    """
    BINDINGS = [Binding("escape", "deny", "Deny", priority=True)]

    CSS = """
    ApprovalScreen { align: center middle; }
    #approval-box { width: 76; height: auto; max-height: 80%; border: round $warning; background: $surface; padding: 1 2; }
    #approval-title { text-style: bold; color: $warning; margin-bottom: 1; }
    #approval-preview { max-height: 12; overflow-y: auto; border: round $panel; padding: 0 1; margin-bottom: 1; }
    #approval-buttons { align-horizontal: center; }
    """

    def __init__(self, tool: str, args: dict) -> None:
        super().__init__()
        self.tool = tool
        self.args = args

    def compose(self) -> ComposeResult:
        with Vertical(id="approval-box"):
            yield Label(f"⚠  Host-code tool: {self.tool}", id="approval-title")
            preview = json.dumps(self.args, indent=2, ensure_ascii=False)[:1500]
            yield Static(Text(preview), id="approval-preview")
            with Horizontal(id="approval-buttons"):
                yield Button("Allow once", variant="warning", id="btn-allow")
                yield Button("Always allow this session", variant="primary", id="btn-always")
                yield Button("Deny", variant="error", id="btn-deny")

    DECISIONS = {"btn-allow": "once", "btn-always": "always", "btn-deny": "deny"}

    def on_button_pressed(self, event: Button.Pressed) -> None:
        self.dismiss(self.DECISIONS.get(event.button.id or "", "deny"))

    def key_escape(self) -> None:
        # Dismissing the prompt must never grant host-code access.
        self.dismiss("deny")

    action_deny = key_escape


class HelpScreen(ModalScreen[None]):
    BINDINGS = [Binding("escape", "close_help", "Close", priority=True)]
    CSS = """
    HelpScreen { align: center middle; }
    #help-box { width: 80; max-width: 96%; height: auto; max-height: 90%; border: round $accent; background: $surface; padding: 1 2; }
    """

    def compose(self) -> ComposeResult:
        with VerticalScroll(id="help-box"):
            yield Static(
                "[b]ImageJAI Console[/b] — keys\n\n"
                "  ctrl+l  login / change provider+model\n"
                "  ctrl+p  choose model and reasoning effort\n"
                "  ctrl+n  new session\n"
                "  ctrl+o  open image in Fiji\n"
                "  ctrl+b  browse local images safely\n"
                "  ctrl+space  accept the best suggestion\n"
                "  ctrl+f  toggle left rail\n"
                "  ctrl+g  toggle right panel\n"
                "  ctrl+t  list tools\n"
                "  ctrl+r  inspect full tool returns\n"
                "  ctrl+q  quit\n"
                "  esc     interrupt running turn\n\n"
                "[b]Slash commands[/b]\n"
                + "".join(f"  {command}  {description}\n" for command, description in SLASH_COMMANDS)
                +
                "  Custom prompts: ~/.imagej-ai/console/commands/*.md or *.txt\n"
                "  Use /commands to pick one, or type /name [arguments].\n\n"
                "  Type / to see matches; Tab completes the highlighted command.\n"
                "  You can draft during a reply; Enter keeps it until the reply ends.\n\n"
                "[b]Streaming[/b]\n"
                "  Native providers (anthropic, gemini) and proxy providers\n"
                "  stream replies token-by-token into the live panel above\n"
                "  the input. Click tool summaries for full returns and capture previews.\n\n"
                "[b]Fiji connection[/b]\n"
                "  The console opens Fiji's ImageJAI TCP server on startup by default.\n"
                "  Use /settings to change automatic startup.\n"
                "  Use /fiji to choose or change the Fiji installation.\n"
                "  Events can be filtered by type, time, and search text."
            )

    def key_escape(self) -> None:
        self.dismiss(None)

    action_close_help = key_escape


# ---------------------------------------------------------------------------
# The app
# ---------------------------------------------------------------------------


class ConsoleApp(App[None]):
    TITLE = f"ImageJAI Console v{__version__}"
    CSS = """
    #topbar { height: 1; background: $surface; }
    #topbar Button.side-toggle {
        width: auto; min-width: 0; height: 1; border: none; padding: 0 1;
        background: $surface; color: $text-muted; text-style: none;
    }
    #topbar Button.side-toggle:hover,
    #topbar Button.side-toggle:focus { background: $surface-lighten-2; color: $text; }
    #topbar.compact #topbar-model,
    #topbar.compact #topbar-fiji { display: none; }
    #topbar-label { width: auto; color: $accent; text-style: bold; padding: 0 1; }
    #topbar-model { width: 1fr; min-width: 0; color: $text-muted; padding: 0 1; }
    #topbar-fiji { width: auto; padding: 0 1; }
    #topbar-posture { width: auto; padding: 0 1; text-style: bold; }
    #topbar-egress { width: auto; padding: 0 1; }

    #main { height: 1fr; }
    #left-rail { width: 30; border-right: solid $panel; background: $surface; padding: 0 1; }
    #chat-column { width: 1fr; }
    #right-panel { width: 44; border-left: solid $panel; background: $surface; padding: 0 1; }

    .rail-title { color: $accent; text-style: bold; margin: 1 0 0 0; }
    #sessions { height: 1fr; min-height: 6; border: round $panel; margin-bottom: 1; }
    #quick-actions { height: auto; }
    #left-rail .rail-section { height: auto; }
    #left-rail .rail-item {
        width: 1fr; height: 1; min-width: 100%;
        border: none; margin: 0; background: $surface; text-style: none;
    }
    #left-rail .rail-item:hover { background: $surface-lighten-2; }
    #left-rail .rail-item:focus { text-style: bold; background: $surface-lighten-2; }
    #rail-status { height: auto; color: $text-muted; padding: 0 1; }
    #quick-actions Button {
        width: 1fr; height: 1; min-width: 100%;
        border: none; margin: 0 0 0 0;
        background: $surface; text-style: none;
    }
    #quick-actions Button:hover { background: $surface-lighten-2; }
    #quick-actions Button:focus { text-style: bold; background: $surface-lighten-2; }

    #chat-log { height: 1fr; border: none; padding: 0 1; }
    #live-text {
        height: auto; max-height: 8; overflow-y: auto;
        padding: 0 1; color: $text-muted; border-top: solid $panel;
        display: none;
    }
    #turn-status { height: auto; padding: 0 1; color: $warning; }
    #suggestion-row, #clarification-row { height: 3; margin: 0 1; }
    #suggestion-row .suggestion-chip, #clarification-row .clarification-chip {
        width: auto; min-width: 8; height: 3; margin-right: 1;
        border: round $panel; background: $surface-lighten-1;
    }
    #suggestion-row .suggestion-chip:hover,
    #clarification-row .clarification-chip:hover { background: $accent 30%; }
    #chat-input { dock: bottom; border: round $accent; margin: 0 1 1 1; }
    #chat-input:focus { border: round $accent; }

    #fiji-status { height: auto; border: round $panel; padding: 0 1; margin: 1 0; }
    #events-filters { height: 3; }
    #events-category, #events-period { width: 1fr; }
    #events-search { height: 3; }
    #events-log { height: 1fr; border: round $panel; }

    .fiji-ok { color: $success; }
    .fiji-bad { color: $error; }
    .hidden { display: none; }
    """

    BINDINGS = [
        Binding("ctrl+q", "quit", "Quit", priority=True),
        Binding("ctrl+l", "login", "Login", priority=True),
        Binding("ctrl+n", "new_session", "New", priority=True),
        Binding("ctrl+o", "open_image", "Open", priority=True),
        Binding("ctrl+b", "browse_files", "Browse", priority=True),
        Binding("ctrl+f", "toggle_left", "Rail", priority=True),
        Binding("ctrl+g", "toggle_right", "Panel", priority=True),
        Binding("ctrl+t", "list_tools", "Tools", priority=True),
        Binding("ctrl+r", "tool_results", "Returns", priority=True),
        Binding("ctrl+p", "switch_model", "Model", priority=True),
        Binding("ctrl+comma", "settings", "Settings", priority=True),
        Binding("ctrl+space", "accept_suggestion", "Suggest", priority=True),
        Binding("f1", "help", "Help"),
        Binding("escape", "interrupt", "Interrupt"),
    ]

    def __init__(
        self, config: ConsoleConfig | None = None, force_login: bool = False,
        initial_session_id: str | None = None,
        auto_start_fiji: bool = False,
    ) -> None:
        super().__init__()
        self.config = config or ConsoleConfig.load()
        self.force_login = force_login
        self.initial_session_id = initial_session_id
        self.auto_start_fiji = auto_start_fiji
        self.fiji = FijiConnection(self.config.host, self.config.port)
        if self.config.fiji_path and fiji_root(self.config.fiji_path):
            self.fiji.select_installation(Path(self.config.fiji_path))
        # One token map per session: the "@" list mints tokens for the model and
        # FijiConnection reverses them before anything reaches Fiji.
        self.path_tokens = PluginTokenMap(self.fiji)
        self.fiji.token_map = self.path_tokens
        self._session_token_maps: dict[str, PluginTokenMap] = {}
        self.store = SessionStore(self.config)
        self.session: Session | None = None
        self.agent: ConsoleAgent | None = None
        self.evidence: EvidenceJournal | None = None
        self.artifacts: ArtifactStore | None = None
        self.last_evidence_error: str | None = None
        self.turn_running = False
        from .turn_queue import PromptQueue
        self.prompt_queue = PromptQueue()
        from .kernel import PythonWorkspace
        self.python_workspace = PythonWorkspace(self.fiji)
        from .jobs import JobTracker
        self.jobs = JobTracker()
        self._jobs_poll_lock = threading.Lock()
        self._turn_cancel_requested = False
        self._clear_after_turn = False
        self._pending_free_model = False
        self._pending_billing_failure = None
        self.abort: AbortFlag | None = None
        self._always_allow_host_code: set[str] = set()
        self._stop_events = threading.Event()
        self.event_feed = EventFeed()
        self._fiji_state: dict = {}
        self._fiji_poll_lock = threading.Lock()
        self._last_event_poll_at = 0.0
        self._turn_started: float | None = None
        self._turn_tool_count = 0
        self._live_text = ""
        self._live_kind = "assistant"
        self._live_painted_length = 0
        self._activity_label = activity.THINKING
        self._activity_started = time.monotonic()
        self._active_tools: list[tuple[str, dict]] = []
        self._tool_result_details: dict[int, ToolResultDetail] = {}
        self._next_tool_result_detail = 0
        # Shared model catalog: curated yaml + the plugin's 24 h cache + pins.
        self.catalog = CatalogEngine()
        harness_root = console_config.CONFIG_DIR / "harness"
        self.global_harness = HarnessStore(
            harness_root / "harness_state.json",
            harness_root / "refinements.jsonl",
        )
        self.session_harness: HarnessStore | None = None
        self._knowledge: KnowledgeIndex | None = None
        self.posture = PostureController()
        self._fiji_posture_known = False
        self._posture_change_pending = False
        self._posture_change_generation = 0
        self._posture_probe_at = 0.0
        self.egress = EgressLamp()
        self.receipts = ReceiptsLog()
        self.suggestion_engine = load_suggestion_engine()
        self.suggestion_chips: list[rail.Chip] = []
        self._slash_file_choices: list[tuple[str, str]] = []
        self._slash_scan_at = 0.0
        self.clarification_chips: list[rail.Chip] = []
        self._pending_confirmations: dict[str, tuple[str, list[str], Any]] = {}
        self._confirmation_queue: list[str] = []
        self._active_confirmation: str | None = None
        self.pending_learning_drafts: list[dict[str, Any]] = []
        self.macro_journal = macros.SessionCodeJournal()
        self.safety_state = SafetyState()
        self._macro_journal_directory: Path | None = None
        self._macro_journal_lock = threading.RLock()
        self._last_code_blocks: list[tuple[str, str]] = []
        self._transcript_entries = 0
        self._dropped_transcript_entries = 0
        self._transcript_notice_at = 0
        self._live_text_last_paint = 0.0
        self.skills: SkillCatalog | None = None
        self._skill_diagnostics_shown = False
        self._reload_pending = False
        self._side_agent = None
        self._side_running = False
        from .instrument import InstrumentDetector
        self.instrument_detector = InstrumentDetector()
        self.instrument_status = {"reviewed":False, "fingerprint":"", "changed":[], "facts":{}}
        self.instrument_fingerprint = ""
        self._instrument_probe_at = 0.0
        self._validation_abort = None

    # ------------------------------------------------------------ compose
    def compose(self) -> ComposeResult:
        yield Horizontal(
            Button("◀ Sessions", id="toggle-left", classes="side-toggle"),
            Static("ImageJAI Console", id="topbar-label"),
            Static("no provider — press ctrl+l to log in", id="topbar-model"),
            Static("", id="topbar-posture"),
            Static("", id="topbar-egress"),
            Static("Fiji ○ disconnected", id="topbar-fiji"),
            Button("Fiji ▶", id="toggle-right", classes="side-toggle"),
            id="topbar",
        )
        with Horizontal(id="main"):
            with VerticalScroll(id="left-rail"):
                yield Label("SESSIONS", id="rail-title", classes="rail-title")
                yield NavigableOptionList(id="sessions")
                yield HistorySection(
                    lambda: self.macro_journal, self.action_history_entry,
                    self.action_clear_macro_history, self._history_preference,
                    collapsed=self.config.history_collapsed,
                    exclude_plumbing=self.config.history_exclude_plumbing,
                )
                with Vertical(id="quick-actions"):
                    yield Button("Open image…", id="qa-open", variant="primary")
                    yield Button("Capture view", id="qa-capture")
                    yield Button("Show state", id="qa-state")
                    yield Button("Results table", id="qa-results")
                    yield Button("ROIs", id="qa-rois")
                    yield Button("Close dialogs", id="qa-dialogs")
                    yield Button("Settings", id="qa-settings")
                # the ported Fiji rail: same sections and order as the Swing panel
                for section in RAIL_SECTIONS:
                    yield Label(section.title.upper(), classes="rail-title")
                    with Vertical(classes="rail-section"):
                        for item in section.items:
                            yield Button(
                                f"{item.glyph} {item.label}",
                                id=f"rail-{item.id.replace('.', '-')}",
                                classes="rail-item",
                            )
                yield Static(rail.STATUS_IDLE, id="rail-status")
            with Vertical(id="chat-column"):
                yield RichLog(id="chat-log", markup=True, wrap=True, min_width=1, max_lines=4000)
                yield Static("", id="live-text")
                with Horizontal(id="clarification-row", classes="hidden"):
                    for index in range(2):
                        yield Button("", id=f"clarification-{index}", classes="clarification-chip")
                yield Static("", id="turn-status")
                yield MentionOverlay(self.path_tokens)
                yield SlashOverlay()
                with Horizontal(id="suggestion-row", classes="hidden"):
                    for index in range(3):
                        yield Button("", id=f"suggestion-{index}", classes="suggestion-chip")
                yield Input(
                    placeholder="Ask about your images, @file, or /help  (enter to send)",
                    id="chat-input",
                )
            with Vertical(id="right-panel"):
                yield Static("Fiji: not checked yet", id="fiji-status")
                yield Button("Safe mode: unconfirmed", id="safety-indicator")
                yield Label("EVENTS", id="events-title", classes="rail-title")
                with Horizontal(id="events-filters"):
                    yield Select(
                        [("All types", "all"), ("Images", "image"),
                         ("Macros", "macro"), ("Dialogs", "dialog"),
                         ("Results", "results"), ("Jobs", "job"),
                         ("Privacy", "privacy"), ("Warnings", "warning"),
                         ("Other", "other")],
                        value="all", allow_blank=False, id="events-category")
                    yield Select(
                        [("All time", "all"), ("Last 5 min", "5m"),
                         ("Last hour", "1h"), ("Today", "today")],
                        value="all", allow_blank=False, id="events-period")
                yield Input(placeholder="Find an event", id="events-search")
                yield RichLog(id="events-log", markup=True, wrap=True, max_lines=300)

    # unfortunately compose must yield widgets/screens; simpler: push help lazily

    def on_mount(self) -> None:
        # Re-assert the token map here too: tests and embedders may swap the
        # connection object after __init__, and an unmapped connection would
        # forward a pseudonym straight to Fiji.
        self.fiji.token_map = self.path_tokens
        if isinstance(self.path_tokens, PluginTokenMap):
            self.path_tokens.connection = self.fiji
        self.query_one("#chat-input", Input).focus()
        # sessions
        if not self.store.sessions:
            self._select_session(self.store.create())
        else:
            requested = self.store.get(self.initial_session_id) if self.initial_session_id else None
            self._select_session(requested or self.store.list()[0])
        self._reload_sessions()
        # build the agent for a saved provider/model (restart case)
        self._ensure_agent()
        self._render_session_messages()
        self._catalog_refreshed(self.catalog.offline())
        # header state
        self._update_header()
        if self.config.provider and self.config.model:
            self._log(f"[dim]Provider: {self.config.provider} / {self.config.model}. ctrl+l to change.[/dim]")
        else:
            self._log("[yellow]No provider configured — press ctrl+l to log in.[/yellow]")
        ws = find_workspace()
        self._log(f"[dim]Workspace: {ws}[/dim]" if ws else "[red]No agent workspace found — Fiji tools disabled.[/red]")
        # honour the saved layout: these config fields were written but never read
        self._apply_saved_layout()
        # Fiji owns the live policy. A saved console choice must not make the
        # badge claim Standard until Fiji confirms its actual posture.
        self._render_posture()
        # fiji poll + event stream + turn spinner
        self.set_interval(15.0, self._poll_fiji)
        self.set_interval(2.0, self._poll_jobs)
        self._poll_fiji()
        if self.auto_start_fiji:
            self._start_fiji_worker()
        self._start_event_thread()
        self.set_interval(60.0, self._refresh_event_log)
        self.set_interval(0.12, self._tick_turn_status)
        self.set_interval(0.5, self._render_posture)
        # first run or explicit `imagejai login`: push the login screen
        if self.force_login or not self.config.provider or not self.config.model:
            self.action_login()

    # ------------------------------------------------------------- helpers
    def _log(self, text: str | Text) -> None:
        log = _safe_query(self, "#chat-log", RichLog)
        if log is None:
            return
        self._transcript_entries += 1
        if self._transcript_entries > MAX_TRANSCRIPT_ENTRIES:
            # RichLog drops old lines silently; say so once per 100 drops so a
            # reader never mistakes a trimmed transcript for the whole session.
            self._dropped_transcript_entries += 1
            if self._dropped_transcript_entries - self._transcript_notice_at >= 100 or (
                    self._dropped_transcript_entries == 1):
                self._transcript_notice_at = self._dropped_transcript_entries
                log.write(
                    f"[dim]… {self._dropped_transcript_entries} earlier entr"
                    f"{'y' if self._dropped_transcript_entries == 1 else 'ies'} "
                    "scrolled out of this view; the session evidence keeps them "
                    "in full ([b]/export[/b])[/dim]")
        log.write(text)

    def _event_log(self, text: str) -> None:
        log = _safe_query(self, "#events-log", RichLog)
        if log is not None:
            log.write(text)

    def toast(self, message: str, level: str = "information") -> None:
        """Show one bounded three-second toast (S1.23)."""
        severity = str(level or "information").lower()
        if severity in ("warn", "warning"):
            severity = "warning"
        elif severity in ("error", "danger", "failure"):
            severity = "error"
        else:
            severity = "information"
        clean = str(message or "").strip()[:512]
        if clean:
            self.notify(clean, severity=severity, timeout=3.0)

    def _update_header(self) -> None:
        model_chip = _safe_query(self, "#topbar-model", Static)
        if model_chip is None:
            return
        if self.config.provider and self.config.model:
            effort = getattr(self.config, "effort", DEFAULT_EFFORT)
            suffix = f" · {effort} effort" if effort != DEFAULT_EFFORT else ""
            model_chip.update(f"{self.config.provider} / {self.config.model}{suffix}")
        else:
            model_chip.update("no provider — press ctrl+l to log in")

    def _reload_sessions(self) -> None:
        lv = self.query_one("#sessions", NavigableOptionList)
        lv.clear_options()
        sessions = self.store.list()
        lv.add_options([Option(Text(s.title), id=s.id) for s in sessions])
        # keep a row highlighted so Enter opens a session without arrowing first
        if sessions:
            active = self.session.id if self.session else None
            ids = [s.id for s in sessions]
            lv.index = ids.index(active) if active in ids else 0

    def _select_session(self, session: Session) -> None:
        if session.provider and session.model:
            self.config.provider = session.provider
            self.config.model = session.model
            self.config.effort = session.effort or DEFAULT_EFFORT
            self.config.save()
            self._update_header()
        self.session = session
        self.python_workspace.close()
        from .turn_queue import PromptQueue
        self.prompt_queue = PromptQueue(session.pending_turns)
        from .jobs import JobTracker
        self.jobs = JobTracker(session.background_jobs)
        self.usage_ledger = UsageLedger(session.usage)
        self.path_tokens = self._session_token_maps.setdefault(
            session.id, PluginTokenMap(self.fiji))
        self.path_tokens.connection = self.fiji
        self.fiji.token_map = self.path_tokens
        overlay = _safe_query(self, "#mention-overlay", MentionOverlay)
        if overlay is not None:
            overlay.token_map = self.path_tokens
        self.evidence = EvidenceJournal(sessions_dir(), session.id)
        self.artifacts = ArtifactStore(sessions_dir(), session.id)
        self.last_evidence_error = None
        with self._macro_journal_lock:
            self.macro_journal = macros.SessionCodeJournal()
            self._macro_journal_directory = sessions_dir() / session.id / "macros"
            try:
                if (self._macro_journal_directory / macros.INDEX_FILE).is_file():
                    self.macro_journal.load_from_index_if_present(self._macro_journal_directory)
                else:
                    self.macro_journal.restore_tool_runs(self.evidence.read_events(
                        event_types=["tool_call", "tool_result"]))
                    self.macro_journal.save_index(self._macro_journal_directory)
            except (OSError, ValueError) as exc:
                self.last_evidence_error = f"could not restore session macros: {exc}"
        session_root = sessions_dir() / session.id / "harness"
        self.session_harness = HarnessStore(
            session_root / "harness_state.json",
            session_root / "refinements.jsonl",
        )
        self.agent = None
        self._ensure_agent()
        if self.agent is not None and session.messages:
            self.agent.messages = list(session.messages)

    def _ensure_agent(self) -> None:
        """(Re)build the ConsoleAgent for the active provider/model/session."""
        if not (self.config.provider and self.config.model):
            self.agent = None
            return
        refusal = self.posture_refusal_for(self.config.provider, self.config.model)
        if refusal:
            # Fail closed: no agent at all rather than a quietly cloud-bound one.
            self.agent = None
            self._log(f"[red]{rich_escape(refusal)}[/red]")
            return
        if (
            self.agent is not None
            and self.agent.provider == self.config.provider
            and self.agent.model == self.config.model
            and getattr(self.agent, "effort", DEFAULT_EFFORT) == self.config.effort
        ):
            return
        try:
            key = load_secret(self.config.provider) if self.config.provider else None
            self.agent = create_agent(
                self.config.provider,
                self.config.model,
                api_key=key,
                external_session_id=(
                    self.session.external_session_id if self.session else None
                ),
                effort=self.config.effort,
                resume_vendor_latest=(
                    self.session.resume_vendor_latest if self.session else False
                ),
            )
            bind_fiji = getattr(self.agent, "set_fiji_connection", None)
            if callable(bind_fiji):
                bind_fiji(self.fiji)
            if self.session and self.session.messages:
                self.agent.messages = list(self.session.messages)
            if self.session:
                self.agent.action_receipts = dict(self.session.action_receipts)
            from .runtime_tools import attach_runtime_tools
            attach_runtime_tools(self.agent, workspace=self.python_workspace, skill_loader=self._load_model_skill)
            self.agent.model_callbacks = TurnCallbacks(on_usage=self._record_usage, before_model_call=self._before_model_call)
            capability = (
                "official provider client"
                if self.config.provider in SUBSCRIPTION_PROVIDERS
                else f"{len(self.agent.tools)} Fiji tools"
            )
            self._log(
                f"[green]✓ connected: {self.config.provider} / {self.config.model} "
                f"({capability})[/green]"
            )
        except Exception as exc:
            self.agent = None
            self._log(f"[red]provider init failed: {exc}[/red]")

    def _persist_session(self) -> None:
        if self.session and self.agent:
            self.session.messages = self.agent.messages
            self.session.action_receipts = dict(getattr(self.agent, "action_receipts", {}))
            self.session.provider = self.config.provider
            self.session.model = self.config.model
            self.session.effort = self.config.effort
            self.session.external_session_id = getattr(
                self.agent, "external_session_id", None
            )
            self.session.resume_vendor_latest = getattr(
                self.agent, "resume_vendor_latest", False
            )
            self.session.touch()
            self.store.save()
            self._reload_sessions()

    # ------------------------------------------------------------- actions
    def action_login(self) -> None:
        def done(result: dict | None) -> None:
            if not result:
                return
            refusal = self.posture_refusal_for(result.get("provider"), result.get("model"))
            if refusal:
                self._log(f"[red]{rich_escape(refusal)}[/red]")
                self._log("[dim]change the posture with /posture, or pick a local model[/dim]")
                return
            provider_changed = result["provider"] != self.config.provider
            model_changed = result["model"] != self.config.model
            if provider_changed or model_changed:
                self._persist_session()
            self.config.provider = result["provider"]
            self.config.model = result["model"]
            if provider_changed or model_changed:
                self.config.effort = DEFAULT_EFFORT
            self.config.save()
            self._update_header()
            if provider_changed and self.agent is not None and self.agent.messages:
                # provider history shapes differ — start clean
                self._log("[yellow]provider changed — starting a new session[/yellow]")
                self.action_new_session()
                return
            self._ensure_agent()
            if model_changed:
                self._persist_session()

        self.push_screen(LoginScreen(self.config), done)

    def action_new_session(self) -> None:
        if self.turn_running:
            self._log("[yellow]wait for the current reply or press Escape first[/yellow]")
            return
        self._open_session(self.store.create(
            self.config.provider, self.config.model, self.config.effort))
        self._log("[dim]new session[/dim]")

    def action_settings(self) -> None:
        if self.turn_running:
            self._log("[yellow]wait for the current reply before changing settings[/yellow]")
            return
        self.push_screen(
            SettingsScreen(replace(self.config, posture=self.posture.current.name)),
            self._apply_settings,
        )

    def _apply_settings(self, result: dict | None) -> None:
        if not result:
            return
        if self.turn_running:
            self._log("[yellow]settings were not changed during a running reply[/yellow]")
            return
        values = result["values"]
        candidate = replace(self.config, **values)
        requested_posture = Posture.parse(candidate.posture)
        candidate.posture = self.posture.current.name
        try:
            candidate.save()
        except OSError as exc:
            self._log(f"[red]settings were not saved: {rich_escape(str(exc))}[/red]")
            return
        old_path = self.config.fiji_path
        old_auto_start = self.auto_start_fiji
        self.config = candidate
        self.store.config = candidate
        self.auto_start_fiji = candidate.auto_start_fiji
        if old_path != candidate.fiji_path:
            root = Path(candidate.fiji_path) if candidate.fiji_path else None
            if root is not None:
                self.fiji.select_installation(root)
            else:
                candidates = fiji_candidates(None)
                if candidates:
                    self.fiji.select_installation(candidates[0])
                else:
                    self.fiji.clear_installation()
            if not self.auto_start_fiji:
                self._poll_fiji()
        if self.auto_start_fiji and (old_path != candidate.fiji_path or not old_auto_start):
            self._start_fiji_worker()
        if requested_posture is not self.posture.current:
            self._request_posture_change(requested_posture)
        self._render_posture()
        self._apply_saved_layout()
        self._log("[green]settings saved[/green]")
        if result.get("next") == "model":
            self.action_switch_model()
        elif result.get("next") == "login":
            self.action_login()

    def _reset_conversation_ui(self) -> None:
        self.python_workspace.close()
        self.cancel_all_confirmations()
        self.show_clarifications([])
        self._update_suggestion_chips("", enabled=False)
        overlay = _safe_query(self, "#mention-overlay", MentionOverlay)
        if overlay is not None:
            overlay.close()
        chat_input = _safe_query(self, "#chat-input", Input)
        if chat_input is not None:
            chat_input.value = ""
        self._clear_live_text()
        status = _safe_query(self, "#turn-status", Static)
        if status is not None:
            status.update("")
        self._last_code_blocks.clear()
        self.pending_learning_drafts.clear()
        self._always_allow_host_code.clear()
        self._tool_result_details.clear()
        log = _safe_query(self, "#chat-log", RichLog)
        if log is not None:
            log.clear()
        self._transcript_entries = 0
        self._dropped_transcript_entries = 0
        self._transcript_notice_at = 0

    def _render_session_messages(self) -> None:
        if self.session is None:
            return
        try:
            events = self.evidence.read_events() if self.evidence else []
        except (OSError, ValueError) as exc:
            events = []
            self.last_evidence_error = str(exc)
        for entry in replay_entries(events, self.session.messages, self.session.action_receipts):
            text, path = entry.text, None
            if entry.artifact and self.artifacts:
                try:
                    path = self.artifacts.path_for(entry.artifact)
                    if entry.kind != "tool_result":
                        with path.open(encoding="utf-8", newline="") as stream:
                            text = stream.read()
                except (OSError, ValueError) as exc:
                    self._log(f"[yellow]Saved {entry.kind} unavailable: {rich_escape(str(exc))}[/yellow]")
                    continue
            if entry.kind == "tool_call":
                self._log(activity.tool_start_text(entry.name, entry.arguments))
            elif entry.kind == "tool_result":
                description = ResultSummary(entry.summary, "success" if entry.ok else "error") if entry.summary else None
                self._show_tool_result(entry.name, entry.ok, text, entry.arguments, path, description)
            elif entry.kind == "thinking":
                self._log("[b magenta]Thinking:[/b magenta] " + rich_escape(text))
            elif entry.kind == "assistant":
                self._log("[b green]assistant:[/b green] " + render_markdown(text))
            elif entry.kind == "user":
                self._log("[b cyan]you:[/b cyan] " + rich_escape(text))
            elif entry.kind in {"side_user", "side_assistant", "side_thinking"}:
                label = {"side_user":"Side question", "side_assistant":"Side answer", "side_thinking":"Side thinking"}[entry.kind]
                self._log("[b cyan]" + label + ":[/b cyan] " + (render_markdown(text) if entry.kind == "side_assistant" else rich_escape(text)))

    def _open_session(self, session: Session) -> None:
        if self._validation_abort:
            self._log("[yellow]Finish or stop validation before changing chats.[/yellow]")
            return
        if self.turn_running:
            self._log("[yellow]wait for the current reply or press Escape first[/yellow]")
            return
        self._persist_session()
        self._reset_conversation_ui()
        self._select_session(session)
        self._reload_sessions()
        self._render_session_messages()

    def action_clear_conversation(self) -> None:
        self.prompt_queue.clear()
        self._save_prompt_queue()
        if self.turn_running:
            self._clear_after_turn = True
            self.action_interrupt()
            self._log("[dim]conversation will clear when the reply stops[/dim]")
            return
        if self.session is None:
            return
        self._evidence_append("decision", {"kind": "conversation_cleared"})
        self.session.messages = []
        self.session.action_receipts = {}
        self.session.external_session_id = None
        self.session.resume_vendor_latest = False
        self.session.title = "New session"
        self.session.touch()
        self.store.save_session(self.session)
        self._session_token_maps[self.session.id] = PluginTokenMap(self.fiji)
        self.path_tokens = self._session_token_maps[self.session.id]
        self.fiji.token_map = self.path_tokens
        overlay = _safe_query(self, "#mention-overlay", MentionOverlay)
        if overlay is not None:
            overlay.token_map = self.path_tokens
        self._reset_conversation_ui()
        self.agent = None
        self._ensure_agent()
        self._reload_sessions()
        self._log("[dim]conversation cleared[/dim]")

    def _finish_pending_clear(self) -> None:
        if self._clear_after_turn:
            self._clear_after_turn = False
            self.action_clear_conversation()

    def _resume_command(self, argument: str) -> None:
        choice = argument.strip()
        if not choice:
            self._show_rail_data("Console chats", rail.console_chats_popup(
                sessions=[{
                    "id": session.id, "title": session.title,
                    "updated": session.updated,
                } for session in self.store.list()]
            ))
            return
        if choice.lower() == "vendor":
            if self.config.provider not in SUBSCRIPTION_PROVIDERS:
                self._log("[yellow]provider latest is available for Codex and Claude subscriptions[/yellow]")
                return
            if self.turn_running:
                self._log("[yellow]wait for the current reply or press Escape first[/yellow]")
                return
            session = self.store.create(
                self.config.provider, self.config.model, self.config.effort)
            session.resume_vendor_latest = True
            self.store.save_session(session)
            self._open_session(session)
            self._log("[dim]next reply will continue this provider's latest chat in the agent workspace[/dim]")
            return
        if choice.lower() == "latest":
            sessions = self.store.list()
            session = sessions[0] if sessions else None
        else:
            session = self.store.get(choice)
        if session is None:
            self._log("[yellow]saved chat not found; use /resume to list chats[/yellow]")
            return
        self._open_session(session)

    def action_open_image(self) -> None:
        self.push_screen(PathScreen(), lambda p: self._open_path(p))

    def _apply_saved_layout(self) -> None:
        self._set_side_visible("left", bool(self.config.show_left_rail))
        self._set_side_visible("right", bool(self.config.show_right_panel))

    def on_resize(self, event) -> None:
        topbar = _safe_query(self, "#topbar")
        if topbar is not None:
            topbar.set_class(event.size.width < 100, "compact")
        self._apply_saved_layout()

    def _set_side_visible(self, side: str, visible: bool) -> None:
        selector = "#left-rail" if side == "left" else "#right-panel"
        node = _safe_query(self, selector)
        if node is not None:
            node.set_class(not visible, "hidden")
        button = _safe_query(self, f"#toggle-{side}", Button)
        if button is not None:
            compact = self.size.width < 100
            if side == "left":
                button.label = ("◀" if visible else "▶") if compact else (
                    "◀ Sessions" if visible else "Sessions ▶")
                button.tooltip = ("Hide sessions and tools (Ctrl+F)" if visible
                                  else "Show sessions and tools (Ctrl+F)")
            else:
                button.label = ("▶" if visible else "◀") if compact else (
                    "Fiji ▶" if visible else "◀ Fiji")
                button.tooltip = ("Hide Fiji state and events (Ctrl+G)" if visible
                                  else "Show Fiji state and events (Ctrl+G)")

    def action_toggle_left(self) -> None:
        self.config.show_left_rail = not self.config.show_left_rail
        self._set_side_visible("left", self.config.show_left_rail)
        self.config.save()

    def action_toggle_right(self) -> None:
        self.config.show_right_panel = not self.config.show_right_panel
        self._set_side_visible("right", self.config.show_right_panel)
        self.config.save()

    def action_switch_model(self, free_only: bool = False) -> None:
        """Choose a model, then its reasoning effort, like Prime Agent."""

        def done(choice: dict | None) -> None:
            if not choice:
                return
            provider, model = choice.get("provider"), choice.get("model")
            if not provider or not model:
                return
            refusal = self.posture_refusal_for(provider, model)
            if refusal:
                self._log(f"[red]{rich_escape(refusal)}[/red]")
                return
            if provider_needs_key(provider) and not provider_has_key(provider):
                self._log(
                    f"[yellow]{provider} needs an API key — opening login[/yellow]")
                self.action_login()
                return
            levels = tuple(choice.get("effort_levels") or ())
            current = (self.config.effort if provider == self.config.provider
                       and model == self.config.model else DEFAULT_EFFORT)
            if levels:
                self.push_screen(
                    EffortPickerScreen(provider, model, levels, current),
                    lambda effort: self._apply_model_choice(provider, model, effort)
                    if effort is not None else None,
                )
            else:
                self._apply_model_choice(provider, model, DEFAULT_EFFORT)

        self.push_screen(
            ModelPickerScreen(self.catalog, self.config.provider, self.config.model, free_only=free_only),
            done,
        )

    def action_effort(self) -> None:
        """Change effort without picking the same model again."""
        provider, model = self.config.provider, self.config.model
        if not provider or not model:
            self.action_switch_model()
            return
        levels = effort_levels(provider, model)
        if not levels:
            self._log(f"[yellow]{rich_escape(provider)} does not expose an effort setting[/yellow]")
            return
        self.push_screen(
            EffortPickerScreen(provider, model, levels, self.config.effort),
            lambda effort: self._apply_model_choice(provider, model, effort)
            if effort is not None else None,
        )

    def _apply_model_choice(self, provider: str, model: str, effort: str) -> None:
        if self.turn_running:
            self._log("[yellow]Stop the current reply before changing its model.[/yellow]")
            return
        if (provider, model, effort) == (
                self.config.provider, self.config.model, self.config.effort):
            return
        self._persist_session()
        provider_changed = provider != self.config.provider
        self.config.provider = provider
        self.config.model = model
        self.config.effort = effort
        self.config.save()
        self.agent = None
        self._update_header()
        if self.session:
            if provider_changed:
                self.session.messages = conversation_for_switch(self.session.messages)
                self.session.external_session_id = None
                self.session.resume_vendor_latest = False
            self.session.provider, self.session.model, self.session.effort = provider, model, effort
            self.store.save_session(self.session)
        self._ensure_agent()
        self._log(f"[dim]Model: {rich_escape(provider)} / {rich_escape(model)}; "
                  f"effort: {rich_escape(effort)}[/dim]")
        self._evidence_append("decision", {
            "kind": "model_selection", "provider": provider,
            "model": model, "effort": effort,
        })
        self._persist_session()

    def action_list_tools(self) -> None:
        if self.agent is None:
            self._log("[yellow]log in first (ctrl+l)[/yellow]")
            return
        names = sorted(t.__name__ for t in self.agent.tools)
        self._log(f"[b]Fiji tools ({len(names)})[/b]: " + ", ".join(names))

    @work(thread=True, exclusive=True, group="commands")
    def action_commands(self) -> None:
        """Show built-in commands and disk prompts without blocking the UI."""
        found = discover_commands(
            self.config.provider, self.config.model,
            find_workspace(), console_config.CONFIG_DIR,
            reserved_commands=frozenset(command for command, _ in SLASH_COMMANDS),
        )
        groups = [{"title": "Console", "items": [
            {"command": command, "description": description}
            for command, description in SLASH_COMMANDS
        ]}]
        for source in ("Console", "Claude", "Gemma"):
            items = [
                {"command": item.command, "description": item.path.name,
                 "prompt_command": item}
                for item in found.commands if item.source == source
            ]
            if items:
                groups.append({"title": f"{source} files", "items": items})
        self.call_from_thread(self._commands_ready, groups, found.errors)

    def _commands_ready(self, groups: list[dict], errors: tuple[str, ...]) -> None:
        for error in errors:
            self._log(f"[yellow]Command files: {rich_escape(error)}[/yellow]")
        self._show_rail_data("Commands", {"groups": groups})

    def _select_fiji_path(self, chosen: Path | None) -> None:
        if chosen is None:
            return
        self.config.fiji_path = str(chosen)
        self.config.save()
        self.fiji.select_installation(chosen)
        self._log(f"[dim]Fiji installation: {rich_escape(str(chosen))}[/dim]")
        self._start_fiji_worker()

    def _fiji_command(self, argument: str) -> None:
        if self._validation_abort and argument.strip() != "status":
            self._log("[yellow]Finish or stop validation before changing Fiji.[/yellow]")
            return
        raw = argument.strip()
        if not raw:
            self._open_fiji_picker()
            return
        if raw == "status":
            found = fiji_candidates(self.config.fiji_path)
            selected = self.config.fiji_path or (str(found[0]) if found else "none detected")
            self._log(f"[b]Fiji installation[/b]: {rich_escape(selected)}\n"
                      f"connection: {self.config.host}:{self.config.port} "
                      f"({'online' if self.fiji.connected else 'offline'})")
            return
        if raw == "start":
            self._start_fiji_worker()
            return
        if raw.startswith("use ") or raw.startswith("path "):
            root = fiji_root(raw.split(" ", 1)[1].strip().strip('"'))
            if root is None:
                self._log("[red]No Fiji launcher found at that path.[/red]")
                return
            self._select_fiji_path(root)
            return
        self._log("[yellow]usage: /fiji, /fiji status, /fiji start, or /fiji use <path>[/yellow]")

    @work(thread=True, exclusive=True, group="fiji-discovery")
    def _open_fiji_picker(self) -> None:
        self.call_from_thread(self._log, "[dim]Looking for Fiji installations…[/dim]")
        roots = fiji_candidates(self.config.fiji_path)
        self.call_from_thread(self._show_fiji_picker, roots)

    def _show_fiji_picker(self, roots: list[Path]) -> None:
        self.push_screen(FijiPathScreen(self.config.fiji_path, roots),
                         self._select_fiji_path)

    @work(thread=True, exclusive=True, group="fiji-bootstrap")
    def _start_fiji_worker(self) -> None:
        if self.config.host not in {"localhost", "127.0.0.1", "::1"}:
            self.call_from_thread(
                self._log, "[yellow]Automatic Fiji startup requires a local TCP host.[/yellow]")
            return
        if self.config.fiji_path and fiji_root(self.config.fiji_path) is None:
            self.call_from_thread(
                self._log, "[red]Saved Fiji path no longer has a launcher. Use /fiji to choose another.[/red]")
            return
        roots = fiji_candidates(self.config.fiji_path)
        if not roots:
            self.call_from_thread(
                self._log, "[yellow]No Fiji installation found. Use /fiji to choose one.[/yellow]")
            return
        root = roots[0]
        if not self.config.fiji_path:
            self.config.fiji_path = str(root)
            self.config.save()
        self.fiji.select_installation(root)
        self.call_from_thread(
            self._log, f"[dim]Connecting to Fiji at {rich_escape(str(root))}…[/dim]")
        try:
            result = ensure_fiji(
                root, self.config.port, self.fiji.installation_root,
                close_startup_error=self.config.close_fiji_startup_error)
        except Exception as exc:
            self.call_from_thread(
                self._log, f"[red]Fiji startup: {rich_escape(str(exc))}[/red]")
            return
        self.call_from_thread(self._log, f"[green]Fiji {rich_escape(result)}[/green]")
        self.call_from_thread(self._poll_fiji)

    # -------------------------------------------------------- confirmations
    def request_confirmation(
        self, confirmation_id: str, prompt: str, options: Any, on_choice: Any,
    ) -> bool:
        """Queue one ID-keyed, bounded confirmation prompt (S1.27)."""
        clean_id = str(confirmation_id or "").strip()
        if not re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", clean_id):
            return False
        if clean_id in self._pending_confirmations or len(self._pending_confirmations) >= 32:
            return False
        bounded = [str(option or "").strip()[:256] for option in list(options or [])[:MAX_CONFIRM_OPTIONS]]
        bounded = [option for option in bounded if option]
        if not bounded:
            return False
        self._pending_confirmations[clean_id] = (str(prompt or "")[:4096], bounded, on_choice)
        self._confirmation_queue.append(clean_id)
        self._show_next_confirmation()
        return True

    def _show_next_confirmation(self) -> None:
        if self._active_confirmation is not None:
            return
        while self._confirmation_queue:
            confirmation_id = self._confirmation_queue.pop(0)
            pending = self._pending_confirmations.get(confirmation_id)
            if pending is None:
                continue
            prompt, options, _callback = pending
            self._active_confirmation = confirmation_id
            self.push_screen(
                ConfirmationScreen(confirmation_id, prompt, options),
                lambda choice, cid=confirmation_id: self._finish_confirmation(cid, choice),
            )
            return

    def _finish_confirmation(self, confirmation_id: str, choice: Any) -> None:
        pending = self._pending_confirmations.pop(confirmation_id, None)
        if self._active_confirmation == confirmation_id:
            self._active_confirmation = None
        if pending is not None and choice is not None:
            callback = pending[2]
            if callable(callback):
                try:
                    callback(str(choice))
                except Exception as exc:
                    self._log(f"[red]confirmation callback failed: {rich_escape(str(exc))}[/red]")
        self._show_next_confirmation()

    def cancel_confirmation(self, confirmation_id: str) -> bool:
        """Cancel queued or visible prompt and tear down its panel (S1.28)."""
        clean_id = str(confirmation_id or "")
        if clean_id not in self._pending_confirmations:
            return False
        if self._active_confirmation == clean_id and isinstance(self.screen, ConfirmationScreen):
            self.screen.dismiss(None)
        else:
            self._pending_confirmations.pop(clean_id, None)
            self._confirmation_queue = [cid for cid in self._confirmation_queue if cid != clean_id]
        return True

    def cancel_all_confirmations(self) -> None:
        self._confirmation_queue.clear()
        self._pending_confirmations.clear()
        self._active_confirmation = None
        if isinstance(self.screen, ConfirmationScreen):
            self.screen.dismiss(None)

    # ----------------------------------------------------- suggestion chips
    def _update_suggestion_chips(self, text: str, *, enabled: bool = True) -> None:
        self.suggestion_chips = rail.suggestion_chips(
            text, self.suggestion_engine, enabled=enabled and not self.turn_running)
        row = _safe_query(self, "#suggestion-row", Horizontal)
        if row is None:
            return
        buttons = list(row.query(".suggestion-chip"))
        for index, button in enumerate(buttons):
            if index < len(self.suggestion_chips):
                button.label = self.suggestion_chips[index].phrase
                button.remove_class("hidden")
            else:
                button.add_class("hidden")
        row.set_class(not self.suggestion_chips, "hidden")

    def _accept_suggestion(self, index: int) -> None:
        if index < 0 or index >= len(self.suggestion_chips):
            return
        chat_input = _safe_query(self, "#chat-input", Input)
        if chat_input is not None:
            chat_input.value = self.suggestion_chips[index].phrase
            chat_input.cursor_position = len(chat_input.value)
            chat_input.focus()

    def action_accept_suggestion(self) -> None:
        """Ctrl+Space inserts the best current completion without sending it."""
        slash = _safe_query(self, "#slash-overlay", SlashOverlay)
        if slash is not None and slash.open:
            chat_input = _safe_query(self, "#chat-input", Input)
            if chat_input is not None:
                slash.apply_to(chat_input)
            return
        self._accept_suggestion(0)

    @work(thread=True, exclusive=True, group="slash-completion")
    def _load_slash_file_choices(self) -> None:
        found = discover_commands(
            self.config.provider, self.config.model,
            find_workspace(), console_config.CONFIG_DIR,
            reserved_commands=frozenset(command for command, _ in SLASH_COMMANDS),
        )
        choices = [(entry.command, f"{entry.source} command") for entry in found.commands]
        self.call_from_thread(self._set_slash_file_choices, choices)

    def _set_slash_file_choices(self, choices: list[tuple[str, str]]) -> None:
        self._slash_file_choices = choices
        chat_input = _safe_query(self, "#chat-input", Input)
        overlay = _safe_query(self, "#slash-overlay", SlashOverlay)
        if chat_input is not None and overlay is not None:
            overlay.update_for(chat_input.value, chat_input.cursor_position,
                               list(SLASH_COMMANDS) + choices)

    def show_clarifications(self, candidates: Any) -> None:
        """Render up to two structured clarification choices in the transcript.

        Cloud agents can call this when a provider exposes structured choices;
        choosing one sends it immediately, matching the Swing behaviour.
        """
        self.clarification_chips = rail.clarification_chips(candidates)
        row = _safe_query(self, "#clarification-row", Horizontal)
        if row is None:
            return
        buttons = list(row.query(".clarification-chip"))
        for index, button in enumerate(buttons):
            if index < len(self.clarification_chips):
                button.label = self.clarification_chips[index].phrase
                button.remove_class("hidden")
            else:
                button.add_class("hidden")
        row.set_class(not self.clarification_chips, "hidden")

    def _accept_clarification(self, index: int) -> None:
        if index < 0 or index >= len(self.clarification_chips):
            return
        phrase = self.clarification_chips[index].phrase
        self.show_clarifications([])
        self._submit_text(phrase)

    def _submit_text(self, text: str, *, literal: bool = False, queued_id=None) -> None:
        text = (text or "").strip()
        if not text:
            return
        if len(text) > MAX_INPUT_CHARS:
            self._log(
                f"[yellow]message not sent: {len(text):,} characters; the limit "
                f"is {MAX_INPUT_CHARS:,}. Put long content in a file and point "
                "the agent at it.[/yellow]")
            return
        if text.startswith("/") and not literal:
            self._slash(text)
            return
        if self.agent is None:
            self._log("[yellow]no provider — press ctrl+l to log in first[/yellow]")
            self.action_login()
            return
        if self.turn_running:
            self._log("[yellow]a turn is already running — esc to interrupt[/yellow]")
            return
        if self._validation_abort:
            self._log("[yellow]Wait for validation to finish or press Escape before starting another analysis.[/yellow]")
            return
        if self._posture_change_pending:
            self._log("[yellow]waiting for Fiji to confirm the privacy posture[/yellow]")
            return
        notice = (self._cost_notice_before_send(text, literal, queued_id=queued_id)
                  if queued_id else self._cost_notice_before_send(text, literal))
        if notice:
            return
        self.show_clarifications([])
        abort = AbortFlag()
        self.abort = self.agent.abort = abort
        self.turn_running = True
        if queued_id:
            self.prompt_queue.remove(queued_id)
            self._save_prompt_queue()
        self._turn_ok = False
        self._turn_cancel_requested = False
        self._turn_started = time.monotonic()
        self._activity_started = self._turn_started
        self._activity_label = activity.THINKING
        self._active_tools.clear()
        self._turn_tool_count = 0
        self._run_turn(text, abort)

    @work(thread=True, exclusive=True, group="browse-files")
    def action_browse_files(self) -> None:
        """Scan locally, then open the token-only Browse Files screen."""
        folder = self._mention_folder()
        if folder is None:
            self.call_from_thread(self._log, "[yellow]open an image or choose a workspace first[/yellow]")
            return
        try:
            entries = browse.scan_folder(folder, token_map=self.path_tokens)
        except Exception as exc:
            self.call_from_thread(
                self._log, f"[red]could not scan folder: {rich_escape(str(exc))}[/red]")
            return
        self.call_from_thread(self._open_browse_screen, folder, entries)

    def _open_browse_screen(self, folder: Path, entries: Any) -> None:
        if not entries:
            self._log(f"[yellow]no supported images in {rich_escape(str(folder))}[/yellow]")
            return
        self.push_screen(
            BrowseFilesScreen(str(folder), entries, browse.load_tag_rules(folder)),
            self._browse_chosen,
        )

    def _browse_chosen(self, choice: Any) -> None:
        if not choice:
            return
        entries = list(choice.get("entries") or [])
        tag = str(choice.get("tag") or "").strip()
        if not entries:
            return
        pseudonymised = self.posture.current is not Posture.STANDARD
        names = [entry.token if pseudonymised else str(entry.file) for entry in entries]
        # Only shape metadata and tokens leave the machine in protected postures.
        items = [entry.safe_metadata() for entry in entries]
        noun = "image/series" if len(names) == 1 else "images/series"
        prompt = f"I've selected {len(names)} {noun} for analysis: " + ", ".join(names)
        if tag:
            prompt += f' (tagged "{tag}")'
        prompt += ". Safe metadata: " + json.dumps(items, separators=(",", ":"))
        chat_input = _safe_query(self, "#chat-input", Input)
        if chat_input is not None:
            chat_input.value = prompt
            chat_input.cursor_position = len(prompt)
            chat_input.focus()
        self._log("[green]selection inserted into chat; review it, then press Enter[/green]")
        self.toast("Selection ready — review it, then press Enter.")

    def action_help(self) -> None:
        self.push_screen(HelpScreen())

    def action_interrupt(self) -> None:
        if self._validation_abort:
            self._validation_abort.set()
        if self._side_agent is not None:
            self._side_agent.abort.set()
        self.prompt_queue.clear()
        self._save_prompt_queue()
        if self.turn_running and self.abort is not None and not self._turn_cancel_requested:
            self.abort.set()
            self._turn_cancel_requested = True
            self._flush_live_text()
            status = _safe_query(self, "#turn-status", Static)
            if status is not None:
                status.update("[yellow]Stopping reply...[/yellow]")
            self._log("[yellow]Reply interrupted[/yellow]")

    # ------------------------------------------------------ fiji plumbing
    @work(thread=True, group="poll")
    def _poll_fiji(self) -> None:
        if not self._fiji_poll_lock.acquire(blocking=False):
            return
        remote_posture = None
        try:
            try:
                state = self.fiji.get_state()
                self._fiji_state = summarize_state(state)
                self._refresh_instrument()
                ok = True
                try:
                    safe_mode = self.fiji.safe_mode_status()
                except Exception:
                    safe_mode = None
                self.safety_state.update_status(True, safe_mode)
                now = time.monotonic()
                if (not self._fiji_posture_known and not self._posture_change_pending
                        and now - self._posture_probe_at >= 10):
                    self._posture_probe_at = now
                    try:
                        remote_posture = self.fiji.privacy_posture()
                    except FijiError:
                        pass
            except FijiError as exc:
                self._fiji_state = {"error": str(exc)}
                ok = False
                self._fiji_posture_known = False
            except Exception as exc:
                self._fiji_state = {"error": str(exc)}
                ok = False
                self._fiji_posture_known = False
            if remote_posture is not None:
                self.call_from_thread(self._adopt_fiji_posture, remote_posture)
            self.call_from_thread(self._render_fiji_status, ok)
        finally:
            self._fiji_poll_lock.release()

    def _render_fiji_status(self, ok: bool) -> None:
        self._render_posture()
        if not ok:
            self.safety_state.update_status(False)
        self._render_safety_status()
        chip = _safe_query(self, "#topbar-fiji", Static)
        panel = _safe_query(self, "#fiji-status", Static)
        if chip is None or panel is None:
            return  # screens not ready yet; next poll retries
        try:
            if ok:
                chip.update(f"Fiji ● {rich_escape(self.config.host)}:{self.config.port}")
            else:
                err = self._fiji_state.get("error", "")
                chip.update(f"Fiji ○ offline ({rich_escape(str(err)[:40])})")
            if ok:
                s = self._fiji_state
                mem = s.get("memory") or {}
                used = mem.get("used") or mem.get("used_mb") or mem.get("usedMB")
                lines = [
                    f"[b]Fiji[/b] ● {rich_escape(self.config.host)}:{self.config.port}",
                    f"images open: {rich_escape(str(s.get('n_images', '?')))}",
                    f"active: {rich_escape(str(s.get('active_title') or '—'))}",
                    f"memory: {rich_escape(mem) if isinstance(mem, str) else (_fmt_bytes(used) if used else '—')}",
                ]
                panel.update("\n".join(lines))
            else:
                panel.update(
                    "[b]Fiji[/b] ○ offline\n"
                    "Connecting automatically. Use /fiji to choose or start\n"
                    "a different Fiji installation."
                )
        except Exception:
            pass

    def _render_safety_status(self) -> None:
        indicator = _safe_query(self, "#safety-indicator", Button)
        if indicator is not None:
            label, color = self.safety_state.label()
            indicator.label = Text(label, style=color)

    def action_safety(self) -> None:
        self.push_screen(SafetyEventsScreen(self.safety_state))

    def _start_event_thread(self) -> None:
        def run() -> None:
            while not self._stop_events.is_set():
                try:
                    stream = self.fiji.event_stream()
                    for evt in stream:
                        if self._stop_events.is_set():
                            return
                        self.call_from_thread(self._render_event, evt)
                except Exception:
                    if self._stop_events.wait(5.0):
                        return

        threading.Thread(target=run, daemon=True, name="fiji-events").start()

    def _render_event(self, evt: Any) -> None:
        if self.safety_state.add(evt):
            self._render_safety_status()
        topic = (evt.get("event") or evt.get("topic")) if isinstance(evt, dict) else None
        if topic in {"image.opened", "image.closed", "image.updated"}:
            now = time.monotonic()
            if now - self._last_event_poll_at >= 1.0:
                self._last_event_poll_at = now
                self._poll_fiji()
        if isinstance(evt, dict) and not self._posture_change_pending:
            if topic in {"data_governance.posture.requested",
                         "data_governance.posture.downshifted",
                         "data_governance.posture.defaulted"}:
                data = evt.get("data")
                if isinstance(data, dict) and data.get("to"):
                    self._adopt_fiji_posture(data["to"])
        line = self.event_feed.add(evt)
        if line is not None and self._event_is_visible(line):
            self._event_log(line.markup())

    def _event_filter_values(self) -> tuple[str, str, str]:
        category = _safe_query(self, "#events-category", Select)
        period = _safe_query(self, "#events-period", Select)
        search = _safe_query(self, "#events-search", Input)
        return (str(category.value) if category and category.value else "all",
                str(period.value) if period and period.value else "all",
                search.value if search else "")

    def _event_is_visible(self, line) -> bool:
        category, period, search = self._event_filter_values()
        return self.event_feed.matches(line, category, period, search)

    def _refresh_event_log(self) -> None:
        log = _safe_query(self, "#events-log", RichLog)
        if log is None:
            return
        log.clear()
        category, period, search = self._event_filter_values()
        for line in self.event_feed.visible(category, period, search)[-300:]:
            log.write(line.markup())

    def on_select_changed(self, event: Select.Changed) -> None:
        if event.select.id in {"events-category", "events-period"}:
            self._refresh_event_log()

    def _record_egress(self, text: str) -> None:
        """One outbound chat turn (S6.23/S6.27)."""
        self._record_egress_bytes(
            len(text.encode("utf-8")), command="chat turn", categories=("prompt",))

    def _record_egress_bytes(self, byte_count: int, *, command: str,
                             categories: tuple[str, ...]) -> None:
        self.egress.record(max(0, int(byte_count)))
        self.receipts.append(
            self.config.provider or "",
            self.config.model or "",
            self.posture.current,
            command=command,
            bytes_out=max(0, int(byte_count)),
            categories=categories,
            redaction=(RedactionStatus.APPLIED
                       if self.posture.current is not Posture.STANDARD
                       else RedactionStatus.NOT_APPLIED),
        )
        self._render_posture()

    # ------------------------------------------------------- session records
    def _rename_session(self, title: str) -> None:
        clean = " ".join(str(title or "").split())[:80]
        if not clean:
            self._log("[yellow]usage: /rename <new title>[/yellow]")
            return
        if self.session is None:
            self._log("[yellow]no active session[/yellow]")
            return
        self.session.title = clean
        self.session.touch()
        self.store.save_session(self.session)
        self._reload_sessions()
        self._log(f"[green]session renamed[/green] {rich_escape(clean)}")

    def _delete_session_command(self, rest: str) -> None:
        target_id = (rest or "").strip() or (self.session.id if self.session else "")
        session = self.store.get(target_id) if target_id else None
        if session is None:
            self._log("[yellow]usage: /delete-session [session id][/yellow]")
            return
        folder = sessions_dir() / session.id
        # Say plainly what survives: the scientific record is not deleted here.
        prompt = (
            f"Delete the chat session \"{session.title}\"?\n"
            f"The transcript is removed. The evidence journal and artifacts in\n"
            f"{folder} are kept, so the scientific record survives."
        )
        self.request_confirmation(
            f"delete-session-{session.id}", prompt,
            ["Delete the chat only", "Delete chat and evidence", "Cancel"],
            lambda choice: self._delete_session(session.id, choice),
        )

    def _delete_session(self, session_id: str, choice: str) -> None:
        if choice.startswith("Cancel"):
            return
        session = self.store.get(session_id)
        if session is None:
            return
        also_evidence = "evidence" in choice.lower()
        self._evidence_append("decision", {
            "kind": "session_deleted", "session_id": session_id,
            "evidence_deleted": also_evidence,
        })
        self.store.delete(session_id)
        if also_evidence:
            import shutil
            try:
                shutil.rmtree(sessions_dir() / session_id, ignore_errors=True)
            except OSError as exc:
                self._log(f"[red]evidence not removed: {rich_escape(str(exc))}[/red]")
        if self.session is not None and self.session.id == session_id:
            remaining = self.store.list()
            self._select_session(remaining[0] if remaining else self.store.create())
            node = _safe_query(self, "#chat-log", RichLog)
            if node is not None:
                node.clear()
        self._reload_sessions()
        kept = "evidence removed too" if also_evidence else "evidence journal kept"
        self._log(f"[green]session deleted[/green] ({kept})")

    # ---------------------------------------------------- on-premises gating
    def posture_refusal_for(self, provider: str | None, model: str | None) -> str:
        """Why On-premises forbids this provider/model, or "" when allowed.

        Locality decides, not the vendor name: a cloud Ollama tag is still
        cloud even though the provider looks local (S6.10, S6.11).
        """
        if self.posture.current is not Posture.ON_PREMISES:
            return ""
        name = (provider or "").strip()
        if not name:
            return ""
        if not is_local_provider(name):
            return (f"On-premises posture: {name} sends data off this machine.")
        if is_cloud_ollama_tag(model or ""):
            return ("On-premises posture cannot use a cloud-hosted Ollama model "
                    f"({rich_escape(str(model))}). Pull a local tag instead.")
        return ""

    # ------------------------------------------------------- pointing at Fiji
    def _active_title(self) -> str:
        return str((self._fiji_state or {}).get("active_title") or "").strip()

    def _roi_command(self, rest: str) -> None:
        """`/roi x y w h [title]` — flash a rectangle on an image (S1.25)."""
        parts = (rest or "").split()
        numbers = [p for p in parts[:4] if re.fullmatch(r"-?\d+", p)]
        if len(numbers) != 4:
            self._log("[yellow]usage: /roi <x> <y> <width> <height> [image title][/yellow]")
            return
        title = " ".join(parts[4:]).strip() or self._active_title()
        if not title:
            self._log("[yellow]no active image — open one or name it[/yellow]")
            return
        rect = [int(n) for n in numbers]
        self._fiji_rail_action("highlight ROI", lambda: rail.highlight_roi(
            self.fiji, title, rect))

    def _focus_command(self, rest: str) -> None:
        """`/focus [title]` — bring an image window to the front (S1.26)."""
        title = (rest or "").strip() or self._active_title()
        if not title:
            self._log("[yellow]no active image — open one or name it[/yellow]")
            return
        self._fiji_rail_action("focus image", lambda: rail.focus_image(self.fiji, title))

    @work(thread=True, exclusive=False, group="fiji-point")
    def _fiji_rail_action(self, label: str, action) -> None:
        try:
            result = action()
        except Exception as exc:
            self.call_from_thread(
                self._log, f"[red]✗ {label}: {rich_escape(rail.readable_message(exc))}[/red]")
            return
        if getattr(result, "ok", True):
            self.call_from_thread(
                self._log, f"[green]✓ {rich_escape(result.status or label)}[/green]")
            self.call_from_thread(self._set_rail_status, result.status or label)
        else:
            self.call_from_thread(
                self._log, f"[yellow]{label}: {rich_escape(result.status)}[/yellow]")

    # ------------------------------------------------------ image attachments
    IMAGE_MODES = ("never", "standard-only", "always")

    def _image_policy_decision(self) -> tuple[bool, str]:
        """Decide whether captured pixels may leave the machine this turn.

        A capture can show the sample itself, or a name burned into the image,
        which no path token can hide. So the posture decides, not the model's
        ability to read pictures.
        """
        mode = str(getattr(self.config, "attach_images", "standard-only") or "").strip().lower()
        if mode not in self.IMAGE_MODES:
            return False, f"unknown /images setting {mode!r}"
        profile = self._model_harness_profile()
        if not profile.get("vision"):
            return False, "this model cannot read images"
        if mode == "never":
            return False, "image attachments are off (/images standard-only)"
        local = is_local_provider(self.config.provider or "")
        if self.posture.current is Posture.ON_PREMISES and not local:
            return False, "On-premises: pixels never go to a cloud model"
        if mode == "standard-only" and self.posture.current is not Posture.STANDARD:
            return False, (f"{self.posture.current.label} posture: captures stay local "
                           "(/images always to override)")
        return True, f"{self.posture.current.label} posture, {mode}"

    def _apply_image_policy(self) -> tuple[bool, str]:
        allowed, reason = self._image_policy_decision()
        setter = getattr(self.agent, "set_image_policy", None)
        if callable(setter):
            setter(allowed, reason)
        return allowed, reason

    def _record_image_attachment(self, tool_name: str, base64_chars: int) -> None:
        """Pixels left the machine: journal it and count it as egress."""
        approximate_bytes = int(base64_chars * 3 / 4)
        self._evidence_append("image_capture", {
            "tool": str(tool_name),
            "attached_to_model": True,
            "approximate_bytes": approximate_bytes,
            "posture": self.posture.current.name,
            "provider": self.config.provider or "",
            "model": self.config.model or "",
        })
        if self.config.provider and not is_local_provider(self.config.provider):
            self.call_from_thread(
                self._record_egress_bytes, approximate_bytes,
                command="image attachment", categories=("image", "pixels"))
        self.call_from_thread(
            self._log,
            f"[dim]image sent to the model ({_fmt_bytes(approximate_bytes)})[/dim]")

    def _images_command(self, rest: str) -> None:
        wanted = (rest or "").strip().lower()
        if wanted:
            if wanted not in self.IMAGE_MODES:
                self._log("[yellow]usage: /images never | standard-only | always[/yellow]")
                return
            self.config.attach_images = wanted
            self.config.save()
            self._apply_image_policy()
            self._evidence_append("decision", {
                "kind": "image_attachment_setting", "value": wanted,
            })
        allowed, reason = self._image_policy_decision()
        mode = getattr(self.config, "attach_images", "standard-only")
        state = "[green]on[/green]" if allowed else "[yellow]off[/yellow]"
        self._log(
            f"[b]Image attachments[/b]: {state} — {rich_escape(reason)}\n"
            f"  setting: {rich_escape(str(mode))}  (never | standard-only | always)\n"
            "  A capture may show the sample itself, so pixels follow the\n"
            "  privacy posture, not the model's ability to read pictures."
        )

    # -------------------------------------------------------- privacy chips
    def _render_posture(self) -> None:
        """Posture badge and egress lamp, the two privacy signals from S6.7/S6.23."""
        badge_node = _safe_query(self, "#topbar-posture", Static)
        if badge_node is not None:
            badge = badge_for(self.posture.current)
            suffix = " [dim](Fiji unverified)[/dim]" if not self._fiji_posture_known else ""
            text = f"[{badge.background}]{badge.glyph}[/] {rich_escape(badge.label)}{suffix}"
            if badge_node.content != text:
                badge_node.update(text)
        lamp_node = _safe_query(self, "#topbar-egress", Static)
        if lamp_node is not None:
            state = self.egress.state()
            glyph = "[red]●[/red]" if state.lit else "[dim]○[/dim]"
            text = f"{glyph} [dim]{state.calls}[/dim]"
            if lamp_node.content != text:
                lamp_node.update(text)

    def _show_posture(self, rest: str = "") -> None:
        """`/posture` — show the posture, or set it when a name is given."""
        wanted = (rest or "").strip()
        if not wanted:
            badge = badge_for(self.posture.current)
            verification = ("Fiji confirmed" if self._fiji_posture_known
                            else "Fiji has not confirmed this yet")
            self._log(
                f"[b]Privacy posture[/b]: {badge.glyph} {self.posture.current.label}\n"
                f"  {verification}\n"
                f"  {rich_escape(self.posture.current.description)}\n"
                f"  {rich_escape(footer_text(self.posture.current))}\n"
                f"  images to the model: "
                f"{'yes' if self._image_policy_decision()[0] else 'no'} "
                f"({rich_escape(self._image_policy_decision()[1])})\n"
                "  change it with /posture standard | pseudonymised | on-premises"
            )
            return
        try:
            target = Posture.parse(wanted.replace(" ", "-"))
        except Exception:
            self._log(f"[red]unknown posture {rich_escape(wanted)}[/red]")
            return
        self._request_posture_change(target)

    def _request_posture_change(self, target: Posture) -> None:
        if self.turn_running:
            self._log("[yellow]interrupt the current reply before changing privacy posture[/yellow]")
            return
        if self._posture_change_pending:
            self._log("[yellow]a privacy posture change is already in progress[/yellow]")
            return
        self._posture_change_pending = True
        self._posture_change_generation += 1
        generation = self._posture_change_generation
        self._log(f"[dim]Applying {target.label} in Fiji…[/dim]")
        self._change_fiji_posture(target.name, generation)

    @work(thread=True, exclusive=True, group="posture-change")
    def _change_fiji_posture(self, target: str, generation: int) -> None:
        try:
            confirmed = self.fiji.set_privacy_posture(target)
        except Exception as exc:
            self.call_from_thread(self._finish_posture_change, generation, None, str(exc))
            return
        self.call_from_thread(self._finish_posture_change, generation, confirmed, None)

    def _finish_posture_change(self, generation: int, confirmed: str | None,
                               error: str | None) -> None:
        if generation != self._posture_change_generation:
            return
        self._posture_change_pending = False
        if error:
            self._log(f"[red]Fiji did not change privacy posture: {rich_escape(error)}[/red]")
            return
        self._adopt_fiji_posture(confirmed)
        self._log(f"[green]Fiji and console posture: {self.posture.current.label}[/green]")

    def _adopt_fiji_posture(self, value: str | None) -> None:
        if value is None:
            return
        try:
            posture = Posture.parse(value)
        except ValueError:
            return
        if self._posture_change_pending:
            return
        changed = posture is not self.posture.current
        if changed and posture.is_stricter_than(self.posture.current) and self.turn_running:
            self.action_interrupt()
        self.posture.sync_from_fiji(posture)
        self._fiji_posture_known = True
        self._render_posture()
        if self.config.posture != posture.name:
            self.config.posture = posture.name
            try:
                self.config.save()
            except OSError as exc:
                self._log(f"[yellow]could not save Fiji posture: {rich_escape(str(exc))}[/yellow]")
        if changed:
            self._ensure_agent()
            self._log(f"[dim]Fiji privacy posture is {posture.label}[/dim]")

    def _receipt_rows(self) -> list[dict]:
        """Receipts in the shape the ported pane model expects."""
        rows = []
        for receipt in self.receipts.recent():
            rows.append({
                "time": receipt.time_text,
                "command": receipt.command or f"{receipt.provider}/{receipt.model}",
                "bytes_out": receipt.bytes_out,
                "redaction_applied": receipt.redaction.applied,
                "posture": receipt.posture.label,
                "fields_redacted": list(receipt.fields_redacted),
                "redacted_payload": receipt.redacted_payload,
                "notes": receipt.notes,
            })
        return rail.receipts_rows(rows)

    def _show_receipts(self) -> None:
        """`/receipts` — the pane of what this session actually sent out."""
        rows = self._receipt_rows()
        summary = self.receipts.summary()
        if not rows:
            self._log("[dim]no outbound calls recorded in this session[/dim]")
            return
        self.push_screen(ReceiptsScreen(
            rows, summary.counter_text,
            detail_for=lambda row: rail.receipt_detail(row.get("raw", row)),
        ))

    # ------------------------------------------------------- data governance
    def _audit_rows_for_pane(self) -> list[dict]:
        folder = self.posture.active_folder or self._mention_folder()
        try:
            summary = summarise_audit(folder)
        except Exception:
            return []
        rows = []
        for receipt in self.receipts.recent():
            rows.append({
                "posture": receipt.posture.name,
                "command": receipt.command,
                "redaction_applied": receipt.redaction.applied,
            })
        # Audit counters come from the plugin's own log when it exists.
        total = int(getattr(summary, "total", 0) or 0)
        for _ in range(max(0, total - len(rows))):
            rows.append({"posture": "", "command": "", "redaction_applied": False})
        return rows

    def _open_governance(self) -> None:
        folder = self.posture.active_folder or self._mention_folder()
        model = rail.governance_model(
            posture=self.posture.current.label,
            image_folder=folder,
            audit_rows=self._audit_rows_for_pane(),
        )
        allowed, reason = self._image_policy_decision()
        image_state = ("yes — " if allowed else "no — ") + reason
        self.push_screen(GovernanceScreen(model, image_state), self._governance_choice)

    def _governance_choice(self, choice: Any) -> None:
        if not choice:
            return
        action = choice.get("action")
        if action == "posture":
            self._show_posture(str(choice.get("posture") or ""))
        elif action == "statement":
            self._write_statement()
        elif action == "audit":
            self._show_audit()

    @work(thread=True, exclusive=True, group="statement")
    def _write_statement(self) -> None:
        """`/statement` — write the Data Handling Statement next to the images."""
        folder = self.posture.active_folder or self._mention_folder()
        if folder is None:
            self.call_from_thread(
                self._log, "[yellow]open an image first: the statement is written "
                           "next to the project[/yellow]")
            return
        try:
            text = generate_data_handling_statement(
                folder, self.posture.current,
                receipts=self.receipts.summary(),
                version=__version__,
            )
            target = statement_path(folder)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(text, encoding="utf-8")
        except Exception as exc:
            self.call_from_thread(
                self._log, f"[red]could not write the statement: {rich_escape(str(exc))}[/red]")
            return
        self._evidence_append("decision", {
            "kind": "data_handling_statement", "path": str(target),
            "posture": self.posture.current.name,
        })
        self.call_from_thread(
            self._log, f"[green]✓ Data Handling Statement[/green] {rich_escape(str(target))}")

    def _show_audit(self) -> None:
        """`/audit` — read the plugin's audit CSV next to the images."""
        folder = self.posture.active_folder or self._mention_folder()
        try:
            summary = summarise_audit(folder)
        except Exception as exc:
            self._log(f"[red]audit log unreadable: {rich_escape(str(exc))}[/red]")
            return
        if summary is None or not getattr(summary, "total", 0):
            self._log(f"[dim]no audit rows next to {rich_escape(str(folder))}[/dim]")
            return
        self._log(
            f"[b]Audit[/b] {rich_escape(str(folder))}\n"
            f"  rows: {summary.total}\n"
            f"  range: {rich_escape(str(summary.date_range))}"
        )

    # ------------------------------------------------------------ Fiji rail
    def _set_rail_status(self, text: str) -> None:
        node = _safe_query(self, "#rail-status", Static)
        if node is not None:
            node.update(rich_escape(rail.short_status(text)))

    def _run_rail_item(self, item_id: str) -> None:
        """Run one rail action off the UI thread and report what it did."""
        if item_id == "agent.commands":
            self.action_commands()
            return
        try:
            item = rail.find_item(item_id)
        except KeyError:
            self._log(f"[red]unknown rail item {item_id}[/red]")
            return
        state = rail.item_state(
            item_id,
            session_alive=self.agent is not None,
            has_commands=self.agent is not None,
        )
        if not state.enabled:
            self._set_rail_status(state.tooltip)
            self._log(f"[yellow]{item.label}: {rich_escape(state.tooltip)}[/yellow]")
            return
        self._set_rail_status(f"{item.label}…")
        self._rail_worker(item_id, item.label)

    @work(thread=True, exclusive=False, group="rail")
    def _rail_worker(self, item_id: str, label: str) -> None:
        kwargs = self._rail_kwargs(item_id)
        try:
            result = rail.dispatch(item_id, self.fiji, **kwargs)
        except rail.PreconditionError as exc:
            self.call_from_thread(self._rail_failed, label, str(exc))
            return
        except Exception as exc:
            self.call_from_thread(
                self._rail_failed, label, rail.readable_message(exc))
            return
        self.call_from_thread(self._rail_done, label, result)

    def _rail_kwargs(self, item_id: str) -> dict:
        """Extra arguments a rail action needs from the running app."""
        workspace = find_workspace()
        kwargs: dict[str, Any] = {}
        if item_id in ("agent.new_chat", "recipe.save"):
            kwargs["session_alive"] = self.agent is not None
        if item_id == "agent.console_chats":
            kwargs["sessions"] = [
                {"id": s.id, "title": s.title} for s in self.store.list()
            ]
        if item_id == "session.new_wip":
            kwargs["workspace"] = workspace
            kwargs["slug"] = time.strftime("wip-%Y%m%d-%H%M")
        if item_id == "recipe.save":
            kwargs["recipes_dir"] = (workspace / "recipes") if workspace else None
        if item_id in {"macros.my", "macros.session", "macros.all", "macros.save"}:
            kwargs["journal"] = self.macro_journal
            kwargs["imagej_root"] = self._macro_imagej_root()
            kwargs["workspace"] = workspace
            kwargs["home"] = console_config.CONFIG_DIR
        return kwargs

    def _macro_imagej_root(self) -> Path | None:
        selected = getattr(self.fiji, "expected_root", None) or self.config.fiji_path
        return Path(selected) if selected else None

    def _rail_failed(self, label: str, message: str) -> None:
        self._set_rail_status(message)
        self._log(f"[red]✗ {label}: {rich_escape(message)}[/red]")

    def _rail_done(self, label: str, result: Any) -> None:
        self._set_rail_status(result.status or label)
        if result.log:
            self._log(f"[dim]{rich_escape(result.log)}[/dim]")
        if result.data is not None:
            self._show_rail_data(label, result.data)
        if result.prompt:
            # The rail speaks to the agent the way the Swing panel did: it
            # writes a prompt into the conversation instead of acting alone.
            chat_input = _safe_query(self, "#chat-input", Input)
            if chat_input is not None:
                chat_input.value = result.prompt
                chat_input.focus()
        if result.ok and not result.data and not result.prompt:
            self._log(f"[green]✓ {label}[/green]")

    def _show_rail_data(self, label: str, data: Any) -> None:
        """Open a popup model (macros, recipes, saved chats, commands) as a list."""
        rows, empty = rows_from_model(data)
        if not any(payload is not None for _, payload in rows):
            self._log(f"[yellow]{label}: {rich_escape(empty or 'nothing to show')}[/yellow]")
            return
        self.push_screen(
            ListPickerScreen(
                label, data,
                macro_actions=label in {"My Macros", "Session Macros", "All Macros"},
            ),
            lambda chosen: self._rail_choice(label, chosen),
        )

    def _rail_choice(self, label: str, chosen: Any) -> None:
        """Act on a row picked from a rail popup."""
        if chosen is None:
            return
        if isinstance(chosen, dict) and isinstance(chosen.get("item"), macros.MacroItem):
            item = chosen["item"]
            action = chosen.get("macro_action")
            if action == "open_in_script_editor":
                self._open_macro_editor(item)
            elif action == "open_folder":
                self._open_macro_folder(item)
            return
        prompt_command = chosen.get("prompt_command") if isinstance(chosen, dict) else None
        if isinstance(prompt_command, PromptCommand):
            self._run_prompt_command(prompt_command.command, prompt_command)
            return
        # a saved console chat: open it
        session_id = chosen.get("id") if isinstance(chosen, dict) else None
        if session_id:
            session = self.store.get(str(session_id))
            if session is not None:
                self._open_session(session)
                return
        # a slash command from the palette: put it in the input
        command = chosen.get("command") if isinstance(chosen, dict) else None
        if isinstance(command, str) and command.startswith("/"):
            chat_input = _safe_query(self, "#chat-input", Input)
            if chat_input is not None:
                chat_input.value = command + " "
                chat_input.cursor_position = len(chat_input.value)
                chat_input.focus()
            return
        if isinstance(chosen, str) and chosen.startswith("/"):
            chat_input = _safe_query(self, "#chat-input", Input)
            if chat_input is not None:
                chat_input.value = chosen + " "
                chat_input.cursor_position = len(chat_input.value)
                chat_input.focus()
            return
        if label == "Save Macro" and isinstance(chosen, macros.MacroItem):
            self.push_screen(
                MacroNameScreen(macros.default_file_name(chosen)),
                lambda name: self._save_macro_choice(chosen, name),
            )
            return
        # a macro or a recipe: run it through Fiji with its source tag
        run_payload = getattr(chosen, "run_payload", None)
        if callable(run_payload):
            payload = run_payload()
        else:
            payload = macros.run_payload(chosen) if hasattr(chosen, "source") else None
        if payload:
            name = _label_of(chosen)
            self._fiji_call(name, f"running {name}",
                            lambda: self.fiji.command(payload),
                            macro_payload=payload if isinstance(chosen, macros.MacroItem) else None)
            return
        self._log(f"[dim]{label}: {rich_escape(_label_of(chosen))}[/dim]")

    def _save_macro_choice(self, item: macros.MacroItem, name: str | None) -> None:
        if name is None:
            return
        try:
            path = macros.saved_macro_path(
                item, name, self._macro_imagej_root(), home=console_config.CONFIG_DIR)
        except ValueError as exc:
            self._log(f"[red]{rich_escape(str(exc))}[/red]")
            return
        if path.exists():
            self.push_screen(
                ConfirmationScreen("macro-overwrite", f"Overwrite {path.name}?", ["Overwrite"]),
                lambda choice: self._write_macro_item(item, path, overwrite=True)
                if choice == "Overwrite" else None,
            )
        else:
            self._write_macro_item(item, path)

    def _write_macro_item(self, item: macros.MacroItem, path: Path,
                          *, overwrite: bool = False) -> None:
        try:
            saved = macros.write_macro_item(item, path, overwrite=overwrite)
        except (OSError, ValueError) as exc:
            self._log(f"[red]could not save macro: {rich_escape(str(exc))}[/red]")
            return
        self._log(f"[green]macro saved[/green] {rich_escape(str(saved))}")

    @work(thread=True, exclusive=False, group="macro-editor")
    def _open_macro_editor(self, item: macros.MacroItem) -> None:
        try:
            reply = self.fiji.command(macros.script_editor_payload(item))
            failure = _fiji_result_error(reply)
            if failure:
                raise FijiError(failure)
        except (FijiError, OSError, ValueError) as exc:
            message = macros.script_editor_failure(str(exc))
            self.call_from_thread(self._log, f"[red]{rich_escape(message)}[/red]")
            return
        self.call_from_thread(
            self._log, f"[green]opened in Fiji's Script Editor[/green] "
            f"{rich_escape(item.name)}")

    @work(thread=True, exclusive=False, group="macro-folder")
    def _open_macro_folder(self, item: macros.MacroItem) -> None:
        try:
            folder = macros.open_context_folder(
                item, self._macro_imagej_root(), home=console_config.CONFIG_DIR)
        except (OSError, ValueError) as exc:
            self.call_from_thread(
                self._log, f"[red]could not open macros folder: {rich_escape(str(exc))}[/red]")
            return
        self.call_from_thread(
            self._log, f"[green]opened folder[/green] {rich_escape(str(folder))}")

    # ------------------------------------------------------ quick actions
    def on_button_pressed(self, event: Button.Pressed) -> None:
        bid = event.button.id or ""
        if bid == "safety-indicator":
            self.action_safety()
            return
        if bid in {"toggle-left", "toggle-right"}:
            if bid == "toggle-left":
                self.action_toggle_left()
            else:
                self.action_toggle_right()
            chat_input = _safe_query(self, "#chat-input", Input)
            if chat_input is not None:
                chat_input.focus()
            return
        if bid.startswith("suggestion-") or bid.startswith("clarification-"):
            try:
                index = int(bid.rsplit("-", 1)[1])
            except (TypeError, ValueError):
                return
            if bid.startswith("suggestion-"):
                self._accept_suggestion(index)
            else:
                self._accept_clarification(index)
            return
        if bid.startswith("rail-"):
            self._run_rail_item(bid[5:].replace("-", "."))
            return
        if bid == "qa-open":
            self.action_open_image()
        elif bid == "qa-blobs":
            self._fiji_macro('run("Blobs");')
        elif bid == "qa-capture":
            self._fiji_call("capture", "capture view", lambda: self.fiji.capture())
        elif bid == "qa-state":
            self._fiji_call("state", "state", lambda: self.fiji.get_state())
        elif bid == "qa-results":
            self._fiji_call("results", "results table", lambda: self.fiji.results())
        elif bid == "qa-rois":
            self._fiji_call("rois", "ROI manager", lambda: self.fiji.rois())
        elif bid == "qa-dialogs":
            self._fiji_call("close dialogs", "closing dialogs", lambda: self.fiji.close_dialogs())
        elif bid == "qa-settings":
            self.action_settings()

    def _open_path(self, path: str | None) -> None:
        if not path:
            return
        self._fiji_call(f"open {path}", f"opening {path}", lambda: self.fiji.open_image(path))

    def _fiji_macro(self, code: str) -> None:
        self._fiji_call(
            code, f"macro: {code[:60]}", lambda: self.fiji.execute_macro(code),
            macro_payload={"code": code, "language": "ijm", "source": "console:quick"},
        )

    @work(thread=True, exclusive=False, group="fiji-quick")
    def _fiji_call(self, label: str, doing: str, fn,
                   macro_payload: dict | None = None) -> None:
        if self._validation_abort:
            self.call_from_thread(self._log, "[yellow]Finish or stop validation before using another Fiji control.[/yellow]")
            return
        self.call_from_thread(self._log, f"[dim]▸ {doing} …[/dim]")
        try:
            resp = fn()
            if macro_payload:
                failure = _fiji_result_error(resp)
                if failure:
                    raise FijiError(failure)
        except FijiError as exc:
            if macro_payload:
                self._record_macro_payload(macro_payload, False, str(exc))
            self.call_from_thread(
                self._log, f"[red]✗ {label}: {rich_escape(str(exc))}[/red]")
            return
        except Exception as exc:
            if macro_payload:
                self._record_macro_payload(macro_payload, False, str(exc))
            self.call_from_thread(
                self._log, f"[red]✗ {label}: {rich_escape(str(exc))}[/red]")
            return
        if macro_payload:
            self._record_macro_payload(macro_payload, True, "")
        body = resp.get("result", resp) if isinstance(resp, dict) else resp
        text = json.dumps(body, indent=2, default=str, ensure_ascii=False)
        self.call_from_thread(
            self._log, f"[green]✓ {label}[/green]\n" + rich_escape(text[:2000]))

    def _record_macro_payload(self, payload: dict, ok: bool, error: str) -> None:
        with self._macro_journal_lock:
            entry = self.macro_journal.record(
                str(payload.get("code") or ""), str(payload.get("language") or "ijm"),
                source=str(payload.get("source") or "console"), success=ok,
                failure_message=error or None,
            )
            if entry is not None:
                self._save_macro_journal()

    def _save_macro_journal(self) -> None:
        if self._macro_journal_directory is not None:
            try:
                self.macro_journal.save_index(self._macro_journal_directory)
            except (OSError, ValueError) as exc:
                self.last_evidence_error = f"could not save session macros: {exc}"

    def _history_preference(self, name: str, value: bool) -> None:
        setattr(self.config, name, value)
        self.config.save()

    def action_history_entry(self, entry_id: int) -> None:
        entry = self.macro_journal.find(entry_id)
        if entry is None:
            return
        session_id = self.session.id if self.session else None
        def chosen(choice) -> None:
            if self.session and self.session.id != session_id:
                return
            if choice:
                action, ident = choice
                current = self.macro_journal.find(ident)
                if current is None:
                    return
                item = macros.session_item(current)
                if action == "remove":
                    with self._macro_journal_lock:
                        self.macro_journal.remove_from_ring(ident)
                        self._save_macro_journal()
                elif action == "edit":
                    self._open_macro_editor(item)
                elif action == "save":
                    self._rail_choice("Save Macro", item)
                elif action == "run":
                    self._rail_choice("Session Macros", item)
            chat_input = _safe_query(self, "#chat-input", Input)
            if chat_input is not None and len(self.screen_stack) == 1:
                chat_input.focus()
        self.push_screen(HistoryEntryScreen(entry), chosen)

    def action_clear_macro_history(self) -> None:
        journal = self.macro_journal
        def confirmed(choice) -> None:
            if choice == "Clear history" and self.macro_journal is journal:
                with self._macro_journal_lock:
                    journal.clear_ring()
                    self._save_macro_journal()
                self._evidence_append("decision", {"kind": "macro_history_cleared"})
        self.push_screen(ConfirmationScreen(
            "macro-history-clear", "Clear this session's macro history? Saved macros and analysis evidence remain available.",
            ["Clear history", "Keep history"]), confirmed)

    # ------------------------------------------------------------ sessions
    def on_option_list_option_selected(self, event: OptionList.OptionSelected) -> None:
        if event.option_list.id == "sessions" and event.option.id:
            event.stop()
            sess = self.store.get(event.option.id)
            if sess:
                self._open_session(sess)

    # ------------------------------------------------------------- chatting
    def _mention_folder(self) -> "Path | None":
        """Folder the "@" list reads: the active image's folder, else the cwd."""
        active = (self._fiji_state or {}).get("active_path") or ""
        if active:
            parent = Path(str(active)).parent
            if parent.is_dir():
                return parent
        return Path.cwd()

    def on_input_changed(self, event: Input.Changed) -> None:
        """Update the inline file and slash-command lists as the user types."""
        if event.input.id == "events-search":
            self._refresh_event_log()
            return
        if event.input.id != "chat-input":
            return
        overlay = _safe_query(self, "#mention-overlay", MentionOverlay)
        slash = _safe_query(self, "#slash-overlay", SlashOverlay)
        if event.value.startswith("/"):
            if overlay is not None:
                overlay.close()
            if slash is not None:
                slash.update_for(event.value, event.input.cursor_position,
                                 list(SLASH_COMMANDS) + self._slash_file_choices)
            now = time.monotonic()
            if now - self._slash_scan_at > 30.0:
                self._slash_scan_at = now
                self._load_slash_file_choices()
        else:
            if slash is not None:
                slash.close()
            if overlay is not None:
                overlay.update_for(
                    event.value, event.input.cursor_position,
                    self._mention_folder(), self.posture.current.name,
                )
        self._update_suggestion_chips(
            event.value, enabled=not ((overlay and overlay.open) or (slash and slash.open)))

    def on_input_submitted(self, event: Input.Submitted) -> None:
        if event.input.id != "chat-input":
            return
        slash = _safe_query(self, "#slash-overlay", SlashOverlay)
        if slash is not None and slash.open:
            chosen = slash.selected()
            exact = any(command.casefold() == event.value.strip().casefold() for command, _ in slash.choices)
            if chosen and not exact:
                if slash.apply_to(event.input):
                    return
            slash.close()
        overlay = _safe_query(self, "#mention-overlay", MentionOverlay)
        if overlay is not None and overlay.open:
            # enter completes the mention instead of sending the message
            if overlay.apply_to(event.input):
                return
        text = event.value.strip()
        if self.turn_running:
            if text in {"/queue clear", "/queue list"}:
                self._queue_command(text[7:])
                accepted = True
            elif text.startswith(("/steer ", "/queue ")):
                command, _, content = text.partition(" ")
                accepted = self._queue_prompt(content, "steer" if command == "/steer" else "follow_up")
            elif text == "/queue":
                self._queue_command("")
                accepted = True
            elif text == "/reload":
                self._reload_pending = True
                self._log("[dim]Resources will reload at the next tool boundary.[/dim]")
                accepted = True
            elif text.startswith("/btw "):
                self._btw_command(text[5:])
                accepted = True
            elif text.startswith("/"):
                self.toast("Use /steer to correct this reply, /queue for the next task, or Escape to stop.")
                return
            else:
                accepted = self._queue_prompt(text, "steer")
            if accepted:
                event.input.value = ""
            return
        event.input.value = ""
        self._submit_text(text)

    def on_key(self, event) -> None:
        """Arrow keys, Tab, and Escape drive the open completion list."""
        if isinstance(self.screen, ModalScreen):
            return
        if event.key == "escape" and self.turn_running:
            slash = _safe_query(self, "#slash-overlay", SlashOverlay)
            overlay = _safe_query(self, "#mention-overlay", MentionOverlay)
            if slash is not None:
                slash.close()
            if overlay is not None:
                overlay.close()
            self.action_interrupt()
            event.stop()
            event.prevent_default()
            return
        slash = _safe_query(self, "#slash-overlay", SlashOverlay)
        if slash is not None and slash.open:
            if event.key == "down":
                slash.move(1)
            elif event.key == "up":
                slash.move(-1)
            elif event.key == "escape":
                slash.close()
            elif event.key == "tab":
                chat_input = _safe_query(self, "#chat-input", Input)
                if chat_input is not None:
                    slash.apply_to(chat_input)
            else:
                return
            event.stop()
            event.prevent_default()
            return
        overlay = _safe_query(self, "#mention-overlay", MentionOverlay)
        if overlay is None or not overlay.open:
            return
        if event.key == "down":
            overlay.move(1)
        elif event.key == "up":
            overlay.move(-1)
        elif event.key == "escape":
            overlay.close()
        elif event.key == "tab":
            chat_input = _safe_query(self, "#chat-input", Input)
            if chat_input is not None:
                overlay.apply_to(chat_input)
        else:
            return
        event.stop()
        event.prevent_default()

    def _slash(self, text: str) -> None:
        cmd, _, rest = text.partition(" ")
        cmd = cmd.lower()
        if cmd == "/steer":
            self._steer_command(rest)
        elif cmd == "/queue":
            self._queue_command(rest)
        elif cmd == "/python":
            self._python_command(rest)
        elif cmd == "/jobs":
            self._jobs_command()
        elif cmd == "/reload":
            self._log("[dim]Reloading local resources…[/dim]")
            self._reload_resources()
        elif cmd == "/fork":
            self._fork_command(rest)
        elif cmd == "/btw":
            self._btw_command(rest)
        elif cmd == "/validate":
            self._validate_command(rest)
        elif cmd == "/memory-review":
            self._memory_review_command(rest)
        elif cmd == "/instrument":
            self._instrument_command(rest)
        elif cmd == "/commands":
            self.action_commands()
        elif cmd in ("/help", "/?"):
            self.action_help()
        elif cmd == "/login":
            self.action_login()
        elif cmd == "/model":
            self.action_switch_model()
        elif cmd == "/effort":
            self.action_effort()
        elif cmd == "/fiji":
            self._fiji_command(rest)
        elif cmd == "/settings":
            self.action_settings()
        elif cmd == "/new":
            self.action_new_session()
        elif cmd == "/resume":
            self._resume_command(rest)
        elif cmd == "/rename":
            self._rename_session(rest)
        elif cmd == "/delete-session":
            self._delete_session_command(rest)
        elif cmd == "/state":
            self._fiji_call("state", "state", lambda: self.fiji.get_state())
        elif cmd == "/capture":
            self._fiji_call("capture", "capture view", lambda: self.fiji.capture())
        elif cmd == "/browse":
            self.action_browse_files()
        elif cmd == "/results":
            self._fiji_call("results", "results table", lambda: self.fiji.results())
        elif cmd == "/rois":
            self._fiji_call("rois", "ROI manager", lambda: self.fiji.rois())
        elif cmd == "/console":
            self._fiji_call("console", "fiji console", lambda: self.fiji.console_tail())
        elif cmd == "/safety":
            self.action_safety()
        elif cmd == "/friction":
            self._fiji_call("friction", "friction patterns", lambda: self.fiji.friction_patterns())
        elif cmd == "/tool-results":
            self.action_tool_results()
        elif cmd == "/tools":
            self.action_list_tools()
        elif cmd == "/cost-notices":
            self.action_cost_notices()
        elif cmd == "/budget":
            self._set_budget(rest)
        elif cmd == "/compact":
            self._compact_session(force=True)
        elif cmd == "/memory":
            self._memory_command(rest)
        elif cmd == "/remember":
            self._remember_command(rest)
        elif cmd == "/refine":
            self._refine_command(rest)
        elif cmd == "/knowledge":
            self._knowledge_command(rest)
        elif cmd == "/reference":
            self._reference_command(rest)
        elif cmd == "/fixes":
            self._fixes_command(rest)
        elif cmd == "/skills":
            self._skills_command(rest)
        elif cmd == "/skill":
            self._skill_command(rest)
        elif cmd == "/export":
            self._export_session()
        elif cmd == "/posture":
            self._show_posture(rest)
        elif cmd == "/images":
            self._images_command(rest)
        elif cmd == "/roi":
            self._roi_command(rest)
        elif cmd == "/focus":
            self._focus_command(rest)
        elif cmd == "/receipts":
            self._show_receipts()
        elif cmd == "/governance":
            self._open_governance()
        elif cmd == "/statement":
            self._write_statement()
        elif cmd == "/audit":
            self._show_audit()
        elif cmd == "/clear":
            self.action_clear_conversation()
        elif cmd == "/quit":
            self.exit()
        else:
            self._run_prompt_command(text)

    @work(thread=True, exclusive=True, group="prompt-command")
    def _run_prompt_command(self, typed: str,
                            selected: PromptCommand | None = None) -> None:
        """Resolve a file command, read it, and send it as a normal turn."""
        arguments = ""
        entry = selected
        if entry is None:
            found = discover_commands(
                self.config.provider, self.config.model,
                find_workspace(), console_config.CONFIG_DIR,
                reserved_commands=frozenset(command for command, _ in SLASH_COMMANDS),
            )
            match = match_command(typed, found.commands)
            if match is None:
                name = typed.partition(" ")[0]
                self.call_from_thread(
                    self._log, f"[yellow]unknown command {rich_escape(name)} — /help[/yellow]")
                return
            entry, arguments = match
        try:
            prompt = load_prompt(entry, arguments, max_chars=MAX_INPUT_CHARS)
        except CommandFileError as error:
            self.call_from_thread(
                self._log, f"[red]{rich_escape(entry.command)}: {rich_escape(str(error))}[/red]")
            return
        self.call_from_thread(self._deliver_prompt_command, prompt, typed)

    def _deliver_prompt_command(self, prompt: str, invocation: str) -> None:
        if self.agent is None:
            chat_input = _safe_query(self, "#chat-input", Input)
            if chat_input is not None:
                chat_input.value = invocation.rstrip() + " "
                chat_input.focus()
            self._log("[yellow]log in first, then run the command[/yellow]")
            self.action_login()
            return
        self._submit_text(prompt, literal=True)

    # ------------------------------------------------------------ skills
    def _steer_command(self, text: str) -> None:
        if not text.strip():
            self._log("[yellow]Usage: /steer <correction for the active analysis>.[/yellow]")
        elif self.turn_running:
            self._queue_prompt(text, "steer")
        else:
            self._submit_text(text, literal=True)

    def _python_command(self, text: str) -> None:
        if text.strip() == "reset":
            self.python_workspace.close()
            self._log("[dim]Python scratchpad reset.[/dim]")
        elif text.strip() in {"", "status"}:
            self._log("[b]Python scratchpad[/b]\n" + rich_escape(
                "Variables: " + (", ".join(self.python_workspace.variables) or "none. The process starts only when needed.")))
        else:
            self._submit_text("Use python_cell to run this numerical Python code, preserving its text:\n" + text, literal=True)

    def _jobs_command(self) -> None:
        self._log("[b]Background macro jobs[/b]\n" + ("\n".join(rich_escape(row["id"] + ": " + row["state"]) for row in self.jobs.snapshot()) or "None."))

    def _refresh_instrument(self, force: bool = False) -> None:
        if not force and time.monotonic() - self._instrument_probe_at < 120:
            return
        self._instrument_probe_at = time.monotonic()
        from .instrument import InstrumentProfile
        try:
            root = getattr(self.fiji, "expected_root", None) or self.fiji.installation_root()
            instrument_state = dict(self._fiji_state or {})
            if instrument_state.get("active"):
                metadata = self.fiji.command({"command":"get_metadata"}, timeout=3)
                calibration = metadata.get("result", {}).get("calibration")
                if not metadata.get("ok") or not isinstance(calibration, dict):
                    raise ValueError("Full image calibration is unavailable; instrument assumptions are withheld")
                instrument_state["instrument_calibration"] = calibration
            facts = self.instrument_detector.facts(root, instrument_state)
            profile = InstrumentProfile(console_config.CONFIG_DIR / "instruments" / (facts["installation_key"] + ".json"))
            status = profile.inspect(facts)
            previous = self.instrument_fingerprint
            self.instrument_status, self.instrument_fingerprint = status, status["fingerprint"]
            self._instrument_profile = profile
            if previous and previous != self.instrument_fingerprint:
                self.call_from_thread(self._event_log, "[yellow]Instrument configuration changed; dependent knowledge is withheld pending review.[/yellow]")
        except Exception as exc:
            self.instrument_status = {"reviewed":False, "fingerprint":"", "changed":[], "facts":{}, "error":str(exc)}
            self.instrument_fingerprint = ""

    def _instrument_command(self, text: str) -> None:
        action, _, evidence = text.partition("|")
        if action.strip() == "approve":
            if not evidence.strip() or not self.instrument_status.get("facts"):
                self._log("[yellow]Usage: /instrument approve | written review evidence, after connecting Fiji.[/yellow]")
                return
            status = self._instrument_profile.approve(self.instrument_status["facts"], evidence.strip())
            self.instrument_status = status
            self._evidence_append("decision", {"kind":"instrument_review", "fingerprint":status["fingerprint"], "evidence":evidence.strip()})
            self._log("[green]Instrument configuration reviewed. Revalidate individual assumptions through /memory-review.[/green]")
        else:
            self._instrument_probe_at = 0.0
            self._log("[dim]Checking instrument configuration…[/dim]")
            self._instrument_status_worker()

    @work(thread=True, group="instrument-review")
    def _instrument_status_worker(self) -> None:
        self._refresh_instrument()
        status = self.instrument_status
        message = "Reviewed" if status["reviewed"] else "Needs review"
        message += "; changed configuration: " + (", ".join(status["changed"]) or "none")
        if status.get("error"):
            message += "; " + status["error"]
        self.call_from_thread(self._log, "[b]Instrument configuration[/b]\n" + rich_escape(message))

    def _memory_review_command(self, text: str) -> None:
        if text.strip():
            self._open_memory_review(text.strip())
            return
        from .memory_review import review_rows
        rows = review_rows(self._harness_stores(), instrument_fingerprint=self.instrument_fingerprint)
        self.push_screen(ListPickerScreen("Knowledge needing review", {"items":rows, "empty":"No expired, conflicting or instrument-dependent entries need review."}),
            lambda row:self._open_memory_review(row["id"]) if row else None)

    def _open_memory_review(self, entry_id: str) -> None:
        located = self._find_harness_entry(entry_id)
        if not located:
            self._log("[yellow]Memory entry not found.[/yellow]")
            return
        store, entry = located
        from .review_ui import MemoryReviewScreen
        def chosen(choice):
            if not choice:
                return
            self._review_worker(store, entry, choice, self.evidence)
        self.push_screen(MemoryReviewScreen(entry), chosen)

    @work(thread=True, group="memory-review")
    def _review_worker(self, store, entry, choice, journal) -> None:
        try:
            from .memory_review import renew, attach_validation
            if choice["action"] == "renew":
                updated = renew(store, entry, days=choice["days"], evidence=choice["evidence"])
            elif choice["action"] == "attach":
                updated = attach_validation(store, entry, Path(choice["path"]))
            elif choice["action"] == "revalidate":
                from .instrument import revalidate_entry
                if not self.instrument_status["reviewed"]:
                    raise ValueError("Review the current instrument configuration first with /instrument")
                updated = revalidate_entry(store, entry, self.instrument_fingerprint, choice["evidence"])
            else:
                updated = store.deprecate(entry.id, expected_version=entry.version, reviewer="console-user", reason=choice["evidence"])
            if journal:
                journal.append("decision", {"kind":"memory_review", "entry_id":entry.id, "action":choice["action"], "version":updated.version})
            self.call_from_thread(self._log, "[green]Knowledge review saved.[/green]")
        except Exception as exc:
            self.call_from_thread(self._log, "[red]Review refused: " + rich_escape(str(exc)) + "[/red]")
    def _validate_command(self, text: str) -> None:
        action, _, filename = text.partition(" ")
        if action not in {"check", "run"} or not filename.strip():
            self._log("[yellow]Usage: /validate check|run <manifest.json>.[/yellow]")
            return
        if self.turn_running or self._validation_abort:
            self._log("[yellow]Finish the current reply before checking or running validation.[/yellow]")
            return
        if action == "check":
            self._validation_worker(filename.strip().strip('"'), False)
        else:
            self._log("[dim]Checking frozen inputs before showing the procedure…[/dim]")
            self._prepare_validation(filename.strip().strip('"'))

    @work(thread=True, group="validation-prepare")
    def _prepare_validation(self, filename) -> None:
        from .validation import load_manifest
        try:
            frozen = load_manifest(Path(filename))
            prompt = (f"Run this procedure on {len(frozen.manifest['cases'])} declared development/held-out images? "
                      "The console opens images and executes the macro on duplicates.\n\nProcedure to execute:\n" + frozen.macro)
            def show():
                self.push_screen(ConfirmationScreen("procedure-validation", prompt, ["Run validation", "Cancel"], max_prompt_chars=70000),
                    lambda choice:self._start_validation(filename, frozen.manifest_hash) if choice == "Run validation" else None)
            self.call_from_thread(show)
        except Exception as exc:
            self.call_from_thread(self._log, "[red]Validation refused: " + rich_escape(str(exc)) + "[/red]")

    def _start_validation(self, filename, expected_hash):
        if self.turn_running or self._validation_abort:
            self._log("[yellow]Another analysis is active. Finish or stop it before validation.[/yellow]")
            return
        self._validation_abort = AbortFlag()
        self._validation_worker(filename, True, expected_hash)

    @work(thread=True, exclusive=True, group="validation")
    def _validation_worker(self, filename: str, run: bool, expected_hash: str = "") -> None:
        from .validation import load_manifest, run_validation, save_receipt, FijiValidationRunner
        journal = self.evidence
        try:
            frozen = load_manifest(Path(filename))
            if expected_hash and frozen.manifest_hash != expected_hash:
                raise ValueError("Manifest changed after the procedure was shown; review it again")
            if not run:
                self.call_from_thread(self._log, f"[green]Frozen procedure and {len(frozen.manifest['cases'])} distinct development/held-out cases verified.[/green]")
                return
            flag = self._validation_abort or AbortFlag()
            self._validation_abort = flag
            self._refresh_instrument(force=True)
            receipt = run_validation(frozen, FijiValidationRunner(self.fiji, flag), abort=flag,
                fingerprint=getattr(self, "instrument_fingerprint", ""))
            path = save_receipt(frozen.path.parent, receipt)
            if journal:
                journal.append("decision", {"kind":"procedure_validation", "passed":receipt["passed"], "receipt":str(path), "manifest_sha256":frozen.manifest_hash})
            message = "Validation checks passed" if receipt["passed"] else "Validation checks failed"
            self.call_from_thread(self._log, "[cyan]" + rich_escape(message + "; receipt: " + str(path)) + "[/cyan]")
        except Exception as exc:
            self.call_from_thread(self._log, "[red]Validation refused: " + rich_escape(str(exc)) + "[/red]")
        finally:
            if run:
                self._validation_abort = None
    def _btw_command(self, text: str) -> None:
        if not self.agent or not text.strip() or len(text) > MAX_INPUT_CHARS:
            self._log("[yellow]Usage: /btw <side question>, after choosing an agent.[/yellow]")
            return
        if self._side_running:
            self._log("[yellow]A side question is already running; Escape stops it.[/yellow]")
            return
        if self._posture_change_pending:
            self._log("[yellow]Wait for the privacy change before asking a side question.[/yellow]")
            return
        if self._cost_notice_before_send("/btw " + text, False):
            return
        self._side_running = True
        self._answer_side_question(text, self.agent, self.session, self.evidence, self.usage_ledger, self.artifacts)

    @work(thread=True, group="side-question")
    def _answer_side_question(self, text, source, session, journal, ledger, artifacts=None) -> None:
        from .side_questions import build_side_agent, SideThoughtBuffer
        from .project_context import protected_text
        thoughts = None
        try:
            clean = protected_text(text, self._knowledge_sanitizer())
            side = build_side_agent(source, load_secret(source.provider))
            self._side_agent = side
            def evidence(kind, payload):
                if journal:
                    refs = []
                    if isinstance(payload.get("text"), str) and len(payload["text"]) > EVIDENCE_INLINE_TEXT_CHARS and artifacts:
                        full = payload["text"]
                        ref = artifacts.save_text(full, extension="md", media_type="text/markdown; charset=utf-8")
                        refs.append(ref)
                        payload = {"text_artifact":ref["path"], "head":full[:1000], "tail":full[-1000:]}
                    journal.append("decision", {"kind": kind, **payload}, refs)
            def usage(report):
                row = ledger.record(report, self._selected_cost_entry(source.provider, source.model))
                if row and session:
                    session.usage = list(ledger.rows)
                    self.store.save_session(session)
                evidence("side_usage", report)
            def before(request):
                if self.session is not session or self.posture_refusal_for(source.provider, source.model):
                    return False
                return self._before_model_call(request, abort_override=side.abort)
            evidence("side_user", {"text": clean})
            if not is_local_provider(source.provider):
                self.call_from_thread(self._record_egress, clean)
            def thinking(text):
                evidence("side_thinking", {"text":text})
                self.call_from_thread(self._log, "[magenta]Side thinking:[/magenta] " + rich_escape(text))
            thoughts = SideThoughtBuffer(thinking)
            def answer(text):
                thoughts.flush()
                evidence("side_assistant", {"text":text})
                self.call_from_thread(self._log, "[b cyan]Side answer:[/b cyan] " + render_markdown(text))
            cb = TurnCallbacks(on_usage=usage, before_model_call=before,
                on_thinking_delta=thoughts.push,
                on_assistant=answer,
                on_error=lambda error:self.call_from_thread(self._log, "[red]Side question: " + rich_escape(error) + "[/red]"))
            side.turn(clean, cb)
        except Exception as exc:
            self.call_from_thread(self._log, "[red]Side question failed: " + rich_escape(str(exc)) + "[/red]")
        finally:
            if thoughts:
                thoughts.flush()
            self._side_agent = None
            self._side_running = False

    def _fork_command(self, rest: str) -> None:
        if self.turn_running or not self.session:
            self._log("[yellow]Finish or stop the current reply before branching.[/yellow]")
            return
        try:
            count = int(rest) if rest.strip() else None
        except ValueError:
            self._log("[yellow]Usage: /fork [message count], at the end of a completed reply.[/yellow]")
            return
        self._persist_session()
        self._log("[dim]Creating conversation branch…[/dim]")
        self._fork_worker(self.session, count)

    @work(thread=True, exclusive=True, group="session-fork")
    def _fork_worker(self, source, count) -> None:
        try:
            child = self.store.fork(source, count)
        except Exception as exc:
            self.call_from_thread(self._log, "[red]Branch failed: " + rich_escape(str(exc)) + "[/red]")
            return
        def open_child():
            if not self.turn_running and self.session is source:
                self._open_session(child)
                self._log("[cyan]Conversation branched. Fiji pixels and windows are unchanged; this is not an image rollback.[/cyan]")
            else:
                self._reload_sessions()
                self._log("[cyan]Branch saved; reopen it from the chat list.[/cyan]")
        self.call_from_thread(open_child)
    def _save_prompt_queue(self) -> None:
        if self.session:
            self.session.pending_turns = self.prompt_queue.snapshot()
            self.store.save_session(self.session)

    @work(thread=True, group="job-poll")
    def _poll_jobs(self) -> None:
        if not any(row.get("state") not in {"completed", "failed", "cancelled", "timed_out"} or not row.get("notified") for row in self.jobs.snapshot()):
            return
        if not self._jobs_poll_lock.acquire(blocking=False):
            return
        tracker, session_id = self.jobs, self.session.id if self.session else None
        try:
            tracker.poll(self.fiji, str(getattr(self.fiji, "expected_root", None) or ""))
            self.call_from_thread(self._job_poll_done, tracker, session_id)
        finally:
            self._jobs_poll_lock.release()

    def _job_poll_done(self, tracker, session_id) -> None:
        if self.jobs is not tracker or not self.session or self.session.id != session_id:
            return
        notices = tracker.take_notices()
        self.session.background_jobs = tracker.snapshot()
        self.store.save_session(self.session)
        for text in notices:
            self._evidence_append("decision", {"kind": "job_completed", "text": text})
            self._log("[cyan]" + rich_escape(text) + "[/cyan]")
            if not self._queue_prompt(text, "steer" if self.turn_running else "follow_up"):
                tracker.unclaim(text)
        self.session.background_jobs = tracker.snapshot()
        self.store.save_session(self.session)
        if notices and not self.turn_running and self.agent is not None:
            self._deliver_next_prompt()

    def _queue_prompt(self, text: str, mode: str) -> bool:
        if self._posture_change_pending:
            self._log("[yellow]Wait for the privacy change to finish before sending a correction.[/yellow]")
            return False
        try:
            row = self.prompt_queue.put(text, mode)
            self._save_prompt_queue()
            self._evidence_append("decision", {"kind": "prompt_queued", "id": row["id"], "mode": mode})
        except (ValueError, OSError) as exc:
            self._log("[yellow]" + rich_escape(str(exc)) + "[/yellow]")
            return False
        self._log("[dim]" + ("Correction will apply at the next tool boundary." if mode == "steer" else "Next task queued.") + "[/dim]")
        return True

    def _queue_command(self, text: str) -> None:
        if text.strip() == "clear":
            self.prompt_queue.clear()
            self._save_prompt_queue()
            self._log("[dim]Pending messages cleared.[/dim]")
        elif not text.strip() or text.strip() == "list":
            rows = self.prompt_queue.snapshot()
            self._log("[b]Pending messages[/b]\n" + ("\n".join(rich_escape(r["mode"] + ": " + r["text"][:160]) for r in rows) or "None."))
        else:
            self._queue_prompt(text, "follow_up")
            if not self.turn_running:
                self._deliver_next_prompt()

    def _take_steering(self) -> list[str]:
        if self._reload_pending:
            self._reload_pending = False
            self._reload_resources_now()
        rows = self.prompt_queue.take("steer", all_matches=True)
        if rows:
            self._save_prompt_queue()
        sanitizer = self._knowledge_sanitizer()
        return [sanitizer(row["text"]) if sanitizer else row["text"] for row in rows]

    def _deliver_next_prompt(self) -> None:
        rows = [row for row in self.prompt_queue.snapshot() if row["mode"] == "steer"]
        if not rows:
            rows = [row for row in self.prompt_queue.snapshot() if row["mode"] == "follow_up"]
        if rows:
            row = rows[0]
            self._submit_text(row["text"], literal=True, queued_id=row["id"])

    def _discover_skill_catalog(self) -> SkillCatalog:
        project = self._mention_folder() or Path.cwd()
        workspace = find_workspace()
        bundled = (workspace / "skills") if workspace is not None else None
        return discover_skills(
            project,
            user_skills_dir=console_config.CONFIG_DIR / "skills",
            bundled_skills_dir=bundled,
        )

    def _load_model_skill(self, name: str) -> str:
        catalog = self.skills or self._discover_skill_catalog()
        body = catalog.load_body(name, model_invoked=True)
        sanitizer = self._knowledge_sanitizer()
        from .project_context import protected_text
        return protected_text(body, sanitizer)

    @work(thread=True, group="resource-reload")
    def _reload_resources(self) -> None:
        self._reload_resources_now()

    def _reload_resources_now(self) -> None:
        try:
            self.skills = self._discover_skill_catalog()
            self._skill_diagnostics_shown = False
            self._apply_skill_catalog()
            if self.agent:
                active = getattr(self.agent, "_active_skill", "")
                if active.startswith("name: "):
                    name = active.splitlines()[0][6:]
                    try:
                        body = self.skills.load_body(name, model_invoked=False)
                        sanitizer = self._knowledge_sanitizer()
                        from .project_context import protected_text
                        self.agent.set_active_skill(name, protected_text(body, sanitizer))
                    except Exception:
                        self.agent.set_active_skill(None)
                        self.call_from_thread(self._log, "[yellow]Previously active skill is unavailable; it was cleared.[/yellow]")
            self._load_slash_file_choices()
            apply_context = getattr(self, "_apply_project_context", None)
            if apply_context:
                apply_context()
            self.call_from_thread(self._log, "[green]Skills, commands and folder instructions reloaded.[/green]")
        except Exception as exc:
            self.call_from_thread(self._log, "[red]Reload failed: " + rich_escape(str(exc)) + "[/red]")

    def _apply_project_context(self) -> None:
        if self.agent is None:
            return
        from .project_context import load_project_instructions
        profile = self._model_harness_profile()
        result = load_project_instructions(self._mention_folder() or Path.cwd(), find_workspace(), self._knowledge_sanitizer(),
            max_chars=min(32768, max(1024, profile["context_window"] // 2)))
        setter = getattr(self.agent, "set_project_instructions", None)
        if setter:
            setter(result.text)
        self.project_instruction_sources = result.sources
        if result.warnings:
            self.call_from_thread(self._event_log, "[yellow]" + rich_escape("; ".join(result.warnings)) + "[/yellow]")

    def _apply_skill_catalog(self, query: str = "") -> None:
        setter = getattr(self.agent, "set_skill_catalog", None)
        if not callable(setter):
            return
        try:
            self.skills = self._discover_skill_catalog()
            # Full metadata remains cheap and lets the model ask the user to
            # load a relevant body. Absolute source paths stay local.
            setter(self.skills.prompt_catalog(max_chars=8_000))
        except Exception as exc:
            setter("")
            self.last_evidence_error = f"skill discovery failed: {exc}"
            return
        if self.skills.diagnostics and not self._skill_diagnostics_shown:
            self._skill_diagnostics_shown = True
            self._event_log(
                f"[yellow]{len(self.skills.diagnostics)} skill discovery warning(s)[/yellow]")

    def _skills_command(self, query: str) -> None:
        try:
            self.skills = self._discover_skill_catalog()
            rows = self.skills.search(query) if query.strip() else list(self.skills.skills)
        except Exception as exc:
            self._log(f"[red]skill discovery failed: {rich_escape(str(exc))}[/red]")
            return
        if not rows:
            self._log("[dim]no matching guidance skills[/dim]")
            return
        self._log("[b]Guidance skills[/b]\n" + "\n".join(
            f"  {skill.name} · {rich_escape(skill.description)}"
            + (f" · recipe {rich_escape(skill.recipe)}" if skill.recipe else "")
            for skill in rows[:50]))

    def _skill_command(self, name: str) -> None:
        clean = (name or "").strip()
        setter = getattr(self.agent, "set_active_skill", None)
        if not callable(setter):
            self._log("[yellow]this provider cannot inject console skills[/yellow]")
            return
        if clean == "clear":
            setter(None, None)
            self._log("[dim]active guidance skill cleared[/dim]")
            return
        if not clean:
            self._log("[yellow]usage: /skill <name> or /skill clear[/yellow]")
            return
        try:
            self.skills = self._discover_skill_catalog()
            body = self.skills.load_body(clean, model_invoked=False)
            from .project_context import protected_text
            body = protected_text(body, self._knowledge_sanitizer())
        except Exception as exc:
            self._log(f"[red]could not load skill: {rich_escape(str(exc))}[/red]")
            return
        setter(clean, body)
        self._evidence_append("decision", {
            "kind": "skill_loaded", "name": clean,
        })
        self._log(
            f"[green]loaded guidance skill[/green] {rich_escape(clean)}; "
            "it advises the agent but does not execute its referenced recipe.")

    # ------------------------------------------------------ refinement review
    def _refine_command(self, rest: str) -> None:
        text = (rest or "").strip()
        if not text or text == "run":
            self._refine_session()
            return
        if text == "list":
            self._show_learning_drafts()
            return
        action, _, arguments = text.partition(" ")
        if action == "discard":
            self.pending_learning_drafts.clear()
            self._log("[dim]refinement drafts discarded[/dim]")
            return
        if action != "accept":
            self._log(
                "[yellow]usage: /refine [run|list|discard|accept <n> <scope>][/yellow]")
            return
        bits = arguments.split()
        if not bits or not bits[0].isdigit():
            self._log("[yellow]usage: /refine accept <n> [scope][/yellow]")
            return
        index = int(bits[0]) - 1
        scope = bits[1] if len(bits) > 1 else "session"
        if index < 0 or index >= len(self.pending_learning_drafts):
            self._log("[yellow]unknown draft number[/yellow]")
            return
        store = self._store_for_scope(scope)
        if store is None:
            self._log("[yellow]that scope needs an active project/session[/yellow]")
            return
        draft = self.pending_learning_drafts[index]
        try:
            entry = store.propose(
                kind=draft["kind"], scope=scope,
                title=draft["title"], content=draft["content"],
                applicability=draft.get("applicability") or {},
                evidence=[f"session:{self.session.id}" if self.session else "session-review"],
                source="refinement:draft",
            )
        except Exception as exc:
            self._log(f"[red]could not save draft: {rich_escape(str(exc))}[/red]")
            return
        self.pending_learning_drafts.pop(index)
        self._evidence_append("decision", {
            "kind": "refinement_candidate_accepted", "entry_id": entry.id,
            "scope": scope,
        })
        self._log(
            f"[green]saved as candidate[/green] {entry.id}; it still needs `/memory approve`."
        )

    def _show_learning_drafts(self) -> None:
        if not self.pending_learning_drafts:
            self._log("[dim]no pending refinement drafts[/dim]")
            return
        lines = ["[b]Untrusted learning drafts[/b]"]
        for index, draft in enumerate(self.pending_learning_drafts, 1):
            lines.append(
                f"  {index}. {draft['kind']} · {rich_escape(draft['title'])}\n"
                f"     {rich_escape(draft['content'][:240])}")
        lines.append("Use `/refine accept <n> <scope>` or `/refine discard`.")
        self._log("\n".join(lines))

    def _refinement_evidence_text(self) -> str:
        if self.evidence is None:
            return ""
        events = self.evidence.read_events()[-80:]
        compact_rows = [
            {"type": row.get("type"), "payload": row.get("payload"),
             "artifact_refs": row.get("artifact_refs", [])}
            for row in events
        ]
        text = json.dumps(compact_rows, ensure_ascii=False, default=str)
        text = self.path_tokens.redact(text)
        # Fail closed on unregistered absolute paths before a refinement call.
        text = re.sub(
            r"(?i)(?:\b[A-Z]:[\\/][^\s\"']+|/(?:Users|home|mnt|data|Volumes)/[^\s\"']+)",
            "[local-path-redacted]", text)
        return text[:80_000]

    @work(thread=True, exclusive=True, group="refinement")
    def _refine_session(self) -> None:
        proposer = getattr(self.agent, "propose_learnings", None)
        if not callable(proposer):
            self.call_from_thread(
                self._log, "[yellow]this provider cannot run a refinement review[/yellow]")
            return
        evidence_text = self._refinement_evidence_text()
        if not evidence_text or evidence_text == "[]":
            self.call_from_thread(self._log, "[yellow]no session evidence to review[/yellow]")
            return
        try:
            drafts = proposer(evidence_text, max_proposals=5)
        except Exception as exc:
            from .usage import ModelCallStopped
            if isinstance(exc, ModelCallStopped):
                return
            self.call_from_thread(
                self._log, f"[red]refinement failed: {rich_escape(str(exc))}[/red]")
            return
        safe: list[dict[str, Any]] = []
        for draft in drafts:
            title = self.path_tokens.redact(str(draft.get("title") or ""))
            content = self.path_tokens.redact(str(draft.get("content") or ""))
            if self._looks_like_raw_path(title + " " + content):
                continue
            safe.append({**draft, "title": title, "content": content})
        self.pending_learning_drafts = safe
        if self.config.provider and not is_local_provider(self.config.provider):
            self.call_from_thread(
                self._record_egress_bytes, len(evidence_text.encode("utf-8")),
                command="harness refinement", categories=("summary", "evidence"))
        self._evidence_append("decision", {
            "kind": "refinement_drafts_created", "count": len(safe),
        })
        self.call_from_thread(self._show_learning_drafts)

    # ------------------------------------------------------- harness review
    def _harness_stores(self) -> list[tuple[str, HarnessStore]]:
        stores: list[tuple[str, HarnessStore]] = []
        if self.session_harness is not None:
            stores.append(("session", self.session_harness))
        project = self._project_harness()
        if project is not None:
            stores.append(("project", project))
        stores.append(("user/shared", self.global_harness))
        return stores

    def _store_for_scope(self, scope: str) -> HarnessStore | None:
        if scope == "session":
            return self.session_harness
        if scope in ("project", "instrument"):
            return self._project_harness()
        if scope in ("user", "shared"):
            return self.global_harness
        return None

    def _find_harness_entry(self, entry_id: str) -> tuple[HarnessStore, Any] | None:
        for _label, store in self._harness_stores():
            try:
                return store, store.get(entry_id)
            except Exception:
                continue
        return None

    @staticmethod
    def _looks_like_raw_path(text: str) -> bool:
        return bool(re.search(
            r"(?i)(?:\b[A-Z]:[\\/][^\s]+|/(?:Users|home|mnt|data|Volumes)/[^\s]+)",
            text or ""))

    def _remember_command(self, rest: str) -> None:
        """Create a candidate only: /remember scope kind | title | content."""
        head, sep, remainder = (rest or "").partition("|")
        title, sep2, content = remainder.partition("|")
        bits = head.strip().split()
        if not sep or not sep2 or len(bits) != 2:
            self._log(
                "[yellow]usage: /remember <scope> <kind> | <title> | <content>[/yellow]")
            return
        scope, kind = bits
        store = self._store_for_scope(scope)
        if store is None:
            self._log("[yellow]that scope needs an active project/session[/yellow]")
            return
        # Durable knowledge never stores a known real path. Unknown path-like
        # strings are refused rather than guessed or silently persisted.
        safe_title = self.path_tokens.redact(title.strip())
        safe_content = self.path_tokens.redact(content.strip())
        if self._looks_like_raw_path(safe_title + " " + safe_content):
            self._log(
                "[red]memory refused: replace raw paths or sample identifiers with tokens[/red]")
            return
        try:
            entry = store.propose(
                kind=kind, scope=scope, title=safe_title, content=safe_content,
                evidence=[f"session:{self.session.id}" if self.session else "explicit-user"],
                source="user:/remember",
            )
        except Exception as exc:
            self._log(f"[red]memory proposal failed: {rich_escape(str(exc))}[/red]")
            return
        self._evidence_append("decision", {
            "kind": "harness_proposal", "entry_id": entry.id,
            "scope": entry.scope, "entry_kind": entry.kind,
        })
        self._log(
            f"[green]candidate recorded[/green] {entry.id} · {rich_escape(entry.title)}\n"
            "It is not shown to models until `/memory approve <id> | <evidence>`."
        )

    def _memory_command(self, rest: str) -> None:
        """Inspect or explicitly review harness entries."""
        text = (rest or "").strip()
        if not text:
            rows = []
            for label, store in self._harness_stores():
                try:
                    entries = store.list(include_expired=True)
                except Exception as exc:
                    rows.append(f"  {label}: unreadable ({rich_escape(str(exc))})")
                    continue
                for entry in entries[-30:]:
                    rows.append(
                        f"  {entry.id} · {entry.status}/{entry.scope}/{entry.kind} · "
                        f"v{entry.version} · {rich_escape(entry.title)}")
            self._log("[b]Harness knowledge[/b]\n" + (
                "\n".join(rows) if rows else "  [dim]no entries[/dim]"))
            return
        action, _, args = text.partition(" ")
        if action == "validation":
            entry_id, _, path = args.partition(" ")
            located = self._find_harness_entry(entry_id)
            if not located or not path.strip():
                self._log("[yellow]Usage: /memory validation <id> <receipt.json>.[/yellow]")
                return
            self._log("[dim]Checking frozen validation evidence; promotion remains a separate review.[/dim]")
            self._review_worker(located[0], located[1], {"action":"attach", "path":path.strip().strip('"')}, self.evidence)
            return
        if action == "search":
            found = []
            for _label, store in self._harness_stores():
                try:
                    found.extend(store.search(args, limit=10).entries)
                except Exception:
                    pass
            found.sort(key=lambda entry: (entry.status, entry.id))
            self._log("[b]Memory search[/b]\n" + ("\n".join(
                f"  {e.id} · {e.status}/{e.scope} · {rich_escape(e.title)}"
                for e in found[:20]) or "  [dim]no matches[/dim]"))
            return
        first, sep, reason = args.partition("|")
        pieces = first.strip().split()
        if not pieces:
            self._log("[yellow]memory command needs an entry id[/yellow]")
            return
        entry_id = pieces[0]
        located = self._find_harness_entry(entry_id)
        if located is None:
            self._log(f"[yellow]unknown memory id {rich_escape(entry_id)}[/yellow]")
            return
        store, entry = located
        review = reason.strip()
        try:
            if action == "approve":
                if not sep or not review:
                    raise ValueError("approval needs evidence after `|`")
                updated = store.promote(
                    entry.id, expected_version=entry.version,
                    reviewer="console-user", evidence=review)
            elif action == "deprecate":
                if not sep or not review:
                    raise ValueError("deprecation needs a reason after `|`")
                updated = store.deprecate(
                    entry.id, expected_version=entry.version,
                    reviewer="console-user", reason=review)
            elif action == "rollback":
                if len(pieces) != 2 or not pieces[1].isdigit() or not sep or not review:
                    raise ValueError("usage: /memory rollback <id> <version> | <reason>")
                updated = store.rollback(
                    entry.id, int(pieces[1]), expected_version=entry.version,
                    reviewer="console-user", reason=review)
            else:
                raise ValueError("use search, approve, deprecate, or rollback")
        except Exception as exc:
            self._log(f"[red]memory review failed: {rich_escape(str(exc))}[/red]")
            return
        self._evidence_append("decision", {
            "kind": f"harness_{action}", "entry_id": updated.id,
            "status": updated.status, "version": updated.version,
        })
        self._log(
            f"[green]{action} recorded[/green] {updated.id} · "
            f"{updated.status} · v{updated.version}")

    # ------------------------------------------------------------- compaction
    def _compaction_artifact_writer(self, payload: Any, metadata: Any) -> str | None:
        if self.artifacts is None:
            return None
        if isinstance(payload, bytes):
            ref = self.artifacts.save_bytes(payload, extension="bin")
        else:
            ref = self.artifacts.save_text(str(payload), extension="txt")
        return str(ref["path"])

    def _run_compaction(self, *, force: bool) -> dict[str, Any]:
        agent = self.agent
        compact_fn = getattr(agent, "compact", None)
        needs_fn = getattr(agent, "needs_compaction", None)
        if not callable(compact_fn):
            return {"compacted": False, "reason": "this provider owns its history"}
        profile = self._model_harness_profile()
        window = max(4_096, int(profile.get("context_window") or 32_000))
        reserve = min(16_384, max(1_024, window // 4))
        if not force and callable(needs_fn) and not needs_fn(window, reserve):
            return {"compacted": False, "reason": "below threshold"}
        keep = max(2_000, min(20_000, window // 3))
        report = compact_fn(
            keep_recent_tokens=keep,
            artifact_writer=self._compaction_artifact_writer,
        )
        if report.get("compacted"):
            self._evidence_append("compaction", report)
            if self.config.provider and not is_local_provider(self.config.provider):
                chars = int(report.get("summary_input_chars") or 0)
                self.call_from_thread(
                    self._record_egress_bytes, chars,
                    command="context compaction", categories=("summary",))
        return report

    def _compact_session_if_needed(self) -> None:
        report = self._run_compaction(force=False)
        if report.get("compacted"):
            before = report.get("tokens_before", "?")
            after = report.get("tokens_after", "?")
            self.call_from_thread(
                self._log, f"[dim]context compacted: ~{before} → ~{after} tokens; "
                           "full evidence retained[/dim]")

    @work(thread=True, exclusive=True, group="manual-compaction")
    def _compact_session(self, force: bool = True) -> None:
        report = self._run_compaction(force=force)
        if report.get("compacted"):
            self.call_from_thread(
                self._log,
                "[green]context compacted; the lossless evidence journal was not changed[/green]")
            self.call_from_thread(self._persist_session)
        else:
            self.call_from_thread(
                self._log, "[yellow]not compacted: "
                + rich_escape(str(report.get("reason") or "not needed")) + "[/yellow]")

    # ----------------------------------------------------- bundled knowledge
    def knowledge(self) -> "KnowledgeIndex | None":
        """The shipped recipe/reference/fix index, built once per session."""
        if self._knowledge is not None:
            return self._knowledge
        workspace = find_workspace()
        if workspace is None:
            return None
        ledger = console_config.CONFIG_DIR.parent / ".imagejai" / "ledger.json"
        try:
            self._knowledge = KnowledgeIndex(
                workspace / "recipes",
                workspace / "references",
                ledger_lookup=self._ledger_lookup,
                ledger_path=ledger if ledger.is_file() else None,
            )
        except Exception as exc:
            self.last_evidence_error = f"knowledge index failed: {exc}"
            return None
        return self._knowledge

    def _ledger_lookup(self, error_code: str, error_fragment: str, macro_prefix: str):
        """Ask the plugin for confirmed fixes; an offline Fiji simply has none."""
        try:
            response = self.fiji.command({
                "command": "ledger_lookup",
                "errorCode": error_code,
                "errorFragment": error_fragment,
                "macroPrefix": macro_prefix,
            })
        except Exception:
            return []
        result = response.get("result", response) if isinstance(response, dict) else {}
        entries = (result or {}).get("entries") or (result or {}).get("matches") or []
        return entries if isinstance(entries, list) else []

    def _knowledge_sanitizer(self):
        if self.posture.current is Posture.STANDARD:
            return None
        return self.path_tokens.redact

    def _knowledge_command(self, query: str) -> None:
        index = self.knowledge()
        if index is None:
            self._log("[yellow]no agent workspace found, so no bundled knowledge[/yellow]")
            return
        clean = (query or "").strip()
        if not clean:
            rows = index.rows()
            recipes = sum(1 for row in rows if row.kind == "procedure")
            references = sum(1 for row in rows if row.kind == "fact")
            self._log(
                f"[b]Bundled knowledge[/b]: {recipes} recipes, {references} references"
                + (f", {len(index.diagnostics)} warning(s)" if index.diagnostics else "")
                + "\n  /knowledge <question> to search, /reference <name> [section] to read."
            )
            return
        try:
            found = index.search(clean, self._knowledge_state(), limit=8,
                                 sanitizer=self._knowledge_sanitizer())
        except Exception as exc:
            self._log(f"[red]knowledge search failed: {rich_escape(str(exc))}[/red]")
            return
        if not found:
            self._log("[dim]nothing in the shipped recipes or references matched[/dim]")
            return
        lines = ["[b]Bundled knowledge[/b]"]
        for why, entry in found:
            lines.append(
                f"  · [{entry.kind}] {rich_escape(entry.title)}\n"
                f"      {rich_escape(entry.content[:200])}\n"
                f"      [dim]{rich_escape(entry.source)} — {rich_escape(why)}[/dim]")
        self._log("\n".join(lines))

    def _knowledge_state(self) -> dict:
        raw = (self._fiji_state or {}).get("raw") or {}
        active = (self._fiji_state or {}).get("active") or {}
        if not isinstance(active, dict):
            active = {}
        state = {
            "format": active.get("format"),
            "bit_depth": active.get("bit_depth") or active.get("type"),
            "channels": active.get("channels") or active.get("n_channels"),
            "slices": active.get("slices") or active.get("n_slices"),
            "frames": active.get("frames") or active.get("n_frames"),
        }
        return {key: value for key, value in state.items() if value is not None}

    def _reference_command(self, rest: str) -> None:
        index = self.knowledge()
        if index is None:
            self._log("[yellow]no agent workspace found[/yellow]")
            return
        parts = (rest or "").split(maxsplit=1)
        if not parts:
            self._log("[yellow]usage: /reference <name> [section][/yellow]")
            return
        name, section = parts[0], (parts[1] if len(parts) > 1 else None)
        try:
            body = index.load_reference(name, section)
        except Exception as exc:
            self._log(f"[red]{rich_escape(str(exc))}[/red]")
            return
        sanitizer = self._knowledge_sanitizer()
        if sanitizer is not None:
            body = sanitizer(body)
        self._log(f"[b]{rich_escape(name)}[/b]\n" + render_markdown(body))

    def _fixes_command(self, rest: str) -> None:
        index = self.knowledge()
        if index is None:
            self._log("[yellow]no agent workspace found[/yellow]")
            return
        text = (rest or "").strip()
        if not text:
            self._log("[yellow]usage: /fixes <error text>[/yellow]")
            return
        try:
            found = index.fixes("", text, "")
        except Exception as exc:
            self._log(f"[red]fix lookup failed: {rich_escape(str(exc))}[/red]")
            return
        if not found:
            self._log("[dim]no confirmed fix recorded for that error[/dim]")
            return
        lines = ["[b]Confirmed fixes[/b] [dim](software reliability, not scientific validity)[/dim]"]
        for entry in found:
            counts = entry.metadata or {}
            lines.append(
                f"  · {rich_escape(entry.title)}\n"
                f"      {rich_escape(entry.content[:240])}\n"
                f"      [dim]seen {counts.get('times_seen', '?')}×, "
                f"worked {counts.get('confirmations_true', '?')}, "
                f"failed {counts.get('confirmations_false', '?')}[/dim]")
        self._log("\n".join(lines))

    # --------------------------------------------------------- harness digest
    def _model_harness_profile(self) -> dict[str, Any]:
        reliability = "medium"
        context_window = 32_000
        vision = False
        for entry in self.catalog.curated_entries():
            if (entry.provider == self.config.provider
                    and entry.model_id == self.config.model):
                reliability = getattr(entry.reliability, "value", entry.reliability)
                context_window = entry.context_window or context_window
                vision = bool(entry.vision_capable)
                break
        return {
            "reliability": str(reliability),
            "context_window": int(context_window),
            "vision": vision,
        }

    def _project_harness(self) -> "HarnessStore | None":
        active_path = (self._fiji_state or {}).get("active_path")
        if not active_path:
            return None
        folder = Path(str(active_path)).parent
        if not folder.is_dir():
            return None
        root = folder / "AI_Exports" / ".imagejai-harness"
        return HarnessStore(root / "harness_state.json", root / "refinements.jsonl")

    def _apply_harness_digest(self, query: str) -> None:
        """Retrieve only confirmed knowledge and inject a small private digest."""
        setter = getattr(self.agent, "set_harness_digest", None)
        if not callable(setter):
            return
        profile = self._model_harness_profile()
        low = profile["reliability"] == "low" or profile["context_window"] <= 8_000
        profile["max_entries"] = 1 if low else 2
        profile["max_digest_chars"] = 1200 if low else 2400
        raw = (self._fiji_state or {}).get("raw") or {}
        active = (self._fiji_state or {}).get("active") or {}
        if not isinstance(active, dict):
            active = {}
        state = {
            "task": query,
            "_instrument_reviewed": self.instrument_status["reviewed"],
            "_instrument_fingerprint": self.instrument_fingerprint,
            "format": active.get("format"),
            "bit_depth": active.get("bit_depth") or active.get("type"),
            "channels": active.get("channels") or active.get("n_channels"),
            "slices": active.get("slices") or active.get("n_slices"),
            "frames": active.get("frames") or active.get("n_frames"),
            "instrument_profile": active.get("instrument_profile"),
        }
        state = {key: value for key, value in state.items() if value is not None}
        sanitizer = (
            (lambda value: value)
            if self.posture.current is Posture.STANDARD
            else self.path_tokens.redact
        )
        stores = [self.session_harness, self._project_harness(), self.global_harness]
        sections: list[str] = []
        try:
            for store in stores:
                if store is None:
                    continue
                rendered = store.digest(query, state, profile, sanitizer)
                lines = [line for line in rendered.splitlines()
                         if line and not line.startswith("[harness-digest]")]
                sections.extend(lines)
        except Exception as exc:
            self.last_evidence_error = f"harness retrieval failed: {exc}"
            setter("")
            return
        # Shipped recipes and references are curated package content, so they
        # can be offered alongside reviewed entries — clearly labelled, and
        # never mixed up with something a person approved in this session.
        bundled: list[str] = []
        index = self.knowledge()
        if index is not None:
            try:
                for _why, entry in index.search(
                        query, self._knowledge_state(),
                        limit=1 if low else 3, sanitizer=sanitizer):
                    bundled.append(
                        f"- [bundled/{entry.kind}] {entry.title}: "
                        f"{entry.content[:200 if low else 360]}"
                        f" (source {entry.source})")
            except Exception as exc:
                self.last_evidence_error = f"knowledge retrieval failed: {exc}"
        budget = 1800 if low else 5000
        digest = "[reviewed knowledge only]"
        if sections:
            digest += "\n" + "\n".join(sections)
        else:
            digest += "\nNo confirmed entries matched this task and image state."
        if bundled:
            digest += "\n[shipped with the package, not session-specific]\n" + "\n".join(bundled)
        setter(digest[:budget])

    # --------------------------------------------------- scientific evidence
    def _evidence_append(self, event_type: str, payload: Any,
                         artifact_refs: Any = None) -> None:
        journal = self.evidence
        if journal is None:
            return
        try:
            journal.append(event_type, payload, artifact_refs)
        except Exception as exc:
            # Evidence persistence must never corrupt or abort an analysis.
            # Keep the error visible in app state and report it after the turn.
            self.last_evidence_error = str(exc)

    def _record_text_evidence(self, event_type: str, text: str) -> None:
        value = str(text or "")
        if len(value) <= EVIDENCE_INLINE_TEXT_CHARS or self.artifacts is None:
            self._evidence_append(event_type, {"text": value})
            return
        try:
            ref = self.artifacts.save_text(
                value, extension="md", media_type="text/markdown; charset=utf-8")
            self._evidence_append(event_type, {
                "text_artifact": ref["path"],
                "sha256": ref["sha256"],
                "size": ref["size"],
                "head": value[:1000],
                "tail": value[-1000:],
                "disclosure": "Full text is stored as a session artifact.",
            }, [ref])
        except Exception as exc:
            self.last_evidence_error = str(exc)

    def _record_and_show_tool_result(self, correlation_id: str, name: str,
                                     args: dict, ok: bool, result: str) -> None:
        # The display callback receives a clipped preview in some providers.
        # Render once from the lossless callback so a click always has the full
        # return and concurrent calls keep their own submitted arguments.
        artifact_path = self._record_tool_evidence(correlation_id, name, args, ok, result)
        self.call_from_thread(self._on_tool_result_ui, name, ok, result, args, artifact_path)

    def _record_tool_evidence(self, correlation_id: str, name: str,
                              args: dict, ok: bool, result: str) -> Path | None:
        self.jobs.observe(name, args, ok, result, str(getattr(self.fiji, "expected_root", None) or ""))
        if self.session:
            self.session.background_jobs = self.jobs.snapshot()
        with self._macro_journal_lock:
            if self.macro_journal.record_tool_run(name, args, ok, result) is not None:
                self._save_macro_journal()
        journal = self.evidence
        if journal is None:
            return
        result_value: Any = result
        refs: list[dict] = []
        artifact_path = None
        if len(result) > EVIDENCE_INLINE_TEXT_CHARS and self.artifacts is not None:
            try:
                ref = self.artifacts.save_text(
                    result, extension="txt", media_type="text/plain; charset=utf-8")
                refs.append(ref)
                artifact_path = self.artifacts.session_dir / ref["path"]
                result_value = {
                    "artifact": ref["path"], "sha256": ref["sha256"],
                    "size": ref["size"], "head": result[:1000],
                    "tail": result[-1000:],
                    "summary": summarize_result(activity.tool_key(name, args), ok, result).text,
                    "disclosure": "Full tool result is stored as a session artifact.",
                }
            except Exception as exc:
                self.last_evidence_error = str(exc)
                return
        try:
            journal.record_tool_pair(
                name, args, result_value, ok=ok, correlation_id=correlation_id,
                result_artifacts=refs,
            )
        except Exception as exc:
            self.last_evidence_error = str(exc)
        return artifact_path

    def _record_turn_checkpoint(self) -> None:
        state = self._fiji_state or {}
        raw = state.get("raw") if isinstance(state.get("raw"), dict) else {}
        active = state.get("active") or {}
        if not isinstance(active, dict):
            active = {}
        try:
            checkpoint = build_scientific_checkpoint(
                image_id=active.get("id") or active.get("image_id"),
                image_revision=active.get("revision") or active.get("image_revision"),
                dataset_tokens=sorted(self.path_tokens.mapping()),
                c=active.get("channel") or active.get("c"),
                z=active.get("slice") or active.get("z"),
                t=active.get("frame") or active.get("t"),
                calibration=active.get("calibration") or raw.get("calibration") or {},
                roi=raw.get("roi") or raw.get("roi_state"),
                macros=[], result_artifacts=[], approvals=[], pending_jobs=[], decisions=[],
            )
            self._evidence_append("checkpoint", checkpoint)
        except Exception as exc:
            self.last_evidence_error = str(exc)

    # approval bridge: called from the turn thread
    def _approve_host_code(self, name: str, args: dict) -> bool:
        if name in self._always_allow_host_code:
            return True
        result = {"decision": "deny"}
        done = threading.Event()

        def cb(decision: str) -> None:
            result["decision"] = decision
            done.set()

        self.call_from_thread(self._push_approval, name, args, cb)
        if not done.wait(timeout=600):
            # No answer in ten minutes: fail closed.
            return False
        decision = result["decision"]
        self._evidence_append("approval", {
            "tool": name, "arguments": args, "decision": decision,
        })
        if decision == "always":
            # Only an explicit "always" widens the grant for the session.
            self._always_allow_host_code.add(name)
        return decision in ("once", "always")

    def _push_approval(self, name: str, args: dict, cb) -> None:
        def screen_cb(decision: str | None) -> None:
            cb(decision or "deny")

        self.push_screen(ApprovalScreen(name, args), screen_cb)

    @work(thread=True, exclusive=True, group="turn")
    def _run_turn(self, text: str, abort: AbortFlag) -> None:
        try:
            agent = self.agent
            if agent is None or abort.set_flag:
                return
            session = self.session
            if session is not None and text:
                session.derive_title(text)
            self._compact_session_if_needed()
            self._apply_image_policy()
            result_filter = getattr(agent, "set_tool_result_filter", None)
            if callable(result_filter):
                result_filter(self._knowledge_sanitizer())
            self._apply_skill_catalog(text)
            self._apply_project_context()
            self._refresh_instrument(force=True)
            self._apply_harness_digest(text)
            # Record outbound data only if the user has not interrupted setup.
            if abort.set_flag:
                return
            self._record_turn_checkpoint()
            if self.config.provider and self.config.model:
                if not is_local_provider(self.config.provider):
                    self.call_from_thread(self._record_egress, text)
            cb = TurnCallbacks(
                on_user=lambda t: (
                    self._record_text_evidence("user", t),
                    self.call_from_thread(
                        self._log, f"[b cyan]you:[/b cyan] {rich_escape(t)}")
                ),
                on_assistant=lambda t: self.call_from_thread(self._on_assistant_ui, t),
                on_tool_start=lambda n, a: self.call_from_thread(
                    self._on_tool_start_ui, n, a
                ),
                on_tool_record=self._record_and_show_tool_result,
                on_image_attached=self._record_image_attachment,
                on_usage=self._record_usage,
                before_model_call=self._before_model_call,
                take_steering=self._take_steering,
                on_error=lambda e: (
                    self._evidence_append("decision", {"kind": "turn_error", "message": str(e)}),
                    self.call_from_thread(
                        self._on_turn_error_ui, str(e))
                ),
                on_done=lambda ok: (
                    self._evidence_append("decision", {"kind": "turn_complete", "ok": bool(ok)}),
                    self.call_from_thread(self._finish_turn, ok)
                ),
                on_approval=self._approve_host_code,
                on_text_delta=lambda t: self.call_from_thread(self._on_text_delta_ui, t),
                on_thinking_delta=lambda t: self.call_from_thread(self._on_thinking_delta_ui, t),
                on_tool_preparing=lambda n: self.call_from_thread(self._on_tool_preparing_ui, n),
            )
            if not abort.set_flag:
                agent.turn(text, cb)
        except Exception as exc:
            if not abort.set_flag:
                self.call_from_thread(
                    self._log, f"[red]reply setup failed: {rich_escape(str(exc))}[/red]")
        finally:
            self.call_from_thread(self._complete_turn)

    def _complete_turn(self) -> None:
        was_cancelled = self._turn_cancel_requested or bool(self.abort and self.abort.set_flag)
        self._flush_live_text()
        self._persist_session()
        self.turn_running = False
        self._active_tools.clear()
        self._set_activity(activity.THINKING)
        self._turn_cancel_requested = False
        self._set_busy(False)
        self._finish_pending_clear()
        if self._pending_free_model:
            self._pending_free_model = False
            self.action_switch_model(free_only=True)
        elif self._pending_billing_failure:
            provider, error = self._pending_billing_failure
            self._pending_billing_failure = None
            def chosen(action):
                if action in {"model", "free"}:
                    self.action_switch_model(free_only=action == "free")
            self.push_screen(BillingFailureScreen(provider, error), chosen)
        elif not was_cancelled and getattr(self, "_turn_ok", False) and self.prompt_queue.snapshot():
            self.call_after_refresh(self._deliver_next_prompt)

    # ------------------------------------------------------- live streaming
    @property
    def _live_text(self) -> str:
        if self._live_fragments:
            self._live_text_value += "".join(self._live_fragments)
            self._live_fragments.clear()
        return self._live_text_value

    @_live_text.setter
    def _live_text(self, value: str) -> None:
        self._live_text_value = value
        self._live_fragments = []
        self._live_length = len(value)

    def _on_text_delta_ui(self, text: str) -> None:
        self._stream_delta_ui(text, "assistant")

    def _on_thinking_delta_ui(self, text: str) -> None:
        self._stream_delta_ui(text, "thinking")

    def _stream_delta_ui(self, text: str, kind: str) -> None:
        if not text or self._turn_cancel_requested:
            return
        if not self._active_tools:
            self._set_activity(activity.THINKING if kind == "thinking" else "Writing reply")
        if self._live_kind != kind:
            self._flush_live_text()
            self._live_kind = kind
        self._live_fragments.append(text)
        self._live_length += len(text)
        # Back-pressure: a fast stream must not repaint on every token.
        now = time.monotonic()
        if now - self._live_text_last_paint < LIVE_TEXT_MIN_INTERVAL_S:
            return
        self._live_text_last_paint = now
        self._paint_live_text()

    def _paint_live_text(self) -> None:
        if not self._live_length or self._live_painted_length == self._live_length:
            return
        live = _safe_query(self, "#live-text", Static)
        if live is None:
            return
        follow = live.is_vertical_scroll_end
        live.styles.display = "block"
        label, style = ("Thinking", "bold magenta") if self._live_kind == "thinking" else ("Assistant", "bold green")
        live.update(Text.assemble((label, style), "\n", self._live_text))
        self._live_painted_length = self._live_length
        if follow:
            live.scroll_end(animate=False)

    def _flush_live_text(self) -> None:
        text, kind = self._live_text, self._live_kind
        self._clear_live_text()
        if not text.strip():
            return
        self._record_text_evidence(kind, text)
        if kind == "thinking":
            self._log(Text.assemble(("Thinking: ", "bold magenta"), text))
        else:
            self._log("[b green]assistant:[/b green] " + render_markdown(text))

    def _clear_live_text(self) -> None:
        self._live_text = ""
        self._live_painted_length = 0
        self._live_text_last_paint = 0.0
        live = _safe_query(self, "#live-text", Static)
        if live is not None:
            live.update("")
            live.styles.display = "none"

    def _tick_turn_status(self) -> None:
        # Paint a short final delta even when no further token arrives.
        self._paint_live_text()
        status = _safe_query(self, "#turn-status", Static)
        if status is None:
            return
        if not self.turn_running:
            if status.content != "":
                status.update("")
            return
        if self._turn_cancel_requested:
            if status.content != "[yellow]Stopping reply...[/yellow]":
                status.update("[yellow]Stopping reply...[/yellow]")
            return
        elapsed = time.monotonic() - self._activity_started
        status.update(activity.status_text(self._activity_label, elapsed, self._turn_tool_count))

    def _set_activity(self, label: str) -> None:
        if label != self._activity_label:
            self._activity_label = label
            self._activity_started = time.monotonic()

    def _on_tool_preparing_ui(self, name: str) -> None:
        if self._turn_cancel_requested or self._active_tools:
            return
        key = activity.tool_key(name)
        if key in {"run_macro", "run_macro_async", "run_script"}:
            label = activity.WRITING
        elif key == "run_shell":
            label = "Preparing command"
        else:
            label = "Preparing tool"
        self._set_activity(label)

    def _on_assistant_ui(self, text: str) -> None:
        if self._turn_cancel_requested:
            self._clear_live_text()
            return
        if self._live_kind == "thinking":
            self._flush_live_text()
        else:
            self._clear_live_text()
        if text and text.strip():
            self._record_text_evidence("assistant", text)
            self._log("[b green]assistant:[/b green] " + render_markdown(text))
            self._last_code_blocks = code_blocks(text)

    def _on_tool_start_ui(self, name: str, args: dict) -> None:
        if self._turn_cancel_requested:
            return
        self._turn_tool_count += 1
        self._flush_live_text()
        self._active_tools.append((name, args))
        self._set_activity(activity.tool_activity(name, args))
        self._log(activity.tool_start_text(name, args))

    def _on_turn_error_ui(self, error: str) -> None:
        self._flush_live_text()
        self._show_tool_result("Provider request", False, error,
                              description=ResultSummary(failure_summary(error), "error"))
        if failure_kind(error):
            self._pending_billing_failure = (self.config.provider, error)

    def _on_tool_result_ui(self, name: str, ok: bool, summary: str,
                           args: dict | None = None, artifact_path: Path | None = None) -> None:
        if self._turn_cancel_requested:
            return
        # Vendor streams may style a Shell call as its single Fiji operation;
        # evidence still contains the original Shell arguments.
        styled_name, styled_args = name, args
        if name.lower() in {"shell", "bash", "exec_command"} and args is not None:
            actions = fiji_actions(args.get("command") or args.get("cmd") or "")
            if len(actions) == 1 and actions[0].name != "Shell":
                styled_name, styled_args = actions[0].name, actions[0].args
        for index, (active_name, _args) in enumerate(self._active_tools):
            if active_name == name and (args is None or _args == args):
                if args is None:
                    args = _args
                self._active_tools.pop(index)
                break
        else:
            for index, (running_name, running_args) in enumerate(self._active_tools):
                if running_name == styled_name and running_args == styled_args:
                    self._active_tools.pop(index)
                    break
        self._set_activity(activity.tool_activity(*self._active_tools[-1]) if self._active_tools
                           else activity.THINKING)
        self._show_tool_result(name, ok, summary, args, artifact_path)

    def _show_tool_result(self, name: str, ok: bool, result: str,
                          args: dict | None = None, artifact_path: Path | None = None,
                          description: ResultSummary | None = None) -> None:
        self._next_tool_result_detail += 1
        ident = self._next_tool_result_detail
        description = description or summarize_result(activity.tool_key(name, args), ok, result)
        self._tool_result_details[ident] = ToolResultDetail(
            activity.displayed_tool_name(name, args), description.text,
            raw="" if artifact_path else result, artifact=artifact_path)
        self._log(activity.tool_result_text(name, ok, result, args, detail_id=ident, description=description))
        # Retain exactly the details still linked by the bounded transcript.
        log = _safe_query(self, "#chat-log", RichLog)
        if log is not None and log._size_known:
            visible = set()
            for line in log.lines:
                for segment in line:
                    action = segment.style.meta.get("@click", "") if segment.style else ""
                    match = re.fullmatch(r"app\.tool_result\((\d+)\)", action) if isinstance(action, str) else None
                    if match:
                        visible.add(int(match.group(1)))
            for key in self._tool_result_details.keys() - visible:
                del self._tool_result_details[key]

    def action_tool_results(self) -> None:
        rows = [{"label": f"{detail.name}: {detail.summary}", "detail_id": ident}
                for ident, detail in reversed(list(self._tool_result_details.items()))]
        self.push_screen(ListPickerScreen("Tool returns", {"items": rows,
                         "empty": "No tool returns in this conversation view yet."}),
                         lambda choice: self.action_tool_result(choice["detail_id"]) if choice else None)

    def action_tool_result(self, ident: int) -> None:
        detail = self._tool_result_details.get(ident)
        if detail is None:
            return
        try:
            screen = ToolResultScreen(detail)
        except OSError as exc:
            self.toast(f"Could not open the full tool return: {exc}", "error")
            return
        candidate = _find_png_path(screen.raw)
        if candidate:
            preview = render_thumbnail_markup(candidate)
            if preview:
                screen.preview = Text.from_markup(preview)
        def restore_input(_result: None) -> None:
            chat_input = _safe_query(self, "#chat-input", Input)
            if chat_input is not None:
                chat_input.focus()
        self.push_screen(screen, restore_input)

    def _set_busy(self, busy: bool) -> None:
        inp = _safe_query(self, "#chat-input", Input)
        if inp is None:
            return
        # Keep the next draft editable while the current reply runs.
        inp.disabled = False

    def _finish_turn(self, ok: bool) -> None:
        self._turn_ok = bool(ok)
        if self._turn_cancel_requested:
            return
        if not ok:
            self._log("[yellow]turn ended with errors — see above[/yellow]")
        if self.last_evidence_error:
            self._log(
                "[red]evidence journal error: "
                + rich_escape(self.last_evidence_error)
                + "[/red]")
            self.last_evidence_error = None

    # ------------------------------------------------------- budget/export
    def _selected_cost_entry(self, provider=None, model=None):
        from .model_choice import subscription_entries
        provider = provider or self.config.provider
        model = model or self.config.model
        entries = [*subscription_entries(), *self.catalog.offline().models]
        return next((e for e in entries if e.provider == provider and e.model_id == model), None)

    def _pending_cost_notice(self, provider, model):
        if not provider or not model:
            return None
        entry = self._selected_cost_entry(provider, model)
        if entry and entry.key not in self.config.model_cost_observations:
            self.config.model_cost_observations.update(snapshot_of([entry]))
            self.config.model_cost_observations[entry.key]["observed_on"] = self.catalog.today().isoformat()
            self.config.save()
        variant = cost_variant(provider, model, entry)
        if variant is None or self.session is None:
            return None
        model_key = f"model:{provider}/{model}:{variant}"
        provider_key = f"provider:{provider}:{variant}"
        if model_key in self.session.cost_notice_acknowledged or provider_key in self.session.cost_notice_acknowledged:
            return None
        return entry, variant, model_key, provider_key

    def _accept_cost_notice(self, session, provider, model, details, remember):
        entry, variant, model_key, provider_key = details
        for key in (model_key, provider_key if remember else model_key):
            if key not in session.cost_notice_acknowledged:
                session.cost_notice_acknowledged.append(key)
        self.store.save_session(session)
        self._evidence_append("decision", {"kind": "cost_notice_accepted", "provider": provider,
                                           "model": model, "variant": variant, "remember": remember})

    def _cost_notice_before_send(self, text: str, literal: bool, queued_id=None) -> bool:
        provider, model = self.config.provider, self.config.model
        details = self._pending_cost_notice(provider, model)
        if details is None:
            return False
        entry, variant, _model_key, _provider_key = details
        session = self.session
        def chosen(choice):
            if self.session is not session:
                return
            action, remember = choice or ("cancel", False)
            if action == "continue":
                self._accept_cost_notice(session, provider, model, details, remember)
                self._submit_text(text, literal=literal, queued_id=queued_id)
            else:
                inp = _safe_query(self, "#chat-input", Input)
                if inp is not None:
                    inp.value = text
                    inp.focus()
                if action == "free":
                    self.action_switch_model(free_only=True)
        self.push_screen(CostNoticeScreen(provider, notice_text(provider, model, variant, entry)), chosen)
        return True

    def _cost_notice_before_model_call(self, request, abort) -> bool:
        """Also cover paid refinement and compaction started outside chat."""
        done = threading.Event()
        decision = {"proceed": False, "screen": None}
        provider, model = request["provider"], request["model"]
        def show():
            details = self._pending_cost_notice(provider, model)
            if details is None:
                decision["proceed"] = True
                done.set()
                return
            session = self.session
            entry, variant, _model_key, _provider_key = details
            def chosen(choice):
                action, remember = choice or ("cancel", False)
                if self.session is session and action == "continue":
                    self._accept_cost_notice(session, provider, model, details, remember)
                    decision["proceed"] = True
                else:
                    self._turn_cancel_requested = True
                    if action == "free":
                        if self.turn_running:
                            self._pending_free_model = True
                        else:
                            self.call_later(self.action_switch_model, free_only=True)
                done.set()
            screen = CostNoticeScreen(provider, notice_text(provider, model, variant, entry))
            decision["screen"] = screen
            self.push_screen(screen, chosen)
        self.call_from_thread(show)
        answered = interruptible_choice(done, abort)
        if not answered:
            def close():
                screen = decision["screen"]
                if screen is not None and self.screen is screen:
                    screen.dismiss(None)
            self.call_from_thread(close)
        return answered and decision["proceed"] and not abort.set_flag

    def _catalog_refreshed(self, result) -> None:
        self._cost_notifications = changed_notices(self.config.model_cost_observations,
            result.models, self.config.dismissed_cost_notices, self.catalog.today())
        new_pins = [e for e in result.models if e.pinned and e.key not in self.config.model_cost_observations]
        if new_pins:
            self.config.model_cost_observations.update(snapshot_of(new_pins))
            for entry in new_pins:
                self.config.model_cost_observations[entry.key]["observed_on"] = self.catalog.today().isoformat()
            self.config.save()
        for notice in self._cost_notifications:
            self._log(Text(notice.title + ": " + notice.body + "  /cost-notices to review. Registry checked " + self.catalog.today().isoformat() + "; not a provider bill.", style="yellow"))

    def action_cost_notices(self) -> None:
        self._catalog_refreshed(self.catalog.offline())
        rows = [{"label": n.title, "notice": n} for n in self._cost_notifications]
        def picked(choice):
            if not choice:
                return
            notice = choice["notice"]
            def decided(action):
                if action == "Dismiss":
                    self.config.dismissed_cost_notices.append(notice.id)
                    current = next((e for e in self.catalog.offline().models if e.key == notice.model_key), None)
                    if current:
                        self.config.model_cost_observations.update(snapshot_of([current]))
                        self.config.model_cost_observations[current.key]["observed_on"] = self.catalog.today().isoformat()
                    self.config.save()
            self.push_screen(ConfirmationScreen(notice.title, notice.body + "\nSource: the model registry. Dismissal applies to this change; another price change will appear again.",
                             ["Dismiss", "Keep notice"]), decided)
        self.push_screen(ListPickerScreen("Model cost changes", {"items": rows,
                         "empty": "No undismissed changes for used or pinned models."}), picked)

    def _record_usage(self, report: dict) -> None:
        # Turn workers send one completed request at a time; persist immediately
        # so restarting between tool rounds does not erase the earlier cost.
        ledger = getattr(self, "usage_ledger", None)
        if ledger is None or self.session is None:
            return
        entry = next((e for e in self.catalog.offline().models if e.provider == report["provider"]
                      and e.model_id == report["model"]), None)
        row = ledger.record(report, entry)
        if row is not None:
            self.session.usage = list(ledger.rows)
            self.store.save_session(self.session)
            self._evidence_append("decision", {"kind": "model_usage", **row})

    def _before_model_call(self, request: dict, abort_override=None) -> bool:
        # A manual summary/review must not inherit a previously stopped reply.
        abort = abort_override or ((self.abort or getattr(self.agent, "abort", None) or AbortFlag()) if self.turn_running else AbortFlag())
        if abort.set_flag:
            return False
        if not self._cost_notice_before_model_call(request, abort):
            return False
        ledger = getattr(self, "usage_ledger", None)
        if not self.config.budget_enabled or ledger is None:
            return True
        entry = next((e for e in self.catalog.offline().models if e.provider == request["provider"]
                      and e.model_id == request["model"]), None)
        if cost_variant(request["provider"], request["model"], entry) is None:
            return True
        unknown = ledger.unknown_requests > 0 or entry is None or entry.input_usd_per_mtok is None or entry.output_usd_per_mtok is None
        if not unknown and ledger.total_usd < self.config.budget_ceiling_usd:
            return True
        done = threading.Event()
        decision = {"proceed": False}
        screen = BudgetCeilingScreen(ledger.total_usd, self.config.budget_ceiling_usd, unknown)
        def chosen(choice):
            action, ceiling = choice or ("stop", None)
            if action in {"stop", "free"}:
                self._turn_cancel_requested = True
            if action == "raise" and ceiling is not None:
                self.config.budget_ceiling_usd = ceiling
                self.config.save()
                decision["proceed"] = True
            elif action == "once":
                decision["proceed"] = True
            elif action == "free":
                if self.turn_running:
                    self._pending_free_model = True
                else:
                    self.call_later(self.action_switch_model, free_only=True)
            self._evidence_append("decision", {"kind": "budget_pause", "choice": action,
                                  "ceiling_usd": self.config.budget_ceiling_usd, "known_cost_usd": ledger.total_usd,
                                  "unknown_cost": unknown})
            done.set()
        self.call_from_thread(self.push_screen, screen, chosen)
        answered = interruptible_choice(done, abort)
        if not answered:
            def close():
                if self.screen is screen:
                    screen.dismiss(("stop", None))
            self.call_from_thread(close)
        return answered and decision["proceed"] and not abort.set_flag

    def _set_budget(self, argument: str) -> None:
        if not argument:
            self._show_budget()
            return
        if self.turn_running:
            self._log("[yellow]Use the spending-pause dialog or stop the reply before changing its limit.[/yellow]")
            return
        if argument.lower() == "off":
            self.config.budget_enabled = False
        else:
            try:
                self.config.budget_ceiling_usd = positive_limit(argument)
            except ValueError as exc:
                self._log(Text(str(exc) + "; use /budget <US dollars> or /budget off", style="yellow"))
                return
            self.config.budget_enabled = True
        self.config.save()
        self._log(Text("Session spending pause: " + (f"${self.config.budget_ceiling_usd:g}" if self.config.budget_enabled else "off")))

    def _show_budget(self) -> None:
        self._log(Text("Spending pause: " + (f"${self.config.budget_ceiling_usd:g}" if self.config.budget_enabled else "off")
                       + ". Set with /budget <US dollars>; disable with /budget off."))
        if not (self.config.provider and self.config.model) or self.agent is None:
            self._log("[yellow]log in first (ctrl+l)[/yellow]")
            return
        ledger = getattr(self, "usage_ledger", None)
        if ledger is not None and ledger.rows:
            self._log(Text("Session usage\n" + ledger.summary()))
            return
        est = estimate_usage(self.agent.messages, self.config.provider, self.config.model)
        lines = [
            f"[b]Session usage — {self.config.provider} / {self.config.model}[/b]",
            f"messages: {len(self.agent.messages)}",
            f"input tokens (est):  {est.input_tokens:>10,}",
            f"output tokens (est): {est.output_tokens:>10,}",
        ]
        if est.priced:
            lines += [
                f"input cost (est):    ${est.input_usd:>10.4f}",
                f"output cost (est):   ${est.output_usd:>10.4f}",
                f"[b]total (est):         ${est.total_usd:>10.4f}[/b]",
                f"(tokenizer multiplier ×{est.tokenizer_multiplier:g} applied; "
                "estimates are chars/4; this is old conversation size, not cumulative request usage)",
            ]
        else:
            lines.append("no pricing in models.yaml for this model — token estimate only")
        self._log("\n".join(lines))

    def _export_session(self) -> None:
        if self.session is None:
            self._log("[yellow]nothing to export yet[/yellow]")
            return
        from .config import CONFIG_DIR

        out_dir = CONFIG_DIR / "console" / "exports"
        out_dir.mkdir(parents=True, exist_ok=True)
        stamp = time.strftime("%Y%m%d-%H%M%S")
        slug = "".join(c if c.isalnum() else "-" for c in self.session.title.lower())[:40].strip("-") or "session"
        path = out_dir / f"{stamp}-{slug}-{time.time_ns()}.md"
        messages = self.agent.messages if self.agent is not None else self.session.messages
        try:
            events = self.evidence.read_events() if self.evidence else []
            entries = replay_entries(events, messages, self.session.action_receipts)
        except (OSError, ValueError) as exc:
            self._log(f"[red]export failed: {rich_escape(str(exc))}[/red]")
            return
        parts = [
            f"# ImageJAI Console — {self.session.title}",
            "",
            f"- provider: {self.config.provider} / {self.config.model}",
            f"- exported: {time.strftime('%Y-%m-%d %H:%M:%S')}",
            f"- messages: {len(messages)}",
            "",
        ]
        try:
            for entry in entries:
                content = entry.text
                if entry.artifact and self.artifacts:
                    with self.artifacts.path_for(entry.artifact).open(encoding="utf-8", newline="") as stream:
                        content = stream.read()
                title = entry.kind + (f": {entry.name}" if entry.name else "")
                if entry.kind == "tool_call":
                    content = json.dumps(entry.arguments, indent=2, ensure_ascii=False)
                parts.append(f"## {title}\n\n{content or '(structured turn)'}\n")
            path.write_text("\n".join(parts), encoding="utf-8")
        except (OSError, ValueError) as exc:
            self._log(f"[red]export failed: {rich_escape(str(exc))}[/red]")
            return
        self._log(f"[green]✓ exported[/green] {path}")

    def on_unmount(self) -> None:
        if self._validation_abort:
            self._validation_abort.set()
        if self._side_agent is not None:
            self._side_agent.abort.set()
        self.python_workspace.close()
        self._stop_events.set()
        self._confirmation_queue.clear()
        self._pending_confirmations.clear()
        self._active_confirmation = None
