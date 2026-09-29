"""Macro library, session code journal, and recipe list for the console rail.

Port of ``src/main/java/imagejai/ui/MacroLibrary.java``,
``ui/ScriptEditorBridge.java``, ``ui/MacroMenuItem.java`` (context menu),
``engine/SessionCodeJournal.java``, ``engine/CodeAutoNamer.java`` and the
recipe half of ``ui/LeftRail.java``.

Why the journal lives here: "Session Macros" is not a folder — it is the list
of code this session has run. The Swing rail read the Java journal directly;
the console runs its own macros, so it needs its own journal with the same
ring size, dedup rule, naming, and row format, or the three macro lists would
disagree about what a session macro is.

Feature ids: S2.8-S2.11 (macro lists, context menu, script editor, save),
S2.12-S2.13 (recipes), S2.16-S2.18 (session history).
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import re
import time
import unicodedata
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Any, Callable, Iterable, Mapping, Sequence

from .rail import RailResult, readable_message

__all__ = [
    "SUPPORTED_EXTENSIONS",
    "MacroSource",
    "MacroItem",
    "normalise_language",
    "extension_for_language",
    "language_for_path",
    "is_imagej_macro",
    "sanitize_file_name",
    "default_file_name",
    "saved_macro_directory",
    "saved_macro_path",
    "write_macro_item",
    "list_user_macros",
    "list_saved_macros",
    "list_session_macros",
    "list_all_macros",
    "macro_popup_model",
    "macro_context_menu",
    "context_menu_folder",
    "open_context_folder",
    "run_payload",
    "script_editor_payload",
    "slugify",
    "name_for",
    "describe_for",
    "canonicalise",
    "JournalEntry",
    "SessionCodeJournal",
    "RecipeItem",
    "list_recipes",
    "recipe_popup_model",
    "recipe_run_command",
    "load_recipe",
    "popup_action",
]


SUPPORTED_EXTENSIONS = (".ijm", ".groovy", ".py", ".js", ".bsh", ".clj", ".rb")
IMAGEJAI_DIR = "ImageJAI"
SAVED_MACRO_DIR = "macros"

EMPTY_MY_MACROS = "No .ijm files found in the ImageJ folder"
EMPTY_SESSION_MACROS = "No session macros yet"
EMPTY_ALL_MACROS = "No macros found"
EMPTY_RECIPES = "No recipes found"
SCANNING_ITEM = "Scanning..."
SCANNING_STATUS = "Scanning macros..."


class MacroSource:
    """The three macro origins with their menu caption and TCP source tag.

    Mirrors ``MacroLibrary.Source`` (MacroLibrary.java:34). The ``tcp_source``
    string is what the server records for the run, so it must stay byte-exact.
    """

    USER = "user"
    SESSION = "session"
    SAVED = "saved"

    TITLES = {
        USER: "My Macros",
        SESSION: "Session Macros",
        SAVED: "Saved ImageJAI Macros",
    }
    TCP_SOURCES = {
        USER: "rail:my-macros",
        SESSION: "rail:session-macros",
        SAVED: "rail:saved-macros",
    }
    # Grouped "All Macros" order (LeftRail.java:652).
    GROUP_ORDER = (SESSION, SAVED, USER)

    @classmethod
    def title(cls, source: str) -> str:
        return cls.TITLES.get(source, source)

    @classmethod
    def tcp_source(cls, source: str) -> str:
        return cls.TCP_SOURCES.get(source, "rail:unknown")


def normalise_language(language: str | None) -> str:
    return "" if language is None else language.strip().lower()


def extension_for_language(language: str | None) -> str:
    """File extension for a Fiji script language (MacroLibrary.java:221)."""
    lang = normalise_language(language)
    if lang.startswith("groov"):
        return "groovy"
    if lang.startswith("jython") or lang.startswith("python"):
        return "py"
    if lang.startswith("java") or lang.startswith("js") or lang.startswith("ecma"):
        return "js"
    if lang.startswith("bean"):
        return "bsh"
    if lang.startswith("cloj"):
        return "clj"
    if lang.startswith("ruby"):
        return "rb"
    return "ijm"


def language_for_path(path: str | Path | None) -> str:
    """Language implied by a file suffix (MacroLibrary.java:232)."""
    name = "" if path is None else Path(path).name.lower()
    for suffix, language in (
        (".ijm", "ijm"),
        (".groovy", "groovy"),
        (".py", "jython"),
        (".js", "javascript"),
        (".bsh", "beanshell"),
        (".clj", "clojure"),
        (".rb", "ruby"),
    ):
        if name.endswith(suffix):
            return language
    return "ijm"


def is_imagej_macro(language: str | None) -> bool:
    """True when the code runs through execute_macro (MacroLibrary.java:215)."""
    lang = normalise_language(language)
    return lang in ("", "ijm", "macro", "imagej macro")


@dataclass(frozen=True)
class MacroItem:
    """One runnable entry in a macro list (MacroLibrary.MacroItem)."""

    source: str
    name: str
    detail: str = ""
    language: str = "ijm"
    path: Path | None = None
    code: str | None = None
    session_entry_id: int = 0

    def load_code(self) -> str:
        """Code to run: inline session code, else the file (MacroLibrary.java:68)."""
        if self.code is not None:
            return self.code
        if self.path is None:
            return ""
        return Path(self.path).read_text(encoding="utf-8")

    def menu_text(self, include_source: bool = False) -> str:
        """Menu caption; the detail is suppressed when it repeats the name."""
        prefix = f"{MacroSource.title(self.source)}: " if include_source else ""
        text = f"{prefix}{self.name}"
        if self.detail and self.detail != self.name:
            text += f" ({self.detail})"
        return text

    def tooltip(self) -> str:
        if self.path is not None:
            return str(Path(self.path).resolve())
        return f"{normalise_language(self.language)} session entry"


# --------------------------------------------------------------------------
# names and paths
# --------------------------------------------------------------------------

_BAD_FILENAME_CHARS = re.compile(r'[\\/:*?"<>|]+')
_WHITESPACE = re.compile(r"\s+")


def sanitize_file_name(raw: str | None) -> str:
    """Windows-safe macro file name (MacroLibrary.java:246)."""
    if raw is None:
        return ""
    name = _WHITESPACE.sub("_", _BAD_FILENAME_CHARS.sub("_", raw.strip()))
    name = re.sub(r"^\.+", "", name)
    name = re.sub(r"^_+", "", name)
    return re.sub(r"_+$", "", name)


def _strip_supported_extension(name: str | None) -> str:
    if not name:
        return ""
    lower = name.lower()
    for ext in SUPPORTED_EXTENSIONS:
        if lower.endswith(ext):
            return name[: -len(ext)]
    return name


def _ensure_supported_extension(name: str, language: str | None) -> str:
    if not name:
        return ""
    lower = name.lower()
    for ext in SUPPORTED_EXTENSIONS:
        if lower.endswith(ext):
            return name
    return f"{name}.{extension_for_language(language)}"


def default_file_name(item: "MacroItem | None") -> str:
    """Pre-filled name in the Save Macro dialog (MacroLibrary.java:209)."""
    base = "macro" if item is None else item.name
    language = "ijm" if item is None else item.language
    return _ensure_supported_extension(
        sanitize_file_name(_strip_supported_extension(base)), language
    )


def saved_macro_directory(
    imagej_root: str | Path | None = None, home: str | Path | None = None
) -> Path:
    """``<ImageJ root>/ImageJAI/macros``, else ``~/.imagej-ai/macros``.

    MacroLibrary.java:183 / :369. The console cannot call ``IJ.getDirectory``,
    so the caller passes the Fiji root it already knows.
    """
    if imagej_root:
        return Path(imagej_root) / IMAGEJAI_DIR / SAVED_MACRO_DIR
    base = Path(home) if home else Path.home()
    config_dir = base if base.name == ".imagej-ai" else base / ".imagej-ai"
    return config_dir / SAVED_MACRO_DIR


def legacy_macro_directory(home: str | Path | None = None) -> Path:
    """Pre-1.0 saved macros (MacroLibrary.java:377)."""
    base = Path(home) if home else Path.home()
    config_dir = base if base.name == ".imagej-ai" else base / ".imagej-ai"
    return config_dir / "learned_macros"


def saved_macro_path(
    item: "MacroItem | None",
    raw_name: str,
    imagej_root: str | Path | None = None,
    home: str | Path | None = None,
) -> Path:
    """Target path for Save Macro (MacroLibrary.java:188).

    Raises ``ValueError("Enter a macro name")`` when sanitising leaves nothing,
    which is the exact message the Swing dialog shows.
    """
    file_name = _ensure_supported_extension(
        sanitize_file_name(raw_name), "ijm" if item is None else item.language
    )
    if not file_name:
        raise ValueError("Enter a macro name")
    return saved_macro_directory(imagej_root, home) / file_name


def write_macro_item(
    item: "MacroItem | None", dest: str | Path, overwrite: bool = False
) -> Path:
    """Write the macro code to ``dest`` (MacroLibrary.java:197).

    Existing files raise ``FileExistsError`` so the caller can show the
    "Overwrite <name>?" confirmation instead of clobbering work silently.
    """
    if item is None:
        raise ValueError("No macro selected")
    code = item.load_code()
    if not code or not code.strip():
        raise ValueError("Selected macro is empty")
    target = Path(dest)
    if target.exists() and not overwrite:
        raise FileExistsError(f"Overwrite {target.name}?")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(code, encoding="utf-8")
    return target


# --------------------------------------------------------------------------
# S2.8 the three macro lists
# --------------------------------------------------------------------------


def _relative_detail(path: Path, display_root: Path | None) -> str:
    if display_root is None:
        return ""
    try:
        return str(path.resolve().relative_to(display_root.resolve())).replace(os.sep, "/")
    except ValueError:
        return str(path.resolve())


def _is_supported(path: Path, ijm_only: bool) -> bool:
    name = path.name.lower()
    if ijm_only:
        return name.endswith(".ijm")
    return any(name.endswith(ext) for ext in SUPPORTED_EXTENSIONS)


def _walk_macros(
    root: Path, excluded: Path | None, source: str, ijm_only: bool
) -> list[MacroItem]:
    items: list[MacroItem] = []
    if not root.is_dir():
        return items
    excluded_resolved = excluded.resolve() if excluded else None
    for dirpath, dirnames, filenames in os.walk(root, onerror=lambda _e: None):
        current = Path(dirpath)
        if excluded_resolved is not None:
            resolved = current.resolve()
            if resolved == excluded_resolved or excluded_resolved in resolved.parents:
                dirnames[:] = []
                continue
        for filename in filenames:
            candidate = current / filename
            if _is_supported(candidate, ijm_only):
                items.append(
                    MacroItem(
                        source=source,
                        name=candidate.name,
                        detail=_relative_detail(candidate, root),
                        language=language_for_path(candidate),
                        path=candidate,
                    )
                )
    return items


def _sort_by_detail(items: list[MacroItem]) -> list[MacroItem]:
    # MacroLibrary.java:328 — detail first (so folders group), then name.
    return sorted(items, key=lambda i: (i.detail.lower(), i.name.lower()))


def list_user_macros(
    imagej_root: str | Path | None, saved_dir: str | Path | None = None
) -> list[MacroItem]:
    """``.ijm`` files under the ImageJ root, skipping ImageJAI's own folder."""
    if not imagej_root:
        return []
    root = Path(imagej_root)
    excluded = Path(saved_dir) if saved_dir else root / IMAGEJAI_DIR / SAVED_MACRO_DIR
    return _sort_by_detail(_walk_macros(root, excluded, MacroSource.USER, True))


