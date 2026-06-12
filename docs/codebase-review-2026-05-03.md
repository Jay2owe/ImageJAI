# ImageJAI Codebase Review — 2026-05-03

**Trigger:** Cron-fired at 06:07 BST from a request the night before.
**Scope:** Full codebase — 6 subsystems reviewed in parallel by independent agents. Read-only; no fixes applied.
**Total findings:** 87 (19 BUG · 32 ISSUE · 19 INEFFICIENCY · 17 NIT)

Per-subsystem detail lives in `docs/_review_scratch_2026-05-03/`. This doc is the synthesis.

---

## TL;DR — top 5 to look at first

These are the highest-priority items across all 6 buckets, ranked by user-facing impact. Each links to the detailed finding in the scratch doc.

1. **Budget ceiling is theatre — UI persists it, launcher never reads it.**
   `TierSafetyPanel.java:110` writes `budgetCeilingEnabled` / `budgetCeilingUsd` to settings, but `AgentLaunchOrchestrator` and `ProxyAgentLauncher` never consult those values. A user who sets a $10 ceiling can incur $1000 of spend with zero intervention. This is a Phase-H regression in the multi-provider flag-flip — the safety dialog landed but the enforcement step did not. → `02_agent_launcher_ui.md` BUG #4.

2. **Hardcoded user path in `ImageJAIPlugin.java:260`.**
   `findAgentWorkspace()` contains a literal `<local ImageJAI agent workspace>`. Any other user (or any CI run) silently falls back to an empty `.imagej-ai/agent/` workspace and the agent has no recipes/references. This was probably a debug paste that escaped review. → `06_build_deploy.md` BUG #1.

3. **`pushSuppress("image.*")` permanently silences events on macro errors.**
   `TCPCommandServer.java:2750–3218` opens an event-bus suppression scope but only pops it on the success path. Any exception in fuzzy validation, snapshot, or undo capture leaves the bus suppressed for **every subsequent macro on every socket** until the server restarts. Symptom: silent drop of `image.*` notifications after a malformed macro — agent thinks nothing changed when something did. → `01_tcp_server.md` BUG #1.

4. **Provider status hardcoded to NEEDS_SETUP in `ProviderRegistry.java:186-189`.**
   Both branches of the if/else assign the same status. Result: every configured provider shows ⚠ in the dropdown, never ✓, regardless of whether models are present. Phase-D dropdown UX is broken. One-line fix: change line 189 to `Status.READY`. → `05_multi_provider.md` BUG #1.

5. **`Settings.save()` corrupts `config.json` under concurrent writes.**
   `Settings.java:283-293` does synchronous file I/O with no synchronization, no atomic rename, no lock. EDT, `TierSafetyPanel.persist()`, `ModelPickerButton` selection, and others can write concurrently — config gets truncated or corrupted. Severity is amplified now that multi-provider has multiple panels touching settings. → `02_agent_launcher_ui.md` ISSUE #5.

---

## Severity totals by subsystem

| Subsystem                                              | BUG | ISSUE | INEFF | NIT | Total |
|--------------------------------------------------------|-----|-------|-------|-----|-------|
| 01 TCP server (`TCPCommandServer.java` + engine)       |  2  |   8   |   3   |  4  |  17   |
| 02 Launcher + Swing UI                                 |  4  |   5   |   3   |  3  |  15   |
| 03 Python agent helpers (`ij.py`, `pixels.py`, etc.)   |  5  |   7   |   5   |  9  |  26   |
| 04 Recipes + reference docs                            |  2  |   5   |   1   |  0  |   8   |
| 05 Multi-provider phases C/D/E/F/G/H                   |  4  |   4   |   5   |  0  |  13   |
| 06 Build + deploy (pom.xml, Java 8 compat)             |  2  |   3   |   2   |  1  |   8   |
| **Total**                                              | **19** | **32** | **19** | **17** | **87** |

---

