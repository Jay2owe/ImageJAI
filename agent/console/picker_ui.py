"""Full model picker screen — the terminal form of the Swing dropdown.

Ports the parts of `ui/picker/ModelPickerButton.java` that make sense in a
terminal: provider grouping, pinned favourites, type-to-filter, a free-only
toggle, tier badges, deprecation notices, a refresh that runs live discovery,
and a per-provider status strip. The catalog work itself lives in
`agent.console.catalog`; this file is only the screen.
"""
from __future__ import annotations

from typing import Any

from rich.markup import escape as rich_escape
from textual import events, work
from textual.app import ComposeResult
from textual.binding import Binding
from textual.containers import Horizontal, Vertical
from textual.screen import ModalScreen
from textual.widgets import Input, Label, ListItem, ListView, OptionList, Static
from textual.widgets.option_list import Option

from .catalog import (
    BADGE_RETIRED,
    CatalogEngine,
    CatalogEntry,
    PROVIDER_DISPLAY_NAMES,
    RefreshResult,
    Tier,
    TIER_COLOURS,
    deprecation_notice,
    is_local_daemon_provider,
)
from .model_choice import DEFAULT_EFFORT, effort_levels, subscription_entries
from .cliagents import is_cloud_ollama_tag

# Glyphs follow the Swing set (AiRootPanel/ModelMenuItem): filled star = pinned,
# coloured dot = tier, warning triangle = deprecated.
_PIN_ON = "\u2605"
_PIN_OFF = "\u2606"
_DOT = "\u25cf"

_FREE_TIERS = {Tier.FREE, Tier.FREE_WITH_LIMITS}


def tier_markup(entry: CatalogEntry, today) -> str:
    """Coloured dot for the row's tier, or the retired red dot."""
    badge = entry.badge(today)
    rgb = TIER_COLOURS.get(badge, TIER_COLOURS[BADGE_RETIRED if badge == BADGE_RETIRED
                                               else Tier.UNCURATED.value])
    return f"[#{rgb[0]:02x}{rgb[1]:02x}{rgb[2]:02x}]{_DOT}[/]"


def row_label(entry: CatalogEntry, today, current_model: str | None = None) -> str:
    """One picker line: pin, tier dot, name, context window, notices."""
    pin = _PIN_ON if entry.pinned else _PIN_OFF
    name = rich_escape(entry.display_name or entry.model_id)
    parts = [f"{pin} {tier_markup(entry, today)} {name}"]
    if entry.model_id == current_model:
        parts.append("[green]\u2713[/green]")
    if entry.context_window:
        parts.append(f"[dim]{entry.context_window // 1000}k[/dim]")
    if entry.vision_capable:
        parts.append("[dim]vision[/dim]")
    if not entry.curated:
        parts.append("[dim]unverified[/dim]")
    notice = deprecation_notice(entry, today)
    if notice:
        parts.append(f"[yellow]\u26a0 {rich_escape(notice)}[/yellow]")
    return "  ".join(parts)


class ModelOptionList(OptionList):
    """Render visible options without mounting a widget for every model.

    Keep the selection interface shared with the previous picker and the
    effort list. Options themselves are data, not children needing layout.
    """

    @property
    def index(self) -> int | None:
        return self.highlighted

    @index.setter
    def index(self, value: int | None) -> None:
        self.highlighted = value

    def _skip_heading(self, direction: int) -> None:
        index = self.highlighted
        if index is None or not self.get_option_at_index(index).disabled:
            return
        # Page navigation may land on a provider heading. Prefer an enabled
        # row in the same direction, falling back at the start/end of the list.
        for step in (direction, -direction):
            stop = self.option_count if step > 0 else -1
            for target in range(index, stop, step):
                if not self.get_option_at_index(target).disabled:
                    self.highlighted = target
                    return

    def action_page_down(self) -> None:
        super().action_page_down()
        if self.highlighted is None:
            self.action_last()
        self._skip_heading(1)

    def action_page_up(self) -> None:
        super().action_page_up()
        if self.highlighted is None:
            self.action_first()
        self._skip_heading(-1)


