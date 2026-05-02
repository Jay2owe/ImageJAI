package imagejai.engine.picker;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Reads and writes {@code models_local.yaml} — the per-user pin / hide override
 * file located alongside the curated registry.
 *
 * <p>Path resolution per docs/multi_provider/02_curation_strategy.md §4:
 * <ul>
 *   <li>Windows: {@code %APPDATA%\imagejai\models_local.yaml}</li>
 *   <li>macOS:   {@code ~/Library/Application Support/imagejai/models_local.yaml}</li>
 *   <li>Linux:   {@code ${XDG_CONFIG_HOME:-~/.config}/imagejai/models_local.yaml}</li>
 * </ul>
 *
 * <p>SafeConstructor mandatory — file is user-editable, treated as untrusted
 * (risk #16 in §4 of docs/multi_provider/07_implementation_plan.md).
 */
public final class ModelsLocalLoader {

    /** One row's worth of override state. */
    public static final class Override {
        private final String providerId;
        private final String modelId;
        private final Boolean pinned;
        private final Boolean hidden;

        public Override(String providerId, String modelId, Boolean pinned, Boolean hidden) {
            this.providerId = Objects.requireNonNull(providerId, "providerId");
            this.modelId = Objects.requireNonNull(modelId, "modelId");
            this.pinned = pinned;
            this.hidden = hidden;
        }

        public String providerId() { return providerId; }
        public String modelId() { return modelId; }
        public Boolean pinned() { return pinned; }
        public Boolean hidden() { return hidden; }

        public String key() { return providerId + " " + modelId; }
    }

    public static final String FILENAME = "models_local.yaml";

    private final Path filePath;

    public ModelsLocalLoader(Path filePath) {
        this.filePath = Objects.requireNonNull(filePath, "filePath");
    }

    public Path filePath() {
        return filePath;
    }

    /**
     * Resolve the platform-appropriate config root. Used by the production
     * {@link ProviderRegistry} bootstrap; tests construct with their own path.
     */
    public static Path resolveDefaultPath() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path base;
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            base = appData != null && !appData.isEmpty()
                    ? Paths.get(appData, "imagejai")
                    : Paths.get(System.getProperty("user.home"), ".imagejai");
        } else if (os.contains("mac")) {
            base = Paths.get(System.getProperty("user.home"),
                    "Library", "Application Support", "imagejai");
        } else {
            String xdg = System.getenv("XDG_CONFIG_HOME");
            base = xdg != null && !xdg.isEmpty()
                    ? Paths.get(xdg, "imagejai")
                    : Paths.get(System.getProperty("user.home"), ".config", "imagejai");
        }
        return base.resolve(FILENAME);
    }

    /** Load all overrides from {@link #filePath}; returns empty list if missing. */
    @SuppressWarnings("unchecked")
    public List<Override> load() {
        if (!Files.exists(filePath)) {
            return Collections.emptyList();
        }
        try (InputStream in = Files.newInputStream(filePath)) {
            LoaderOptions options = new LoaderOptions();
            options.setMaxAliasesForCollections(50);
            Yaml yaml = new Yaml(new SafeConstructor(options));
            Object root = yaml.load(in);
            if (!(root instanceof Map)) {
                return Collections.emptyList();
            }
            Object overridesObj = ((Map<String, Object>) root).get("overrides");
            if (!(overridesObj instanceof List)) {
                return Collections.emptyList();
            }
            List<Override> out = new ArrayList<Override>();
            for (Object raw : (List<Object>) overridesObj) {
                if (!(raw instanceof Map)) {
                    continue;
                }
                Map<String, Object> row = (Map<String, Object>) raw;
                String provider = ModelsYamlLoader.stringValue(row.get("provider"));
                String modelId = ModelsYamlLoader.stringValue(row.get("model_id"));
                if (provider == null || modelId == null) {
                    continue;
                }
                Boolean pinned = optBool(row.get("pinned"));
                Boolean hidden = optBool(row.get("hidden"));
                out.add(new Override(provider.toLowerCase(), modelId, pinned, hidden));
            }
            return out;
        } catch (IOException ex) {
            return Collections.emptyList();
        }
    }

    /** Index loaded overrides by {@code providerId + " " + modelId}. */
    public Map<String, Override> loadAsMap() {
        Map<String, Override> map = new LinkedHashMap<String, Override>();
        for (Override o : load()) {
            map.put(o.key(), o);
        }
        return map;
    }

    /**
     * Write {@code overrides} atomically to {@link #filePath} (write to a tmp
     * file then rename — protects against torn writes from concurrent agents).
     */
    public void save(List<Override> overrides) throws IOException {
        Path parent = filePath.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        Path tmp = parent == null
                ? filePath.resolveSibling(filePath.getFileName().toString() + ".tmp")
                : Files.createTempFile(parent, "models_local-", ".tmp");
        try (Writer w = new BufferedWriter(Files.newBufferedWriter(tmp, StandardCharsets.UTF_8))) {
            w.write("# Managed by the ImageJAI dropdown — pin/hide overrides.\n");
            w.write("# Hand-edits preserved; the dropdown only writes keys you have toggled.\n");
            w.write("version: 1\n");
            w.write("overrides:\n");
            for (Override o : overrides) {
                w.write("  - provider: " + o.providerId() + "\n");
                w.write("    model_id: " + o.modelId() + "\n");
                if (o.pinned() != null) {
                    w.write("    pinned: " + o.pinned() + "\n");
                }
                if (o.hidden() != null) {
                    w.write("    hidden: " + o.hidden() + "\n");
                }
            }
        }
        Files.move(tmp, filePath,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    /** Convenience: toggle the pinned flag for one entry, persisting to disk. */
    public void setPinned(String providerId, String modelId, boolean pinned) throws IOException {
        Map<String, Override> map = loadAsMap();
        String key = providerId + " " + modelId;
        Override existing = map.get(key);
        Override merged = new Override(providerId, modelId, pinned,
                existing == null ? null : existing.hidden());
        map.put(key, merged);
        save(new ArrayList<Override>(map.values()));
    }

    private static Boolean optBool(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        String s = o.toString().trim().toLowerCase(Locale.ROOT);
        if (s.equals("true") || s.equals("yes")) return Boolean.TRUE;
        if (s.equals("false") || s.equals("no")) return Boolean.FALSE;
        return null;
    }
}
