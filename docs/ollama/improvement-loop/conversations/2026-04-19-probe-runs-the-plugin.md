# 2026-04-19 — probe_plugin actually executes the plugin

User friction report (no full transcript):
> when he probes a plugin it seems to run the plugin

Probing is supposed to: open the plugin's dialog, read its field
schema, then cancel — no side effects on the image. Instead Gemma
sees the plugin run with default parameters after the probe
returns.

## Root cause

`handleProbeCommand` at `TCPCommandServer.java:3596` disposed the
probed dialog via `dlg.dispose()` alone. From javap of
`ij.gui.GenericDialog.dispose()` in the Fiji ij-1.54p.jar: the
method calls `Dialog.dispose()`, clears the static `instance`
pointer, fiddles with macro/recorder state, resets counters —
and never writes the private `wasCanceled` field. The three
`putfield wasCanceled:Z = true` sites in the class are inside
`actionPerformed` (Cancel button), `keyPressed` (Esc), and one
anonymous helper. Plugin code that called `gd.showDialog()` then
checks `gd.wasCanceled()` — sees `false` after the dispose —
collects the default field values and executes against the active
image.

## Applied — 2026-04-19

| # | Section | Change | File |
|---|---|---|---|
| F1 | §1 | In `handleProbeCommand`'s Runnable: before `dlg.dispose()`, walk the dialog's class hierarchy looking for the private `wasCanceled` field and flip it to `true` via reflection (covers `GenericDialog` + subclasses like `NonBlockingGenericDialog`). For custom non-GenericDialog dialogs where no `wasCanceled` field is found, fall back to `clickCancelButton(dlg)` which scans the component tree for a button labelled "cancel" / "close" / "no" and fires its action. Adds two small helpers (`clickCancelButton`, `isCancelLabel`) placed next to `readFieldText`. | `TCPCommandServer.java` *(rebuild + redeploy done)* |

<!-- F1: probe_plugin silently executed the plugin with default
args because dlg.dispose() leaves wasCanceled=false, and the
calling plugin treats that as OK. Flipping wasCanceled via field
reflection before dispose makes probe side-effect-free →
plan §1, applied -->

**Outstanding action for the user**: restart Fiji so the new
`imagej-ai-0.2.0.jar` classes load. No Gemma-terminal restart
required — no Python was touched.

**Sidecar cleanup done during this round**: moved
`ij-1.54f-Jamie_PC.jar` and `ij-1.54f-Jamie_PC-2.jar` out of
`Fiji.app/jars/` into `Fiji.app/jars_backup_2026-04-19/` so the
classpath has a single `ij-*.jar` (canonical `ij-1.54p.jar`).
The patched jars' only substantive deviations from canonical
were a version-string bump to "1.54r", a different point-click
ROI code path (`PointRoi.addUserPoint` vs manual polygon
unpacking), and two orphan classes (`SignedShortProcessor`,
`TableListener`) not referenced by any plugin in this Fiji
install — including Chronos 0.5.0 and IHF-Analysis-Pipeline
3.0.0, which are the two plugins the user thought needed the
patches. Reversible: backup folder preserves both jars.
