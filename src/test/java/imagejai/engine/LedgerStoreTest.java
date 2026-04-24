package imagejai.engine;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Unit tests for {@link LedgerStore}. Every test points the store at a fresh
 * temp directory so the user's real {@code ~/.imagejai/ledger.json} never
 * participates. Per plan: docs/tcp_upgrade/14_federated_ledger.md.
 */
public class LedgerStoreTest {

    private Path tmpDir;
    private Path ledgerFile;

    @Before
    public void setUp() throws IOException {
        tmpDir = Files.createTempDirectory("imagejai-ledger-test-");
        ledgerFile = tmpDir.resolve("ledger.json");
    }

    @After
    public void tearDown() throws IOException {
        if (tmpDir == null) return;
        // Best-effort recursive delete — tests must not leak temp dirs.
        List<Path> paths = new ArrayList<Path>();
        Files.walk(tmpDir).forEach(paths::add);
        // Delete deepest first so directories are empty when removed.
        paths.sort(Comparator.reverseOrder());
        for (Path p : paths) {
            try { Files.deleteIfExists(p); } catch (IOException ignore) {}
        }
    }

    // ------------------------------------------------------------------
    // Fingerprint + normalisation
    // ------------------------------------------------------------------

    @Test
    public void fingerprintIs32HexChars() {
        String fp = LedgerStore.fingerprint("CODE", "something failed at line 12", "run(\"Measure\")");
        assertEquals(32, fp.length());
        assertTrue("fingerprint is hex", fp.matches("[0-9a-f]+"));
    }

    @Test
    public void fingerprintCollapsesLineNumbers() {
        String a = LedgerStore.fingerprint("X", "error at line 12", "run(\"Measure\")");
        String b = LedgerStore.fingerprint("X", "error at line 9999", "run(\"Measure\")");
        assertEquals("line numbers must not change the fingerprint", a, b);
    }

    @Test
    public void fingerprintDiffersOnErrorCode() {
        String a = LedgerStore.fingerprint("A", "same message", "run(\"X\")");
        String b = LedgerStore.fingerprint("B", "same message", "run(\"X\")");
        assertNotEquals(a, b);
    }

    @Test
    public void fingerprintDiffersOnPluginName() {
        String a = LedgerStore.fingerprint("X", "same", "run(\"Gaussian Blur...\")");
        String b = LedgerStore.fingerprint("X", "same", "run(\"Mean...\")");
        assertNotEquals(a, b);
    }

    @Test
    public void normaliseMacroKeepsFirstTwoRunCalls() {
        String n = LedgerStore.normaliseMacro(
                "run(\"A...\", \"x=1\");\nrun(\"B...\", \"y=2\");\nrun(\"C...\");");
        assertEquals("a...|b...", n);
    }

    @Test
    public void normaliseMacroFallsBackToFirstLineWhenNoRunCall() {
        String n = LedgerStore.normaliseMacro("title = getTitle();\nwait(100);");
        assertEquals("title = gettitle();", n);
    }

    @Test
    public void normaliseErrorHandlesNull() {
        assertEquals("", LedgerStore.normaliseError(null));
    }

    // ------------------------------------------------------------------
    // Basic confirm / lookup / file write
    // ------------------------------------------------------------------

    @Test
    public void firstConfirmCreatesFile() {
        LedgerStore store = new LedgerStore(ledgerFile);
        assertFalse("ledger file starts absent", Files.exists(ledgerFile));

        store.confirm(null, "SCALE_MISSING", "Set Scale required",
                "run(\"Measure\")",
                "run(\"Set Scale...\", \"unit=pixel distance=1\");",
                "run(\"Set Scale...\", \"unit=pixel distance=1\");\nrun(\"Measure\");",
                "claude-code", true);

        assertTrue("ledger file created on first confirm", Files.exists(ledgerFile));
        assertEquals(1, store.size());
    }

    @Test
    public void subsequentConfirmIncrementsTimesSeenAndAddsAgent() {
        LedgerStore store = new LedgerStore(ledgerFile);
        LedgerStore.Entry first = store.confirm(null, "X", "fragment", "run(\"Foo\")",
                "fix1", "example", "agent-a", true);
        LedgerStore.Entry second = store.confirm(first.fingerprint, "X", "fragment",
                "run(\"Foo\")", "fix1", "example", "agent-b", true);

        assertEquals("second confirm keeps the fingerprint stable",
                first.fingerprint, second.fingerprint);
        assertEquals(2, second.timesSeen);
        assertTrue("second agent added", second.confirmedBy.contains("agent-a"));
        assertTrue("second agent added", second.confirmedBy.contains("agent-b"));
    }

    @Test
    public void lookupFindsEntryByFragmentAndMacroPrefix() {
        LedgerStore store = new LedgerStore(ledgerFile);
        store.confirm(null, "X", "Set Scale required", "run(\"Measure\")",
                "fix", "ex", "a1", true);

        List<LedgerStore.Entry> hits = store.lookup("X", "Set Scale required",
                "run(\"Measure\")", 5);
        assertEquals(1, hits.size());
        assertEquals("fix", hits.get(0).confirmedFix);
    }