def list_saved_macros(
    imagej_root: str | Path | None = None,
    workspace: str | Path | None = None,
    home: str | Path | None = None,
) -> list[MacroItem]:
    """ImageJAI-saved macros from all three folders, de-duplicated by path.

    MacroLibrary.java:117 — Fiji ``ImageJAI/macros``, the legacy
    ``~/.imagej-ai/learned_macros``, and ``<workspace>/agent/macro_sets``.
    """
    dirs: list[Path] = [saved_macro_directory(imagej_root, home)]
    dirs.append(legacy_macro_directory(home))
    if workspace:
        base = Path(workspace)
        root = base if base.name == "agent" else base / "agent"
        dirs.append(root / "macro_sets")

    items: list[MacroItem] = []
    seen: set[str] = set()
    for directory in dirs:
        if not directory.is_dir():
            continue
        for item in _walk_macros(directory, None, MacroSource.SAVED, False):
            key = str(Path(item.path).resolve()) if item.path else item.name
            if key not in seen:
                seen.add(key)
                items.append(item)
    return _sort_by_detail(items)


def list_session_macros(journal: "SessionCodeJournal") -> list[MacroItem]:
    """Code run this session, newest first (MacroLibrary.java:108)."""
    return [session_item(entry) for entry in journal.snapshot_current_session()]


