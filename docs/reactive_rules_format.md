# Reactive Rules — File Format

ImageJAI's reactive rules engine (Phase 8) loads rule files from
`~/.imagej-ai/reactive/`. Each `*.json` file in that directory defines one
rule that fires when a matching event is published on the plugin's event bus.

## Why JSON, not YAML?

The phase spec proposed YAML. Fiji's dependency set does not ship a YAML
parser, and ImageJAI's `pom-scijava` constraint forbids adding one. The
engine therefore uses JSON. To convert an existing YAML rule:

```bash
python -c "import yaml, json, sys; print(json.dumps(yaml.safe_load(open(sys.argv[1]))))" rule.yaml > rule.json
```

## File layout

```
~/.imagej-ai/reactive/
  incucyte_calibration.json   # one rule per file
  auto_close_errors.json
  reactive.lock               # if present, the engine is disabled
```

Edits are hot-reloaded within ~500ms (debounced). Malformed files are
quarantined and surfaced via `list_reactive_rules` rather than blocking the
rest of the rule set.

## Schema

```json
{
  "name": "strip_incucyte_calibration",
  "description": "Short human-readable one-liner.",
  "enabled": true,
  "priority": 100,
  "when": {
    "event": "image.opened",
    "where": {"unit": "inch"}
  },
  "do": [
    {"execute_macro": "run(\"Properties...\", \"unit=pixel\");"},
    {"gui_action": {"type": "toast", "message": "..."}}
  ],
  "rate_limit": "1/sec",
  "wait_before": 0
}
```

| Field          | Type     | Default | Notes                                                 |
|----------------|----------|---------|-------------------------------------------------------|
| `name`         | string   | —       | Unique; used by `reactive_enable` / `reactive_disable`. |
| `description`  | string   | `""`    | Free text.                                            |
| `enabled`      | boolean  | `true`  | Runtime toggle survives reloads.                      |
| `priority`     | int      | `100`   | Lower = fires earlier. Tie-break by source file path. |
| `when.event`   | string   | —       | Exact topic, `image.*` suffix wildcard, or `*`.       |
| `when.where`   | object   | `{}`    | Equality match on nested `data.*` fields.             |
| `do`           | array    | —       | Ordered action list, see below.                        |
| `rate_limit`   | string   | —       | `N/sec`, `N/min`, `N/hour`. Token bucket per rule.    |
| `wait_before`  | int (ms) | `0`     | Delay before first action runs.                       |

## Action types

| Action            | Payload                                       | Behaviour |
|-------------------|-----------------------------------------------|-----------|
| `execute_macro`   | string (ImageJ macro code)                    | Runs via `CommandEngine.executeMacro`. |
| `publish_event`   | `{topic, data?}`                              | Re-publishes on the bus. |
| `gui_action`      | `{type, ...}`                                 | Delegates to Phase 7 dispatcher. No-op if chat panel absent. |
| `run_intent`      | string (phrase)                               | Phase 5 IntentRouter lookup + macro exec. No-op if unavailable. |
| `close_dialog`    | `{title_matches: "<regex>"}`                  | Disposes non-image windows matching the title regex. |
| `capture`         | string (base name, optional)                  | Writes active image PNG to `~/.imagej-ai/captures/`. |
| `wait`            | string (`"250ms"`, `"2s"`) or int (ms)        | Sleeps before the next action. |

## Cycle safety

Rate limiting prevents a rule from firing more often than its window allows.
The engine additionally logs a warning if a rule re-matches within 1s of an
event it published itself — the rate limiter still enforces the window, so
this is informational.

## Kill switch

Create an empty file at `~/.imagej-ai/reactive/reactive.lock` to suspend
*all* rule dispatch without editing individual rule files. Delete the file
to re-enable.

## TCP commands

```bash
python ij.py reactive list            # rules + hit counts, sorted by priority
python ij.py reactive enable <name>   # runtime toggle (survives reload)
python ij.py reactive disable <name>
python ij.py reactive reload          # force reload
```

Examples live under `docs/reactive_rules_examples/`.
