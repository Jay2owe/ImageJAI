# Stage 08 — Prompt interception + URL handling

## Why this stage exists

Claude Code, Gemini CLI, and Codex CLI print "Do you trust this
folder?" / "Allow read access…" prompts on first run and on many
risky operations. Today those prompts block the agent forever
inside the embedded terminal because JediTerm has no notion of
what's on screen. This stage adds a scrollback watcher, a
per-agent approval policy, a toolbar of Confirm / Cancel /
Interrupt / Kill buttons, and clickable URL hyperlinks so OAuth
flows aren't a copy-paste nightmare. Behaviour is ported verbatim
from the AgentConsole Python widget so we inherit its already-
tested regexes and ordering — we translate Python/pyte logic into
Java/JediTerm, not redesign it.

## Prerequisites

- Stage 06 `_COMPLETED`. (Stage 07 may be in flight in parallel —
  it doesn't touch the terminal pane.)

## Read first

- `docs/embedded-agent-widget/00_overview.md`
- `docs/embedded-agent-widget/PLAN.md` §2.7 (prompt interception),
  §2.8 (URL + OAuth-link handling)
- AgentConsole reference files (not in this repo — adjust paths
  based on where the user keeps it; ask before blind-editing):
  - `gui/vt100_widget.py:1464-1474` (Ctrl+C dual-mode)
  - `gui/vt100_widget.py:1476-1482` (Ctrl+V paste)
  - `adapters/claude_adapter.py:20-23` (`_APPROVAL_RE`)
  - `core/session_manager.py:108-114` (`_TRUST_PROMPT_RE`)
  - `core/ai_commander.py:44-46` (`_PASTE_CONFIRM_RE`)
  - `core/ai_commander.py:1105-1124` (`_poll_paste_confirm`)
- `src/main/java/imagejai/ui/TerminalView.java` (from
  stage 06 — mount the toolbar above the JediTerm pane)

## Scope

- `PromptWatcher` — 250 ms poll over `JediTermWidget`'s last 20
  buffer lines. Reads per-agent regex patterns from
  `src/main/resources/agents/<id>/approval.json` (files land in
  stage 09; this stage ships a placeholder `default/approval.json`
  that escalates everything).
- `ApprovalPolicy` — loads `approval.json`, decides
  `AUTO_CONFIRM` / `ESCALATE` / `PENDING` for each matched prompt.
  Unmatched prompts escalate (safe default).
- `TerminalToolbar` mounted above the JediTerm pane:
  - **Confirm** — hidden by default; shown when watcher detects a
    pending prompt. Sends `\r`.
  - **Cancel** — hidden by default; shown with Confirm. Sends `\x1b`.
  - **Interrupt** — always visible. Sends `\x03`.
  - **Kill session** — always visible. Modal confirm →
    `session.destroy()`.
  - **Copy URL** — appears when watcher sees a fresh URL; copies
    the most recent URL to clipboard; disappears after 30 s or
    when another URL arrives.
- **Ctrl+C dual-mode binding.** JediTerm's default handler is
  replaced: if `getSelectedText()` is non-empty, copy selection to
  OS clipboard and clear; else send `\x03` into the PTY.
- **Ctrl+V raw UTF-8 paste.** Matches AgentConsole's v1 (no
  bracketed paste). If prompt_toolkit corruption appears in
  Gemma, stage 12 can flip `setBracketedPasteMode(true)`.
- **URL hyperlinks.** Register a `HyperlinkStyle` provider on
  `JediTermWidget` for `https?://\S+` and `file://\S+` → open via
  `Desktop.getDesktop().browse(URI)`.
- **Focus return.** After any toolbar-button dispatch, call
  `terminalWidget.requestFocusInWindow()`.
- **Approval escalation modal.** When `ApprovalPolicy` returns
  `ESCALATE`, show a modal: "The agent is asking permission for:
  <raw prompt text>. Allow / Deny" — Allow sends `\r`, Deny sends
  `\x1b`. `always_escalate` regex entries (e.g. `rm -rf`, `git
  reset --hard`, `drop table`) can never be auto-confirmed.

## Out of scope

