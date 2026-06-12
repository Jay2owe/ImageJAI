package imagejai.engine.picker;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

/**
 * Covers {@link ProviderRegistry#withProvider} — the hook that injects the
 * synthetic {@code "cli"} provider (installed CLI agents) into the otherwise
 * API-only registry so the default-on picker keeps showing them and legacy
 * {@code cli:<command>} selections resolve.
 */
public class ProviderRegistryCliInjectionTest {

    private static ModelEntry cliAgent(String command, String name) {
        return new ModelEntry("cli", command, name, "", ModelEntry.Tier.FREE, 0,
                false, ModelEntry.Reliability.HIGH, false, true, "");
    }

    @Test
    public void withProviderInjectsCliAndResolvesLookup() {
        ProviderRegistry base = ProviderRegistry.empty();
        ProviderEntry cli = new ProviderEntry("cli", "CLI agents",
                ProviderEntry.Status.READY, "",
                Arrays.asList(cliAgent("claude", "Claude Code"),
                        cliAgent("aider", "Aider")));

        ProviderRegistry r = base.withProvider(cli);

        assertNotNull("cli:claude must resolve", r.lookup("cli", "claude"));
        assertNotNull("cli:aider must resolve", r.lookup("cli", "aider"));
        assertNotNull("cli provider group present", r.provider("cli"));
        // Canonical API providers remain.
        assertNotNull(r.provider("anthropic"));
        assertNotNull(r.provider("groq"));
    }

    @Test
    public void cliProviderRendersFirst() {
        ProviderRegistry r = ProviderRegistry.empty().withProvider(
                new ProviderEntry("cli", "CLI agents", ProviderEntry.Status.READY,
                        "", Collections.singletonList(cliAgent("claude", "Claude Code"))));
        assertTrue("cli should be the first provider group",
                r.providersList().get(0).providerId().equals("cli"));
    }

    @Test
    public void withProviderNullOrEmptyIsNoop() {
        ProviderRegistry base = ProviderRegistry.empty();
        assertSame(base, base.withProvider(null));
        assertSame(base, base.withProvider(new ProviderEntry("cli", "CLI agents",
                ProviderEntry.Status.READY, "", Collections.<ModelEntry>emptyList())));
        // A canonical provider that exists is untouched by a no-op injection.
        assertNull(base.lookup("cli", "claude"));
    }
}