def session_item(entry: "JournalEntry") -> MacroItem:
    """Journal entry as a macro item; detail shows ``x<runCount>`` when reused."""
    language = entry.language or "ijm"
    detail = normalise_language(language)
    if entry.run_count > 1:
        detail += f" x{entry.run_count}"
    return MacroItem(
        source=MacroSource.SESSION,
        name=entry.name,
        detail=detail,
        language=language,
        path=None,
        code=entry.code,
        session_entry_id=entry.id,
    )


def list_all_macros(
    journal: "SessionCodeJournal | None" = None,
    imagej_root: str | Path | None = None,
    workspace: str | Path | None = None,
    home: str | Path | None = None,
) -> list[MacroItem]:
    """Session + saved + user macros in the Java concatenation order."""
    items: list[MacroItem] = []
    if journal is not None:
        items.extend(list_session_macros(journal))
    items.extend(list_saved_macros(imagej_root, workspace, home))
    items.extend(list_user_macros(imagej_root))
    return items


def macro_popup_model(
    items: Sequence[MacroItem],
    *,
    grouped: bool = False,
    empty_text: str = EMPTY_ALL_MACROS,
) -> dict:
    """Popup contents for a macro button (LeftRail.java:644).

    Returns groups even in the flat case so one renderer handles both, and
    keeps the Java empty-state strings.
    """
    if not items:
        return {"empty": empty_text, "groups": []}
    if not grouped:
        return {"empty": None, "groups": [{"title": None, "items": list(items)}]}
    groups: list[dict[str, Any]] = []
    for source in MacroSource.GROUP_ORDER:
        section = [item for item in items if item.source == source]
        if section:
            groups.append(
                {
                    "title": MacroSource.title(source),
                    "items": section,
                    "separator_before": bool(groups),
                }
            )
    return {"empty": None, "groups": groups}


def macro_context_menu(item: MacroItem) -> list[dict[str, str]]:
    """Right-click actions on a macro row (S2.9, LeftRail.java:701).

    Session entries have no file, so the second action opens the ImageJAI
    macros folder instead of a containing folder.
    """
    folder_label = "Open macros folder" if item.path is None else "Open containing folder"
    return [
        {"id": "open_in_script_editor", "label": "Open in Script Editor"},
        {"id": "open_folder", "label": folder_label},
    ]


def context_menu_folder(
    item: MacroItem,
    imagej_root: str | Path | None = None,
    home: str | Path | None = None,
) -> Path:
    """Folder the "Open ... folder" action reveals (LeftRail.java:738)."""
    if item.path is None or Path(item.path).parent == Path(item.path):
        return saved_macro_directory(imagej_root, home)
    return Path(item.path).resolve().parent


def open_context_folder(
    item: MacroItem,
    imagej_root: str | Path | None = None,
    home: str | Path | None = None,
    *,
    opener: "Callable[[Path], None] | None" = None,
) -> Path:
    """Create the target folder if needed and reveal it in the file manager."""
    folder = context_menu_folder(item, imagej_root, home)
    folder.mkdir(parents=True, exist_ok=True)
    if opener is not None:
        opener(folder)
    elif sys.platform == "win32":
        os.startfile(str(folder))
    elif sys.platform == "darwin":
        subprocess.Popen(["open", str(folder)], start_new_session=True)
    else:
        subprocess.Popen(["xdg-open", str(folder)], start_new_session=True)
    return folder


