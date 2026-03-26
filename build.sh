#!/bin/bash
# Build and deploy ImageJAI to Fiji
set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
FIJI_DIR="$(dirname "$PROJECT_DIR")/../../Fiji.app"
echo "Building ImageJAI..."
cd "$PROJECT_DIR"
mvn clean package -q 2>/dev/null

# Find the built JAR (version comes from pom.xml)
JAR_FILE=$(ls target/imagej-ai-*.jar 2>/dev/null | head -1)

if [ -n "$JAR_FILE" ]; then
    JAR_NAME=$(basename "$JAR_FILE")
    echo "Build successful: $JAR_FILE"

    if [ -d "$FIJI_DIR/plugins" ]; then
        # Remove old version
        rm -f "$FIJI_DIR/plugins"/imagej-ai-*.jar
        cp "$JAR_FILE" "$FIJI_DIR/plugins/"
        echo "Deployed to Fiji: $FIJI_DIR/plugins/$JAR_NAME"
    else
        echo "Warning: Fiji plugins directory not found at $FIJI_DIR/plugins"
        echo "Copy $JAR_FILE to your Fiji plugins/ directory manually."
    fi
else
    echo "Build failed!"
    exit 1
fi
