# Data Governance in ImageJAI

## What this is

ImageJAI applies a per-folder Privacy Posture to gate what data leaves
the JVM toward the agent CLI. The three postures are Standard,
Pseudonymised, and On-premises.

The goal is practical governance for microscopy work. The user keeps
the speed and flexibility of Claude Code, Codex, Gemini CLI, Aider, or
local Ollama agents, while ImageJAI makes the privacy choice visible,
records what happened, and applies pseudonymisation before responses
leave the plugin.

## Quick start

New folders default to Pseudonymised. For patient-derived,
participant-linked, embargoed, or otherwise restricted data, set the
folder to On-premises. For non-identifiable test images, training
images, or public sample data, Standard is available.

Use Fiji's File > Open command, the Browse Files dialog, or
drag-and-drop to open files. In Pseudonymised mode, do not paste real
filenames into the agent chat. Refer to "the current image" or to the
pseudonym shown by ImageJAI.

## The three postures

| Posture | What cloud agents see | Who picks this | Typical use |
|---|---|---|---|
| Standard | Original filenames, metadata, and image captures | User or supervisor | Public samples, demos, non-identifiable test data |
| Pseudonymised | Tokenised paths and metadata; microscopy pixels are downsampled and burn-in masked; GUI screenshots are refused | Default for new folders | Routine lab microscopy where filenames or metadata may carry study identifiers |
| On-premises | Same pseudonymisation as above, but cloud-hosted model endpoints are refused | Supervisor, PI, data steward, or user | Patient-derived work, clinical trial material, restricted consent, embargoed work |

## Recommended workflow in Pseudonymised mode

1. Open images via Fiji File > Open, the Browse Files dialog, or
   drag-and-drop. Do not paste filenames into the agent chat.
2. Refer to images by "the current image" or by their pseudonym, for
   example `image-7a3f.lif`.
3. For series-within-file selections, such as 8-week timepoints in a
   `.lif` file, use the Browse Files dialog. It lets you select
   multiple series locally and sends only pseudonyms plus a
   user-chosen tag to the agent.
4. If the agent needs full-resolution vision, for example to check
   focus or spot contamination, it can request `request_visual`. You
   approve each call. The grant is one-shot and logged.

## What "Pseudonymised" means here

ImageJAI follows the UK GDPR Art. 4(5) pseudonymisation framing:
identifiers are replaced with tokens, but the mapping can still be
reversed inside the live JVM session so the software can open the
right file and series.

The token map is held in JVM memory only. It is not written to the
audit CSV and is not serialised to disk.

In Pseudonymised and On-premises postures, ImageJAI tokenises file and
folder paths, file-like titles, OME-XML fields that commonly carry
people or instrument identifiers, results-table `Label` values, and
non-numeric `Slice` values. Free-text errors and logs are also
scrubbed for registered sensitive strings.

Microscopy pixels are treated differently from GUI screenshots.
Active image content may be sent after downsampling to at most
512 x 512 pixels and applying the burn-in mask. Dialog, window, and
desktop screenshots are refused because title bars and window chrome
often expose filenames, study codes, or user names.

## Audit trail

The audit log is written next to the project data:

```text
AI_Exports/imagejai_audit.csv
```

The CSV is append-only. It records one row per outbound TCP response
or governance event. The header is:

```text
timestamp_utc,session_id,command,posture,model_endpoint,capture_source,bytes_out,bytes_in,image_hash,redaction_applied,fields_redacted,notes
```

Important columns:

| Column | Meaning |
|---|---|
| `timestamp_utc` | When the response or governance event happened |
| `command` | TCP command or governance event, such as `capture_image`, `visual.granted`, or `posture.downshift` |
| `posture` | Standard, Pseudonymised, or On-premises |
| `model_endpoint` | Best-effort agent/vendor label |
| `capture_source` | Active image, dialog screenshot, window screenshot, or blank |
| `redaction_applied` | Whether pseudonymisation changed the outbound payload |
| `fields_redacted` | Semicolon-separated fields matching the `_governance` block in the JSON response |
| `notes` | Override reasons, token lists, refusal reasons, and other short governance details |

## Data Handling Statement PDF

The launcher Configuration Pane can generate a Data Handling
Statement PDF under `AI_Exports/`. It summarises the current Privacy
Posture, data flow, vendor terms, pseudonymisation scheme, audit log
location, and known limits. It is written for supervisors, Research
Ethics Committee amendments, grant applications, and Data Management
Plans.

## When to use On-premises

Use On-premises when the data should not be sent to a cloud-hosted
model endpoint. Examples include:

- Patient consent forms with no language covering cloud LLM use.
- Clinical trial data.
- Patient-derived organoids or tissue where filenames encode study
  identifiers.
- Embargoed pre-publication work.
- Any folder where the PI, REC, or information-governance contact has
  asked for local-only processing.

On-premises still shows the egress lamp when bytes leave the JVM
toward the local agent process. The promise is local execution, not
"no bytes ever leave ImageJAI".

## Limits of this protection

- Pseudonymisation is reversible inside a live session. This is
  intentional: ImageJAI needs to resolve `image-7a3f.lif:4` back to
  the real file and Bio-Formats series locally.
- External CLIs, such as Claude Code in a separate terminal, bypass
  the embedded terminal's outbound prompt scrubber. Users must not
  paste real filenames there. Use the Browse Files dialog instead.
- Burn-in detection is a fast border heuristic. It catches most
  common microscope burn-ins, but it is not OCR and it is not a
  guarantee that every text label is found.
- The filter governs ImageJAI TCP responses. Manual Fiji GUI actions
  and user-managed external scripts remain the user's responsibility.

## For PIs and supervisors

To verify the posture, check the posture badge in the launcher or open
the Data Governance Configuration Pane. For a written record, open
`AI_Exports/imagejai_audit.csv` and generate the Data Handling
Statement PDF. For a sensitive `.lif`, set the folder to On-premises
before launching an agent, then use Browse Files to select the file
and series locally.
