# Fiji Toolbar Tools — Reference

How to create, install, and persist custom buttons on Fiji's toolbar from a
macro or from the TCP agent. The toolbar is the horizontal strip at the top of
the ImageJ window; each icon is a "tool macro" — a macro with a special name
whose body runs when the user clicks the button.

Use this when the user asks for a one-click button, a personal shortcut, a
quick way to rerun the last workflow, or says "I do this a lot — make me a
button."

Sources: `imagej.net/ij/developer/macro/functions.html#Tool`, Fiji wiki
toolbar docs (`imagej.net/ij/docs/guide/146-11.html`), and the bundled
`StartupMacros.fiji.ijm` in a standard Fiji install.

Run install code from the agent with
`python ij.py script --file <path.groovy>` (Groovy) or
`python ij.py macro '<code>'` (macro).

---

## §0 Lookup Map — "How do I find X?"

| Question | Where to look |
|---|---|
| "What's the exact tool macro header syntax?" | §2 |
| "How do I draw the button icon?" | §3 icon mini-language |
| "Where does the `.ijm` file have to live?" | §4 install paths |
| "How do I install it now without restarting Fiji?" | §5 runtime install |
| "Make the button survive a Fiji restart?" | §4.2 StartupMacros |
| "Button should open a dialog, not run silently" | §6.2 dialog example |
| "Button should call back to the AI agent" | §6.4 agent-callback example |
| "Button does nothing when clicked" | §8 gotchas |
| "I want a toolset the user opens from the `>>` menu" | §4.1 toolsets |
| "What's the end-to-end agent recipe?" | §7 agent workflow |

---

## §1 Term Index (A–Z)

Alphabetical pointer to the section containing each term, tool, shortcut, or
concept. Use `grep -n '`<term>`' fiji-toolbar-tools-reference.md` to jump.

### A

`Action Tool` (name suffix) §2 · `agent-callback button` §6.4 · `Agent Workflow` §7 · `append (StartupMacros)` §4.2, §5.3 · `absolute paths` §8

### B

`B<xy>` (icon command) §3 · `Blur Tool` (example) §2 · `Button does nothing` §8

### C

`C<rgb>` (icon colour) §3 · `Count Particles Tool` (example) §6.2 · `custom buttons` (intro)

### D

`D<x><y>` (icon dot) §3 · `dialog-driven tool` §6.2 · `double-click (Tool Options)` §2, §6.2

### E

`EDT (tool body runs on)` §8 · `Enhance Contrast` (rule) §8

### F

`F<x><y><w><h>` (filled rectangle) §3 · `f<x><y><w><h>` (filled oval) §3 · `File.open / File.close` §6.4 · `Fiji install directory` §4

### G

`getDirectory("imagej")` §4, §5.1, §5.2, §6.3 · `Groovy install` §5.1

### H

`Hello World Tool` (example) §2

### I

`icon mini-language` §3 · `icon string (format)` §2, §3 · `IJ.getDirectory` §4 · `install paths` §4 · `installFile (MacroInstaller)` §5.1, §8 · `invocation` (header) · `Install...` (`run(...)` macro) §4.3, §5.2, §8

### L

`L<x1><y1><x2><y2>` (icon line) §3 · `Label (tool macro)` §2

### M

`MacroInstaller` §4.3, §5.1, §7, §8 · `mnemonic icon pattern` §3

### N

`name collision` §8

### O

`O<x><y><w><h>` (outlined oval) §3 · `one-click action` §6.1 · `Otsu Mask Tool` (example) §6.1

### P

`P<x1><y1>...<xn><yn>0` (polyline) §3 · `permanent (persisting across restarts)` §4.2, §5.3 · `persisting across restarts` §5.3 · `pipeline button` §6.3 · `probe` (general) — see `agent/CLAUDE.md`

### Q

`Quick Blur Tool` (example) §5.1

### R

`R<x><y><w><h>` (outlined rectangle) §3 · `reactive rules` §6.4 · `relative paths (not supported)` §8 · `run("Install...")` §4.3, §5.2, §8 · `runMacro` §6.3 · `Runtime install` §5

### S

`separator (` Tool-`)` §2 · `showMessage` §6.1 · `showStatus` §2, §5.1, §6.4 · `sigma (var)` §2 · `StartupMacros.fiji.ijm` §4.2, §5.3, §7, §8 · `StartupMacros.ijm` §4.2 · `StartupMacros.txt` §4.2 · `Swing dialogs` (general) — see `agent/CLAUDE.md`

### T

`T<xy><size><char>` (icon text) §3 · `TCP server bus` §6.4 · `Tool` (name suffix) §2 · `Tool Options` (name suffix) §2, §6.2 · `Tool-` (separator suffix) §2 · `tool macro syntax` §2 · `toolset` §4.1, §7 · `triggers (file-based)` §6.4

### V

`V<x><y><w><h>` (variant fill) §3

### W

`W` (variant fill) §3 · `Worked Examples` §6

---