def run_payload(item: MacroItem) -> dict:
    """TCP command that runs this entry (LeftRail.java:766).

    ImageJ macros go through ``execute_macro``; every other language through
    ``run_script``. Both carry the rail source tag so the audit trail shows
    which list the user clicked.
    """
    code = item.load_code()
    source = MacroSource.tcp_source(item.source)
    if is_imagej_macro(item.language):
        return {"command": "execute_macro", "code": code, "source": source}
    return {
        "command": "run_script",
        "language": normalise_language(item.language) or "groovy",
        "code": code,
        "source": source,
    }


# --------------------------------------------------------------------------
# S2.10 Script Editor bridge
#
# ScriptEditorBridge.java reflects into org.scijava.ui.swing.script.TextEditor
# from inside the JVM. The console is a separate process, so the same
# reflection has to run *in* Fiji: it is sent as a Groovy script through
# run_script. The class names, the instances-list reuse rule, open(File) vs
# createNewDocument(name, code), and the failure messages all come from
# ScriptEditorBridge.java:14-105.
# --------------------------------------------------------------------------

SCRIPT_EDITOR_CLASS = "org.scijava.ui.swing.script.TextEditor"
SCRIPT_EDITOR_MISSING = "Fiji's Script Editor is not installed"
SCRIPT_EDITOR_NO_CONTEXT = "Fiji's Script Editor context is unavailable"
_SCRIPT_EDITOR_GROOVY = """
import java.awt.Window
import javax.swing.SwingUtilities
def pluginLoader = Thread.currentThread().getContextClassLoader()
def loadClass = { String name ->
    try {
        return Class.forName(name)
    } catch (ClassNotFoundException first) {
        if (pluginLoader == null) throw first
        return Class.forName(name, true, pluginLoader)
    }
}
def openEditor = {
    def editorClass = null
    try {
        editorClass = loadClass("%(editor)s")
    } catch (ClassNotFoundException missing) {
        throw new IllegalStateException("%(missing)s", missing)
    }
    def editor = null
    try {
        def instances = editorClass.getField("instances").get(null)
        if (instances instanceof List) {
            for (int i = instances.size() - 1; i >= 0; i--) {
                def candidate = instances.get(i)
                if (editorClass.isInstance(candidate)
                        && (!(candidate instanceof Window) || candidate.isDisplayable())) {
                    editor = candidate
                    break
                }
            }
        }
    } catch (Throwable ignored) {
    }
    if (editor == null) {
        def contextClass = loadClass("org.scijava.Context")
        def helper = loadClass("net.imagej.legacy.IJ1Helper")
        def context = helper.getMethod("getLegacyContext").invoke(null)
        if (context == null) {
            throw new IllegalStateException("%(nocontext)s")
        }
        editor = editorClass.getConstructor(contextClass).newInstance(context)
    }
    %(load)s
    if (editor instanceof Window) {
        editor.setVisible(true)
        editor.toFront()
        editor.requestFocus()
    }
}
if (SwingUtilities.isEventDispatchThread()) {
    openEditor.call()
} else {
    Throwable[] failure = new Throwable[1]
    SwingUtilities.invokeAndWait({ ->
        try { openEditor.call() } catch (Throwable t) { failure[0] = t }
    } as Runnable)
    if (failure[0] != null) throw failure[0]
}
return "opened"
"""


def _groovy_string(value: str) -> str:
    """Quote a Python string as a Groovy double-quoted literal."""
    escaped = (
        value.replace("\\", "\\\\")
        .replace('"', '\\"')
        .replace("\r", "\\r")
        .replace("\n", "\\n")
        .replace("$", "\\$")
    )
    return f'"{escaped}"'


def script_editor_payload(item: MacroItem) -> dict:
    """run_script payload that opens this entry in Fiji's Script Editor."""
    if item is None:
        raise ValueError("No macro selected")
    if item.path is not None and Path(item.path).is_file():
        load = (
            'editorClass.getMethod("open", java.io.File.class)'
            f".invoke(editor, new java.io.File({_groovy_string(str(Path(item.path).resolve()))}))"
        )
    else:
        file_name = default_file_name(item)
        load = (
            'editorClass.getMethod("createNewDocument", String.class, String.class)'
            f".invoke(editor, {_groovy_string(file_name)},"
            f" {_groovy_string(item.load_code())}); "
            'def pane = editorClass.getMethod("getEditorPane").invoke(editor); '
            'pane.getClass().getMethod("setFileName", String.class)'
            f".invoke(pane, {_groovy_string(file_name)}); "
            'SwingUtilities.invokeLater({ -> '
            'editorClass.getMethod("setTitle", String.class)'
            f".invoke(editor, {_groovy_string('*' + file_name)})"
            ' } as Runnable)'
        )
    code = _SCRIPT_EDITOR_GROOVY % {
        "editor": SCRIPT_EDITOR_CLASS,
        "missing": SCRIPT_EDITOR_MISSING,
        "nocontext": SCRIPT_EDITOR_NO_CONTEXT,
        "load": load,
    }
    return {
        "command": "run_script",
        "language": "groovy",
        "code": code,
        "source": "rail:script-editor",
    }


