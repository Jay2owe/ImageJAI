# AgentConsole-Style Makeover for ImageJAI TCP

## Context

ImageJAI exposes Fiji to external agents (Claude, other bots) via a single TCP command server on port 7746 (`TCPCommandServer.java`). The protocol today is **request → response, strictly pull-based**: the agent asks, the server blocks on the EDT, the server replies. A Python helper (`agent/ij.py`) wraps that surface.

In parallel, the sister project **AgentConsole** runs a persistent Claude CLI ("AI Commander") whose job is to orchestrate agents. Over many iterations it evolved a set of TCP/control patterns that made the app *dramatically* easier for an LLM to drive: push notifications, readonly shortcuts, async job IDs, state dedup, batch protocol, intent mappings, friction logs, reactive YAML rules, and GUI backchannel sentinels.

This makeover ports those patterns into ImageJAI's TCP server so the external agent experience stops being "poll, wait, poll, wait, guess" and becomes "subscribe, react, fire-and-forget".

## End Goal

A Fiji-side TCP server that behaves like a **collaborator**, not a passive endpoint:

- **Subscribes** the agent to events it cares about instead of forcing hooks to re-scrape state every prompt.
- **Answers cheap questions cheaply** — read-only queries skip the full dispatch path.
- **Never blocks** on long plugins — async job IDs + push completion.
- **Never repeats itself** — state is hashed, unchanged context becomes one byte.
- **Learns** shorthand phrases the user actually types, so repeat workflows stop costing LLM tokens.
- **Tells you when it's stuck** — friction log makes failure patterns visible.
- **Drives the chat panel** — GUI sentinels let the external agent surface previews, toasts, and ROI highlights back to the user.
- **Reacts on its own** to known footguns (Incucyte inch calibration, stuck error dialogs) via a small reactive-rules engine.

## Success Criteria

When an external Claude session works on a typical Fiji task:

1. Idle Fiji = zero hook traffic. No re-injection of unchanged state.
2. A 10-minute StarDist run doesn't block the socket or time out.
3. `ij.py state` / `info` / `results` round-trips drop below 10ms.
4. Typing a phrase the user has taught once never hits the LLM again.
5. Claude can ask "what keeps failing?" and get a real answer from the server.
6. A macro error dialog auto-closes; a confused agent auto-captures a screenshot.
7. The plugin's own chat panel can be driven remotely via TCP markers.

## Phase Map

Phases are ordered by **felt impact per hour of work**, not by architectural purity. Each phase is independently mergeable and independently useful — stop at any point and the previous phases still deliver value.

| # | Phase | Felt impact | Complexity |
|---|-------|-------------|------------|
| 1 | State dedup + readonly fast-path | Every hook call gets cheaper | Low |
| 2 | Event bus (push notifications) | Idle Fiji stops costing tokens | Medium |
| 3 | Async jobs with IDs | Long plugins stop blocking | Medium |
| 4 | Batch `\|\|\|` shorthand | Multi-step workflows feel instant | Low |
| 5 | Server-side intent mappings | Repeat phrases stop hitting LLM | Medium |
| 6 | Friction log | Agent sees its own failure patterns | Low |
| 7 | GUI_ACTION sentinels | Chat panel becomes remote-drivable | Medium |
| 8 | Reactive YAML rules | Known footguns auto-fix | Medium |

Phase files:

- `01_state_dedup_readonly_fastpath.md`
- `02_event_bus_push_notifications.md`
- `03_async_jobs.md`
- `04_batch_shorthand.md`
- `05_intent_mappings.md`
- `06_friction_log.md`
- `07_gui_action_sentinels.md`
- `08_reactive_rules.md`

## Non-Goals

- No change to the LLM-facing chat panel logic (`ConversationLoop.java`) except where Phase 7 needs a sentinel interceptor. The plugin's own in-Fiji chat continues to work exactly as today.
- No external dependencies. Everything stays inside Fiji-shipped libs (Gson, Swing, `java.net`).
- No breaking changes to existing TCP commands. Every new capability is additive and opt-in — old `ij.py` scripts keep working unchanged.
- No Java hot-reload, persistent daemons outside Fiji's JVM, or cross-process IPC beyond the existing TCP socket.
