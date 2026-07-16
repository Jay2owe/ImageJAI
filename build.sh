#!/usr/bin/env bash
# Build ImageJAI and, unless --no-deploy is supplied, install it in local Fiji.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEFAULT_FIJI_DIR="$(cd "$PROJECT_DIR/../.." && pwd)/Fiji.app"
FIJI_DIR="${IMAGEJAI_FIJI_DIR:-$DEFAULT_FIJI_DIR}"
SKIP_TESTS=false
DEPLOY=true

usage() {
    cat <<'EOF'
Usage: ./build.sh [--skip-tests] [--no-deploy] [--fiji-dir PATH]

  --skip-tests     Compile tests but do not run them. Tests run by default.
  --no-deploy      Build and verify the JAR without changing a Fiji install.
  --fiji-dir PATH  Override the default Fiji.app directory.
EOF
}

sha256_file() {
    local file="$1"
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$file" | awk '{print tolower($1)}'
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$file" | awk '{print tolower($1)}'
    elif command -v openssl >/dev/null 2>&1; then
        openssl dgst -sha256 "$file" | awk '{print tolower($NF)}'
    else
        echo "No SHA-256 implementation found (sha256sum, shasum, or openssl)." >&2
        return 1
    fi
}

# Copy and hash the complete JAR before replacing anything. The temporary file
# is created in plugins/ so the final rename stays on one filesystem. If copy or
# verification fails, the installed JAR is untouched.
install_verified_artifact() {
    local source="$1"
    local plugins_dir="$2"
    local artifact_name="$3"
    local expected_hash="$4"
    local staged
    local staged_hash

    staged="$(mktemp "$plugins_dir/.${artifact_name}.install.XXXXXX")"
    if ! cp "$source" "$staged"; then
        rm -f "$staged"
        return 1
    fi
    if ! staged_hash="$(sha256_file "$staged")"; then
        rm -f "$staged"
        return 1
    fi
    if [[ "$staged_hash" != "$expected_hash" ]]; then
        echo "Staged JAR hash mismatch; existing Fiji plugin was preserved." >&2
        rm -f "$staged"
        return 1
    fi

    if ! mv -f "$staged" "$plugins_dir/$artifact_name"; then
        rm -f "$staged"
        return 1
    fi

    # A verified main artifact is now installed. Only then remove stale
    # ImageJAI JARs; unrelated Fiji plugins are never matched.
    find "$plugins_dir" -maxdepth 1 -type f -name 'imagej-ai-*.jar' \
        ! -name "$artifact_name" -delete
}

main() {
    while (($#)); do
        case "$1" in
            --skip-tests)
                SKIP_TESTS=true
                ;;
            --no-deploy)
                DEPLOY=false
                ;;
            --fiji-dir)
                shift
                if (($# == 0)); then
                    echo "--fiji-dir requires a path." >&2
                    return 2
                fi
                FIJI_DIR="$1"
                ;;
            -h|--help)
                usage
                return 0
                ;;
            *)
                echo "Unknown option: $1" >&2
                usage >&2
                return 2
                ;;
        esac
        shift
    done

    cd "$PROJECT_DIR"
    local -a maven_args=(clean package -Denforcer.skip=true)
    if [[ "$SKIP_TESTS" == true ]]; then
        maven_args+=(-DskipTests)
    fi

    echo "Building ImageJAI (tests $([[ "$SKIP_TESTS" == true ]] && echo skipped || echo enabled))..."
    mvn "${maven_args[@]}"

    local version
    local jar_file
    local jar_name
    local source_hash
    version="$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout)"
    if [[ -z "$version" || "$version" == *$'\n'* ]]; then
        echo "Could not resolve one Maven project version." >&2
        return 1
    fi

    jar_name="imagej-ai-${version}.jar"
    jar_file="target/$jar_name"
    if [[ ! -f "$jar_file" ]]; then
        echo "Fresh main artifact not found at the exact expected path: $jar_file" >&2
        return 1
    fi
    source_hash="$(sha256_file "$jar_file")"
    echo "Build verified: $jar_file"
    echo "SHA-256: $source_hash"

    if [[ "$DEPLOY" == true ]]; then
        local plugins_dir="$FIJI_DIR/plugins"
        if [[ ! -d "$plugins_dir" ]]; then
            echo "Fiji plugins directory not found at $plugins_dir" >&2
            echo "Build is valid; no files were installed." >&2
            return 1
        fi
        install_verified_artifact "$jar_file" "$plugins_dir" "$jar_name" "$source_hash"
        echo "Installed verified JAR: $plugins_dir/$jar_name"
    else
        echo "Deployment disabled (--no-deploy)."
    fi

    # Schedule exactly one detached graph update after the successful build and
    # optional verified install. The hook runner owns locking and debouncing.
    if command -v python >/dev/null 2>&1; then
        python scripts/graphify_hook.py --event build-deploy --all
    elif command -v python3 >/dev/null 2>&1; then
        python3 scripts/graphify_hook.py --event build-deploy --all
    else
        echo "Python not found - graphify update was not scheduled."
    fi
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
    main "$@"
fi
