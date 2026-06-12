# Family — Claude

## Handshake

The TCP server supports a capability handshake (`hello`) that
lets Claude declare what reply shapes it can parse. The
handshake is **optional**: a client that never says hello gets
today's reply shape unchanged.

Claude's defaults: `vision=true`, `output_format=markdown`,
`token_budget=20000`, `pulse=false` (hooks already feed session
state, so server-side pulse would duplicate).

**Safe mode** (`safe_mode=true` by default for handshake clients —
safe_mode_v2 stage 02). When on, the server runs the destructive-op
scanner, the auto-snapshot rescue, the per-image queue-storm guard,
and the scientific-integrity scanner before every mutating call.
The user's "Safe Mode" checkbox on the AgentLauncher panel feeds
through the `IMAGEJAI_SAFE_MODE` env var (`1`/`0`) to `ij.py`'s
hello payload. To enable the two opt-in guards (bit-depth narrow
block, Enhance-Contrast normalize block), set them in
`_HELLO_CAPS["safe_mode_options"]` directly. No-handshake clients
keep today's unguarded fast path.

Run it manually to see what features the server will emit for
Claude:

```bash
python ij.py capabilities
```

Every new socket is independent today — `ij.py` opens one socket
per command, so the hello response is informational. Future
steps will key caps off the agent id and persist them across
commands.

## Style

- Plan in one short line, act, report. Long plan paragraphs are
  not needed — the user can read the macro you ran.
- When asked for a number, lead with the number, then one line of
  context, then any caveats. Tables are fine; multi-paragraph
  prose is not.
