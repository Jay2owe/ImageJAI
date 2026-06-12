package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Headless unit tests for step 13 — {@link ImageGraph}. The data structure is
 * exercised with synthetic addOpenedImage / addDerivedImage / trackMacroChange
 * calls so WindowManager state is not required. Per plan:
 * docs/tcp_upgrade/13_provenance_graph.md.
 */
public class ImageGraphTest {

    private static Set<String> titles(String... ts) {
        return new HashSet<String>(Arrays.asList(ts));
    }

    // ------------------------------------------------------------------
    // Basic insertion
    // ------------------------------------------------------------------

    @Test
    public void addOpenedImageCreatesANodeWithOriginOpened() {
        ImageGraph g = new ImageGraph();
        ImageGraph.Node n = g.addOpenedImage("raw.tif");

        assertNotNull(n);
        assertEquals("raw.tif", n.title);
        assertEquals("opened", n.origin);
        assertTrue("opened nodes have no parents", n.parents.isEmpty());
        assertNull("opened nodes carry no macro", n.macro);
        assertEquals(1, g.size());
    }

    @Test
    public void addDerivedImageLinksToParentWithOneEdge() {
        ImageGraph g = new ImageGraph();
        ImageGraph.Node parent = g.addOpenedImage("raw.tif");
        ImageGraph.Node child = g.addDerivedImage(
                "raw_blur", "macro",
                "run(\"Gaussian Blur...\", \"sigma=2\");",
                Arrays.asList(parent.id));

        assertEquals("raw_blur", child.title);
        assertEquals("macro", child.origin);
        assertEquals(1, child.parents.size());
        assertEquals(parent.id, child.parents.get(0));

        JsonObject snap = g.snapshot();
        assertEquals(2, snap.get("nodeCount").getAsInt());
        JsonArray edges = snap.getAsJsonArray("edges");
        assertEquals(1, edges.size());
        JsonObject e = edges.get(0).getAsJsonObject();
        assertEquals(parent.id, e.get("from").getAsString());
        assertEquals(child.id, e.get("to").getAsString());
        assertEquals("Gaussian Blur...", e.get("op").getAsString());
    }

    @Test
    public void addDerivedImageDropsUnknownParentIds() {
        ImageGraph g = new ImageGraph();
        ImageGraph.Node n = g.addDerivedImage(
                "orphan", "macro", null, Arrays.asList("nBogus"));

        // Unknown parent dropped → orphan is retained but has no parents/edges.
        assertEquals(1, g.size());
        assertTrue(n.parents.isEmpty());
        JsonObject snap = g.snapshot();
        assertEquals(0, snap.getAsJsonArray("edges").size());
    }

    // ------------------------------------------------------------------
    // Delta
    // ------------------------------------------------------------------

    @Test
    public void deltaSinceReturnsOnlyNewNodesAndEdges() {
        ImageGraph g = new ImageGraph();
        ImageGraph.Node a = g.addOpenedImage("a");
        long marker = g.currentMarker();

        ImageGraph.Node b = g.addDerivedImage(
                "b", "macro", "run(\"Invert\");", Arrays.asList(a.id));

        ImageGraph.Delta d = g.deltaSince(marker);
        assertEquals(1, d.nodes.size());
        assertEquals(b.id, d.nodes.get(0).id);
        assertEquals(1, d.edges.size());
        assertEquals(a.id, d.edges.get(0).from);
    }

    @Test
    public void deltaSinceIsEmptyWhenNothingChanged() {
        ImageGraph g = new ImageGraph();
        g.addOpenedImage("a");
        long marker = g.currentMarker();

        ImageGraph.Delta d = g.deltaSince(marker);
        assertTrue("no changes since marker → empty delta", d.isEmpty());
    }

    // ------------------------------------------------------------------
    // Close lifecycle
    // ------------------------------------------------------------------

    @Test
    public void markClosedByTitleFlagsTheNodeWithoutRemovingIt() {
        ImageGraph g = new ImageGraph();
        ImageGraph.Node n = g.addOpenedImage("raw.tif");

        g.markClosedByTitle("raw.tif");
        assertEquals("closed nodes stay in the graph (history)", 1, g.size());
        assertTrue(n.isClosed());
        JsonObject snap = g.snapshot();
        JsonObject node0 = snap.getAsJsonArray("nodes").get(0).getAsJsonObject();
        assertTrue("closed marker surfaced in JSON",
                node0.has("closed") && node0.get("closed").getAsBoolean());
    }

