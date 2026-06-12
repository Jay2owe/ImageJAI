# Stage 3 — Wrapper Architecture (Spike Report)

**Status:** spike complete. All eight cross-provider tests pass against
mocked SDKs. Spike code at `agent/providers/_spike/`.

## 1. The problem

Today, `agent/ollama_agent/ollama_chat.py` calls `ollama.chat(...)`
directly and lets the Ollama Python client introspect Python callables,
dispatch tool calls, and thread messages. Multi-provider needs a thin
abstraction hiding three SDK shapes (LiteLLM Proxy, native Anthropic,
native Gemini) behind one client object the existing tool loop uses.

## 2. Routing diagram

```
                       ┌──────────────────────────────┐
                       │  ollama_chat.py tool loop    │
                       │  (messages, tools, run)      │
                       └──────────────┬───────────────┘
                                      │
                          router.get_client(provider, model)
                                      │
            ┌─────────────────────────┼──────────────────────────┐
            ▼                         ▼                          ▼
   ┌──────────────────┐    ┌────────────────────┐    ┌──────────────────────┐
   │ LiteLLMProxy     │    │ AnthropicNative    │    │ GeminiNative         │
   │ Client           │    │ Client             │    │ Client               │
   │ openai SDK →     │    │ anthropic SDK →    │    │ google-genai SDK →   │
   │ localhost:4000   │    │ api.anthropic.com  │    │ generativelanguage.. │
   └──────────┬───────┘    └─────────┬──────────┘    └──────────┬───────────┘
              ▼                      ▼                          ▼
      Groq / Cerebras /        Claude (Opus,             Gemini 2.5 Pro,
      OpenRouter / GitHub      Sonnet, Haiku)            2.5 Flash, …
      Models / Mistral /
      OpenAI / Ollama Cloud
```

Most providers go through the proxy. The two native paths exist because
Anthropic's `tool_use` / `tool_result` content blocks and Gemini's
`function_call` parts carry richer typing than the OpenAI translation
LiteLLM produces. Decision on Ollama: §7.

## 3. Common interface

`agent/providers/_spike/base.py` defines:

```python
@dataclass
class ToolCall:
    id: str            # provider-supplied call id (used for result threading)
    name: str          # tool function name
    args: dict         # already-parsed kwargs for TOOL_MAP[name](**args)

class ProviderClient(ABC):
    def chat(self, messages, tools, model, **opts): ...
    def extract_text(self, response) -> str: ...
    def extract_tool_calls(self, response) -> list[ToolCall]: ...
    def append_assistant(self, messages, response) -> None: ...
    def append_tool_result(self, messages, call, result) -> None: ...
```

Five operations cover everything the loop needs. Body becomes
provider-agnostic:

```python
resp = client.chat(messages, ALL_TOOLS, model)
client.append_assistant(messages, resp)
calls = client.extract_tool_calls(resp)
if not calls:
    return client.extract_text(resp)
for tc in calls:
    result = TOOL_MAP[tc.name](**tc.args)
    client.append_tool_result(messages, tc, result)
```

## 4. Tool-schema conversion

Each provider wants a different tool-definition shape. All three accept
JSON-Schema for the parameter object, so we build *one* schema per Python
callable and wrap it three ways. Lives in `base.py`.

### 4.1 Does LiteLLM auto-convert callables?

**No.** Tested empirically. The Ollama Python client is unique in
introspecting Python callables (`inspect.signature` + docstring). Every
other client (`openai`, `anthropic`, `google.genai`) requires a
fully-built schema dict.

`litellm.utils.function_to_dict` exists but: (1) we point the **OpenAI
SDK** at the proxy, not litellm directly — the proxy sees an
already-converted schema; (2) `function_to_dict` is undocumented and
brittle (no `Optional`/`Union` last we checked).

So we own the conversion. `fn_to_json_schema(fn)` produces a generic
JSON-Schema fragment; three thin wrappers adapt it to each provider's
envelope.

### 4.2 The converter

