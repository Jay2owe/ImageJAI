package imagejai.ui.installer;

import imagejai.config.Settings;
import imagejai.engine.picker.ModelEntry;
import imagejai.engine.picker.ProviderEntry;
import imagejai.engine.picker.ProviderRegistry;
import imagejai.ui.installer.wizard.BrowserAuthWizard;
import imagejai.ui.installer.wizard.InstallerWizard;
import imagejai.ui.installer.wizard.LocalModelDownloadWizard;
import imagejai.ui.installer.wizard.LocalRuntimeWizard;
import imagejai.ui.installer.wizard.PaidWithCardWizard;
import imagejai.ui.installer.wizard.PureApiKeyWizard;

import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Rectangle;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Settings tab that exposes one card per provider. Cards show the current
 * configuration status (✓/⚠/✗) and a button that opens the matching install
 * wizard. The dropdown's ⚠ status icon click deep-links here through
 * {@link #scrollTo(String)}.
 *
 * <p>Implements docs/multi_provider/05_ui_design.md §9 (settings touchpoints)
 * and §7 (status-icon click flow).
 */
public class MultiProviderPanel extends JPanel {

    /** Curated descriptions per provider — fallback to the first model's description otherwise. */
    private static final Map<String, ProviderMeta> META;
    static {
        Map<String, ProviderMeta> m = new LinkedHashMap<String, ProviderMeta>();
        m.put("anthropic", new ProviderMeta(
                "Anthropic", "Claude (Opus, Sonnet, Haiku) — strongest agent for long Fiji sessions.",
                ProviderCard.CostTier.PAID, "paid",
                "https://console.anthropic.com/settings/keys"));
        m.put("openai", new ProviderMeta(
                "OpenAI", "GPT-5 / GPT-4 family — gold-standard tool reliability.",
                ProviderCard.CostTier.PAID, "paid",
                "https://platform.openai.com/api-keys"));
        m.put("gemini", new ProviderMeta(
                "Google Gemini", "Vision-capable, frontier-class free tier.",
                ProviderCard.CostTier.FREE_WITH_LIMITS, "browser",
                "https://aistudio.google.com/app/apikey"));
        m.put("groq", new ProviderMeta(
                "Groq", "Open-weight models on LPU silicon — 4-10× faster than GPU providers.",
                ProviderCard.CostTier.FREE_WITH_LIMITS, "key",
                "https://console.groq.com/keys"));
        m.put("cerebras", new ProviderMeta(
                "Cerebras", "Wafer-scale chips — highest tok/s anywhere. 1M tokens/day free.",
                ProviderCard.CostTier.FREE_WITH_LIMITS, "key",
                "https://cloud.cerebras.ai/platform/keys"));
        m.put("openrouter", new ProviderMeta(
                "OpenRouter", "Single key for 300+ models behind one OpenAI-shaped API.",
                ProviderCard.CostTier.FREE_WITH_LIMITS, "key",
                "https://openrouter.ai/keys"));
        m.put("github-models", new ProviderMeta(
                "GitHub Models", "Free taste of GPT-5/Claude/Llama-4 with a GitHub PAT.",
                ProviderCard.CostTier.FREE_WITH_LIMITS, "browser",
                "https://github.com/settings/tokens"));
        m.put("mistral", new ProviderMeta(
                "Mistral", "EU-hosted GDPR-friendly Mistral Large/Medium/Small line.",
                ProviderCard.CostTier.PAID, "key",
                "https://console.mistral.ai/api-keys"));
        m.put("together", new ProviderMeta(
                "Together AI", "Cheapest serverless host for Llama, Qwen, DeepSeek, GLM.",
                ProviderCard.CostTier.PAID, "key",
                "https://api.together.ai/settings/api-keys"));
        m.put("huggingface", new ProviderMeta(
                "HuggingFace", "Inference Providers gateway — one key, multiple backends.",
                ProviderCard.CostTier.PAID, "key",
                "https://huggingface.co/settings/tokens"));
        m.put("deepseek", new ProviderMeta(
                "DeepSeek", "Open-weight V4/R1 — reasoning + tools, off-peak discounts.",
                ProviderCard.CostTier.PAID, "key",
                "https://platform.deepseek.com/api_keys"));
        m.put("xai", new ProviderMeta(
                "xAI Grok", "Long-context Grok 4 family — Grok 4.1 Fast competes on price.",
                ProviderCard.CostTier.PAID, "key",
                "https://console.x.ai/keys"));
        m.put("perplexity", new ProviderMeta(
                "Perplexity", "Search-augmented LLMs — grounded in live web results.",
                ProviderCard.CostTier.PAID, "key",
                "https://www.perplexity.ai/settings/api"));
        m.put("ollama-cloud", new ProviderMeta(
                "Ollama Cloud", "Free-with-limits frontier-scale open models behind browser sign-in.",
                ProviderCard.CostTier.FREE_WITH_LIMITS, "runtime",
                "https://ollama.com/cloud"));
        m.put("ollama", new ProviderMeta(
                "Ollama (local)", "Runs on your computer — no API key, no quota.",
                ProviderCard.CostTier.FREE, "runtime-models",
                "https://ollama.com/download"));
        META = Collections.unmodifiableMap(m);
    }

    /** Order in which cards render — mirrors the dropdown order. */
    static final List<String> CARD_ORDER = Collections.unmodifiableList(Arrays.asList(
            "ollama", "ollama-cloud", "anthropic", "openai", "gemini",
            "groq", "cerebras", "openrouter", "github-models", "mistral",
            "together", "huggingface", "deepseek", "xai", "perplexity"));

    private final ProviderRegistry registry;
    private final ProviderCredentials credentials;
    private final WizardFactory wizardFactory;
    private final Map<String, ProviderCard> cards = new LinkedHashMap<String, ProviderCard>();
    private final JPanel cardStack;
    private final JScrollPane scroll;
    private TierSafetyPanel tierSafetyPanel;

    /** Test seam — lets fixtures replace the wizard implementations. */
    public interface WizardFactory {
        InstallerWizard wizardFor(String providerKey);
    }

    public MultiProviderPanel(ProviderRegistry registry, ProviderCredentials credentials) {
        this(registry, credentials, defaultFactory(registry, credentials), null);
    }

    public MultiProviderPanel(ProviderRegistry registry,
                              ProviderCredentials credentials,
                              Settings settings) {
        this(registry, credentials, defaultFactory(registry, credentials), settings);
    }

    public MultiProviderPanel(ProviderRegistry registry,
                              ProviderCredentials credentials,
                              WizardFactory wizardFactory) {
        this(registry, credentials, wizardFactory, null);
    }

    public MultiProviderPanel(ProviderRegistry registry,
                              ProviderCredentials credentials,
                              WizardFactory wizardFactory,
                              Settings settings) {
        super(new BorderLayout());
        this.registry = registry == null ? ProviderRegistry.empty() : registry;
        this.credentials = credentials;
        this.wizardFactory = wizardFactory;
        setBorder(new EmptyBorder(10, 10, 10, 10));

        cardStack = new JPanel();
        cardStack.setLayout(new BoxLayout(cardStack, BoxLayout.Y_AXIS));
        if (settings != null) {
            tierSafetyPanel = new TierSafetyPanel(settings);
            cardStack.add(tierSafetyPanel);
            cardStack.add(javax.swing.Box.createVerticalStrut(10));
        }
        for (String key : CARD_ORDER) {
            ProviderCard card = buildCard(key);
            cards.put(key, card);
            cardStack.add(card);
            cardStack.add(javax.swing.Box.createVerticalStrut(6));
        }
        scroll = new JScrollPane(cardStack);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
    }

    /** Tier-safety section — null when constructed without a Settings instance (test seam). */
    public TierSafetyPanel tierSafetyPanel() {
        return tierSafetyPanel;
    }

    /** Names of every provider currently shown as a card. */
    public java.util.Set<String> providerKeys() {
        return Collections.unmodifiableSet(cards.keySet());
    }

    /** Refresh every card's status from disk. */
    public void refreshAll() {
        for (Map.Entry<String, ProviderCard> entry : cards.entrySet()) {
            entry.getValue().updateStatus(deriveStatus(entry.getKey()));
        }
    }

    /** Status of a card at this moment — visible for tests. */
    public ProviderCard.Status statusOf(String providerKey) {
        ProviderCard card = cards.get(providerKey);
        if (card == null) {
            return ProviderCard.Status.NEEDS_SETUP;
        }
        return deriveStatus(providerKey);
    }

    /** Look up the card for one provider — visible for tests. */
    public ProviderCard cardFor(String providerKey) {
        return cards.get(providerKey);
    }

    /** Scroll the named provider's card into view (deep-link from dropdown). */
    public void scrollTo(String providerKey) {
        ProviderCard card = cards.get(providerKey);
        if (card == null) {
            return;
        }
        Rectangle bounds = card.getBounds();
        cardStack.scrollRectToVisible(bounds);
        JViewport viewport = scroll.getViewport();
        viewport.setViewPosition(new java.awt.Point(0, Math.max(0, bounds.y - 8)));
        card.requestFocusInWindow();
    }

    /** Trigger the wizard programmatically — used by the dropdown deep-link. */
    public void launchWizard(String providerKey) {
        ProviderCard card = cards.get(providerKey);
        if (card != null) {
            card.actionButton().doClick();
        }
    }

    private ProviderCard buildCard(final String key) {
        ProviderMeta meta = META.get(key);
        String displayName = meta != null ? meta.displayName : key;
        String description = meta != null ? meta.description : "";
        ProviderCard.CostTier tier = meta != null ? meta.tier : ProviderCard.CostTier.PAID;

        ProviderCard card = new ProviderCard(key, displayName, description,
                deriveStatus(key), tier);
        card.setActionListener(e -> handleWizardClick(card, key));
        return card;
    }

    private void handleWizardClick(final ProviderCard card, final String providerKey) {
        InstallerWizard wizard = wizardFactory == null ? null : wizardFactory.wizardFor(providerKey);
        if (wizard == null) {
            return;
        }
        boolean saved = wizard.showAndSave(card);
        if (saved) {
            card.updateStatus(deriveStatus(providerKey));
        }
    }

    private ProviderCard.Status deriveStatus(String providerKey) {
        if ("ollama".equals(providerKey)) {
            // Local Ollama only needs the daemon to be reachable; the saved
            // OLLAMA_API_BASE is enough for the proxy.
            if (credentials != null && credentials.hasCredentials("ollama")) {
                return ProviderCard.Status.READY;
            }
            // Default daemon URL is a safe assumption — surface as ready so the
            // card doesn't nag users on a fresh install.
            return ProviderCard.Status.READY;
        }
        if (credentials != null && credentials.hasCredentials(providerKey)) {
            return ProviderCard.Status.READY;
        }
        return ProviderCard.Status.NEEDS_SETUP;
    }

    /** Map a provider key to the wizard shape it uses. Visible for tests. */
    public static String installShapeFor(String providerKey) {
        ProviderMeta meta = META.get(providerKey);
        return meta == null ? "key" : meta.shape;
    }

    private static WizardFactory defaultFactory(final ProviderRegistry registry,
                                                final ProviderCredentials credentials) {
        return providerKey -> {
            ProviderMeta meta = META.get(providerKey);
            if (meta == null) {
                return null;
            }
            switch (meta.shape) {
                case "paid":
                    return new PaidWithCardWizard(providerKey, meta.displayName,
                            meta.signupUrl, credentials);
                case "browser":
                    return new BrowserAuthWizard(providerKey, meta.displayName,
                            meta.signupUrl, cliHintFor(providerKey), credentials);
                case "runtime":
                    return new LocalRuntimeWizard(providerKey, credentials);
                case "runtime-models":
                    return new LocalModelDownloadWizard(registry, credentials);
                case "key":
                default:
                    return new PureApiKeyWizard(providerKey, meta.displayName,
                            meta.signupUrl, credentials);
            }
        };
    }

    private static String cliHintFor(String providerKey) {
        switch (providerKey) {
            case "github-models": return "gh auth login --scopes models:read";
            case "gemini": return "gcloud auth application-default login (Vertex path)";
            default: return "";
        }
    }

    /** Resolve description fallback from registry when curated meta lacks one. */
    static String descriptionForRegistry(ProviderRegistry registry, String providerKey) {
        if (registry == null) {
            return "";
        }
        ProviderEntry entry = registry.provider(providerKey);
        if (entry == null) {
            return "";
        }
        for (ModelEntry model : entry.models()) {
            String desc = model.description();
            if (desc != null && !desc.isEmpty()) {
                return desc;
            }
        }
        return "";
    }

    private static final class ProviderMeta {
        final String displayName;
        final String description;
        final ProviderCard.CostTier tier;
        final String shape;
        final String signupUrl;

        ProviderMeta(String displayName,
                     String description,
                     ProviderCard.CostTier tier,
                     String shape,
                     String signupUrl) {
            this.displayName = displayName;
            this.description = description;
            this.tier = tier;
            this.shape = shape;
            this.signupUrl = signupUrl;
        }
    }
}
