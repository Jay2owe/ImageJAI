package imagejai.engine;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validated command descriptors loaded from the manifest packaged in the jar. */
final class CommandManifest {
    static final String RESOURCE = "/imagejai/command_manifest.json";
    private static final Set<String> TRANSPORTS = setOf("request_response", "stream");
    private static final Set<String> CLASSES = setOf("read_only", "mutation", "administrative");
    private static final Set<String> AUTH = setOf("public", "session_optional", "session_required");
    private static final Set<String> PYTHON_COVERAGE = setOf("convenience", "raw");
    private static final CommandManifest INSTANCE = load();

    private final String productVersion;
    private final List<Descriptor> commands;
    private final Map<String, Descriptor> byName;

    private CommandManifest(String productVersion, List<Descriptor> commands) {
        this.productVersion = productVersion;
        this.commands = Collections.unmodifiableList(new ArrayList<Descriptor>(commands));
        Map<String, Descriptor> descriptors = new LinkedHashMap<String, Descriptor>();
        for (Descriptor command : commands) descriptors.put(command.name, command);
        this.byName = Collections.unmodifiableMap(descriptors);
    }

    static String productVersion() {
        return INSTANCE.productVersion;
    }

    static List<Descriptor> descriptors() {
        return INSTANCE.commands;
    }

    static List<String> allNames() {
        return namesForTransport(null);
    }

    static List<String> requestResponseNames() {
        return namesForTransport("request_response");
    }

    static List<String> streamNames() {
        return namesForTransport("stream");
    }

    static Set<String> hashDedupNames() {
        Set<String> names = new HashSet<String>();
        for (Descriptor descriptor : INSTANCE.commands) {
            if (descriptor.hashDedup) names.add(descriptor.name);
        }
        return Collections.unmodifiableSet(names);
    }

    static Descriptor descriptor(String name) {
        return INSTANCE.byName.get(name);
    }

    private static List<String> namesForTransport(String transport) {
        List<String> names = new ArrayList<String>();
        for (Descriptor descriptor : INSTANCE.commands) {
            if (transport == null || transport.equals(descriptor.transport)) {
                names.add(descriptor.name);
            }
        }
        return Collections.unmodifiableList(names);
    }

    private static CommandManifest load() {
        try (InputStream stream = CommandManifest.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IllegalStateException("missing packaged resource " + RESOURCE);
            JsonElement parsed = JsonParser.parseReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw invalid("root must be an object");
            JsonObject root = parsed.getAsJsonObject();
            if (integer(root, "schema_version") != 1) {
                throw invalid("schema_version must be 1");
            }
            String productVersion = string(root, "product_version");
            if (!"0.3.0".equals(productVersion)) {
                throw invalid("product_version must be 0.3.0");
            }
            if (!"ImageJAI TCP JSONL".equals(string(root, "protocol"))) {
                throw invalid("protocol must be ImageJAI TCP JSONL");
            }
            JsonElement commandElement = root.get("commands");
            if (commandElement == null || !commandElement.isJsonArray()) {
                throw invalid("commands must be an array");
            }
            JsonArray array = commandElement.getAsJsonArray();
            if (array.size() == 0) throw invalid("commands cannot be empty");
            List<Descriptor> descriptors = new ArrayList<Descriptor>();
            Set<String> seen = new HashSet<String>();
            String prior = null;
            for (JsonElement element : array) {
                if (!element.isJsonObject()) throw invalid("command entry must be an object");
                JsonObject command = element.getAsJsonObject();
                String name = string(command, "name");
                String transport = enumValue(command, "transport", TRANSPORTS, name);
                String classification = enumValue(command, "classification", CLASSES, name);
                String authentication = enumValue(command, "authentication", AUTH, name);
                if (!seen.add(name)) throw invalid("duplicate command " + name);
                if (prior != null && prior.compareTo(name) >= 0) {
                    throw invalid("commands must be strictly sorted by name");
                }
                prior = name;
                JsonElement hashDedupElement = command.get("dedup_hash");
                if (hashDedupElement != null
                        && (!hashDedupElement.isJsonPrimitive()
                        || !hashDedupElement.getAsJsonPrimitive().isBoolean())) {
                    throw invalid(name + ": dedup_hash must be boolean");
                }
                boolean hashDedup = hashDedupElement != null
                        && hashDedupElement.getAsBoolean();
                if (hashDedup && !"read_only".equals(classification)) {
                    throw invalid(name + ": hash dedup requires read_only classification");
                }
                JsonObject request = requireObject(command, "request", name);
                JsonArray required = requireStringArray(request, "required", name);
                JsonArray optional = requireStringArray(request, "optional", name);
                ensureDisjoint(required, optional, name);
                validateAnyOf(request, name);
                JsonObject reply = requireObject(command, "reply", name);
                string(reply, "type");
                requireStringArray(command, "capabilities", name);
                JsonObject python = requireObject(command, "python", name);
                String coverage = enumValue(
                        python, "coverage", PYTHON_COVERAGE, name + ": python");
                JsonArray helpers = requireStringArray(python, "helpers", name);
                String summary = string(command, "summary");
                if ("convenience".equals(coverage) && helpers.size() == 0) {
                    throw invalid(name + ": convenience coverage requires a helper");
                }
                if ("raw".equals(coverage)
                        && (helpers.size() != 0 || !summary.contains("imagej_command"))) {
                    throw invalid(name + ": raw coverage must document imagej_command only");
                }
                descriptors.add(new Descriptor(name, transport, classification,
                        authentication, hashDedup));
            }
            return new CommandManifest(productVersion, descriptors);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read packaged command manifest", e);
        }
    }

