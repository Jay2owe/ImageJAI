package imagejai.local;

/**
 * Phrasebook candidate ranked by fuzzy similarity.
 */
public final class RankedPhrase {

    private final String phrase;
    private final String intentId;
    private final double score;

    public RankedPhrase(String phrase, String intentId, double score) {
        this.phrase = phrase == null ? "" : phrase;
        this.intentId = intentId == null ? "" : intentId;
        this.score = score;
    }

    public String phrase() {
        return phrase;
    }

    public String intentId() {
        return intentId;
    }

    public double score() {
        return score;
    }
}
