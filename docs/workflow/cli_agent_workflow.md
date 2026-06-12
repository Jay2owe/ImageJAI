# CLI Agent Workflow

External or embedded terminal agent path. This is the plain-language version of the TCP-controlled workflow.

## Main Flow

```text
1. User prompt
   The user asks a terminal agent to analyse or control Fiji.
        |
        v
2. ImageJAI launches the agent
   The user chooses Claude, Codex, Gemini CLI, Aider, or Gemma.
   ImageJAI gives the agent the local TCP server port and agent/ workspace.
        |
        v
3. Terminal agent session
   The agent runs inside ImageJAI or in an external terminal.
   It reads local instructions, recipes, references, and helper tools.
        |
        v
4. The agent prepares two connection types
        |
        +--> A. Short command connection
        |
        +--> B. Kept-open live event connection
```

## A. Command And Reply

```text
Agent opens a short command connection
  -> sends one request to ImageJAI's local TCP server
  -> server routes the request to the matching Fiji action
  -> Fiji/ImageJ reads state, runs work, inspects pixels, controls dialogs, or manages jobs
  -> raw result is assembled
  -> safety and governance gate runs before the reply leaves ImageJAI
  -> TCP server sends one final reply back on the same command connection
  -> helper reads the reply, parses it, and closes the connection
  -> agent uses the result
```

Examples of command requests:

```text
read image state
read Results table
inspect pixels
run a macro or script
probe a plugin dialog
interact with a live dialog
start or check a background job
show GUI feedback in ImageJAI
```

## B. Live Event Subscription

```text
Agent opens a live listening connection
  -> sends one subscribe request to ImageJAI's local TCP server
  -> the subscription happens at the TCP server, not directly in Fiji
  -> the server keeps this listening connection open
  -> the server attaches the agent to ImageJAI's internal event feed
  -> ImageJAI and Fiji activity produce event messages
  -> each subscribed agent gets its own event queue
  -> TCP server streams event messages and heartbeat messages back to the agent
  -> agent keeps listening while sending new commands on short command connections
```

Events can include:

```text
image opened, updated, closed, or active image changed
dialog opened, changed, confirmed, cancelled, or closed
macro/script started, paused, completed, failed, or timed out
job progress, job finished, or job cancelled
reactive rule fired
GUI action result
safety warning, blocked action, backup, or queue protection event
```

## Accuracy Points

```text
Command replies and live events are separate paths.

Command replies:
  agent -> short TCP connection -> ImageJAI TCP server -> Fiji/ImageJ
  -> safety/governance -> ImageJAI TCP server -> same short connection -> agent

Live events:
  agent -> kept-open subscribe connection -> ImageJAI TCP server
  -> ImageJAI internal event feed -> per-agent event queue
  -> same kept-open connection -> agent

The agent can use both at once:
  it sends commands on short connections
  while listening for progress, dialogs, new images, jobs, and safety events
```

## Output

```text
The CLI agent combines:
  command replies that say what a request returned
  live events that say what changed while work was running

Then it can:
  send another command
  wait for more events
  ask the user for clarification
  write approved files/logs
  give the final terminal answer
```
