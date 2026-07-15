package imagejai.engine;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class EventBusTest {

    @Test
    public void lifecycleEventsNeverCoalesce() {
        EventBus bus = new EventBus(fixedClock(1000L));
        final List<JsonObject> frames = new ArrayList<JsonObject>();
        bus.subscribe("job.completed", collecting(frames));

        bus.publish("job.completed", data("job_id", "j-1"));
        bus.publish("job.completed", data("job_id", "j-1"));

        assertEquals(2, frames.size());
    }

    @Test
    public void progressCoalescesOnlyForTheSameIdentity() {
        final AtomicLong now = new AtomicLong(1000L);
        EventBus bus = new EventBus(new LongSupplier() {
            @Override
            public long getAsLong() {
                return now.get();
            }
        });
        final List<JsonObject> frames = new ArrayList<JsonObject>();
        bus.subscribe("job.progress", collecting(frames));

        bus.publish("job.progress", data("job_id", "j-1"));
        bus.publish("job.progress", data("job_id", "j-2"));
        bus.publish("job.progress", data("job_id", "j-1"));

        assertEquals("a second job must not be suppressed", 2, frames.size());
        assertEquals("j-1", frames.get(0).getAsJsonObject("data")
                .get("job_id").getAsString());
        assertEquals("j-2", frames.get(1).getAsJsonObject("data")
                .get("job_id").getAsString());

        now.addAndGet(201L);
        bus.publish("job.progress", data("job_id", "j-1"));
        assertEquals(3, frames.size());
    }

    @Test
    public void imageUpdatesWithoutIdentityAreRetained() {
        EventBus bus = new EventBus(fixedClock(1000L));
        final List<JsonObject> frames = new ArrayList<JsonObject>();
        bus.subscribe("image.updated", collecting(frames));

        bus.publish("image.updated", new JsonObject());
        bus.publish("image.updated", new JsonObject());

        assertEquals(2, frames.size());
    }

    @Test
    public void outboundSignalNeverCarriesRawCustomTopicOrIdentity() throws Exception {
        final List<OutboundEvent> events = new ArrayList<OutboundEvent>();
        AutoCloseable subscription = OutboundEvent.subscribe(
                new OutboundEvent.Listener() {
                    @Override
                    public void outboundEvent(OutboundEvent event) {
                        events.add(event);
                    }
                });
        try {
            OutboundEvent.publishStream(
                    "patient.subject-alpha", "Subject Alpha", 123);
        } finally {
            subscription.close();
        }

        assertEquals(1, events.size());
        assertEquals("stream", events.get(0).command());
        assertEquals("custom", events.get(0).topic());
        assertFalse(events.get(0).identityHash().contains("Subject Alpha"));
        assertEquals(123, events.get(0).bytesOut());
    }

    private static EventBus.Listener collecting(final List<JsonObject> frames) {
        return new EventBus.Listener() {
            @Override
            public void onEvent(JsonObject frame) {
                frames.add(frame);
            }
        };
    }

    private static JsonObject data(String key, String value) {
        JsonObject data = new JsonObject();
        data.addProperty(key, value);
        return data;
    }

    private static LongSupplier fixedClock(final long value) {
        return new LongSupplier() {
            @Override
            public long getAsLong() {
                return value;
            }
        };
    }
}
