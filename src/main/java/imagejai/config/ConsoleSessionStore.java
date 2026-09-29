package imagejai.config;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Read-only Fiji view of the chat sessions shared with ImageJAI Console. */
public final class ConsoleSessionStore {
    private final Path sessionsDir;
    private final Gson gson = new Gson();

    public ConsoleSessionStore() {
        this(Settings.getConfigDir().resolve("sessions"));
    }

    public ConsoleSessionStore(Path sessionsDir) {
        this.sessionsDir = sessionsDir;
    }

    public List<Summary> list() {
        if (!Files.isDirectory(sessionsDir)) return Collections.emptyList();
        List<Summary> sessions = new ArrayList<Summary>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(sessionsDir, "*.json")) {
            for (Path file : files) {
                try {
                    JsonObject value = JsonParser.parseString(
                            new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
                            .getAsJsonObject();
                    String id = text(value, "id");
                    if (!id.matches("[A-Za-z0-9-]{1,64}")) continue;
                    sessions.add(new Summary(
                            id,
                            defaultText(text(value, "title"), "New session"),
                            text(value, "provider"),
                            text(value, "model"),
                            number(value, "updated"),
                            file));
                } catch (Exception ignored) {
                    // One interrupted or foreign file must not hide other sessions.
                }
            }
        } catch (IOException ignored) {
            return Collections.emptyList();
        }
        Collections.sort(sessions, new Comparator<Summary>() {
            @Override public int compare(Summary a, Summary b) {
                return Double.compare(b.updated, a.updated);
            }
        });
        return sessions;
    }

    public String transcript(Summary session) throws IOException {
        JsonObject root = JsonParser.parseString(
                new String(Files.readAllBytes(session.path), StandardCharsets.UTF_8))
                .getAsJsonObject();
        StringBuilder out = new StringBuilder();
        out.append(session.title).append("\n\n");
        JsonElement messages = root.get("messages");
        if (messages == null || !messages.isJsonArray()) return out.toString();
        for (JsonElement element : messages.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject message = element.getAsJsonObject();
            String role = text(message, "role");
            if ("system".equals(role)) continue;
            JsonElement content = message.get("content");
            if (content == null || content.isJsonNull()) continue;
            out.append(role.isEmpty() ? "message" : role).append(": ");
            out.append(content.isJsonPrimitive()
                    ? content.getAsString()
                    : gson.toJson(content));
            out.append("\n\n");
        }
        return out.toString();
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static double number(JsonObject object, String key) {
        JsonElement value = object.get(key);
        try {
            return value == null || value.isJsonNull() ? 0.0 : value.getAsDouble();
        } catch (RuntimeException ignored) {
            return 0.0;
        }
    }

    private static String defaultText(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value;
    }

    public static final class Summary {
        public final String id;
        public final String title;
        public final String provider;
        public final String model;
        public final double updated;
        final Path path;

        Summary(String id, String title, String provider, String model,
                double updated, Path path) {
            this.id = id;
            this.title = title;
            this.provider = provider;
            this.model = model;
            this.updated = updated;
            this.path = path;
        }
    }
}
