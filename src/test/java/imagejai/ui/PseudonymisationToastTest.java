package imagejai.ui;

import imagejai.engine.security.OutboundPromptScrubber;
import org.junit.Test;

import javax.swing.SwingUtilities;
import java.util.Collections;

import static org.junit.Assert.assertTrue;

public class PseudonymisationToastTest {
    @Test
    public void replacementDetailsAreShownInToast() throws Exception {
        final PseudonymisationToast toast = new PseudonymisationToast();

        toast.pseudonymised(Collections.singletonList(
                new OutboundPromptScrubber.Replacement("MOAB2_subject_017", "image-7a3f")));
        SwingUtilities.invokeAndWait(new Runnable() {
            @Override
            public void run() {
            }
        });

        assertTrue(toast.isVisible());
        assertTrue(toast.detailTextForTest().contains("image-7a3f"));
    }
}
