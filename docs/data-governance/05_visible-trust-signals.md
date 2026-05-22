# 05 — Visible trust signals + Configuration Pane

## Why this stage exists

The protection in stages 02–04 is real but invisible. Stage 05 is
the user-facing surface that makes the work *legible* — to the
biologist using the tool, the supervisor walking past their monitor,
and the colleague evaluating ImageJAI for their own lab.

Trust signals should be ambient (always visible, never interruptive);
the guiding principle is *"the webcam LED, not the seatbelt chime."*
The Configuration Pane is the showcase surface — a single
"Data Governance ▾" expander shows the supervisor everything they
need to be convinced in 30 seconds.

## Prerequisites

- Stage 03 (`PostureBadge`, button slots in launcher panel).
- Stage 04 (audit rows exist, `AuditLog.recent(n)` available).
- Stage 02 (`OutboundPromptScrubber` exists; the toast notifier
  lives here).

## Read first

- `docs/data-governance/00_overview.md`.
- `src/main/java/imagejai/engine/AgentLauncher.java` and the Swing
  panel that hosts it.
- Stage 03's `PostureBadge` for visual consistency.
- Stage 04's `AuditRow`.

## Scope

Four components, all in the launcher panel:

### 5.1 Configuration Pane (the showcase)

A collapsible "Data Governance ▾" expander that any user can open
and immediately see the posture, log path, and exports they can
generate:

```
Data Governance ▾
  Posture for this folder:    [ Standard | Pseudonymised | On-premises ]
  Audit log:                  AI_Exports/imagejai_audit.csv     [Open]
  Outbound calls this session: 17  (16 pseudonymised, 1 visual override)
  Generate Data Handling Statement                              [Generate]
  Vendor terms summary                                          [View]
```

The posture selector here is the same as the folder banner from
stage 01 — changes are sticky to the folder and emit downshift /
upshift logic via `PostureController`.

The "Generate" button is wired in stage 06 (placeholder until
then).

### 5.2 Egress indicator (the "lamp")

A 6×6 pixel filled circle next to the posture badge:

- Idle: dark grey (almost invisible).
- Lights solid red for 300ms each time bytes leave the JVM toward
  the agent process.
- Tooltip: *"Egress indicator. Lights when ImageJAI sends bytes to
  the agent process. (In On-premises mode the bytes go to a local
  process, not over the network.)"*

The tooltip is deliberately honest about On-premises — bytes still
leave the JVM, they just don't leave the machine. Over-claiming
destroys the trust signal.

### 5.3 Receipts pane

A collapsible panel below the launcher with a table of the last 50
outbound calls (subscribed to `AuditLog.recent(50)`). Columns:

```
time   |   command   |   bytes_out   |   redacted   |   [show]
```

`[show]` opens a modal with two text areas: the redacted JSON sent
to the agent, and the list of `fields_redacted` from the audit row.
Stores redacted payloads (≤8KB per receipt), never originals.

Brief-sent rows (from stage 09) appear with `command =
get_pending_brief` and notes summarising the token list.

### 5.4 Outbound prompt scrubber toast

When the `OutboundPromptScrubber` from stage 02 replaces a
substring in the user's typed prompt, surface a non-modal toast at
the bottom-right of the launcher:

```
┌──────────────────────────────────────────────────┐
│  Pseudonymised before send:                     │
│  'MOAB2_subject_017' → 'image-7a3f'             │
│                                       [ OK ]    │
└──────────────────────────────────────────────────┘
```

Toast dismisses on click or after 5s. Same audit row category as
TCP redaction (`fields_redacted=["prompt"]`).

### 5.5 Governance-vocabulary tooltips

Audit the launcher panel and add tooltips using institutional
vocabulary:

- Posture badge — stage 03.
- "View Audit Log" — *"Audit trail of outbound calls to the agent.
  CSV format. Suitable for ethics applications."*
- "Generate Data Handling Statement" — *"Produces a printable
  statement summarising vendor terms, pseudonymisation scheme, and
  audit trail for this project."*
- Agent dropdown — *"Agent CLI to launch. In On-premises posture,
  only local-binary agents are selectable."*
- Egress indicator — see 5.2.
- Play button — *"Launch the selected agent. The current Privacy
  Posture governs what data may leave the machine."*

Every tooltip should sound like it came out of a DPIA.

## Out of scope

- The PDF generator itself (stage 06).
- Telemetry / analytics — nothing this stage adds may itself send
  bytes off-machine.
- The Browse Files dialog (stage 09).
- The brief broker (stage 09).

## Files touched

