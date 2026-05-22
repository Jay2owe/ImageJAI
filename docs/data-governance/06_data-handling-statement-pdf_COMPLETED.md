# 06 — Data Handling Statement PDF generator

## Why this stage exists

This is the artefact that converts a sceptical supervisor or REC
reviewer in a single sitting. A printable, project-stamped PDF that
summarises vendor terms, pseudonymisation scheme, audit-trail
location, and opt-out path turns "we have some privacy stuff" into
"here is a one-page document I can paste into the ethics amendment."

Without this stage the rest of the work is a hidden engineering
investment that nobody outside the project sees. With it, it becomes
a tangible deliverable a PI can hand upward and an external lab can
evaluate ImageJAI by.

## Prerequisites

- Stage 04 (audit CSV being written; summary metadata reachable).
- Stage 02 (`CaptureHandler`, `VisualOverrideRegistry` exist; the
  PDF documents their behaviour).

## Read first

- `docs/data-governance/00_overview.md`.
- `pom.xml` — check whether a PDF library is already on the
  classpath. If none, add Apache PDFBox 3.x (Apache 2.0).
- Stage 04's `AuditLog` and `AuditRow`.
- Stage 02's `PseudonymisationFilter` for the pseudonymisation
  scheme description.
- Stage 07's `vendor_terms_summary.md` — coordinate vendor strings
  via the shared `VendorTermsRegistry` introduced here.

## Scope

