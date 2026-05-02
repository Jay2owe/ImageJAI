package imagejai.local;

/**
 * Metadata for an intent slot that may be requested in chat.
 */
public final class SlotSpec {
    private final String name;
    private final String prompt;
    private final String defaultValue;

    public SlotSpec(String name, String prompt, String defaultValue) {
        this.name = name == null ? "" : name;
        this.prompt = prompt == null ? "" : prompt;
        this.defaultValue = defaultValue;
    }

    public String name() {
        return name;
    }

    public String prompt() {
        return prompt;
    }

    public String defaultValue() {
        return defaultValue;
    }

    public boolean hasDefault() {
        return defaultValue != null;
    }
}