class ModelPickerScreen(ModalScreen[dict]):
    """Browse every provider and model; returns {"provider":…, "model":…}.

    Type to filter, arrows or the mouse scroll, `enter` selects, `ctrl+s`
    pins, `ctrl+h` hides, `f2` toggles free-only, `f5` refreshes, `esc` cancels.
    """

    CSS = """
    ModelPickerScreen { align: center middle; }
    #picker-box { width: 96; max-width: 96%; height: 90%; border: round $accent; background: $surface; padding: 1 2; }
    #picker-title { text-style: bold; margin-bottom: 1; }
    #picker-search { border: round $accent; margin-bottom: 1; }
    #picker-list { height: 1fr; border: round $panel; margin-bottom: 1; padding: 0; }
    #picker-list > .option-list--option { padding: 0; }
    #picker-status { height: auto; color: $text-muted; }
    #picker-help { height: auto; color: $text-muted; }
    """

    BINDINGS = [
        Binding("escape", "cancel_pick", "Cancel", priority=True),
        Binding("f2", "toggle_free", "Free only"),
        Binding("f5", "refresh_now", "Refresh"),
        Binding("ctrl+s", "toggle_pin", "Pin"),
        Binding("ctrl+h", "toggle_hide", "Hide", priority=True),
    ]

    def __init__(self, engine: CatalogEngine, current_provider: str | None = None,
                 current_model: str | None = None, *, free_only: bool = False) -> None:
        super().__init__()
        self.engine = engine
        self.current_provider = current_provider
        self.current_model = current_model
        self.free_only = free_only
        self.result: RefreshResult | None = None
        self._rows: list[CatalogEntry | None] = []
        self._subscription_entries = subscription_entries()
        self._last_search: str | None = None
        self._refresh_generation = 0

    def compose(self) -> ComposeResult:
        with Vertical(id="picker-box"):
            yield Label("Choose a model", id="picker-title")
            yield Input(placeholder="type to filter (provider or model)", id="picker-search")
            yield ModelOptionList(id="picker-list")
            yield Static("", id="picker-status")
            yield Static(
                "[dim]enter select · ctrl+s pin · ctrl+h hide · f2 free only · "
                "f5 refresh · esc cancel[/dim]",
                id="picker-help",
            )

    def on_mount(self) -> None:
        # Offline first: curated yaml + the shared 24 h cache paint instantly.
        self.result = self.engine.offline()
        self._redraw()
        self.query_one("#picker-search", Input).focus()

    # -- data ------------------------------------------------------------
    def _entries(self) -> list[CatalogEntry]:
        if self.result is None:
            return []
        rows = [*self._subscription_entries, *self.result.models]
        needle = self.query_one("#picker-search", Input).value.strip().lower()
        if needle:
            rows = [
                e for e in rows
                if needle in e.model_id.lower()
                or needle in (e.display_name or "").lower()
                or needle in e.provider.lower()
            ]
        if self.free_only:
            rows = [e for e in rows
                    if e.tier in _FREE_TIERS or (is_local_daemon_provider(e.provider)
                       and not (e.provider == "ollama" and is_cloud_ollama_tag(e.model_id)))]
        return rows

    def _redraw(self) -> None:
        listview = self.query_one("#picker-list", ModelOptionList)
        selected = self._selected()
        preferred = (selected.provider, selected.model_id) if selected else (
            self.current_provider, self.current_model)
        self._rows = []
        options = []
        today = self.engine.today()
        rows = self._entries()
        self._last_search = self.query_one("#picker-search", Input).value.strip().lower()
        # No separate favourites section: the menu drops S3.4. A pinned row
        # keeps its star marker in place, inside its provider group.
        provider = None
        for entry in rows:
            if entry.provider != provider:
                provider = entry.provider
                name = PROVIDER_DISPLAY_NAMES.get(provider, provider)
                status = self.result.statuses.get(provider) if self.result else None
                mark = ""
                if status is not None:
                    mark = {"ready": "[green]\u2713[/green]",
                            "needs-setup": "[yellow]key needed[/yellow]",
                            "unavailable": "[red]\u2717[/red]"}.get(status.ui_status, "")
                options.append(Option(f"[b]{rich_escape(name)}[/b]  {mark}", disabled=True))
                self._rows.append(None)
            current = self.current_model if entry.provider == self.current_provider else None
            options.append(Option(row_label(entry, today, current)))
            self._rows.append(entry)
        # One update, rather than a mount/message/layout for every row.
        listview.clear_options().add_options(options)
        first = next((i for i, e in enumerate(self._rows)
                      if e is not None and (e.provider, e.model_id) == preferred), None)
        if first is None:
            first = next((i for i, e in enumerate(self._rows) if e is not None), None)
        listview.index = first
        self._render_status()

    def _render_status(self) -> None:
        status = self.query_one("#picker-status", Static)
        if self.result is None:
            status.update("")
            return
        shown = sum(1 for e in self._rows if e is not None)
        line = f"{shown} models · {self.result.summary()}"
        if self.free_only:
            line += " · free only"
        status.update(f"[dim]{rich_escape(line)}[/dim]")

    def _selected(self) -> CatalogEntry | None:
        index = self.query_one("#picker-list", ModelOptionList).index
        if index is None or index < 0 or index >= len(self._rows):
            return None
        return self._rows[index]

    # -- actions ---------------------------------------------------------
    def on_input_changed(self, event: Input.Changed) -> None:
        if (event.input.id == "picker-search"
                and event.input.value.strip().lower() != self._last_search):
            self._redraw()

    def on_key(self, event: events.Key) -> None:
        # Navigate from search without losing the ability to keep filtering.
        if self.query_one("#picker-search", Input).has_focus:
            action = {"down": "action_cursor_down", "up": "action_cursor_up",
                      "pagedown": "action_page_down", "pageup": "action_page_up"}.get(event.key)
            if action:
                getattr(self.query_one("#picker-list", ModelOptionList), action)()
                event.prevent_default()
                event.stop()

    def on_input_submitted(self, event: Input.Submitted) -> None:
        if event.input.id == "picker-search":
            entry = self._selected()
            if entry is not None:
                self.dismiss({
                    "provider": entry.provider, "model": entry.model_id,
                    "effort_levels": list(effort_levels(
                        entry.provider, entry.model_id, dict(entry.features))),
                })
            else:
                self.query_one("#picker-list", ModelOptionList).focus()

    def on_option_list_option_selected(self, event: OptionList.OptionSelected) -> None:
        entry = self._selected()
        if entry is not None:
            self.dismiss({
                "provider": entry.provider, "model": entry.model_id,
                "effort_levels": list(effort_levels(
                    entry.provider, entry.model_id, dict(entry.features))),
            })

    def action_cancel_pick(self) -> None:
        self.dismiss(None)

    def action_toggle_free(self) -> None:
        self.free_only = not self.free_only
        self._redraw()

    def action_toggle_pin(self) -> None:
        entry = self._selected()
        if entry is None:
            return
        self.engine.set_pinned(entry.provider, entry.model_id, not entry.pinned)
        self._reapply_overrides()

    def action_toggle_hide(self) -> None:
        entry = self._selected()
        if entry is None:
            return
        self.engine.set_hidden(entry.provider, entry.model_id, True)
        self._reapply_overrides()

    def _reapply_overrides(self) -> None:
        """Redraw from the override file without touching the network."""
        if self.result is None:
            return
        visible = self.engine.visible_models(list(self.result.all_models))
        self.result = RefreshResult(
            refreshed_at=self.result.refreshed_at,
            models=tuple(visible),
            all_models=self.result.all_models,
            statuses=self.result.statuses,
            added=self.result.added,
            removed=self.result.removed,
            changes=self.result.changes,
        )
        self._redraw()

    def action_refresh_now(self) -> None:
        self._refresh_generation += 1
        self.query_one("#picker-status", Static).update("[dim]\u27f3 refreshing…[/dim]")
        self._refresh_worker(self._refresh_generation)

    @work(thread=True, exclusive=True, group="picker-refresh")
    def _refresh_worker(self, generation: int) -> None:
        app = self.app
        try:
            result = self.engine.refresh()
        except Exception as exc:  # a dead endpoint must not kill the screen
            app.call_from_thread(self._finish_refresh, generation, None, str(exc))
            return
        app.call_from_thread(self._finish_refresh, generation, result, None)

    def _finish_refresh(self, generation, result, error):
        if not self.is_attached or self not in self.app.screen_stack or generation != self._refresh_generation:
            return
        if error is not None:
            self.query_one("#picker-status", Static).update(f"[red]refresh failed: {rich_escape(error)}[/red]")
        else:
            self._apply_refresh(result)

    def _apply_refresh(self, result: Any) -> None:
        notify = getattr(self.app, "_catalog_refreshed", None)
        if callable(notify):
            notify(result)
        self.result = result
        self._redraw()