## 01 — TCP server (`TCPCommandServer.java`, ~7100 lines)

Detail: `docs/_review_scratch_2026-05-03/01_tcp_server.md`

**BUG**
- `TCPCommandServer.java:2750–3218` — `pushSuppress("image.*")` not balanced on exception paths. Permanently silences events. *(See TL;DR #3.)*
- `TCPCommandServer.java:1994` — `handleGetConsole` does `request.get("tail").getAsInt()` with no type check. Malformed input crashes the handler with no response. Fix: use the `optInt(...)` helper at 1828–1832.

**ISSUE**
- `:2848` — New `ExecutorService` spawned per macro / per script (also `:3617`), then immediately shut down. Under sustained load (10+ macros/s) threads outpace reaping; idle timeout keeps them alive. Use a class-level bounded pool initialised in `start()` and torn down in `stop()`.
- `:2653–2670` — `handleGetState` 5-second EDT wait has no retry/backoff; one Fiji GC pause = one user-visible failure.
- `:1040–1063` — Friction-logging and pattern-detection blocks swallow exceptions silently. If they crash, you lose observability and the handler still appears to succeed.
- `:2933–2937` — Worker `ExecutionException` flattened to `"Macro error: " + getMessage()`; loses exception type. Clients can't branch on compile-vs-runtime errors.
- `:667–700` — No per-client rate limit, no socket cap, no `accept()` backlog. Slowloris-class attack would exhaust the thread pool.
- `:851–855` — Subscription unsubscribe is not atomic with the ACK frame write. If the ACK throws, the listener gets removed but the client may have already half-read a corrupted frame and now waits forever for heartbeats.
- `:3550–3618` — `handleRunScript` declares `final long scriptTimeoutMs = resolveTimeoutMs(...)` but never assigns from the request; per-request timeout overrides for `run_script` are silently ignored.

**INEFFICIENCY**
- `:1465–1476` — `canonicalise()` allocates a `TreeMap` per JsonObject, per recursion, per readonly-poll. Cache the canonical form.
- `:3270–3276` — `safeDetectOpenDialogs()` swallows AWT exceptions silently; transient bugs vanish.
- `:448` — `cached3DUniverse` (volatile) is never invalidated; pins the 3D Viewer in memory after deletion. Use a weak ref or TTL.

**NIT** — silent timeout-parse failure (`:108`); ambiguous error string (`:2684`); md5Hex returns empty on failure (`:1617`); inconsistent result shape `output` vs `error` (`:2985–3016`).

---

## 02 — Launcher + Swing UI

Detail: `docs/_review_scratch_2026-05-03/02_agent_launcher_ui.md`

**BUG**
- `AgentLauncher.java:318` — `findExecutable()` does `Process.waitFor()` on the **EDT** during agent detection. UI freezes for seconds.
- `AgentLauncher.java:391` — `commandExists()` spawns a process and waits, but never `.destroy()`s on exception/timeout.
- `AgentLauncher.java:424` — `syncContextFiles()` launches python with no Process handle stored anywhere; can't destroy on Fiji shutdown. Zombie processes possible.
- `TierSafetyPanel.java:110` → `AgentLaunchOrchestrator` — **Budget ceiling never enforced.** *(See TL;DR #1.)*

**ISSUE**
- `AgentLauncher.java:182` — Workspace path embedded in a `cd /d "..." &&` cmd string with no shell escaping. Special characters in the path could break out (low real-world risk on this user's machine, real risk if anyone else ever runs it).
- `ModelPickerButton.java:403` — `settings.save()` invoked on EDT after model selection without synchronization.
- `AiRootPanel.java:259-270` — `agentSelector` `ActionListener` never removed in `removeNotify()`; memory leak when panel is replaced.
- `TerminalToolbar.java:106` — `urlTimer` only stopped in `hideCopyUrl()`; if the toolbar is disposed first, the timer fires on a destroyed component.
- `Settings.java:283-293` — Concurrent `save()` corrupts `config.json`. *(See TL;DR #5.)*

**INEFFICIENCY**
- `AgentLauncher.java:315-327` — Detection runs synchronously on EDT for all 9 known agents at startup. Move to `SwingWorker`.
- `MultiProviderPanel.java:42-104` — Provider metadata baked into a static final `Map`; needs recompile to add a provider URL. Externalise to YAML/JSON.
- `AiRootPanel.java:197-210` — Shutdown spawns a daemon thread just to dodge an EDT block; cleaner with `SwingWorker`.

**NIT** — hardcoded agent command names with no alias support (`AgentLauncher.java:65-75`); `tcpPort` not validated (`:87`); GSD settings mixed into `InstallerPanel` JSON (`:50-52`).

---

## 03 — Python agent helpers

Detail: `docs/_review_scratch_2026-05-03/03_python_agent_helpers.md`

Highest-volume bucket — 26 findings. Most actionable items are clustered.

**BUG**
- `agent/ij.py:829` — `imagej_events()` defined twice (also at 772–827). Python silently uses the second; the first is dead code with subtly different behavior. Real maintenance trap.
- `agent/probe_plugin.py:56,67,77,92,110` — All `open()` calls lack `encoding='utf-8'`. On Windows (cp1252 default) any JSON containing `μm` or other non-ASCII silently corrupts on read or write.
- `agent/pixels.py:35–51` — Socket created at line 37, only `.close()`d at line 50 on success path. `json.loads()` failure at line 51 leaks the FD. Wrap in try/finally.
- `agent/ij.py:177–197` — `imagej_command()` end-of-frame detection is fragile: relies on socket close to know all data arrived, can stop after the first `\n`-terminated JSON if multiple frames arrive in one chunk. Use `JSONDecoder.raw_decode()` or split on `\n`.
- `agent/auditor.py:153, 155–160` — `_check_area_plausibility()` returns dict / list-of-dicts / None inconsistently. Callers at 593–598 only handle list and dict; None gets `.get("status")`-d later and crashes.

**ISSUE**
- `agent/recipe_search.py:56–285` — Custom `_simple_yaml_parse()` fallback "does NOT handle anchors, aliases, or complex nesting beyond 3 levels". Silent malformed dicts on encountering them.
- `agent/ij.py:772–827 vs 829–875` — Subtle behavior difference in the duplicate `imagej_events()`: new one returns on `{"ok": False}` frames, old one continues. Whichever is in scope changes replay semantics.
- `agent/ij.py:772–826` — Docstring contradicts the implementation at 810–811.
- `agent/auditor.py:354–392` — `_check_edge_bias()` pairs columns by row index then searches `y_idxs` for the matching X — O(n²) and breaks if columns have different orderings (e.g. after filtering).
- `agent/pixels.py:42–51` — `json.loads()` on a possibly-incomplete buffer; partial frames raise `JSONDecodeError`.
- `agent/ij.py:763–764` — `gui_confirm()` fallback hangs the UI for 60 s on subscribe failure with no early exit.

**INEFFICIENCY**
- `agent/recipe_search.py:467–510` — O(n²) token matching; convert text tokens to a set.
- `agent/auditor.py:310–348` — `_check_outliers()` re-scans rows for every numeric column; build a column cache once.
- `agent/recipe_search.py:315–323` — `load_all_recipes()` re-parses every YAML on every call. Cache by mtime.
- `agent/ij.py:580–606` — TOCTOU re-check after subscribe (correct behaviour, mark intentional with a comment).

**NIT** — encoding missing on more file opens (`ij.py:1048`, several others); print formatting inconsistencies; `math.isnan()` called on potential ints (`auditor.py:62`); fragile positional CLI parsing (`recipe_search.py:738`); PEP 8 multi-statement lines.

**House-rule cross-check (from cron prompt):** no `Enhance Contrast normalize=true` on measured data found. No code closes the Log window. Outputs go to `AI_Exports/`. SIFT-on-moving-cells not introduced. Clean on the rules side.

---

## 04 — Recipes + reference docs

Detail: `docs/_review_scratch_2026-05-03/04_recipes_and_refs.md`

The smallest bucket and the one with fewest real defects. All 61 cross-references resolve, all YAML parses, agent context docs are clean.

**BUG**
- `agent/recipes/incucyte_gfp_extraction.yaml:14-30` — `parameters:` declared as a dict (bare keys `paraboloid_r1:` etc.) instead of a list of `{name, type, default}` objects. Will not match the recipe schema.
- `agent/recipes/trackmate_cell_traces.yaml:15-21` — Same schema bug, same fix.

**ISSUE — lab-contamination flags (low severity, the reviewer agreed)**
- `brain-atlas-registration-reference.md` — multiple SCN mentions (legit anatomical, but the doc reads as if SCN is the canonical example).
- `publication-figures-reference.md:1331-1332` — BMAL1 / PER2 used as protein-name examples; would be more neutral as TP53 / EGFR.
- `light-sheet-reference.md:598` — `scene.add_brain_region("SCN", ...)` example.
- `live-cell-timelapse-reference.md:166,176` — SCN/Tissue Slice listed as the named recommendation for drift correction.
- `registration-stitching-reference.md:886` — "Neuronal rhythms (SCN)" used as the canonical drift-correction use case.

**INEFFICIENCY**
- `agent/recipes/3d_ring_render.yaml:93,207,209` — Two overlapping descriptions of "the isolation method" (display range vs DAPI masking) that are both correct but read as contradictory.

---

## 05 — Multi-provider (phases C–H)

Detail: `docs/_review_scratch_2026-05-03/05_multi_provider.md`

**BUG**
- `ProviderRegistry.java:186-189` (Phase D) — Status always `NEEDS_SETUP`. *(See TL;DR #4.)*
- `Settings.java migrateIfNeeded()` (Phase D) — No migration from `selectedAgentName` → `selectedProvider` + `selectedModelId` after the Phase-H flag flip. Users' "re-launch last" button breaks silently on upgrade. Add explicit mappings (`claude_agent` → `{anthropic, claude-sonnet-4-6}`, `gemma4_31b_agent` → `{ollama, gemma4:31b-cloud}`, etc.).
- `agent/contexts/loader.py:58-61` (Phase F) — Family overlay files for `gemini.md` and `other.md` don't exist on disk; the loader silently skips them. Gemini models receive incomplete context. Either create the files or warn.
- `ProviderTierGate.java:95-103` (Phase H) — "Don't ask again" suppresses by `providerId` only. Ticking the box on Opus also silences the Haiku confirmation. Use `providerId + modelId` as the suppression key.

**ISSUE**
- `AgentLaunchOrchestrator.java:75-93` (Phase D) — Skeleton launchers return `null`; callers in `AiRootPanel` don't null-check. When real launchers land, network errors fail invisibly. Add a null-guard with a user dialog.
- `FirstUseDialog.java:70-78` (Phase H) — `result` field non-volatile, written from window-close handler and button handlers. Use `volatile` or a `CompletableFuture`.
- `agent/providers/models.yaml` (Phase F) — 11+ Gemini models declared `family: gemini` `curated: true`, no overlay file exists. (Pair with the loader BUG above.)
- `AiRootPanel.java:493-501` (Phase D) — ▶ enabled for any model in registry, including ones whose launcher is skeleton-only. Disable for non-Ollama transports until launchers ship.

**INEFFICIENCY** — `ModelPickerButton` hover-card may instantiate `JWindow` per hover (verify singleton); no Phase-D test fixture for live `/models` data; flag-flip default in `Settings.java:113` lacks a docstring pointing at the opt-out; no regression test for `useMultiProviderPicker=false`; `FirstUseDialog.java:156-193` builds HTML by string concat without attribute escaping; `NativeAgentLauncher.java:38-47` logs the same "not yet wired" message twice.

---

## 06 — Build + deploy

Detail: `docs/_review_scratch_2026-05-03/06_build_deploy.md`

**Good news first:** the Java 8 work in commit 79cfe57 holds. No Java 9+ APIs (`List.of`, `Map.of`, `String.repeat`, `String.strip`, `Stream.toList`, `var` outside tests, switch expressions) found anywhere under `src/main/java`. The `TerminalProviderFactory` lazy-load works as documented.

**BUG**
- `ImageJAIPlugin.java:260` — Hardcoded user path. *(See TL;DR #2.)*
- `pom.xml:145` — `maven-jar-plugin` has no `<version>`; inherits from scijava parent. Pin it for reproducibility.

**ISSUE**
- `pom.xml:136` — `maven-compiler-plugin` also unpinned.
- `build.sh:9` — `-DskipTests` unconditional. 30+ test classes never run on any local build, so the Java-8-compat work is not regression-tested.
- `docs/java8-compatibility.md:13-14` — The "nothing outside `engine/terminal/embedded` may import pty4j/JediTerm" rule is documented but **not enforced**. A `forbidden-apis` Maven plugin would catch a future violation at compile time instead of `NoClassDefFoundError` at user runtime.

**INEFFICIENCY**
- No `.github/workflows/` exists — Java 8 compat has no automated check on commit. A multi-JDK matrix build (8, 11, 25) would catch regressions immediately.
- `build.sh:6` — Deploy path assumes `../../Fiji.app`. Make it overridable via `FIJI_HOME`.

**NIT** — `pom.xml:32` `scijava.jvm.build.version=[8,)` is a wide range; document the intentional `enforcer.skip=true` override.

---

## What to look at first when you wake up

Open this list in priority order:

1. **Multi-provider safety regression first** — TL;DR #1 (budget ceiling not enforced) and TL;DR #4 (provider status always NEEDS_SETUP) are both Phase-D/H regressions that affect every user every time they launch. Both are small fixes (a missing read in `AgentLaunchOrchestrator`, a one-line change in `ProviderRegistry.java:189`). Settings migration (multi-provider BUG #2) belongs in the same PR — without it, the Phase-H flag flip silently loses every existing user's "last agent".
2. **TCP suppression leak** — TL;DR #3. Wrap `pushSuppress`/`popSuppress` in try/finally. After this, any silent "events stopped firing" reports you've heard recently get a plausible cause.
3. **Hardcoded Dropbox path** — TL;DR #2. One-line removal; unblocks any non-you user (and CI when it lands).
4. **Settings concurrency** — TL;DR #5. `synchronized` + atomic rename in `Settings.save()` is a small change with broad blast-radius coverage; multi-provider added the panels that surface this latent bug.
5. **Python helpers cleanup** — the duplicate `imagej_events()` (`ij.py:829`), missing UTF-8 in `probe_plugin.py`, and the `pixels.py` socket leak are independent and small. Could be one cleanup PR.
6. **Recipe schema bugs** — the two YAML files (`incucyte_gfp_extraction.yaml`, `trackmate_cell_traces.yaml`) need `parameters:` reshaped from dict to list. Trivial mechanical edit; verify with `recipe_search.py --validate` afterwards.
7. **Build hardening** (forbidden-apis, plugin version pins, CI) is a worthwhile follow-up but not urgent — Java 8 compat is currently clean by accident, not by enforcement.

The lab-contamination flags in `04_recipes_and_refs.md` are minor — defer to your judgement on whether to neutralise the SCN/BMAL1/PER2 examples in general references; the reviewer agreed they are example-selection bias rather than real contamination.

---

*Scratch files retained at `docs/_review_scratch_2026-05-03/` for full per-finding context. Safe to delete once items are triaged.*
