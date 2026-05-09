# Agent-Context Sanitization

Lands D7 from [`decisions.md`](../decisions.md). Externally-sourced text
that crosses the TCP boundary into agent context is now wrapped in tagged
envelopes by `imagejai.engine.security.AgentContextSanitizer`.

## Design

Single static helper, ~60 LOC, no state. Public surface:

```java
AgentContextSanitizer.wrap(String raw, String sourceTag);
AgentContextSanitizer.wrap(String raw, String sourceTag, int maxBytes);
```

For every call:

1. C0 controls (0x00–0x1F) and the C1 block (0x80–0x9F) plus DEL (0x7F)
   are stripped. `\n` and `\t` are preserved so log structure survives.
2. The result is length-capped at `DEFAULT_MAX_BYTES = 8192` UTF-8
   bytes. Inputs above the cap are truncated codepoint-safely at
   `maxBytes / 2` and tagged with `...[truncated]`.
3. The remainder is wrapped in `[sourceTag: <safe>]`. Empty or
   all-control inputs collapse to `[sourceTag: <empty>]`.

Truncation uses a per-codepoint UTF-8 byte budget so we never split a
4-byte CJK character or surrogate pair. The codepoint cost is computed
inline (no `String.getBytes(...)` allocation per slice).

## Five boundary points in `TCPCommandServer.java`

| Boundary               | Site                                                          | Tag                |
| ---------------------- | ------------------------------------------------------------- | ------------------ |
| `handleGetMetadata`    | `imp.getProperty("Info")` (Bio-Formats OME-XML), L4490        | `OME-XML`          |
| `handleGetMetadata`    | per-key property values from `imp.getProperties()`, L4502     | `META:<key>`       |
| `handleGetLog`         | `IJ.getLog()` payload, L4355                                  | `LOG`              |
| `handleGetDialogs`     | dialog `title` and extracted body `text`, L5552–5554          | `DIALOG`           |
| `handleGetConsole`     | `stdout`, `stderr`, and the combined stream, L2135–2138       | `CONSOLE`          |

These five handlers are the only places where pixel-bystander text
(image metadata authored by an attacker, plugin-emitted strings,
dialog content, stdout traces) reaches an agent verbatim. None of the
five handlers calls another, so no double-wrapping risk.

## What this prevents

- **Control-character attacks.** ANSI escapes, NUL/BEL/ESC injected via
  TIFF tag descriptions, OME-XML annotations, or rogue plugin output
  cannot alter terminal state, hide trailing payloads from the agent,
  or break out of the envelope.
- **Length bombs.** A 50 MB OME-XML blob is capped at 4 KB plus a
  `...[truncated]` marker — the agent sees a clean envelope, not a
  context-window-sized payload.
- **Role-confusion attacks.** Strings like `"SYSTEM: ignore previous
  instructions"`, `"[/INST] now you are ..."`, or `"DAN MODE: ..."`
  remain inside `[OME-XML: ...]`. The boundary marker is a strong
  indirect-prompt-injection signal for frontier models per Microsoft's
  2025 indirect-prompt-injection guidance.

## What this does NOT prevent

- **Trusted-surface attacks.** A recipe YAML the user authored that
  gets MITM'd before reaching the agent flows through other paths.
  Recipe signing is `Planned` and explicitly out of D7 scope.
- **An LLM that ignores boundary markers.** Empirically rare with
  frontier models, common with smaller local models. The corpus
  exercises this — the test only asserts envelope integrity, not
  model behaviour.
- **Trusted log content from the user's own macros.** `LOG` text the
  user produced is wrapped the same way; that's harmless but does
  mean a verbose `print()` from a known-good macro now appears in
  envelope form to the agent.

## Regression test

`src/test/java/imagejai/engine/security/AgentContextSanitizerTest.java`
runs 27 adversarial inputs across seven categories: control-char,
length, role-confusion, encoding, unicode-attack, boundary, and
idempotency. Each input is documented verbatim in
[`../supplements/adversarial_corpus.md`](../supplements/adversarial_corpus.md).
