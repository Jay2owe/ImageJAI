package imagejai.local;

/**
 * Margin-aware top-2 matcher result for post-submit disambiguation.
 */
public final class Match2Result {

    public enum Status {
        CONFIDENT,
        AMBIGUOUS,
        MISS
    }

    private final Status status;
    private final RankedPhrase best;
    private final RankedPhrase runnerUp;
    private final double margin;

    private Match2Result(Status status, RankedPhrase best, RankedPhrase runnerUp,
                         double margin) {
        this.status = status;
        this.best = best;
        this.runnerUp = runnerUp;
        this.margin = margin;
    }

    public static Match2Result confident(RankedPhrase best) {
        return new Match2Result(Status.CONFIDENT, best, null, 0.0);
    }

    public static Match2Result ambiguous(RankedPhrase best, RankedPhrase runnerUp,
                                         double margin) {
        return new Match2Result(Status.AMBIGUOUS, best, runnerUp, margin);
    }

    public static Match2Result miss() {
        return new Match2Result(Status.MISS, null, null, 0.0);
    }

    public Status status() {
        return status;
    }

    public boolean isConfident() {
        return status == Status.CONFIDENT;
    }

    public boolean isAmbiguous() {
        return status == Status.AMBIGUOUS;
    }

    public boolean isMiss() {
        return status == Status.MISS;
    }

    public RankedPhrase best() {
        return best;
    }

    public RankedPhrase runnerUp() {
        return runnerUp;
    }

    public double margin() {
        return margin;
    }
}
