#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_DIR="${SCREENSHOT_OUTPUT_DIR:-.wiki/images}"

if [[ "$OUTPUT_DIR" = /* ]]; then
    echo "SCREENSHOT_OUTPUT_DIR must be relative to the repository root" >&2
    exit 2
fi

cd "$ROOT"

if ! command -v java >/dev/null 2>&1; then
    echo "Java 25 is required to generate screenshots" >&2
    exit 2
fi

if ! java -version 2>&1 | head -n 1 | grep -Eq 'version "25([.-][^"]*)?"'; then
    echo "Java 25 is required to generate screenshots" >&2
    java -version >&2 || true
    exit 2
fi

mkdir -p "$OUTPUT_DIR"

install_args="install chromium"
if [[ "$(uname -s)" == "Linux" && "${PLAYWRIGHT_INSTALL_DEPS:-0}" == "1" ]]; then
    install_args="install --with-deps chromium"
fi

# This is safe to run repeatedly. Playwright reuses its cached browser when the
# exact Chromium build for the pinned Java dependency is already installed.
./mvnw -B -ntp exec:java -e \
    -Dexec.classpathScope=test \
    -Dexec.mainClass=com.microsoft.playwright.CLI \
    -Dexec.args="$install_args"

./mvnw -B -ntp \
    -Ddocumentation.screenshots.output="$OUTPUT_DIR" \
    -Dtest=DocumentationScreenshotsTest \
    test

echo
echo "Generated documentation screenshot catalog:"
find "$OUTPUT_DIR" -maxdepth 1 -type f -name '*.png' -print | LC_ALL=C sort
