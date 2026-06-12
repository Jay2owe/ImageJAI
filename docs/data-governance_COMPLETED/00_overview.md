# Data Governance for ImageJAI

## End goal

ImageJAI grows a visible, defensible **Data Governance** layer that
preserves the plugin's key advantage — agent-agnostic CLI integration
(Claude Code, Aider, Codex, Gemini CLI, Ollama-driven Gemma) — while
giving the user, their supervisor, and a Research Ethics Committee
reviewer a credible posture they can point at.

The user picks a **Privacy Posture** per folder. Outbound TCP
responses flow through a `PseudonymisationFilter` that tokenises
identifiable strings (GDPR Art. 4(5)). A **Browse Files** dialog lets
the user pre-select images (and series-within-files) locally; the
agent receives only pseudonyms plus a user-chosen tag. Vision is
**differentiated**: microscopy pixels flow at reduced resolution,
GUI/dialog screenshots are refused, and burn-in text on microscope
frames is masked. An on-demand `request_visual` override restores
full pixel access for one call with explicit user consent and an
audit row.

This is not a maximum-lockdown system. It is a polished,
institutional-feeling layer with real (but pragmatic) protection
underneath — engineered so the CLI advantage is enhanced, not eroded.

## Why we're doing this

The supervisor of this project raised the concern that researchers
using ImageJAI route data through commercial AI providers via the
agent CLIs. The lab handles pseudonymised data (MOAB2 amyloid stacks,
SCN tissue, occasional patient-derived organoid work) where filenames
and OME-XML metadata fields are themselves personal data under UK
GDPR.

Today every TCP command response is sent verbatim to whichever LLM is
driving the session. Filenames containing participant identifiers,
dialog screenshots with study codes, and Bio-Formats verbose dumps
all flow outbound with no governance.

The CLI-agnostic design is also ImageJAI's primary differentiator
from embedded-LLM Fiji plugins. Any governance layer must work for
every CLI — Claude Code today, local Gemma tomorrow — without locking
the user into one vendor. Every mechanism here is agent-agnostic by
construction.

## Architecture overview

```
  Fiji plugin GUI                        Agent CLI (any)
       │                                      ▲
       │ folder-open                          │
       ▼                                      │
  FolderPostureStore  ◄── .imagejai-posture.json
       │                                      │
       ▼                                      │
  Settings.privacyPosture                     │
       │                                      │
       ├─► AgentLauncher (filters dropdown)   │
       │                                      │
       │   [Browse Files…]                    │
       │        │                             │
       │        ▼                             │
       │   SelectionBroker ◄── tag, tokens    │
       │        │                             │
       │        ▼                             │
       │   browse_pending_brief / get_pending_brief
       │                                      │
  TCPCommandServer.dispatch                   │
       │                                      │
       ├──► PseudonymisationFilter ──► socket │
       │       │                              │
       │       ├─ PathTokenMap (incl. series) │
       │       ├─ OmeXmlScrubber              │
       │       ├─ CaptureHandler              │
       │       │    ├─ ACTIVE_IMAGE → downsample + burn-in mask
       │       │    ├─ DIALOG / WINDOW → refused
       │       │    └─ request_visual override (one-shot, audited)
       │       └─ FreeTextScrub                │
       │                                      │
       └──► AuditLog ──► AI_Exports/imagejai_audit.csv

  Embedded PTY (optional): OutboundPromptScrubber
                            same PathTokenMap
                            warns/replaces typed identifiers
```

Two existing concerns stay independent:

- **Existing `safeModeEnabled`** in `Settings.java` — destructive-action
  prevention. NOT touched. Orthogonal threat model.
- **Existing `AgentContextSanitizer`** —  prompt-injection mitigation
  on inbound text. The new `PseudonymisationFilter` is its outbound
  counterpart.

## Stage map

