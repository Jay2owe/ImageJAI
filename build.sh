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

    # Rebuild the graphify knowledge graph so it reflects the freshly deployed
    # source. Code-only AST pass (no LLM, no tokens); doc/image nodes from any
    # prior full run are preserved. Detached in the background so it doesn't
    # slow the deploy. Same mechanism the post-commit hook uses.
    GF_PY=""
    if command -v python >/dev/null 2>&1 && python -c "import graphify" >/dev/null 2>&1; then
        GF_PY=python
    elif command -v python3 >/dev/null 2>&1 && python3 -c "import graphify" >/dev/null 2>&1; then
        GF_PY=python3
    fi
    if [ -n "$GF_PY" ]; then
        mkdir -p .git/graphify-hook-logs
        GF_LOG=".git/graphify-hook-logs/deploy-$(date +%Y%m%d-%H%M%S).log"
        echo "Rebuilding graphify knowledge graph (detached): $GF_LOG"
        PYTHONIOENCODING=utf-8 PYTHONUTF8=1 nohup "$GF_PY" -c "
from graphify.watch import _rebuild_code
from pathlib import Path
import sys
sys.exit(0 if _rebuild_code(Path('.')) else 1)
" > "$GF_LOG" 2>&1 < /dev/null &
        disown 2>/dev/null || true
    else
        echo "graphify not found on PATH - skipping knowledge-graph rebuild."
    fi
else
    echo "Build failed!"
    exit 1
fi
