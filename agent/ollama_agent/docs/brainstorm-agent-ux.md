# Brainstorm: Transforming Ollama Chat into an Agent Experience

**Date:** 2026-04-09
**Topic:** How to make ollama_chat.py feel more like Claude Code / Codex and improve efficiency
**Method:** 4 parallel research agents (Landscape, Technical Feasibility, Creative Architect, Domain)

---

## The Landscape

The "local LLM agent REPL" space has exploded. The most relevant projects:

- **Goose** (Block, 27K stars) -- closest analog. Session-based agents with sandbox policies and approval modes, Ollama support, MCP integration.
- **OpenCode** -- open-source Claude Code clone that explicitly supports Ollama. Requires 64K context minimum.
- **mcp-client-for-ollama** -- the most feature-complete Ollama agent TUI: iterative tool execution, human-in-the-loop approval, streaming, thinking mode, configurable loop limits.
- **smolagents** (HuggingFace, 26K stars) -- agents write executable Python instead of JSON tool calls, using 30% fewer LLM calls. Worth studying.

What makes Claude Code feel agent-like: it's literally a **React app in the terminal** (Ink/Yoga layout engine), with streaming tokens, live tool visualization, permission prompts, context compaction at 70% capacity, and diff rendering. Codex adds Rust-native speed, `@` fuzzy file search, interrupt+queue (type while the model runs), and sandbox policies.

**Critical finding:** Ollama's native API now supports **streaming with tool calls** (fixed May 2025). The `stream: false` constraint is no longer necessary. This is the single biggest unlock.

---

## Technical Approaches

| Approach | Effort | Impact | Works at 4B? |
|----------|--------|--------|--------------|
| `OLLAMA_FLASH_ATTENTION=1` + `OLLAMA_KV_CACHE_TYPE=q8_0` | 2 min (env vars) | 10-30% faster, 50% less KV memory | Yes |
| Bump `num_ctx` to 8192 | 1 min | 2x conversation length | Yes |
| Add 2-3 few-shot examples to system prompt | 10 min | 15-40% better tool accuracy | Yes |
| Switch REPL to `stream=True` | 30 min | Perceived latency drops to first-token time | Yes |
| Tool argument validation (check device/scene names) | 30 min | Eliminates bad calls reaching hardware | Yes |
| Sliding window context pruning at 70% capacity | 1 hr | Prevents context overflow | Yes |
| Embedding-based pre-routing (`ollama.embed()`) | 2-3 hr | Skip LLM for high-confidence matches (~200ms vs ~800ms) | Yes |
| Truncate tool results to 300 chars in message history | 15 min | Saves context tokens | Yes |
| Temperature 0.0 for router, 0.2 for REPL | 5 min | More deterministic dispatch | Yes |
| `prompt_toolkit` for input (up-arrow, autocomplete) | 20 min | Readline history, tab-complete commands | N/A |
| `rich` library for markdown rendering | 15 min | Formatted tables, panels, syntax highlighting | N/A |

**Key constraint:** KV cache reuse requires byte-for-byte prefix matching. The system prompt must be **completely static** -- any dynamic content (timestamps, state) must go at the END of messages, never in the system prompt itself.

**Key pitfall confirmed:** 4B models have a bias toward emitting tool calls even for conversational prompts. The `_is_meaningful_response()` cop-out filter is a correct mitigation. This is a known Gemma 4 e4b issue reported across multiple projects.

---

## Ideas (Ranked)

### Tier 1 -- Build First (high impact, reasonable complexity)

#### 1. Streaming Token Output
**Impact: 8 | Feasibility: 9**

The blocker is gone. Switch to `stream=True` on the native `ollama.chat()` API. Tokens appear character-by-character, and tool calls are still delivered. Replace the spinner with live token rendering. This is the single biggest UX upgrade -- it transforms dead-time into visible intelligence. For voice/TTS, buffer until sentence boundaries before speaking.

#### 2. Expand Tool Surface from 6 to 16
**Impact: 9 | Feasibility: 9**

10 missing capabilities accessible via existing TCP services:

