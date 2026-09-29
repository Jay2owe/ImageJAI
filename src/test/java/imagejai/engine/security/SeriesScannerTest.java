package imagejai.engine.security;

import loci.formats.FormatException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.AccessDeniedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
        assertTrue(first.get(0).token().matches("image-[0-9a-f]{32}\\.lif:1"));
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

    @Test
    public void unreadableMetadataIsNotCachedAndSucceedsAfterAccessIsRestored() throws Exception {
        Path file = temp.newFile("restored.lif").toPath();
        AtomicInteger created = new AtomicInteger();
        SeriesScanner scanner = new SeriesScanner(new PathTokenMap(bytes(11)),
                new SeriesScanner.ReaderFactory() {
                    @Override
                    public SeriesScanner.MetadataReader create() {
                        if (created.getAndIncrement() == 0) {
                            return new FakeReader() {
                                @Override
                                public void setId(String id) throws IOException {
                                    throw new AccessDeniedException(id);
                                }
                            };
                        }
                        return new FakeReader();
                    }
                });

        List<SeriesScanner.SeriesInfo> denied = scanner.scan(file);
        List<SeriesScanner.SeriesInfo> restored = scanner.scan(file);

        assertEquals(2, created.get());
        assertEquals(1, denied.size());
        assertFalse(denied.get(0).readable());
        assertEquals(2, restored.size());
        assertTrue(restored.get(0).readable());
    }

    @Test
    public void deniedFolderIsDistinctFromEmptyAndCanBeRetried() throws Exception {
        Path folder = temp.newFolder("folder-restored").toPath();
        Files.createFile(folder.resolve("image.tif"));
        AtomicInteger opens = new AtomicInteger();
        SeriesScanner scanner = new SeriesScanner(new PathTokenMap(bytes(12)),
                new SeriesScanner.ReaderFactory() {
                    @Override public SeriesScanner.MetadataReader create() { return new FakeReader(); }
                }, directory -> {
                    if (opens.getAndIncrement() == 0) {
                        throw new AccessDeniedException(directory.toString());
                    }
                    return Files.newDirectoryStream(directory);
                });

        try {
            scanner.scanFolder(folder);
            throw new AssertionError("Expected folder_unreadable");
        } catch (SeriesScanner.ScanException expected) {
            assertEquals("folder_unreadable", expected.code());
        }
        assertEquals(2, scanner.scanFolder(folder).size());
    }

    @Test
    public void deniedPathProbeFailsBeforeListingAndCanBeRetried() throws Exception {
        Path folder = temp.newFolder("probe-restored").toPath();
        Files.createFile(folder.resolve("image.tif"));
        AtomicInteger probes = new AtomicInteger();
        AtomicInteger opens = new AtomicInteger();
        SeriesScanner scanner = new SeriesScanner(new PathTokenMap(bytes(14)),
                new SeriesScanner.ReaderFactory() {
                    @Override public SeriesScanner.MetadataReader create() { return new FakeReader(); }
                }, directory -> {
                    opens.incrementAndGet();
                    return Files.newDirectoryStream(directory);
                }, path -> {
                    if (probes.getAndIncrement() == 0) {
                        throw new AccessDeniedException(path.toString());
                    }
                    return Files.readAttributes(path,
                            java.nio.file.attribute.BasicFileAttributes.class);
                });

        try {
            scanner.scanFolder(folder);
            throw new AssertionError("Expected folder_unreadable");
        } catch (SeriesScanner.ScanException expected) {
            assertEquals("folder_unreadable", expected.code());
        }
        assertEquals(0, opens.get());
        assertEquals(2, scanner.scanFolder(folder).size());
        assertEquals(1, opens.get());
    }

    @Test
    public void folderEnumerationStopsAtSafetyCap() throws Exception {
        Path folder = temp.newFolder("bounded-folder").toPath();
        Path repeated = Files.createFile(folder.resolve("image.tif"));
        SeriesScanner scanner = new SeriesScanner(new PathTokenMap(bytes(13)),
                new SeriesScanner.ReaderFactory() {
                    @Override public SeriesScanner.MetadataReader create() { return new FakeReader(); }
                }, directory -> repeatingDirectory(repeated,
                        SeriesScanner.MAX_DIRECTORY_ENTRIES + 1));

        try {
            scanner.scanFolder(folder);
            throw new AssertionError("Expected directory_entry_cap");
        } catch (SeriesScanner.ScanException expected) {
            assertEquals("directory_entry_cap", expected.code());
        }
    }

    private static DirectoryStream<Path> repeatingDirectory(final Path path, final int count) {
        return new DirectoryStream<Path>() {
            @Override
            public Iterator<Path> iterator() {
                return new Iterator<Path>() {
                    private int index;
                    @Override public boolean hasNext() { return index < count; }
                    @Override public Path next() {
                        if (!hasNext()) throw new NoSuchElementException();
                        index++;
                        return path;
                    }
                };
            }
            @Override public void close() { }
        };
    }

    private static byte[] bytes(int value) {
        byte[] salt = new byte[32];
        for (int i = 0; i < salt.length; i++) {
            salt[i] = (byte) value;
        }
        return salt;
    }

    private static class FakeReader implements SeriesScanner.MetadataReader {
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
