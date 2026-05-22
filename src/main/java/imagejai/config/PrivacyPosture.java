package imagejai.config;

/**
 * User-selected Data Governance posture for the currently opened image folder.
 */
public enum PrivacyPosture {
    STANDARD("Standard", "Cloud agents allowed, no pseudonymisation."),
    PSEUDONYMISED("Pseudonymised",
            "Cloud agents allowed; identifiers tokenised before send (UK GDPR Art. 4(5))."),
    ON_PREMISES("On-premises",
            "Local agents only; pseudonymisation also applied.");

    private final String label;
    private final String description;

    PrivacyPosture(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String label() {
        return label;
    }

    public String description() {
        return description;
    }

    public boolean isStricterThan(PrivacyPosture other) {
        return other != null && ordinal() > other.ordinal();
    }

    public static PrivacyPosture defaultPosture() {
        return PSEUDONYMISED;
    }
}
