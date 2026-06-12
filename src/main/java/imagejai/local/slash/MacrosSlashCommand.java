package imagejai.local.slash;

import ij.IJ;
import imagejai.local.AssistantReply;
import imagejai.local.SlashCommand;
import imagejai.local.SlashCommandContext;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MacrosSlashCommand implements SlashCommand {
    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    public String name() { return "macros"; }
    public String intentId() { return "slash.macros"; }
    public String description() { return "List saved Fiji macros"; }

    public AssistantReply execute(SlashCommandContext context) {
        try {
            List<MacroRow> rows = listMacros(defaultMacroDirs());
            if (rows.isEmpty()) {
                return AssistantReply.text("No .ijm macros found.");
            }
            StringBuilder sb = new StringBuilder("Macros:\nFilename | Description | Last modified");
            for (MacroRow row : rows) {
                sb.append("\n").append(row.filename).append(" | ")
                        .append(row.description.length() == 0 ? "-" : row.description)
                        .append(" | ").append(row.lastModified);
            }
            return AssistantReply.text(sb.toString());
        } catch (IOException e) {
            return AssistantReply.text("Could not list macros: " + e.getMessage());
        }
    }

    static List<Path> defaultMacroDirs() throws IOException {
        List<Path> dirs = new ArrayList<Path>();
        String fijiMacros = IJ.getDirectory("macros");
        if (fijiMacros != null && fijiMacros.trim().length() > 0) {
            dirs.add(Paths.get(fijiMacros));
        }
        String home = System.getProperty("user.home");
        if (home == null || home.trim().length() == 0) {
            home = ".";
        }
        Path learned = Paths.get(home, ".imagej-ai", "learned_macros");
        Files.createDirectories(learned);
        dirs.add(learned);
        return dirs;
    }

    static List<MacroRow> listMacros(List<Path> dirs) throws IOException {
        List<MacroRow> rows = new ArrayList<MacroRow>();
        for (Path dir : dirs) {
            if (dir == null || !Files.isDirectory(dir)) {
                continue;
            }
            DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.ijm");
            try {
                for (Path path : stream) {
                    rows.add(new MacroRow(path.getFileName().toString(),
                            readDescription(path),
                            DATE_FMT.format(Instant.ofEpochMilli(Files.getLastModifiedTime(path).toMillis()))));
                }
            } finally {
                stream.close();
            }
        }
        Collections.sort(rows, new Comparator<MacroRow>() {
            public int compare(MacroRow a, MacroRow b) {
                return a.filename.compareToIgnoreCase(b.filename);
            }
        });
        return rows;
    }

    private static String readDescription(Path path) {
        try {
            BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
            try {
                String first = reader.readLine();
                if (first != null && first.trim().startsWith("//")) {
                    return first.trim().substring(2).trim();
                }
            } finally {
                reader.close();
            }
        } catch (IOException ignored) {
            // Missing or unreadable descriptions are non-fatal.
        }
        return "";
    }

    static class MacroRow {
        final String filename;
        final String description;
        final String lastModified;

        MacroRow(String filename, String description, String lastModified) {
            this.filename = filename;
            this.description = description;
            this.lastModified = lastModified;
        }
    }
}
