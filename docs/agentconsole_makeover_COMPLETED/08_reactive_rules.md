# Phase 8 — Reactive YAML Rules

## Motivation

Some reactions to Fiji events are **deterministic and repetitive**:

- An Incucyte image opens with unit = "inch" → strip calibration before any Subtract Background call (documented footgun, recorded in `feedback_incucyte_calibration.md`).
- A modal error dialog appears and nobody's paying attention → auto-close after 10s.
- A macro fails → auto-capture a screenshot for postmortem.
- Memory crosses 80% → release unused buffers + notify.
- A stack with >50 slices opens → auto-publish a "pick slice for preview" event.

Today, every one of these needs the external agent to notice (via polling or hook) and issue the corrective command. That costs an LLM turn per footgun. It also means the corrective action only happens when the user is in the middle of a conversation — not when they're not.

AgentConsole's `__reactive-architect` owns YAML files at `~/.agent-console/reactive/` that fire when bus events match a trigger, running chained actions (inject stdin, narrate, broadcast, run orchestrator command). Rules are data, not code — they hot-reload.

Metaphor: reflexes vs. conscious thought. You don't think about pulling your hand back from a hot stove. The plugin shouldn't need to think about stripping Incucyte calibration either.

## End Goal

- YAML files at `~/.imagej-ai/reactive/*.yaml` define rules.
- Each rule has: a `when` clause (bus event topic + optional field predicates), a `do` list of actions, an optional `rate_limit`.
- Supported actions: `execute_macro`, `publish_event`, `gui_action` (Phase 7), `close_dialog`, `capture`, `run_intent` (Phase 5).
- Engine loads on startup, watches for file changes, hot-reloads.
- TCP introspection: `list_reactive_rules`, `reactive_rule_stats` (hit counts).
- **Safety**: every rule can be disabled individually without deletion; `reactive.lock` file turns off the whole engine.

## Scope

### Server-side: `engine/ReactiveEngine.java` (new)

- Requires Phase 2 (event bus) as prerequisite — it's a bus subscriber.
- Subscribes to `*` at startup, filters to rules that match.
- Actions dispatch through existing handlers (`CommandEngine`, `IntentRouter`, `GuiActionDispatcher`, `EventBus`).
- Rate limiting per rule (avoid feedback loops: a rule that fires on `image.updated` and itself updates the image would infinite-loop without rate limit).

### YAML schema

```yaml
# ~/.imagej-ai/reactive/incucyte_calibration.yaml
name: strip_incucyte_calibration
description: Incucyte images ship with inch calibration which breaks Subtract Background.
when:
  event: image.opened
  where:
    unit: inch
do:
  - execute_macro: |
      run("Properties...", "unit=pixel pixel_width=1 pixel_height=1");
  - gui_action:
      type: toast
      message: "Stripped inch calibration (Incucyte footgun)"
      level: info
rate_limit: 1/sec
```

```yaml
# ~/.imagej-ai/reactive/auto_close_errors.yaml
name: auto_close_stale_error_dialogs
when:
  event: dialog.appeared
  where:
    kind: error
do:
  - wait: 10s
  - close_dialog:
      title_matches: ".*"
rate_limit: 5/min
```

### Server-side: `engine/TCPCommandServer.java`

- `list_reactive_rules` → all loaded rules with enabled flag + hit counts.
- `reactive_enable {name}` / `reactive_disable {name}` — toggle at runtime.
- `reactive_reload` — force reload.

## Implementation Sketch

```java
// ReactiveEngine.java
public class ReactiveEngine {
    private final List<Rule> rules = new CopyOnWriteArrayList<>();

    public ReactiveEngine(EventBus bus, CommandEngine engine, ...) {
        bus.subscribe("*", this::onEvent);
        watchDir(Paths.get(System.getProperty("user.home"), ".imagej-ai", "reactive"));
    }

    private void onEvent(JsonObject frame) {
        String topic = frame.get("event").getAsString();
        for (Rule r : rules) {
            if (!r.enabled) continue;
            if (!r.matches(topic, frame)) continue;
            if (!r.rateLimit.tryAcquire()) continue;
            r.hits++;
            executeActions(r, frame);
        }
    }
}
```

## Impact

- Known footguns fix themselves without any LLM turn.
- Behavioural customisation becomes **data** (YAML the user edits), not code — users tune the plugin to their pipeline without rebuilding.
- Rules are composable with every other phase: they can trigger intents (Phase 5), fire GUI actions (Phase 7), publish new events (Phase 2) that cascade into other rules.
- The lab's institutional knowledge (like "Incucyte breaks Subtract Background") becomes enforceable by the tool, not just documented in `learnings.md`.

## Validation

1. Drop `incucyte_calibration.yaml` into `~/.imagej-ai/reactive/`; open an inch-calibrated Incucyte tif → calibration stripped within 500ms without agent involvement.
2. Disable the rule via TCP → same image opens with calibration intact.
3. Edit the YAML → hot-reloads within 2s, no plugin restart.
4. `reactive_rule_stats` shows accurate hit counts.
5. Malformed YAML → rule skipped with error logged; other rules still load.

## Risks

- **Feedback loops.** A rule that reacts to its own side effect. Mitigation: rate limit per rule; cycle detector warns if a rule's actions retrigger itself within 1s.
- **Silent corrections confuse users.** "Why did my calibration change?" Mitigation: every rule must emit a trace (via `log.entry` or a toast via Phase 7) so users can see what fired.
- **Action surface grows unboundedly.** Keep the action set small and auditable: `execute_macro`, `close_dialog`, `capture`, `publish_event`, `gui_action`, `run_intent`. New actions need a design review.
- **YAML parsing.** No YAML parser in current dependencies. Mitigation: use a tiny embedded parser or switch this phase's config to JSON; document that YAML-familiar users can convert with a one-liner.
- **Ordering.** Multiple rules match the same event → undefined order. Mitigation: explicit `priority: N` field; stable sort; tie-break on file path.
