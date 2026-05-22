package imagejai.engine.security;

import loci.formats.FormatException;
import loci.formats.IFormatReader;
import loci.formats.ImageReader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Metadata-only Bio-Formats series scanner. It calls {@code setId},
 * series/dimension/metadata accessors, and {@code close}; it never calls
 * pixel-plane APIs such as {@code openBytes}.
 */
public final class SeriesScanner {
    public interface MetadataReader extends AutoCloseable {
        void setId(String id) throws IOException, FormatException;
        int getSeriesCount();
        void setSeries(int series);
        Hashtable<String, Object> getSeriesMetadata();
        int getSizeX();
        int getSizeY();
        int getSizeZ();
        int getSizeC();
        int getSizeT();
        String getFormat();
        @Override void close() throws IOException;
    }

    interface ReaderFactory {
        MetadataReader create();
    }

    private static final ReaderFactory BIO_FORMATS_READER_FACTORY = new ReaderFactory() {
        @Override
        public MetadataReader create() {
            return new BioFormatsMetadataReader(new ImageReader());
        }
    };

    private final PathTokenMap tokenMap;
    private final ReaderFactory readerFactory;
    private final ConcurrentHashMap<CacheKey, List<SeriesInfo>> cache =
            new ConcurrentHashMap<CacheKey, List<SeriesInfo>>();

    public SeriesScanner() {
        this(PathTokenMap.getInstance(), BIO_FORMATS_READER_FACTORY);
    }

    SeriesScanner(PathTokenMap tokenMap, ReaderFactory readerFactory) {
        this.tokenMap = tokenMap == null ? PathTokenMap.getInstance() : tokenMap;
        this.readerFactory = readerFactory == null
                ? BIO_FORMATS_READER_FACTORY
                : readerFactory;
    }

    public List<SeriesInfo> scanFolder(Path folder) {
        if (folder == null || !Files.isDirectory(folder)) {
            return Collections.emptyList();
        }
        List<Path> files = new ArrayList<Path>();
        try (java.util.stream.Stream<Path> stream = Files.list(folder)) {
            stream.filter(Files::isRegularFile)
                    .filter(SeriesScanner::isSupportedImage)
                    .sorted()
                    .forEach(files::add);
        } catch (IOException e) {
            return Collections.emptyList();
        }
        List<SeriesInfo> out = new ArrayList<SeriesInfo>();
        for (Path file : files) {
            out.addAll(scan(file));
        }
        return Collections.unmodifiableList(out);
    }

    public List<SeriesInfo> scan(Path file) {
        if (file == null || !Files.isRegularFile(file) || !isSupportedImage(file)) {
            return Collections.emptyList();
        }
        CacheKey key = cacheKey(file);
        List<SeriesInfo> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        List<SeriesInfo> scanned = scanUncached(file.toAbsolutePath().normalize());
        List<SeriesInfo> existing = cache.putIfAbsent(key, scanned);
        return existing == null ? scanned : existing;
    }

    private List<SeriesInfo> scanUncached(Path file) {
        MetadataReader reader = readerFactory.create();
        List<SeriesInfo> out = new ArrayList<SeriesInfo>();
        try {
            reader.setId(file.toString());
            int count = Math.max(1, reader.getSeriesCount());
            boolean tokeniseAsSeries = count > 1 || isContainerFormat(file);
            for (int i = 0; i < count; i++) {
                reader.setSeries(i);
                Hashtable<String, Object> seriesMetadata = reader.getSeriesMetadata();
                int publicSeries = tokeniseAsSeries ? i + 1 : -1;
                String token = publicSeries >= 0
                        ? tokenMap.tokenForSeries(file, publicSeries)
                        : tokenMap.tokenForPath(file);
                out.add(new SeriesInfo(file, publicSeries, token,
                        labelFor(file, i, seriesMetadata), reader.getFormat(),
                        reader.getSizeX(), reader.getSizeY(), reader.getSizeZ(),
                        reader.getSizeC(), reader.getSizeT(), true, ""));
            }
        } catch (Exception e) {
            out.add(new SeriesInfo(file, -1, tokenMap.tokenForPath(file),
                    baseName(file), "", 0, 0, 0, 0, 0, false,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } finally {
            try {
                reader.close();
            } catch (IOException ignored) {
            }
        }
        return Collections.unmodifiableList(out);
    }

    static boolean isSupportedImage(Path file) {
        String name = file == null || file.getFileName() == null
                ? ""
                : file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".lif")
                || name.endsWith(".czi")
                || name.endsWith(".nd2")
                || name.endsWith(".ome.tif")
                || name.endsWith(".ome.tiff")
                || name.endsWith(".tif")
                || name.endsWith(".tiff");
    }

    private static boolean isContainerFormat(Path file) {
        String name = file == null || file.getFileName() == null
                ? ""
                : file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".lif") || name.endsWith(".czi") || name.endsWith(".nd2");
    }

