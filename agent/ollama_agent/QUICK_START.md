# Bus Subscription Quick Start

## What's New

The Ollama wrappers now support **subscribing to agent/event bus topics via natural language**.

## Function Signature

```python
def agent_command(command: str, subscribe: str = "") -> str:
    """Send a command OR set up a subscription."""
```

## How to Use

### Regular Commands (Backward Compatible)
```python
agent_command("list")                    # List all agents
agent_command("spawn claude")            # Spawn an agent
agent_command("send 'hello' to codex")   # Send input
```

### Subscriptions (New)
```python
# Watch a specific worker
agent_command(subscribe="codex status")              # Status changes
agent_command(subscribe="codex idle")                # Idle + prompt
agent_command(subscribe="coder-1 activity")         # Activity updates

# Watch all agents
agent_command(subscribe="agent presence")            # Any agent connect/disconnect
agent_command(subscribe="agent status")              # Any agent status change

# Watch system events
agent_command(subscribe="reactive failure")          # Reactive workflow failures
agent_command(subscribe="event")                     # All events
```

## What Happens

1. **Parse**: Description → topics + prompt template
2. **Resolve**: Worker name → session ID (via `list`)
3. **Subscribe**: Calls `_session_bus_registrar.subscribe_session(...)`
4. **Return**: Registrar's response message

## Supported Patterns

| Description | Topics | Auto-Prompt |
|---|---|---|
| `"codex status"` | `agent.<sid>.status` | "Worker {payload.status}..." |
| `"codex idle"` | `agent.<sid>.status` | "Worker is idle..." |
| `"codex activity"` | `agent.<sid>.activity` | "{payload.description}" |
| `"codex presence"` | `agent.<sid>.presence` | "Agent {payload.status}." |
| `"codex lifecycle"` | `agent.<sid>.lifecycle` | (empty) |
| `"any status"` | `agent.*.status` | "Worker {payload.status}..." |
| `"reactive failure"` | `event.reactive.failed` | "Reactive failure: {payload.reason}" |
| `"event"` | `event.*` | (empty) |

## Error Handling

```python
result = agent_command(subscribe="nonexistent status")
# → "ERROR: Worker 'nonexistent' not found. Try: list"

result = agent_command(subscribe="codex idle")
# → "('Subscribed to agent.<sid>.status', 'info')"  [if found]
# → "('ERROR: Agent bus unavailable', 'error')"     [if bus down]
# → "('ERROR: Agent not running', 'warn')"          [if agent offline]
```

## In Gemma Tool Calling

When Gemma wants to watch an agent, it calls:

```python
agent_command(
    command="",
    subscribe="tell me when codex finishes"
)
```

Gemma sees the response (success/error) and knows whether the watch is active.

## Integration Notes

- **Backward compatible**: Old code still works
- **Async**: Subscriptions don't block; return immediately
- **Commander-focused**: Subscriptions always route to `commander.session_id`
- **Auto-submit**: Events are auto-submitted with `submit_system_events=True`

## Debugging

Check what topics a description will map to:

```python
from ollama_chat import _parse_subscribe_description

desc = _parse_subscribe_description("codex idle")
print(desc)
# {'topics': ['agent.<codex>.status'], 'worker': 'codex',
#  'prompt_template': 'Worker is idle. Check output and decide.'}
```

Resolve a worker name to SID:

```python
from ollama_chat import _resolve_worker_sid

sid = _resolve_worker_sid("codex")
print(sid)  # → "abc123-def456..." (or "" if not found)
```

## Files

- `ollama_chat.py` - Full wrapper with tools + subscriptions
- `ollama_router.py` - Intent router with subscriptions
- `IMPLEMENTATION_SUMMARY.md` - Full technical details
- `SUBSCRIPTION_EXTENSION.md` - Architecture & limits

---

**TL;DR**: Call `agent_command(subscribe="worker status")` to watch agents. Plain English → auto-subscribed.
