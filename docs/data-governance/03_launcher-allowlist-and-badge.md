# 03 — AgentLauncher allowlist + posture badge

## Why this stage exists

A filter that pseudonymises identifiers is necessary but not
sufficient — On-premises mode also needs to ensure the agent doing
the asking is itself local. The `gemma4:31b-cloud` model tag in
Ollama routes through Ollama's US servers and is, for posture
purposes, a cloud agent. This stage prevents the user from picking a
cloud agent (or a `*-cloud` Ollama tag) while On-premises is in
force.

This stage also lays in the launcher-panel slots for the **Browse
Files** button (filled by stage 09) and the **Configuration Pane**
(filled by stage 05). The coloured posture badge is the most-seen
trust signal: every launch, the user sees a posture chip next to the
play button.

## Prerequisites

- Stage 01 (`PrivacyPosture`, `PostureController`).

## Read first

- `docs/data-governance/00_overview.md`.
- `src/main/java/imagejai/engine/AgentLauncher.java` — full file.
  Grep for `KNOWN_AGENTS`, `detectAgents`, `launch`,
  `buildAgentCommandString` (locate by name, not by line number).
- `src/main/java/imagejai/engine/AgentLaunchSpec.java`.
- `agent/` directory — which wrappers exist and which are
  local-only.

## Scope

- Add `boolean isLocal()` to `AgentLaunchSpec`.
- In `detectAgents()`, filter by posture: `ON_PREMISES` returns
  only specs where `isLocal()` is true.
- Parse Ollama model tag from wrapper env (`OLLAMA_MODEL` or first
  configured model). Refuse `*-cloud` suffixes in On-premises with
  a clear error: *"On-premises mode cannot use cloud-hosted Ollama
  models. Switch to a local tag (e.g. `gemma3:27b`) or change the
  posture for this folder."*
- Add `PostureBadge` Swing component shown next to the play
  button. Updates live via `PostureController` listener.
- Reserve UI slots in the launcher panel for:
  - `[Browse Files…]` button (filled by stage 09 — render a
    disabled placeholder until then).
  - `[Configuration Pane ▾]` expander (filled by stage 05 — same
    placeholder treatment).
- Tooltip vocabulary uses governance language (steward, audit
  trail, pseudonymisation scheme).

## Out of scope

- The audit log (stage 04).
- The egress lamp, Receipts pane, Configuration Pane contents
  (stage 05).
- The PDF generator (stage 06).
- The Browse Files dialog and brief broker (stage 09).

## Files touched

| Path | NEW/MODIFY | Reason |
|------|------------|--------|
| `src/main/java/imagejai/engine/AgentLauncher.java` | MODIFY | Posture-aware `detectAgents()`, `*-cloud` Ollama refusal, slot placement |
| `src/main/java/imagejai/engine/AgentLaunchSpec.java` | MODIFY | Add `boolean isLocal()` |
| `src/main/java/imagejai/ui/PostureBadge.java` | NEW | Coloured Swing chip with label + tooltip |
| `src/test/java/imagejai/engine/AgentLauncherPostureTest.java` | NEW | Filter + refusal tests |

## Implementation sketch

`AgentLaunchSpec`:

```java
public boolean isLocal() { return localOnly; }
```

`AgentLauncher.detectAgents()`:

```java
List<AgentLaunchSpec> detected = ...;
if (postureController.current() == PrivacyPosture.ON_PREMISES) {
    detected = detected.stream().filter(AgentLaunchSpec::isLocal).toList();
}
return detected;
```

Cloud-Ollama refusal:

```java
private void refuseCloudTagIfOnPremises(AgentLaunchSpec spec) {
    if (postureController.current() != PrivacyPosture.ON_PREMISES) return;
    if (!spec.isOllama()) return;
    String tag = resolveModelTag(spec);
    if (tag != null && tag.endsWith("-cloud")) {
        throw new PostureViolation(
            "On-premises mode cannot use cloud-hosted Ollama models. " +
            "Switch to a local tag (e.g. gemma3:27b) or change the posture for this folder."
        );
    }
}
```

`PostureBadge`:

```java
public final class PostureBadge extends JLabel implements PostureController.Listener {
    private static final Color GREY  = new Color(0x9AA0A6);
    private static final Color AMBER = new Color(0xF5A524);
    private static final Color GREEN = new Color(0x32A852);

    public PostureBadge(PostureController controller) {
        setOpaque(true);
        setBorder(new EmptyBorder(2, 8, 2, 8));
        controller.addListener(this);
        apply(controller.current());
    }
    public void postureChanged(PrivacyPosture from, PrivacyPosture to, Path folder) {
        SwingUtilities.invokeLater(() -> apply(to));
    }
    private void apply(PrivacyPosture p) {
        setText(p.label);
        setToolTipText("<html>" + p.description + "<br/>UK GDPR Art. 4(5) pseudonymisation applies.</html>");
        switch (p) {
            case STANDARD      -> setBackground(GREY);
            case PSEUDONYMISED -> setBackground(AMBER);
            case ON_PREMISES   -> setBackground(GREEN);
        }
    }
}
```

Layout (the play-button row of the launcher panel):

```
[Play ▶] [PostureBadge] [Browse Files… disabled] [Configuration ▾ disabled] [.] egress
                         ^ stage 09 enables       ^ stage 05 fills
```

## Exit gate

1. `mvn compile` passes.
2. `AgentLauncherPostureTest` covers:
   - In `ON_PREMISES`, `detectAgents()` returns only local specs.
   - In Standard / Pseudonymised, all specs returned.
   - Launching Ollama with `gemma4:31b-cloud` in On-premises throws
     `PostureViolation` with the expected message.
   - Same spec launches in Pseudonymised.
3. Manual: open Fiji, set posture to On-premises, agent dropdown
   shows only local-binary entries.
4. Manual: in On-premises, set Ollama model to `gemma3:27b-cloud`
   → error dialog with refusal message.
5. Manual: posture badge appears next to play button, colour and
   text match current posture, tooltip reads governance copy.
6. Manual: badge updates without restart when folder posture
   changes.

## Known risks

- Model tag detection from wrapper env may not be reliable for
  custom user wrappers — fall back to refusing the launch with a
  warning rather than silently allowing.
- The "Gemma 4 31B (Claude-style)" wrapper may default to a
  `-cloud` tag. Document the required local tag in stage 07.
- Colour-blind accessibility: badge always shows the text label,
  not just colour.