def script_editor_failure(detail: str | None) -> str:
    """Wrap an unexpected editor failure (ScriptEditorBridge.java:96)."""
    text = (detail or "").strip()
    for known in (SCRIPT_EDITOR_MISSING, SCRIPT_EDITOR_NO_CONTEXT):
        if known in text:
            return known
    if text.startswith("Could not open Fiji's Script Editor:"):
        return text
    return f"Could not open Fiji's Script Editor: {text or 'unknown error'}"


# --------------------------------------------------------------------------
# S2.16-S2.18 session code journal (and the source of "Session Macros")
# --------------------------------------------------------------------------

HISTORY_RING_CAP = 200
MAX_INDEX_BYTES = 8 * 1024 * 1024
INDEX_FILE = "INDEX.json"
MAX_SLUG_LEN = 50

# CodeAutoNamer.java:32 — state-management ops that do not name a workflow.
PLUMBING_COMMANDS = (
    "close",
    "close all",
    "duplicate...",
    "duplicate",
    "select none",
    "select all",
    "make inverse",
    "clear results",
    "set measurements",
    "set measurements...",
    "properties...",
    "set scale...",
    "rename",
    "rename...",
)

_BOILERPLATE = re.compile(
    r"^(auto[\- ]generated|claude[\- ]agent[\- ]executed|run\s+by.*agent|ai[\- ]generated)",
    re.IGNORECASE,
)
_LEADING_COMMENT = re.compile(r"^\s*(?://\s*(.*)|#\s*(.*)|/\*\s*(.*?)(?:\*/)?\s*$)")
_RUN_CALL = re.compile(r"\brun\s*\(\s*\"([^\"]+)\"")
_DEF_GROOVY = re.compile(r"\bdef\s+([A-Za-z_][A-Za-z0-9_]*)")
_FUNCTION = re.compile(r"\bfunction\s+([A-Za-z_][A-Za-z0-9_]*)")
_ASSIGNMENT = re.compile(
    r"^\s*(?!if\b|for\b|while\b|return\b)([A-Za-z_][A-Za-z0-9_]*)\s*=", re.MULTILINE
)


def _truncate_slug(slug: str) -> str:
    if len(slug) <= MAX_SLUG_LEN:
        return slug
    cut = slug.rfind("__", 0, MAX_SLUG_LEN + 1)
    if cut > 0:
        return slug[:cut]
    cut = slug.rfind("_", 0, MAX_SLUG_LEN + 1)
    if cut > 0:
        return slug[:cut]
    return slug[:MAX_SLUG_LEN]


def slugify(text: str | None) -> str:
    """``[a-z0-9_]`` slug, collapsed and trimmed (CodeAutoNamer.slugify)."""
    if text is None:
        return ""
    lowered = unicodedata.normalize("NFKD", text.lower())
    lowered = "".join(c for c in lowered if not unicodedata.combining(c))
    out: list[str] = []
    for char in lowered:
        if ("a" <= char <= "z") or ("0" <= char <= "9"):
            out.append(char)
        elif not out or out[-1] != "_":
            out.append("_")
    return _truncate_slug("".join(out).strip("_"))


def describe_for(language: str | None, code: str, time_suffix: str) -> tuple[str, bool]:
    """``(slug, plumbing_only)`` for captured code (CodeAutoNamer.describeFor).

    Cascade: leading comment, then up to three non-plumbing ``run("...")``
    names joined with ``__``, then the first defined symbol, then a timestamp.
    """
    slug = _from_leading_comment(code)
    if slug:
        return slug, False
    run_calls = _from_run_calls(code)
    if run_calls is not None and run_calls[0]:
        return run_calls
    slug = _from_first_symbol(code)
    if slug:
        return slug, False
    prefix = "macro" if not language or normalise_language(language) == "ijm" else "script"
    return f"{prefix}_{time_suffix}", False


def name_for(language: str | None, code: str, time_suffix: str) -> str:
    return describe_for(language, code, time_suffix)[0]


def _from_leading_comment(code: str) -> str | None:
    scanned = 0
    for raw in re.split(r"\r?\n", code or ""):
        line = raw.strip()
        if not line:
            continue
        if scanned >= 5:
            break
        scanned += 1
        match = _LEADING_COMMENT.search(line)
        if not match:
            continue
        text = next((g for g in match.groups() if g is not None), None)
        if text is None:
            continue
        text = text.strip()
        if not text or _BOILERPLATE.search(text):
            continue
        slug = slugify(text)
        if slug:
            return slug
    return None


def _from_run_calls(code: str) -> "tuple[str, bool] | None":
    distinct: list[str] = []
    for match in _RUN_CALL.finditer(code or ""):
        name = match.group(1).strip()
        if name.lower() in PLUMBING_COMMANDS:
            continue
        slug = slugify(name)
        if not slug or slug in distinct:
            continue
        distinct.append(slug)
        if len(distinct) >= 3:
            break
    if distinct:
        return _truncate_slug("__".join(distinct)), False
    for match in _RUN_CALL.finditer(code or ""):
        slug = slugify(match.group(1))
        if slug and slug not in distinct:
            distinct.append(slug)
        if len(distinct) >= 2:
            break
    if not distinct:
        return None
    return _truncate_slug("__".join(distinct)), True


def _from_first_symbol(code: str) -> str | None:
    for pattern in (_DEF_GROOVY, _FUNCTION, _ASSIGNMENT):
        match = pattern.search(code or "")
        if match:
            return slugify(match.group(1))
    return None


