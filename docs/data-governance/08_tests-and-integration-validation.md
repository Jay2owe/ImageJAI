# 08 — Tests + integration validation

## Why this stage exists

The credibility of the whole Data Governance layer rests on the
claim that "in Pseudonymised mode, no identifiable string leaves
the JVM (except per the documented, audited exceptions)." Stage 02
covers the filter in isolation; this stage proves the claim
end-to-end across all mechanisms (filter, brief broker, visual
override, prompt scrubber, capture handler) and produces a
re-runnable manual checklist.

Without this stage, the user-facing copy ("UK GDPR Art. 4(5)
pseudonymisation", "audit trail of every outbound call") is a claim
we cannot defend if challenged. With it, the claim is backed by an
automated proof and a documented manual sweep.

## Prerequisites

- Stages 02 (filter), 04 (audit log), 05 (visible UI), 06 (PDF),
  09 (file browser + brief broker) all completed.

## Read first

- All previous stage files, especially exit gates.
- `src/main/java/imagejai/engine/security/PseudonymisationFilter.java`
  and its unit tests from stage 02.
- `src/main/java/imagejai/engine/TCPCommandServer.java` — for the
  integration harness.
- `agent/ij.py` — the Python client used in the integration test.

## Scope

### 8.1 Exhaustive per-command filter tests (extend stage 02)

Stage 02 covered representative commands. This stage exhaustively
covers every TCP command that can return a path, OME-XML, pixels,
a results-table row, dialog text, or log output. Discover the
command list at test time (`TCPCommandServer.knownCommands()` or a
shared canonical list) so any new command without a redaction case
fails the test rather than silently slipping through.

Each command gets:
- Pseudonymised case: identifiable input, assert no original
  string survives in the outbound bytes.
- Standard case: identical-bytes passthrough.

### 8.2 Outbound-string proof (the integration test)

Single integration test:

```
1. Spin up TCPCommandServer on an ephemeral port.
2. Set posture = PSEUDONYMISED.
3. Open synthetic image with identifiable path:
   /MOAB2/subject_017_visit3.lif
4. Write deliberately identifiable OME-XML PHI fields.
5. Connect Python client (agent/ij.py).
6. Run scripted sequence touching EVERY TCP command (including
   capture_image with each CaptureSource, request_visual flow,
   browse_pending_brief / get_pending_brief / open_image_by_token).
7. Capture every byte the server sent on the socket.
8. Assert NONE of:
     - "MOAB2", "subject_017", "visit3"
     - the absolute parent path
     - any OME-XML PHI value written
     - any Label value
9. Assert every response carries a "_governance" block.
10. Assert audit CSV gained exactly N rows; redaction_applied=true
    on every TCP-data row.
```

This is the load-bearing artefact for the README quote.

### 8.3 Differentiated capture tests

Specific to stage 02's `CaptureHandler`:

- `capture_image` with `source=active_image_content` in
  Pseudonymised → outbound bytes contain a base64 PNG, but
  decoded image is ≤512×512 and no detected text remains in
  border regions.
- `capture_image` with `source=dialog_screenshot` in Pseudonymised
  → outbound contains placeholder hash, no `image_base64`.
- `request_visual` granted → next `capture_image` of active
  image returns full-resolution PNG (burn-in mask still applied).
- Second `capture_image` after grant without re-grant → back to
  downsampled.
- `request_visual` in On-premises → refused with documented error.

### 8.4 Outbound prompt scrubber tests

- Embedded-PTY: user types `"analyse MOAB2_subject_017"` where
  `MOAB2_subject_017.lif` is in `PathTokenMap` → keystrokes sent
  to PTY contain the token, not the original. Toast fired.
- Multi-token replacement (longest-first): no partial matches.
- Non-matching prompt: pass-through, no toast.

### 8.5 Browse Files / brief broker tests

Specific to stage 09:

- Select 3 series in the dialog, click Send → `SelectionBroker`
  gains brief; `browse_pending_brief` returns `pending: true`;
  `get_pending_brief` returns tokens + tag; tokens reverse-
  resolve to the correct files in `open_image_by_token`.
- External-CLI path: clipboard receives one-line nudge.
- Embedded-PTY path: nudge text typed into terminal.

### 8.6 Negative / honest-claim tests

Tests that fail the over-broad claims, so contributors don't
accidentally promote them:

- Macro `print()` of an unregistered original path → string
  appears in `get_log` (documents limitation).
- `PathTokenMap` is reversible inside the JVM (documents that
  this is pseudonymisation, not anonymisation).
- Egress lamp flashes in On-premises mode (documents the honest
  tooltip language).

These exist to prevent future copy-editors from promoting README
wording beyond what the filter delivers.

### 8.7 Manual UI checklist

`docs/data-governance/manual_test_checklist.md`:

```
[ ] Fresh install: open a folder with no .imagejai-posture.json →
    banner appears, default Pseudonymised.
[ ] Apply on banner → .imagejai-posture.json created.
[ ] Re-open folder → banner does NOT appear; posture loaded.
[ ] Posture badge colour and label match folder posture.
[ ] In Pseudonymised, run get_state → egress lamp blinks, Receipts
    row appears, [show] reveals redacted payload only.
[ ] Configuration Pane expander shows current posture, audit-log
    path, session call counts, Generate button.
[ ] "View Audit Log" opens CSV in OS default app.
[ ] "Generate Data Handling Statement" produces a PDF with all 7
    sections.
[ ] Set posture to On-premises → dropdown shows only local
    binaries.
[ ] Launch Ollama with gemma4:31b-cloud in On-premises → error
    dialog with documented refusal.
[ ] Open second folder marked On-premises while in Standard →
    downshift banner; badge updates.
[ ] Open Standard folder while On-premises → NO upshift.
[ ] Click Override (logged) on downshift banner → audit row with
    free-text reason.
[ ] Open Browse Files dialog → series of a .lif file populate;
    search "8w wt" filters correctly.
[ ] Select 3 series, click Send → external CLI: clipboard toast.
    Embedded PTY: text auto-typed.
[ ] Agent receives brief, opens each token via
    open_image_by_token → images appear in Fiji.
[ ] capture_image of active microscopy image: lamp blinks, base64
    appears in receipt but ≤512px.
[ ] capture_image of a dialog: receipt shows placeholder, no
    image bytes.
[ ] request_visual flow: agent calls, user sees toast, clicks
    Allow → next capture full-res; subsequent capture back to
    downsampled.
[ ] Type a sensitive name into the embedded PTY → toast appears,
    underlying bytes contain the token.
[ ] Audit CSV header line matches the documented column order
    exactly.
[ ] PDF §4 free-tier Gemini line shows the uppercase warning.
[ ] PDF §3 mentions series-within-file and differentiated capture.
[ ] PDF §7 mentions external-CLI typed-filename limitation.
```

## Out of scope

- Performance benchmarking (nice-to-have, not blocking release).
- Cross-platform CI matrix.
- TCP server pen-testing.

## Files touched

| Path | NEW/MODIFY | Reason |
|------|------------|--------|
| `src/test/java/imagejai/engine/security/PseudonymisationFilterExhaustiveTest.java` | NEW | One test per TCP command |
| `src/test/java/imagejai/engine/security/PseudonymisationIntegrationTest.java` | NEW | Outbound-string proof |
| `src/test/java/imagejai/engine/security/CaptureHandlerTest.java` | NEW | Differentiated capture cases |
| `src/test/java/imagejai/engine/security/OutboundPromptScrubberTest.java` | NEW | PTY scrubber cases |
| `src/test/java/imagejai/engine/security/BrowseBriefIntegrationTest.java` | NEW | Browse / brief / reverse-resolve flow |
| `src/test/java/imagejai/engine/security/NegativeClaimsTest.java` | NEW | Limitations as tests |
| `docs/data-governance/manual_test_checklist.md` | NEW | Re-runnable UI checklist |

## Exit gate

1. `mvn test -Pintegration` runs all classes; all pass on the
   developer's machine. Default `mvn test` runs unit tests only.
2. Exhaustive test discovers TCP commands at runtime; adding a
   new command without a redaction case fails the test.
3. Manual checklist completed once on a real Fiji install; the
   executing agent saves a ticked copy as
   `docs/data-governance/manual_test_run_<date>.md`.
4. Negative tests pass in the direction that *documents* the
   limitation, not in the direction that promotes a stronger
   claim.
5. PDF generation tested with non-zero audit history.

## Known risks

- Integration test is slow (TCP startup, every command).
  `@Tag("integration")`; document profile in Javadoc.
- "List all TCP commands at test time" must not use private-state
  reflection. Expose `TCPCommandServer.knownCommands()` or share
  a canonical list with `agent/CLAUDE.md`.
- A future TCP command returning binary in a non-`image_base64`
  field would silently pass — documented as a blind spot in
  negative tests; periodic dispatcher audit on the maintainer.
- This stage inherits any debt from earlier stages. Push fixes
  back to the offending stage rather than papering over here.
