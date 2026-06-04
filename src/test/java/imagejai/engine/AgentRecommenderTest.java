package imagejai.engine;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class AgentRecommenderTest {

    private static AgentLauncher.AgentInfo agent(String name, boolean local) {
        return new AgentLauncher.AgentInfo(
                name, name.toLowerCase().replace(' ', '_'),
                name + " description", "/path/" + name, "", local, "");
    }

    @Test
    public void emptyListReturnsLocalAssistant() {
        AgentRecommender.Recommendation rec = AgentRecommender.recommend(
                new ArrayList<AgentLauncher.AgentInfo>(), null, false);
        assertTrue(rec.isLocalAssistant());
        assertNull(rec.agent);
        assertEquals(AgentLauncher.LOCAL_ASSISTANT_NAME, rec.displayName);
        assertNotNull(rec.reason);
        assertFalse(rec.reason.trim().isEmpty());
    }

    @Test
    public void nullListReturnsLocalAssistant() {
        AgentRecommender.Recommendation rec = AgentRecommender.recommend(null, null, true);
        assertTrue(rec.isLocalAssistant());
    }

    @Test
    public void lastUsedPresentReturnsIt() {
        AgentLauncher.AgentInfo claude = agent("Claude Code", false);
        AgentLauncher.AgentInfo aider = agent("Aider", false);
        List<AgentLauncher.AgentInfo> agents = Arrays.asList(claude, aider);

        AgentRecommender.Recommendation rec =
                AgentRecommender.recommend(agents, "Aider", false);

        assertSame(aider, rec.agent);
        assertEquals("Aider", rec.displayName);
    }

    @Test
    public void lastUsedLocalAssistantReturnsLocalAssistant() {
        List<AgentLauncher.AgentInfo> agents = Arrays.asList(agent("Claude Code", false));

        AgentRecommender.Recommendation rec = AgentRecommender.recommend(
                agents, AgentLauncher.LOCAL_ASSISTANT_NAME, false);

        assertTrue(rec.isLocalAssistant());
    }

    @Test
    public void lastUsedAbsentFallsToFirstInstalled() {
        AgentLauncher.AgentInfo claude = agent("Claude Code", false);
        List<AgentLauncher.AgentInfo> agents = Arrays.asList(claude);

        AgentRecommender.Recommendation rec =
                AgentRecommender.recommend(agents, "Codex CLI", false);

        assertSame(claude, rec.agent);
    }

    @Test
    public void onPremisesNeverReturnsCloudAgent() {
        // The eligible list is posture-filtered to locals only; the last-used
        // agent was a cloud agent that is now hidden by on-premises.
        AgentLauncher.AgentInfo gemma = agent("Gemma 4 31B", true);
        List<AgentLauncher.AgentInfo> agents = Arrays.asList(gemma);

        AgentRecommender.Recommendation rec =
                AgentRecommender.recommend(agents, "Claude Code", true);

        assertNotNull(rec.agent);
        assertTrue("recommended agent must be local under on-premises",
                rec.agent.isLocal());
        assertSame(gemma, rec.agent);
    }

    @Test
    public void onPremisesEmptyEligibleReturnsLocalAssistant() {
        AgentRecommender.Recommendation rec = AgentRecommender.recommend(
                new ArrayList<AgentLauncher.AgentInfo>(), "Claude Code", true);

        assertTrue(rec.isLocalAssistant());
    }
}