| NN | Name | Goal | Size | Depends on |
|----|------|------|------|------------|
| 01 | settings-and-posture-infrastructure | `PrivacyPosture` enum + per-folder `.imagejai-posture.json` + folder-open banner + auto-downshift | M | none |
| 02 | pseudonymisation-filter | `PseudonymisationFilter` (path + series tokens, OME-XML scrub, differentiated `CaptureHandler` w/ burn-in detection, `request_visual` override, outbound prompt scrubber, `_governance` self-describing block, `open_image_by_token`) + tests | XL | 01 |
| 03 | launcher-allowlist-and-badge | Filter `KNOWN_AGENTS` by posture, refuse `*-cloud` Ollama tags, coloured posture badge, Browse Files button placement | M | 01 |
| 04 | audit-log | `AI_Exports/imagejai_audit.csv` writer + "View Audit Log" button + new row types | S | 02 |
| 05 | visible-trust-signals | Configuration Pane, egress lamp, Receipts pane, prompt-scrubber toast, governance vocabulary tooltips | M | 03, 04 |
| 06 | data-handling-statement-pdf | PDF generator + "Generate Data Handling Statement" button | M | 04 |
| 07 | documentation-pack | `docs/data-governance/` user docs + DPIA template + vendor terms summary + `agent/CLAUDE.md` updates | S | 06 |
| 08 | tests-and-integration-validation | Exhaustive redactor tests + outbound-string proof + differentiated-capture tests + manual UI checklist | M | 02, 05, 06 |
| 09 | pseudonymised-file-browser | Browse Files dialog + Bio-Formats series scanner + `SelectionBroker` + brief TCP commands + clipboard/PTY nudge + tag suggestion engine | L | 02 |

Sizes: S ≈ half a day, M ≈ one day, L ≈ 1–2 days, XL ≈ 2–3 days.

Stage 09 may run in parallel with stages 04–08 once stage 02 lands.

## House rules (every stage must respect)

- `safeModeEnabled` and `privacyPosture` are independent — neither
  toggle affects the other.
- All outputs land in `AI_Exports/` next to the opened image.
- The token map for pseudonymisation is **in-JVM only** — never
  serialise it to disk, never log original strings into the audit
  CSV in a recoverable way.
- Use **"pseudonymisation"** (not "anonymisation") in user-facing
  text. The filter implements pseudonymisation per UK GDPR Art.
  4(5).
- Use **"Privacy Posture"** as the user-facing name for the
  per-folder setting and **"Data Governance"** as the umbrella
  section name.
- **Fail-closed in Pseudonymised / On-premises modes.** If any
  redaction step errors, refuse to send the response. Fail-open is
  allowed only in Standard mode.
- **Agent-agnostic.** No mechanism may depend on a specific CLI
  (Claude Code hooks, Gemini SDK, etc.). Everything uses TCP
  commands, the clipboard, or the embedded PTY.
- There is a known `Settings (Jamie Malcolm's conflicted copy
  2026-05-06).java` in `src/main/java/imagejai/config/` — IGNORE
  it. Edit only `Settings.java`.

## Known open questions

- Exact line numbers for the dispatch hook in `TCPCommandServer.java`
  need to be located by the executing agent — use Grep for
  `dispatchCore` / `dispatchInternal` rather than trusting numbers.
- Folder-open trigger location is most likely in `ImageMonitor.java`
  or a Fiji `ImageListener`. Stage 01 must locate this.
- PDF library choice (PDFBox vs OpenPDF) — pick whichever is closer
  to the existing Maven classpath in stage 06.
- Burn-in OCR — cheap heuristic alone vs Tesseract dependency. Stage
  02 ships the heuristic; Tesseract can be added later as a stage
  follow-up if false negatives are seen in practice.

## How to run a stage

```
/do-step docs/data-governance/
```

The lowest-numbered `NN_*.md` file without a `_COMPLETED` suffix is
executed, then renamed to `NN_*_COMPLETED.md` after a passing commit.