    @Test
    public void lookupLimitsByMax() {
        LedgerStore store = new LedgerStore(ledgerFile);
        // Seven distinct fingerprints, all sharing errorCode + errorFragment
        // so the fuzzy fallback path surfaces them all.
        for (int i = 0; i < 7; i++) {
            store.confirm(null, "X", "same fragment",
                    "run(\"P" + i + "...\")", "fix" + i, null,
                    "agent", true);
        }
        List<LedgerStore.Entry> hits = store.lookup("X", "same fragment",
                "run(\"P0...\")", 3);
        assertEquals(3, hits.size());
    }

    // ------------------------------------------------------------------
    // Confidence tiers
    // ------------------------------------------------------------------

    @Test
    public void confidenceLowOnSingleConfirm() {
        LedgerStore store = new LedgerStore(ledgerFile);
        LedgerStore.Entry e = store.confirm(null, "X", "frag", "run(\"A\")",
                "fix", null, "a1", true);
        assertEquals("low", LedgerStore.confidenceOf(e));
    }

    @Test
    public void confidenceMediumAfterTwoSeen() {
        LedgerStore store = new LedgerStore(ledgerFile);
        LedgerStore.Entry e = null;
        for (int i = 0; i < 2; i++) {
            e = store.confirm(null, "X", "frag", "run(\"A\")",
                    "fix", null, "a1", true);
        }
        assertEquals("medium", LedgerStore.confidenceOf(e));
    }

    @Test
    public void confidenceHighAfterTwoAgentsAndFiveSeen() {
        LedgerStore store = new LedgerStore(ledgerFile);
        LedgerStore.Entry e = null;
        for (int i = 0; i < 3; i++) {
            e = store.confirm(null, "X", "frag", "run(\"A\")",
                    "fix", null, "a1", true);
        }
        for (int i = 0; i < 2; i++) {
            e = store.confirm(null, "X", "frag", "run(\"A\")",
                    "fix", null, "a2", true);
        }
        assertEquals("high", LedgerStore.confidenceOf(e));
    }

    @Test
    public void contradictedEntryDowngradesToLow() {
        LedgerStore store = new LedgerStore(ledgerFile);
        LedgerStore.Entry e = null;
        // 3 true + 3 false — ratio below 2:1 → low, regardless of seen count.
        for (int i = 0; i < 3; i++) {
            e = store.confirm(null, "X", "frag", "run(\"A\")",
                    "fix", null, "agent-" + i, true);
        }
        for (int i = 0; i < 3; i++) {
            e = store.confirm(null, "X", "frag", "run(\"A\")",
                    "fix", null, "agent-f-" + i, false);
        }
        assertEquals("low", LedgerStore.confidenceOf(e));
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    @Test
    public void reloadRestoresEntriesFromDisk() {
        LedgerStore first = new LedgerStore(ledgerFile);
        first.confirm(null, "X", "frag", "run(\"A\")",
                "fix", "ex", "agent", true);
        first.confirm(null, "Y", "other", "run(\"B\")",
                "fix2", "ex2", "agent", true);

        LedgerStore reopened = new LedgerStore(ledgerFile);
        assertEquals(2, reopened.size());
        List<LedgerStore.Entry> hits = reopened.lookup("X", "frag",
                "run(\"A\")", 5);
        assertEquals(1, hits.size());
        assertEquals("fix", hits.get(0).confirmedFix);
    }

    @Test
    public void atomicWriteProducesValidJsonWithVersion() throws IOException {
        LedgerStore store = new LedgerStore(ledgerFile);
        store.confirm(null, "X", "frag", "run(\"A\")",
                "fix", null, "agent", true);

        String raw = new String(Files.readAllBytes(ledgerFile), StandardCharsets.UTF_8);
        JsonObject obj = new JsonParser().parse(raw).getAsJsonObject();
        assertEquals(LedgerStore.FORMAT_VERSION, obj.get("version").getAsInt());
        assertTrue("entries array present", obj.has("entries"));
    }

    @Test
    public void deletingFileMidSessionRecoversOnNextWrite() throws IOException {
        LedgerStore store = new LedgerStore(ledgerFile);
        store.confirm(null, "X", "frag", "run(\"A\")",
                "fix", null, "agent", true);
        assertTrue(Files.exists(ledgerFile));

        Files.delete(ledgerFile);
        assertFalse(Files.exists(ledgerFile));

        store.confirm(null, "Y", "frag2", "run(\"B\")",
                "fix2", null, "agent", true);
        assertTrue("next write recreates the file", Files.exists(ledgerFile));

        // Both in-memory entries survive — the delete did not drop state.
        assertEquals(2, store.size());
    }

    @Test
    public void memoryOnlyFlagStaysFalseOnHappyPath() {
        LedgerStore store = new LedgerStore(ledgerFile);
        store.confirm(null, "X", "frag", "run(\"A\")",
                "fix", null, "agent", true);
        assertFalse(store.isMemoryOnly());
    }

    // ------------------------------------------------------------------
    // Suggested-JSON shape
    // ------------------------------------------------------------------

    @Test
    public void suggestedJsonCarriesConfidenceAndFix() {
        LedgerStore store = new LedgerStore(ledgerFile);
        LedgerStore.Entry e = store.confirm(null, "X", "frag", "run(\"A\")",
                "some-fix", "example()", "agent", true);
        JsonObject s = LedgerStore.toSuggestedJson(e);
        assertTrue(s.get("fromLedger").getAsBoolean());
        assertEquals("some-fix", s.get("confirmedFix").getAsString());
        assertEquals("low", s.get("confidence").getAsString());
        assertNotNull(s.get("confirmedBy"));
    }
}
