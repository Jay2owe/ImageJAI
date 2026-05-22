package imagejai.engine.security;

/**
 * Source category for image capture responses. GUI/window/desktop captures
 * are treated as high-risk because titles and chrome can expose filenames.
 */
public enum CaptureSource {
    ACTIVE_IMAGE_CONTENT,
    ACTIVE_IMAGE_WITH_OVERLAY,
    DIALOG_SCREENSHOT,
    WINDOW_SCREENSHOT,
    DESKTOP_SCREENSHOT;

    public static CaptureSource from(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return ACTIVE_IMAGE_CONTENT;
        }
        String cleaned = raw.trim().toUpperCase().replace('-', '_').replace(' ', '_');
        for (CaptureSource source : values()) {
            if (source.name().equals(cleaned)) {
                return source;
            }
        }
        return ACTIVE_IMAGE_CONTENT;
    }

    public boolean isRefusedScreenshot() {
        return this == DIALOG_SCREENSHOT
                || this == WINDOW_SCREENSHOT
                || this == DESKTOP_SCREENSHOT;
    }
}
