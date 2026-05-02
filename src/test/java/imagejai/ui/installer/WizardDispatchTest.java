package imagejai.ui.installer;

import org.junit.Test;
import imagejai.engine.picker.ProviderRegistry;
import imagejai.ui.installer.wizard.BrowserAuthWizard;
import imagejai.ui.installer.wizard.InstallerWizard;
import imagejai.ui.installer.wizard.LocalModelDownloadWizard;
import imagejai.ui.installer.wizard.LocalRuntimeWizard;
import imagejai.ui.installer.wizard.PaidWithCardWizard;
import imagejai.ui.installer.wizard.PureApiKeyWizard;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies that each canonical provider key resolves to the correct
 * install-shape wizard. Mirrors the five shapes in
 * docs/multi_provider/07_implementation_plan.md §E.
 */
public class WizardDispatchTest {

    private static final Set<String> EXPECTED_KEY_SHAPE = new HashSet<String>(Arrays.asList(
            "groq", "cerebras", "openrouter", "mistral", "together",
            "huggingface", "deepseek", "xai", "perplexity"));
    private static final Set<String> EXPECTED_BROWSER_SHAPE = new HashSet<String>(Arrays.asList(
            "gemini", "github-models"));
    private static final Set<String> EXPECTED_PAID_SHAPE = new HashSet<String>(Arrays.asList(
            "anthropic", "openai"));
    private static final Set<String> EXPECTED_RUNTIME_SHAPE = new HashSet<String>(Arrays.asList(
            "ollama-cloud"));
    private static final Set<String> EXPECTED_RUNTIME_MODELS_SHAPE = new HashSet<String>(Arrays.asList(
            "ollama"));

    @Test
    public void everyProviderResolvesToSomeShape() throws IOException {
        Path tmp = Files.createTempDirectory("wizdisp-test");
        ProviderCredentials creds = new ProviderCredentials(tmp);
        // Constructing the panel exercises the default WizardFactory wiring.
        MultiProviderPanel panel = new MultiProviderPanel(ProviderRegistry.empty(), creds);
        assertEquals(15, panel.providerKeys().size());
    }

    @Test
    public void pureApiKeyProvidersClassifiedAsKeyShape() {
        for (String key : EXPECTED_KEY_SHAPE) {
            assertEquals("expected 'key' shape for " + key,
                    "key", MultiProviderPanel.installShapeFor(key));
        }
    }

    @Test
    public void browserAuthProvidersClassifiedAsBrowserShape() {
        for (String key : EXPECTED_BROWSER_SHAPE) {
            assertEquals("expected 'browser' shape for " + key,
                    "browser", MultiProviderPanel.installShapeFor(key));
        }
    }

    @Test
    public void paidWithCardProvidersClassifiedAsPaidShape() {
        for (String key : EXPECTED_PAID_SHAPE) {
            assertEquals("expected 'paid' shape for " + key,
                    "paid", MultiProviderPanel.installShapeFor(key));
        }
    }

    @Test
    public void localRuntimeShapesMatchOllama() {
        for (String key : EXPECTED_RUNTIME_SHAPE) {
            assertEquals("runtime", MultiProviderPanel.installShapeFor(key));
        }
        for (String key : EXPECTED_RUNTIME_MODELS_SHAPE) {
            assertEquals("runtime-models", MultiProviderPanel.installShapeFor(key));
        }
    }

    @Test
    public void defaultFactoryReturnsCorrectWizardClassForEveryShape() throws Exception {
        Path tmp = Files.createTempDirectory("wizdisp-test");
        ProviderCredentials creds = new ProviderCredentials(tmp);
        MultiProviderPanel panel = new MultiProviderPanel(ProviderRegistry.empty(), creds);
        // Reach inside via reflection for the wizardFactory field — keeps the
        // public API small while still letting the test exercise dispatch.
        Field factoryField = MultiProviderPanel.class.getDeclaredField("wizardFactory");
        factoryField.setAccessible(true);
        MultiProviderPanel.WizardFactory factory =
                (MultiProviderPanel.WizardFactory) factoryField.get(panel);

        Map<String, Class<? extends InstallerWizard>> expected = new LinkedHashMap<>();
        expected.put("anthropic", PaidWithCardWizard.class);
        expected.put("openai", PaidWithCardWizard.class);
        expected.put("gemini", BrowserAuthWizard.class);
        expected.put("github-models", BrowserAuthWizard.class);
        expected.put("groq", PureApiKeyWizard.class);
        expected.put("cerebras", PureApiKeyWizard.class);
        expected.put("openrouter", PureApiKeyWizard.class);
        expected.put("mistral", PureApiKeyWizard.class);
        expected.put("together", PureApiKeyWizard.class);
        expected.put("huggingface", PureApiKeyWizard.class);
        expected.put("deepseek", PureApiKeyWizard.class);
        expected.put("xai", PureApiKeyWizard.class);
        expected.put("perplexity", PureApiKeyWizard.class);
        expected.put("ollama-cloud", LocalRuntimeWizard.class);
        expected.put("ollama", LocalModelDownloadWizard.class);

        for (Map.Entry<String, Class<? extends InstallerWizard>> e : expected.entrySet()) {
            InstallerWizard wizard = factory.wizardFor(e.getKey());
            assertNotNull("missing wizard for " + e.getKey(), wizard);
            assertEquals("wrong wizard class for " + e.getKey(),
                    e.getValue(), wizard.getClass());
            assertEquals(e.getKey(), wizard.providerKey());
        }
    }

    @Test
    public void everyShapeAssignmentIsExhaustive() {
        Set<String> covered = new HashSet<String>();
        covered.addAll(EXPECTED_KEY_SHAPE);
        covered.addAll(EXPECTED_BROWSER_SHAPE);
        covered.addAll(EXPECTED_PAID_SHAPE);
        covered.addAll(EXPECTED_RUNTIME_SHAPE);
        covered.addAll(EXPECTED_RUNTIME_MODELS_SHAPE);
        assertTrue("all 15 canonical providers must be classified — missing " + covered,
                covered.containsAll(ProviderRegistry.CANONICAL_PROVIDERS));
    }
}
