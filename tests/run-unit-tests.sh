#!/usr/bin/env bash
# Plain-Java unit tests for org.naturzukunft.jdt.mcp (no Eclipse/OSGi, no Tycho reactor).
#
# org.naturzukunft.jdt.mcp is a Tycho `eclipse-plugin` module with no Tycho-surefire test
# fragment set up, and Tycho auto-compiles (and fails on) a conventional `src/test/java` even
# without a matching build.properties entry -- so the unit test sources live under
# `unit-tests/java/`, outside Tycho's reach. Rather than take on a full Tycho-surefire setup
# (a separate eclipse-test-plugin module, a test target platform, ...), classes that have no
# Eclipse/OSGi dependency get unit-tested here instead: compile src/main/java +
# unit-tests/java with plain javac against JUnit 5 jars already cached locally (Eclipse itself
# ships a JUnit 5 test runner, so these are on disk from any prior Tycho build against the
# 2025-12 p2 repository), then run them with a tiny JUnit-Platform-Launcher-based runner
# (UnitTestRunner). See issues #82, #57.
#
# Usage: tests/run-unit-tests.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
MODULE="$PROJECT_ROOT/org.naturzukunft.jdt.mcp"

M2_REPO="${M2_REPO:-$HOME/.m2/repository}"
BUNDLE_DIR="$M2_REPO/p2/osgi/bundle"

find_jar() {
    local name="$1"
    local jar
    jar="$(find "$BUNDLE_DIR/$name" -iname '*.jar' 2>/dev/null | sort -V | tail -1)"
    if [ -z "$jar" ]; then
        echo "ERROR: could not find a cached jar for $name under $BUNDLE_DIR" >&2
        echo "       (run any Tycho build against the 2025-12 p2 repo first, it caches JUnit 5)" >&2
        exit 1
    fi
    echo "$jar"
}

JUNIT_JARS=()
for bundle in junit-jupiter-api junit-jupiter-engine junit-platform-commons junit-platform-engine \
              junit-platform-launcher org.opentest4j org.apiguardian.api; do
    JUNIT_JARS+=("$(find_jar "$bundle")")
done

CP="$(IFS=:; echo "${JUNIT_JARS[*]}")"

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

echo "Compiling unit tests..."
# Production classes under test: only those without an Eclipse/OSGi/Jackson/MCP-SDK import.
MAIN_SOURCES=(
    "$MODULE/src/main/java/org/naturzukunft/jdt/mcp/MavenCompilerCompliance.java"
    "$MODULE/src/main/java/org/naturzukunft/jdt/mcp/MavenClasspathFreshness.java"
    "$MODULE/src/main/java/org/naturzukunft/jdt/mcp/WorkspaceArtifactMatcher.java"
    "$MODULE/src/main/java/org/naturzukunft/jdt/mcp/WorkspaceProjectName.java"
    "$MODULE/src/main/java/org/naturzukunft/jdt/mcp/tools/ArgParser.java"
    "$MODULE/src/main/java/org/naturzukunft/jdt/mcp/server/ToolArgumentValidator.java"
)
mapfile -t TEST_SOURCES < <(find "$MODULE/unit-tests/java" -name '*.java' | sort)

javac -encoding UTF-8 -d "$WORK_DIR" -cp "$CP" "${MAIN_SOURCES[@]}" "${TEST_SOURCES[@]}"

echo "Running unit tests..."
java -cp "$WORK_DIR:$CP" org.naturzukunft.jdt.mcp.UnitTestRunner
