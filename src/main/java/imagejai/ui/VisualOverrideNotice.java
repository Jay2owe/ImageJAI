package imagejai.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import imagejai.engine.EventBus;
import imagejai.engine.security.VisualOverrideRegistry;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;

/**
 * Non-modal Data Governance notice for one-shot full-resolution visual grants.
 */
public final class VisualOverrideNotice extends JPanel implements EventBus.Listener {
    private static final String TOPIC = "data_governance.visual.requested";
    private static final Color BG = new Color(38, 43, 47);
    private static final Color BORDER = new Color(82, 132, 163);
    private static final Color TEXT = new Color(224, 238, 246);

    private final EventBus eventBus;
    private final VisualOverrideRegistry registry;
    private final JLabel detail;
    private String pendingSessionId = "default";
    private String pendingReason = "";
    private String pendingRequestId = "";

    public VisualOverrideNotice() {
        this(EventBus.getInstance(), VisualOverrideRegistry.getInstance());
    }

    VisualOverrideNotice(EventBus eventBus, VisualOverrideRegistry registry) {
        super(new BorderLayout(8, 0));
        this.eventBus = eventBus == null ? EventBus.getInstance() : eventBus;
        this.registry = registry == null ? VisualOverrideRegistry.getInstance() : registry;

        setBackground(BG);
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER),
                new EmptyBorder(6, 8, 6, 8)));

        detail = new JLabel();
        detail.setForeground(TEXT);
        detail.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        add(detail, BorderLayout.CENTER);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        buttons.setOpaque(false);
        JButton allow = new JButton("Allow once");
        allow.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        allow.addActionListener(e -> allow());
        JButton deny = new JButton("Deny");
        deny.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        deny.addActionListener(e -> deny());
        buttons.add(allow);
        buttons.add(deny);
        add(buttons, BorderLayout.EAST);

        setVisible(false);
        this.eventBus.subscribe(TOPIC, this);
    }

    @Override
    public void onEvent(JsonObject frame) {
        if (frame == null || !TOPIC.equals(optString(frame, "event", ""))) {
            return;
        }
        JsonObject data = object(frame.get("data"));
        final String session = optString(data, "session", "default");
        final String reason = optString(data, "reason", "");
        final String requestId = optString(data, "request_id", "");
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                showRequest(session, reason, requestId);
            }
        });
    }

    public void dispose() {
        eventBus.unsubscribe(this);
    }

    void showRequest(String sessionId, String reason, String requestId) {
        pendingSessionId = clean(sessionId).isEmpty() ? "default" : clean(sessionId);
        pendingReason = clean(reason);
        pendingRequestId = clean(requestId);
        String suffix = clean(requestId).isEmpty() ? "" : " (" + escape(requestId) + ")";
        detail.setText("<html><b>Full-resolution visual request" + suffix
                + ":</b> " + escape(pendingReason.isEmpty()
                ? "No reason supplied." : pendingReason) + "</html>");
        setVisible(true);
        revalidate();
        repaint();
    }

    void allowForTest() {
        allow();
    }

    void denyForTest() {
        deny();
    }

    String detailTextForTest() {
        return detail.getText();
    }

    private void allow() {
        registry.grant(pendingSessionId, pendingRequestId, pendingReason);
        hideNotice();
    }

    private void deny() {
        registry.deny(pendingSessionId);
        hideNotice();
    }

    private void hideNotice() {
        setVisible(false);
        pendingSessionId = "default";
        pendingReason = "";
        pendingRequestId = "";
    }

    private static JsonObject object(JsonElement element) {
        return element != null && element.isJsonObject()
                ? element.getAsJsonObject()
                : new JsonObject();
    }

    private static String optString(JsonObject object, String key, String fallback) {
        if (object == null) {
            return fallback;
        }
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive()
                ? clean(value.getAsString())
                : fallback;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String escape(String value) {
        return clean(value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
