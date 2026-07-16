package imagejai.engine;

import imagejai.llm.LLMBackend;
import imagejai.llm.LLMResponse;
import imagejai.llm.Message;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates reusable ImageJ scripts (Groovy/Jython/Macro) with proper
 * SciJava parameter annotations that auto-create GUI dialogs.
 * <p>
 * The preferred output is Groovy with {@code // @Parameter} comment-style
 * annotations, which Fiji auto-discovers from the scripts directory.
 */
public class ScriptGenerator {

    /** Supported scripting languages for generated scripts. */
    public enum ScriptLanguage {
        GROOVY,    // .groovy -- recommended, full Java interop
        JYTHON,    // .py -- Python 2 syntax (Jython)
        MACRO      // .ijm -- ImageJ macro language
    }

    /** A generated script with metadata for installation. */
    public static class GeneratedScript {
        public String name;             // human-readable name
        public String fileName;         // filename with extension
        public String content;          // full script content
        public ScriptLanguage language; // language enum
        public String description;      // what it does
        public String menuPath;         // where it appears in menus
    }

    private static final Pattern CODE_BLOCK_PATTERN =
            Pattern.compile("```(?:groovy|python|jython|ijm|macro)?\\s*\\n(.*?)```", Pattern.DOTALL);

    private static final String INSTALL_SUBDIR = "scripts" + File.separator
            + "Plugins" + File.separator + "AI_Generated";

    private LLMBackend backend;

    public ScriptGenerator(LLMBackend backend) {
        this.backend = backend;
    }

    /**
     * Generate a script from a natural language description.
     * The LLM generates the script with proper annotations.
     *
     * @param description natural language description of the script
     * @param language    target scripting language
     * @param context     conversation context for the LLM
     * @return generated script, or null on failure
     */
    public GeneratedScript generateScript(String description, ScriptLanguage language,
                                           List<Message> context) {
        String systemPrompt = getScriptGenerationPrompt(language);
        List<Message> messages = new ArrayList<Message>();
        if (context != null) {
            messages.addAll(context);
        }
        messages.add(Message.user("Generate a " + languageName(language)
                + " script for: " + description));

        LLMResponse response = backend.chat(messages, systemPrompt);
        if (!response.isSuccess() || response.getContent() == null) {
            return null;
        }

        String scriptContent = extractScriptFromResponse(response.getContent());
        if (scriptContent == null || scriptContent.trim().isEmpty()) {
            // Fall back to the full response content if no code block found
            scriptContent = response.getContent().trim();
        }

        GeneratedScript script = new GeneratedScript();
        script.name = deriveScriptName(description);
        script.fileName = toFileName(script.name, language);
        script.content = scriptContent;
        script.language = language;
        script.description = description;
        script.menuPath = "Plugins>AI_Generated>" + script.name;
        return script;
    }

    /**
     * Generate a Groovy script with {@code // @Parameter} annotations for GUI.
     * This is the preferred method -- creates installable menu items.
     *
     * @param description natural language description
     * @param context     conversation context
     * @return generated Groovy script, or null on failure
     */
    public GeneratedScript generateGroovyPlugin(String description, List<Message> context) {
        return generateScript(description, ScriptLanguage.GROOVY, context);
    }

    /**
     * Convert a pipeline (from PipelineBuilder) into a standalone script.
     *
     * @param pipeline the pipeline to convert
     * @param language target scripting language
     * @return generated script containing all pipeline steps
     */
    public GeneratedScript pipelineToScript(PipelineBuilder.Pipeline pipeline,
                                             ScriptLanguage language) {
        if (pipeline == null || pipeline.steps == null || pipeline.steps.isEmpty()) {
            return null;
        }

        String content;
        if (language == ScriptLanguage.GROOVY) {
            content = pipelineToGroovy(pipeline);
        } else if (language == ScriptLanguage.JYTHON) {
            content = pipelineToJython(pipeline);
        } else {
            content = pipelineToMacro(pipeline);
        }

        GeneratedScript script = new GeneratedScript();
        script.name = sanitizeName(pipeline.name);
        script.fileName = toFileName(script.name, language);
        script.content = content;
        script.language = language;
        script.description = "Pipeline: " + pipeline.name;
        script.menuPath = "Plugins>AI_Generated>" + script.name;
        return script;
    }

    /**
     * Install a generated script into Fiji's scripts directory.
     * Creates the {@code scripts/Plugins/AI_Generated/} directory if needed.
     *
     * @param script  the script to install
     * @param fijiDir path to the Fiji.app directory
     * @return the installed file path, or null on failure
     */
    public String installScript(GeneratedScript script, String fijiDir) {
        if (script == null || fijiDir == null || script.language == null
                || script.content == null) {
            return null;
        }

        try {
            Path root = Paths.get(fijiDir).toAbsolutePath().normalize();
            Path installDir = root.resolve(INSTALL_SUBDIR).normalize();
            if (!installDir.startsWith(root)) return null;
            Files.createDirectories(installDir);
            Path rootReal = root.toRealPath();
            Path installReal = installDir.toRealPath();
            if (!installReal.startsWith(rootReal)) return null;

            // GeneratedScript is mutable and callers can replace fileName
            // after generation. Recompute the basename from the safe name
            // and enum instead of trusting a traversal/CRLF-bearing value.
            String safeName = sanitizeName(script.name);
            String safeFileName = toFileName(safeName, script.language);
            Path target = installReal.resolve(safeFileName).normalize();
            if (!target.getParent().equals(installReal)
                    || Files.isSymbolicLink(target)) return null;

            Files.write(target, script.content.getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            script.name = safeName;
            script.fileName = safeFileName;
            script.menuPath = "Plugins>AI_Generated>" + safeName;
            System.err.println("[ImageJAI] Script installed: " + target);
            return target.toString();
        } catch (IOException e) {
            System.err.println("[ImageJAI] Failed to install script: " + e.getMessage());
            return null;
        } catch (RuntimeException e) {
            System.err.println("[ImageJAI] Invalid script path: " + e.getMessage());
            return null;
        }
    }

    /**
     * Get the system prompt for script generation in the given language.
     *
     * @param language target scripting language
     * @return system prompt string
     */
    public String getScriptGenerationPrompt(ScriptLanguage language) {
        if (language == ScriptLanguage.GROOVY) {
            return GROOVY_SYSTEM_PROMPT;
        } else if (language == ScriptLanguage.JYTHON) {
            return JYTHON_SYSTEM_PROMPT;
        } else {
            return MACRO_SYSTEM_PROMPT;
        }
    }

    /**
     * Update the LLM backend used for script generation.
     *
     * @param backend the new LLM backend
     */
    public void setBackend(LLMBackend backend) {
        this.backend = backend;
    }

    // ---- System prompts ----

    private static final String GROOVY_SYSTEM_PROMPT =
            "You are an expert Fiji/ImageJ script generator. Generate Groovy scripts for Fiji.\n"
            + "\n"
            + "RULES:\n"
            + "1. Use Fiji's comment-style parameter annotations for GUI inputs:\n"
            + "   // @String(label=\"Input Directory\", style=\"directory\") inputDir\n"
            + "   // @String(label=\"Output Directory\", style=\"directory\") outputDir\n"
            + "   // @Double(label=\"Gaussian Sigma\", value=2.0) sigma\n"
            + "   // @String(label=\"Threshold Method\", choices={\"Otsu\",\"Triangle\",\"Li\"}) method\n"
            + "   // @Boolean(label=\"Show Intermediate\", value=false) showIntermediate\n"
            + "   // @Integer(label=\"Min Size\", value=50) minSize\n"
            + "\n"
            + "2. Import ImageJ classes:\n"
            + "   import ij.IJ\n"
            + "   import ij.ImagePlus\n"
            + "   import ij.io.FileSaver\n"
            + "   import ij.measure.ResultsTable\n"
            + "\n"
            + "3. Use IJ.run() for ImageJ commands, IJ.log() for output.\n"
            + "4. Handle errors with try/catch blocks.\n"
            + "5. Add comments explaining each section.\n"
            + "6. Return the script inside a ```groovy code block.\n"
            + "7. The script must be self-contained and immediately runnable in Fiji.\n";

    private static final String JYTHON_SYSTEM_PROMPT =
            "You are an expert Fiji/ImageJ script generator. Generate Jython (Python 2) scripts for Fiji.\n"
            + "\n"
            + "RULES:\n"
            + "1. Use SciJava #@ parameter annotations for GUI inputs:\n"
            + "   #@ String(label=\"Input Directory\", style=\"directory\") inputDir\n"
            + "   #@ Double(label=\"Gaussian Sigma\", value=2.0) sigma\n"
            + "   #@ String(label=\"Threshold Method\", choices={\"Otsu\",\"Triangle\",\"Li\"}) method\n"
            + "\n"
            + "2. Import ImageJ classes:\n"
            + "   from ij import IJ\n"
            + "   from ij import ImagePlus\n"
            + "   from ij.io import FileSaver\n"
            + "\n"
            + "3. Use IJ.run() for ImageJ commands, IJ.log() for output.\n"
            + "4. Use Python 2 syntax (print statements, no f-strings).\n"
            + "5. Add comments explaining each section.\n"
            + "6. Return the script inside a ```python code block.\n"
            + "7. The script must be self-contained and immediately runnable in Fiji.\n";

    private static final String MACRO_SYSTEM_PROMPT =
            "You are an expert Fiji/ImageJ script generator. Generate ImageJ Macro Language (.ijm) scripts.\n"
            + "\n"
            + "RULES:\n"
            + "1. Use Dialog for user input:\n"
            + "   Dialog.create(\"My Tool\");\n"
            + "   Dialog.addNumber(\"Sigma\", 2.0);\n"
            + "   Dialog.addChoice(\"Method\", newArray(\"Otsu\",\"Triangle\",\"Li\"), \"Otsu\");\n"
            + "   Dialog.show();\n"
            + "   sigma = Dialog.getNumber();\n"
            + "   method = Dialog.getChoice();\n"
            + "\n"
            + "2. Use run() for ImageJ commands.\n"
            + "3. Use setBatchMode(true/false) for batch processing.\n"
            + "4. Use print() or IJ.log() for output.\n"
            + "5. Add comments explaining each section.\n"
            + "6. Return the script inside a ```ijm code block.\n"
            + "7. The script must be self-contained and immediately runnable.\n";

    // ---- Private helpers ----

    /**
     * Extract script content from an LLM response by finding the first code block.
     */
    private String extractScriptFromResponse(String response) {
        if (response == null) {
            return null;
        }
        Matcher m = CODE_BLOCK_PATTERN.matcher(response);
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    /**
     * Derive a human-readable script name from a description.
     */
    private String deriveScriptName(String description) {
        if (description == null || description.trim().isEmpty()) {
            return "Untitled_Script";
        }
        // Take first 40 chars, replace non-alphanumeric with underscore
        String truncated = description.trim();
        if (truncated.length() > 40) {
            truncated = truncated.substring(0, 40);
        }
        return sanitizeName(truncated);
    }

    /**
     * Sanitize a string for use as a filename (letters, digits, underscores).
     */
    private String sanitizeName(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "Untitled";
        }
        String sanitized = name.replace('\r', ' ').replace('\n', ' ')
                .replaceAll("[^a-zA-Z0-9_\\- ]", "").trim();
        sanitized = sanitized.replaceAll("\\s+", "_");
        if (sanitized.isEmpty()) {
            return "Untitled";
        }
        if (sanitized.length() > 80) sanitized = sanitized.substring(0, 80);
        return sanitized;
    }

    /**
     * Convert a script name to a filename with the appropriate extension.
     */
    private String toFileName(String name, ScriptLanguage language) {
        String ext;
        if (language == ScriptLanguage.GROOVY) {
            ext = ".groovy";
        } else if (language == ScriptLanguage.JYTHON) {
            ext = ".py";
        } else {
            ext = ".ijm";
        }
        return name + ext;
    }

    /**
     * Get a human-readable language name.
     */
    private String languageName(ScriptLanguage language) {
        if (language == ScriptLanguage.GROOVY) {
            return "Groovy";
        } else if (language == ScriptLanguage.JYTHON) {
            return "Jython";
        } else {
            return "ImageJ Macro";
        }
    }

    /**
     * Convert a pipeline to a Groovy script.
     */
    private String pipelineToGroovy(PipelineBuilder.Pipeline pipeline) {
        StringBuilder sb = new StringBuilder();
        sb.append("// Pipeline: ").append(pipeline.name).append("\n");
        sb.append("// Generated by ImageJ AI Assistant\n");
        sb.append("// Steps: ").append(pipeline.steps.size()).append("\n\n");
        sb.append("import ij.IJ\n");
        sb.append("import ij.ImagePlus\n\n");

        for (int i = 0; i < pipeline.steps.size(); i++) {
            PipelineBuilder.PipelineStep step = pipeline.steps.get(i);
            sb.append("// Step ").append(step.index).append(": ").append(step.description).append("\n");
            // Wrap macro code in IJ.runMacro()
            String escaped = step.macroCode.trim()
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n");
            sb.append("IJ.runMacro(\"").append(escaped).append("\")\n\n");
        }
        return sb.toString();
    }

    /**
     * Convert a pipeline to a Jython script.
     */
    private String pipelineToJython(PipelineBuilder.Pipeline pipeline) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Pipeline: ").append(pipeline.name).append("\n");
        sb.append("# Generated by ImageJ AI Assistant\n");
        sb.append("# Steps: ").append(pipeline.steps.size()).append("\n\n");
        sb.append("from ij import IJ\n\n");

        for (int i = 0; i < pipeline.steps.size(); i++) {
            PipelineBuilder.PipelineStep step = pipeline.steps.get(i);
            sb.append("# Step ").append(step.index).append(": ").append(step.description).append("\n");
            String escaped = step.macroCode.trim()
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n");
            sb.append("IJ.runMacro(\"").append(escaped).append("\")\n\n");
        }
        return sb.toString();
    }

    /**
     * Convert a pipeline to an ImageJ macro script (concatenates step code directly).
     */
    private String pipelineToMacro(PipelineBuilder.Pipeline pipeline) {
        return pipeline.exportAsMacro();
    }
}
