# Policy-backed assistants and transactional settings

## Why this stage exists

ConversationLoop and Local Assistant can mutate Fiji outside the coordinator and posture/safe-mode policy. Their visible chat and backend state also diverge when conversations are cleared or settings are cancelled/saved.

## Prerequisites

- Stages 05 and 08 must be `_COMPLETED`.

## Read first

- `docs/full-codebase-hardening-2026-07/00_overview.md`.
- `docs/codebase-review-2026-07-15.md`, privacy blocker and "UI, settings and terminal behavior".
- `src/main/java/imagejai/ConversationLoop.java`.
- `src/main/java/imagejai/local/LocalAssistant.java` and `FijiBridge.java`.
- `src/main/java/imagejai/config/Settings.java`.
- `src/main/java/imagejai/ui/SettingsDialog.java`, `AiRootPanel.java`, and `ChatView.java`.

## Scope

- Route assistant mutations through `MutationCoordinator` and `LaunchPolicy`.
- Apply safe-mode scanning, undo, provenance, and posture to destructive assistant intents.
- Make Settings Cancel restore the exact pre-dialog snapshot.
- On save, refresh or recreate affected live backends safely.
- Clear visible HTML, ConversationLoop history, Local Assistant context, and pending turn together.
- Remove posture-listener leaks from assistant/settings lifecycle.

## Out of scope

- Terminal PTY and provider discovery behavior belongs to stage 19.
- UI keyboard work belongs to stage 20; contrast/dialog lifecycle belongs to stage 21.
- Phrasebook matching belongs to stage 22.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `src/main/java/imagejai/ConversationLoop.java` | MODIFY | Share policy/coordinator and clear state. |
| `src/main/java/imagejai/local/LocalAssistant.java` | MODIFY | Govern intents and clear context. |
| `src/main/java/imagejai/local/FijiBridge.java` | MODIFY | Dispatch mutations through the coordinator. |
| `src/main/java/imagejai/config/Settings.java` | MODIFY | Support immutable snapshots and safe apply. |
| `src/main/java/imagejai/ui/SettingsDialog.java` | MODIFY | Make Cancel transactional. |
| `src/main/java/imagejai/ui/AiRootPanel.java` | MODIFY | Rebuild backend and clear all layers. |
| `src/main/java/imagejai/ui/ChatView.java` | MODIFY | Align visible clearing with controller state. |
| `src/test/java/imagejai/local/ConversationMemoryTest.java` | MODIFY | Cover clear, cancel, save, and policy paths. |

## Implementation sketch

Open Settings against a detached working copy; Apply/OK validates then atomically swaps settings and recreates only the affected backend. Cancel discards the copy. Define one clear-conversation action invoked by all UI paths that clears view, remote/local histories, pending prompts, and image attachment state. Assistant Fiji actions submit typed coordinator requests rather than directly calling ImageJ.

## Exit gate

1. Cancel leaves persisted and live settings byte-for-byte/effectively unchanged.
2. Saving a backend-affecting setting replaces the live backend exactly once.
3. Clearing leaves no user/model messages or pending local context in any layer.
4. Assistant destructive actions are denied/approved, undone, and journaled like TCP actions.
5. Repeated panel/dialog creation does not increase posture listener count.

## Known risks

- Backend recreation must not lose an active conversation unless the changed setting requires it; define the behavior in tests.
- Never call blocking model or mutation work on the Swing event thread.