```python
def fn_to_json_schema(fn):
    sig = inspect.signature(fn)
    arg_docs = _parse_arg_docs(fn.__doc__ or "")
    resolved = typing.get_type_hints(fn)   # PEP-563 safe
    properties, required = {}, []
    for pname, param in sig.parameters.items():
        if pname in ("self", "cls"): continue
        ann = resolved.get(pname, param.annotation)
        prop = _annotation_to_jsonschema(ann)
        if pname in arg_docs:
            prop["description"] = arg_docs[pname]
        properties[pname] = prop
        if param.default is inspect.Parameter.empty:
            required.append(pname)
    return {"name": fn.__name__,
            "description": _summary_line(fn.__doc__ or ""),
            "schema": {"type": "object",
                       "properties": properties,
                       "required": required}}
```

Two non-obvious bits:

- `typing.get_type_hints(fn)` resolves PEP-563 stringified annotations.
  Without it, any module using `from __future__ import annotations`
  yields literal strings as `param.annotation` and the converter falls
  through to `{"type": "string"}` for everything.
- Gemini rejects empty `properties: {}`. `to_gemini_tool` omits the
  `parameters` key entirely for zero-arg tools.

### 4.3 The three wrappers

```python
def to_openai_tool(fn):                         # LiteLLM path
    s = fn_to_json_schema(fn)
    return {"type": "function",
            "function": {"name": s["name"],
                         "description": s["description"],
                         "parameters": s["schema"]}}

def to_anthropic_tool(fn):                      # native path
    s = fn_to_json_schema(fn)
    return {"name": s["name"],
            "description": s["description"],
            "input_schema": s["schema"]}

def to_gemini_tool(fn):                         # native path
    s = fn_to_json_schema(fn)
    decl = {"name": s["name"], "description": s["description"]}
    if s["schema"]["properties"]:
        decl["parameters"] = s["schema"]
    return decl
```

## 5. Per-path findings

### 5.1 LiteLLM Proxy (openai SDK)

- `client.chat.completions.create(messages=..., tools=..., model=...)`.
- Tool calls in `response.choices[0].message.tool_calls[i]`, each with
  `.id`, `.function.name`, **`.function.arguments`** — that last is a
  **JSON-encoded string**, not a dict. Parse inside `extract_tool_calls`.
- Reply threading: append the assistant message back as a dict with the
  `tool_calls` field preserved, then a `{"role":"tool", "tool_call_id":
  ..., "content": ...}` per result.
- Do NOT append the SDK's pydantic message object — pydantic
  serialises through unknown-field validation on the next call and
  silently drops fields. Always re-build as a plain dict.

### 5.2 Anthropic native (anthropic SDK)

- `client.messages.create(messages=..., tools=..., model=..., max_tokens=...)`.
- `max_tokens` is **mandatory**. Spike defaults to 4096; production
  pulls per-model values from Stage 4.
- `system=` is a top-level kwarg, not a `role: "system"` message. Pull
  system entries out of `messages` and pass via `system=`.
- Response shape: `response.content` is a **list of blocks**. A single
  response can contain text, tool_use, and (with extended thinking) thinking
  blocks, in any order.
- Tool-use threading is fiddly:
  - Assistant turn must be re-appended with the **whole content block
    list intact**. Anthropic rejects a tool_result whose `tool_use_id`
    is not present in the immediately-prior assistant message.
  - Tool result is delivered as a `role: user` message carrying a
    `{"type":"tool_result", "tool_use_id": ..., "content": ...}` block.
    Field name is `tool_use_id`, not `tool_call_id` — that's an OpenAI-ism.

### 5.3 Gemini native (google-genai SDK)

- `client.models.generate_content(model=, contents=, config=)`. Tools
  and system prompt live inside `config` (a `GenerateContentConfig`),
  not as top-level kwargs. Easy to miss.
