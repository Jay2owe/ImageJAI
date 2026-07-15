# Readable visual surfaces and disposable dialogs

## Why this stage exists

Several fixed backgrounds lack matching foreground colors or visible focus treatment, especially model/status banners. Three single-use decision dialogs hide rather than dispose, retaining resources and listeners after their decision is complete.

## Prerequisites

- Stages 19 and 20 must be `_COMPLETED`.

## Read first

- `docs/full-codebase-hardening-2026-07/00_overview.md`.
- `docs/codebase-review-2026-07-15.md`, UI contrast and dialog lifecycle findings.
- `src/main/java/imagejai/ui/picker/BillingFailureDialog.java`, `BudgetCeilingDialog.java`, and `FirstUseDialog.java`.
- `src/main/java/imagejai/ui/picker/ModelMenuItem.java` and `TierChangeBanner.java`.
- `src/main/java/imagejai/ui/PostureBanner.java`.

## Scope

- Pair custom backgrounds with explicit readable foregrounds under light/dark themes.
- Preserve a visible focus indicator under custom chrome.
- Replace ProviderCard interactive labels with accessible controls/actions.
- Dispose the three single-use decision dialogs and release listeners/resources.
- Add theme contrast, focus, displayability, and listener-cleanup tests.

## Out of scope

- Keyboard navigation/action semantics are owned by stage 20.
- Terminal/model background work is completed in stage 19.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `src/main/java/imagejai/ui/picker/BillingFailureDialog.java` | MODIFY | Dispose after decision. |
| `src/main/java/imagejai/ui/picker/BudgetCeilingDialog.java` | MODIFY | Dispose after decision. |
| `src/main/java/imagejai/ui/picker/FirstUseDialog.java` | MODIFY | Dispose after decision. |
| `src/main/java/imagejai/ui/picker/ModelMenuItem.java` | MODIFY | Theme-safe foreground/focus. |
| `src/main/java/imagejai/ui/picker/TierChangeBanner.java` | MODIFY | Theme-safe foreground/focus. |
| `src/main/java/imagejai/ui/PostureBanner.java` | MODIFY | Theme-safe foreground/focus. |
| `src/main/java/imagejai/ui/installer/ProviderCard.java` | MODIFY | Accessible status/actions and contrast. |
| `src/test/java/imagejai/ui/VisualLifecycleTest.java` | NEW | Contrast/focus/disposal regressions. |

## Implementation sketch

Derive colors from UI defaults or use tested explicit pairs for both palettes. Retain standard focus painting or draw an accessible focus border. Set one-shot dialogs to dispose, unregister listeners on close, and assert they are no longer displayable after each outcome.

## Exit gate

1. Light/dark tests find no unreadable foreground/background pair on owned surfaces.
2. Focus remains visibly represented on custom controls.
3. All three dialogs are undisplayable after accept, reject, or close.
4. Repeated open/close does not grow listener/resource counts.
5. ProviderCard status/actions expose keyboard activation and accessible names.

## Known risks

- Avoid hard-coded colors that become unreadable under installed look-and-feel themes.
- Disposal must preserve the selected result before resources are released.
