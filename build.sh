#!/bin/bash
# Build and deploy ImageJAI to Fiji
set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
FIJI_DIR="$(dirname "$PROJECT_DIR")/../../Fiji.app"
JAR_NAME="imagej-ai-0.1.0-SNAPSHOT.jar"

echo "Building ImageJAI..."
cd "$PROJECT_DIR"
mvn clean package -q 2>/dev/null

if [ -f "target/$JAR_NAME" ]; then
    echo "Build successful: target/$JAR_NAME"

    if [ -d "$FIJI_DIR/plugins" ]; then
        # Remove old version
        rm -f "$FIJI_DIR/plugins"/imagej-ai-*.jar
        cp "target/$JAR_NAME" "$FIJI_DIR/plugins/"
        echo "Deployed to Fiji: $FIJI_DIR/plugins/$JAR_NAME"
    else
        echo "Warning: Fiji plugins directory not found at $FIJI_DIR/plugins"
        echo "Copy target/$JAR_NAME to your Fiji plugins/ directory manually."
    fi
else
    echo "Build failed!"
    exit 1
fi
