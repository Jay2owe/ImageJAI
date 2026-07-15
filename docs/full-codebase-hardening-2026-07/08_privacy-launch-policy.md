# Unified privacy and launch posture policy

## Why this stage exists

Gemini credential discovery can place a key in a URL and cache it in plaintext, some provider/legacy paths bypass posture, and Claude launches with dangerous permission skipping enabled by default. Privacy and egress must be decided once for every launch and assistant path.

## Prerequisites

- Stage 01 must be `_COMPLETED`.

## Read first

- `docs/full-codebase-hardening-2026-07/00_overview.md`.
- `docs/codebase-review-2026-07-15.md`, release blocker 8.
- `src/main/java/imagejai/engine/AgentLauncher.java` (TODO: locate command construction, Claude flags, environment, and provider checks).
- `src/main/java/imagejai/engine/picker/ProviderDiscovery.java` and `ModelsCache.java`.
- `src/main/java/imagejai/engine/PostureController.java`.
- `src/main/java/imagejai/ConversationLoop.java` and `local/LocalAssistant.java`.
- `src/main/java/imagejai/config/Settings.java`.

## Scope

- Centralize launch/egress policy for all providers, on-prem launches, legacy ConversationLoop, and Local Assistant.
- Remove keys from URLs, logs, caches, arguments, and persisted model metadata.
- Default Claude dangerous permission skipping to false and require per-launch consent.
- Validate model/CLI IDs before command construction.
- Rewrite/revoke sidecar posture state when settings change.
- Add tests for every launch surface and credential leak path.

## Out of scope

- Credential wizard threading/validation belongs to stage 19.
- Terminal PTY reliability belongs to stage 19.
- Bundle secret/private-file scrubbing belongs to stage 26.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `src/main/java/imagejai/engine/LaunchPolicy.java` | NEW | Single launch posture/egress decision point. |
| `src/main/java/imagejai/engine/AgentLauncher.java` | MODIFY | Enforce policy and opt-in dangerous flag. |
| `src/main/java/imagejai/engine/picker/ProviderDiscovery.java` | MODIFY | Remove credential-bearing URL flows. |
| `src/main/java/imagejai/engine/picker/ModelsCache.java` | MODIFY | Never persist credentials. |
| `src/main/java/imagejai/engine/PostureController.java` | MODIFY | Rewrite/revoke stale sidecar posture. |
| `src/main/java/imagejai/ConversationLoop.java` | MODIFY | Use the centralized policy. |
| `src/main/java/imagejai/local/LocalAssistant.java` | MODIFY | Enforce posture/safe-mode decisions. |
| `src/test/java/imagejai/engine/AgentLauncherPostureTest.java` | MODIFY | Cover every launch path and opt-in flag. |

## Implementation sketch

`LaunchPolicy.evaluate(provider, model, posture, requestedCapabilities)` should return an allow/deny decision plus the permitted environment and flags. Credentials stay in an approved secret source/environment only and are stripped from discovery/cache objects. Direct/nonlocal/on-prem paths call the same evaluator. The dangerous Claude flag is absent unless an explicit one-launch choice is recorded.

## Exit gate

1. Tests prove no credential appears in URLs, caches, logs, or command arguments.
2. Every provider, legacy, Local Assistant, and direct launch path calls the same policy.
3. Default Claude commands omit `--dangerously-skip-permissions`; one-launch consent adds it only once.
4. Posture changes atomically replace or revoke sidecar state.
5. Malicious model/CLI identifiers cannot inject shell syntax.

## Known risks

- Do not print full launch environments in tests or diagnostics.
- Policy centralization can break legitimate local workflows; encode explicit local capabilities rather than bypasses.
