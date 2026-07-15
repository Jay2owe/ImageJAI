#!/bin/bash
# Build and deploy ImageJAI to Fiji
set -e

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
FIJI_DIR="$(cd "$PROJECT_DIR/../.." && pwd)/Fiji.app"
echo "Building ImageJAI..."
cd "$PROJECT_DIR"
mvn clean package -Dmaven.test.skip=true -q 2>/dev/null

# Find the main plugin JAR (exclude sources/tests classifiers)
JAR_FILE=$(find target -maxdepth 1 -type f -name 'imagej-ai-*.jar' \
    ! -name '*-sources.jar' ! -name '*-tests.jar' | head -1)

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

    # Schedule one detached public-API graph update after the successful build
    # and optional deploy. Git/editor/build triggers share one debounce + lock.
    if command -v python >/dev/null 2>&1; then
        python scripts/graphify_hook.py --event build-deploy --all
    elif command -v python3 >/dev/null 2>&1; then
        python3 scripts/graphify_hook.py --event build-deploy --all
    else
        echo "Python not found - graphify update was not scheduled."
    fi
else
    echo "Build failed!"
    exit 1
fi
