Here is the full request/response path for a normal `ij.py` command, using `python ij.py macro '...'` as the concrete example.

```text
AGENT / SHELL
  |
  |  python ij.py macro 'run("Measure");'
  v
agent/ij.py main()
  |
  |  parses CLI subcommand "macro"
  |  builds Python call: execute_macro(code)
  v
execute_macro(code)
  |
  |  builds TCP JSON:
  |  {"command": "execute_macro", "code": "..."}
  v
imagej_command(cmd)
  |
  |  1. If readonly command, attach if_none_match from local cache
  |  2. Attach IMAGEJAI_SESSION_ID / IMAGEJAI_MODEL_ENDPOINT if set
  |  3. Open TCP socket to localhost:7746
  |  4. Send one newline-terminated JSON object
  v
TCP socket
  |
  |  {"command":"execute_macro","code":"..."}\n
  v
TCPCommandServer.handleClient()
  |
  |  1. read one line
  |  2. reject empty / too large requests
  |  3. special-case subscribe streams
  |  4. otherwise call dispatch(rawJson, socket)
  v
TCPCommandServer.dispatch()
  |
  |  JsonParser.parseString(rawJson).getAsJsonObject()
  v
dispatchInternal(request, caps, socket)
  |
  |  1. read request["command"]
  |  2. choose AgentCaps:
  |     - DEFAULT_CAPS if no hello/caps on this socket
  |     - negotiated caps if hello established them on this socket
  |  3. call dispatchCore(command, request, caps, socket)
  v
dispatchCore()
  |
  |  token-auth gate if enabled
  |  if command == "execute_macro":
  v
handleExecuteMacro(request, caps)
  |
  |  1. validate "code"
  |  2. safe-mode checks:
  |     - destructive-operation scan
  |     - queue-storm guard
  |     - ROI auto-backup if needed
  |  3. record macro/provenance baselines:
  |     - image state
  |     - histogram
  |     - dialogs
  |     - graph
  |     - undo boundary if enabled
  |  4. suppress image.* events during fragile macro window
  |  5. publish macro.started on EventBus
  |  6. run macro inside Fiji/ImageJ:
  |     - IJ.runMacro / CommandEngine path
  |     - ImageJ WindowManager / ResultsTable / plugins mutate state
  |  7. watch for:
  |     - timeout
  |     - blocking dialogs
  |     - macro errors
  |     - new images
  |     - results table changes
  |  8. publish macro.completed / safe_mode.* / results.changed as needed
  |  9. compute response additions:
  |     - success/error
  |     - output
  |     - resultsTable
  |     - dialogs
  |     - stateDelta
  |     - histogramDelta
  |     - graphDelta
  |     - pulse if enabled
  v
successResponse(result) or errorResponse(error)
  |
  v
dispatchInternal() post-processing
  |
  |  1. promote any piggyback gui_action to top-level
  |  2. apply privacy/pseudonymisation filtering
  |  3. apply per-socket response dedup if eligible
  |  4. apply readonly hash dedup if eligible
  |  5. record failures to FrictionLog
  |  6. attach pattern hints if enabled
  |  7. append audit receipt row
  v
handleClient()
  |
  |  writeOutbound(writer, commandName, jsonResponse)
  |  newline-terminated JSON response
  |  close socket
  v
agent/ij.py imagej_command()
  |
  |  1. recv response bytes until newline/socket close
  |  2. json.loads(...)
  |  3. if readonly unchanged, hydrate from local cache
  |  4. update cache if response has hash
  |  5. return Python dict
  v
ij.py main()
  |
  |  print JSON / save capture / format special output
  v
AGENT SEES RESULT
```

The side-channel during that flow is the EventBus:

```text
handleExecuteMacro / ImageMonitor / DialogWatcher / JobRegistry / PostureController
  |
  |  eventBus.publish("macro.started", data)
  |  eventBus.publish("image.opened", data)
  |  eventBus.publish("dialog.appeared", data)
  |  eventBus.publish("safe_mode.blocked", data)
  v
EventBus singleton in Fiji JVM
  |
  |  matches topic patterns
  v
Internal subscribers
  |
  |  ReactiveEngine, SafeModeIndicator, VisualOverrideNotice, etc.
  v
Optional external TCP subscribers
  |
  |  python ij.py subscribe macro.* image.* dialog.*
  |  or imagej_events(["macro.*"])
  v
agent receives JSONL event frames
```

A few important wrinkles:

```text
CLI command != TCP command
python ij.py script      -> {"command": "run_script", ...}
python ij.py ui list     -> {"command": "interact_dialog", "action": "list_components"}
python ij.py rois        -> {"command": "get_roi_state"}
python pixels.py cells   -> {"command": "get_pixels"} then local Python analysis
```

`subscribe` is special:

```text
{"command": "subscribe", "topics": ["image.*"]}
```

does not go through normal `dispatchCore()`. `handleClient()` detects it early, keeps the socket open, registers an EventBus listener, and streams frames until the client disconnects.

For composite commands:

```text
{"command": "batch", "commands": [...]}
{"command": "run", "chain": "measure ||| capture after"}
```

the server handler parses subcommands and recursively calls the same dispatch path for each generated JSON command.