| New Tool | TCP Port | Command | What it unlocks |
|----------|----------|---------|-----------------|
| `query_events(count)` | 7751 | `events N` | "What happened recently?" |
| `manage_automations(action, name)` | 7751 | `automations/enable/disable` | "Enable the bedtime automation" |
| `create_scene(name, actions)` | 7751 | `add_scene <json>` | Scene Composer (idea #5) |
| `manage_macros(action, name)` | 7751 | `record/stop/play/macros` | "Record what I'm doing" |
| `push_event(type, data)` | 7751 | `event <type> <json>` | Trigger automations manually |
| `who_is_home()` | 7750 | `who` | "Is anyone home?" |
| `set_house_state(state)` | 7750 | `set_state <STATE>` | "Set the house to guest mode" |
| `smart_auto(command)` | Direct | `handle_command()` | Focus, DND, reminders, lab notes, alarms |
| `service_health()` | Watchdog | `check_all()` | "Are all services running?" |
| `read_context(source)` | Disk | File read | "What did I do today?" / "Read my notes" |

**Caveat:** 15+ tools causes JSON structure collapse in small models. Solution: **dynamic tool loading** -- only inject the 6-8 most relevant tools per turn based on the query, not all 16. Use embedding similarity or keyword matching to select which tools to present.

#### 3. Undo/Rollback Stack
**Impact: 8 | Feasibility: 8**

Every tool call logs to a stack with pre-computed inverse. `control_device(yeelight, on)` -> undo = `control_device(yeelight, off)`. Scene triggers expand from `scenes.json` and record each sub-action's inverse. "Undo" or "undo the last 3 things" unwinds the stack. Critical for voice where misrecognition triggers wrong commands. The model just calls `undo_last(count)` -- inverse computation is deterministic Python.

#### 4. Dynamic System Prompt with Context Injection
**Impact: 8 | Feasibility: 9**

The current system prompt is static and hardcodes device/scene names. Enrich it dynamically (but put dynamic content at the END to preserve KV cache for the static prefix):

```
[static prefix - same every call, cached]
You are a home automation assistant...
RULES: ...
AVAILABLE SCENES: ...
AVAILABLE DEVICES: ...

[dynamic suffix - changes per call, not cached but minimal]
CURRENT STATE: house=ACTIVE, time=23:10, phones_home=Jamie
RECENT: bedtime automation fired 5min ago
```

This gives the model awareness of time, presence, and recent events without an extra tool call.

---

### Tier 2 -- Build Next (transformative but complex)

#### 5. Scene Composer ("Remember this as reading mode")
**Impact: 9 | Feasibility: 7**

The scene engine already supports `add_scene <json>` via TCP. The agent needs:
1. A `snapshot_devices()` tool that queries all reachable devices for current state
2. The ability to call `add_scene` with the snapshot formatted as scene JSON
3. The model recognizing "save this as X" intent (easy for 4B)

The Python layer constructs the scene JSON from the snapshot -- don't ask the 4B model to emit valid JSON.

#### 6. Proactive Time-Aware Suggestions
**Impact: 8 | Feasibility: 9**

A background thread checks conditions every 60s (Python heuristics, no model involved):
- 11pm + house ACTIVE -> suggest bedtime
- Kettle-plug on > 30 min -> suggest off
- Phone leaves network -> suggest leaving scene

Appears as a subtle line above the prompt. Suggestion fatigue mitigation: if ignored 3x, suppress for the session. For voice/Telegram, send proactively.

#### 7. Embedding-Based Pre-Routing
**Impact: 7 | Feasibility: 8**

New routing tier: `regex -> embedding match -> Ollama/Gemma -> Gemini -> Claude`

Pre-embed all tool descriptions + trigger phrases using `ollama.embed()`. For each user input, embed and cosine-match. If similarity > 0.85, skip the LLM entirely and call the tool directly (~200ms vs ~800ms). This is the most architecturally significant change -- adds a zero-LLM-cost tier.

#### 8. Parallel Tool Execution
**Impact: 7 | Feasibility: 8**

Gemma 4 supports parallel tool calls natively. When `msg.tool_calls` has multiple entries for different devices, fire them simultaneously with `ThreadPoolExecutor`. Show live progress:
```
  [running] control_device(yeelight, off)
  [done]    control_device(kettle-plug, off) -> OK
  [done]    control_device(yeelight, off) -> OK
```

Cuts multi-device commands from 8s (sequential) to 2-3s.

---

### Tier 3 -- Stretch Goals (experimental, high-risk/high-reward)

#### 9. Event Bus Subscriber Mode
**Impact: 8 | Feasibility: 7**

Subscribe to the scene engine's event bus and display real-time events interleaved with chat. Inject last 5 events into context so the model can answer "why did the lights turn off?" Edge case: event flood during device flapping needs rate limiting.

#### 10. Conversation Memory + Self-Improving Prompts
**Impact: 7 | Feasibility: 8**

Log every command-response pair to JSONL. Track tool call success/failure rates in `tool_stats.json`. Inject failure avoidance knowledge into the system prompt dynamically: "Note: midea (AC) is often unreachable." Add a `/stats` command showing success rates and most-used tools.

#### 11. Dry-Run Sandbox
**Impact: 7 | Feasibility: 9**

`/dryrun` or prefix with `?` to intercept tool calls and show what *would* happen without executing. Mock TOOL_MAP returns synthetic "OK". Critical safety feature when testing compound commands or new learned tools.

#### 12. Voice-Optimized Response Shaping
**Impact: 7 | Feasibility: 9**

Post-process responses before TTS: strip ANSI/markdown, replace device IDs with human names from `devices.json` ("yeelight" -> "the ceiling light"), collapse lists into natural sentences, translate errors ("ERROR: port 7750 not reachable" -> "I couldn't reach the network monitor").

---

### Bonus: Wild Ideas

#### 13. Ambient Autonomous Mode ("Ghost in the Machine")
**Impact: 10 | Feasibility: 5**

User defines rules in natural language: "if the kettle is still on after 20 minutes, turn it off." Rules stored in `ambient_rules.json`, evaluated by a Python loop every 30-60s. **Not** fully autonomous AI -- a natural-language front end to the existing automation engine. Safety rails: no shutdown allowed, max fire rate per rule, all actions logged and undoable. The evaluation loop is Python; only rule *creation* from natural language needs the model.

#### 14. smolagents-Style Code-as-Action
Instead of JSON tool calls, have the model emit Python code that calls your tool functions directly. HuggingFace research shows this uses 30% fewer LLM calls. Risky at 4B scale but interesting.

#### 15. MCP Server Mode
Expose the Ollama agent as an MCP server so Claude Code, Codex, or other agents can use it as a tool. Composability across the routing chain.

---

## Recommended Starting Point

### Phase 1 (afternoon of work)
1. Set env vars: `OLLAMA_FLASH_ATTENTION=1`, `OLLAMA_KV_CACHE_TYPE=q8_0`
2. Bump `num_ctx` to 8192
3. Add 2-3 few-shot examples to `_SYSTEM_PROMPT`
4. Switch REPL to `stream=True`
5. Add tool argument validation

### Phase 2 (1-2 days)
1. Expand tools from 6 to ~12 (events, automations, who_is_home, house state, smart auto commands)
2. Add undo/rollback stack
3. Dynamic system prompt with house state injection
4. `prompt_toolkit` for input + `rich` for output formatting

### Phase 3 (next week)
1. Scene Composer
2. Proactive suggestions
3. Embedding-based pre-routing
4. Parallel tool execution

**Fix the router/chat asymmetry first** -- `ollama_router.py` (used by voice + Telegram) has NO learned tools support, while `ollama_chat.py` does. Any tools the user teaches in the REPL are invisible to voice and Telegram. This is a high-priority gap.

---

## Key Risks and Open Questions

1. **Tool count scaling** -- Adding tools is the highest-impact change, but 15+ tools causes JSON collapse in 4B models. Dynamic tool selection (embed-match which tools to inject per query) is the mitigation but adds complexity.

2. **Streaming + tool calls verification** -- Ollama fixed this for native API but NOT for OpenAI compat layer. Needs testing with the specific Ollama version and Gemma 4 e4b.

3. **Context window management** -- Bumping to 8192 helps, but with 12+ tools the definitions alone could eat 1-2K tokens. Sliding window pruning + tool result truncation are essential.

4. **4B model ceiling** -- Multi-step plan decomposition, self-reflection, and task decomposition don't work reliably at 4B. These should trigger cloud escalation rather than being forced locally.

5. **KV cache invalidation** -- Any dynamic content in the system prompt prefix breaks cache reuse. The dynamic context injection must be structured carefully (static prefix, dynamic suffix).

---

## Summary Matrix

| # | Idea | Feasibility | Impact | Complexity | Best for 4B? |
|---|------|:-----------:|:------:|:----------:|:------------:|
| 1 | Streaming token output | 9 | 8 | Medium | Yes |
| 2 | Expand tool surface (6->16) | 9 | 9 | Medium | Yes (with dynamic loading) |
| 3 | Undo/rollback | 8 | 8 | Medium | Yes |
| 4 | Dynamic system prompt | 9 | 8 | Low | Yes |
| 5 | Scene composer | 7 | 9 | Med-High | Partial |
| 6 | Proactive suggestions | 9 | 8 | Medium | N/A (Python) |
| 7 | Embedding pre-routing | 8 | 7 | Medium | Yes |
| 8 | Parallel tool execution | 8 | 7 | Medium | N/A (Python) |
| 9 | Event bus subscriber | 7 | 8 | Med-High | Yes |
| 10 | Self-improving prompts | 8 | 7 | Low | Yes |
| 11 | Dry-run sandbox | 9 | 7 | Low | Yes |
| 12 | Voice-optimized responses | 9 | 7 | Low | N/A (Python) |
| 13 | Ambient autonomous mode | 5 | 10 | Very High | Partial |
| 14 | Code-as-action | 4 | 6 | High | No |
| 15 | MCP server mode | 6 | 5 | High | N/A |

---

## Sources

- [Goose - Block's open source AI agent](https://github.com/block/goose)
- [OpenCode CLI with Ollama integration](https://opencode.ai/docs/providers/)
- [Open Interpreter](https://github.com/openinterpreter/open-interpreter)
- [Aider - AI pair programming](https://github.com/Aider-AI/aider)
- [smolagents by HuggingFace](https://github.com/huggingface/smolagents)
- [mcp-client-for-ollama](https://github.com/jonigl/mcp-client-for-ollama)
- [Google ADK Ollama integration](https://google.github.io/adk-docs/agents/models/ollama/)
- [Ollama structured outputs](https://ollama.com/blog/structured-outputs)
- [Gemma 4 function calling guide](https://ai.google.dev/gemma/docs/capabilities/function-calling)
- [Rich Python library](https://github.com/Textualize/rich)
- [prompt_toolkit](https://github.com/prompt-toolkit/python-prompt-toolkit)
