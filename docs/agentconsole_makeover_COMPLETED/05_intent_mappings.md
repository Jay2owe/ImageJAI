# Phase 5 — Server-Side Intent Mappings

## Motivation

Researchers reuse the same English phrases constantly: *"rolling ball 50"*, *"gaussian 2 sigma"*, *"threshold otsu"*, *"measure"*, *"subtract channel 1"*. Every one of those, today, takes a full trip through the LLM — a Gemini call, a response parse, a macro execution. That's 300–2000ms of latency and real tokens per invocation, for a phrase the user has said 400 times.

AgentConsole's intent router (`docs/commander_control/03_intent_router.md`, storage at `Second Brain/runtime/intent_mappings.json`) solves this: the user says something, the router checks the learned dictionary first, executes the mapped action directly, and only falls back to the LLM on a miss. A teach-once command persists the mapping.

Metaphor: the difference between saying *"coffee"* to your partner (who makes it how you like) and reciting the recipe every morning.

## End Goal

- TCP command `intent {"phrase": "rolling ball 50"}` → resolves against the stored mappings and either executes the mapped macro or returns `{"ok": false, "miss": true, "suggestion": null}`.
- TCP command `intent_teach {"phrase": "rolling ball 50", "macro": "run('Subtract Background...', 'rolling=50');"}` — persists the mapping.
- Mappings stored in `~/.imagej-ai/intent_mappings.json` (human-editable).
- Pattern support: `"rolling ball <N>"` with `<N>` substituting a numeric argument into the macro body.
- External agents (ij.py / context hook) call `intent` first, fall back to LLM only on miss.

## Scope

### Server-side: `engine/IntentRouter.java` (new)

- Loads mappings from `~/.imagej-ai/intent_mappings.json` at startup; hot-reloads on file change (watcher or on-demand stat).
- Matches phrase against each mapping's regex. First match wins.
- Simple capture-group substitution: mapping pattern `"rolling ball (\d+)"` + template `run("Subtract Background...", "rolling=$1");` → substitutes.
- Returns a resolved macro string ready for `CommandEngine`.

### Server-side: `engine/TCPCommandServer.java`

- `intent`: resolve and execute. Returns `{ok, result, mapped_to}` on hit, `{ok: false, miss: true}` on miss.
- `intent_teach`: add/update a mapping.
- `intent_list`: dump all current mappings.
- `intent_forget {"phrase": "..."}`: remove a mapping.

### Storage format: `~/.imagej-ai/intent_mappings.json`

```json
{
  "version": 1,
  "mappings": [
    {
      "pattern": "rolling ball (\\d+)",
      "macro": "run(\"Subtract Background...\", \"rolling=$1\");",
      "description": "Subtract Background with rolling ball radius",
      "hits": 42,
      "created_at": "2026-04-17T10:23:00Z",
      "last_used": "2026-04-17T14:10:33Z"
    }
  ]
}
```

### Client-side: `agent/ij.py`

- `python ij.py intent "rolling ball 50"` → runs it.
- `python ij.py teach "rolling ball <N>" 'run("Subtract Background...", "rolling=<N>");'` → teaches it.
- Optional integration in context hook: before routing a prompt to the LLM, probe `intent`; if hit, inject `[INTENT EXECUTED: ...]` into context and skip the LLM.

## Implementation Sketch

```java
// IntentRouter.java
public class IntentRouter {
    private List<Mapping> mappings = new ArrayList<>();
    private long lastLoaded;

    public Optional<String> resolve(String phrase) {
        reloadIfChanged();
        for (Mapping m : mappings) {
            Matcher mt = m.pattern.matcher(phrase.trim());
            if (mt.matches()) {
                m.hits++;
                m.lastUsed = System.currentTimeMillis();
                return Optional.of(substitute(m.template, mt));
            }
        }
        return Optional.empty();
    }

    public void teach(String patternStr, String template, String description) {
        Pattern p = Pattern.compile(patternStr, Pattern.CASE_INSENSITIVE);
        mappings.add(new Mapping(p, template, description));
        save();
    }
}
```

## Impact

- Repeat phrases skip the LLM entirely. A user running the same preprocessing on 50 files pays zero LLM cost after the first teach.
- Latency for mapped phrases drops from ~1s (LLM) to ~20ms (regex + macro execute).
- The mappings file becomes a **record of how the user actually talks to Fiji**, shareable between projects and users.
- External and internal use cases both benefit: the embedded chat panel can call `intent` first too (cross-phase enabler).

## Validation

1. `teach "rolling ball (\\d+)" 'run(...rolling=$1)'`; then `intent "rolling ball 50"` → executes correctly.
2. `intent "unknown phrase"` → `{miss: true}`.
3. Edit `~/.imagej-ai/intent_mappings.json` externally; next `intent` call reflects the edit (hot reload).
4. `intent_list` shows hit counts; most-used mappings surface first.

## Risks

- **Pattern collision.** Two patterns match the same phrase. Mitigation: "first match wins" with explicit ordering; `intent_list` shows the effective order; add `intent_reorder` later if needed.
- **Bad patterns brick the router.** An invalid regex added externally crashes every call. Mitigation: validate on load; quarantine broken mappings to an `errors` list returned by `intent_list`.
- **Teach pollution.** Users teach overly specific phrases; the dictionary grows stale. Mitigation: `hits` counter + `last_used`; offer a `intent_prune --unused 30d` housekeeping command.
- **Security.** Mappings execute arbitrary macro code. The file is user-owned, so no new attack surface vs. the existing macro execution — but document that editing the file == editing what arbitrary-sounding English phrases do.
