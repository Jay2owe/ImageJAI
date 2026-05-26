# Data Protection Impact Assessment - ImageJAI use

This template is written for UK university DPIA workflows. Replace the
bracketed text with project-specific details and attach the generated
ImageJAI Data Handling Statement PDF where appropriate.

## 1. Project description

[Fill: project title, lab, PI or supervisor, data steward, funder,
study identifiers, and the microscopy analysis being performed.]

## 2. Nature of personal data processed

This project processes pseudonymised microscopy images. File paths,
OME-XML metadata fields, results-table `Label` columns, and dialog
identifiers may constitute personal data under UK GDPR Art. 4(1)
where they contain participant identifiers.

The local JVM keeps the pseudonymisation token map during the live
session so the plugin can resolve image tokens back to real files and
Bio-Formats series when needed.

## 3. Necessity and proportionality

[Fill: why AI-assisted Fiji analysis is needed, why the selected
agent/model is proportionate, what non-AI alternatives were
considered, and how human supervision is maintained.]

## 4. Data flow

```text
ImageJAI plugin (local)
  -> TCP server (localhost:7746)
  -> PseudonymisationFilter (in-JVM)
  -> Agent CLI
  -> Vendor model endpoint
```

The PseudonymisationFilter tokenises identifiable strings and applies
differentiated handling to image pixels before transmission. Active
microscopy image content is downsampled and burn-in masked in
Pseudonymised mode. GUI, dialog, window, and desktop screenshots are
refused.

## 5. Vendor contractual posture

[Fill: paste the relevant row or rows from
`docs/data-governance/vendor_terms_summary.md`. Note whether the
chosen endpoint is cloud-hosted or local.]

## 6. Privacy Posture in force

Privacy Posture: [Standard / Pseudonymised / On-premises]

Justification: [Fill: why this posture is suitable for the dataset,
consent language, endpoint, and analysis need.]

## 7. Risks and mitigations

| Risk | Likelihood | Severity | Mitigation |
|---|---|---|---|
| File path leakage | Low | Medium | PseudonymisationFilter tokenises path-typed fields and path-like substrings |
| OME-XML PHI leakage | Low | Medium | OmeXmlScrubber removes Experimenter, Description, StageLabel, AcquisitionDate, InstrumentRef, InstrumentSerialNumber, and Annotation elements |
| Pixel data leakage | Low | High | Differentiated CaptureHandler; active image content is downsampled and burn-in masked; visual override is gated and audited |
| GUI screenshot leakage | Very Low | High | DIALOG, WINDOW, and DESKTOP captures are refused |
| User-typed filename in external CLI | Medium | Medium | Browse Files dialog; embedded PTY scrubber; documented user discipline |
| Cloud endpoint used for restricted data | Low | High | On-premises posture filters the launcher to local-binary agents and refuses cloud-hosted Ollama tags |
| Redaction failure | Very Low | High | Pseudonymised and On-premises postures fail closed and drop the original response |

## 8. Audit and accountability

ImageJAI writes an append-only audit trail at:

```text
AI_Exports/imagejai_audit.csv
```

The CSV records the timestamp, session id, command, Privacy Posture,
model endpoint, capture source, byte counts, image hash, whether
redaction was applied, fields redacted, and short governance notes.

Generate a Data Handling Statement PDF from the launcher
Configuration Pane for project records, REC amendments, and Data
Management Plans.

## 9. Approval

REC reference: [fill]

Information-governance contact: [fill]

Approved Privacy Posture: [fill]

Conditions or restrictions: [fill]

Date: [fill]

Signature: [fill]
