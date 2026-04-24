package imagejai.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

/**
 * Snapshot of every registered ImageJ/Fiji command name + its implementing
 * class, taken once at server start. Powers fuzzy-match plugin-name
 * validation introduced by {@code docs/tcp_upgrade/04_fuzzy_plugin_registry.md}.
 * <p>
 * The underlying source is {@code ij.Menus.getCommands()}. That method
 * returns a {@link Hashtable} keyed by menu label; we freeze the snapshot
 * into an immutable list so subsequent lookups don't pay hashtable-iteration
 * cost per call.
 * <p>
 * Invalidation: if a user installs a new update site mid-session, new
 * plugins will NOT appear until the server restarts. Acceptable tradeoff
 * for Phase 1 — the reload surface (a {@code reload_commands} TCP handler)
 * can be added later if telemetry shows this bites.
 */
final class MenuCommandRegistry {

    /** A single fuzzy-match result: canonical name + its score. */
    static final class Match {
        final String name;
        final double score;
        Match(String name, double score) { this.name = name; this.score = score; }
        String name() { return name; }
        double score() { return score; }
    }

    private final List<String> names;
    private final Map<String, String> byName;

    private MenuCommandRegistry(Map<String, String> snapshot) {
        this.byName = Collections.unmodifiableMap(new HashMap<String, String>(snapshot));
        List<String> n = new ArrayList<String>(snapshot.keySet());
        this.names = Collections.unmodifiableList(n);
    }

    // Lazy singleton, loaded on first access. Wrapping Menus.getCommands()
    // in a try/catch keeps headless unit tests from blowing up when ij.Menus
    // isn't initialised yet.
    private static volatile MenuCommandRegistry INSTANCE;

    static MenuCommandRegistry get() {
        MenuCommandRegistry r = INSTANCE;
        if (r != null) return r;
        synchronized (MenuCommandRegistry.class) {
            if (INSTANCE == null) {
                Map<String, String> snap = new HashMap<String, String>();
                try {
                    Hashtable<String, String> cmds = ij.Menus.getCommands();
                    if (cmds != null) snap.putAll(cmds);
                } catch (Throwable ignore) {
                    // headless or too-early access; leave snap empty
                }
                INSTANCE = new MenuCommandRegistry(snap);
            }
            return INSTANCE;
        }
    }

    /**
     * Test-only: replace the singleton with an explicit command set. Allows
     * unit tests to exercise the fuzzy-match logic without a live Fiji.
     */
    static void setForTesting(Map<String, String> cmds) {
        synchronized (MenuCommandRegistry.class) {
            INSTANCE = new MenuCommandRegistry(cmds == null
                    ? Collections.<String, String>emptyMap() : cmds);
        }
    }

    /** Whether the given name is a known command (exact match, case-sensitive). */
    boolean exists(String name) {
        return name != null && byName.containsKey(name);
    }

    /** Canonical command list. Snapshot; callers may not mutate. */
    List<String> allCommands() { return names; }

    /** Resolving class name for the given command, or {@code null} if unknown. */
    String classFor(String name) {
        return name == null ? null : byName.get(name);
    }

    /** Total number of registered commands. */
    int size() { return names.size(); }

    /**
     * Return the top-N closest matches to {@code query} by Jaro-Winkler,
     * sorted descending by score. Results include even very low-scoring
     * candidates; filtering by threshold is the caller's responsibility.
     */
    List<Match> findClosest(String query, int topN) {
        if (query == null || names.isEmpty() || topN <= 0) {
            return Collections.emptyList();
        }
        List<Match> all = new ArrayList<Match>(names.size());
        for (String n : names) {
            all.add(new Match(n, FuzzyMatcher.jaroWinkler(query, n)));
        }
        Collections.sort(all, new Comparator<Match>() {
            @Override
            public int compare(Match a, Match b) {
                return Double.compare(b.score, a.score);
            }
        });
        return all.subList(0, Math.min(topN, all.size()));
    }
}
