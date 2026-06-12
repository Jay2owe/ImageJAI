# Plan: Enhanced Slash Commands for Ollama Chat

This document outlines the plan to implement a robust `/command` system for the local Ollama Gemma 4 agent in `agent_console/ollama/ollama_chat.py`. This will bring its user experience closer to tools like Claude Code and the Gemini CLI.

## 1. Core Objectives

- Introduce high-utility slash commands (`/help`, `/compact`, `/prompt`, `/save`, `/copy`).
- Implement command auto-completion (tab-completion) for slash commands.
- Maintain the lightweight, fast nature of the existing `ollama_chat.py` script.

## 2. Command Implementations

### `/help`
- **Purpose:** Provide a quick reference for all available commands.
- **Implementation:** A simple print block listing commands and their descriptions.

### `/compact`
- **Purpose:** Summarize the conversation history and clear the context window to save KV cache memory and prevent context overflow.
- **Implementation:**
  1. Append a summarization prompt to the current `messages` array.
  2. Execute a `chat_turn` (without tools to ensure it just generates text).
  3. Clear the `messages` array.
  4. Re-insert the system prompt and append the generated summary as a starting assistant message.
  5. Reset `ctx_used` to 0.

### `/prompt <name>` (or `/macro`)
- **Purpose:** Load predefined prompt templates from a directory (e.g., `.ollama/commands/` or `agent_console/ollama/commands/`) and inject them into the chat.
- **Implementation:**
  1. Parse the command argument (the `<name>`).
  2. Look for `<name>.md` or `<name>.txt` in the designated commands directory.
  3. If found, read the file contents.
  4. Automatically populate the chat input or submit it immediately as the user's message.

### `/save <filename>`
- **Purpose:** Save the last assistant response (or the entire conversation history) to a markdown file, useful for extracting generated code or notes.
- **Implementation:**
  1. Extract the `<filename>` argument.
  2. Retrieve the last assistant message from the `messages` array.
  3. Write the content to the specified file path (defaulting to the current working directory or a specific `Second Brain` directory).

### `/copy`
- **Purpose:** Copy the last assistant response directly to the system clipboard.
- **Implementation:**
  1. Retrieve the last assistant message.
  2. Use a cross-platform clipboard approach. Since this project runs on Windows, we can use `subprocess.run(['clip.exe'], input=text.encode('utf-16le'))` or a library like `pyperclip` if it's already in the project's dependencies.

## 3. Command Auto-completion (Autofill)

To support typing `/co` and pressing `Tab` to see or complete `/compact` or `/copy`, we need an interactive input loop that supports completion.

**Implementation Options:**
- **Option A: `prompt_toolkit` (Recommended)**
  - `prompt_toolkit` is highly robust across platforms (including Windows) and supports advanced features like inline auto-completion menus, syntax highlighting, and history.
  - *Integration:* Replace the standard `input()` call with `prompt_toolkit.prompt()`, passing a `WordCompleter` populated with our slash commands (`['/help', '/clear', '/compact', '/prompt', '/save', '/copy', '/tools', '/forget', '/status', '/setup']`).
- **Option B: `readline` module**
  - While standard on Unix, `readline` requires `pyreadline3` on Windows. It allows setting a completer function and binding the `Tab` key.
  - *Integration:* Import `readline`, configure `readline.set_completer()`, and keep using standard `input()`.

*Decision:* The plan favors **Option A (`prompt_toolkit`)** if it is an acceptable dependency for the agent console, as it provides a vastly superior CLI experience (dropdown menus for completion). If external dependencies are strictly limited, **Option B (`readline` / `pyreadline3`)** will be used as the fallback.

## 4. Execution Steps (When Ready)

1.  **Refactor Input Loop:** Update `ollama_chat.py` to use the chosen auto-completion library for the main `user_input = input(...)` prompt.
2.  **Add Command Dispatcher:** Extend the existing `if user_input.lower() == ...` block in the `main` loop to handle the new commands.
3.  **Implement Logic Handlers:**
    - Add the `_compact_history()` helper function.
    - Add the `_load_prompt_macro()` helper function.
    - Add the `_save_to_file()` helper function.
    - Add the `_copy_to_clipboard()` helper function.
4.  **Update `/help`:** Ensure the `/help` command is updated to reflect all new additions.
5.  **Testing:** Run `ollama_chat.py` and verify that typing `/` triggers auto-completion and that each command executes correctly without crashing the REPL loop.