`DataHandlingStatementGenerator` emits
`AI_Exports/DataHandlingStatement_<project>_<YYYYMMDD>.pdf`.
"Generate" button in the launcher panel (and the same button slot
in stage 05's Configuration Pane) calls it.

PDF structure (one or two A4 pages):

```
┌──────────────────────────────────────────────────────────────────┐
│  ImageJAI — Data Handling Statement                              │
│  Project: MOAB2_AF488                                            │
│  Generated: 2026-05-21    Posture in force: Pseudonymised        │
│  ImageJAI version: 0.X.Y                                         │
├──────────────────────────────────────────────────────────────────┤
│  1. Purpose                                                      │
│     This statement summarises the data-handling posture of the   │
│     ImageJAI plugin for the named project. Suitable for          │
│     Research Ethics Committee amendments and Data Management     │
│     Plans.                                                       │
│                                                                  │
│  2. Data flow                                                    │
│     • Images are read from <projectFolder> on the local machine. │
│     • The user selects files (and series-within-files) via the   │
│       Browse Files dialog or Fiji's File menu. Identifiable      │
│       filenames remain on the local machine.                     │
│     • The ImageJAI TCP server (localhost:7746) exposes commands  │
│       to the selected agent CLI.                                 │
│     • Outbound responses pass through the                        │
│       PseudonymisationFilter before reaching the agent process.  │
│     • The agent CLI communicates with the configured model       │
│       endpoint (see §4).                                         │
│                                                                  │
│  3. Pseudonymisation scheme (UK GDPR Art. 4(5))                  │
│     The following are tokenised before leaving the JVM in        │
│     Pseudonymised and On-premises postures:                      │
│       • File and folder paths (whole-file and series-within-     │
│         file)                                                    │
│       • OME-XML elements: Experimenter, Description,             │
│         StageLabel, AcquisitionDate, InstrumentRef,              │
│         InstrumentSerialNumber, Annotation                       │
│       • Results-table columns: Label, Slice (when non-numeric)   │
│       • Dialog and window titles                                 │
│       • Error and log messages                                   │
│                                                                  │
│     Image-pixel handling is differentiated by source:            │
│       • Microscopy image content: downsampled to ≤512×512 and    │
│         text burn-ins (Incucyte timestamps, scanner labels)      │
│         masked before transmission.                              │
│       • GUI / dialog / window screenshots: refused and replaced  │
│         with a hash placeholder.                                 │
│       • On-demand visual override: the agent may request         │
│         full-resolution pixel access for one call; the user      │
│         must explicitly grant; burn-in mask still applied; the   │
│         grant and consumption are logged in the audit trail.     │
│                                                                  │
│     The token map is held in JVM memory only and destroyed at    │
│     session end.                                                 │
│                                                                  │
│  4. Vendor contractual posture                                   │
│     Anthropic Claude API / Claude Code:                          │
│       "No training on API inputs/outputs; abuse logs retained    │
│       7 days." — anthropic.com/legal/commercial-terms            │
│     OpenAI API / Codex CLI:                                      │
│       "No training on business API data; 30-day abuse logs." —   │
│       openai.com/enterprise-privacy                              │
│     Google Gemini API (paid):                                    │
│       "No training; 55-day abuse logs." —                        │
│       cloud.google.com/gemini/docs/discover/data-governance      │
│     Google Gemini API / AI Studio (free tier):                   │
│       PROMPTS AND OUTPUTS ARE USED FOR TRAINING. NOT RECOMMENDED │
│       FOR RESEARCH DATA. — ai.google.dev/gemini-api/terms        │
│     Ollama (local):                                              │
│       No outbound network traffic. Recommended for Restricted    │
│       data.                                                      │
│     Ollama Cloud (*-cloud model tags):                           │
│       Routes inference to Ollama's US-hosted servers. Not a      │
│       local execution. — ollama.com/privacy                      │
│                                                                  │
│  5. Audit trail                                                  │
│     Location: AI_Exports/imagejai_audit.csv                      │
│     Rows in this project to date: <N>                            │
│     Date range: <first row timestamp> — <last row timestamp>     │
│     Of which:                                                    │
│       Pseudonymised calls: <X>                                   │
│       Visual override grants: <Y>                                │
│       Posture downshifts: <Z>                                    │
│                                                                  │
│  6. Opt-out and escalation                                       │
│     Set the Privacy Posture to On-premises via the folder        │
│     banner or the launcher Configuration Pane. In this mode the  │
│     agent dropdown is filtered to local-binary agents only,      │
│     *-cloud Ollama tags are refused, and the visual override is  │
│     unavailable.                                                 │
│                                                                  │
│  7. Limitations                                                  │
│     • Pseudonymisation is not anonymisation. The mapping is      │
│       reversible to anyone with live JVM access during a         │
│       session.                                                   │
│     • The pseudonymisation filter governs TCP responses. For     │
│       external CLIs (Claude Code in a separate terminal),        │
│       filenames typed directly into the agent chat are NOT       │
│       intercepted. The Browse Files dialog and the embedded      │
│       terminal's outbound prompt scrubber mitigate this.         │
│     • Burn-in text detection uses a fast heuristic catching      │
│       ~90% of cases; configurable per-format masks cover known   │
│       microscope vendors (Incucyte, Aperio).                     │
│     • This statement is generated automatically from posture     │
│       and audit metadata at the moment of generation.            │
│                                                                  │
│                                                                  │
│  Generated by ImageJAI v<version> — github.com/<repo>            │
└──────────────────────────────────────────────────────────────────┘
```

The exact wording IS the deliverable text. Implementers copy
verbatim.

## Out of scope

- Live PDF preview / editing.
- Digital signature / hash-stamping.
- Translations.

## Files touched

| Path | NEW/MODIFY | Reason |
|------|------------|--------|
| `src/main/java/imagejai/engine/security/DataHandlingStatementGenerator.java` | NEW | The PDF generator |
| `src/main/java/imagejai/engine/security/VendorTermsRegistry.java` | NEW | Shared with stage 07's docs |
| `src/main/java/imagejai/engine/AgentLauncher.java` | MODIFY | Wire the "Generate" button (slot from stage 05) |
| `pom.xml` | MODIFY | Add `org.apache.pdfbox:pdfbox:3.0.2` if no PDF lib present |
| `src/test/java/imagejai/engine/security/DataHandlingStatementGeneratorTest.java` | NEW | Substring + page-count tests |

## Implementation sketch

```java
public Path generate() throws IOException {
    Path out = projectFolder.resolve("AI_Exports")
        .resolve("DataHandlingStatement_" + projectFolder.getFileName()
                 + "_" + DateTimeFormatter.BASIC_ISO_DATE.format(LocalDate.now())
                 + ".pdf");
    try (PDDocument doc = new PDDocument()) {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        try (PDPageContentStream c = new PDPageContentStream(doc, page)) {
            writeHeader(c);
            writeSection1Purpose(c);
            writeSection2DataFlow(c, projectFolder);
            writeSection3Pseudonymisation(c);
            writeSection4VendorTerms(c, VendorTermsRegistry.ALL);
            writeSection5AuditSummary(c, auditLog.summaryFor(projectFolder));
            writeSection6OptOut(c);
            writeSection7Limitations(c);
            writeFooter(c);
        }
        doc.save(out.toFile());
    }
    return out;
}
```

`VendorTermsRegistry`:

```java
public static final List<VendorTerm> ALL = List.of(
    new VendorTerm("Anthropic Claude API / Claude Code",
        "No training on API inputs/outputs; abuse logs retained 7 days.",
        "https://www.anthropic.com/legal/commercial-terms"),
    new VendorTerm("OpenAI API / Codex CLI",
        "No training on business API data; 30-day abuse logs.",
        "https://openai.com/enterprise-privacy/"),
    new VendorTerm("Google Gemini API (paid)",
        "No training; 55-day abuse logs.",
        "https://cloud.google.com/gemini/docs/discover/data-governance"),
    new VendorTerm("Google Gemini API / AI Studio (free)",
        "PROMPTS AND OUTPUTS ARE USED FOR TRAINING. NOT RECOMMENDED FOR RESEARCH DATA.",
        "https://ai.google.dev/gemini-api/terms"),
    new VendorTerm("Ollama (local)",
        "No outbound network traffic. Recommended for Restricted data.", ""),
    new VendorTerm("Ollama Cloud (*-cloud model tags)",
        "Routes inference to Ollama's US-hosted servers. Not a local execution.",
        "https://ollama.com/privacy")
);
```

`AuditLog.summaryFor`:

```java
public record AuditSummary(int rowCount, int pseudonymised,
                           int visualOverrideGrants, int downshifts,
                           Instant first, Instant last) {}
```

## Exit gate

1. `mvn compile`, `mvn test` pass.
2. PDFBox on the classpath; `mvn package` jar is deployable
   without manual remediation.
3. `DataHandlingStatementGeneratorTest` asserts:
   - File created at the documented path.
   - Text contains: `Pseudonymisation`, `UK GDPR Art. 4(5)`,
     `On-premises`, `imagejai_audit.csv`, `Anthropic`, `OpenAI`,
     `Google Gemini`, `Ollama`, `series-within-file`,
     `visual override`.
   - Free-tier Gemini line contains uppercase warning.
   - Page count 1 or 2.
4. Manual: real project folder → click Generate → PDF looks like
   the mockup; no overlapping text.
5. Manual: fresh project, no audit history → §5 shows
   "Rows in this project to date: 0" without crashing.

## Known risks

- PDFBox does no auto-wrap; pre-wrap section text to a character
  width or implement a simple word-wrap helper.
- Heavyweight PDF dep could clash with Fiji's bundled libraries —
  test in a real Fiji install before merging.
- Vendor terms drift; `VendorTermsRegistry` and stage 07's
  `vendor_terms_summary.md` must be edited together (TODO comment
  in both files referencing the other).
