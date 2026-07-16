package imagejai.engine.picker;

import ij.IJ;
import imagejai.engine.AgentLauncher;
import imagejai.engine.AgentSession;
import imagejai.engine.LaunchPolicy;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared launch logic for the proxy and native transports. Both spawn the same
 * Python entry point — {@code agent.providers.agent_cli} — in a terminal,
 * differing only in the extra environment they inject (native-feature flags for
 * the native path). The entry point builds a provider client via
 * {@code agent.providers.router.get_client} and drives Fiji over TCP.
 *
 * <p>The terminal/{@link AgentSession} machinery is reused from
 * {@link AgentLauncher#launch(AgentLauncher.AgentInfo, AgentLauncher.Mode, Map)}
 * so embedded-PTY vs external-terminal, working dir, TCP-port env, and audit
 * env all behave exactly like a CLI agent launch.
 */
final class ProviderAgentLaunch {

    static final String LOCAL_HOST_CODE_ENV = "IMAGEJAI_ALLOW_LOCAL_HOST_CODE";

    private ProviderAgentLaunch() {
    }

    /** Pure description of how to spawn the provider agent — no side effects. */
    static final class Plan {
        final AgentLauncher.AgentInfo info;
        final Map<String, String> env;
        final boolean dangerousPermissions;

        Plan(AgentLauncher.AgentInfo info, Map<String, String> env,
             boolean dangerousPermissions) {
            this.info = info;
            this.env = Collections.unmodifiableMap(env);
            this.dangerousPermissions = dangerousPermissions;
        }
    }

    static AgentSession launch(AgentLauncher cliLauncher,
                               ModelEntry entry,
                               AgentLauncher.Mode mode,
                               Map<String, String> extraEnv) {
        if (cliLauncher == null) {
            IJ.log("[ProviderAgentLaunch] No agent workspace available — cannot "
                    + "launch a provider agent. Configure the ImageJAI agent "
                    + "directory or use a CLI agent.");
            return null;
        }
        Map<String, String> requestedEnv = withProcessHostCodeGrant(extraEnv);
        final Plan plan;
        try {
            plan = plan(cliLauncher.getAgentWorkspace(), entry, requestedEnv);
        } catch (IllegalArgumentException denied) {
            IJ.log("[ProviderAgentLaunch] Local host-code permission refused: "
                    + denied.getMessage());
            return null;
        }
        if (plan == null) {
            IJ.log("[ProviderAgentLaunch] Missing provider/model/workspace — cannot launch.");
            return null;
        }
        return cliLauncher.launch(plan.info, mode, plan.env,
                plan.dangerousPermissions);
    }

    /**
     * Build the {@link AgentInfo} + environment for the provider agent loop.
     * Package-private and side-effect-free so it can be unit-tested without
     * spawning a terminal. Returns {@code null} when the entry or workspace is
     * unusable.
     */
    static Plan plan(String workspace, ModelEntry entry, Map<String, String> extraEnv) {
        if (isBlank(workspace) || entry == null
                || isBlank(entry.providerId()) || isBlank(entry.modelId())) {
            return null;
        }

        String provider = entry.providerId().trim();
        String model = entry.modelId().trim();
        boolean allowLocalHostCode = strictBoolean(extraEnv == null
                ? null : extraEnv.get(LOCAL_HOST_CODE_ENV));
        String endpoint = "ollama".equalsIgnoreCase(provider) && extraEnv != null
                ? extraEnv.get("OLLAMA_HOST") : null;
        if (allowLocalHostCode
                && !LaunchPolicy.isLocalProviderEndpoint(provider, endpoint)) {
            throw new IllegalArgumentException(
                    "permission is available only to a trusted loopback provider");
        }
        String python = pythonCommand();
        // Provider keys and model ids carry no spaces, so they pass unquoted
        // through both cmd.exe and bash without shell escaping (and avoids
        // cmd `start`'s fragile quote parsing on the external path).
        String command = python + " -m agent.providers.agent_cli"
                + " --provider " + provider
                + " --model " + model
                + (allowLocalHostCode ? " --allow-local-host-code" : "");

        String name = isBlank(entry.displayName())
                ? provider + " / " + model
                : entry.displayName();
        boolean ollama = provider.equals("ollama") || provider.equals("ollama-cloud");
        AgentLauncher.AgentInfo info = new AgentLauncher.AgentInfo(
                name,
                command,
                "Multi-provider agent: " + provider + " / " + model,
                python,
                "",
                provider.equals("ollama"),
                // Carry the model tag for ollama so the on-premises cloud-tag
                // refusal in AgentLauncher still fires for *-cloud models.
                ollama ? model : "");

        Map<String, String> env = new LinkedHashMap<String, String>();
        File parent = new File(workspace).getParentFile();
        if (parent != null) {
            // `python -m agent.providers.agent_cli` needs the parent of the
            // agent/ workspace on PYTHONPATH so the `agent` package resolves
            // regardless of the terminal's working directory.
            String pp = parent.getAbsolutePath();
            String existing = System.getenv("PYTHONPATH");
            env.put("PYTHONPATH",
                    isBlank(existing) ? pp : pp + File.pathSeparator + existing);
        }
        env.put("IMAGEJAI_PROVIDER", provider);
        env.put("IMAGEJAI_MODEL", model);
        if (extraEnv != null) {
            env.putAll(extraEnv);
        }
        return new Plan(info, env, allowLocalHostCode);
    }

    private static Map<String, String> withProcessHostCodeGrant(
            Map<String, String> extraEnv) {
        Map<String, String> env = new LinkedHashMap<String, String>();
        if (extraEnv != null) env.putAll(extraEnv);
        if (!env.containsKey(LOCAL_HOST_CODE_ENV)) {
            String inherited = System.getenv(LOCAL_HOST_CODE_ENV);
            if (inherited != null) env.put(LOCAL_HOST_CODE_ENV, inherited);
        }
        if (!env.containsKey("OLLAMA_HOST")) {
            String inherited = System.getenv("OLLAMA_HOST");
            if (inherited != null) env.put("OLLAMA_HOST", inherited);
        }
        return env;
    }

    /** Security-sensitive boolean: typos must abort instead of silently denying. */
    private static boolean strictBoolean(String raw) {
        if (raw == null) return false;
        String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.isEmpty() || "0".equals(value) || "false".equals(value)
                || "no".equals(value) || "off".equals(value)) return false;
        if ("1".equals(value) || "true".equals(value)
                || "yes".equals(value) || "on".equals(value)) return true;
        throw new IllegalArgumentException(LOCAL_HOST_CODE_ENV
                + " must be one of 1/true/yes/on or 0/false/no/off");
    }

    private static String pythonCommand() {
        String configured = System.getenv("IMAGEJAI_PYTHON");
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        return System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "python"
                : "python3";
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