| Path | NEW/MODIFY | Reason |
|------|------------|--------|
| `src/main/java/imagejai/ui/ConfigurationPane.java` | NEW | The expander showcase |
| `src/main/java/imagejai/ui/EgressIndicator.java` | NEW | The lamp |
| `src/main/java/imagejai/ui/ReceiptsPane.java` | NEW | Collapsible audit table + receipt modal |
| `src/main/java/imagejai/ui/PseudonymisationToast.java` | NEW | Outbound scrubber notification |
| `src/main/java/imagejai/engine/security/AuditLog.java` | MODIFY (from stage 04) | Expose `subscribeRecent(Listener)` for the pane |
| `src/main/java/imagejai/engine/TCPCommandServer.java` | MODIFY | Emit `OutboundEvent` for the indicator |
| `src/main/java/imagejai/engine/AgentLauncher.java` | MODIFY | Compose the pane + indicator + receipts panel + toast layer |
| `src/test/java/imagejai/ui/*Test.java` | NEW | Headless tests for each component |

## Implementation sketch

`EgressIndicator`:

```java
public final class EgressIndicator extends JComponent {
    private static final Color IDLE   = new Color(0x40, 0x40, 0x40);
    private static final Color ACTIVE = new Color(0xE3, 0x1B, 0x23);
    private Color current = IDLE;
    private Timer decay;

    public EgressIndicator(EventBus bus) {
        setPreferredSize(new Dimension(8, 8));
        setToolTipText("<html>Egress indicator. Lights when bytes leave the JVM toward the agent.<br/>"
                     + "In On-premises mode the bytes go to a local process, not over the network.</html>");
        bus.subscribe(OutboundEvent.class, e -> SwingUtilities.invokeLater(this::flash));
    }
    private void flash() { /* set ACTIVE, schedule 300ms decay to IDLE */ }
    protected void paintComponent(Graphics g) { g.setColor(current); g.fillOval(1, 1, 6, 6); }
}
```

`ReceiptsPane` model uses `AuditLog.recent(50)`; receipt modal
stores the redacted body (≤8KB cap, "…(truncated)…" tail).

`ConfigurationPane`:

```java
public final class ConfigurationPane extends JPanel {
    public ConfigurationPane(PostureController posture, AuditLog log,
                             DataHandlingStatementGenerator pdfGen /* stage 06 */) {
        setLayout(new GridBagLayout());
        add(new JLabel("Posture for this folder:"), gc(0,0));
        add(new PostureSelector(posture), gc(1,0));

        add(new JLabel("Audit log:"), gc(0,1));
        JButton openLog = new JButton("Open");
        openLog.addActionListener(e -> { try { log.open(); } catch (IOException ex) { /* dialog */ } });
        add(new JPanel().withLabel("AI_Exports/imagejai_audit.csv").withButton(openLog), gc(1,1));

        add(new JLabel("Outbound calls this session:"), gc(0,2));
        add(new SessionStatsLabel(log), gc(1,2));

        add(new JLabel("Data Handling Statement:"), gc(0,3));
        JButton gen = new JButton("Generate");  // stage 06 wires the actual call
        add(gen, gc(1,3));
    }
}
```

## Exit gate

1. `mvn compile`, `mvn test` pass.
2. `EgressIndicatorTest` — headless: subscribing component
   transitions to ACTIVE on `OutboundEvent`, returns to IDLE after
   ~300ms.
3. `ReceiptsPaneTest` — headless: table populates from
   `AuditLog.subscribeRecent`, modal renders.
4. `ConfigurationPaneTest` — headless: posture selector reflects
   `PostureController.current()` and updates on change.
5. Manual: all four components visible at once on the launcher
   panel without UI cramping.
6. Manual: every tooltip uses the documented vocabulary.
7. Manual: in Pseudonymised mode, run a TCP command — egress lamp
   blinks, Receipts gains a row, `[show]` reveals redacted payload.
8. Manual: type a sensitive substring (already in `PathTokenMap`)
   into the embedded terminal → toast appears showing the
   replacement; the underlying audit row notes
   `fields_redacted=["prompt"]`.
9. Manual: Configuration Pane "Session stats" updates live as
   commands run.

## Known risks

- Receipts pane storing 50 large redacted payloads can balloon
  memory. Cap each receipt at 8KB.
- The egress lamp's honesty about On-premises is the trust signal.
  Resist the temptation to suppress it when bytes go local —
  document the actual semantics in the tooltip.
- Don't add a "clear audit log" or "clear receipts" button. Audit
  is append-only.
- ConfigurationPane changes to posture should route through the
  same `PostureController.requestPosture()` as the folder banner —
  not bypass it (so downshift logic, upshift refusal, audit
  emission all stay consistent).
