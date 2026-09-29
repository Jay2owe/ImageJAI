package imagejai.engine;

import com.google.gson.JsonObject;
import ij.ImagePlus;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class ImageMonitorTest {

    @Test
    public void sameTitleImagesEmitDistinctDeterministicIdentityEvents() {
        ImagePlus first = new ImagePlus("same.tif", new ByteProcessor(1, 1));
        ImagePlus second = new ImagePlus("same.tif", new ByteProcessor(1, 1));
        ImageGraph.ImageRef firstRef = ref(first, 10);
        ImageGraph.ImageRef secondRef = ref(second, 20);
        AtomicReference<List<ImageGraph.ImageRef>> open =
                new AtomicReference<List<ImageGraph.ImageRef>>(
                        Arrays.asList(firstRef, secondRef));
        AtomicReference<ImagePlus> active = new AtomicReference<ImagePlus>(first);
        EventBus bus = new EventBus(new AtomicLong(1000L)::incrementAndGet);
        List<JsonObject> events = new ArrayList<JsonObject>();
        bus.subscribe("image.*", frame -> events.add(frame.deepCopy()));
        ImageMonitor monitor = new ImageMonitor(null, bus, open::get, active::get);

        monitor.publishImageDiffEventsForTest();

        assertEquals("image.opened", events.get(0).get("event").getAsString());
        assertEquals(firstRef.identity, imageId(events.get(0)));
        assertEquals("image.opened", events.get(1).get("event").getAsString());
        assertEquals(secondRef.identity, imageId(events.get(1)));
        assertFalse(firstRef.identity.equals(secondRef.identity));

        events.clear();
        open.set(Arrays.asList(secondRef));
        active.set(second);
        monitor.publishImageDiffEventsForTest();

        assertEquals("image.closed", events.get(0).get("event").getAsString());
        assertEquals(firstRef.identity, imageId(events.get(0)));
        assertEquals("image.updated", events.get(1).get("event").getAsString());
        assertEquals(secondRef.identity, imageId(events.get(1)));
    }

    private static String imageId(JsonObject frame) {
        return frame.getAsJsonObject("data").get("image_id").getAsString();
    }

    private static ImageGraph.ImageRef ref(ImagePlus image, int windowId) {
        return new ImageGraph.ImageRef(image, ImageGraph.stableIdentity(image),
                windowId, image.getTitle(), null);
    }
}
