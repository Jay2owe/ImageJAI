# Bounded, reliable provider runtime

## Why this stage exists

Provider adapters can return before attaching vision, omit event subscribers, drift from tool contracts, ignore budgets, and leave loops or proxy state running without secure bounds. This stage builds on stage 06 policy without reopening arbitrary host-tool access.

## Prerequisites

- Stages 04 and 06 must be `_COMPLETED`.

## Read first

- `docs/full-codebase-hardening-2026-07/00_overview.md`.
- `docs/codebase-review-2026-07-15.md`, "Provider and context runtime".
- `agent/gemma4_31b/loop.py` and `budget_ceiling.py`.
- `agent/providers/base.py`, `router.py`, `anthropic_native.py`, `gemini_native.py`, and `litellm_proxy.py`.

## Scope

- Attach captured images to every vision-capable provider adapter.
- Start the governed event subscriber for every wrapper.
- Correct tool contract names, optional arguments, and real JSON-schema arrays.
- Connect token/cost fallback and bound model/tool rounds plus history/pixel payloads.
- Make interrupt join the owned worker and honor Gemini timeout with narrow retries.
- Authenticate LiteLLM on loopback and secure unpredictable runtime files.
- Quarantine `_spike` and obsolete provider wrappers from production imports; stage 26 excludes them from bundles.

## Out of scope

- Host-code tool availability/approval is fixed in stage 06.
- Context family/heartbeat behavior belongs to stage 18.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `agent/gemma4_31b/loop.py` | MODIFY | Bound rounds, interrupt, vision, and events. |
| `agent/ollama_agent/budget_ceiling.py` | MODIFY | Connect budget fallback. |
| `agent/providers/base.py` | MODIFY | Normalize runtime contracts. |
| `agent/providers/router.py` | MODIFY | Select only production adapters. |
| `agent/providers/anthropic_native.py` | MODIFY | Attach vision/events. |
| `agent/providers/gemini_native.py` | MODIFY | Attach vision, timeout, and retries. |
| `agent/providers/litellm_proxy.py` | MODIFY | Authenticate and secure proxy lifecycle. |
| `agent/providers/test_multiprovider_fixes.py` | MODIFY | Runtime regression matrix. |

## Implementation sketch

Adapters build the complete request, including image attachments, before returning. The loop enforces a total turn/tool ceiling and budget fallback, bounds retained payloads, and joins its worker on interrupt. Provider schemas are contract-tested against the registry. Proxy startup uses an authenticated loopback endpoint and private, collision-resistant runtime state.

## Exit gate

1. Every vision adapter attaches images and every wrapper consumes stage-04 events.
2. Tool contract/schema tests match the actual registry.
3. Budget, round, history, and pixel limits terminate predictably.
4. Interrupt joins the worker; Gemini honors timeout and retries only transient failures.
5. LiteLLM rejects unauthenticated calls and leaves no insecure runtime file.
6. Experimental/obsolete providers are not production-importable.

## Known risks

- A loopback proxy forwarding to cloud is still cloud for policy purposes.
- Preserve native Ollama vision behavior while aligning the other adapters.

