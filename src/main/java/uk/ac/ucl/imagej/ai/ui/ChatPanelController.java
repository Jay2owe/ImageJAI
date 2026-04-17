package uk.ac.ucl.imagej.ai.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Phase 7: contract between the GUI-action dispatcher and the chat panel.
 * <p>
 * Implementations must perform every Swing mutation on the EDT internally so
 * callers (TCP handlers, dispatcher) never have to wrap calls themselves.
 * When the underlying panel is not visible (plugin not open), implementations
 * should degrade gracefully — typically a log line and a no-op.
 */
public interface ChatPanelController {

    /** Append an inline image preview to the chat thread. */
    void inlineImage(Path path);

    /**
     * Show a transient toast at the top of the panel.
     *
     * @param message text to display
     * @param level   {@code "info"} | {@code "warn"} | {@code "error"} (anything
     *                else is treated as {@code "info"})
     */
    void toast(String message, String level);

    /** Render a small Markdown snippet as HTML inside the chat thread. */
    void showMarkdown(String content);

    /**
     * Briefly flash a rectangular ROI on the named image to draw the user's eye.
     *
     * @param imageTitle window title to look up via {@code WindowManager.getImage}
     * @param roiBounds  4-element array {@code [x, y, width, height]}
     */
    void highlightRoi(String imageTitle, int[] roiBounds);

    /** Bring the named image window to the front. */
    void focusImage(String imageTitle);

    /**
     * Append a confirmation prompt with a button per option. The supplied
     * callback fires once with the chosen option label; subsequent clicks
     * are ignored.
     */
    void confirm(String prompt, List<String> options, Consumer<String> onChoice);
}
