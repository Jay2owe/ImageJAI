# Keyboard-accessible primary UI

## Why this stage exists

Core actions in chat, model selection, provider status, receipts, and history are mouse-only, while chat traps Tab. This stage owns keyboard navigation and activation; stage 21 separately owns contrast, focus painting, and single-use dialog disposal.

## Prerequisites

- Stages 10 and 19 must be `_COMPLETED`.

## Read first

- `docs/full-codebase-hardening-2026-07/00_overview.md`.
- `docs/codebase-review-2026-07-15.md`, UI accessibility findings.
- `src/main/java/imagejai/ui/ChatPanel.java` and `ChatView.java`.
- `src/main/java/imagejai/ui/picker/ModelPickerButton.java` and `ProviderMenu.java`.
- `src/main/java/imagejai/ui/installer/ProviderCard.java`.
- `src/main/java/imagejai/ui/ReceiptsPane.java` and `SessionHistoryPanel.java`.

## Scope

- Restore normal Tab traversal while preserving explicit multiline input behavior.
- Give secondary model/provider/receipt/history actions focus and keyboard activation.
- Disable terminal controls without a live session and make them keyboard operable.
- Add headless component-level accessibility tests.

## Out of scope

- Provider discovery and terminal availability are completed in stage 19.
- ProviderCard controls, contrast, custom focus painting, and single-use dialogs belong to stage 21.
- Chat buffer limits belong to stage 23.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `src/main/java/imagejai/ui/ChatPanel.java` | MODIFY | Restore traversal/accessible actions. |
| `src/main/java/imagejai/ui/ChatView.java` | MODIFY | Expose accessible chat content/actions. |
| `src/main/java/imagejai/ui/picker/ModelPickerButton.java` | MODIFY | Keyboard picker operations/focus. |
| `src/main/java/imagejai/ui/picker/ProviderMenu.java` | MODIFY | Accessible provider status/actions. |
| `src/main/java/imagejai/ui/TerminalToolbar.java` | MODIFY | Gate and expose terminal actions. |
| `src/main/java/imagejai/ui/ReceiptsPane.java` | MODIFY | Keyboard receipt actions. |
| `src/main/java/imagejai/ui/SessionHistoryPanel.java` | MODIFY | Keyboard history actions. |
| `src/test/java/imagejai/ui/AccessibilityTest.java` | NEW | Traversal, activation, contrast, disposal regressions. |

## Implementation sketch

Use real buttons/actions with accessible names, mnemonics or Enter/Space bindings and normal focus traversal. Terminal actions are disabled when no session is live. Keep component focusability and accessible descriptions explicit; visual focus treatment is tested in stage 21.

## Exit gate

1. Keyboard-only tests can reach and activate every listed primary/secondary action.
2. Tab leaves the chat editor according to documented behavior.
3. Accessible names/roles are present for interactive chat, provider, receipt, and history controls.
4. Headless action/focus tests pass without mouse events.
5. Terminal controls are disabled without a live session and keyboard-operable with one.

## Known risks

- Preserve standard text-editing keys; do not overload Enter/Space where they insert text.
- Headless tests should inspect action/focus contracts, not pixel-perfect rendering.
