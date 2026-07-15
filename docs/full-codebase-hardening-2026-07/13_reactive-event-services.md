# Bounded reactive and event services

## Why this stage exists

Reactive queues can grow without bound, stale actions still execute, `enabled` is ignored, and rule mutations/captures bypass safety and provenance. Event topics grow unbounded, listeners can fail invisibly, and title-based identities collide.

## Prerequisites

- Stages 04 and 05 must be `_COMPLETED`.

## Read first

- `docs/full-codebase-hardening-2026-07/00_overview.md`.
- `docs/codebase-review-2026-07-15.md`, "Reactive and event services".
- `src/main/java/imagejai/engine/ReactiveEngine.java`.
- `src/main/java/imagejai/engine/EventBus.java` and `OutboundEvent.java`.
- `src/main/java/imagejai/engine/DialogWatcher.java` and `ImageMonitor.java`.
- `docs/reactive_rules/reactive_rules_format.md`.

## Scope

- Bound reactive admission and reject expired/stale work before execution.
- Route rule mutations and captures through policy/coordinator/provenance; enforce `AI_Exports/` and capture bounds.
- Enforce `enabled`; quarantine cyclic or repeatedly failing rules.
- Make hot reload honor configuration and report parse/action failures.
- Bound EventBus topic/state retention and surface listener exceptions.
- Use stable dialog/image identity rather than title alone.

## Out of scope

- Subscription authentication/privacy is completed in stage 04.
- Cross-cutting memory/performance caps belong to stage 23.

## Files touched

| Path | Change | Reason |
|---|---|---|
| `src/main/java/imagejai/engine/ReactiveEngine.java` | MODIFY | Bound and govern rule lifecycle. |
| `src/main/java/imagejai/engine/EventBus.java` | MODIFY | Bound topic state and expose listener failures. |
| `src/main/java/imagejai/engine/OutboundEvent.java` | MODIFY | Carry stable identity/timestamps. |
| `src/main/java/imagejai/engine/DialogWatcher.java` | MODIFY | Track dialogs by stable identity. |
| `src/main/java/imagejai/engine/ImageMonitor.java` | MODIFY | Track images by stable identity/order. |
| `src/test/java/imagejai/engine/ReactiveEngineTest.java` | NEW | Cover queue, stale, enabled, cycles, policy, reload. |
| `src/test/java/imagejai/engine/EventBusTest.java` | MODIFY | Cover retention and listener errors. |

## Implementation sketch

Give actions enqueue/deadline timestamps and acquire a bounded permit before queueing. Before execution, re-check enabled/quarantine/deadline and submit mutations through `MutationCoordinator`. Track consecutive failures with a bounded quarantine policy and observable event/log. EventBus retains only registered/bounded topic metadata, catches listener failures into diagnostics, and keys identities separately from display titles.

## Exit gate

1. Queue saturation rejects work before allocation; expired actions never execute.
2. Disabled/cyclic/quarantined rules cannot mutate and emit a clear reason.
3. Reactive captures stay under `AI_Exports/` and size limits; mutations have safety/undo/provenance.
4. Listener exceptions are observable and do not stop other listeners.
5. Repeated transient titles do not merge distinct dialog/image lifecycles.

## Known risks

- Quarantine thresholds need injected clocks/counters to avoid flaky sleeps.
- Do not allow diagnostic error events to recursively trigger the failing rule.
