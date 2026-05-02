package imagejai.ui.picker;

import imagejai.engine.picker.ModelEntry;

import javax.swing.JMenuItem;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;

/**
 * Custom JMenuItem that renders five fixed-width columns: tier badge, status
 * icon, pin star, display name, right-aligned provider hint. Layout per
 * docs/multi_provider/05_ui_design.md §3.2.
 */
public class ModelMenuItem extends JMenuItem {

    static final int COL_BADGE  = 6;
    static final int COL_STATUS = 26;
    static final int COL_STAR   = 44;
    static final int COL_TEXT   = 64;
    static final int ROW_HEIGHT = 24;
    static final int ICON_HIT_WIDTH = 14;

    private final ModelEntry entry;
    private boolean starHovered;
    private boolean pinned;
    private final PinToggleListener pinToggleListener;

    /** Callback invoked when the user clicks the star column. */
    public interface PinToggleListener {
        void onPinToggled(ModelEntry entry, boolean nowPinned);
    }

    public ModelMenuItem(ModelEntry entry, PinToggleListener pinToggleListener) {
        super(entry.displayName());
        this.entry = entry;
        this.pinned = entry.pinned();
        this.pinToggleListener = pinToggleListener;
        setPreferredSize(new Dimension(380, ROW_HEIGHT));
        setOpaque(true);
        setToolTipText(null);
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                boolean overStar = e.getX() >= COL_STAR && e.getX() < COL_STAR + ICON_HIT_WIDTH;
                if (overStar != starHovered) {
                    starHovered = overStar;
                    repaint();
                }
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getX() >= COL_STAR && e.getX() < COL_STAR + ICON_HIT_WIDTH) {
                    togglePin();
                    repaint();
                    e.consume();
                }
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (starHovered) {
                    starHovered = false;
                    repaint();
                }
            }
        });
    }

    public ModelEntry entry() {
        return entry;
    }

    public boolean isPinned() {
        return pinned;
    }

    void togglePin() {
        pinned = !pinned;
        if (pinToggleListener != null) {
            pinToggleListener.onPinToggled(entry, pinned);
        }
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            Color background = getModel().isArmed()
                    ? selectionColor()
                    : getBackground();
            g2.setColor(background);
            g2.fillRect(0, 0, getWidth(), getHeight());

            paintBadge(g2, entry.tier());
            paintStatus(g2, ProviderStatusIcon.READY);
            paintStar(g2, pinned, starHovered);

            g2.setFont(getFont());
            g2.setColor(textColorFor(entry, getForeground()));
            FontMetrics fm = g2.getFontMetrics();
            int baseline = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
            g2.drawString(entry.displayName(), COL_TEXT, baseline);

            String hint = providerHint(entry.providerId());
            int hintWidth = fm.stringWidth(hint);
            g2.setColor(new Color(140, 140, 150));
            g2.drawString(hint, getWidth() - hintWidth - 8, baseline);
        } finally {
            g2.dispose();
        }
    }

    private Color selectionColor() {
        Color c = UIManager.getColor("MenuItem.selectionBackground");
        return c == null ? new Color(180, 200, 240) : c;
    }

    private static Color textColorFor(ModelEntry entry, Color fallback) {
        if (!entry.curated()) {
            return new Color(110, 110, 120);
        }
        return fallback == null ? Color.BLACK : fallback;
    }

    private static String providerHint(String providerId) {
        if (providerId == null) {
            return "";
        }
        switch (providerId) {
            case "anthropic": return "Anthropic";
            case "gemini": return "Gemini";
            case "groq": return "Groq";
            case "ollama": return "Ollama";
            case "ollama-cloud": return "Ollama Cloud";
            case "openai": return "OpenAI";
            case "openrouter": return "OpenRouter";
            case "github-models": return "GitHub Models";
            case "mistral": return "Mistral";
            case "cerebras": return "Cerebras";
            case "deepseek": return "DeepSeek";
            case "huggingface": return "HuggingFace";
            case "perplexity": return "Perplexity";
            case "together": return "Together";
            case "xai": return "xAI";
            default: return providerId;
        }
    }

    /** Paint a 12px filled disc per tier; documented mapping in 06 §1.1. */
    static void paintBadge(Graphics2D g2, ModelEntry.Tier tier) {
        Color fill;
        switch (tier) {
            case FREE: fill = new Color(60, 180, 75); break;
            case FREE_WITH_LIMITS: fill = new Color(240, 200, 50); break;
            case PAID: fill = new Color(70, 130, 220); break;
            case REQUIRES_SUBSCRIPTION: fill = new Color(160, 95, 215); break;
            case UNCURATED:
            default: fill = new Color(200, 200, 205); break;
        }
        g2.setColor(fill);
        g2.fillOval(COL_BADGE, 6, 12, 12);
        g2.setColor(new Color(60, 60, 70));
        g2.drawOval(COL_BADGE, 6, 12, 12);
    }

    /** Paint the configuration status glyph next to the badge. */
    static void paintStatus(Graphics2D g2, ProviderStatusIcon icon) {
        switch (icon) {
            case READY:
                g2.setColor(new Color(60, 160, 90));
                g2.drawString("✓", COL_STATUS, 17);
                break;
            case NEEDS_SETUP:
                g2.setColor(new Color(200, 150, 30));
                g2.drawString("⚠", COL_STATUS, 17);
                break;
            case UNAVAILABLE:
                g2.setColor(new Color(200, 60, 60));
                g2.drawString("✗", COL_STATUS, 17);
                break;
        }
    }

    /** Paint the pin star. */
    static void paintStar(Graphics2D g2, boolean filled, boolean hovered) {
        Color base = filled ? new Color(220, 175, 30) : new Color(150, 150, 160);
        if (hovered) {
            base = base.brighter();
        }
        g2.setColor(base);
        g2.drawString(filled ? "★" : "☆", COL_STAR, 17);
    }

    /** Status enum mirrored from {@code ProviderEntry.Status} for paint code. */
    public enum ProviderStatusIcon {
        READY, NEEDS_SETUP, UNAVAILABLE
    }
}
