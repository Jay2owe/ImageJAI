package imagejai.engine;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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

    @Test
    public void handlerSupportedTimeoutsAreDeclaredInRequestSchemas() {
        assertTrue(CommandManifest.descriptor("get_pixels")
                .requestFields.contains("timeout_ms"));
        assertTrue(CommandManifest.descriptor("rewind")
                .requestFields.contains("timeout_ms"));
        assertTrue(CommandManifest.descriptor("interact_dialog")
                .requestFields.contains("timeout_ms"));
    }

    @Test
    public void installationTokenIsNotACommandSpecificImageHandle() {
        CommandManifest.Descriptor tokenOpen =
                CommandManifest.descriptor("open_image_by_token");
        assertTrue(tokenOpen.requestFields.contains("image_token"));
        assertFalse(tokenOpen.requestFields.contains("token"));
        assertFalse(CommandManifest.descriptor("open_image")
                .requestFields.contains("token"));
    }

    @Test
    public void scientificReadBindingsAreDeclared() {
        for (String command : new String[] {
                "get_image_info", "get_pixels", "get_histogram", "get_display_state",
                "get_metadata", "capture_image"
        }) {
            CommandManifest.Descriptor descriptor = CommandManifest.descriptor(command);
            assertTrue(command, descriptor.requestFields.contains("image_id"));
            assertTrue(command, descriptor.requestFields.contains("image_revision"));
            assertTrue(command, descriptor.requestFields.contains("display_revision"));
        }
        assertTrue(CommandManifest.descriptor("get_histogram")
                .requestFields.contains("scope"));
    }

    @Test
    public void edtMutationPollingFieldsAreDeclared() {
        for (String command : new String[] {
                "open_image", "open_image_by_token", "interact_dialog",
                "close_dialogs", "close_windows"
        }) {
            assertTrue(command, CommandManifest.descriptor(command)
                    .requestFields.contains("operation_id"));
        }
        assertTrue(CommandManifest.descriptor("close_dialogs")
                .requestFields.contains("timeout_ms"));
    }
}
