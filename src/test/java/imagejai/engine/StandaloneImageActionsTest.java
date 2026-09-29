package imagejai.engine;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class StandaloneImageActionsTest {
    @Test
    public void installsVisibleRoiOverlayOnImageWithoutChatPanel() {
        ImagePlus image = new ImagePlus("fixture", new ByteProcessor(16, 16));

        StandaloneImageActions.installHighlight(image, new int[]{2, 3, 5, 7});

        assertNotNull(image.getOverlay());
        assertEquals(1, image.getOverlay().size());
        assertEquals(2, image.getOverlay().get(0).getBounds().x);
        assertEquals(3, image.getOverlay().get(0).getBounds().y);
        assertEquals(5, image.getOverlay().get(0).getBounds().width);
        assertEquals(7, image.getOverlay().get(0).getBounds().height);
    }
}
