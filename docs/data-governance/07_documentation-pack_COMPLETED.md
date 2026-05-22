# 07 — Documentation pack

## Why this stage exists

Engineering work that nobody reads is engineering work that nobody
gets credit for. The documentation pack lets a future PhD student,
incoming supervisor, or REC reviewer understand the posture model
without reading Java. It is also the source of truth that the Data
Handling Statement PDF (stage 06) summarises.

The DPIA template is the single artefact most likely to be reused by
other groups in the lab and across UK DRI — it converts ImageJAI's
internal design into a portable governance pattern.

`agent/CLAUDE.md` updates are load-bearing too: the agent has to
know about the differentiated capture handler, the `request_visual`
override, the brief-polling rule, and the recommended file-opening
workflow. If those instructions aren't in front of the agent, the
visible UI signals don't get used.

## Prerequisites

- Stage 06 (`VendorTermsRegistry` is the source of truth for vendor
  language).
- Stage 02 (filter mechanisms documented in the redactor reference).
- Stage 09 (brief polling rule referenced in `agent/CLAUDE.md`).

## Read first

- All prior stage files in `docs/data-governance/`.
- Root `README.md` — locate where the "Data Governance" section
  goes.
- `agent/CLAUDE.md` — plan the redaction-aware additions.
- `src/main/java/imagejai/engine/security/VendorTermsRegistry.java`
  from stage 06.
- Existing `docs/safe_mode/` and `docs/safe_mode_v2/` — for tone
  reference; don't duplicate.

## Scope

### 7.1 `docs/data-governance/README.md`

Plain-English overview for biologists and supervisors. Sections:

```
# Data Governance in ImageJAI

## What this is
ImageJAI applies a per-folder Privacy Posture (Standard /
Pseudonymised / On-premises) to gate what data leaves the JVM
toward the agent CLI.

## Quick start
New folders default to Pseudonymised. For patient-derived or
otherwise restricted data, set the folder to On-premises. For
non-identifiable test images, switch to Standard.

## The three postures
Table: posture | what cloud agents see | who picks this | typical use

## Recommended workflow in Pseudonymised mode
1. Open images via Fiji File → Open, the Browse Files dialog,
   or drag-and-drop. (Do NOT paste filenames into the agent chat.)
2. Refer to images by "the current image" or by their pseudonym.
3. For series-within-file selections (8-week timepoints in a .lif,
   etc.) use the Browse Files dialog — it lets you select multiple
   series locally and sends only pseudonyms + a user-chosen tag to
   the agent.
4. If the agent needs full-resolution vision (e.g. to spot
   contamination), it will request `request_visual`. You approve
   per-call; the grant is logged.

## What "Pseudonymised" means here
Plain-language explanation of the filter: which fields are
tokenised, which pass through, how microscopy pixels are handled
(downsampled + burn-in masked), how GUI screenshots are handled
(refused). UK GDPR Art. 4(5) framing.

## Audit trail
Pointer to AI_Exports/imagejai_audit.csv and what each column means.

## Data Handling Statement PDF
How to generate one and what it is for.

## When to use On-premises
Patient consent forms with no LLM language, embargoed
pre-publication work, clinical trial data, etc.

## Limits of this protection
- Pseudonymisation is reversible inside a live session
  (intentional — required for the agent to work).
- External CLIs (Claude Code in a separate terminal) bypass the
  outbound prompt scrubber; users must not paste filenames there.
  The Browse Files dialog is the recommended substitute.
- Burn-in detection catches ~90% of cases; configurable masks
  cover known microscope vendors.

## For PIs and supervisors
How to verify the posture is in force, where the audit log lives,
where the Data Handling Statement PDF lands.
```

### 7.2 `docs/data-governance/DPIA_template.md`

Drop-in template for UK university DPIA workflows. Sections:

```
# Data Protection Impact Assessment — ImageJAI use

## 1. Project description
[fill]

## 2. Nature of personal data processed
This project processes pseudonymised microscopy images. File paths,
OME-XML metadata fields, results-table Label columns, and dialog
identifiers may constitute personal data under UK GDPR Art. 4(1)
where they contain participant identifiers.

## 3. Necessity and proportionality
[fill]

## 4. Data flow
ImageJAI plugin (local) → TCP server (localhost:7746) →
PseudonymisationFilter (in-JVM) → Agent CLI → Vendor model endpoint.
The PseudonymisationFilter tokenises identifiable strings and
applies differentiated handling to image pixels before transmission.

## 5. Vendor contractual posture
[reference to vendor_terms_summary.md or paste row(s)]

## 6. Privacy Posture in force
[fill]   Justification: [fill]

## 7. Risks and mitigations
| Risk | Likelihood | Severity | Mitigation |
| File path leakage | Low | Medium | PseudonymisationFilter |
| OME-XML PHI leakage | Low | Medium | OmeXmlScrubber |
| Pixel data leakage | Low | High | Differentiated CaptureHandler; downsample + burn-in mask; visual override gated and audited |
| GUI screenshot leakage | Very Low | High | DIALOG/WINDOW captures refused |
| User-typed filename in external CLI | Medium | Medium | Browse Files dialog; embedded PTY scrubber; documented discipline |

## 8. Audit and accountability
Audit log at AI_Exports/imagejai_audit.csv (append-only). Data
Handling Statement PDF generated per project.

## 9. Approval
[fill: REC reference, date, signature]
```

