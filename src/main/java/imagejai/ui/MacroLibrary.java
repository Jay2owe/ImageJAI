package imagejai.ui;

import ij.IJ;
import imagejai.engine.SessionCodeJournal;
import imagejai.engine.SessionCodeJournal.Entry;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Locates user, session, and ImageJAI-saved macro/script entries for the rail.
 */
final class MacroLibrary {
    static final String IMAGEJAI_DIR = "ImageJAI";
    static final String SAVED_MACRO_DIR = "macros";

    private static final String[] SUPPORTED_EXTENSIONS = new String[] {
            ".ijm", ".groovy", ".py", ".js", ".bsh", ".clj", ".rb"
    };

    enum Source {
        USER("My Macros", "rail:my-macros"),
        SESSION("Session Macros", "rail:session-macros"),
        SAVED("Saved ImageJAI Macros", "rail:saved-macros");

        final String title;
        final String tcpSource;

        Source(String title, String tcpSource) {
            this.title = title;
            this.tcpSource = tcpSource;
        }
    }

    static final class MacroItem {
        final Source source;
        final String name;
        final String detail;
        final String language;
        final Path path;
        final String code;
        final long sessionEntryId;

        private MacroItem(Source source, String name, String detail, String language,
                          Path path, String code, long sessionEntryId) {
            this.source = source;
            this.name = name;
            this.detail = detail == null ? "" : detail;
            this.language = normaliseLanguage(language);
            this.path = path;
            this.code = code;
            this.sessionEntryId = sessionEntryId;
        }

        String loadCode() throws IOException {
            if (code != null) {
                return code;
            }
            if (path == null) {
                return "";
            }
            return Files.readString(path, StandardCharsets.UTF_8);
        }

        String menuText(boolean includeSource) {
            StringBuilder sb = new StringBuilder();
            if (includeSource) {
                sb.append(source.title).append(": ");
            }
            sb.append(name);
            if (detail.length() > 0 && !detail.equals(name)) {
                sb.append(" (").append(detail).append(")");
            }
            return sb.toString();
        }

        String tooltip() {
            if (path != null) {
                return path.toAbsolutePath().normalize().toString();
            }
            return language + " session entry";
        }
    }

    private MacroLibrary() {}

    static List<MacroItem> listUserMacros() {
        Path root = imageJRoot();
        if (root == null) {
            return Collections.emptyList();
        }
        return scanUserMacros(root, savedMacroDirForRoot(root));
    }

    static List<MacroItem> listSessionMacros() {
        List<Entry> entries = SessionCodeJournal.INSTANCE.snapshotCurrentSession();
        List<MacroItem> items = new ArrayList<MacroItem>();
        for (Entry entry : entries) {
            items.add(sessionItem(entry));
        }
        return items;
    }

    static List<MacroItem> listSavedMacros(File workspace) {
        List<Path> dirs = new ArrayList<Path>();
        Path root = imageJRoot();
        dirs.add(root == null ? fallbackSavedMacroDir() : savedMacroDirForRoot(root));

        Path legacy = legacyHomeMacroDir();
        if (legacy != null) {
            dirs.add(legacy);
        }
        if (workspace != null) {
            dirs.add(workspace.toPath().resolve("agent").resolve("macro_sets"));
        }
        return scanSavedMacros(dirs);
    }

    static List<MacroItem> listAllMacros(File workspace) {
        List<MacroItem> all = new ArrayList<MacroItem>();
        all.addAll(listSessionMacros());
        all.addAll(listSavedMacros(workspace));
        all.addAll(listUserMacros());
        return all;
    }

    static List<MacroItem> scanUserMacros(Path imageJRoot, Path savedDir) {
        List<MacroItem> items = new ArrayList<MacroItem>();
        if (imageJRoot == null || !Files.isDirectory(imageJRoot)) {
            return items;
        }
        walkFiles(imageJRoot, savedDir, Source.USER, true, imageJRoot, items);
        sortByDetail(items);
        return items;
    }

    static List<MacroItem> scanSavedMacros(List<Path> dirs) {
        List<MacroItem> items = new ArrayList<MacroItem>();
        Set<String> seen = new LinkedHashSet<String>();
        if (dirs == null) {
            return items;
        }
        for (Path dir : dirs) {
            if (dir == null || !Files.isDirectory(dir)) {
                continue;
            }
            List<MacroItem> batch = new ArrayList<MacroItem>();
            walkFiles(dir, null, Source.SAVED, false, dir, batch);
            for (MacroItem item : batch) {
                String key = item.path == null ? item.name : item.path.toAbsolutePath().normalize().toString();
                if (seen.add(key)) {
                    items.add(item);
                }
            }
        }
        sortByDetail(items);
        return items;
    }

    static MacroItem sessionItem(Entry entry) {
        String language = entry.language == null ? "ijm" : entry.language;
        String detail = normaliseLanguage(language);
        if (entry.runCount > 1) {
            detail += " x" + entry.runCount;
        }
        return new MacroItem(Source.SESSION, entry.name, detail, language,
                null, entry.code, entry.id);
    }

    static Path savedMacroDirectory() {
        Path root = imageJRoot();
        return root == null ? fallbackSavedMacroDir() : savedMacroDirForRoot(root);
    }

    static Path savedMacroPath(MacroItem item, String rawName) throws IOException {
        String fileName = ensureSupportedExtension(sanitizeFileName(rawName),
                item == null ? "ijm" : item.language);
        if (fileName.length() == 0) {
            throw new IOException("Enter a macro name");
        }
        return savedMacroDirectory().resolve(fileName);
    }