- The SDK has an "automatic function calling" path that runs Python
  callables for you and threads results internally. **We disable it**
  (`AutomaticFunctionCallingConfig(disable=True)`) so our loop stays in
  charge of dispatch — parity with the other paths, failures stay
  debuggable in our own code.
- Roles in Gemini history: `user` and `model`, not `assistant`.
  Translate at the boundary inside `_messages_to_contents`.
- **No native tool-call ids.** Gemini matches calls↔results by name +
  position. We synthesise an id (`f"{name}:{idx}"`) in the ToolCall to
  keep the interface uniform; the function_response part only needs
  the name to match.
- `response.text` concatenates text parts, returns "" if response was
  function calls only. Cheap.

### 5.4 Cross-path parity

`test_all_three_paths_emit_same_normalised_tool_call` asserts the same
`multiply(6, 7)` call coming from any of the three SDKs ends up as the
same `ToolCall(name="multiply", args={"a":6, "b":7})`. This is the
contract that lets the loop body stay shared.

## 6. Migration: line-numbered changes to `ollama_chat.py`

Existing tool-loop call sites:

- `_run_tool_loop` (lines 1300–1337) — route() entry, voice/Telegram.
- `chat_turn` (lines 1916–1995) — interactive REPL.

### 6.1 Imports

| Line | Today | After |
|---|---|---|
| 31 | `import ollama` | kept; still used for `ollama.show()` ctx-bar query and `ollama.chat()` keep-alive eviction at line 1820 |
| (new, near 31) | — | `from agent.providers._spike import router` (or, post-spike, `agent.providers`) |

### 6.2 `_run_tool_loop` (lines 1300–1337)

```diff
 def _run_tool_loop(model: str, text: str) -> ...:
     messages = [{"role": "user", "content": text}]
+    client = router.get_client(_provider_for(model), model)
     try:
         for _ in range(MAX_ROUNDS):
-            resp = ollama.chat(
-                model=model,
-                messages=messages,
-                tools=ALL_TOOLS,
-                stream=False,
-                keep_alive="5m",
-                options={"temperature": 0.2, "num_predict": 256},
-            )
-            msg = resp.message
-            messages.append(msg)
-            if not msg.tool_calls:
-                content = (msg.content or "").strip()
+            resp = client.chat(messages, ALL_TOOLS, model,
+                               temperature=0.2)
+            client.append_assistant(messages, resp)
+            calls = client.extract_tool_calls(resp)
+            if not calls:
+                content = client.extract_text(resp)
                 if _is_meaningful_response(content):
                     return True, content, None
                 return False, "", None

-            for tc in msg.tool_calls:
-                name = tc.function.name
-                args = tc.function.arguments
-                if name not in TOOL_MAP:
-                    messages.append({"role": "tool",
-                                     "content": f"ERROR: unknown tool '{name}'"})
-                    continue
-                result = TOOL_MAP[name](**args)
-                messages.append({"role": "tool", "content": str(result)})
+            for tc in calls:
+                if tc.name not in TOOL_MAP:
+                    client.append_tool_result(messages, tc,
+                                              f"ERROR: unknown tool '{tc.name}'")
+                    continue
+                result = TOOL_MAP[tc.name](**tc.args)
+                client.append_tool_result(messages, tc, str(result))
```

Lines that **stay**: 1306 (initial user message), 1322–1324 (cop-out
detection), 1335 (max-rounds fallback), the surrounding exception
handler.

### 6.3 `chat_turn` (lines 1916–1995)