### 7.3 `docs/data-governance/vendor_terms_summary.md`

Markdown table mirroring `VendorTermsRegistry.ALL` from stage 06.
Top of file: `Last verified: YYYY-MM-DD`. Line near the bottom:
*"This table and `src/main/java/.../VendorTermsRegistry.java` are
edited together."*

### 7.4 `docs/data-governance/redactor_reference.md`

Technical reference for developers extending the filter: what gets
tokenised, how to add a new path-typed field, how to extend
`OmeXmlScrubber`, the `CaptureSource` taxonomy, how
`request_visual` works, how to add a new burn-in mask, the test
patterns from stage 02.

### 7.5 Root `README.md` — new "Data Governance" section

A ~150-word section after the project description. Points to
`docs/data-governance/README.md`. Includes:

> *"ImageJAI applies UK GDPR Art. 4(5) pseudonymisation to all
> outbound responses by default, differentiates microscopy pixels
> (downsampled, burn-in masked) from GUI screenshots (refused),
> refuses cloud-hosted model endpoints in On-premises posture, and
> emits an append-only audit trail per project."*

### 7.6 `agent/CLAUDE.md` — redaction-aware additions

Add a section explaining:

- Depending on posture, the agent sees tokenised paths
  (`image-7a3f.lif`, `image-7a3f.lif:4`) rather than originals.
  Refer to images by these tokens, not real names.
- `capture_image` of an active microscopy image is downsampled in
  Pseudonymised mode; if full resolution is needed for a specific
  purpose, ask the user via `request_visual` with a one-sentence
  reason. Don't request it speculatively.
- `capture_image` of dialogs / windows is refused. Use
  `get_dialogs` and `interact_dialog` instead.
- At the start of each turn, call `browse_pending_brief`. If
  `pending: true`, call `get_pending_brief` and use the user's
  selection.
- Prefer the `pixels.py` numerical path for image analysis; reserve
  vision for genuine triage / contamination spotting.
- If the user types a real filename, gently steer them to the
  Browse Files dialog: *"Could you select that file via Browse
  Files? Then I'll pick it up via the brief."*

## Out of scope

- Translations.
- Marketing collateral beyond the README quote.
- Slack / Discord announcement copy.

## Files touched

| Path | NEW/MODIFY | Reason |
|------|------------|--------|
| `docs/data-governance/README.md` | NEW | User-facing overview |
| `docs/data-governance/DPIA_template.md` | NEW | Drop-in template |
| `docs/data-governance/vendor_terms_summary.md` | NEW | Vendor table |
| `docs/data-governance/redactor_reference.md` | NEW | Developer reference |
| `README.md` (root) | MODIFY | "Data Governance" section + quote |
| `agent/CLAUDE.md` | MODIFY | Redaction-aware + brief-polling guidance |

## Exit gate

1. All five new / modified documents render correctly in GitHub
   markdown.
2. Vendor table in `vendor_terms_summary.md` matches
   `VendorTermsRegistry.ALL` byte-for-byte on summary strings.
3. Root `README.md` Data Governance section ≤150 words.
4. `agent/CLAUDE.md` includes: tokenised paths, differentiated
   capture, `request_visual` etiquette, `browse_pending_brief`
   polling, gentle-steer language.
5. Manual: a colleague unfamiliar with the project reads the
   README and can answer: default posture, audit log location,
   difference between Pseudonymised and On-premises, what to do
   for a sensitive .lif. (If unavailable: re-read your own draft
   cold and self-check.)

## Known risks

- Tone calibration. README for biologists; DPIA for ethics
  officers; redactor reference for developers. Different register
  for each.
- Drift. Vendor terms change. `Last verified:` date + paired-edit
  TODO comment in registry.
- Marketing creep. Don't claim "anonymisation"; don't claim
  "end-to-end encryption". Match what the filter actually does.
