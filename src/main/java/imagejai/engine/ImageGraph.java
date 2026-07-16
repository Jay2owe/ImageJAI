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
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.UUID;

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

    /**
     * Stable, process-local identity for an open {@link ImagePlus}. Titles are
     * mutable and non-unique, while WindowManager IDs can be recycled after a
     * close. The object reference is therefore the source of truth and this
     * opaque identifier is only its deterministic wire representation.
     */
    public static final class ImageRef {
        public final ImagePlus image;
        public final String identity;
        public final int windowId;
        public final String title;
        public final String sourcePath;

        ImageRef(ImagePlus image, String identity, int windowId,
                 String title, String sourcePath) {
            this.image = image;
            this.identity = identity;
            this.windowId = windowId;
            this.title = title == null ? "" : title;
            this.sourcePath = sourcePath;
        }
    }

    private static final Object IDENTITY_LOCK = new Object();
    private static final IdentityHashMap<ImagePlus, String> IMAGE_IDENTITIES =
            new IdentityHashMap<ImagePlus, String>();

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
        public final String imageIdentity;
        public final boolean inPlace;
        boolean closed;

        Node(String id, String title, String origin, String macro,
             List<String> parents, long seq, long timestampMs,
             String imageIdentity, boolean inPlace) {
            this.id = id;
            this.title = title;
            this.origin = origin;
            this.macro = macro;
            this.parents = Collections.unmodifiableList(new ArrayList<String>(parents));
            this.seq = seq;
            this.timestampMs = timestampMs;
            this.imageIdentity = imageIdentity;
            this.inPlace = inPlace;
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
            if (imageIdentity != null) obj.addProperty("imageIdentity", imageIdentity);
            if (inPlace) obj.addProperty("inPlace", true);
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
    /** Stable image identity -> latest version node id. */
    private final Map<String, String> identityToId = new HashMap<String, String>();
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
        identityToId.clear();
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
        return createNode(title, "opened", null, Collections.<String>emptyList(),
                null, false);
    }

    /** Add one concrete opened image while retaining its stable identity. */
    public synchronized Node addOpenedImage(ImageRef ref) {
        if (ref == null) throw new IllegalArgumentException("image ref is required");
        String existing = identityToId.get(ref.identity);
        if (existing != null) return nodes.get(existing);
        return createNode(ref.title, "opened", null,
                Collections.<String>emptyList(), ref.identity, false);
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
        Node n = createNode(title, effOrigin, macro, validParents, null, false);
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

    /** Mark the latest graph version for one concrete image object closed. */
    public synchronized void markClosedByIdentity(String imageIdentity) {
        if (imageIdentity == null) return;
        String id = identityToId.remove(imageIdentity);
        if (id == null) return;
        Node n = nodes.get(id);
        if (n == null) return;
        n.closed = true;
        String mapped = titleToId.get(n.title);
        if (id.equals(mapped)) titleToId.remove(n.title);
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
        Collections.sort(newTitles);

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
                        Collections.<String>emptyList(), null, false);
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
                createNode(title, "opened", macro, Collections.<String>emptyList(),
                        null, false);
            } else {
                addDerivedImage(title, effOrigin, macro, parents);
            }
        }

        List<String> closedTitles = new ArrayList<String>(before);
        Collections.sort(closedTitles);
        for (String title : closedTitles) {
            if (title != null && !after.contains(title)) {
                markClosedByTitle(title);
            }
        }

        return deltaSince(marker);
    }

    /**
     * Identity-safe mutation tracking used by production handlers. Open and
     * close detection compares concrete {@link ImagePlus} objects, results are
     * emitted in WindowManager-ID order, and a mutation with no new image
     * creates a new version node for the retained active image.
     */
    public synchronized Delta trackImageChange(
            List<ImageRef> imagesBefore, ImageRef activeBefore,
            List<ImageRef> imagesAfter, String macro, String origin) {
        List<ImageRef> before = sortedRefs(imagesBefore);
        List<ImageRef> after = sortedRefs(imagesAfter);
        Map<String, ImageRef> beforeById = refsByIdentity(before);
        Map<String, ImageRef> afterById = refsByIdentity(after);
        long marker = seqCounter;

        List<ImageRef> added = new ArrayList<ImageRef>();
        for (ImageRef ref : after) {
            if (!beforeById.containsKey(ref.identity)) added.add(ref);
        }

        String parentId = null;
        if (activeBefore != null && beforeById.containsKey(activeBefore.identity)) {
            parentId = identityToId.get(activeBefore.identity);
            if (parentId == null && (!added.isEmpty()
                    || afterById.containsKey(activeBefore.identity))) {
                parentId = createNode(activeBefore.title, "opened", null,
                        Collections.<String>emptyList(), activeBefore.identity,
                        false).id;
            }
        }
        List<String> parents = parentId == null
                ? Collections.<String>emptyList()
                : Collections.singletonList(parentId);
        String effectiveOrigin = origin == null ? "macro" : origin;

        if (!added.isEmpty()) {
            for (ImageRef ref : added) {
                if (parents.isEmpty()) {
                    createNode(ref.title, "opened", macro,
                            Collections.<String>emptyList(), ref.identity, false);
                } else {
                    createIdentityNode(ref, effectiveOrigin, macro, parents, false);
                }
            }
        } else if (activeBefore != null
                && afterById.containsKey(activeBefore.identity)
                && parentId != null) {
            ImageRef current = afterById.get(activeBefore.identity);
            createIdentityNode(current, effectiveOrigin, macro, parents, true);
        }

        for (ImageRef ref : before) {
            if (!afterById.containsKey(ref.identity)) {
                markClosedByIdentity(ref.identity);
            }
        }
        return deltaSince(marker);
    }

    // -------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------

    private Node createIdentityNode(ImageRef ref, String origin, String macro,
                                    List<String> parents, boolean inPlace) {
        Node n = createNode(ref.title, origin, macro, parents,
                ref.identity, inPlace);
        String opLabel = macroOp(macro);
        for (String parent : parents) {
            seqCounter++;
            edges.add(new Edge(parent, n.id, opLabel, seqCounter, n.timestampMs));
        }
        return n;
    }

    private Node createNode(String title, String origin, String macro,
                            List<String> parents, String imageIdentity,
                            boolean inPlace) {
        seqCounter++;
        idCounter++;
        String id = "n-" + UUID.randomUUID().toString();
        Node n = new Node(id, title, origin, macro, parents, seqCounter,
                System.currentTimeMillis(), imageIdentity, inPlace);
        nodes.put(id, n);
        if (title != null && !title.isEmpty()) titleToId.put(title, id);
        if (imageIdentity != null) identityToId.put(imageIdentity, id);
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
            if (evicted != null && evicted.imageIdentity != null) {
                String mapped = identityToId.get(evicted.imageIdentity);
                if (evictedId.equals(mapped)) identityToId.remove(evicted.imageIdentity);
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

    /** Capture all open images in deterministic WindowManager-ID order. */
    public static List<ImageRef> captureOpenImages() {
        List<ImageRef> out = new ArrayList<ImageRef>();
        try {
            int[] ids = WindowManager.getIDList();
            if (ids != null) {
                Arrays.sort(ids);
                for (int id : ids) {
                    ImagePlus imp = WindowManager.getImage(id);
                    if (imp != null) out.add(refFor(imp, id));
                }
            }
        } catch (Throwable ignore) {
            // Headless / classloader mishap - return what we have.
        }
        return out;
    }

    /** Build a deterministic snapshot from synthetic images (test seam). */
    static List<ImageRef> refsForImages(List<ImagePlus> images) {
        List<ImageRef> out = new ArrayList<ImageRef>();
        if (images != null) {
            int fallbackId = 1;
            for (ImagePlus imp : images) {
                if (imp != null) {
                    int id = imp.getID();
                    out.add(refFor(imp, id > 0 ? id : fallbackId));
                    fallbackId++;
                }
            }
        }
        return sortedRefs(out);
    }

    public static ImageRef captureActiveImage() {
        try {
            ImagePlus imp = WindowManager.getCurrentImage();
            return imp == null ? null : refFor(imp, imp.getID());
        } catch (Throwable ignore) {
            return null;
        }
    }

    public static String stableIdentity(ImagePlus imp) {
        if (imp == null) return null;
        synchronized (IDENTITY_LOCK) {
            String identity = IMAGE_IDENTITIES.get(imp);
            if (identity == null) {
                identity = "img-" + UUID.randomUUID().toString();
                IMAGE_IDENTITIES.put(imp, identity);
            }
            return identity;
        }
    }

    private static ImageRef refFor(ImagePlus imp, int windowId) {
        return new ImageRef(imp, stableIdentity(imp), windowId,
                imp.getTitle(), sourcePath(imp));
    }

    private static String sourcePath(ImagePlus imp) {
        try {
            ij.io.FileInfo info = imp.getOriginalFileInfo();
            if (info == null || info.directory == null || info.fileName == null) return null;
            return java.nio.file.Paths.get(info.directory, info.fileName)
                    .toAbsolutePath().normalize().toString();
        } catch (Throwable ignore) {
            return null;
        }
    }

    private static List<ImageRef> sortedRefs(List<ImageRef> refs) {
        List<ImageRef> out = refs == null
                ? new ArrayList<ImageRef>()
                : new ArrayList<ImageRef>(refs);
        Collections.sort(out, new Comparator<ImageRef>() {
            @Override public int compare(ImageRef a, ImageRef b) {
                int byWindow = Integer.compare(a.windowId, b.windowId);
                if (byWindow != 0) return byWindow;
                return a.identity.compareTo(b.identity);
            }
        });
        return out;
    }

    private static Map<String, ImageRef> refsByIdentity(List<ImageRef> refs) {
        Map<String, ImageRef> out = new LinkedHashMap<String, ImageRef>();
        for (ImageRef ref : refs) out.put(ref.identity, ref);
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
