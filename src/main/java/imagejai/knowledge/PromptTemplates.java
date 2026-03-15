package imagejai.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * System prompts and response parsing for the conversation loop.
 * Handles macro extraction from LLM responses and context formatting.
 */
public final class PromptTemplates {

    private PromptTemplates() {}

    private static final Pattern MACRO_PATTERN =
            Pattern.compile("<macro>(.*?)</macro>", Pattern.DOTALL);

    private static final String SYSTEM_PROMPT =
            "You are an AI assistant for ImageJ/Fiji, a scientific image analysis application.\n"
            + "You help users analyze microscopy images through natural language.\n"
            + "\n"
            + "When the user asks you to perform an image operation, respond with the ImageJ macro code\n"
            + "wrapped in <macro> tags. For example:\n"
            + "<macro>run(\"Gaussian Blur...\", \"sigma=2\");</macro>\n"
            + "\n"
            + "You can include multiple macro blocks in a single response if needed.\n"
            + "\n"
            + "Common ImageJ macro operations:\n"
            + "- Open sample: run(\"Blobs (25K)\");\n"
            + "- Open file: open(\"/path/to/file.tif\");\n"
            + "- Z-project: run(\"Z Project...\", \"projection=[Max Intensity]\");\n"
            + "- Threshold: setAutoThreshold(\"Otsu\"); run(\"Convert to Mask\");\n"
            + "- Measure: run(\"Measure\");\n"
            + "- Analyze particles: run(\"Analyze Particles...\", \"size=50-Infinity show=Outlines summarize\");\n"
            + "- Gaussian blur: run(\"Gaussian Blur...\", \"sigma=2\");\n"
            + "- Duplicate: run(\"Duplicate...\", \"title=copy\");\n"
            + "- Split channels: run(\"Split Channels\");\n"
            + "- Merge channels: run(\"Merge Channels...\", \"c1=C1 c2=C2 create\");\n"
            + "- Set scale: run(\"Set Scale...\", \"distance=1 known=1 unit=um\");\n"
            + "- Save: saveAs(\"Tiff\", \"/path/to/output.tif\");\n"
            + "\n"
            + "IMPORTANT RULES:\n"
            + "1. Always wrap executable macro code in <macro> tags.\n"
            + "2. Explain what you are doing in plain text alongside the macro code.\n"
            + "3. If you need to run multiple steps, use multiple <macro> blocks or combine in one block.\n"
            + "4. If the user asks a question (not an action), just respond conversationally.\n"
            + "5. If an error occurs, analyze it and suggest a fix.\n"
            + "6. When you see the [STATE] context, use it to understand what images are open and their properties.\n"
            + "7. If no image is open and the user wants to process something, ask them to open an image first.\n";

    /**
     * Returns the core system prompt for the ImageJ AI assistant.
     */
    public static String getSystemPrompt() {
        return SYSTEM_PROMPT;
    }

    /**
     * Extract all macro code blocks from an LLM response.
     *
     * @param response the raw LLM response text
     * @return list of macro code strings (may be empty)
     */
    public static List<String> extractMacros(String response) {
        List<String> macros = new ArrayList<String>();
        if (response == null || response.isEmpty()) {
            return macros;
        }
        Matcher matcher = MACRO_PATTERN.matcher(response);
        while (matcher.find()) {
            String code = matcher.group(1).trim();
            if (!code.isEmpty()) {
                macros.add(code);
            }
        }
        return macros;
    }

    /**
     * Strip macro blocks from response to get only conversational text.
     *
     * @param response the raw LLM response text
     * @return the response with macro blocks removed, trimmed
     */
    public static String extractConversation(String response) {
        if (response == null || response.isEmpty()) {
            return "";
        }
        String cleaned = MACRO_PATTERN.matcher(response).replaceAll("");
        // Collapse multiple blank lines into one
        cleaned = cleaned.replaceAll("\n{3,}", "\n\n");
        return cleaned.trim();
    }

    /**
     * Build the [STATE] context block from StateInspector data.
     *
     * @param stateContext the raw state string from StateInspector.buildStateContext()
     * @return formatted context block for injection into the prompt
     */
    public static String buildContextBlock(String stateContext) {
        if (stateContext == null || stateContext.isEmpty()) {
            return "[STATE]\nNo state information available.\n[/STATE]";
        }
        return "[STATE]\n" + stateContext + "[/STATE]";
    }
}