class EffortPickerScreen(ModalScreen[str | None]):
    """Second stage of /model; also opened directly by /effort."""

    CSS = """
    EffortPickerScreen { align: center middle; }
    #effort-box { width: 68; height: auto; max-height: 85%;
                  border: round $accent; background: $surface; padding: 1 2; }
    #effort-list { height: auto; max-height: 12; border: round $panel; }
    #effort-help { color: $text-muted; margin-top: 1; }
    """
    BINDINGS = [Binding("escape", "cancel_pick", "Cancel", priority=True)]

    _DESCRIPTIONS = {
        "default": "Use the provider's normal effort",
        "minimal": "Fastest available reasoning",
        "low": "Quick, simple tasks",
        "medium": "Balanced analysis",
        "high": "More time for difficult analysis",
        "xhigh": "Deep analysis",
        "max": "Highest available effort",
        "ultra": "Extended deep analysis",
    }

    def __init__(self, provider: str, model: str, levels: tuple[str, ...],
                 current: str = DEFAULT_EFFORT) -> None:
        super().__init__()
        self.provider = provider
        self.model = model
        self.levels = (DEFAULT_EFFORT, *levels)
        self.current = current

    def compose(self) -> ComposeResult:
        with Vertical(id="effort-box"):
            yield Label(
                f"Reasoning effort for {rich_escape(self.provider)} / "
                f"{rich_escape(self.model)}", id="effort-title")
            yield ListView(id="effort-list")
            yield Static("[dim]enter select · esc keep current[/dim]", id="effort-help")

    def on_mount(self) -> None:
        rows = self.query_one("#effort-list", ListView)
        for level in self.levels:
            mark = "  ✓" if level == self.current else ""
            rows.append(ListItem(Label(
                f"{level}{mark}  [dim]{self._DESCRIPTIONS.get(level, '')}[/dim]"),
                name=level))
        rows.index = self.levels.index(self.current) if self.current in self.levels else 0
        rows.focus()

    def on_list_view_selected(self, event: ListView.Selected) -> None:
        if event.item.name in self.levels:
            self.dismiss(event.item.name)

    def action_cancel_pick(self) -> None:
        self.dismiss(None)
