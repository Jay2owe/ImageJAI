package imagejai.engine;

import java.util.List;

/**
 * Picks the agent the Welcome CTA and the working-state switch picker should
 * default to, with a short plain-language reason. Pure decision logic — no
 * Swing, no process launch — so it stays unit-testable off the EDT.
 *
 * <p>The input list is the already posture-filtered output of
 * {@link AgentLauncher#detectAgents()}, so under on-premises posture it can only
 * contain local agents and the recommendation can never name a filtered-out one
 * (no dead-ends). The always-present built-in Local Assistant is the final
 * fallback and is represented by a {@code null} {@link Recommendation#agent}.
 */
public final class AgentRecommender {

    /**
     * A recommended choice. {@link #agent} is {@code null} when the pick is the
     * built-in Local Assistant (which has no detected {@link AgentLauncher.AgentInfo}).
     */
    public static final class Recommendation {
        public final AgentLauncher.AgentInfo agent; // null => built-in Local Assistant
        public final String displayName;
        public final String reason;

        Recommendation(AgentLauncher.AgentInfo agent, String displayName, String reason) {
            this.agent = agent;
            this.displayName = displayName;
            this.reason = reason;
        }

        /** True when the recommendation is the built-in, no-process Local Assistant. */
        public boolean isLocalAssistant() {
            return agent == null;
        }
    }

    private AgentRecommender() {
    }

    /**
     * Rank the eligible agents and return the default pick.
     *
     * @param eligibleAgents posture-filtered detected agents (may be {@code null}/empty)
     * @param lastUsedName   display name of the last-launched agent, or {@code null}
     * @param onPremises     {@code true} when the active posture is on-premises
     */
    public static Recommendation recommend(List<AgentLauncher.AgentInfo> eligibleAgents,
                                           String lastUsedName, boolean onPremises) {
        // 1. Respect the user's last choice, if it is still available.
        if (lastUsedName != null && !lastUsedName.trim().isEmpty()) {
            String trimmed = lastUsedName.trim();
            if (AgentLauncher.LOCAL_ASSISTANT_NAME.equals(trimmed)) {
                return new Recommendation(null, AgentLauncher.LOCAL_ASSISTANT_NAME,
                        "your last assistant");
            }
            AgentLauncher.AgentInfo last = findByName(eligibleAgents, trimmed);
            if (last != null) {
                return new Recommendation(last, last.name, "your last assistant");
            }
            // Last-used is no longer eligible (e.g. a cloud agent now hidden by
            // on-premises) — fall through rather than name something unavailable.
        }

        // 2. The first installed agent, preferring a local one under on-premises.
        if (eligibleAgents != null && !eligibleAgents.isEmpty()) {
            if (onPremises) {
                AgentLauncher.AgentInfo local = firstLocal(eligibleAgents);
                if (local != null) {
                    return new Recommendation(local, local.name,
                            "installed, runs on this machine");
                }
            }
            AgentLauncher.AgentInfo first = eligibleAgents.get(0);
            String reason = first.isLocal()
                    ? "installed, runs on this machine"
                    : "installed on this machine";
            return new Recommendation(first, first.name, reason);
        }

        // 3. The always-present built-in Local Assistant.
        return new Recommendation(null, AgentLauncher.LOCAL_ASSISTANT_NAME,
                "built-in, works offline, no setup");
    }

    private static AgentLauncher.AgentInfo findByName(
            List<AgentLauncher.AgentInfo> agents, String name) {
        if (agents == null) {
            return null;
        }
        for (AgentLauncher.AgentInfo a : agents) {
            if (a != null && name.equals(a.name)) {
                return a;
            }
        }
        return null;
    }

    private static AgentLauncher.AgentInfo firstLocal(List<AgentLauncher.AgentInfo> agents) {
        if (agents == null) {
            return null;
        }
        for (AgentLauncher.AgentInfo a : agents) {
            if (a != null && a.isLocal()) {
                return a;
            }
        }
        return null;
    }
}