def canonicalise(code: str | None) -> str:
    """Dedup key for journal entries (SessionCodeJournal.canonicalise)."""
    return _WHITESPACE.sub(" ", (code or "").replace("\r\n", "\n")).strip()


@dataclass
class JournalEntry:
    """One piece of code the session ran (SessionCodeJournal.Entry)."""

    id: int
    name: str
    language: str
    code: str
    canonical: str = ""
    source: str = "console"
    started_at: float = 0.0
    last_run_at: float = 0.0
    run_count: int = 1
    success: bool = True
    failure_message: str | None = None
    plumbing_only: bool = False

    @property
    def line_count(self) -> int:
        return len(self.code.splitlines()) if self.code else 0

    def row_text(self, localtime: Callable[[float], time.struct_time] | None = None) -> str:
        """``[warning] HH:mm:ss  name  xN  nL`` (SessionHistoryPanel.java:564)."""
        clock = localtime or time.localtime
        stamp = time.strftime("%H:%M:%S", clock(self.last_run_at or self.started_at))
        prefix = "" if self.success else "\u26a0 "
        return f"{prefix}{stamp}  {self.name}  x{self.run_count}  {self.line_count}L"

    def tooltip(self) -> str:
        if not self.success and self.failure_message:
            return self.failure_message
        return self.name

    def file_name(self) -> str:
        return f"{self.name}.{extension_for_language(self.language)}"

    def as_dict(self) -> dict:
        return {
            "id": self.id,
            "name": self.name,
            "language": self.language,
            "code": self.code,
            "source": self.source,
            "timestamp": self.started_at,
            "lastRunAt": self.last_run_at,
            "runCount": self.run_count,
            "success": self.success,
            "failureMessage": self.failure_message,
            "plumbingOnly": self.plumbing_only,
        }

    @classmethod
    def from_dict(cls, raw: Mapping[str, Any]) -> "JournalEntry":
        code = str(raw.get("code", "") or "")
        language = str(raw.get("language", "ijm") or "ijm")
        started = float(raw.get("timestamp", 0) or 0)
        suffix = time.strftime("%H%M%S", time.localtime(started or time.time()))
        name = str(raw.get("name") or name_for(language, code, suffix))
        plumbing = raw.get("plumbingOnly")
        if plumbing is None:
            plumbing = describe_for(language, code, suffix)[1]
        return cls(
            id=int(raw.get("id", 0) or 0),
            name=name,
            language=language,
            code=code,
            canonical=canonicalise(code),
            source=str(raw.get("source", "console") or "console"),
            started_at=started,
            last_run_at=float(raw.get("lastRunAt", started) or started),
            run_count=max(1, int(raw.get("runCount", 1) or 1)),
            success=bool(raw.get("success", True)),
            failure_message=raw.get("failureMessage"),
            plumbing_only=bool(plumbing),
        )


