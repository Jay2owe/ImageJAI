package imagejai.ui.picker;

import imagejai.engine.picker.ModelEntry;
import imagejai.engine.picker.SoftDeprecationPolicy;

import javax.swing.JMenuItem;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.font.TextAttribute;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

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
    private ProviderStatusIcon statusIcon;
    private final PinToggleListener pinToggleListener;
    private final StatusIconClickListener statusClickListener;

    /** Callback invoked when the user clicks the star column. */
    public interface PinToggleListener {
        void onPinToggled(ModelEntry entry, boolean nowPinned);
    }

    /**
     * Callback invoked when the user clicks the status icon column. ⚠ deep-links
     * to the multi-provider settings tab; ✗ opens the cached-error dialog;
     * ✓ falls through (callers can ignore by returning false).
     */
    public interface StatusIconClickListener {
        boolean onStatusIconClicked(ModelEntry entry, ProviderStatusIcon status);
    }

    public ModelMenuItem(ModelEntry entry, PinToggleListener pinToggleListener) {
        this(entry, ProviderStatusIcon.READY, pinToggleListener, null);
    }

    public ModelMenuItem(ModelEntry entry,
                         ProviderStatusIcon statusIcon,
                         PinToggleListener pinToggleListener,
                         StatusIconClickListener statusClickListener) {
        super(entry.displayName());
        this.entry = entry;
        this.pinned = entry.pinned();
        this.statusIcon = statusIcon == null ? ProviderStatusIcon.READY : statusIcon;
        this.pinToggleListener = pinToggleListener;
        this.statusClickListener = statusClickListener;
        setPreferredSize(new Dimension(380, ROW_HEIGHT));
        setOpaque(true);
        // Hover-card replaces the Swing tooltip; for soft-deprecated rows we
        // surface the "no longer available since X" copy via an HTML tooltip
        // so screen readers still get the key date even with HoverCard down.
        // Tier-badge lay-language tooltips per docs/multi_provider/06_tier_safety.md §1.2
        // are appended so screen-reader users get the headline before the
        // hover-card opens.
        setToolTipText(composeTooltip(entry, LocalDate.now()));
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
                if (e.getX() >= COL_STATUS && e.getX() < COL_STATUS + ICON_HIT_WIDTH) {
                    if (statusClickListener != null
                            && statusClickListener.onStatusIconClicked(entry, statusIcon)) {
                        e.consume();
                    }
                    return;
                }
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

    public ProviderStatusIcon statusIcon() {
        return statusIcon;
    }

    public void setStatusIcon(ProviderStatusIcon statusIcon) {
        this.statusIcon = statusIcon == null ? ProviderStatusIcon.READY : statusIcon;
        repaint();
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

            SoftDeprecationPolicy.State lifecycle =
                    SoftDeprecationPolicy.stateOf(entry, LocalDate.now());

            // Per 05 §11.1 the visual elements are reusable Icon impls. The
            // static paint helpers below delegate to the same classes so
            // existing test surface stays addressable.
            TierBadgeIcon badge = lifecycle == SoftDeprecationPolicy.State.PINNED_DEPRECATED
                    ? TierBadgeIcon.forTierDeprecated(entry.tier())
                    : TierBadgeIcon.forTier(entry.tier());
            badge.paintIcon(this, g2, COL_BADGE, 6);
            StatusIcon.forStatus(statusIcon).paintIcon(this, g2, COL_STATUS, 4);
            PinStarIcon.forState(pinned, starHovered).paintIcon(this, g2, COL_STAR, 4);

            Font baseFont = getFont();
            Font textFont = lifecycle == SoftDeprecationPolicy.State.SOFT_DEPRECATED
                    || lifecycle == SoftDeprecationPolicy.State.PINNED_DEPRECATED
                    ? strikethrough(baseFont)
                    : baseFont;
            g2.setFont(textFont);
            g2.setColor(textColorFor(entry, lifecycle, getForeground()));
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

    private static Color textColorFor(ModelEntry entry,
                                      SoftDeprecationPolicy.State lifecycle,
                                      Color fallback) {
        if (lifecycle == SoftDeprecationPolicy.State.PINNED_DEPRECATED) {
            return new Color(180, 60, 60);
        }
        if (lifecycle == SoftDeprecationPolicy.State.SOFT_DEPRECATED) {
            return new Color(170, 130, 30);
        }
        if (!entry.curated()) {
            return new Color(110, 110, 120);
        }
        return fallback == null ? Color.BLACK : fallback;
    }

    private static Font strikethrough(Font base) {
        Map<TextAttribute, Object> attrs = new HashMap<TextAttribute, Object>();
        attrs.put(TextAttribute.STRIKETHROUGH, TextAttribute.STRIKETHROUGH_ON);
        return base.deriveFont(attrs);
    }

    /**
     * Lay-language tier-badge tooltip per docs/multi_provider/06_tier_safety.md §1.2.
     * Returned text is the same whether the user hovers the badge column or the
     * row body — Swing's per-cell tooltips are awkward inside JPopupMenu, so we
     * surface the badge meaning at the row level and rely on the hover-card for
     * the rest of the detail.
     */
    static String tierBadgeTooltip(ModelEntry.Tier tier) {
        if (tier == null) {
            return "Auto-detected from the provider — pricing and reliability not yet verified.";
        }
        switch (tier) {
            case FREE:
                return "Free to use. No card needed, no usage caps that matter for normal sessions.";
            case FREE_WITH_LIMITS:
                return "Free, but rate-limited. You may hit a per-minute or per-day cap on long sessions — keep an eye on the status bar.";
            case PAID:
                return "You pay per use. Charges only happen if you've already added credit or a card to this provider — see hover for current rates.";
            case REQUIRES_SUBSCRIPTION:
                return "Requires an active monthly subscription with this provider. Without it the request will be refused — no surprise charges.";
            case UNCURATED:
            default:
                return "Auto-detected from the provider — pricing and reliability not yet verified. Check the provider's pricing page directly before running a long session.";
        }
    }

    static String composeTooltip(ModelEntry entry, LocalDate today) {
        if (entry == null) {
            return null;
        }
        String deprecation = deprecationTooltip(entry, today);
        String badge = tierBadgeTooltip(entry.tier());
        if (deprecation == null) {
            return badge;
        }
        return "<html>" + escapeHtml(deprecation) + "<br>" + escapeHtml(badge) + "</html>";
    }

    private static String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static String deprecationTooltip(ModelEntry entry, LocalDate today) {
        if (entry == null || entry.deprecatedSince() == null) {
            return null;
        }
        SoftDeprecationPolicy.State state = SoftDeprecationPolicy.stateOf(entry, today);
        DateTimeFormatter fmt = DateTimeFormatter.ISO_LOCAL_DATE;
        String since = entry.deprecatedSince().format(fmt);
        if (state == SoftDeprecationPolicy.State.PINNED_DEPRECATED) {
            return entry.replacement() == null || entry.replacement().isEmpty()
                    ? "RETIRED — calls will fail. Provider has retired this model."
                    : "RETIRED — calls will fail. Switch to " + entry.replacement() + ".";
        }
        if (state == SoftDeprecationPolicy.State.SOFT_DEPRECATED) {
            String suffix = entry.replacement() == null || entry.replacement().isEmpty()
                    ? ""
                    : " — try " + entry.replacement();
            return "No longer available since " + since + suffix + ".";
        }
        return null;
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

    /** Paint a 12px filled disc per tier; documented mapping in 06 §1.1.
     * Pinned-deprecated rows swap to the red {@code RETIRED} dot per 02 §3. */
    static void paintBadge(Graphics2D g2, ModelEntry.Tier tier,
                           SoftDeprecationPolicy.State lifecycle) {
        Color fill;
        if (lifecycle == SoftDeprecationPolicy.State.PINNED_DEPRECATED) {
            fill = new Color(200, 60, 60);
        } else {
            switch (tier) {
                case FREE: fill = new Color(60, 180, 75); break;
                case FREE_WITH_LIMITS: fill = new Color(240, 200, 50); break;
                case PAID: fill = new Color(70, 130, 220); break;
                case REQUIRES_SUBSCRIPTION: fill = new Color(160, 95, 215); break;
                case UNCURATED:
                default: fill = new Color(200, 200, 205); break;
            }
        }
        g2.setColor(fill);
        g2.fillOval(COL_BADGE, 6, 12, 12);
        g2.setColor(new Color(60, 60, 70));
        g2.drawOval(COL_BADGE, 6, 12, 12);
    }

    /** Backwards-compatible overload — defaults to ACTIVE lifecycle. */
    static void paintBadge(Graphics2D g2, ModelEntry.Tier tier) {
        paintBadge(g2, tier, SoftDeprecationPolicy.State.ACTIVE);
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
