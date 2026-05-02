package imagejai.engine.picker;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads {@code agent/providers/models.yaml} into immutable {@link ModelEntry}
 * objects. Promoted from Phase D's inline parser so the merge layer can drive
 * it without pulling in {@link ProviderRegistry}'s grouping logic.
 *
 * <p>Phase G additions over Phase D's loader:
 * <ul>
 *   <li>{@code last_verified} — ISO date, used to flag stale entries.</li>
 *   <li>{@code deprecated_since} — ISO date, set by soft-deprecation.</li>
 *   <li>{@code replacement} — model_id curator suggests as a swap.</li>
 * </ul>
 *
 * <p>Always loaded with {@link SafeConstructor}. Never accept untrusted YAML
 * with the default Constructor — see risk #16 in §4 of
 * docs/multi_provider/07_implementation_plan.md.
 */
public final class ModelsYamlLoader {

    public static final String BUNDLED_RESOURCE = "/agent/providers/models.yaml";

    private ModelsYamlLoader() {
        // static-only utility
    }

    /** Load entries from the bundled resource on the classpath. */
    public static List<ModelEntry> loadBundled() {
        try (InputStream in = ModelsYamlLoader.class.getResourceAsStream(BUNDLED_RESOURCE)) {
            if (in == null) {
                return Collections.emptyList();
            }
            return loadFromStream(in);
        } catch (IOException ex) {
            return Collections.emptyList();
        }
    }

    /** Load entries from a filesystem path — used by tests and audit tooling. */
    public static List<ModelEntry> loadFromPath(Path yamlPath) throws IOException {
        if (yamlPath == null || !Files.exists(yamlPath)) {
            return Collections.emptyList();
        }
        try (InputStream in = Files.newInputStream(yamlPath)) {
            return loadFromStream(in);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<ModelEntry> loadFromStream(InputStream in) {
        LoaderOptions options = new LoaderOptions();
        options.setMaxAliasesForCollections(50);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        Object root = yaml.load(in);
        if (!(root instanceof Map)) {
            return Collections.emptyList();
        }
        Object modelsObj = ((Map<String, Object>) root).get("models");
        if (!(modelsObj instanceof List)) {
            return Collections.emptyList();
        }

        Set<String> known = new HashSet<String>(ProviderRegistry.CANONICAL_PROVIDERS);
        List<ModelEntry> out = new ArrayList<ModelEntry>();
        for (Object raw : (List<Object>) modelsObj) {
            if (!(raw instanceof Map)) {
                continue;
            }
            Map<String, Object> row = (Map<String, Object>) raw;
            String provider = stringValue(row.get("provider"));
            String modelId = stringValue(row.get("model_id"));
            if (provider == null || modelId == null) {
                continue;
            }
            String providerKey = provider.trim().toLowerCase();
            if (!known.contains(providerKey)) {
                continue;
            }
            ModelEntry entry = new ModelEntry(
                    providerKey,
                    modelId.trim(),
                    stringValue(row.get("display_name")),
                    stringValue(row.get("description")),
                    ModelEntry.Tier.fromYaml(stringValue(row.get("tier"))),
                    intValue(row.get("context_window"), 0),
                    boolValue(row.get("vision_capable"), false),
                    ModelEntry.Reliability.fromYaml(stringValue(row.get("tool_call_reliability"))),
                    boolValue(row.get("pinned"), false),
                    boolValue(row.get("curated"), true),
                    stringValue(row.get("notes")),
                    dateValue(row.get("last_verified")),
                    dateValue(row.get("deprecated_since")),
                    stringValue(row.get("replacement")),
                    nativeFeaturesValue(row.get("native_features")));
            out.add(entry);
        }
        return out;
    }

    static String stringValue(Object o) {
        if (o == null) {
            return null;
        }
        String s = o.toString().trim();
        return s.isEmpty() ? null : s;
    }

    static int intValue(Object o, int defaultValue) {
        if (o instanceof Number) {
            return ((Number) o).intValue();
        }
        if (o instanceof String) {
            try {
                return Integer.parseInt(((String) o).trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    static boolean boolValue(Object o, boolean defaultValue) {
        if (o instanceof Boolean) {
            return (Boolean) o;
        }
        if (o instanceof String) {
            String s = ((String) o).trim().toLowerCase();
            if ("true".equals(s) || "yes".equals(s)) return true;
            if ("false".equals(s) || "no".equals(s)) return false;
        }
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> nativeFeaturesValue(Object raw) {
        if (!(raw instanceof Map)) {
            return Collections.emptyMap();
        }
        Map<Object, Object> source = (Map<Object, Object>) raw;
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (Map.Entry<Object, Object> entry : source.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) {
                continue;
            }
            out.put(entry.getKey().toString(), entry.getValue());
        }
        return out;
    }

    static LocalDate dateValue(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof LocalDate) {
            return (LocalDate) o;
        }
        if (o instanceof java.util.Date) {
            return ((java.util.Date) o).toInstant()
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate();
        }
        String s = o.toString().trim();
        if (s.isEmpty() || "null".equalsIgnoreCase(s)) {
            return null;
        }
        try {
            return LocalDate.parse(s);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