class SessionCodeJournal:
    """Ring of the last 200 pieces of code this session ran.

    Every nonempty executed macro or script is recorded, including one-line
    commands. Repeated code bumps its run count and moves it to the head.
    """

    def __init__(self, clock: "Callable[[], float] | None" = None) -> None:
        self._clock = clock or time.time
        self._entries: list[JournalEntry] = []
        self._next_id = 1

    # ---- recording ----

    def record(
        self,
        code: str,
        language: str = "ijm",
        *,
        source: str = "console",
        success: bool = True,
        failure_message: str | None = None,
        timestamp: float | None = None,
    ) -> "JournalEntry | None":
        """Record a run; only empty code is skipped."""
        if not code or not code.strip():
            return None
        now = self._clock() if timestamp is None else timestamp
        canonical = canonicalise(code)
        for existing in self._entries:
            if existing.canonical == canonical:
                existing.run_count += 1
                existing.last_run_at = now
                existing.success = success
                existing.failure_message = failure_message
                self._entries.remove(existing)
                self._entries.insert(0, existing)
                return existing
        suffix = time.strftime("%H%M%S", time.localtime(now))
        name, plumbing = describe_for(language, code, suffix)
        entry = JournalEntry(
            id=self._next_id,
            name=name,
            language=language or "ijm",
            code=code,
            canonical=canonical,
            source=source,
            started_at=now,
            last_run_at=now,
            success=success,
            failure_message=failure_message,
            plumbing_only=plumbing,
        )
        self._next_id += 1
        self._entries.insert(0, entry)
        del self._entries[HISTORY_RING_CAP:]
        return entry

    def record_tool_run(
        self, name: str, args: Mapping[str, Any], ok: bool, result: str,
        *, timestamp: float | None = None,
    ) -> "JournalEntry | None":
        """Record executed code, excluding editor previews and refused calls.

        Older vendor agents expose an explicit ij.py invocation as a shell
        tool. Recover its literal source only when it is a single Fiji action;
        a compound shell command cannot give each macro a reliable outcome.
        """
        if result.lstrip().startswith("Refused:"):
            return None
        if name.lower() in {"shell", "bash", "exec_command"}:
            from .shell_actions import fiji_actions
            actions = fiji_actions(str(args.get("command") or args.get("cmd") or ""))
            if len(actions) != 1:
                return None
            name, args = actions[0].name, actions[0].args
        if (name not in {"execute_macro", "execute_macro_async", "run_macro", "run_macro_async", "run_script"}
                or not isinstance(args.get("code"), str)
                or args.get("source") == "rail:script-editor"):
            return None
        return self.record(
            args["code"], str(args.get("language") or "ijm"),
            source=str(args.get("source") or "console:agent"), success=ok,
            failure_message=None if ok else result, timestamp=timestamp,
        )

    def restore_tool_runs(self, events: Iterable[Mapping[str, Any]]) -> int:
        """Rebuild old session history from completed evidence pairs only."""
        calls: dict[str, Mapping[str, Any]] = {}
        for event in events:
            payload = event.get("payload")
            if not isinstance(payload, Mapping):
                continue
            cid = payload.get("correlation_id")
            if not isinstance(cid, str):
                continue
            if event.get("type") == "tool_call":
                calls[cid] = payload
            elif event.get("type") == "tool_result":
                call = calls.pop(cid, None)
                if call is None or not isinstance(call.get("arguments"), Mapping):
                    continue
                result = payload.get("result", "")
                if not isinstance(result, str):
                    result = json.dumps(result, ensure_ascii=False)
                try:
                    timestamp = datetime.fromisoformat(
                        str(event.get("timestamp") or "").replace("Z", "+00:00")
                    ).timestamp()
                except ValueError:
                    timestamp = None
                self.record_tool_run(
                    str(call.get("tool") or ""), call["arguments"],
                    bool(payload.get("ok", True)), result, timestamp=timestamp,
                )
        return len(self._entries)

    # ---- reading ----

    def snapshot(self, exclude_plumbing: bool = False) -> list[JournalEntry]:
        """Newest first; the filter mirrors pref ``history.excludePlumbing``."""
        if exclude_plumbing:
            return [e for e in self._entries if not e.plumbing_only]
        return list(self._entries)

    def snapshot_current_session(self) -> list[JournalEntry]:
        return list(self._entries)

    def find(self, entry_id: int) -> "JournalEntry | None":
        return next((e for e in self._entries if e.id == entry_id), None)

    # ---- mutation ----

    def remove_from_ring(self, entry_id: int) -> bool:
        before = len(self._entries)
        self._entries = [e for e in self._entries if e.id != entry_id]
        return len(self._entries) != before

    def clear_ring(self) -> None:
        self._entries = []

    def clear_ring_and_delete_files(self, directory: str | Path | None) -> None:
        """Clear the ring and remove the journal index (S2.18)."""
        self.clear_ring()
        if directory:
            index = Path(directory) / INDEX_FILE
            if index.exists():
                index.unlink()

    # ---- persistence (pref ai.assistant.history.persist) ----

    def save_index(self, directory: str | Path) -> Path:
        """Write ``INDEX.json``, refusing to exceed the 8 MiB cap."""
        folder = Path(directory)
        folder.mkdir(parents=True, exist_ok=True)
        payload = json.dumps([e.as_dict() for e in self._entries], indent=2)
        if len(payload.encode("utf-8")) > MAX_INDEX_BYTES:
            raise ValueError(f"journal index exceeds {MAX_INDEX_BYTES} bytes")
        target = folder / INDEX_FILE
        temp = target.with_suffix(".json.tmp")
        temp.write_text(payload, encoding="utf-8")
        os.replace(temp, target)
        return target

    def load_from_index_if_present(self, directory: str | Path) -> int:
        """Load a saved ring; a corrupt index is ignored, an oversized one raises."""
        index = Path(directory) / INDEX_FILE
        if not index.is_file():
            return 0
        if index.stat().st_size > MAX_INDEX_BYTES:
            raise ValueError(f"journal index exceeds {MAX_INDEX_BYTES} bytes")
        try:
            raw = json.loads(index.read_text(encoding="utf-8"))
        except (OSError, ValueError):
            return 0
        if not isinstance(raw, list):
            return 0
        entries = [JournalEntry.from_dict(item) for item in raw if isinstance(item, Mapping)]
        self._entries = entries[:HISTORY_RING_CAP]
        self._next_id = max((e.id for e in self._entries), default=0) + 1
        return len(self._entries)


# --------------------------------------------------------------------------
# S2.12 recipes
# --------------------------------------------------------------------------

RECIPE_SUFFIXES = (".yaml", ".yml")
USER_RECIPES_ENV = "IMAGEJAI_USER_RECIPES_DIR"
RECIPE_DIRS_ENV = "IMAGEJAI_RECIPE_DIRS"
TCP_PORT_ENV = "IMAGEJAI_TCP_PORT"


@dataclass(frozen=True)
class RecipeItem:
    """A YAML workflow offered by the rail (LeftRail.RecipeItem)."""

    name: str
    source: str
    path: Path

    def tooltip(self) -> str:
        return str(Path(self.path).resolve())


def _list_recipe_dir(directory: "str | Path | None", source: str) -> list[RecipeItem]:
    if not directory:
        return []
    folder = Path(directory)
    if not folder.is_dir():
        return []
    items: list[RecipeItem] = []
    for path in folder.iterdir():
        if path.is_file() and path.name.lower().endswith(RECIPE_SUFFIXES):
            items.append(RecipeItem(name=path.stem, source=source, path=path))
    return sorted(items, key=lambda item: item.name.lower())


def list_recipes(
    user_dir: "str | Path | None", bundled_dir: "str | Path | None"
) -> list[RecipeItem]:
    """Saved recipes first, then bundled ones (LeftRail.java:836)."""
    return _list_recipe_dir(user_dir, "Saved") + _list_recipe_dir(bundled_dir, "Bundled")


