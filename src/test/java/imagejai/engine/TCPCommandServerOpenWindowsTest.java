package imagejai.engine;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.awt.GraphicsEnvironment;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import org.junit.Assume;
import org.junit.Test;

public class TCPCommandServerOpenWindowsTest {

    @Test
    public void ordinaryVisibleSwingFramesAreReportedButHiddenFramesAreNot()
            throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        final String visibleTitle = "ImageJAI visible frame " + System.nanoTime();
        final String hiddenTitle = "ImageJAI hidden frame " + System.nanoTime();
        final AtomicReference<JFrame> visible = new AtomicReference<JFrame>();
        final AtomicReference<JFrame> hidden = new AtomicReference<JFrame>();

        SwingUtilities.invokeAndWait(() -> {
            JFrame shown = new JFrame(visibleTitle);
            shown.setSize(220, 120);
            shown.setLocation(0, 0);
            shown.setVisible(true);
            visible.set(shown);
            hidden.set(new JFrame(hiddenTitle));
        });

        try {
            final AtomicReference<List<String>> result =
                    new AtomicReference<List<String>>();
            SwingUtilities.invokeAndWait(() -> result.set(
                    TCPCommandServer.collectNonImageWindowTitles()));
            assertTrue(result.get().contains(visibleTitle));
            assertFalse(result.get().contains(hiddenTitle));
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                visible.get().dispose();
                hidden.get().dispose();
            });
        }
    }

    @Test
    public void explicitCloseWindowsCanDisposeNamedAssistantFrameButDialogCleanupCannot()
            throws Exception {
        Assume.assumeFalse(GraphicsEnvironment.isHeadless());
        final String title = "AI Assistant test frame " + System.nanoTime();
        final AtomicReference<JFrame> frame = new AtomicReference<JFrame>();

        SwingUtilities.invokeAndWait(() -> {
            JFrame shown = new JFrame(title);
            shown.setSize(220, 120);
            shown.setLocation(0, 0);
            shown.setVisible(true);
            frame.set(shown);
        });

        try {
            final int[] conservativeCount = new int[1];
            SwingUtilities.invokeAndWait(() -> conservativeCount[0] =
                    TCPCommandServer.dismissOpenDialogsOnEdt(title, false));
            assertEquals(0, conservativeCount[0]);
            assertTrue(frame.get().isShowing());

            final int[] explicitCount = new int[1];
            SwingUtilities.invokeAndWait(() -> explicitCount[0] =
                    TCPCommandServer.dismissOpenDialogsOnEdt(title, true));
            assertEquals(1, explicitCount[0]);
            assertFalse(frame.get().isShowing());
        } finally {
            SwingUtilities.invokeAndWait(() -> frame.get().dispose());
        }
    }
}
