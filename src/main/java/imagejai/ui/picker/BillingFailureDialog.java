package imagejai.ui.picker;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.WindowConstants;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.net.URI;

/**
 * Mid-session billing-failure dialog per
 * docs/multi_provider/06_tier_safety.md §3.7.
 *
 * <p>Fires when the agent loop catches a 401/402/429-billing response from a
 * paid provider. Surfaces the provider's actual error string verbatim so the
 * user can tell whether it was "no card on file", "rate limit", or "expired
 * key" — but rewraps the next-step language in plain English.
 *
 * <p>Three exits, mirroring {@link FirstUseDialog}'s scaffolding:
 * <ul>
 *   <li>{@link Result#SWITCH_MODEL} — caller re-opens the picker filtered to
 *       free-tier rows. Same affordance as the FirstUseDialog cancel path.</li>
 *   <li>{@link Result#OPEN_CONSOLE} — opens the provider's billing console in
 *       the default browser via {@link Desktop#browse(URI)}. Falls back to
 *       printing the URL if {@code Desktop} is unavailable on a headless box,
 *       per Phase H risk-mitigation §H "Browser callback for 'Open … console'".</li>
 *   <li>{@link Result#CLOSE} — user dismisses; loop should pause without
 *       re-launching.</li>
 * </ul>
 *
 * <p>DOCUMENT_MODAL parented to the supplied {@link Frame} so Fiji image
 * windows stay interactive while the dialog is up — same pattern as
 * {@link FirstUseDialog}.
 */
public class BillingFailureDialog extends JDialog {

    public enum Result {
        SWITCH_MODEL,
        OPEN_CONSOLE,
        CLOSE
    }

    /** Test seam — replace {@link Desktop#browse(URI)} so unit tests stay headless. */
    public interface BrowserOpener {
        boolean open(URI uri);
    }

    private final String providerDisplay;
    private final String errorBody;
    private final URI consoleUri;
    private final BrowserOpener browserOpener;
    private Result result = Result.CLOSE;

    public BillingFailureDialog(Frame owner,
                                String providerDisplay,
                                String errorBody,
                                URI consoleUri) {
        this(owner, providerDisplay, errorBody, consoleUri, defaultBrowserOpener());
    }

    public BillingFailureDialog(Frame owner,
                                String providerDisplay,
                                String errorBody,
                                URI consoleUri,
                                BrowserOpener browserOpener) {
        super(owner, providerDisplay + " refused the request", true);
        this.providerDisplay = providerDisplay == null || providerDisplay.isEmpty()
                ? "The provider"
                : providerDisplay;
        this.errorBody = errorBody == null || errorBody.isEmpty()
                ? "(no error body returned)"
                : errorBody;
        this.consoleUri = consoleUri;
        this.browserOpener = browserOpener == null ? defaultBrowserOpener() : browserOpener;
        setModalityType(Dialog.ModalityType.DOCUMENT_MODAL);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                // Same convention as FirstUseDialog — [×] never silently
                // re-launches; treat as Close.
                result = Result.CLOSE;
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

        JLabel headline = new JLabel(this.providerDisplay + " refused the request");
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

        content.add(body, BorderLayout.CENTER);
        content.add(buildButtons(), BorderLayout.SOUTH);
        setContentPane(content);
    }

    private String bodyHtml() {
        return "<html><div style='width:460px'>"
                + "<p>" + escape(this.providerDisplay) + " returned: <b>"
                + escape(this.errorBody) + "</b>.</p>"
                + "<p>This usually means there is no payment method or "
                + "credit on your " + escape(this.providerDisplay) + " account, "
                + "or the API key is rate-limited or expired. ImageJAI did "
                + "not charge you and cannot top up your balance for you.</p>"
                + "<p><b>Next steps</b><br>"
                + "&middot; Add credit / a payment method on the provider's "
                + "console (recommended)<br>"
                + "&middot; Or switch to a free model (Gemini Flash, Groq, "
                + "Ollama)</p>"
                + "</div></html>";
    }

    private JPanel buildButtons() {
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        buttons.setOpaque(false);

        JButton switchModel = new JButton("Switch model");
        switchModel.addActionListener(e -> {
            result = Result.SWITCH_MODEL;
            setVisible(false);
        });
        buttons.add(switchModel);

        JButton openConsole = new JButton(consoleButtonLabel());
        openConsole.setEnabled(consoleUri != null);
        openConsole.addActionListener(e -> {
            result = Result.OPEN_CONSOLE;
            if (consoleUri != null) {
                boolean opened = browserOpener.open(consoleUri);
                if (!opened) {
                    JOptionPane.showMessageDialog(this,
                            "Open this URL manually:\n" + consoleUri,
                            "Browser unavailable",
                            JOptionPane.INFORMATION_MESSAGE);
                }
            }
            setVisible(false);
        });
        buttons.add(openConsole);

        JButton close = new JButton("Close");
        close.addActionListener(e -> {
            result = Result.CLOSE;
            setVisible(false);
        });
        buttons.add(close);

        getRootPane().setDefaultButton(close);
        return buttons;
    }

    private String consoleButtonLabel() {
        return "Open " + this.providerDisplay + " console";
    }

    public Result showAndAwait() {
        setVisible(true);
        return result;
    }

    /** Test-only seam — set the result directly without showing the dialog. */
    void setResultForTest(Result result) {
        this.result = result == null ? Result.CLOSE : result;
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static BrowserOpener defaultBrowserOpener() {
        return uri -> {
            if (uri == null) {
                return false;
            }
            try {
                if (!Desktop.isDesktopSupported()) {
                    return false;
                }
                Desktop desktop = Desktop.getDesktop();
                if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                    return false;
                }
                desktop.browse(uri);
                return true;
            } catch (Exception ex) {
                return false;
            }
        };
    }
}