Same shape:
- 1921: drop `kwargs = {"model": model, ...}` (workaround for `ollama.chat`'s positional/keyword friction).
- 1934: replace `_ollama_chat_interruptible(**kwargs)` with `client.chat(messages, tools or [], model, ...)`. Keep `_ollama_chat_interruptible` as a generic worker-thread wrapper if Ctrl+C latency matters; it now wraps `client.chat`.
- 1937–1938: `msg = resp.message; messages.append(msg)` → `client.append_assistant(messages, resp)`.
- 1941: `if not msg.tool_calls:` → `calls = client.extract_tool_calls(resp); if not calls:`
- 1942: `content = (msg.content or "").strip()` → `content = client.extract_text(resp)`
- 1969–1983: `for tc in msg.tool_calls:` block → `for tc in calls:`, using `tc.name`/`tc.args`, replacing manual `messages.append({"role": "tool", ...})` with `client.append_tool_result(...)`.

### 6.4 Token-estimator (lines 1534–1549)

`_estimate_tokens` reaches into `m.tool_calls` for ollama-Message
objects. After migration, all entries are dicts. Replace the isinstance
branch with a single dict-walk:

```python
def _estimate_tokens(messages):
    total = 100
    for m in messages:
        if isinstance(m, dict):
            content = m.get("content")
            if isinstance(content, str):
                total += len(content)
            elif isinstance(content, list):     # Anthropic content blocks
                for b in content:
                    total += len(json.dumps(b))
            tcs = m.get("tool_calls") or m.get("function_calls")
            if tcs:
                total += len(json.dumps(tcs))
    return total // 4
```

### 6.5 Lines that should stay untouched

- 1801–1820 (keep-alive eviction) — provider-agnostic.
- 1507–1531 (`_get_ctx_limit`) — Ollama-specific; only invoked when active provider is Ollama. Other providers expose context windows differently (Stage 4).
- 1848–1873 (`_ollama_chat_interruptible`) — rename to `_chat_interruptible`, keep body. Just runs whatever callable in a worker thread with Ctrl+C cooperation.
- 1029–1036 (`ALL_TOOLS`, `TOOL_MAP`) — unchanged.
- 1110–1142 (`learn_new_tool`) — unchanged. Learned tools whose schemas are constructed manually (`_build_learned_tool_defs`) need a parallel converter; out of scope for the spike.

### 6.6 Estimated line delta

| Section | Today | After | Δ |
|---|---|---|---|
| Imports | 1 | 2 | +1 |
| `_run_tool_loop` | 38 | ~32 | −6 |
| `chat_turn` (loop body) | ~80 | ~70 | −10 |
| `_estimate_tokens` | 16 | 14 | −2 |
| (new) `_provider_for(model)` helper | 0 | ~10 | +10 |
| **Net** | | | **roughly flat (−7)** |

Migration is mostly a delete; the wrapper does what was inlined.

## 7. Decision: should Ollama keep its native path?

**Recommendation: route Ollama Local through the proxy; keep Ollama
Cloud through the proxy too. Drop the native ollama-python path entirely
in production.**

1. **Single shape is easier to reason about than two.** The current
   ollama-native path has its own message-mutation contract (the
   `messages.append(msg)` of an SDK Message object on line 1318) that
   does not match either of the new dicts-everywhere paths. Keeping
   Ollama on a third shape doubles the bug surface in
   `_estimate_tokens`, `chat_turn`, keep-alive logic.
2. **LiteLLM Proxy supports Ollama natively** as an OpenAI-compatible
   provider (Ollama exposes `/v1/chat/completions` since 0.1.30). Tool
   calling works for any model that supports it.
3. **Gemma's tool-calling quirks** (`agent/ollama_agent/CLAUDE.md`:
   single-purpose tools, docstring ambiguity, wrong-slot args) are a
   Gemma model issue, not a client-library issue. The OpenAI-translated
   path receives the same schema we already tuned for Gemma.
4. **Counter-argument:** ollama-python handles Python-callable
   introspection automatically. We pre-build schemas via `to_openai_tool`
   anyway (Anthropic and Gemini need it), so this isn't extra work — but
   we lose automatic update if a tool function's signature changes and we
   forget to rebuild the spec. **Mitigation:** build schemas at module
   import time from `ALL_TOOLS`; never cache them.

If wrong and Gemma tool-calling regresses on the OpenAI-translated
surface, re-introduce a one-off `OllamaNativeClient` (ollama-python with
`tools=ALL_TOOLS`) in <50 lines. Interface allows it.

## 8. Risks identified during spiking

