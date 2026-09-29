# Privacy controls in ImageJAI

ImageJAI shows a privacy choice for each working folder. New folders use
**Standard**. Saved folder choices are retained, and the assistant and console
show the active choice.

## Choose a posture

| Posture | Effect |
| --- | --- |
| Standard | The agent can receive original filenames, metadata and permitted image captures. |
| Pseudonymised | Supported paths, image identifiers and metadata are replaced with tokens. Image captures are limited and GUI screenshots are restricted. |
| On-premises | Applies the supported pseudonymisation controls and restricts supported agent launch routes to local models. Cloud-hosted Ollama tags are refused. |

Choose the posture from the assistant's privacy controls or the console's
`/settings` Privacy tab. Use a local model when the data must stay on your
computer. A folder's saved setting takes precedence over the default.

## Working with pseudonymised images

Open images with Fiji's **File > Open**, drag and drop, or the assistant's
file browser. Refer to the current image or its displayed token instead of
pasting sensitive filenames into the conversation. The plugin resolves its
tokens locally so it can operate on the correct image.

Supported response filtering includes registered file paths, image titles,
selected metadata, results labels and registered sensitive strings in logs
and errors. The token mapping stays in the live plugin session. Image capture
restrictions and approved visual overrides depend on the selected posture.

## Records

The project audit trail is written to:

```text
AI_Exports/imagejai_audit.csv
```

It records command and governance events, the active posture and response
filtering details. The assistant can generate a Data Handling Statement PDF
under `AI_Exports/` describing its configured data flow and controls.

## Limits

These controls cover ImageJAI's supported launch routes and responses. They
do not anonymise the original images or govern every action of an external
agent. Text pasted into a chat, files read directly by another program and
tools outside ImageJAI may expose data independently. Pseudonymised image
pixels can still contain identifying content.

Provider access is configured separately. Cloud providers receive the content
sent to them and apply their own terms; local model servers must also be
configured appropriately for the working data.
