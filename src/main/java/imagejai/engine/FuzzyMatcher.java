package imagejai.engine;

/**
 * Jaro-Winkler string similarity for fuzzy plugin-name matching.
 * Introduced by {@code docs/tcp_upgrade/04_fuzzy_plugin_registry.md}.
 * <p>
 * The algorithm scores two strings on a 0..1 scale where 1 is identical.
 * Jaro's core computes the fraction of matching characters (within a
 * windowed distance) and accounts for transpositions; Winkler's addition
 * weights common prefix characters more heavily — typos usually happen
 * past the first few characters, so a prefix match is a strong signal.
 * <p>
 * Used to catch hallucinated plugin names (e.g. {@code "Gausian Blur"}
 * vs the canonical {@code "Gaussian Blur..."}). Thresholds chosen
 * conservatively in the plan: &gt;=0.95 with a single clear winner =
 * auto-correct; 0.85..0.95 or ambiguous = suggest only; &lt;0.85 =
 * low-confidence suggest.
 */
final class FuzzyMatcher {

    private FuzzyMatcher() {}

    /**
     * Compute the Jaro-Winkler similarity of two strings.
     * Comparison is case-insensitive (both strings are lowered internally).
     * Returns 1.0 for identical, 0.0 for no match.
     */
    static double jaroWinkler(String a, String b) {
        if (a == null || b == null) return 0.0;
        String s1 = a.toLowerCase();
        String s2 = b.toLowerCase();
        if (s1.equals(s2)) return 1.0;
        if (s1.isEmpty() || s2.isEmpty()) return 0.0;

        double j = jaro(s1, s2);
        int prefix = commonPrefixLength(s1, s2, 4);
        return j + 0.1 * prefix * (1.0 - j);
    }

    /**
     * Pure Jaro similarity (without Winkler's prefix bonus). Exposed for
     * tests that want to probe the underlying metric.
     */
    static double jaro(String s1, String s2) {
        int len1 = s1.length();
        int len2 = s2.length();
        if (len1 == 0 && len2 == 0) return 1.0;
        if (len1 == 0 || len2 == 0) return 0.0;

        int matchWindow = Math.max(len1, len2) / 2 - 1;
        if (matchWindow < 0) matchWindow = 0;

        boolean[] matches1 = new boolean[len1];
        boolean[] matches2 = new boolean[len2];

        int matches = 0;
        for (int i = 0; i < len1; i++) {
            int start = Math.max(0, i - matchWindow);
            int end = Math.min(i + matchWindow + 1, len2);
            for (int k = start; k < end; k++) {
                if (matches2[k]) continue;
                if (s1.charAt(i) != s2.charAt(k)) continue;
                matches1[i] = true;
                matches2[k] = true;
                matches++;
                break;
            }
        }
        if (matches == 0) return 0.0;

        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < len1; i++) {
            if (!matches1[i]) continue;
            while (!matches2[k]) k++;
            if (s1.charAt(i) != s2.charAt(k)) transpositions++;
            k++;
        }

        double m = matches;
        return (m / len1 + m / len2 + (m - transpositions / 2.0) / m) / 3.0;
    }

    private static int commonPrefixLength(String a, String b, int cap) {
        int max = Math.min(cap, Math.min(a.length(), b.length()));
        int i = 0;
        while (i < max && a.charAt(i) == b.charAt(i)) i++;
        return i;
    }
}