## §2 Tool Macro Syntax

A tool macro is an ordinary macro whose **name ends with `Tool`** and contains
an icon string after a ` - ` separator:

```ijm
macro "<Label> [Action] Tool - <icon-string>" {
    // body runs when the user clicks the button
}
```

Exact rules (these are not optional):

- The word `Tool` must appear with a capital T, preceded by a space.
- The icon string is separated from the name by ` - ` (space-hyphen-space).
- The whole thing is inside double quotes.
- `Action` is an optional hint ImageJ shows in the status bar on hover.

Minimum viable example:

```ijm
macro "Hello World Tool - C037T4e14H" {
    showStatus("Hello!");
}
```

### Special tool-macro names

| Name suffix | Fires when | Use for |
|---|---|---|
| ` Tool` | User clicks the toolbar button | The normal action |
| ` Tool Options` | User **double-clicks** the button | Open a config dialog for the tool |
| ` Action Tool` | Same as ` Tool` (synonym, common in docs) | Same |
| ` Tool-` (with trailing dash) | Separator between tools | Layout only |

Example pair:

```ijm
var sigma = 2;

macro "Blur Tool - C037T4e14B" {
    run("Gaussian Blur...", "sigma=" + sigma);
}

macro "Blur Tool Options" {
    sigma = getNumber("Sigma", sigma);
}
```

---

## §3 Icon Mini-Language

The icon string is a sequence of single-letter commands that draw a 16×16
monochrome bitmap. Commands are case-sensitive. Coordinates are single hex
digits (`0`–`9`, `a`–`f`), one per axis.

| Command | Meaning | Example |
|---|---|---|
| `C<rgb>` | Set colour (three hex digits). `C000`=black, `Cfff`=white, `Cf00`=red | `C037` |
| `B<xy>` | Brightness / starting point (rarely needed) | `B10` |
| `T<xy><size><char>` | Draw **single** text character at `(x,y)` with font size | `T4e14H` = 'H' at (4,14) size 14 |
| `F<x><y><w><h>` | Filled rectangle | `F0055` = 5×5 block at (0,0) |
| `R<x><y><w><h>` | Outlined rectangle | `R0155` |
| `O<x><y><w><h>` | Outlined oval | `O2266` |
| `f<x><y><w><h>` | Filled oval (lowercase) | `f3344` |
| `D<x><y>` | Single dot | `D8a` |
| `L<x1><y1><x2><y2>` | Line | `L0088` |
| `P<x1><y1>...<xn><yn>0` | Polyline; terminate with `0` | `P11ff11f1f10` |
| `V<x><y><w><h>` / `W` | Variant fills (rare) | — |

### Common patterns

```text
C037T4e14B         # blue capital 'B' — mnemonic, easy to read
C000F0044C888F4444 # small black square top-left, grey square to its right
Cf00O2266          # red circle
Ca0aP11 f1 ff 1f 11 0  # purple diamond (polygon, terminator 0)
```

Keep icons short. Anything over ~24 characters becomes illegible at 16px.

---

## §4 Install Paths

Every path below is relative to the Fiji install directory. Get it at runtime
with `IJ.getDirectory("imagej")` (Groovy) or `getDirectory("imagej")` (macro).

### §4.1 Toolsets — user-pickable

```
<Fiji>/macros/toolsets/<name>.ijm
```

Each `.ijm` file here shows up as a menu item in the `>>` menu at the right
end of the toolbar. Clicking that menu item replaces the current tools with
the macros in the file. Good for **grouping** a set of buttons the user can
switch in and out (e.g. "Colocalization tools", "Wound-healing tools").

### §4.2 StartupMacros — auto-loaded

```
<Fiji>/macros/StartupMacros.fiji.ijm        # preferred on Fiji
<Fiji>/macros/StartupMacros.ijm             # vanilla ImageJ fallback
<Fiji>/macros/StartupMacros.txt             # legacy
```

Fiji installs every tool macro in this file on startup. Use this to make a
button **permanent** across restarts. Append, never overwrite — the file
usually contains defaults the user relies on.

### §4.3 Runtime install — this session only

Use `MacroInstaller` from Groovy (§5.1) or the macro one-liner
`run("Install...", "install=[<absolute path>]")` to install an `.ijm` file
right now without restarting Fiji. Useful for "try this button" previews.

---

## §5 Runtime Install (the agent's primary path)

### §5.1 Groovy via `run_script`

```groovy
import ij.IJ
import ij.plugin.MacroInstaller

def fiji   = IJ.getDirectory("imagej")
def tsDir  = new File(fiji, "macros/toolsets")
tsDir.mkdirs()

def file = new File(tsDir, "agent_tools.ijm")
file.text = '''
macro "Quick Blur Tool - C037T4e14B" {
    run("Gaussian Blur...", "sigma=2");
}
'''

new MacroInstaller().installFile(file.absolutePath)
IJ.showStatus("Installed: Quick Blur")
```

Send it with:

