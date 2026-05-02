# Stage 09 — Models and Agents installer panel

## Why this stage exists

Biologists who could benefit from Claude Code or Ollama-served
Gemma often will not get past `npm i -g …` on their own. This stage
adds a one-click install panel inside the ImageJAI settings, so
external agents become accessible without leaving Fiji. It also
houses the optional MiniLM semantic-boost download (the only model
file the Local Assistant itself ever fetches), and replaces the
unconditional `--dangerously-skip-permissions` flag in
`AgentLauncher.KNOWN_AGENTS` with a GSD-aware conditional flag.

## Prerequisites

- Stage 01 (`Settings` patterns; the new tab follows the existing
  settings UI conventions).

## Read first

- `docs/local_assistant/00_overview.md`
- `docs/local_assistant/PLAN.md` §12 ("Models and Agents Installer
  Panel")
- `src/main/java/imagejai/engine/AgentLauncher.java` —
  `KNOWN_AGENTS`, `detectAgents()`. **Note the unconditional
  `--dangerously-skip-permissions` for Claude Code; this stage
  replaces it with conditional behaviour.**
- `src/main/java/imagejai/ui/SettingsDialog.java` — how
  tabs / panels are added today
- ImageJAI's `agent/CLAUDE.md` and `CLAUDE.md` for "do not modify
  update sites programmatically" — the same caution applies to npm
  and Ollama installs (always show the command and ask)

## Scope

### Models & Agents tab

Add a "Models & Agents" tab in `SettingsDialog` containing one
row per agent. Each row has: name, status, action button.

| Agent | Status detection | Action |
|---|---|---|
| Local Assistant (built-in) | always ✓ | none |
| Local Assistant — semantic boost (MiniLM, ~80 MB) | `Settings.miniLmInstalled` flag | **[Download]** — fetches `all-MiniLM-L6-v2-int8.onnx` (+tokenizer files) into `~/.imagej-ai/models/all-MiniLM-L6-v2/`, verifies SHA-256, sets the flag |
| Claude Code | `claude --version` exits 0 | **[Install via npm]** — `npm i -g @anthropic-ai/claude-code` (or current package). If `npm` not on `PATH`, offer to open the Node download page. |
| Codex CLI | `codex --version` exits 0 | **[Install via npm]** |
| Ollama (CLI only — models are cloud-served) | `ollama --version` exits 0 | **[Install Ollama CLI]** (~250 MB download from ollama.com). **No additional model pulls** — the configured Ollama-backed agents (Gemma 4 31B etc.) use cloud-served models. |
| Aider | `aider --version` exits 0 | **[Install via pip]** — `pip install aider-chat` |
| Gemini CLI | `gemini --version` exits 0 | **[Install via npm]** |

Implementation notes for the install action:

- Run via `ProcessBuilder`, capturing stdout/stderr line by line
  into a small log dialog so the user sees progress.
- **Always** show the exact command before executing and ask for
  confirmation. The user's house rule "do not modify update sites
  programmatically" generalises: never run an installer silently.
- Show file-size warnings before any download or install kicks off.
- For MiniLM: download to `~/.imagej-ai/models/all-MiniLM-L6-v2/`,
  verify the SHA-256 against a constant in `Settings`, set
  `miniLmInstalled = true` only after verification passes. Failed
  downloads delete the partial file.

### GSD detection for Claude

Add `GsdDetector.java` with a single static method
`isInstalled() -> boolean`. Detection order:

1. Check `~/.claude/skills/gsd/` exists.
2. Fallback: run `claude /gsd:help` with a 5-second timeout; exit 0
   means installed.

Cache the result in memory for the lifetime of the plugin.

In `AgentLauncher`:

- Remove the unconditional `--dangerously-skip-permissions` from
  the Claude Code entry in `KNOWN_AGENTS`.
- At launch time, if the agent is Claude Code and `GsdDetector.
  isInstalled()` is true, append `--dangerously-skip-permissions`
  to the command line. Otherwise launch without it.

In the Models & Agents tab, when the user clicks the Claude row's
action button:

- If GSD is installed: confirm "Claude Code will launch with
  `--dangerously-skip-permissions` (faster, unlocks full
  potential)."
- If GSD is not installed: show one-time prompt: *"Install GSD to
  skip permission prompts and run Claude at full speed? [Install]
  [Skip]"*. If [Install], shell out to whatever the GSD install
  command is (locate it from the user's existing `~/.claude/`
  setup or the GSD project README; do not invent a command).

**GSD is not safer; it is faster and unlocks the full potential.**
The UI copy must reflect that.

### Settings additions

```java
// Settings.java
public boolean miniLmInstalled = false;
public String miniLmModelSha256 = "<constant>";
public boolean claudeUseGsdFlag = true;   // user override of GSD detection
```

## Out of scope

- Actually using MiniLM at runtime (an opt-in tier 2.5 between
  Jaro-Winkler and IntentRouter). Implementing that is a future
  stage; this stage only ships the download flow.
- Slash command for installs (e.g. `/install claude`) — out of
  scope for v1.
- Auto-update checks — out of scope.
- Update site management for Fiji plugins — explicitly forbidden
  by the project house rules.

## Files touched

| Path | Action | Reason |
|---|---|---|
| `src/main/java/imagejai/ui/InstallerPanel.java` | NEW | Models & Agents tab |
| `src/main/java/imagejai/ui/SettingsDialog.java` | MODIFY | Add the tab |
| `src/main/java/imagejai/engine/GsdDetector.java` | NEW | Probe for GSD |
| `src/main/java/imagejai/engine/AgentLauncher.java` | MODIFY | Remove unconditional `--dangerously-skip-permissions`; conditional pass at launch time |
| `src/main/java/imagejai/config/Settings.java` | MODIFY | Add `miniLmInstalled`, `miniLmModelSha256`, `claudeUseGsdFlag` |
| `src/main/java/imagejai/install/MiniLmDownloader.java` | NEW | Streamed download + SHA-256 verify |
| `src/main/java/imagejai/install/ProcessRunner.java` | NEW | Confirm + run + stream-to-log dialog |

## Implementation sketch

```java
// AgentLauncher.launch — conditional flag for Claude
public AgentSession launch(AgentInfo agent, Mode mode) {
  List<String> cmd = new ArrayList<>(baseCommand(agent));
  if ("Claude Code".equals(agent.name)
      && settings.claudeUseGsdFlag
      && GsdDetector.isInstalled()) {
    cmd.add("--dangerously-skip-permissions");
  }
  return spawn(cmd, mode);
}
```

```java
// MiniLmDownloader.download
public void download(Path dest) throws IOException {
  Path tmp = dest.resolveSibling(dest.getFileName() + ".part");
  try (var in = openVerifiedStream(MODEL_URL);
       var out = Files.newOutputStream(tmp)) {
    byte[] buf = new byte[8192]; int n;
    MessageDigest sha = MessageDigest.getInstance("SHA-256");
    while ((n = in.read(buf)) > 0) {
      out.write(buf, 0, n); sha.update(buf, 0, n);
    }
    String got = HexFormat.of().formatHex(sha.digest());
    if (!got.equalsIgnoreCase(EXPECTED_SHA)) {
      Files.delete(tmp);
      throw new IOException("SHA-256 mismatch");
    }
    Files.move(tmp, dest, StandardCopyOption.ATOMIC_MOVE);
  }
}
```

## Exit gate

1. Settings opens; the "Models & Agents" tab is present and
   populated. Status icons reflect reality (run with Claude
   uninstalled, then install it via the panel, reopen — status
   flips to ✓).
2. MiniLM download writes the file to
   `~/.imagej-ai/models/all-MiniLM-L6-v2/`, verifies SHA-256, sets
   `Settings.miniLmInstalled = true`. Cancelling mid-download
   removes the partial file.
3. With GSD installed: launching Claude Code from the agent
   selector passes `--dangerously-skip-permissions`. Verify by
   inspecting the launched process command line.
4. With GSD not installed (rename `~/.claude/skills/gsd/` to test):
   Claude Code launches **without** the flag. The panel offers an
   "Install GSD" prompt with the correct install command.
5. Each install action shows the command before executing and
   streams output to a log dialog.
6. `mvn -q test` passes.

## Known risks

- **`npm` / `pip` / `ollama` install commands change.** Hard-code
  the *current* canonical commands but make them editable via a
  `Settings` field so packagers can patch without a code change.
- **Cross-platform shell differences.** `npm i -g` on Windows may
  need `--global` and a different `PATH` reload. Test on Windows
  and macOS at minimum; document Linux.
- **GSD install command.** Do not invent it. Read from the user's
  `~/.claude/` setup or the GSD project README (likely
  `npm i -g @gsd/...` or similar). If we cannot locate it
  reliably, the prompt should link to the GSD install docs rather
  than running anything.
- **MiniLM URL stability.** Hugging Face URLs occasionally
  change; verify by SHA-256, not by URL. Pin the SHA in code.
- **`SettingsDialog` already-large.** Tabs add complexity. If
  `SettingsDialog` is hard to extend cleanly, build the panel as a
  standalone window invoked from a "Manage Models & Agents" button
  in the existing dialog — the UX matters less than getting the
  install actions right.
