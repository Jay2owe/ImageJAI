package imagejai.engine;

import imagejai.config.PrivacyPosture;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Single, side-effect-free privacy and launch decision point.
 *
 * <p>Every process launch and legacy assistant surface supplies the same facts:
 * whether it can leave the machine, whether it can mutate Fiji, whether Safe
 * Mode is active, and any extra process environment it requests.  The result
 * is immutable so callers cannot add capabilities after approval.</p>
 */
public final class LaunchPolicy {

    public enum Surface {
        CLI,
        PROVIDER,
        LEGACY_CONVERSATION,
        LOCAL_ASSISTANT,
        MODEL_DISCOVERY
    }

    private static final Pattern PROVIDER_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    private static final Pattern MODEL_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/@+~-]{0,255}");
    private static final Pattern ENV_NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,127}");
    private static final Pattern SENSITIVE_ENV = Pattern.compile(
            ".*(?:API_?KEY|TOKEN|SECRET|PASSWORD|CREDENTIAL|PRIVATE_?KEY).*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SHELL_CONTROL = Pattern.compile("[\\r\\n\\u0000;&|<>`]|\\$\\(");

    private LaunchPolicy() {
    }

    public static final class RequestedCapabilities {
        private final Surface surface;
        private final boolean localProvider;
        private final boolean egress;
        private final boolean mutation;
        private final boolean safeMode;
        private final boolean dangerousPermissions;
        private final Map<String, String> environment;

        private RequestedCapabilities(Builder builder) {
            this.surface = builder.surface;
            this.localProvider = builder.localProvider;
            this.egress = builder.egress;
            this.mutation = builder.mutation;
            this.safeMode = builder.safeMode;
            this.dangerousPermissions = builder.dangerousPermissions;
            this.environment = Collections.unmodifiableMap(
                    new LinkedHashMap<String, String>(builder.environment));
        }

        public static Builder builder(Surface surface) {
            return new Builder(surface);
        }

        public static final class Builder {
            private final Surface surface;
            private boolean localProvider;
            private boolean egress;
            private boolean mutation;
            private boolean safeMode = true;
            private boolean dangerousPermissions;
            private Map<String, String> environment = Collections.emptyMap();

            private Builder(Surface surface) {
                this.surface = surface == null ? Surface.CLI : surface;
            }

            public Builder localProvider(boolean value) {
                localProvider = value;
                return this;
            }

            public Builder egress(boolean value) {
                egress = value;
                return this;
            }

            public Builder mutation(boolean value) {
                mutation = value;
                return this;
            }

            public Builder safeMode(boolean value) {
                safeMode = value;
                return this;
            }

            public Builder dangerousPermissions(boolean value) {
                dangerousPermissions = value;
                return this;
            }

            public Builder environment(Map<String, String> value) {
                environment = value == null
                        ? Collections.<String, String>emptyMap()
                        : value;
                return this;
            }

            public RequestedCapabilities build() {
                return new RequestedCapabilities(this);
            }
        }
    }

    public static final class Decision {
        private final boolean allowed;
        private final String reason;
        private final Map<String, String> permittedEnvironment;
        private final boolean dangerousPermissions;

        private Decision(boolean allowed, String reason,
                         Map<String, String> permittedEnvironment,
                         boolean dangerousPermissions) {
            this.allowed = allowed;
            this.reason = reason == null ? "" : reason;
            this.permittedEnvironment = Collections.unmodifiableMap(
                    new LinkedHashMap<String, String>(permittedEnvironment));
            this.dangerousPermissions = dangerousPermissions;
        }

        public boolean allowed() {
            return allowed;
        }

        public String reason() {
            return reason;
        }

        public Map<String, String> permittedEnvironment() {
            return permittedEnvironment;
        }

        public boolean dangerousPermissionsAllowed() {
            return dangerousPermissions;
        }

        public Decision enforce() {
            if (!allowed) {
                throw new PostureViolation(reason);
            }
            return this;
        }
    }

    public static Decision evaluate(String provider, String model,
                                    PrivacyPosture posture,
                                    RequestedCapabilities requested) {
        RequestedCapabilities caps = requested == null
                ? RequestedCapabilities.builder(Surface.CLI).build()
                : requested;
        try {
            requireProviderId(provider);
            if (model != null && !model.trim().isEmpty()) {
                requireModelId(model);
            }
        } catch (IllegalArgumentException invalid) {
            return deny(invalid.getMessage());
        }

        PrivacyPosture effective = posture == null
                ? PrivacyPosture.defaultPosture()
                : posture;
        if (effective == PrivacyPosture.ON_PREMISES
                && caps.egress && !caps.localProvider) {
            return deny("On-premises posture blocks this provider from sending data off this machine.");
        }

        Map<String, String> environment;
        try {
            environment = sanitiseEnvironment(caps.environment);
        } catch (IllegalArgumentException invalid) {
            return deny(invalid.getMessage());
        }
        return new Decision(true, "", environment, caps.dangerousPermissions);
    }

    private static Decision deny(String reason) {
        return new Decision(false, reason, Collections.<String, String>emptyMap(), false);
    }

    public static String requireProviderId(String value) {
        String candidate = value == null ? "" : value.trim();
        if (!PROVIDER_ID.matcher(candidate).matches()) {
            throw new IllegalArgumentException("Invalid provider identifier.");
        }
        return candidate;
    }

    public static String requireModelId(String value) {
        String candidate = value == null ? "" : value.trim();
        if (!MODEL_ID.matcher(candidate).matches()) {
            throw new IllegalArgumentException("Invalid model identifier.");
        }
        return candidate;
    }

    /** Reject text that will later be interpreted by cmd.exe/bash. */
    public static String requireSafeCommandText(String value, String field) {
        String candidate = value == null ? "" : value.trim();
        if (candidate.isEmpty() || SHELL_CONTROL.matcher(candidate).find()) {
            throw new IllegalArgumentException("Unsafe " + field + ".");
        }
        return candidate;
    }

    /**
     * Extra launch variables are capabilities, not an arbitrary second secret
     * store. Provider credentials remain in the dedicated credential store and
     * are read by the provider runtime; they are never copied into a launch
     * spec that tests, logs, or diagnostics may inspect.
     */
    public static Map<String, String> sanitiseEnvironment(Map<String, String> requested) {
        if (requested == null || requested.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : requested.entrySet()) {
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            String value = entry.getValue() == null ? "" : entry.getValue();
            if (!ENV_NAME.matcher(key).matches() || SENSITIVE_ENV.matcher(key).matches()
                    || !isApprovedEnvironmentName(key)) {
                throw new IllegalArgumentException("Launch environment contains an unapproved variable.");
            }
            if (value.indexOf('\u0000') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
                throw new IllegalArgumentException("Launch environment contains an unsafe value.");
            }
            result.put(key, value);
        }
        return Collections.unmodifiableMap(result);
    }

    public static boolean isLocalProvider(String provider) {
        String key = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        return "local-assistant".equals(key)
                || "ollama".equals(key)
                || "lmstudio".equals(key)
                || "jan".equals(key)
                || "llamacpp".equals(key)
                || "vllm".equals(key);
    }

    public static boolean isLocalProviderEndpoint(String provider, String endpoint) {
        String key = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if ("local-assistant".equals(key)) {
            return true;
        }
        if (!isLocalProvider(key)) {
            return false;
        }
        return endpoint == null || endpoint.trim().isEmpty() || isLoopbackEndpoint(endpoint);
    }

    private static boolean isApprovedEnvironmentName(String key) {
        return key.startsWith("IMAGEJAI_")
                || "PYTHONPATH".equals(key)
                || "OLLAMA_MODEL".equals(key)
                || "OLLAMA_HOST".equals(key)
                || "TERM".equals(key)
                || "COLORTERM".equals(key)
                || "TERMINAL_EMULATOR".equals(key);
    }

    public static boolean isLoopbackEndpoint(String endpoint) {
        if (endpoint == null || endpoint.trim().isEmpty()) {
            return false;
        }
        try {
            URI uri = URI.create(endpoint.trim());
            String host = uri.getHost();
            return host != null && ("localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host)
                    || "::1".equals(host));
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }
}
