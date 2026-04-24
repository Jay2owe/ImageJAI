# Step 14 — Federated Mistake Ledger

## Motivation

Every improvement-loop round builds evidence of what fixes work
for specific errors: "when a macro fails with `Set Scale`
missing, add `run('Set Scale...', 'unit=pixel')` beforehand."
Today that knowledge lives in the conversation transcripts and
nowhere else. The next agent session starts from scratch.

A small on-disk ledger keyed by error fingerprint + confirmed fix
turns that accumulated experience into a queryable resource.
Each agent contributes by confirming what worked; each future
agent benefits by asking before guessing.

## End goal

A JSON file at `~/.imagejai/ledger.json`:

```json
{
  "version": 1,
  "entries": [
    {
      "fingerprint": "abc123...",
      "macroPrefix": "run(\"Set Scale...\", \"unit=pixel\")",
      "errorCode": "SCALE_MISSING",
      "errorFragment": "Set Scale required",
      "confirmedFix": "Add run(\"Set Scale...\", \"unit=pixel distance=1\") before any measurement.",
      "exampleMacro": "run(\"Set Scale...\", \"unit=pixel distance=1\");\nrun(\"Measure\");",
      "confirmedBy": ["claude-code", "gemma-31b"],
      "timesSeen": 47,
      "firstSeen": 1710000000000,
      "lastSeen": 1714000000000
    }
  ]
}
```

Two new TCP commands:

### `ledger_lookup`
```json
{"command": "ledger_lookup",
 "error_fragment": "Set Scale",
 "macro_prefix": "run(\"Measure\")"}
// Response
{"ok": true, "result": {
  "matches": [{
    "confirmedFix": "Add run(\"Set Scale...\", ...) before Measure",
    "exampleMacro": "...",
    "confirmedBy": ["claude-code", "gemma-31b"],
    "confidence": "high"  // based on confirmation count
  }]
}}
```

### `ledger_confirm`
```json
{"command": "ledger_confirm",
 "fingerprint": "abc123...",
 "fix": "run(\"Set Scale...\", \"unit=pixel distance=1\");",
 "worked": true}
```

Agent calls `ledger_confirm` after a fix succeeds. Server
updates `timesSeen`, adds agent to `confirmedBy`.

## Scope

In:
- Ledger file at `~/.imagejai/ledger.json` (OS user home). Create
  on first write. Never overwrite without merge.
- `LedgerStore` class: load on server start, persist on write,
  atomic-write via temp file + rename.
- Fingerprint function: SHA-256 of `(errorCode + errorFragment
  normalised + macroPrefix normalised)` truncated to 128 bits.
- Two new handlers: `ledger_lookup`, `ledger_confirm`.
- When an `execute_macro` fails with a known structured error,
  auto-attach ledger matches to the error's `suggested[]`
  without the agent asking.

Out:
- Cross-machine synchronisation. Keep it local; a future phase
  can add an opt-in shared ledger (lab-wide, versioned in Git).
- Auto-confirmation. Only explicit `ledger_confirm` counts.
  Inferring success from "next macro didn't hit the same error"
  is too noisy.

## Read first

- `docs/tcp_upgrade/02_structured_errors.md` — error codes we
  fingerprint.
- `docs/tcp_upgrade/04_fuzzy_plugin_registry.md` — how
  suggestions appear in the structured error's `suggested[]`.
- `docs/ollama_COMPLETED/improvement-loop/conversations/` —
  real transcripts are seed material for the initial ledger.

## Fingerprint design

Error fingerprint must be stable across small phrasing changes
but specific enough to not collide on unrelated errors.

