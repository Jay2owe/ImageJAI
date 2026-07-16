package imagejai.engine.security;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PathTokenMapTest {
    @Test
    public void pathTokensAreStableInMapAndRandomisedBySalt() {
        Path path = Paths.get("MOAB2", "subject_017.lif");
        PathTokenMap a = new PathTokenMap(bytes(1));
        PathTokenMap b = new PathTokenMap(bytes(2));

        String first = a.tokenForPath(path);
        String second = a.tokenForPath(path);

        assertEquals(first, second);
        assertTrue(first.matches("image-[0-9a-f]{32}\\.lif"));
        assertNotEquals(first, b.tokenForPath(path));
    }

    @Test
    public void seriesTokensResolveBackToRealPathAndSeries() {
        Path path = Paths.get("MOAB2", "subject_017.lif");
        PathTokenMap map = new PathTokenMap(bytes(3));
        String token = map.tokenForSeries(path, 4);

        assertTrue(token.matches("image-[0-9a-f]{32}\\.lif:4"));
        PathTokenMap.ResolvedTarget resolved = map.resolve(token).get();

        assertEquals(path, resolved.realPath());
        assertEquals(4, resolved.series());
        assertEquals(-1, map.resolve(map.tokenForPath(path)).get().series());
    }

    @Test
    public void sensitiveSnapshotVersionChangesOnlyForNewValues() {
        PathTokenMap map = new PathTokenMap(bytes(4));
        assertEquals(0L, map.sensitiveVersion());

        String token = map.tokenForSensitiveText("subject-017", "label");
        long registered = map.sensitiveVersion();

        assertEquals(1L, registered);
        assertEquals(token, map.tokenForSensitiveText("subject-017", "other"));
        assertEquals(registered, map.sensitiveVersion());
        assertEquals(1, map.sensitiveEntryCount());
    }

    @Test
    public void oversizedSensitiveValuesFailExplicitlyWithoutGrowingMap() {
        PathTokenMap map = new PathTokenMap(bytes(5));
        StringBuilder value = new StringBuilder(PathTokenMap.MAX_SENSITIVE_CHARS + 1);
        while (value.length() <= PathTokenMap.MAX_SENSITIVE_CHARS) value.append('x');

        try {
            map.tokenForSensitiveText(value.toString(), "label");
            fail("oversized sensitive value should be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("exceeds"));
        }

        assertEquals(0, map.sensitiveEntryCount());
        assertEquals(0L, map.sensitiveCharacterCount());
        assertEquals(1L, map.rejectedEntryCount());
    }

    @Test
    public void sensitiveEntryCapacityFailsExplicitlyAndRemainsBounded() {
        PathTokenMap map = new PathTokenMap(bytes(6));
        for (int i = 0; i < PathTokenMap.MAX_SENSITIVE_TOKENS; i++) {
            map.tokenForSensitiveText("v" + i, "label");
        }

        try {
            map.tokenForSensitiveText("overflow", "label");
            fail("capacity overflow should be rejected");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("capacity"));
        }

        assertEquals(PathTokenMap.MAX_SENSITIVE_TOKENS, map.sensitiveEntryCount());
        assertEquals(1L, map.rejectedEntryCount());
    }

    @Test
    public void concurrentRegistrationIsAtomicAndReturnsOneStrongToken()
            throws Exception {
        final PathTokenMap map = new PathTokenMap(bytes(7));
        final Set<String> tokens = java.util.Collections.synchronizedSet(
                new HashSet<String>());
        final CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            for (int i = 0; i < 32; i++) {
                pool.submit(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            start.await();
                            tokens.add(map.tokenForPathString("study/subject-017.lif"));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, tokens.size());
        assertTrue(tokens.iterator().next().matches("image-[0-9a-f]{32}\\.lif"));
        assertEquals(1, map.pathEntryCount());
        assertFalse(map.resolve(tokens.iterator().next()).isEmpty());
    }

    private static byte[] bytes(int value) {
        byte[] salt = new byte[32];
        for (int i = 0; i < salt.length; i++) {
            salt[i] = (byte) value;
        }
        return salt;
    }
}
