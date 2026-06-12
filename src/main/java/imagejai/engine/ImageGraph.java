package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import ij.ImagePlus;
import ij.WindowManager;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Step 13 (docs/tcp_upgrade/13_provenance_graph.md): session-scoped image
 * provenance DAG. Nodes are images; edges are the operations that produced
 * each child from its parents. Every mutating TCP handler (execute_macro,
 * run_script, run_pipeline, interact_dialog) captures a marker before the
 * call, tracks the resulting image-set diff against the graph, and attaches
 * {@link #deltaSince(long)} to the reply under {@code graphDelta}.
 *
 * <p>V1 parent inference: the active image at call start is the parent of
 * every new image the call produces. Good enough for ~80% of flows; explicit
 * {@code selectImage("X")} parsing is left for future refinement.
 *
 * <p>Graph state is shared across sockets — the underlying Fiji image set is
 * shared, so there is only ever one truth. Per-socket isolation is achieved
 * by capturing a fresh {@link #currentMarker()} at each handler entry; the
 * delta each reply carries covers only the changes that handler produced
 * (plus any concurrent changes from other sockets, which is the right
 * signal for an agent to re-orient).
 *
 * <p>Node/edge budget: {@link #MAX_NODES} nodes; LRU eviction drops the
 * oldest node (by insertion seq) when the cap is exceeded. Edges that
 * reference an evicted node are dropped alongside it. Closed images stay in
 * the graph with {@code closed: true} until they are evicted by the LRU.
 * Callers that expose the graph should document that older history may be
 * truncated.
 */
public final class ImageGraph {

    /** Hard cap on nodes retained in the graph. Older nodes LRU-evict. */
    public static final int MAX_NODES = 500;

    /** Immutable node snapshot. {@link #closed} is mutable state set via
     *  {@link ImageGraph#markClosedByTitle(String)}; all other fields are
     *  fixed at insertion time. */
    public static final class Node {
        public final String id;
        public final String title;
        public final String origin;     // "opened" | "macro" | "script" | "pipeline" | "dialog"
        public final String macro;      // nullable — source of the producing call
        public final List<String> parents;
        public final long seq;
        public final long timestampMs;
        boolean closed;

        Node(String id, String title, String origin, String macro,
             List<String> parents, long seq, long timestampMs) {
            this.id = id;
            this.title = title;
            this.origin = origin;
            this.macro = macro;
            this.parents = Collections.unmodifiableList(new ArrayList<String>(parents));
            this.seq = seq;
            this.timestampMs = timestampMs;
        }

        public boolean isClosed() {
            return closed;
        }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", id);
            obj.addProperty("title", title != null ? title : "");
            obj.addProperty("origin", origin != null ? origin : "unknown");
            if (macro != null && !macro.isEmpty()) obj.addProperty("macro", macro);
            JsonArray parr = new JsonArray();
            for (String p : parents) parr.add(new JsonPrimitive(p));
            obj.add("parents", parr);
            obj.addProperty("seq", seq);
            obj.addProperty("ts", timestampMs);
            if (closed) obj.addProperty("closed", true);
            return obj;
        }
    }

    /** Immutable edge snapshot. The {@code op} label is a short description
     *  of the operation (first {@code run("name")} clause or an 80-char
     *  prefix of the macro) — the full macro lives on the child node. */
    public static final class Edge {
        public final String from;
        public final String to;
        public final String op;
        public final long seq;
        public final long timestampMs;

        Edge(String from, String to, String op, long seq, long timestampMs) {
            this.from = from;
            this.to = to;
            this.op = op;
            this.seq = seq;
            this.timestampMs = timestampMs;
        }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("from", from);
            obj.addProperty("to", to);
            if (op != null) obj.addProperty("op", op);
            obj.addProperty("seq", seq);
            obj.addProperty("ts", timestampMs);
            return obj;
        }
    }

    /** Subgraph produced since a given marker. */
    public static final class Delta {
        public final List<Node> nodes;
        public final List<Edge> edges;

        Delta(List<Node> nodes, List<Edge> edges) {
            this.nodes = nodes;
            this.edges = edges;
        }

        public boolean isEmpty() {
            return nodes.isEmpty() && edges.isEmpty();
        }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            JsonArray narr = new JsonArray();
            for (Node n : nodes) narr.add(n.toJson());
            obj.add("nodes", narr);
            JsonArray earr = new JsonArray();
            for (Edge e : edges) earr.add(e.toJson());
            obj.add("edges", earr);
            return obj;
        }
    }

    // -------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------

    private final LinkedHashMap<String, Node> nodes = new LinkedHashMap<String, Node>();
    private final List<Edge> edges = new ArrayList<Edge>();
    /** Title -> most recently inserted still-open node id. Used for v1
     *  parent inference. Evicted/closed entries are removed lazily. */
    private final Map<String, String> titleToId = new HashMap<String, String>();
    private long seqCounter = 0L;
    private int idCounter = 0;

    /** Current high-water seq. Capture at handler entry and pass back to
     *  {@link #deltaSince(long)} at handler exit to get the subgraph
     *  produced by the handler. */
    public synchronized long currentMarker() {
        return seqCounter;
    }

    /** Number of nodes currently in the graph (primarily for tests). */
    public synchronized int size() {
        return nodes.size();
    }

    /** Wipe all state. Primarily for test isolation. */
    public synchronized void reset() {
        nodes.clear();
        edges.clear();
        titleToId.clear();
        seqCounter = 0L;
        idCounter = 0;
    }

    /** Resolve a title to its latest open node id, or null when the title
     *  has no currently-open node. */
    public synchronized String lookupByTitle(String title) {
        if (title == null) return null;
        return titleToId.get(title);
    }

    /** Add a top-level opened image with no parent. Returns the new node. */
    public synchronized Node addOpenedImage(String title) {
        return createNode(title, "opened", null, Collections.<String>emptyList());
    }

    /** Add a derived image pointing at known parent ids. Unknown parent
     *  ids are silently dropped (e.g. after LRU eviction). Each retained
     *  parent produces one edge. */
    public synchronized Node addDerivedImage(
            String title, String origin, String macro, List<String> parentIds) {
        List<String> validParents = new ArrayList<String>();
        if (parentIds != null) {
            for (String pid : parentIds) {
                if (pid != null && nodes.containsKey(pid)) validParents.add(pid);
            }
        }
        String effOrigin = origin != null ? origin : "macro";
        Node n = createNode(title, effOrigin, macro, validParents);
        String opLabel = macroOp(macro);
        for (String pid : validParents) {
            seqCounter++;
            edges.add(new Edge(pid, n.id, opLabel, seqCounter, n.timestampMs));
        }
        return n;
    }

    /** Mark a node closed by its title. No-op if no open node owns that
     *  title. Does not bump seq — closes do not ride the delta stream
     *  (agents can inspect {@link #snapshot()} for lifecycle state). */
    public synchronized void markClosedByTitle(String title) {
        if (title == null) return;
        String id = titleToId.remove(title);
        if (id == null) return;
        Node n = nodes.get(id);
        if (n != null) n.closed = true;
    }

    /** Nodes and edges whose seq is strictly greater than {@code markerSeq}. */
    public synchronized Delta deltaSince(long markerSeq) {
        List<Node> outN = new ArrayList<Node>();
        for (Node n : nodes.values()) {
            if (n.seq > markerSeq) outN.add(n);
        }
        List<Edge> outE = new ArrayList<Edge>();
        for (Edge e : edges) {
            if (e.seq > markerSeq) outE.add(e);
        }
        return new Delta(outN, outE);
    }

    /** Serialise the full graph in chronological order. Callers should
     *  document that older history may be truncated (LRU-evicted past
     *  {@link #MAX_NODES}). */
    public synchronized JsonObject snapshot() {
        JsonObject obj = new JsonObject();
        JsonArray narr = new JsonArray();
        for (Node n : nodes.values()) narr.add(n.toJson());
        obj.add("nodes", narr);
        JsonArray earr = new JsonArray();
        for (Edge e : edges) earr.add(e.toJson());
        obj.add("edges", earr);
        obj.addProperty("nodeCount", nodes.size());
        obj.addProperty("edgeCount", edges.size());
        obj.addProperty("maxNodes", MAX_NODES);
        obj.addProperty("seq", seqCounter);
        return obj;
    }

    /**
     * V1 mutating-handler entry point. Diffs an open-title snapshot from
     * before the call against one from after, and:
     * <ul>
     *   <li>Registers the active pre-call title as an {@code opened} node
     *       when it is not yet in the graph — but only if at least one new
     *       title actually appeared, so read-only macros do not add spurious
     *       parent nodes.</li>
     *   <li>Adds a derived node for each title present after but not before.
     *       Parents are the resolved active-title id (empty when the
     *       active-title was not set, in which case the node lands as
     *       {@code origin: opened}).</li>
     *   <li>Marks vanished titles closed.</li>
     * </ul>
     * Returns the subgraph produced by the call — the caller passes this
     * straight into its reply.
     *
     * @param titlesBefore      open titles at call start (null treated as empty)
     * @param activeTitleBefore title of the active image at call start (may be null)
     * @param titlesAfter       open titles after the call (null treated as empty)
     * @param macro             producing macro / script source (may be null)
     * @param origin            "macro" / "script" / "pipeline" / "dialog"
     */
    public synchronized Delta trackMacroChange(
            Set<String> titlesBefore, String activeTitleBefore,
            Set<String> titlesAfter, String macro, String origin) {
        Set<String> before = titlesBefore != null ? titlesBefore : Collections.<String>emptySet();
        Set<String> after = titlesAfter != null ? titlesAfter : Collections.<String>emptySet();

        long marker = seqCounter;

        // Which titles actually appeared this call.
        List<String> newTitles = new ArrayList<String>();
        for (String title : after) {
            if (title != null && !before.contains(title)) newTitles.add(title);
        }

        // Resolve the parent id only if there is derived work to attribute.
        // Avoids dropping a phantom "opened" node on every read-only macro.
        String parentId = null;
        if (!newTitles.isEmpty()
                && activeTitleBefore != null
                && !activeTitleBefore.isEmpty()
                && before.contains(activeTitleBefore)) {
            String existing = titleToId.get(activeTitleBefore);
            if (existing == null) {
                Node parent = createNode(activeTitleBefore, "opened", null,
                        Collections.<String>emptyList());
                parentId = parent.id;
            } else {
                parentId = existing;
            }
        }

        List<String> parents = (parentId != null)
                ? Arrays.asList(parentId)
                : Collections.<String>emptyList();
        String effOrigin = origin != null ? origin : "macro";
        for (String title : newTitles) {
            // A title that appears with no active predecessor is an "opened"
            // node (open("path"), user drop-in, etc.). Only attribute to
            // {@code effOrigin} when a real parent was resolved.
            if (parents.isEmpty()) {
                createNode(title, "opened", macro, Collections.<String>emptyList());
            } else {
                addDerivedImage(title, effOrigin, macro, parents);
            }
        }

        for (String title : before) {
            if (title != null && !after.contains(title)) {
                markClosedByTitle(title);
            }
        }

        return deltaSince(marker);
    }

    // -------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------

    private Node createNode(String title, String origin, String macro, List<String> parents) {
        seqCounter++;
        idCounter++;
        String id = "n" + idCounter;
        Node n = new Node(id, title, origin, macro, parents, seqCounter,
                System.currentTimeMillis());
        nodes.put(id, n);
        if (title != null && !title.isEmpty()) titleToId.put(title, id);
        enforceCap();
        return n;
    }

    private void enforceCap() {
        while (nodes.size() > MAX_NODES) {
            Iterator<Map.Entry<String, Node>> it = nodes.entrySet().iterator();
            Map.Entry<String, Node> oldest = it.next();
            String evictedId = oldest.getKey();
            Node evicted = oldest.getValue();
            it.remove();
            if (evicted != null && evicted.title != null) {
                // Only drop the title→id mapping when it still points at the
                // evicted id — a newer node can have overwritten the entry.
                String mapped = titleToId.get(evicted.title);
                if (evictedId.equals(mapped)) titleToId.remove(evicted.title);
            }
            Iterator<Edge> eit = edges.iterator();
            while (eit.hasNext()) {
                Edge e = eit.next();
                if (evictedId.equals(e.from) || evictedId.equals(e.to)) eit.remove();
            }
        }
    }

    /** Pull a short op label from a macro string: first {@code run("name")}
     *  clause, or the first 80 chars of the trimmed macro. {@code null} when
     *  the macro is blank. */
    static String macroOp(String macro) {
        if (macro == null) return null;
        String trimmed = macro.trim();
        if (trimmed.isEmpty()) return null;
        int start = trimmed.indexOf("run(\"");
        if (start >= 0) {
            int q = trimmed.indexOf("\"", start + 5);
            if (q > start + 5) return trimmed.substring(start + 5, q);
        }
        return trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed;
    }

    // -------------------------------------------------------------------
    // WindowManager integration (headless-safe)
    // -------------------------------------------------------------------

    /** Capture the set of currently-open image titles from the live
     *  {@link WindowManager}. Returns an empty set in headless / test
     *  environments where WindowManager has no IDs. */
    public static Set<String> captureOpenTitles() {
        Set<String> out = new HashSet<String>();
        try {
            int[] ids = WindowManager.getIDList();
            if (ids != null) {
                for (int id : ids) {
                    ImagePlus imp = WindowManager.getImage(id);
                    if (imp != null) {
                        String t = imp.getTitle();
                        if (t != null) out.add(t);
                    }
                }
            }
        } catch (Throwable ignore) {
            // Headless / classloader mishap — return what we have.
        }
        return out;
    }

    /** Title of the active image at call time, or null when no image is
     *  active. */
    public static String captureActiveTitle() {
        try {
            ImagePlus imp = WindowManager.getCurrentImage();
            if (imp == null) return null;
            String t = imp.getTitle();
            if (t == null || t.isEmpty()) return null;
            return t;
        } catch (Throwable ignore) {
            return null;
        }
    }
}
