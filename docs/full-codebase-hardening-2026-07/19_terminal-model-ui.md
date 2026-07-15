# Reliable terminal, model discovery, and credentials UI

## Why this stage exists

Terminal writes can fail silently while the prompt is cleared, session output can cross-talk through a singleton scrubber, and launch synchronization can block without draining output. Model refresh and credential/install flows can freeze the Swing thread, race stale results, or save rejected keys.

## Prerequisites

- Stages 08 and 10 must be `_COMPLETED`.

## Read first

- `docs/full-codebase-hardening-2026-07/00_overview.md`.
- `docs/codebase-review-2026-07-15.md`, "UI, settings and terminal behavior".
- `src/main/java/imagejai/engine/AgentLauncher.java`.
- `src/main/java/imagejai/ui/TerminalHost.java`, `TerminalView.java`, and `src/main/java/imagejai/terminal/PromptWatcher.java`.
- `src/main/java/imagejai/engine/picker/ProviderRegistry.java` and `ProviderDiscovery.java`.
- `src/main/java/imagejai/ui/installer/wizard/CredentialVerifier.java` and credential wizard classes.

## Scope

- Surface PTY write failures and retain prompt text for retry.
- Scope scrubber/buffers to one session and prevent control-sequence cross-talk.
- Drain launch output concurrently, apply a timeout, and quote arguments safely.
- Make PromptWatcher lifecycle and failures observable.
- Make model refresh concurrent, cancellable, generation-tagged, and immune to stale completion.
- Bound provider discovery responses.
- Run credential validation/install work off the Swing thread; never persist a rejected key.
- Make settings/discovery load/save corruption visible.

## Out of scope

- Launch posture/credential transport is completed in stage 08.
- Terminal-control keyboard/availability behavior belongs to stage 20; visual/dialog lifecycle belongs to stage 21.
- Global buffer sizes are finalized in stage 23.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `src/main/java/imagejai/engine/AgentLauncher.java` | MODIFY | Safe bounded launch synchronization. |
| `src/main/java/imagejai/ui/TerminalHost.java` | MODIFY | Report writes and scope session state. |
| `src/main/java/imagejai/terminal/PromptWatcher.java` | MODIFY | Make watcher lifecycle/failures observable. |
| `src/main/java/imagejai/engine/picker/ProviderRegistry.java` | MODIFY | Concurrent generation-safe refresh. |
| `src/main/java/imagejai/engine/picker/ProviderDiscovery.java` | MODIFY | Bound discovery response/time. |
| `src/main/java/imagejai/ui/installer/wizard/CredentialVerifier.java` | MODIFY | Background validation and accepted-only persistence. |
| `src/test/java/imagejai/engine/picker/ProviderRegistryRefreshTest.java` | MODIFY | Cover cancellation/stale result races. |
| `src/test/java/imagejai/ui/TerminalReliabilityTest.java` | NEW | Cover write failure/session isolation/timeouts. |

## Implementation sketch

PTY send returns a result/future; UI clears input only after confirmed write. Give every session its own decoder/scrubber and lifecycle. Model refresh assigns a monotonically increasing generation and applies only the newest non-cancelled result. Credential workers return validated secrets to the event thread only after success; failures leave storage untouched.

## Exit gate

1. A failed PTY write is visible and the unsent prompt remains editable.
2. Two simultaneous sessions cannot mix partial lines/control sequences.
3. Launch timeout drains output, kills only the owned process tree, and returns an error.
4. Slow stale model refresh cannot overwrite newer results.
5. Credential failures block persistence and never freeze the event thread.
6. PromptWatcher start/stop/failure paths are directly tested and observable.

## Known risks

- Never include secret values in validation errors or test fixtures.
- Cancellation must ignore late callbacks as well as interrupt background work.
