package imagejai.engine.security;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local-only regex parser for user-facing labels. Override patterns may live
 * in {@code .imagejai-tags.yml}; the file is never included in briefs.
 */
public final class TagSuggestionEngine {
    private final List<Rule> defaultRules;

    public TagSuggestionEngine() {
        defaultRules = defaultRules();
    }

    public String suggest(List<String> labels) {
        return suggest(labels, null);
    }

    public String suggest(List<String> labels, Path folder) {
        Map<String, String> common = commonTags(labels, rulesFor(folder));
        StringBuilder out = new StringBuilder();
        String[] order = {"timepoint", "genotype", "sex", "condition"};
        for (String key : order) {
            String value = common.get(key);
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(value.trim());
        }
        if (out.length() == 0 && labels != null && !labels.isEmpty()) {
            return "selected series";
        }
        return out.toString();
    }

    public Map<String, String> parseLabel(String label) {
        return parseLabel(label, null);
    }

    public Map<String, String> parseLabel(String label, Path folder) {
        return parseLabelWithRules(label, rulesFor(folder));
    }

    public List<Rule> rulesFor(Path folder) {
        if (folder == null) {
            return defaultRules;
        }
        Path override = folder.resolve(".imagejai-tags.yml");
        if (!Files.isRegularFile(override)) {
            return defaultRules;
        }
        try {
            List<Rule> loaded = loadRules(override);
            return loaded.isEmpty() ? defaultRules : loaded;
        } catch (Exception e) {
            return defaultRules;
        }
    }

    private Map<String, String> commonTags(List<String> labels, List<Rule> rules) {
        if (labels == null || labels.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> common = null;
        for (String label : labels) {
            Map<String, String> parsed = parseLabelWithRules(label, rules);
            if (common == null) {
                common = new LinkedHashMap<String, String>(parsed);
                continue;
            }
            List<String> keys = new ArrayList<String>(common.keySet());
            for (String key : keys) {
                String a = common.get(key);
                String b = parsed.get(key);
                if (b == null || !a.equalsIgnoreCase(b)) {
                    common.remove(key);
                }
            }
        }
        return common == null ? Collections.<String, String>emptyMap() : common;
    }

    private Map<String, String> parseLabelWithRules(String label, List<Rule> rules) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        String text = label == null ? "" : label;
        for (Rule rule : rules) {
            Matcher matcher = rule.pattern.matcher(text);
            if (!matcher.find()) {
                continue;
            }
            String value = format(rule, matcher);
            if (!value.trim().isEmpty()) {
                out.put(rule.name, value.trim());
            }
        }
        return out;
    }

    private String format(Rule rule, Matcher matcher) {
        String format = rule.format == null || rule.format.trim().isEmpty()
                ? "{1}"
                : rule.format;
        String out = format;
        for (int i = 1; i <= matcher.groupCount(); i++) {
            out = out.replace("{" + i + "}", safeGroup(matcher, i));
        }
        out = out.replace("{unit}", unitFor(rule, matcher));
        if ("timepoint".equals(rule.name) && "{1}".equals(format)) {
            out = safeGroup(matcher, 1) + " " + unitFor(rule, matcher);
        }
        if ("genotype".equals(rule.name)) {
            out = friendlyGenotype(out);
        } else if ("sex".equals(rule.name)) {
            out = friendlySex(out);
        }
        return out.replaceAll("\\s+", " ").trim();
    }

    private static String safeGroup(Matcher matcher, int group) {
        try {
            String value = matcher.group(group);
            return value == null ? "" : value;
        } catch (Exception e) {
            return "";
        }
    }

    private static String unitFor(Rule rule, Matcher matcher) {
        String match = matcher.group(0).toLowerCase(Locale.ROOT);
        if (match.contains("d")) {
            return "days";
        }
        return "weeks";
    }

    private static String friendlyGenotype(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("wt".equals(v)) return "wild-type";
        if ("ko".equals(v)) return "knockout";
        if ("het".equals(v)) return "heterozygous";
        if ("tg".equals(v)) return "transgenic";
        return v;
    }

    private static String friendlySex(String value) {
        String v = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if ("m".equals(v)) return "male";
        if ("f".equals(v)) return "female";
        return v;
    }

    @SuppressWarnings("unchecked")
    private List<Rule> loadRules(Path yamlPath) throws IOException {
        Object root;
        try (InputStream in = Files.newInputStream(yamlPath)) {
            root = new Yaml().load(in);
        }
        Object listObject = root;
        if (root instanceof Map) {
            listObject = ((Map<?, ?>) root).get("patterns");
        }
        if (!(listObject instanceof Iterable)) {
            return Collections.emptyList();
        }
        List<Rule> rules = new ArrayList<Rule>();
        for (Object item : (Iterable<?>) listObject) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<Object, Object> map = (Map<Object, Object>) item;
            String name = stringValue(map.get("name"));
            String pattern = stringValue(map.get("pattern"));
            String format = stringValue(map.get("format"));
            if (!name.isEmpty() && !pattern.isEmpty()) {
                rules.add(new Rule(name, pattern, format));
            }
        }
        return Collections.unmodifiableList(rules);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : value.toString();
    }

    private static List<Rule> defaultRules() {
        List<Rule> rules = new ArrayList<Rule>();
        rules.add(new Rule("timepoint",
                "(?i)(?<![A-Za-z0-9])(\\d+)\\s*([wd])(?:k|eeks?|ays?)?(?![A-Za-z0-9])",
                "{1} {unit}"));
        rules.add(new Rule("genotype",
                "(?i)(?<![A-Za-z0-9])(wt|ko|het|cre|flox|tg)(?![A-Za-z0-9])",
                "{1}"));
        rules.add(new Rule("sex",
                "(?i)(?<![A-Za-z0-9])(male|female|m|f)(?![A-Za-z0-9])",
                "{1}"));
        rules.add(new Rule("condition",
                "(?i)(?<![A-Za-z0-9])(control|treated|vehicle|drug)(?![A-Za-z0-9])",
                "{1}"));
        return Collections.unmodifiableList(rules);
    }

    public static final class Rule {
        private final String name;
        private final Pattern pattern;
        private final String format;

        Rule(String name, String pattern, String format) {
            this.name = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
            this.pattern = Pattern.compile(pattern);
            this.format = format == null ? "" : format;
        }

        public String name() {
            return name;
        }
    }
}