| # | Risk | Where it bites | Mitigation |
|:-:|---|---|---|
| 1 | **PEP-563 stringified annotations** silently break the schema converter (every `int`/`bool` falls through to `string`). | Anywhere upstream turns on `from __future__ import annotations`. | `typing.get_type_hints(fn)`, not `param.annotation`. In spike. |
| 2 | **Anthropic rejects tool_result without preceding assistant tool_use.** Drop a content block when re-appending and you get a 400. | Future code filtering Anthropic content blocks (e.g. stripping `thinking`). | Always re-thread the FULL block list. Spike does. |
| 3 | **OpenAI tool-call arguments are JSON strings**, not dicts. | Loop dispatch will pass `**str` and crash. | Parse in `extract_tool_calls`. Done in `litellm_proxy.py`. |
| 4 | **Anthropic's `tool_use_id`** is not `tool_call_id`. | Code review, copy-paste. | Field-name mismatch fenced inside each client; unified `ToolCall.id` hides it. |
| 5 | **Gemini has no native tool-call ids**, only name+position. | Parallel calls to same name within one turn could ambiguously match. | Synthesise `f"{name}:{idx}"`; preserve order. |
| 6 | **Gemini rejects empty `parameters: {}`** for zero-arg tools. | No-arg tools work elsewhere but blow up only on Gemini. | `to_gemini_tool` drops the key. Spike test covers. |
| 7 | **Anthropic requires `max_tokens`.** Default OpenAI behaviour (no cap) raises 400. | First time someone runs the existing loop unchanged through Anthropic. | Default `max_tokens=4096`; per-model values in Stage 4. |
| 8 | **Pydantic message objects on re-send.** Appending `resp.choices[0].message` works for ollama-python but breaks OpenAI SDK (unknown-field validation drops `function_call_id`). | "I'll just append the SDK object" shortcut. | Always re-serialise to plain dict in `append_assistant`. |
| 9 | **`AutomaticFunctionCallingConfig`** in google-genai will run callables and trim from response. We disable it; if a future SDK release changes the default, our loop sees zero tool calls and silently terminates. | SDK upgrades. | Pin a regression test that asserts `extract_tool_calls` finds the call. Spike has this. |
| 10 | **Streaming**: spike is `stream=False` everywhere. Streaming delivers tool calls and final text as separate events; loop has to assemble. | Token-by-token UX. | Phase 2 of migration. |

## 9. Testing summary

```
$ python -m pytest agent/providers/_spike/test_spike.py -v
test_spike.py::test_schema_converter_openai PASSED
test_spike.py::test_schema_converter_anthropic PASSED
test_spike.py::test_schema_converter_gemini PASSED
test_spike.py::test_no_arg_tool_omits_parameters_for_gemini PASSED
test_spike.py::test_litellm_proxy_loop PASSED
test_spike.py::test_anthropic_native_loop PASSED
test_spike.py::test_gemini_native_loop PASSED
test_spike.py::test_all_three_paths_emit_same_normalised_tool_call PASSED
8 passed in 5.36s
```

The three `*_loop` tests each: (1) patch the SDK's HTTP method to return canned responses; (2) run the same loop with the same `multiply(a,b)` tool; (3) assert the loop calls `multiply(6, 7)`, gets 42, threads back, final response is text; (4) assert the wire-shape on the second call matches what the real API expects.

`test_all_three_paths_emit_same_normalised_tool_call` is the parity test — same normalised `ToolCall` from all three providers' raw shapes.

## 10. What the spike does NOT prove

- We did not hit a real LiteLLM Proxy. (The wire shape we send is OpenAI shape and is what the proxy expects. Risk is config-side.)
- No streaming.
- No tool-call retries / malformed JSON arguments / unknown tool name.
- No multi-turn parallel tool calls (single response with >1 tool_use). Anthropic and Gemini support this; spike loop dispatches sequentially within one turn but doesn't assert on the parallel case.

Deferred to production migration (Stage 7).