    private static CacheKey cacheKey(Path file) {
        Path normalised = file.toAbsolutePath().normalize();
        try {
            FileTime mtime = Files.getLastModifiedTime(normalised);
            return new CacheKey(normalised, mtime.toMillis());
        } catch (IOException e) {
            return new CacheKey(normalised, -1L);
        }
    }

    private static String labelFor(Path file, int zeroBasedSeries,
                                   Hashtable<String, Object> metadata) {
        String[] keys = {"Image name", "Image Name", "Name", "Series name",
                "Series Name", "Title"};
        if (metadata != null) {
            for (String key : keys) {
                Object value = metadata.get(key);
                if (value != null && !value.toString().trim().isEmpty()) {
                    return value.toString().trim();
                }
            }
        }
        if (zeroBasedSeries <= 0) {
            return baseName(file);
        }
        return baseName(file) + " :" + (zeroBasedSeries + 1);
    }

    private static String baseName(Path file) {
        String name = file == null || file.getFileName() == null
                ? "image"
                : file.getFileName().toString();
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ome.tiff")) {
            return name.substring(0, name.length() - ".ome.tiff".length());
        }
        if (lower.endsWith(".ome.tif")) {
            return name.substring(0, name.length() - ".ome.tif".length());
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static final class BioFormatsMetadataReader implements MetadataReader {
        private final IFormatReader delegate;

        BioFormatsMetadataReader(IFormatReader delegate) {
            this.delegate = delegate;
        }

        @Override
        public void setId(String id) throws IOException, FormatException {
            delegate.setMetadataFiltered(true);
            delegate.setOriginalMetadataPopulated(false);
            delegate.setGroupFiles(false);
            delegate.setFlattenedResolutions(false);
            delegate.setId(id);
        }

        @Override public int getSeriesCount() { return delegate.getSeriesCount(); }
        @Override public void setSeries(int series) { delegate.setSeries(series); }
        @Override public Hashtable<String, Object> getSeriesMetadata() {
            return delegate.getSeriesMetadata();
        }
        @Override public int getSizeX() { return delegate.getSizeX(); }
        @Override public int getSizeY() { return delegate.getSizeY(); }
        @Override public int getSizeZ() { return delegate.getSizeZ(); }
        @Override public int getSizeC() { return delegate.getSizeC(); }
        @Override public int getSizeT() { return delegate.getSizeT(); }
        @Override public String getFormat() { return delegate.getFormat(); }
        @Override public void close() throws IOException { delegate.close(); }
    }

    private static final class CacheKey {
        private final Path path;
        private final long mtime;

        CacheKey(Path path, long mtime) {
            this.path = path;
            this.mtime = mtime;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof CacheKey)) {
                return false;
            }
            CacheKey key = (CacheKey) other;
            return mtime == key.mtime && Objects.equals(path, key.path);
        }

        @Override
        public int hashCode() {
            return Objects.hash(path, mtime);
        }
    }

    public static final class SeriesInfo {
        private final Path file;
        private final int series;
        private final String token;
        private final String label;
        private final String format;
        private final int sizeX;
        private final int sizeY;
        private final int sizeZ;
        private final int sizeC;
        private final int sizeT;
        private final boolean readable;
        private final String error;

        SeriesInfo(Path file, int series, String token, String label, String format,
                   int sizeX, int sizeY, int sizeZ, int sizeC, int sizeT,
                   boolean readable, String error) {
            this.file = file;
            this.series = series;
            this.token = token == null ? "" : token;
            this.label = label == null ? "" : label;
            this.format = format == null ? "" : format;
            this.sizeX = Math.max(0, sizeX);
            this.sizeY = Math.max(0, sizeY);
            this.sizeZ = Math.max(0, sizeZ);
            this.sizeC = Math.max(0, sizeC);
            this.sizeT = Math.max(0, sizeT);
            this.readable = readable;
            this.error = error == null ? "" : error;
        }

        public Path file() { return file; }
        public int series() { return series; }
        public String token() { return token; }
        public String label() { return label; }
        public String format() { return format; }
        public int sizeX() { return sizeX; }
        public int sizeY() { return sizeY; }
        public int sizeZ() { return sizeZ; }
        public int sizeC() { return sizeC; }
        public int sizeT() { return sizeT; }
        public boolean readable() { return readable; }
        public String error() { return error; }

        public String dimensionsLabel() {
            if (!readable) {
                return "unreadable";
            }
            StringBuilder sb = new StringBuilder();
            if (sizeX > 0 && sizeY > 0) {
                sb.append(sizeX).append('x').append(sizeY);
            }
            if (sizeZ > 1) {
                sb.append('x').append(sizeZ);
            }
            if (sizeT > 1) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(sizeT).append("t");
            }
            return sb.toString();
        }

        public Map<String, Object> safeMetadata() {
            Map<String, Object> out = new LinkedHashMap<String, Object>();
            out.put("token", token);
            out.put("series", series);
            out.put("channels", sizeC);
            out.put("size_x", sizeX);
            out.put("size_y", sizeY);
            out.put("size_z", sizeZ);
            out.put("size_t", sizeT);
            out.put("format", format);
            return out;
        }
    }
}
