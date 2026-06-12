# agent_console/ollama

Gemma 4 tool-calling wrapper spawned by `OllamaGemma4Adapter`. This is the **live** copy — changes here take effect on the next session spawn. `Bridge/ollama/` is a standalone CLI and is **not** what AgentConsole runs.

Everything about *what* the tools do, *which* models are supported, and *how* the Ollama API is invoked is already visible in `ollama_chat.py` and `ollama_router.py`. Derive from code. This file only covers things that aren't obvious from reading the source.

## Golden rules for adding features

**The model is Gemma 4 (4B local / 31B cloud). It is not Claude.**

1. **Single-purpose tools only.** A tool with two or more parameters will be called with wrong-slot arguments. If a feature needs a second axis (e.g. subscribe events *and* pick who gets notified), add a second tool, not a second parameter — or pair the second param with an extremely explicit docstring example and a safety-net that recovers from the expected failure mode.
2. **The docstring *is* the schema.** `ollama.chat(tools=[fn, ...])` introspects the Python signature and docstring; there is no separate JSON definition. Any ambiguity in the docstring becomes a behaviour bug. Lead with one-line purpose, list concrete example values, say "do NOT" when the model routinely gets it wrong.
3. **Learned tools are the exception.** `learned_tools.json` entries are wrapped by `_build_learned_tool_defs` because they have no Python signature to introspect.

## Gotchas the code does not announce

- **Do NOT `repr()` the exec code sent to `_ac_tcp("exec ...")`.** The orchestrator slices everything after `exec ` and runs `exec(that_string, namespace)`. Wrapping in quotes makes Python evaluate a string literal — the call silently no-ops and the server returns `OK` as if it worked. See `_setup_bus_subscription` for the canonical form.
- **`_ac_tcp` stops reading at the first newline.** If you need multi-line output from an exec call, encode it on a single line (e.g. `repr(result)`) or the tail is truncated.
- **The `list` command output is `#N [status] name (sid)`.** The `#N` is a display index, NOT a session ID. Use `_iter_list_entries()` to parse — never grab the first token.
- **Session IDs are hex in parentheses.** Display names (`agent-2`, `claude-1`, `jon`) contain dashes and must go through a name lookup; `_resolve_worker_sid` handles both cases but only falls through to "treat as raw SID" if the string matches `^[0-9a-f]{8}...`.
- **`AC_SESSION_ID` / `AC_AUTH_TOKEN` are only set when `session_manager.py` injects them.** The gate in `_start_session` must include `AgentType.OLLAMA` or "self"-subscription in gemma will fail silently with "no AC_SESSION_ID in env".
- **`stream=False` is required.** Ollama drops `tool_calls` in streaming mode for some combinations — do not flip this without testing tool dispatch end-to-end.

## Files

- `ollama_chat.py` — interactive REPL, tool loop, bus subscriptions, Ctrl+C handling.
- `ollama_router.py` — programmatic `route(text)` used by voice listener and Telegram.
- `__init__.py` — package marker (needed because this directory is imported as `agent_console.ollama`).

## Related docs

Deeper feature write-ups (subscription design, UX brainstorm, implementation history) live alongside this file. They're reference material, not instructions — skim if relevant, ignore otherwise.