```bash
python ij.py script --file /tmp/install_blur.groovy
```

### §5.2 Macro one-liner

```ijm
path = getDirectory("imagej") + "macros/toolsets/agent_tools.ijm";
run("Install...", "install=[" + path + "]");
```

Assumes the file already exists. Write the file from Groovy (§5.1) or from the
shell; the macro language has no clean way to write multi-line files.

### §5.3 Persisting across restarts

After confirming the button works, append the tool macro to
`<Fiji>/macros/StartupMacros.fiji.ijm`. Groovy pattern:

```groovy
def startup = new File(IJ.getDirectory("imagej"), "macros/StartupMacros.fiji.ijm")
startup << "\n\n" + toolMacroText     // append, do not overwrite
```

Always read existing contents first if you plan to replace, and always preserve
everything the user already has.

---

## §6 Worked Examples

### §6.1 One-click action

```ijm
macro "Otsu Mask Tool - C000T0e14OC037T9e14M" {
    if (nImages == 0) { showMessage("No image open"); return; }
    setAutoThreshold("Otsu dark");
    setOption("BlackBackground", true);
    run("Convert to Mask");
}
```

### §6.2 Dialog-driven tool

```ijm
var particleMin = 50;
var particleMax = 10000;

macro "Count Particles Tool - C000T4e14#" {
    run("Analyze Particles...",
        "size=" + particleMin + "-" + particleMax +
        " show=Outlines display summarize clear");
}

macro "Count Particles Tool Options" {
    Dialog.create("Particle sizes");
    Dialog.addNumber("Min area (px)", particleMin);
    Dialog.addNumber("Max area (px)", particleMax);
    Dialog.show();
    particleMin = Dialog.getNumber();
    particleMax = Dialog.getNumber();
}
```

Double-click the button to change sizes; single-click to run.

### §6.3 Pipeline button (runs a saved macro file)

```ijm
macro "My Pipeline Tool - C03aT1e14PT8e14L" {
    runMacro(getDirectory("imagej") + "macros/my_pipeline.ijm");
}
```

Keeps the tool header tiny; the real logic lives in a separate file the user
can edit.

### §6.4 Agent-callback button

Publish an event on the TCP server's bus so an AI agent listening via
reactive rules can pick it up:

```ijm
macro "Ask Agent Tool - C00fT4e14?" {
    // send a synchronous TCP message from the macro — requires the
    // ij-tcp-client macro bundled with ImageJAI (if present). Otherwise
    // touch a trigger file the agent is watching:
    f = File.open(getDirectory("home") + ".imagej-ai/triggers/help-me.txt");
    print(f, getTitle() + "\t" + getTime());
    File.close(f);
    showStatus("Requested help from agent");
}
```

Pair with a reactive rule that watches `trigger.*` events (see
`docs/reactive_rules/reactive_rules_format.md`).

---

## §7 Agent Workflow

1. Decide: toolset (group of buttons) or single button in StartupMacros?
2. Draft the tool macro(s). Keep each body short; delegate heavy logic to a
   separate macro file and `runMacro` it (§6.3).
3. Write the `.ijm` file via Groovy (`run_script`), using
   `IJ.getDirectory("imagej")` to anchor the path.
4. Install at runtime with `MacroInstaller.installFile` (§5.1).
5. Verify with `get_state` / `capture_image` that the toolbar changed, and by
   firing the button once with a safe input.
6. If the user wants it permanent, append to `StartupMacros.fiji.ijm` (§5.3).

See `agent/recipes/install_toolbar_tool.yaml` for a parameterised template.

---

## §8 Gotchas

- **Button does nothing.** The macro name is missing the ` Tool` suffix or the
  ` - ` before the icon. ImageJ silently skips malformed headers.
- **Icon is blank.** Colour was never set — prepend `C000` (black) or any
  other `C<rgb>`. Default is white on white.
- **Name collision.** Two tools with the same label quietly overwrite. Use a
  unique prefix (e.g. `agent_` or the recipe id) for agent-installed tools.
- **`run("Install...")` silently fails** if the path has Windows backslashes
  or contains spaces without `[...]` brackets. Always wrap:
  `"install=[" + path + "]"`.
- **StartupMacros overwrite.** `installFile` on StartupMacros *replaces* the
  live toolbar; to persist, **append** to the file on disk, don't rewrite the
  whole thing.
- **No relative paths.** `MacroInstaller.installFile` and
  `run("Install...")` both want absolute paths. Use `IJ.getDirectory("imagej")`
  as the anchor.
- **Output files still go to `AI_Exports/`.** A toolbar button does not exempt
  the user's data rule — any measurements or saved images the tool produces
  belong next to the opened image, not next to the `.ijm` file.
- **Never use `Enhance Contrast normalize=true`** inside a tool body on data
  the user will measure. See top-level rule in `CLAUDE.md`.
- **Tool body runs on the EDT.** Long jobs freeze the UI. For anything >1s,
  wrap in `run_macro_async` or kick off a background thread from Groovy.
