package imagejai.engine;

import org.junit.Test;

import java.awt.image.BufferedImage;
import java.util.Random;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ImageCaptureBoundsTest {

    @Test
    public void incompressiblePngAbortsAtOutputLimit() throws Exception {
        BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(123456789L);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, random.nextInt());
            }
        }

        try {
            ImageCapture.toPngBytes(image, 4096);
            fail("Expected bounded PNG encoder to abort");
        } catch (ImageCapture.CaptureTooLargeException expected) {
            assertTrue(expected.getMessage().contains("4096"));
        }
    }
}