    @Test
    public void markClosedIsNoOpForUnknownTitle() {
        ImageGraph g = new ImageGraph();
        g.markClosedByTitle("never-opened");
        assertEquals(0, g.size());
    }

    // ------------------------------------------------------------------
    // trackMacroChange — parent inference
    // ------------------------------------------------------------------

    @Test
    public void trackMacroChangeAttributesNewImagesToActiveImageAtCallStart() {
        ImageGraph g = new ImageGraph();
        g.addOpenedImage("raw.tif");
        long marker = g.currentMarker();

        ImageGraph.Delta d = g.trackMacroChange(
                titles("raw.tif"),
                "raw.tif",
                titles("raw.tif", "raw_blur"),
                "run(\"Gaussian Blur...\", \"sigma=2\");",
                "macro");

        assertEquals(1, d.nodes.size());
        assertEquals("raw_blur", d.nodes.get(0).title);
        assertEquals("macro", d.nodes.get(0).origin);
        assertEquals(1, d.nodes.get(0).parents.size());
        assertEquals(1, d.edges.size());
        assertEquals("Gaussian Blur...", d.edges.get(0).op);

        // Marker was captured before the call — delta picks up only the new
        // derived node and its edge, not the pre-existing opened root.
        assertTrue("derived node post-dates marker",
                d.nodes.get(0).seq > marker);
    }

    @Test
    public void trackMacroChangeRegistersPreExistingActiveImageWhenGraphIsEmpty() {
        // Happens on first macro after server startup: no "opened" node yet,
        // but WindowManager already has an active image. The tracker should
        // add it as an opened node so the derived edge has a parent.
        ImageGraph g = new ImageGraph();
        ImageGraph.Delta d = g.trackMacroChange(
                titles("raw.tif"),
                "raw.tif",
                titles("raw.tif", "raw_mask"),
                "setAutoThreshold(\"Otsu dark\");",
                "macro");

        // Two nodes: the implicit opened root + the derived mask.
        assertEquals(2, d.nodes.size());
        boolean haveOpened = false, haveDerived = false;
        for (ImageGraph.Node n : d.nodes) {
            if ("raw.tif".equals(n.title) && "opened".equals(n.origin)) haveOpened = true;
            if ("raw_mask".equals(n.title) && "macro".equals(n.origin)) haveDerived = true;
        }
        assertTrue("implicit parent registered", haveOpened);
        assertTrue("derived image registered", haveDerived);
        assertEquals(1, d.edges.size());
    }

    @Test
    public void trackMacroChangeDoesNotAddSpuriousParentWhenNoNewImages() {
        // Read-only macros (Analyze Particles with no mask, getValue) don't
        // touch the image set. The tracker must not drop a phantom "opened"
        // node every time such a macro runs.
        ImageGraph g = new ImageGraph();
        ImageGraph.Delta d = g.trackMacroChange(
                titles("raw.tif"),
                "raw.tif",
                titles("raw.tif"),
                "getStatistics(area, mean);",
                "macro");

        assertTrue("nothing appeared → empty delta", d.isEmpty());
        assertEquals("no nodes added for read-only macro", 0, g.size());
    }

    @Test
    public void trackMacroChangeWithNullActiveTitleFallsBackToOpenedOrigin() {
        // No image selected at call start — a new image appears. Best we can
        // do is record it as an opened root with no parent; don't invent a
        // fake edge.
        ImageGraph g = new ImageGraph();
        ImageGraph.Delta d = g.trackMacroChange(
                Collections.<String>emptySet(),
                null,
                titles("brand_new"),
                "open(\"/tmp/brand_new.tif\");",
                "macro");

        assertEquals(1, d.nodes.size());
        assertEquals("opened", d.nodes.get(0).origin);
        assertTrue(d.nodes.get(0).parents.isEmpty());
        assertEquals("no edge when no parent", 0, d.edges.size());
    }

