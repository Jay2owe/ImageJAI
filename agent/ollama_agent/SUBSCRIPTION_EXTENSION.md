# Ollama Chat Wrapper Bus Subscription Extension

**Date**: 2026-04-17
**Phase**: Phase 7 (Agent Bus Integration)
**Status**: Complete

## Overview

Extended both `ollama_chat.py` and `ollama_router.py` to support **bus subscriptions within the Gemma tool-calling interface**. The `agent_command()` function now accepts an optional `subscribe` parameter that accepts natural-language descriptions of subscription intents.

## What Changed

### Signature
```python
# Before
def agent_command(command: str) -> str:
    """Send a command to AgentConsole (agent orchestrator)."""
    return _ac_tcp(command)  # or _tcp(7745, command)

# After
def agent_command(command: str, subscribe: str = "") -> str:
    """Send a command to AgentConsole (agent orchestrator) and optionally subscribe.

    Args:
        command: Regular orchestrator command (list, spawn, kill, send, etc.)
        subscribe: Optional subscription description. If provided, subscribes to events.
                   Examples: "codex status", "watch agent activity", "reactive failures"
    """
    if subscribe:
        return _setup_bus_subscription(subscribe)
    else:
        return _ac_tcp(command)
```

### New Helper Functions

#### `_resolve_worker_sid(name_or_id: str) -> str`
Fuzzy-matches a worker name or explicit session ID via the `list` command.
- Input: `"codex"`, `"coder-1"`, or explicit SID like `"abc123-def456..."`
- Output: Session ID if found; empty string if not found
- Used: When a subscription mentions a specific worker by name

#### `_parse_subscribe_description(description: str) -> dict`
Parses natural-language description into topic patterns and prompt templates.

**Pattern Recognition** (case-insensitive):
- `"codex status"` / `"idle"` / `"working"` → `agent.<sid>.status`
- `"codex presence"` → `agent.<sid>.presence`
- `"codex lifecycle"` → `agent.<sid>.lifecycle`
- `"codex activity"` → `agent.<sid>.activity`
- `"reactive failure"` → `event.reactive.failed`
- `"any" + "fail"` → `event.reactive.failed`
- `"event"` → `event.*`
- `"presence"` → `agent.*.presence` (all agents)
- `"status"` → `agent.*.status` (all agents)

**Returns**: Dictionary with keys:
- `topics`: List of topic patterns (e.g., `["agent.<sid>.status"]`)
- `worker`: Extracted worker name (if any)
- `prompt_template`: Auto-generated prompt text (e.g., `"Worker {payload.status}. Read output and decide..."`)

#### `_setup_bus_subscription(description: str) -> str`
End-to-end subscription setup:
1. Parses the description
2. Resolves worker names to SIDs
3. Builds an `exec` call to `self._session_bus_registrar.subscribe_session(...)`
4. Runs via AgentConsole TCP with auth token
5. Returns registrar's `(message, level)` response

**Default Flags** (hardcoded for Commander feedback loop):
- `forward_system_events=True` — inject raw event into Commander's PTY
- `submit_system_events=True` — press Enter after the wrapper
- `submit_prompt=True` — press Enter after the prompt template
- `prompt_template="Worker {payload.status}. Read output and decide..."` (or custom)

### Files Modified

1. **`ollama_chat.py`** (lines 159–285)
   - Added 3 helper functions
   - Updated `agent_command(...)` signature and docstring
   - Uses `_ac_tcp(...)` for authenticated access

2. **`ollama_router.py`** (lines 116–285)
   - Added identical logic with `_router` suffix (self-contained)
   - Uses raw TCP + auth token helpers (`_ac_tcp_router`, etc.)
   - Maintains compatibility with existing `_tcp(7745, ...)` fallback

## Usage Examples

### Via Gemma Tool Calling (ollama_chat.py)

```
User: Watch the codex worker for me.
→ Gemma calls: agent_command(command="", subscribe="codex status")
→ Result: Subscribed to agent.<codex_sid>.status with auto-prompt

User: Tell me when any agent fails.
→ Gemma calls: agent_command(command="", subscribe="reactive failure")
→ Result: Subscribed to event.reactive.failed
```

### Via Router (ollama_router.py)

Used by home automation intent router when Gemma needs to set up an event watch:

```python
from ollama_router import agent_command

# Regular command
agent_command(command="list")  # → list all agents

# Subscription
agent_command(subscribe="codex idle")  # → watch codex status + notify on idle
```

### Direct Call (Advanced)

```python
# Same interface in both files
result = agent_command(
    command="",
    subscribe="watch coder-1 activity"
)
# → Sets up agent.<coder_1_sid>.activity subscription
# → Returns: "('Subscribed to agent.<sid>.activity', 'info')" or error
```

## Integration Notes

### Bus Subscriber Context
This extension delegates to AgentConsole's `_session_bus_registrar`, which owns all subscription state. The registrar returns `(message, level)` tuples from Ollama's perspective:

- **info**: Subscription successful
- **warn**: Agent not running / name ambiguous
- **error**: Agent bus unavailable / technical failure

### Keyword Matching
The parser uses substring matching (case-insensitive) to determine topics. Examples that **all work**:

- `"subscribe codex status"` → `agent.<sid>.status`
- `"watch codex for idle"` → `agent.<sid>.status` + `"Worker is idle..."`
- `"tell me when codex fails"` → Uses "fail" heuristic if no status keyword found
- `"reactive action failed"` → `event.reactive.failed`

### Fallback Behavior
If a worker name is mentioned but not found, returns error rather than subscribing to `agent.*.status`. This prevents silent mismatches.

## Testing

No automated tests yet (Phase 7 integration pending test harness). Manual smoke tests:

```bash
cd Bridge/ollama

# Test basic resolution
python3 -c "
from ollama_chat import _resolve_worker_sid, _parse_subscribe_description
print(_resolve_worker_sid('codex'))  # Should return SID if alive
print(_parse_subscribe_description('watch codex status'))  # Should return topics + prompt
"

# Test with running Ollama + AgentConsole
python ollama_chat.py
# Type: "subscribe me to codex status"
# Should trigger agent_command(subscribe="subscribe me to codex status")
```

## Known Limitations

1. **No deduplication**: If Gemma calls `agent_command(subscribe="...")` twice in quick succession, both subscriptions will be created (registrar will replace, not merge).

2. **Worker name ambiguity**: If `"coder"` matches both `"coder-1"` and `"coder-review"`, the first match in `list` output wins. Use full SID if ambiguity is likely.

3. **Prompt templates**: Hardcoded to a generic feedback template. Custom templates require a new wrapper function.

4. **No subscription management**: The `subscribe` param only creates new subscriptions. To clear/update existing ones, call `agent_command(command="...")` with an explicit unsubscribe command (future feature).

## Future Work

- **Intent Learning**: `intent_router.py` could learn common subscription patterns (`"watch codex"` → `subscribe codex status`) and route them automatically.
- **Subscription UI**: Command Center could expose a "subscriptions" panel showing active watches.
- **Template Builder**: LLM-driven custom prompt templates (`"Tell me {payload.activity} when done"` → auto-validate placeholders).
- **Batch Subscribe**: Multi-worker watches in one call (e.g., `"watch all agents for failure"`).

## Files

- `/Bridge/ollama/ollama_chat.py` — extended
- `/Bridge/ollama/ollama_router.py` — extended
- `/Bridge/ollama/SUBSCRIPTION_EXTENSION.md` — this document
