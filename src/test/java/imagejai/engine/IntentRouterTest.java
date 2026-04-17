package imagejai.engine;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class IntentRouterTest {

    private Path tmpDir;
    private Path store;

    @Before
    public void setUp() throws IOException {
        tmpDir = Files.createTempDirectory("intent-router-test-");
        store = tmpDir.resolve("intent_mappings.json");
    }

    @After
    public void tearDown() throws IOException {
        if (Files.exists(store)) Files.delete(store);
        Path tmp = store.resolveSibling(store.getFileName().toString() + ".tmp");
        if (Files.exists(tmp)) Files.delete(tmp);
        if (Files.exists(tmpDir)) Files.delete(tmpDir);
    }

    @Test
    public void substituteReplacesDollarGroups() {
        Pattern p = Pattern.compile("rolling ball (\\d+)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher("rolling ball 50");
        assertTrue(m.matches());
        String out = IntentRouter.substitute(
                "run(\"Subtract Background...\", \"rolling=$1\");", m);
        assertEquals("run(\"Subtract Background...\", \"rolling=50\");", out);
    }

    @Test
    public void substituteMultipleGroups() {
        Pattern p = Pattern.compile("gaussian (\\d+) sigma on (\\S+)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher("gaussian 3 sigma on myimage.tif");
        assertTrue(m.matches());
        String out = IntentRouter.substitute("run(\"Gaussian Blur...\", \"sigma=$1\"); // $2", m);
        assertEquals("run(\"Gaussian Blur...\", \"sigma=3\"); // myimage.tif", out);
    }

    @Test
    public void substituteWithGroupContainingDollarSignIsLiteral() {
        // A captured group like "$foo" must be inserted verbatim — not
        // interpreted as a back-reference the way Matcher.appendReplacement
        // would.
        Pattern p = Pattern.compile("(.+)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher("$foo");
        assertTrue(m.matches());
        String out = IntentRouter.substitute("x=$1", m);
        assertEquals("x=$foo", out);
    }

    @Test
    public void teachThenResolveHits() {
        IntentRouter r = new IntentRouter(store);
        r.teach("rolling ball (\\d+)",
                "run(\"Subtract Background...\", \"rolling=$1\");",
                "Subtract Background with radius");
        Optional<IntentRouter.Resolved> res = r.resolve("rolling ball 50");
        assertTrue(res.isPresent());
        assertEquals("run(\"Subtract Background...\", \"rolling=50\");", res.get().macro);
        assertEquals(1, res.get().mapping.hits);
    }

    @Test
    public void resolveMissOnUnknownPhrase() {
        IntentRouter r = new IntentRouter(store);
        r.teach("foo (\\d+)", "print(\"$1\");", null);
        assertFalse(r.resolve("bar 99").isPresent());
    }

    @Test
    public void teachRejectsInvalidRegex() {
        IntentRouter r = new IntentRouter(store);
        try {
            r.teach("rolling ball (", "run(\"X\");", null);
            fail("expected IllegalArgumentException for invalid regex");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void persistenceRoundTrip() throws IOException {
        IntentRouter r1 = new IntentRouter(store);
        r1.teach("otsu", "setAutoThreshold(\"Otsu\");", "Apply Otsu threshold");
        r1.resolve("otsu");
        r1.resolve("otsu");
        // Fresh router reads same file.
        IntentRouter r2 = new IntentRouter(store);
        Optional<IntentRouter.Resolved> res = r2.resolve("OTSU"); // case insensitive
        assertTrue(res.isPresent());
        assertEquals("setAutoThreshold(\"Otsu\");", res.get().macro);
        assertTrue("hits persisted across reload", res.get().mapping.hits >= 3);
    }

    @Test
    public void invalidRegexInFileIsQuarantined() throws IOException {
        // Hand-craft a file with one valid and one invalid pattern.
        String json = "{\"version\":1,\"mappings\":["
                + "{\"pattern\":\"good (\\\\d+)\",\"macro\":\"print($1);\"},"
                + "{\"pattern\":\"bad ([\",\"macro\":\"nope\"}"
                + "]}";
        Files.write(store, json.getBytes(StandardCharsets.UTF_8));
        IntentRouter r = new IntentRouter(store);
        assertEquals(1, r.mappingCount());
        assertEquals(1, r.quarantinedCount());
        JsonObject lst = r.list();
        JsonArray q = lst.getAsJsonArray("quarantined");
        assertEquals(1, q.size());
    }

    @Test
    public void forgetRemoves() {
        IntentRouter r = new IntentRouter(store);
        r.teach("alpha", "run(\"A\");", null);
        r.teach("beta", "run(\"B\");", null);
        assertTrue(r.forget("alpha"));
        assertFalse(r.resolve("alpha").isPresent());
        assertTrue(r.resolve("beta").isPresent());
        assertFalse("forget returns false for unknown", r.forget("gamma"));
    }

    @Test
    public void hotReloadOnExternalEdit() throws IOException, InterruptedException {
        IntentRouter r = new IntentRouter(store);
        r.teach("foo", "print(\"foo\");", null);
        // Wait a bit so mtime can change visibly on coarse filesystems.
        Thread.sleep(1100);
        String newJson = "{\"version\":1,\"mappings\":["
                + "{\"pattern\":\"bar\",\"macro\":\"print(\\\"bar\\\");\"}"
                + "]}";
        Files.write(store, newJson.getBytes(StandardCharsets.UTF_8));
        assertFalse(r.resolve("foo").isPresent());
        Optional<IntentRouter.Resolved> res = r.resolve("bar");
        assertTrue(res.isPresent());
        assertNotNull(res.get().macro);
    }

    @Test
    public void emptyAndWhitespacePhrasesMiss() {
        IntentRouter r = new IntentRouter(store);
        r.teach("x", "print(\"x\");", null);
        assertFalse(r.resolve(null).isPresent());
        assertFalse(r.resolve("").isPresent());
        assertFalse(r.resolve("   ").isPresent());
    }
}
