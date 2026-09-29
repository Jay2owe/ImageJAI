package imagejai.engine;

import com.google.gson.JsonObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
    public void displayTitleIsNeverUsedAsStableCoalescingIdentity() {
        EventBus bus = new EventBus(fixedClock(1000L));
        final List<JsonObject> frames = new ArrayList<JsonObject>();
        bus.subscribe("image.updated", collecting(frames));

        bus.publish("image.updated", data("title", "Results"));
        bus.publish("image.updated", data("title", "Results"));

        assertEquals("same-title images must retain distinct frames", 2, frames.size());
    }

    @Test
    public void coalescingAndSuppressionMetadataRemainBounded() {
        EventBus bus = new EventBus(fixedClock(1000L));
        for (int i = 0; i < 2000; i++) {
            bus.publish("image.updated", data("image_id", "img-" + i));
        }
        assertTrue(bus.coalescingStateSizeForTest() <= 1024);

        for (int i = 0; i < 2000; i++) {
            String pattern = "custom." + i;
            bus.pushSuppress(pattern);
            bus.popSuppress(pattern);
        }
        assertEquals(0, bus.suppressionStateSizeForTest());
    }

    @Test
    public void listenerFailuresAreBoundedObservableAndDoNotStopPeers() {
        EventBus bus = new EventBus(fixedClock(1234L));
        final List<JsonObject> delivered = new ArrayList<JsonObject>();
        bus.subscribe("job.completed", new EventBus.Listener() {
            @Override public void onEvent(JsonObject frame) {
                throw new IllegalStateException("synthetic listener failure");
            }
        });
        bus.subscribe("job.completed", collecting(delivered));

        for (int i = 0; i < 70; i++) {
            bus.publish("job.completed", data("job_id", "job-" + i));
        }

        assertEquals(70, delivered.size());
        assertEquals(70L, bus.listenerFailureCount());
        assertEquals("diagnostic retention must be bounded", 64,
                bus.recentListenerFailures().size());
        EventBus.ListenerFailure latest = bus.recentListenerFailures().get(63);
        assertEquals(1234L, latest.timestampMillis());
        assertEquals("job.completed", latest.topic());
        assertTrue(latest.errorType().contains("IllegalStateException"));
    }

    @Test
    public void subscriptionFloodIsBoundedAndRejectedCountIsObservable() {
        EventBus bus = new EventBus(fixedClock(1L));
        for (int i = 0; i < EventBus.MAX_SUBSCRIPTIONS + 200; i++) {
            bus.subscribe("topic." + i, new EventBus.Listener() {
                @Override public void onEvent(JsonObject frame) { }
            });
        }
        bus.subscribe(repeat('x', EventBus.MAX_PATTERN_CHARS + 1), collecting(
                new ArrayList<JsonObject>()));

        assertEquals(EventBus.MAX_SUBSCRIPTIONS, bus.subscriberCount());
        assertEquals(201L, bus.rejectedSubscriptionCount());
    }

    @Test
    public void multiPatternSubscriptionIsAtomicAtCapacity() {
        EventBus bus = new EventBus(fixedClock(1L));
        EventBus.Listener listener = collecting(new ArrayList<JsonObject>());
        for (int i = 0; i < EventBus.MAX_SUBSCRIPTIONS - 1; i++) {
            bus.subscribe("topic." + i, listener);
        }
        int before = bus.subscriberCount();

        assertFalse(bus.subscribeAll(
                java.util.Arrays.asList("one", "two"), listener));
        assertEquals(before, bus.subscriberCount());
        assertEquals(1L, bus.rejectedSubscriptionCount());
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

    private static String repeat(char value, int count) {
        StringBuilder out = new StringBuilder(count);
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }
}