```java
public static String fingerprint(String errorCode,
                                 String errorFragment,
                                 String macroPrefix) {
    String normCode = errorCode == null ? "" : errorCode;
    String normFrag = normaliseError(errorFragment);
    String normMacro = normaliseMacro(macroPrefix);
    return sha256Hex128(normCode + "\0" + normFrag + "\0" + normMacro);
}

static String normaliseError(String f) {
    // strip line numbers, file paths, image titles
    // collapse whitespace
    // lowercase
    // keep tokens that describe the failure class
    return f.replaceAll("line \\d+", "line ?")
            .replaceAll("\\d+", "?")
            .toLowerCase()
            .trim()
            .replaceAll("\\s+", " ");
}

static String normaliseMacro(String m) {
    // keep first two "run" calls; drop args
    Matcher runs = Pattern.compile("run\\s*\\(\\s*[\"']([^\"']+)[\"']").matcher(m);
    List<String> names = new ArrayList<>();
    while (runs.find() && names.size() < 2) names.add(runs.group(1));
    return String.join("|", names);
}
```

Normalisation throws away line numbers, image titles, and
specific arguments — exactly the parts that change between
instances of the same bug.

## Auto-attaching matches to errors

When `execute_macro` fails, before sending the reply:

```java
ErrorReply err = buildError(...);
String fp = fingerprint(err.code, err.message, macroCode);
List<LedgerEntry> hits = LedgerStore.get().lookupByPrefix(fp, macroCode, err.message);
for (LedgerEntry e : hits.subList(0, Math.min(3, hits.size()))) {
    err.suggested.add(toSuggestedBlock(e));
}
```

So the agent's reply includes:

```json
{"error": {
  "code": "MACRO_RUNTIME_ERROR",
  "message": "...",
  "suggested": [
    {"fromLedger": true,
     "confirmedFix": "Add run(\"Set Scale...\", ...) before Measure",
     "confidence": "high",
     "exampleMacro": "..."}
  ]
}}
```

Agent sees the fix immediately. No extra round-trip.

## Confidence tiers

- `high` — confirmed by ≥ 2 distinct agents, seen ≥ 5 times.
- `medium` — confirmed by ≥ 1 agent, seen ≥ 2 times.
- `low` — seen once or not yet confirmed.

Agent may (and should) weight its acceptance by confidence.
Low-confidence entries are suggestions, not prescriptions.

## Seed entries

Bootstrap the ledger from the existing improvement-loop
transcripts. One-time offline script walks
`docs/ollama_COMPLETED/improvement-loop/conversations/*.md`,
extracts confirmed-fix patterns (friction sections where a fix
worked), builds entries with `confirmedBy: ["gemma-31b"],
timesSeen: 1`. Ships with the first release.

## Capability gate

`caps.ledger` — default `true`. Turning off:
- Skips auto-attaching matches to errors
- Still allows explicit `ledger_lookup` / `ledger_confirm`

## Tests

- First run: ledger file doesn't exist → created on first
  `ledger_confirm`.
- `ledger_confirm` with new fingerprint → new entry.
- `ledger_confirm` with existing fingerprint → increments
  `timesSeen`, adds agent to `confirmedBy` if new.
- `execute_macro` that hits a known failure pattern → error
  reply has `suggested[]` entries from ledger.
- Concurrent writes from two sockets → no corruption (atomic
  temp-file write, lock on load+save).
- Ledger file deleted mid-session → next write recreates it
  from in-memory state.
- Fingerprint normalisation: two errors that differ only in
  line number collide (intended).

## Failure modes

- Ledger poisoning: one bad `ledger_confirm` with `worked: true`
  spreads a wrong fix. Mitigation: `worked: false` is also
  recorded; confidence downgrades when contradicted; entries
  with ratio of `true:false` confirmations below 2:1 emit as
  `low` confidence.
- Fingerprint overlaps on unrelated bugs. Audit the top-seen
  entries periodically for the first few months.
- File growth over years. Cap at 10,000 entries, LRU-evict
  oldest (by `lastSeen`). Reasonable for a desktop tool.
- User's home directory is read-only. Falls back to in-memory
  only; logs a warning at startup.
