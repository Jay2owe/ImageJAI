# Stage 10 — New WIP + Macro sets + Run recipe + Audit

## Why this stage exists

The remaining four rail buttons from PLAN.md §2.4 ("Session" and
"Guidance" sections) are the ones that exercise non-trivial
plumbing: New WIP writes a file and PTY-injects a prompt; Macro
sets reads a user-managed `.ijm` folder and fires `execute_macro`;
Run recipe spawns a new Python helper that walks the existing
recipe YAMLs; Audit calls the existing `agent/auditor.py` and
pastes its summary into the PTY. All four close the loop on the
biologist's day-to-day workflow without ever round-tripping the
LLM.

## Prerequisites

- Stage 07 `_COMPLETED` (rail + `TcpHotline`).
- Stage 09 `_COMPLETED` (PTY-inject pattern used by New WIP +
  Audit is consistent with Commands button).

## Read first

- `docs/embedded-agent-widget/00_overview.md`
- `docs/embedded-agent-widget/PLAN.md` §2.4 (rail table), §2.9
  (Python helper gaps), §4 Phase 4 bullets on these buttons
- `agent/recipes/` (26 existing YAMLs — the ones Run recipe
  enumerates)
- `agent/recipe_search.py` (YAML loader + fallback parser to
  reuse — do **not** introduce a PyYAML hard dependency)
- `agent/auditor.py:561` (`audit_results()` — the entry point
  Audit calls)
- `agent/work_in_progress/` (existing folder — WIP button writes
  new `.md` files here)

## Scope

- **New WIP** button (Session rail section). Swing input dialog
  for a slug → creates `agent/work_in_progress/<slug>.md` from
  `src/main/resources/wip_template.md` → PTY-inject `"Start new
  WIP: read \`<path>\` and help me scope it."`
- `src/main/resources/wip_template.md` — template (Goal / Steps /
  Decisions / Open questions).
- **Macro sets…** button (Fiji-hotlines rail section, below stage
  07's three buttons). Popup lists `.ijm` files in a new
  `agent/macro_sets/` folder; clicking fires `execute_macro` with
  the file body via `TcpHotline`, tagged `source=rail:macro-set`.
- `agent/macro_sets/` — new folder, empty. Add a `README.md`
  explaining user workflow ("drop any `.ijm` here to expose via
  the rail button").
- **Run recipe…** button (Guidance rail section). Popup lists the
  26 YAMLs in `agent/recipes/`; clicking spawns
  `python agent/run_recipe.py <name>` and tees per-step stdout
  into the PTY.
- `agent/run_recipe.py` — new Python helper:
  - Loads YAML via `recipe_search.py`'s existing loader.
  - Presents a SciJava `GenericDialog` for the recipe's
    `parameters:` block.
  - Substitutes `${slot}` references in each `step.macro`.
  - Executes `steps[]` **one at a time**, printing
    `[step 3/7] gaussian_blur … ok (312 ms)` to stdout between
    calls. On failure, highlight the failing step and skip the
    rest (PLAN.md audit gap #5).
- **Audit my results** button (Guidance rail section). Calls
  `agent/auditor.py:561 audit_results()` with `get_results_table`
  + `get_image_info` TCP outputs, pastes the returned `summary`
  into the PTY as context.
- Focus return after every button (stage 08 already added the
  helper — call it from these new buttons too).

## Out of scope

- Session history panel — stage 11.
- Commands / New agent chat — stage 09.
- Font shortcuts / colour scheme — stage 12.

## Files touched

| Path                                                                | Action | Reason                                                |
|---------------------------------------------------------------------|--------|-------------------------------------------------------|
| `src/main/java/imagejai/ui/LeftRail.java`                | MODIFY | Four new buttons across Session / Hotlines / Guidance |
| `src/main/resources/wip_template.md`                                | NEW    | Template for New WIP                                  |
| `agent/macro_sets/`                                                 | NEW    | User-drop `.ijm` folder                               |
| `agent/macro_sets/README.md`                                        | NEW    | Explain workflow                                      |
| `agent/run_recipe.py`                                               | NEW    | Load YAML + GenericDialog + per-step execution        |

## Implementation sketch

`wip_template.md`:
```markdown
# <slug>

## Goal

## Steps

## Decisions

## Open questions
```

`run_recipe.py` structure:
```python
def main():
    name = sys.argv[1]
    recipe = load_recipe(Path("agent/recipes") / f"{name}.yaml")
    params = recipe.get("parameters", {})
    if params:
        chosen = show_generic_dialog(params)     # SciJava GenericDialog
    else:
        chosen = {}
    for i, step in enumerate(recipe["steps"], 1):
        macro = substitute_slots(step["macro"], chosen)
        t0 = time.time()
        print(f"[step {i}/{len(recipe['steps'])}] {step['name']} …", flush=True)
        resp = tcp_execute_macro(macro, source="rail:recipe")
        dt = int((time.time() - t0) * 1000)
        if not resp.get("success"):
            print(f"  FAIL ({dt} ms): {resp.get('error')}", flush=True)
            sys.exit(1)
        print(f"  ok ({dt} ms)", flush=True)
```

Macro sets button dispatch:
```java
for (Path ijm : list("agent/macro_sets/*.ijm")) {
    popup.addItem(ijm.getFileName().toString(), () -> {
        TcpHotline.executeMacro(readAll(ijm), "rail:macro-set");
    });
}
```

## Exit gate

1. New WIP button creates the file and injects the prompt; the
   agent picks up the path and begins scoping.
2. Drop a `.ijm` into `agent/macro_sets/`; click the button;
   macro executes via TCP; `SessionCodeJournal` (stage 04) records
   it with `source=rail:macro-set`.
3. Run recipe on `cell_counting.yaml` (one of the 26 existing
   YAMLs) end-to-end: dialog appears, user supplies params,
   per-step output streams into the PTY, final state is the
   expected Fiji outcome.
4. Audit button pastes the auditor summary into the PTY; the
   agent can quote it back in its next turn.
5. All four buttons return focus to the terminal after dispatch.
6. `run_recipe.py` aborts on first failing step and surfaces the
   failing step name in the PTY.

## Known risks

- PyYAML dependency creep. The existing `recipe_search.py` has a
  fallback parser for installs without PyYAML. `run_recipe.py`
  must use the same loader, not add a hard PyYAML import.
- `${slot}` substitution needs to quote values with spaces
  safely — Fiji macro `run()` takes a space-delimited arg string.
  Test with a recipe that passes a folder path containing spaces.
- Long-running recipes block the PTY output queue. Use unbuffered
  `stdout` (`python -u`) so progress streams live.
