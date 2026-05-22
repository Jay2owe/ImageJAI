package imagejai.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Optional;

/**
 * Reads and writes per-folder Privacy Posture sidecar files.
 */
public class FolderPostureStore {

    public static final String FILE_NAME = ".imagejai-posture.json";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final class Record {
        private PrivacyPosture posture;
        @SerializedName("set_by")
        private String setBy;
        @SerializedName("set_at")
        private String setAt;
        private String notes;

        @SuppressWarnings("unused")
        private Record() {
        }

        public Record(PrivacyPosture posture, String setBy, String setAt, String notes) {
            this.posture = posture == null ? PrivacyPosture.defaultPosture() : posture;
            this.setBy = emptyToDefault(setBy, "user");
            this.setAt = emptyToDefault(setAt, Instant.now().toString());
            this.notes = notes == null ? "" : notes;
        }

        public PrivacyPosture posture() {
            return posture;
        }

        public String setBy() {
            return setBy;
        }

        public String setAt() {
            return setAt;
        }

        public String notes() {
            return notes;
        }

        private void normalise() {
            if (posture == null) {
                posture = PrivacyPosture.defaultPosture();
            }
            setBy = emptyToDefault(setBy, "user");
            setAt = emptyToDefault(setAt, Instant.now().toString());
            if (notes == null) {
                notes = "";
            }
        }
    }

    public Path postureFile(Path folder) {
        return normaliseFolder(folder).resolve(FILE_NAME);
    }

    public Optional<Record> read(Path folder) throws IOException {
        Path file = postureFile(folder);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Record record = GSON.fromJson(reader, Record.class);
            if (record == null) {
                throw new IOException("Privacy Posture file is empty: " + file);
            }
            record.normalise();
            return Optional.of(record);
        } catch (JsonParseException e) {
            throw new IOException("Invalid Privacy Posture file: " + file, e);
        }
    }

    public Record write(Path folder, PrivacyPosture posture, String setBy, String notes)
            throws IOException {
        Path dir = normaliseFolder(folder);
        Files.createDirectories(dir);

        Record record = new Record(posture, setBy, Instant.now().toString(), notes);
        Path file = dir.resolve(FILE_NAME);
        Path tmp = file.resolveSibling(FILE_NAME + ".tmp");

        try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
            GSON.toJson(record, writer);
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignore) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return record;
    }

    private static Path normaliseFolder(Path folder) {
        if (folder == null) {
            throw new IllegalArgumentException("folder must not be null");
        }
        return folder.toAbsolutePath().normalize();
    }

    private static String emptyToDefault(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }
}
