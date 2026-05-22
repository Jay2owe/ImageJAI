package imagejai.ui;

import imagejai.engine.OutboundEvent;
import org.junit.Test;

import javax.swing.SwingUtilities;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EgressIndicatorTest {
    @Test
    public void outboundEventFlashesThenDecays() throws Exception {
        final EgressIndicator indicator = new EgressIndicator();
        try {
            assertFalse(indicator.isActiveForTest());

            OutboundEvent.publish("ping", 12);
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                }
            });

            assertTrue(indicator.isActiveForTest());

            Thread.sleep(450L);
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                }
            });

            assertFalse(indicator.isActiveForTest());
        } finally {
            indicator.dispose();
        }
    }
}
