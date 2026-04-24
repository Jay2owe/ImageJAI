# Step 13 — Image Provenance Graph

## Motivation

After four or five macros, every agent starts to lose track of
which image came from where. Transcripts show Gemma writing
`selectImage("mask")` when three masks exist, or measuring on
the blurred intermediate instead of the derived binary. The
history is in plain sight (open windows, log lines) but
scattered across sources the agent never reassembles.

A small server-side DAG of "this image was created by this
macro from that parent image" turns scattered observation into
a single query, and lets every reply carry a minimal *diff* of
what the graph gained.

## End goal

Server maintains a session-scoped directed acyclic graph of
images and the operations that produced them:

```
raw.tif ──Gaussian Blur──▶ raw_blur
raw_blur ──Otsu threshold──▶ raw_mask
raw_mask ──Analyze Particles──▶ (results appended; no new image)
```

Queryable via:

```json
{"command": "get_image_graph"}
// Response
{"ok": true, "result": {
  "nodes": [
    {"id": "n1", "title": "raw.tif", "origin": "opened", "ts": ...},
    {"id": "n2", "title": "raw_blur", "origin": "macro",
     "macro": "run(\"Gaussian Blur...\", \"sigma=2\");",
     "parents": ["n1"], "ts": ...},
    ...
  ],
  "edges": [
    {"from": "n1", "to": "n2", "op": "Gaussian Blur..."}
  ]
}}
```

And every mutating reply carries a `graphDelta` — the subgraph
added by that command:

```json
{"graphDelta": {
  "nodes": [{"id": "n2", "title": "raw_blur", "parents": ["n1"]}],
  "edges": [{"from": "n1", "to": "n2", "op": "Gaussian Blur..."}]
}}
```

## Scope

In:
- `ImageGraph` data structure: nodes (images) and edges (ops).
- Node creation hooked into image creation events from the
  existing `ImageMonitor`/`EventBus`.
- Parent inference: best-effort heuristic — the active image at
  the moment of creation is the parent; if the macro used
  `Duplicate` / `Z Project` / `Subtract Background` etc., that's
  the origin op.
- New TCP command `get_image_graph`.
- `graphDelta` in mutating replies.

Out:
- Cross-session persistence. Graph resets on server restart;
  that's fine.
- Cycle detection. The graph is a DAG by construction — a
  macro can't create an image whose parent is itself. If a weird
  plugin does, handle gracefully (mark orphan, don't crash).

## Read first

- `src/main/java/imagejai/engine/ImageMonitor.java`
  — how image creation is already tracked.
- `src/main/java/imagejai/engine/EventBus.java`
  — `image.opened`, `image.updated`, `image.closed` topics.
- `docs/tcp_upgrade/05_state_delta_and_pulse.md` —
  `stateDelta.windows.added` is the same info at lower detail.

## Data structures

```java
public final class ImageGraph {
    public record Node(String id, String title, String origin,
                       String macro, List<String> parents,
                       long timestampMs) {}
    public record Edge(String from, String to, String op, long timestampMs) {}

    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final List<Edge> edges = new ArrayList<>();
    private int idCounter = 0;

    public Node addOpenedImage(String title) {
        Node n = new Node(nextId(), title, "opened", null,
                          List.of(), System.currentTimeMillis());
        nodes.put(n.id(), n);
        return n;
    }

    public Node addDerivedImage(String title, String macro,
                                List<String> parentIds) {
        Node n = new Node(nextId(), title, "macro", macro,
                          parentIds, System.currentTimeMillis());
        nodes.put(n.id(), n);
        for (String p : parentIds) {
            edges.add(new Edge(p, n.id(), macroOp(macro),
                               System.currentTimeMillis()));
        }
        return n;
    }

    public Delta deltaSince(int markerIdx) {
        // return nodes/edges added since marker
    }
}
```

Scoped per `AgentCaps` — each socket has its own graph view.
They all observe the same Fiji state but each agent gets its
own incremental deltas.

## Parent inference

First version: the active image at macro start is the parent of
every new image the macro creates. Good enough for 80% of flows.

Second version (optional refinement): inspect the macro for
explicit source references (`selectImage("X"); run("Op");` →
parent is X regardless of currently active). Ship v1, upgrade
if transcripts show v1 is confusing.

## Reply integration

On every `execute_macro`, `run_script`, `run_pipeline`,
`interact_dialog`:

1. Snapshot graph index before.
2. Run the command.
3. For each new image in post-execution state not in the graph,
   call `addDerivedImage(title, macroCode, [activeImageIdAtStart])`.
4. Compute `deltaSince(snapshotIdx)`.
5. If non-empty, append `graphDelta` to the reply.

## Capability gate

`caps.graphDelta` — default `true`. Cheap to compute (graph ops
are O(1) per image). Per-agent defaults:

| Cap | Gemma | Claude | Codex | Gemini |
|---|---|---|---|---|
| `graphDelta` | true | true | true | true |
| `get_image_graph` | always available | | | |

## Tests

- Open an image → `graphDelta` has one node with `origin: "opened"`.
- Run a filter that creates a new image → new node with
  `parents: [openedId]` and one edge.
- Run `Analyze Particles` (no new image) → `graphDelta` is
  empty or absent.
- `Duplicate` with a selection → new node, parent is the source
  image.
- Close an image → node stays in graph (history preserved) but
  `get_image_graph` marks it as `closed: true`.
- `get_image_graph` returns all nodes in chronological order.

## Failure modes

- Parent misattribution when a macro ran `selectImage` mid-way
  before creating an image. The v1 heuristic gets this wrong.
  Accept for Phase 2; plan the v2 refinement if it bites.
- Graph grows unbounded. Cap at 500 nodes; LRU-evict oldest
  on overflow. Document in `get_image_graph` that older history
  may be truncated.
- Active-image-at-start logic gets stale if an `execute_macro`
  runs multi-image sequences. Capture active image per
  intermediate step when possible; accept the occasional miss.
- Concurrent sockets: each agent sees only its own deltas but
  all share the same underlying image set. Two agents running
  simultaneously may see the other's image creations attributed
  to "opened" (no macro known). This is fine for graph semantics.
