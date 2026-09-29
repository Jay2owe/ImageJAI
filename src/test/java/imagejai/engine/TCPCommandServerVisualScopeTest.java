package imagejai.engine;

import ij.ImagePlus;
import ij.io.FileInfo;
import ij.process.ByteProcessor;
import imagejai.engine.security.VisualOverrideRegistry;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class TCPCommandServerVisualScopeTest {

    @Test
    public void duplicateTitlesAndPathsStillHaveDistinctLiveGrantScopes() {
        ImagePlus first = image("duplicate.tif");
        ImagePlus second = image("duplicate.tif");
        FileInfo sharedPath = new FileInfo();
        sharedPath.directory = "C:\\same\\";
        sharedPath.fileName = "duplicate.tif";
        first.setFileInfo(sharedPath);
        second.setFileInfo(sharedPath);

        String firstToken = TCPCommandServer.visualImageToken(first);
        String secondToken = TCPCommandServer.visualImageToken(second);
        assertNotEquals(firstToken, secondToken);

        VisualOverrideRegistry registry = new VisualOverrideRegistry();
        VisualOverrideRegistry.PendingRequest pending = registry.request(
                "session", "inspect", firstToken);
        assertTrue(registry.grant("session", pending.requestId, "inspect"));
        assertFalse(registry.hasGrant("session", secondToken));
        assertTrue(registry.hasGrant("session", firstToken));
    }

    private static ImagePlus image(String title) {
        return new ImagePlus(title, new ByteProcessor(2, 2));
    }
}
