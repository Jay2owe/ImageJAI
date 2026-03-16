package uk.ac.ucl.imagej.ai.config;

/**
 * Global constants for the ImageJ AI Assistant plugin.
 */
public final class Constants {

    private Constants() {}

    public static final String PLUGIN_NAME = "AI Assistant";
    public static final String VERSION = "0.1.0";

    // Config directory: ~/.imagej-ai/
    public static final String CONFIG_DIR_NAME = ".imagej-ai";

    // Default LLM settings
    public static final String DEFAULT_GEMINI_MODEL = "gemini-2.0-flash";
    public static final String DEFAULT_OLLAMA_URL = "http://localhost:11434";
    public static final String DEFAULT_OLLAMA_MODEL = "llama3";
    public static final String DEFAULT_OPENAI_URL = "https://api.openai.com/v1";
    public static final String DEFAULT_OPENAI_MODEL = "gpt-4o-mini";

    // Gemini API
    public static final String GEMINI_API_BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    // Timeouts
    public static final int HTTP_CONNECT_TIMEOUT_MS = 10000;
    public static final int HTTP_READ_TIMEOUT_MS = 60000;
    public static final int MACRO_TIMEOUT_MS = 30000;

    // Conversation
    public static final int MAX_CONVERSATION_HISTORY = 20;

    // Image capture
    public static final int MAX_THUMBNAIL_SIZE = 1024;

    // Macro retry
    public static final int MAX_MACRO_RETRIES = 3;

    // TCP command server
    public static final int DEFAULT_TCP_PORT = 7746;  // 7745 is AgentConsole
    public static final int TCP_MAX_MESSAGE_SIZE = 1024 * 1024; // 1MB
}