- The per-agent `commands.json` / `clear.json` / `approval.json`
  registry content for the five named agents — stage 09 (only the
  `default/` placeholder ships here).
- Commands button / New-agent-chat button / any PTY-inject menu
  — stage 09.
- Bracketed-paste flip — stage 12, contingent on stage 08 field
  reports.

## Files touched

| Path                                                                        | Action | Reason                                         |
|-----------------------------------------------------------------------------|--------|------------------------------------------------|
| `src/main/java/imagejai/terminal/PromptWatcher.java`             | NEW    | 250 ms poll, pattern match, emit events        |
| `src/main/java/imagejai/terminal/ApprovalPolicy.java`            | NEW    | Loads approval.json, classifies prompts        |
| `src/main/java/imagejai/ui/TerminalToolbar.java`                 | NEW    | Confirm / Cancel / Interrupt / Kill / Copy URL |
| `src/main/java/imagejai/ui/TerminalView.java`                    | MODIFY | Mount toolbar above the JediTerm pane          |
| `src/main/java/imagejai/terminal/EmbeddedPty.java`               | MODIFY | Register hyperlink style + Ctrl+C override     |
| `src/main/resources/agents/default/approval.json`                           | NEW    | Escalate-all placeholder                       |

## Implementation sketch

`approval.json` shape (final per-agent files land in stage 09):
```json
{
  "auto_confirm": [
    "(?i)^allow\\s+read\\s+access\\s+to\\s+.*CLAUDE\\.md",
    "(?i)^permit\\s+access\\s+to\\s+.*/AI_Exports/"
  ],
  "always_escalate": [
    "(?i)rm\\s+-rf",
    "(?i)git\\s+reset\\s+--hard",
    "(?i)drop\\s+table"
  ]
}
```

```java
public final class PromptWatcher {
    void tick() {
        String tail = readLast20Lines(terminal);
        if (!changedSinceLastTick(tail)) return;
        for (Pattern p : policy.autoConfirm()) {
            if (p.matcher(tail).find() && !alwaysEscalate(tail)) {
                pty.write("\r"); return;
            }
        }
        if (isQuestionPrompt(tail)) fireEvent(PromptState.PENDING, tail);
        if (containsUrl(tail))      fireEvent(UrlSeen, extractUrl(tail));
    }
}
```

Ctrl+C dual-mode (pseudocode, inside the JediTerm key-handler
override):
```java
if (e.getKeyCode() == VK_C && e.isControlDown()) {
    String sel = terminal.getSelectedText();
    if (sel != null && !sel.isEmpty()) {
        copyToClipboard(sel); terminal.clearSelection();
    } else {
        pty.write(new byte[]{0x03});
    }
    e.consume();
}
```

## Exit gate

1. Trust-folder prompt in Claude Code auto-confirms via
   `default/approval.json` override at runtime (placeholder
   regex). Manual test: launch Claude Code in a fresh folder → no
   user click required to proceed.
2. `rm -rf` prompt from any agent escalates to the modal — never
   auto-confirmed.
3. Ctrl+C with no selection sends `\x03`; with selection copies.
4. Ctrl+V pastes raw UTF-8 into the PTY.
5. OAuth URL in Claude Code first-run is clickable in the
   terminal pane and opens the system browser; a "Copy URL"
   button also appears in the toolbar for 30 s.
6. Kill Session modal asks for confirmation before
   `session.destroy()`.
7. After clicking any toolbar button, the next keystroke lands in
   the PTY without a manual click.

## Known risks

- **Regex false positives.** If `auto_confirm` matches inside a
  chat transcript the agent is echoing, we'd silently confirm a
  prompt that isn't there. Mitigation: match only against the
  **last 2** non-blank lines, not the whole 20-line tail, and
  require the line to end with `?`, `:`, or `[y/N]`.
- **Poll interval.** 250 ms is a balance. Too fast wastes CPU;
  too slow means the user sees the prompt before the watcher
  does and clicks through manually. Matches AgentConsole's
  tested value.
- **JediTerm hyperlink API drift.** JediTerm's `HyperlinkStyle`
  provider API has shifted between point releases. If the 3.57
  surface differs from what's sketched here, fall back to a
  listener that replaces the `TextBuffer`'s styles post-write.
