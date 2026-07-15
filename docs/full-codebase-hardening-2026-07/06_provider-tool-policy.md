# Provider-specific host-tool policy

## Why this stage exists

Every rich provider currently receives unrestricted shell/script/process tools, including `shell=True`, and the safety hook always permits them. A cloud model must not receive host-code execution merely because a tool exists in the local registry.

## Prerequisites

- Stage 01 must be `_COMPLETED`.

## Read first

- `docs/full-codebase-hardening-2026-07/00_overview.md`.
- `docs/codebase-review-2026-07-15.md`, release blocker 4 and "Provider and context runtime".
- `agent/gemma4_31b/registry.py`.
- `agent/gemma4_31b/loop.py` (TODO: locate tool schema construction and pre-dispatch safety).
- `agent/gemma4_31b/tools_shell.py` and `tools_python.py`.
- `agent/providers/base.py` and `router.py`.

## Scope

- Exclude shell/script/process tools from cloud provider schemas by default.
- Give local providers host-code tools only through an explicit capability.
- Require visible per-call approval for cloud elevation with exact command/script preview and no silent remember option.
- Replace `shell=True` with structured argv where retained.
- Validate provider model/CLI identifiers before any shell or process boundary.
- Add policy/schema tests for local, cloud, and elevated cases.

## Out of scope

- Provider adapter/loop/proxy reliability belongs to stage 07.
- Java launcher posture and credential handling belong to stage 08.
- Context overlay and heartbeat behavior belong to stage 18.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `agent/gemma4_31b/registry.py` | MODIFY | Filter schemas by provider/capabilities. |
| `agent/gemma4_31b/loop.py` | MODIFY | Enforce approval before dispatch. |
| `agent/gemma4_31b/tools_shell.py` | MODIFY | Remove `shell=True` and expose exact previews. |
| `agent/gemma4_31b/tools_python.py` | MODIFY | Gate script execution. |
| `agent/providers/base.py` | MODIFY | Carry provider locality/capability policy. |
| `agent/providers/router.py` | MODIFY | Populate trusted provider classification. |
| `agent/gemma4_31b/tests/test_tool_policy.py` | NEW | Regression matrix for schemas and approval. |

## Implementation sketch

Build tool schemas from an allowlist derived from `provider.is_local` plus explicit capabilities. The default cloud allowlist excludes arbitrary host-code primitives. An elevation request displays provider, exact argv/script, working directory, and one-call scope; denial is a structured tool error. Validate identifiers against the provider registry and use argument arrays for subprocesses.

## Exit gate

1. Cloud schemas contain no shell/script/process execution by default.
2. Local schemas include them only when the explicit capability is true.
3. Approval denial executes nothing; approval applies to exactly one matching call.
4. Shell metacharacters in model/CLI identifiers are rejected before process creation.
5. Provider and Gemma policy test suites pass.

## Known risks

- Do not misclassify a loopback proxy as a local model if it forwards to cloud inference.
- Preserve safe Fiji semantic tools; the restriction is arbitrary host-code execution, not ImageJ analysis commands.
