package imagejai.engine;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Plain data: how to spawn an agent. Shared by external and embedded
 * launch paths so command construction, cwd, and env live in one place.
 */
public final class AgentLaunchSpec {
    public final AgentLauncher.AgentInfo info;
    /** The agent's own executable command + context flags, unwrapped. */
    public final List<String> agentCommand;
    public final File workingDir;
    public final Map<String, String> env;

    public AgentLaunchSpec(AgentLauncher.AgentInfo info,
                           List<String> agentCommand,
                           File workingDir,
                           Map<String, String> env) {
        this.info = info;
        this.agentCommand = Collections.unmodifiableList(agentCommand);
        this.workingDir = workingDir;
        this.env = env == null ? new LinkedHashMap<>() : new LinkedHashMap<>(env);
    }
}