    private static JsonObject requireObject(JsonObject parent, String field, String command) {
        JsonElement element = parent.get(field);
        if (element == null || !element.isJsonObject()) {
            throw invalid(command + ": " + field + " must be an object");
        }
        return element.getAsJsonObject();
    }

    private static JsonArray requireArray(JsonObject parent, String field, String command) {
        JsonElement element = parent.get(field);
        if (element == null || !element.isJsonArray()) {
            throw invalid(command + ": " + field + " must be an array");
        }
        return element.getAsJsonArray();
    }

    private static JsonArray requireStringArray(JsonObject parent, String field,
                                                String command) {
        JsonArray array = requireArray(parent, field, command);
        Set<String> seen = new HashSet<String>();
        for (JsonElement value : array) {
            if (!value.isJsonPrimitive() || value.getAsString().trim().isEmpty()) {
                throw invalid(command + ": " + field + " must contain non-blank strings");
            }
            if (!seen.add(value.getAsString())) {
                throw invalid(command + ": " + field + " contains duplicate "
                        + value.getAsString());
            }
        }
        return array;
    }

    private static void ensureDisjoint(JsonArray required, JsonArray optional,
                                       String command) {
        Set<String> names = new HashSet<String>();
        for (JsonElement value : required) names.add(value.getAsString());
        for (JsonElement value : optional) {
            if (names.contains(value.getAsString())) {
                throw invalid(command + ": field cannot be both required and optional: "
                        + value.getAsString());
            }
        }
    }

    private static void validateAnyOf(JsonObject request, String command) {
        if (!request.has("required_any_of")) return;
        JsonArray groups = requireArray(request, "required_any_of", command);
        for (JsonElement groupElement : groups) {
            if (!groupElement.isJsonArray() || groupElement.getAsJsonArray().size() < 2) {
                throw invalid(command + ": required_any_of groups need at least two fields");
            }
            Set<String> seen = new HashSet<String>();
            for (JsonElement field : groupElement.getAsJsonArray()) {
                if (!field.isJsonPrimitive() || field.getAsString().trim().isEmpty()) {
                    throw invalid(command + ": required_any_of must contain non-blank strings");
                }
                if (!seen.add(field.getAsString())) {
                    throw invalid(command + ": required_any_of contains duplicate "
                            + field.getAsString());
                }
            }
        }
    }

    private static int integer(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive()) throw invalid(field + " is required");
        try {
            return element.getAsInt();
        } catch (RuntimeException e) {
            throw invalid(field + " must be an integer");
        }
    }

    private static String string(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive()) throw invalid(field + " is required");
        String value = element.getAsString();
        if (value.trim().isEmpty()) throw invalid(field + " cannot be blank");
        return value;
    }

    private static String enumValue(JsonObject object, String field, Set<String> values,
                                    String command) {
        String value = string(object, field);
        if (!values.contains(value)) throw invalid(command + ": invalid " + field + " " + value);
        return value;
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException("invalid " + RESOURCE + ": " + message);
    }

    private static Set<String> setOf(String... values) {
        Set<String> result = new HashSet<String>();
        Collections.addAll(result, values);
        return Collections.unmodifiableSet(result);
    }

    static final class Descriptor {
        final String name;
        final String transport;
        final String classification;
        final String authentication;
        final boolean hashDedup;

        private Descriptor(String name, String transport, String classification,
                           String authentication, boolean hashDedup) {
            this.name = name;
            this.transport = transport;
            this.classification = classification;
            this.authentication = authentication;
            this.hashDedup = hashDedup;
        }
    }
}
