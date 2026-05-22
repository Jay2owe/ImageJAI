package imagejai.engine.security;

import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class PathTokenMapTest {
    @Test
    public void pathTokensAreStableInMapAndRandomisedBySalt() {
        Path path = Paths.get("MOAB2", "subject_017.lif");
        PathTokenMap a = new PathTokenMap(bytes(1));
        PathTokenMap b = new PathTokenMap(bytes(2));

        String first = a.tokenForPath(path);
        String second = a.tokenForPath(path);

        assertEquals(first, second);
        assertTrue(first.matches("image-[0-9a-f]{4}\\.lif"));
        assertNotEquals(first, b.tokenForPath(path));
    }

    @Test
    public void seriesTokensResolveBackToRealPathAndSeries() {
        Path path = Paths.get("MOAB2", "subject_017.lif");
        PathTokenMap map = new PathTokenMap(bytes(3));
        String token = map.tokenForSeries(path, 4);

        assertTrue(token.matches("image-[0-9a-f]{4}\\.lif:4"));
        PathTokenMap.ResolvedTarget resolved = map.resolve(token).get();

        assertEquals(path, resolved.realPath());
        assertEquals(4, resolved.series());
        assertEquals(-1, map.resolve(map.tokenForPath(path)).get().series());
    }

    private static byte[] bytes(int value) {
        byte[] salt = new byte[32];
        for (int i = 0; i < salt.length; i++) {
            salt[i] = (byte) value;
        }
        return salt;
    }
}