def recipe_popup_model(items: Sequence[RecipeItem]) -> dict:
    """"Saved" / "Bundled" groups, separated, with the Java empty state."""
    if not items:
        return {"empty": EMPTY_RECIPES, "groups": []}
    groups: list[dict[str, Any]] = []
    for source in ("Saved", "Bundled"):
        section = [item for item in items if item.source == source]
        if section:
            groups.append(
                {"title": source, "items": section, "separator_before": bool(groups)}
            )
    return {"empty": None, "groups": groups}


def python_command(env: "Mapping[str, str] | None" = None) -> str:
    """``IMAGEJAI_PYTHON`` else python/python3 (LeftRail.java:1274)."""
    environ = env if env is not None else os.environ
    configured = (environ.get("IMAGEJAI_PYTHON") or "").strip()
    if configured:
        return configured
    return "python" if os.name == "nt" else "python3"


def recipe_run_command(
    recipe: "RecipeItem | str | Path",
    agent_dir: str | Path,
    *,
    port: int = 7746,
    user_recipes_dir: "str | Path | None" = None,
    python: str | None = None,
    env: "Mapping[str, str] | None" = None,
) -> dict:
    """Launch plan for ``run_recipe.py`` (LeftRail.java:883, :1262).

    Returns argv, cwd, and only the environment *additions*; the caller merges
    them into its own environment so the user's PATH survives.
    """
    path = Path(recipe.path if isinstance(recipe, RecipeItem) else recipe).resolve()
    directory = Path(agent_dir).resolve()
    if not (directory / "run_recipe.py").is_file() and (
        directory / "agent" / "run_recipe.py"
    ).is_file():
        directory = directory / "agent"
    user_dir = Path(user_recipes_dir).resolve() if user_recipes_dir else None
    search_dirs = [str(user_dir)] if user_dir else []
    search_dirs.append(str(directory / "recipes"))
    return {
        "argv": [python or python_command(env), "-u", "run_recipe.py", str(path)],
        "cwd": str(directory),
        "env": {
            TCP_PORT_ENV: str(port),
            USER_RECIPES_ENV: str(user_dir) if user_dir else "",
            RECIPE_DIRS_ENV: os.pathsep.join(search_dirs),
        },
        "recipe": path.name,
    }


def load_recipe(path: str | Path) -> dict:
    """Read one recipe YAML, returning ``{"_error": ...}`` on failure.

    Same contract as ``agent/recipe_search.py:load_recipe`` so the console and
    the recipe runner agree about what a broken recipe looks like.
    """
    target = Path(path)
    try:
        import yaml

        data = yaml.safe_load(target.read_text(encoding="utf-8"))
    except Exception as exc:  # noqa: BLE001 - surfaced to the status line
        return {"_error": readable_message(exc), "_path": str(target)}
    if not isinstance(data, dict):
        return {"_error": "Recipe document must be a YAML mapping", "_path": str(target)}
    return data


def recipe_summary(path: str | Path) -> dict:
    """Name / description / parameter names for a recipe row tooltip."""
    recipe = load_recipe(path)
    if "_error" in recipe:
        return recipe
    parameters = recipe.get("parameters") or []
    names: list[str] = []
    if isinstance(parameters, list):
        names = [
            str(p.get("name"))
            for p in parameters
            if isinstance(p, Mapping) and p.get("name")
        ]
    elif isinstance(parameters, Mapping):
        names = [str(k) for k in parameters]
    return {
        "id": str(recipe.get("id") or Path(path).stem),
        "name": str(recipe.get("name") or Path(path).stem),
        "description": " ".join(str(recipe.get("description") or "").split()),
        "domain": str(recipe.get("domain") or ""),
        "difficulty": str(recipe.get("difficulty") or ""),
        "parameters": names,
    }


# --------------------------------------------------------------------------
# rail popup dispatch (called from rail.ACTIONS)
# --------------------------------------------------------------------------


def popup_action(
    kind: str,
    conn: Any = None,
    *,
    journal: "SessionCodeJournal | None" = None,
    imagej_root: "str | Path | None" = None,
    workspace: "str | Path | None" = None,
    home: "str | Path | None" = None,
    user_recipes_dir: "str | Path | None" = None,
    bundled_recipes_dir: "str | Path | None" = None,
) -> RailResult:
    """Build one rail popup model and its status line.

    Status text follows LeftRail.java:648-658: the empty-state string when
    nothing was found, otherwise "<n> macros found".
    """
    if kind == "my":
        items = list_user_macros(imagej_root)
        model = macro_popup_model(items, grouped=False, empty_text=EMPTY_MY_MACROS)
    elif kind in ("session", "save"):
        items = list_session_macros(journal) if journal is not None else []
        model = macro_popup_model(items, grouped=False, empty_text=EMPTY_SESSION_MACROS)
    elif kind == "all":
        items = list_all_macros(journal, imagej_root, workspace, home)
        model = macro_popup_model(items, grouped=True, empty_text=EMPTY_ALL_MACROS)
    elif kind == "recipes":
        recipes = list_recipes(user_recipes_dir, bundled_recipes_dir)
        model = recipe_popup_model(recipes)
        if not recipes:
            return RailResult(ok=False, status=EMPTY_RECIPES, data=model)
        return RailResult(status=f"{len(recipes)} recipes found", data=model)
    else:
        raise KeyError(f"unknown macro popup kind {kind!r}")

    if model["empty"]:
        return RailResult(ok=False, status=model["empty"], data=model)
    return RailResult(status=f"{len(items)} macros found", data=model)
