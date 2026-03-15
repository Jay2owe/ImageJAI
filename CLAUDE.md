# ImageJAI — Claude Context

## What This Is
A Fiji/ImageJ plugin that adds an AI assistant. Single JAR install, free to use (Google Gemini free tier or local Ollama). Natural language → ImageJ macro execution → results.

## Location
`C:\Users\jamie\UK Dementia Research Institute Dropbox\Brancaccio Lab\Jamie\Experiments\ImageJAI`

## Fiji Location
`C:\Users\jamie\UK Dementia Research Institute Dropbox\Brancaccio Lab\Jamie\Fiji.app`

## Tech Stack
- Java 8 (Fiji's Zulu JDK 8) — ALL code must be Java 8 compatible
- Maven with pom-scijava parent (v37.0.0)
- Swing for GUI (inside ImageJ's JVM)
- Gson 2.10.1 (provided by Fiji)
- java.net.HttpURLConnection for LLM API calls (no external HTTP libs)
- System Java: JDK 25 (for compilation, but source/target = 1.8)

## Build & Deploy
```bash
# Build
mvn clean package -q

# Deploy to Fiji (removes old version first)
bash build.sh
```

## Project Structure
```
src/main/java/uk/ac/ucl/imagej/ai/
  ImageJAIPlugin.java      # Entry point (@Plugin, menu: Plugins > AI Assistant)
  config/
    Constants.java          # Global constants, API URLs, defaults
    Settings.java           # Persisted config (~/.imagej-ai/config.json)
  ui/
    ChatPanel.java          # Main chat Swing panel
    SettingsDialog.java     # First-run settings dialog
  llm/                      # LLM backend abstraction (Phase 1B)
  engine/                   # Macro execution, state inspection (Phase 1C)
  knowledge/                # RAG, macro reference (Phase 3C)
  agents/                   # Multi-agent system (Phase 5)
```

## Critical Constraints
- **Java 8**: No var, no records, no HttpClient, no text blocks, no switch expressions
- **Zero external deps**: Only use what Fiji already ships (Gson, ImageJ API, Swing)
- **EDT safety**: All Swing operations on EDT. All LLM calls on background threads.
- **Plugin entry**: `@Plugin(type = Command.class)` for SciJava discovery

## Implementation Plan
See PLAN.md for the full phased implementation plan with parallel tracks.

## User Preferences
- No Co-Author lines on git commits
