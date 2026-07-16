package imagejai.engine;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class CommandManifestTest {
    @Test
    public void packagedManifestIsSortedUniqueAndComplete() {
        List<String> names = CommandManifest.allNames();
        List<String> sorted = new ArrayList<String>(names);
        Collections.sort(sorted);
        assertEquals(sorted, names);
        assertEquals(names.size(), new HashSet<String>(names).size());
        assertEquals(61, names.size());
        assertEquals(60, CommandManifest.requestResponseNames().size());
        assertEquals(Collections.singletonList("subscribe"), CommandManifest.streamNames());
        assertEquals("0.3.0", CommandManifest.productVersion());
    }

    @Test
    public void hashDedupDescriptorsAreReadOnly() {
        assertEquals(21, CommandManifest.hashDedupNames().size());
        for (String name : CommandManifest.hashDedupNames()) {
            CommandManifest.Descriptor descriptor = CommandManifest.descriptor(name);
            assertNotNull(descriptor);
            assertEquals("read_only", descriptor.classification);
            assertTrue(descriptor.hashDedup);
        }
    }

    @Test
    public void tcpCommandSurfaceComesFromThePackagedManifest() {
        assertEquals(CommandManifest.requestResponseNames(),
                TCPCommandServer.knownCommands());
    }
}