    @Test
    public void trackMacroChangeMarksVanishedTitlesClosed() {
        ImageGraph g = new ImageGraph();
        ImageGraph.Node keeper = g.addOpenedImage("keep.tif");
        ImageGraph.Node goner = g.addOpenedImage("goner.tif");

        g.trackMacroChange(
                titles("keep.tif", "goner.tif"),
                "keep.tif",
                titles("keep.tif"),
                "close(\"goner.tif\");",
                "macro");

        assertFalse("survivor stays open", keeper.isClosed());
        assertTrue("vanished title marked closed", goner.isClosed());
        assertEquals("closed nodes preserved in graph", 2, g.size());
    }

    // ------------------------------------------------------------------
    // LRU eviction
    // ------------------------------------------------------------------

    @Test
    public void overflowTriggersLruEvictionAndEdgeCleanup() {
        ImageGraph g = new ImageGraph();
        ImageGraph.Node root = g.addOpenedImage("root");
        String rootId = root.id;

        // Fill past the cap — each extra image chains to the previous child.
        ImageGraph.Node parent = root;
        for (int i = 0; i < ImageGraph.MAX_NODES + 5; i++) {
            parent = g.addDerivedImage(
                    "derived_" + i, "macro", "run(\"Duplicate...\");",
                    Arrays.asList(parent.id));
        }

        assertEquals("size hard-capped at MAX_NODES",
                ImageGraph.MAX_NODES, g.size());

        JsonObject snap = g.snapshot();
        JsonArray nodeArr = snap.getAsJsonArray("nodes");
        boolean rootStillThere = false;
        for (int i = 0; i < nodeArr.size(); i++) {
            if (rootId.equals(nodeArr.get(i).getAsJsonObject().get("id").getAsString())) {
                rootStillThere = true;
                break;
            }
        }
        assertFalse("oldest (root) evicted first", rootStillThere);

        // Edges referencing evicted nodes must be dropped too — no dangling
        // edges in the JSON snapshot.
        JsonArray edgeArr = snap.getAsJsonArray("edges");
        for (int i = 0; i < edgeArr.size(); i++) {
            JsonObject e = edgeArr.get(i).getAsJsonObject();
            assertFalse("no edge references evicted root",
                    rootId.equals(e.get("from").getAsString())
                            || rootId.equals(e.get("to").getAsString()));
        }
    }

    // ------------------------------------------------------------------
    // Snapshot shape / reset
    // ------------------------------------------------------------------

    @Test
    public void snapshotReportsMaxNodesAndCurrentCounts() {
        ImageGraph g = new ImageGraph();
        g.addOpenedImage("a");
        g.addDerivedImage("b", "macro", "run(\"Invert\");",
                Arrays.asList(g.lookupByTitle("a")));

        JsonObject snap = g.snapshot();
        assertEquals(ImageGraph.MAX_NODES, snap.get("maxNodes").getAsInt());
        assertEquals(2, snap.get("nodeCount").getAsInt());
        assertEquals(1, snap.get("edgeCount").getAsInt());
        assertTrue(snap.get("seq").getAsLong() > 0L);
    }

    @Test
    public void resetClearsEverything() {
        ImageGraph g = new ImageGraph();
        g.addOpenedImage("a");
        g.reset();

        assertEquals(0, g.size());
        assertEquals(0L, g.currentMarker());
        JsonObject snap = g.snapshot();
        assertEquals(0, snap.getAsJsonArray("nodes").size());
        assertEquals(0, snap.getAsJsonArray("edges").size());
    }

    // ------------------------------------------------------------------
    // macroOp label extraction
    // ------------------------------------------------------------------

    @Test
    public void macroOpExtractsFirstRunClause() {
        assertEquals("Gaussian Blur...",
                ImageGraph.macroOp("run(\"Gaussian Blur...\", \"sigma=2\");"));
    }

    @Test
    public void macroOpFallsBackToPrefixWhenNoRunClause() {
        String out = ImageGraph.macroOp("setAutoThreshold(\"Otsu dark\");");
        assertEquals("setAutoThreshold(\"Otsu dark\");", out);
    }

    @Test
    public void macroOpReturnsNullForBlankInput() {
        assertNull(ImageGraph.macroOp(null));
        assertNull(ImageGraph.macroOp(""));
        assertNull(ImageGraph.macroOp("   \n  "));
    }
}
