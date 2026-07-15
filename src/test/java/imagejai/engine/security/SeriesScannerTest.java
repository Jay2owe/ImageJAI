package imagejai.engine.security;

import loci.formats.FormatException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SeriesScannerTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void scansSeriesMetadataAndCachesByPathAndMtime() throws Exception {
        Path file = temp.newFile("wt_8w_male_001.lif").toPath();
        AtomicInteger created = new AtomicInteger();
        SeriesScanner scanner = new SeriesScanner(new PathTokenMap(bytes(9)),
                new SeriesScanner.ReaderFactory() {
                    @Override
                    public SeriesScanner.MetadataReader create() {
                        created.incrementAndGet();
                        return new FakeReader();
                    }
                });

        List<SeriesScanner.SeriesInfo> first = scanner.scan(file);
        List<SeriesScanner.SeriesInfo> second = scanner.scan(file);

        assertEquals(1, created.get());
        assertEquals(2, first.size());
        assertEquals(first, second);
        assertEquals("wt_8w_male_001", first.get(0).label());
        assertEquals(1, first.get(0).series());
        assertTrue(first.get(0).token().matches("image-[0-9a-f]{4}\\.lif:1"));
        assertEquals(4, first.get(0).sizeC());
        assertEquals("64x32x5", first.get(0).dimensionsLabel());
    }

    @Test
    public void cacheInvalidatesWhenMtimeChanges() throws Exception {
        Path file = temp.newFile("sample.tif").toPath();
        AtomicInteger created = new AtomicInteger();
        SeriesScanner scanner = new SeriesScanner(new PathTokenMap(bytes(10)),
                new SeriesScanner.ReaderFactory() {
                    @Override
                    public SeriesScanner.MetadataReader create() {
                        created.incrementAndGet();
                        return new FakeReader();
                    }
                });

        scanner.scan(file);
        long previousMtime = Files.getLastModifiedTime(file).toMillis();
        Files.setLastModifiedTime(file,
                java.nio.file.attribute.FileTime.fromMillis(previousMtime + 2000L));
        scanner.scan(file);

        assertEquals(2, created.get());
    }

    @Test
    public void metadataReaderInterfaceDoesNotExposePixelPlaneReads() {
        List<String> forbiddenFragments = Arrays.asList(
                "openbytes", "openplane", "openthumb", "getpixels", "readpixels");
        for (java.lang.reflect.Method method : SeriesScanner.MetadataReader.class.getMethods()) {
            String name = method.getName().toLowerCase(Locale.ROOT);
            for (String forbidden : forbiddenFragments) {
                assertTrue(method.getName() + " must not expose pixel reads",
                        !name.contains(forbidden));
            }
        }
    }

    private static byte[] bytes(int value) {
        byte[] salt = new byte[32];
        for (int i = 0; i < salt.length; i++) {
            salt[i] = (byte) value;
        }
        return salt;
    }

    private static final class FakeReader implements SeriesScanner.MetadataReader {
        private int series;

        @Override public void setId(String id) throws IOException, FormatException { }
        @Override public int getSeriesCount() { return 2; }
        @Override public void setSeries(int series) { this.series = series; }
        @Override public Hashtable<String, Object> getSeriesMetadata() {
            Hashtable<String, Object> metadata = new Hashtable<String, Object>();
            metadata.put("Image name", series == 0
                    ? "wt_8w_male_001"
                    : "wt_8w_male_002");
            return metadata;
        }
        @Override public int getSizeX() { return 64; }
        @Override public int getSizeY() { return 32; }
        @Override public int getSizeZ() { return 5; }
        @Override public int getSizeC() { return 4; }
        @Override public int getSizeT() { return 1; }
        @Override public String getFormat() { return "Leica LIF"; }
        @Override public void close() throws IOException { }
    }
}
