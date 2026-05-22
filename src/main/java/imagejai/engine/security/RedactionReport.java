package imagejai.engine.security;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import imagejai.config.PrivacyPosture;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Small value object describing what a pseudonymisation pass changed.
 */
public final class RedactionReport {
    private final String command;
    private final PrivacyPosture posture;
    private final Set<String> fieldsPseudonymised;
    private final int bytesBefore;
    private final int bytesAfter;
    private final boolean failed;

    private RedactionReport(Builder builder) {
        this.command = builder.command;
        this.posture = builder.posture;
        this.fieldsPseudonymised = Collections.unmodifiableSet(
                new LinkedHashSet<String>(builder.fieldsPseudonymised));
        this.bytesBefore = builder.bytesBefore;
        this.bytesAfter = builder.bytesAfter;
        this.failed = builder.failed;
    }

    public static RedactionReport passthrough(String command) {
        return builder().command(command).posture(PrivacyPosture.STANDARD).build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public String command() {
        return command;
    }

    public PrivacyPosture posture() {
        return posture;
    }

    public Set<String> fieldsPseudonymised() {
        return fieldsPseudonymised;
    }

    public int bytesBefore() {
        return bytesBefore;
    }

    public int bytesAfter() {
        return bytesAfter;
    }

    public boolean failed() {
        return failed;
    }

    public JsonObject governanceBlock() {
        JsonObject governance = new JsonObject();
        governance.addProperty("posture", posture == null ? "" : posture.label());
        JsonArray fields = new JsonArray();
        for (String field : fieldsPseudonymised) {
            fields.add(field);
        }
        governance.add("fields_pseudonymised", fields);
        if (failed) {
            governance.addProperty("redaction_failed", true);
        }
        return governance;
    }

    public static final class Builder {
        private String command = "";
        private PrivacyPosture posture = PrivacyPosture.PSEUDONYMISED;
        private final LinkedHashSet<String> fieldsPseudonymised =
                new LinkedHashSet<String>();
        private int bytesBefore;
        private int bytesAfter;
        private boolean failed;

        public Builder command(String command) {
            this.command = command == null ? "" : command;
            return this;
        }

        public Builder posture(PrivacyPosture posture) {
            this.posture = posture == null ? PrivacyPosture.PSEUDONYMISED : posture;
            return this;
        }

        public Builder fieldPseudonymised(String field) {
            if (field != null && !field.trim().isEmpty()) {
                fieldsPseudonymised.add(field);
            }
            return this;
        }

        public Builder bytesBefore(int bytesBefore) {
            this.bytesBefore = Math.max(0, bytesBefore);
            return this;
        }

        public Builder bytesAfter(int bytesAfter) {
            this.bytesAfter = Math.max(0, bytesAfter);
            return this;
        }

        public Builder failed(boolean failed) {
            this.failed = failed;
            return this;
        }

        public RedactionReport build() {
            return new RedactionReport(this);
        }
    }
}
