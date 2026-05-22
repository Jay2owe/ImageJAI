# 01 — Settings and per-folder Privacy Posture infrastructure

## Why this stage exists

Every other stage reads from one source of truth: "what is the
current Privacy Posture?" Stage 01 builds that source of truth.
Without it the `PseudonymisationFilter` has nothing to gate on, the
launcher allowlist has no posture to consult, the audit log has
nothing to record, the Browse Files dialog has no posture context,
and the PDF has nothing to summarise.

The per-folder model (rather than a global toggle) is load-bearing:
biologists already think in folders-per-experiment, and the
per-folder posture is what stops the "PI ticks safe-mode, student
silently un-ticks" two-track deception failure mode.

## Prerequisites

None.

## Read first

- `docs/data-governance/00_overview.md` — house rules and framing.
- `src/main/java/imagejai/config/Settings.java` — locate existing
  `safeModeEnabled`. IGNORE the conflicted-copy file.
- `src/main/java/imagejai/config/Constants.java` — for any path
  constants. IGNORE the conflicted copy.
- `src/main/java/imagejai/engine/ImageMonitor.java` (full file) and
  any class with `addImageListener` / `ImageOpenedListener` to find
  the folder-open trigger point.
- `CLAUDE.md` — house rule about `AI_Exports/`.

## Scope

- `PrivacyPosture` enum (STANDARD, PSEUDONYMISED, ON_PREMISES).
- `privacyPosture` field in `Settings.java` (default
  `PSEUDONYMISED`), independent of `safeModeEnabled`.
- `FolderPostureStore` reading / writing `.imagejai-posture.json`
  next to image folders.
- On folder-open:
  - If `.imagejai-posture.json` exists → load and apply.
  - If not → show non-modal banner; default Pseudonymised; write
    file on first answer.
- Auto-downshift when entering a stricter folder mid-session
  (loud, logged). Never auto-upshift.
- Logged-override button writes audit row with free-text reason
  (audit log itself ships in stage 04 — emit an event for now).
- `PostureController` event hub with a `Listener` interface so
  later stages (badge, redactor, audit log, file browser) can
  subscribe.

## Out of scope

- The `PseudonymisationFilter` itself (stage 02).
- The Browse Files dialog (stage 09).
- Refusing `*-cloud` Ollama tags (stage 03).
- Audit CSV writes (stage 04).
- Coloured posture badge (stage 03).
- Egress lamp, Receipts pane, Configuration Pane (stage 05).

## Files touched

| Path | NEW/MODIFY | Reason |
|------|------------|--------|
| `src/main/java/imagejai/config/PrivacyPosture.java` | NEW | Enum + human-readable labels |
| `src/main/java/imagejai/config/Settings.java` | MODIFY | Add `privacyPosture` field, getter, setter |
| `src/main/java/imagejai/config/FolderPostureStore.java` | NEW | `.imagejai-posture.json` read/write |
| `src/main/java/imagejai/ui/PostureBanner.java` | NEW | Non-modal banner Swing component |
| `src/main/java/imagejai/engine/PostureController.java` | NEW | Session posture + listeners + downshift logic |
| `src/main/java/imagejai/engine/ImageMonitor.java` | MODIFY | Hook image-opened → `PostureController.onFolderOpened`. TODO: locate the exact method. |

## Implementation sketch

`PrivacyPosture`:

```java
public enum PrivacyPosture {
    STANDARD("Standard", "Cloud agents allowed, no pseudonymisation."),
    PSEUDONYMISED("Pseudonymised", "Cloud agents allowed; identifiers tokenised before send (UK GDPR Art. 4(5))."),
    ON_PREMISES("On-premises", "Local agents only; pseudonymisation also applied.");

    public final String label;
    public final String description;
    PrivacyPosture(String label, String description) { ... }
    public boolean isStricterThan(PrivacyPosture other) { return ordinal() > other.ordinal(); }
}
```

`Settings.java` (add next to `safeModeEnabled`):

```java
private PrivacyPosture privacyPosture = PrivacyPosture.PSEUDONYMISED;
public PrivacyPosture getPrivacyPosture() { return privacyPosture; }
public void setPrivacyPosture(PrivacyPosture p) { this.privacyPosture = p; }
```

`.imagejai-posture.json` format:

```json
{
  "posture": "PSEUDONYMISED",
  "set_by": "user",
  "set_at": "2026-05-21T14:32:18Z",
  "notes": ""
}
```

Banner copy (Swing, non-modal):

```
┌─────────────────────────────────────────────────────────────────────┐
│  Privacy Posture for this folder                                  ✕ │
│                                                                     │
│   ( ) Standard          Cloud agents, no pseudonymisation           │
│   (●) Pseudonymised     Cloud agents, identifiers tokenised         │
│   ( ) On-premises       Local agents only                           │
│                                                                     │
│   Recommended workflow in Pseudonymised mode:                       │
│   open images via Fiji File → Open or the Browse Files dialog —     │
│   do not paste filenames into the agent chat.                       │
│                                                                     │
│   This setting is remembered for this folder.                       │
│                                                          [ Apply ]  │
└─────────────────────────────────────────────────────────────────────┘
```

Downshift banner:

```
┌─────────────────────────────────────────────────────────────────────┐
│  Posture downshifted: Standard → Pseudonymised               ✕      │
│  Folder: MOAB2_AF488/                                               │
│  Outbound responses now pass through the pseudonymisation filter.   │
│                                                                     │
│   [ Override (logged) ]                              [ OK ]         │
└─────────────────────────────────────────────────────────────────────┘
```

`PostureController` event hub:

```java
public final class PostureController {
    public interface Listener {
        void postureChanged(PrivacyPosture from, PrivacyPosture to, Path folder);
    }
    public void addListener(Listener l);
    public PrivacyPosture current();
    public void onFolderOpened(Path folder);
    public void requestPosture(PrivacyPosture p, Path folder, String reason);
}
```

## Exit gate

1. `mvn compile` passes, no new warnings.
2. `PrivacyPostureTest` asserts the stricter-than ordering.
3. `FolderPostureStoreTest` round-trips write→read.
4. Manual: open Fiji, launch plugin, open an image from a folder with
   no `.imagejai-posture.json` → banner appears, default
   Pseudonymised, Apply writes the file.
5. Manual: open second image from On-premises folder while session is
   in Standard → downshift banner fires; `PostureController.current()`
   returns ON_PREMISES.
6. Manual: open image from Standard folder while session is
   On-premises → NO upshift; posture remains On-premises.

## Known risks

- Folder-open in Fiji can fire multiple times for one user action
  (Bio-Formats series import). Debounce in `PostureController` —
  consult the store once per (folder, second).
- `.imagejai-posture.json` writing on a read-only filesystem must
  not crash — fall back to in-memory-only posture and surface a
  warning row in the launcher.
- The "conflicted copy" `Settings` file is a Dropbox artefact; do
  not edit or delete.