    static void writeMacroItem(MacroItem item, Path dest) throws IOException {
        if (item == null) {
            throw new IOException("No macro selected");
        }
        String code = item.loadCode();
        if (code == null || code.trim().isEmpty()) {
            throw new IOException("Selected macro is empty");
        }
        Files.createDirectories(dest.getParent());
        Files.writeString(dest, code, StandardCharsets.UTF_8);
    }

    static String defaultFileName(MacroItem item) {
        String base = item == null ? "macro" : item.name;
        return ensureSupportedExtension(sanitizeFileName(stripSupportedExtension(base)),
                item == null ? "ijm" : item.language);
    }

    static boolean isImageJMacro(String language) {
        String l = normaliseLanguage(language);
        return l.length() == 0 || "ijm".equals(l) || "macro".equals(l)
                || "imagej macro".equals(l);
    }

    static String extensionForLanguage(String language) {
        String l = normaliseLanguage(language);
        if (l.startsWith("groov")) return "groovy";
        if (l.startsWith("jython") || l.startsWith("python")) return "py";
        if (l.startsWith("java") || l.startsWith("js") || l.startsWith("ecma")) return "js";
        if (l.startsWith("bean")) return "bsh";
        if (l.startsWith("cloj")) return "clj";
        if (l.startsWith("ruby")) return "rb";
        return "ijm";
    }

    static String languageForPath(Path path) {
        String name = path == null || path.getFileName() == null
                ? ""
                : path.getFileName().toString().toLowerCase();
        if (name.endsWith(".ijm")) return "ijm";
        if (name.endsWith(".groovy")) return "groovy";
        if (name.endsWith(".py")) return "jython";
        if (name.endsWith(".js")) return "javascript";
        if (name.endsWith(".bsh")) return "beanshell";
        if (name.endsWith(".clj")) return "clojure";
        if (name.endsWith(".rb")) return "ruby";
        return "ijm";
    }

    static String sanitizeFileName(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim()
                .replaceAll("[\\\\/:*?\"<>|]+", "_")
                .replaceAll("\\s+", "_")
                .replaceAll("^\\.+", "")
                .replaceAll("^_+", "")
                .replaceAll("_+$", "");
    }

    private static void walkFiles(Path root, final Path excludedDir, final Source source,
                                  final boolean ijmOnly, final Path displayRoot,
                                  final List<MacroItem> out) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (excludedDir != null && sameOrChild(dir, excludedDir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (attrs != null && attrs.isRegularFile()
                            && isSupported(file, ijmOnly)) {
                        out.add(fileItem(source, file, displayRoot));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // Partial scans are better than failing the popup entirely.
        }
    }

    private static MacroItem fileItem(Source source, Path path, Path displayRoot) {
        String name = path.getFileName().toString();
        String detail = "";
        if (displayRoot != null) {
            try {
                detail = displayRoot.toAbsolutePath().normalize()
                        .relativize(path.toAbsolutePath().normalize())
                        .toString()
                        .replace(File.separatorChar, '/');
            } catch (IllegalArgumentException ignored) {
                detail = path.toAbsolutePath().normalize().toString();
            }
        }
        return new MacroItem(source, name, detail, languageForPath(path), path, null, 0L);
    }

    private static boolean isSupported(Path path, boolean ijmOnly) {
        String name = path.getFileName().toString().toLowerCase();
        if (ijmOnly) {
            return name.endsWith(".ijm");
        }
        for (String ext : SUPPORTED_EXTENSIONS) {
            if (name.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameOrChild(Path path, Path parent) {
        if (path == null || parent == null) {
            return false;
        }
        Path p = path.toAbsolutePath().normalize();
        Path q = parent.toAbsolutePath().normalize();
        return p.equals(q) || p.startsWith(q);
    }

    private static void sortByDetail(List<MacroItem> items) {
        Collections.sort(items, new Comparator<MacroItem>() {
            @Override
            public int compare(MacroItem a, MacroItem b) {
                int byDetail = a.detail.compareToIgnoreCase(b.detail);
                if (byDetail != 0) {
                    return byDetail;
                }
                return a.name.compareToIgnoreCase(b.name);
            }
        });
    }

    private static Path imageJRoot() {
        String imagej = directory("imagej");
        if (imagej != null) {
            return Paths.get(imagej);
        }
        String macros = directory("macros");
        if (macros != null) {
            Path parent = Paths.get(macros).getParent();
            if (parent != null) {
                return parent;
            }
        }
        return null;
    }

    private static String directory(String key) {
        try {
            String dir = IJ.getDirectory(key);
            return dir == null || dir.trim().isEmpty() ? null : dir;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Path savedMacroDirForRoot(Path root) {
        return root.resolve(IMAGEJAI_DIR).resolve(SAVED_MACRO_DIR);
    }

    private static Path fallbackSavedMacroDir() {
        String home = System.getProperty("user.home");
        if (home == null || home.trim().isEmpty()) {
            home = ".";
        }
        return Paths.get(home).resolve(".imagej-ai").resolve(SAVED_MACRO_DIR);
    }

    private static Path legacyHomeMacroDir() {
        String home = System.getProperty("user.home");
        if (home == null || home.trim().isEmpty()) {
            return null;
        }
        return Paths.get(home).resolve(".imagej-ai").resolve("learned_macros");
    }

    private static String ensureSupportedExtension(String name, String language) {
        if (name == null || name.length() == 0) {
            return "";
        }
        String lower = name.toLowerCase();
        for (String ext : SUPPORTED_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return name;
            }
        }
        return name + "." + extensionForLanguage(language);
    }

    private static String stripSupportedExtension(String name) {
        if (name == null) {
            return "";
        }
        String lower = name.toLowerCase();
        for (String ext : SUPPORTED_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return name.substring(0, name.length() - ext.length());
            }
        }
        return name;
    }

    private static String normaliseLanguage(String language) {
        return language == null ? "" : language.trim().toLowerCase();
    }
}
