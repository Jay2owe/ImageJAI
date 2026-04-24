# Stage 09 — Per-agent registries + Commands + New-agent-chat

## Why this stage exists

Stage 08 shipped prompt interception with a single
`default/approval.json` fallback. That's safe but brittle — every
prompt escalates because no agent-specific patterns are in place.
This stage ships the real per-agent registry files
(`commands.json`, `clear.json`, `approval.json`) for all five
agents in `KNOWN_AGENTS`, plus the two rail buttons that consume
them: **Commands** (popup of per-agent slash commands) and
**New agent chat** (renamed from "New chat" for disambiguation —
it clears the agent's context, not Fiji state).

## Prerequisites

- Stage 08 `_COMPLETED`.

## Read first

- `docs/embedded-agent-widget/00_overview.md`
- `docs/embedded-agent-widget/PLAN.md` §2.4 (rail dispatch modes),
  §2.6 (per-agent behaviour matrix), §4 Phase 4 bullets on
  registries and the two buttons
- `src/main/java/imagejai/engine/AgentLauncher.java:46-56`
  (`KNOWN_AGENTS` — the authoritative agent list)
- `agent/gemma4_31b/loop.py:187-194` (Gemma built-in slash commands)
- `agent/gemma4_31b/loop.py:861-871` (user `.ccommands/` scan)

## Scope

- Six sets of three files under `src/main/resources/agents/<id>/`:
  `gemma4_31b/`, `gemma4_31b_claude/`, `claude/`, `aider/`,
  `gemini/`, `codex/`. Each set contains:
  - `commands.json` — array of `{command, description}` for the
    agent's built-in slash commands.
  - `clear.json` — `{match: "<regex>", on_miss: "pty_restart"}`
    tells the "New agent chat" button how to detect "unknown
    command" fallback.
  - `approval.json` — `auto_confirm` + `always_escalate` regex
    lists from stage 08's sketch, tuned per-agent (e.g. Claude's
    `Allow/Approve/Permit` + trust-folder matchers).
- **Commands** button on the `LeftRail` under an "Agent" section.
  Popup lists the current agent's commands; clicking injects
  `<cmd>\r` into the PTY. For Claude Code additionally live-scans
  `.claude/commands/*.md` in the working dir. For Gemma scans
  `~/.config/imagej-ai/gemma4_31b/.ccommands/*.{md,txt}`. For
  agents without a registry entry: button disabled, tooltip "no
  command list".
- **New agent chat** button. PTY-inject `/clear`; if the agent's
  `clear.json.match` pattern appears in scrollback within 1 s,
  stay; otherwise `session.destroy()` + `launcher.launch(info,
  EMBEDDED)` in the same tab. Fiji state untouched.
- `scripts/refresh_agent_registries.sh` (Bash) +
  `scripts/refresh_agent_registries.ps1` (PowerShell): runs
  `<cli> /help` for each installed agent, parses output, writes
  the updated `commands.json`. Intended for monthly manual runs,
  not CI.
- Day-1 verification on Phase 4 entry: run each agent's `/help`
  live and confirm the `commands.json` matches reality. Codex and
  Gemini `commands.json` are generated during this day-1 check —
  PLAN.md §2.6 explicitly marks them as "To verify".

## Out of scope

- Macro sets / Run recipe / New WIP / Audit buttons — stage 10.
- Session history panel — stage 11.
- PTY-restart / `session.destroy()` plumbing — already exists
  from stage 05 (`AgentSession.destroy()`).

## Files touched

| Path                                                              | Action | Reason                                         |
|-------------------------------------------------------------------|--------|------------------------------------------------|
| `src/main/resources/agents/gemma4_31b/commands.json`              | NEW    | Built-in + boilerplate                         |
| `src/main/resources/agents/gemma4_31b/clear.json`                 | NEW    | `/clear` matcher                               |
| `src/main/resources/agents/gemma4_31b/approval.json`              | NEW    | Auto-confirm / escalate patterns               |
| `src/main/resources/agents/gemma4_31b_claude/…`                   | NEW    | Same shape as gemma4_31b (system prompt diff)  |
| `src/main/resources/agents/claude/…`                              | NEW    | Claude Code variants                           |
| `src/main/resources/agents/aider/…`                               | NEW    | Aider built-ins from `aider --help`            |
| `src/main/resources/agents/gemini/…`                              | NEW    | Gemini CLI; verify day 1                       |
| `src/main/resources/agents/codex/…`                               | NEW    | Codex CLI; verify day 1                        |
| `src/main/java/imagejai/ui/LeftRail.java`              | MODIFY | Agent section with Commands + New agent chat   |
| `scripts/refresh_agent_registries.sh`                             | NEW    | Dev script for monthly refresh                 |
| `scripts/refresh_agent_registries.ps1`                            | NEW    | Windows counterpart                            |

## Implementation sketch

`commands.json` shape:
```json
[
  {"command": "/clear",       "description": "Clear chat history"},
  {"command": "/queue",       "description": "Show pending turns"},
  {"command": "/interrupt",   "description": "Abort current turn"},
  {"command": "/ccommands",   "description": "List user commands"},
  {"command": "/save-recipe", "description": "Save session as recipe"}
]
```

`clear.json` shape:
```json
{
  "match": "(?i)(unknown command|not recognised)",
  "on_miss": "pty_restart"
}
```

Commands popup:
```java
void onCommandsClicked() {
    AgentInfo info = session.info();
    List<Cmd> builtins = loadBuiltins(info.id);          // resources/agents/.../commands.json
    List<Cmd> userCmds = scanUserCommandDir(info.id);    // .claude/commands/ or .ccommands/
    JPopupMenu m = new JPopupMenu();
    addSection(m, "Built-in", builtins, cmd -> session.writeInput(cmd + "\r"));
    if (!userCmds.isEmpty())
        addSection(m, "User", userCmds, cmd -> session.writeInput(cmd + "\r"));
    m.show(button, 0, button.getHeight());
}
```

New-agent-chat flow:
```java
void onNewAgentChatClicked() {
    session.writeInput("/clear\r");
    scheduleAfter(1000, () -> {
        if (!scrollbackContains(ClearJson.load(info.id).match())) return;
        // unknown command → restart
        AgentSession fresh = launcher.launch(info, Mode.EMBEDDED);
        terminalHost.swap(fresh);
    });
}
```

## Exit gate

1. Commands popup works for every agent in `KNOWN_AGENTS` —
   clicking a command injects it + `\r` and the agent responds.
2. Claude Code Commands popup additionally lists `.md` files
   from `.claude/commands/` in the current working dir.
3. Gemma Commands popup additionally lists
   `~/.config/imagej-ai/gemma4_31b/.ccommands/*.{md,txt}`.
4. New agent chat button:
   - On Gemma / Claude / Aider: sends `/clear`, chat clears, same
     session continues.
   - On an agent that doesn't support `/clear`: restart fires,
     user sees a fresh banner within ~2 s.
5. Stage-08 `ApprovalPolicy` now loads the per-agent
   `approval.json` (not the `default/` placeholder). Claude
   trust-folder prompt auto-confirms without the placeholder.
6. `scripts/refresh_agent_registries.{sh,ps1}` runs cleanly on a
   machine with the agents installed; diffs against the committed
   JSON are minimal.

## Known risks

- Slash-command drift. Aider and Claude Code add commands in
  point releases. The refresh-registries scripts address this, but
  drift won't auto-page us. Commit each JSON with the agent
  version in the commit message so `git log` explains the pin.
- Live-scanning `.claude/commands/` is filesystem I/O on every
  popup open. Cache for 5 s to avoid hammering.
