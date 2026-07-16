package imagejai.ui;

import javax.swing.UIManager;
import java.awt.Color;

/** Small theme/contrast helpers shared by custom-painted Swing surfaces. */
public final class ThemeColors {
    private static final double TEXT_CONTRAST = 4.5;
    private static final double FOCUS_CONTRAST = 3.0;

    private ThemeColors() { }

    public static Color uiColor(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color == null ? fallback : color;
    }

    public static Color panelBackground() {
        return uiColor("Panel.background", new Color(242, 242, 242));
    }

    public static Color menuBackground() {
        return uiColor("MenuItem.background", panelBackground());
    }

    public static Color textOn(Color background) {
        return ensureContrast(background,
                uiColor("Label.foreground", Color.BLACK), TEXT_CONTRAST);
    }

    public static Color menuTextOn(Color background, boolean selected) {
        String key = selected ? "MenuItem.selectionForeground" : "MenuItem.foreground";
        return ensureContrast(background, uiColor(key, textOn(background)), TEXT_CONTRAST);
    }

    public static Color semanticText(Color background, Color preferred) {
        return ensureContrast(background, preferred, TEXT_CONTRAST);
    }

    public static Color focusColor(Color background) {
        Color candidate = uiColor("Focus.color",
                uiColor("Button.focus", new Color(0, 95, 204)));
        return ensureContrast(background, candidate, FOCUS_CONTRAST);
    }

    /** A severity tint derived from the installed panel palette. */
    public static Color tintedSurface(Color accent) {
        Color base = panelBackground();
        double accentWeight = relativeLuminance(base) < 0.35 ? 0.22 : 0.10;
        return mix(base, accent == null ? base : accent, accentWeight);
    }

    public static Color ensureContrast(Color background, Color preferred, double minimum) {
        Color bg = background == null ? panelBackground() : background;
        Color choice = preferred == null ? Color.BLACK : preferred;
        if (contrastRatio(bg, choice) >= minimum) return choice;
        Color black = Color.BLACK;
        Color white = Color.WHITE;
        return contrastRatio(bg, black) >= contrastRatio(bg, white) ? black : white;
    }

    public static Color mix(Color first, Color second, double secondWeight) {
        Color a = first == null ? Color.BLACK : first;
        Color b = second == null ? a : second;
        double weight = Math.max(0.0, Math.min(1.0, secondWeight));
        double inverse = 1.0 - weight;
        return new Color(
                clamp((int) Math.round(a.getRed() * inverse + b.getRed() * weight)),
                clamp((int) Math.round(a.getGreen() * inverse + b.getGreen() * weight)),
                clamp((int) Math.round(a.getBlue() * inverse + b.getBlue() * weight)));
    }

    public static double contrastRatio(Color first, Color second) {
        double a = relativeLuminance(first == null ? Color.BLACK : first);
        double b = relativeLuminance(second == null ? Color.BLACK : second);
        double light = Math.max(a, b);
        double dark = Math.min(a, b);
        return (light + 0.05) / (dark + 0.05);
    }

    private static double relativeLuminance(Color color) {
        return 0.2126 * linear(color.getRed() / 255.0)
                + 0.7152 * linear(color.getGreen() / 255.0)
                + 0.0722 * linear(color.getBlue() / 255.0);
    }

    private static double linear(double value) {
        return value <= 0.03928 ? value / 12.92
                : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
