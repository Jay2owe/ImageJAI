package imagejai.ui.picker;

import imagejai.engine.picker.ModelEntry;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * First-use-of-paid-or-uncurated-model dialog.
 *
 * <p>Three flavours per docs/multi_provider/06_tier_safety.md §3:
 * 🔵 pay-as-you-go ({@link Variant#PAID}), 🟣 subscription
 * ({@link Variant#SUBSCRIPTION}), ⚪ uncurated ({@link Variant#UNCURATED}).
 *
 * <p>DOCUMENT_MODAL — Fiji image windows stay interactive while the dialog is
 * up. Three exits: {@link Result#CONTINUE}, {@link Result#PICK_FREE}, and the
 * {@link Result#CANCEL} default that fires when the user closes via [×]
 * (treated as {@code PICK_FREE} per 06 §3.2 — never silently launches).
 *
 * <p>The "Don't ask again for {provider} this session" checkbox is
 * <em>per-session, per-provider</em>; the in-memory set lives on
 * {@link ProviderTierGate}, not on disk (06 §3.4).
 */
public class FirstUseDialog extends JDialog {

    public enum Variant {
        PAID, SUBSCRIPTION, UNCURATED
    }

    public enum Result {
        CONTINUE,
        PICK_FREE,
        CANCEL
    }

    private final Variant variant;
    private final ModelEntry entry;
    private final String providerDisplay;
    private Result result = Result.CANCEL;
    private JCheckBox dontAskAgain;

    public FirstUseDialog(Frame owner, ModelEntry entry, Variant variant) {
        this(owner, entry, variant, providerDisplayName(entry));
    }

    public FirstUseDialog(Frame owner, ModelEntry entry, Variant variant, String providerDisplay) {
        super(owner, titleFor(variant), true);
        this.variant = variant == null ? Variant.PAID : variant;
        this.entry = entry;
        this.providerDisplay = providerDisplay == null || providerDisplay.isEmpty()
                ? providerDisplayName(entry)
                : providerDisplay;
        setModalityType(Dialog.ModalityType.DOCUMENT_MODAL);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // [×] is equivalent to "Use a free/curated model instead" — never
                // silently launches per 06 §3.2.
                result = Result.PICK_FREE;
                setVisible(false);
            }
        });
        buildUi();
        pack();
        Dimension preferred = getPreferredSize();
        setSize(Math.max(preferred.width, 540), Math.max(preferred.height, 280));
        setLocationRelativeTo(owner);
    }

    private void buildUi() {
        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(new EmptyBorder(16, 18, 12, 18));
        content.setBackground(Color.WHITE);

        JLabel headline = new JLabel(headlineFor(variant));
        headline.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        content.add(headline, BorderLayout.NORTH);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        body.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));

        JLabel detail = new JLabel(bodyHtml());
        detail.setAlignmentX(LEFT_ALIGNMENT);
        body.add(detail);
        body.add(Box.createVerticalStrut(8));

        dontAskAgain = new JCheckBox(checkboxLabel());
        dontAskAgain.setOpaque(false);
        dontAskAgain.setAlignmentX(LEFT_ALIGNMENT);
        body.add(dontAskAgain);

        content.add(body, BorderLayout.CENTER);
        content.add(buildButtons(), BorderLayout.SOUTH);
        setContentPane(content);
    }

    private JPanel buildButtons() {
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        buttons.setOpaque(false);
        JButton pickFree = new JButton(cancelButtonLabel());
        pickFree.addActionListener(e -> {
            result = Result.PICK_FREE;
            setVisible(false);
        });
        JButton cont = new JButton("Continue");
        cont.addActionListener(e -> {
            result = Result.CONTINUE;
            setVisible(false);
        });
        buttons.add(pickFree);
        buttons.add(cont);
        getRootPane().setDefaultButton(cont);
        return buttons;
    }

    private static String titleFor(Variant variant) {
        if (variant == Variant.SUBSCRIPTION) {
            return "Heads up: this model needs a subscription";
        }
        if (variant == Variant.UNCURATED) {
            return "This model has not been verified";
        }
        return "Heads up: this model charges per use";
    }

    private static String headlineFor(Variant variant) {
        if (variant == Variant.SUBSCRIPTION) {
            return "Heads up: this model needs a subscription";
        }
        if (variant == Variant.UNCURATED) {
            return "This model has not been verified";
        }
        return "Heads up: this model charges per use";
    }

    private String bodyHtml() {
        String name = entry == null ? "this model" : entry.displayName();
        String provider = providerDisplay;
        if (variant == Variant.SUBSCRIPTION) {
            return "<html><div style='width:460px'>"
                    + "<p>" + escape(name) + " (" + escape(provider) + ") requires an active "
                    + escape(provider) + " subscription, charged by the provider directly.</p>"
                    + "<p>ImageJAI does not handle the subscription &mdash; you sign up on the "
                    + "provider's site. Without an active subscription this request will simply "
                    + "fail; you will not be charged anything through ImageJAI.</p>"
                    + "</div></html>";
        }
        if (variant == Variant.UNCURATED) {
            return "<html><div style='width:460px'>"
                    + "<p>" + escape(name) + " was auto-detected from " + escape(provider)
                    + ". ImageJAI has not verified its pricing, tool-call reliability, "
                    + "or vision support.</p>"
                    + "<p><b>What this means</b><br>"
                    + "&middot; It may charge you (check the provider's pricing page).<br>"
                    + "&middot; It may produce malformed tool calls and break your "
                    + "analysis loop. ImageJAI will retry, but you may need to switch "
                    + "to a curated model.</p>"
                    + "</div></html>";
        }
        // PAID
        return "<html><div style='width:460px'>"
                + "<p>" + escape(name) + " (" + escape(provider)
                + ") is a pay-as-you-go model.</p>"
                + "<p><b>Pricing</b><br>"
                + "Charged per million input/output tokens; see the provider's "
                + "pricing page for current rates.</p>"
                + "<p>A typical ImageJAI session runs $0.05 &ndash; $0.40, but a long "
                + "batch with vision can go higher.</p>"
                + "<p>You will only be charged if you have already added credit or a "
                + "card to your " + escape(provider) + " account. If not, the request "
                + "will fail and ImageJAI will tell you what to do.</p>"
                + "</div></html>";
    }

    private String checkboxLabel() {
        if (variant == Variant.UNCURATED) {
            return "Don't ask again for unverified " + providerDisplay + " models this session";
        }
        return "Don't ask again for " + providerDisplay + " this session";
    }

    private String cancelButtonLabel() {
        if (variant == Variant.UNCURATED) {
            return "Use a curated model instead";
        }
        return "Use a free model instead";
    }

    private static String providerDisplayName(ModelEntry entry) {
        if (entry == null || entry.providerId() == null) {
            return "this provider";
        }
        switch (entry.providerId()) {
            case "anthropic": return "Anthropic";
            case "openai": return "OpenAI";
            case "gemini": return "Google Gemini";
            case "groq": return "Groq";
            case "cerebras": return "Cerebras";
            case "openrouter": return "OpenRouter";
            case "github-models": return "GitHub Models";
            case "mistral": return "Mistral";
            case "together": return "Together AI";
            case "huggingface": return "HuggingFace";
            case "deepseek": return "DeepSeek";
            case "xai": return "xAI";
            case "perplexity": return "Perplexity";
            case "ollama-cloud": return "Ollama Cloud";
            case "ollama": return "Ollama (local)";
            default: return entry.providerId();
        }
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    public Result showAndAwait() {
        setVisible(true);
        return result;
    }

    /** Whether the user ticked the "don't ask again" box on close. */
    public boolean dontAskAgainChecked() {
        return dontAskAgain != null && dontAskAgain.isSelected();
    }

    /** Test-only seam — set the result directly without showing the dialog. */
    void setResultForTest(Result result) {
        this.result = result == null ? Result.CANCEL : result;
    }

    /** Test-only seam — toggle the "don't ask again" state programmatically. */
    void setDontAskAgainForTest(boolean checked) {
        if (dontAskAgain != null) {
            dontAskAgain.setSelected(checked);
        }
    }

    public Variant variant() {
        return variant;
    }
}
