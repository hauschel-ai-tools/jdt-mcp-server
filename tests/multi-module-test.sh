#!/usr/bin/env bash
# Multi-Module Integration Test for JDT MCP Server
# Tests that inter-project dependencies are set up correctly for cross-module
# refactoring (issue #15).
#
# Usage:  tests/multi-module-test.sh [path/to/jdtls-mcp-binary]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# shellcheck source=lib/mcp-helpers.sh
source "$SCRIPT_DIR/lib/mcp-helpers.sh"

# ── Find binary ──────────────────────────────────────────────────────────────

find_binary() {
    local explicit="${1:-}"
    if [ -n "$explicit" ]; then
        echo "$explicit"
        return
    fi

    local candidate="$PROJECT_ROOT/org.naturzukunft.jdt.mcp.product/target/products/jdtls-mcp/linux/gtk/x86_64/bin/jdtls-mcp"
    if [ -x "$candidate" ]; then
        echo "$candidate"
        return
    fi

    echo "ERROR: No binary found. Build with 'mvn clean package' first." >&2
    exit 1
}

BINARY=$(find_binary "${1:-}")
echo "Using binary: $BINARY"

# ── Create multi-module Maven project ────────────────────────────────────────

create_multi_module_project() {
    local dir="$1"

    # Parent pom.xml
    cat > "$dir/pom.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>org.test</groupId>
    <artifactId>test-parent</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>
    <modules>
        <module>test-api</module>
        <module>test-impl</module>
    </modules>
</project>
EOF

    # Module: test-api
    mkdir -p "$dir/test-api/src/main/java/org/test/api"
    cat > "$dir/test-api/pom.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.test</groupId>
        <artifactId>test-parent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
    <artifactId>test-api</artifactId>
</project>
EOF

    cat > "$dir/test-api/src/main/java/org/test/api/Greeter.java" <<'EOF'
package org.test.api;

public interface Greeter {
    String greet(String name);
}
EOF

    # Module: test-impl (depends on test-api)
    mkdir -p "$dir/test-impl/src/main/java/org/test/impl"
    cat > "$dir/test-impl/pom.xml" <<'EOF'
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.test</groupId>
        <artifactId>test-parent</artifactId>
        <version>1.0.0-SNAPSHOT</version>
    </parent>
    <artifactId>test-impl</artifactId>
    <dependencies>
        <dependency>
            <groupId>org.test</groupId>
            <artifactId>test-api</artifactId>
            <version>1.0.0-SNAPSHOT</version>
        </dependency>
    </dependencies>
</project>
EOF

    cat > "$dir/test-impl/src/main/java/org/test/impl/GreeterImpl.java" <<'EOF'
package org.test.impl;

import org.test.api.Greeter;

public class GreeterImpl implements Greeter {
    @Override
    public String greet(String name) {
        return "Hello, " + name + "!";
    }
}
EOF
}

TEST_PROJECT_DIR="$(mktemp -d)"
create_multi_module_project "$TEST_PROJECT_DIR"
echo "Multi-module test project at: $TEST_PROJECT_DIR"

# ── Cleanup on exit ──────────────────────────────────────────────────────────

cleanup_all() {
    cleanup
    [ -d "$TEST_PROJECT_DIR" ] && rm -rf "$TEST_PROJECT_DIR"
}
trap cleanup_all EXIT

# ── Start server ─────────────────────────────────────────────────────────────

start_server "$BINARY" "$TEST_PROJECT_DIR"
echo "Server PID: $SERVER_PID"
wait_for_ready 90

# Initialize
send_and_receive "initialize" '{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"multi-module-test","version":"1.0"}}' > /dev/null
send_notification "notifications/initialized"

# Wait for import to complete (check periodically)
echo "Waiting for project import to complete..."
IMPORT_TIMEOUT=120
IMPORT_ELAPSED=0
while [ $IMPORT_ELAPSED -lt $IMPORT_TIMEOUT ]; do
    RESPONSE=$(send_and_receive "tools/call" '{"name":"jdt_list_projects","arguments":{}}')
    CONTENT=$(echo "$RESPONSE" | jq -r '.result.content[0].text // empty' 2>/dev/null || true)

    # Check if both modules are imported
    if echo "$CONTENT" | grep -q "test-api" && echo "$CONTENT" | grep -q "test-impl"; then
        echo "Both modules imported after ${IMPORT_ELAPSED}s"
        break
    fi

    sleep 3
    IMPORT_ELAPSED=$((IMPORT_ELAPSED + 3))
done

if [ $IMPORT_ELAPSED -ge $IMPORT_TIMEOUT ]; then
    echo "ERROR: Modules not imported within ${IMPORT_TIMEOUT}s"
    echo "Last response: $CONTENT"
    exit 1
fi

# Give JDT time to finish indexing after project import and classpath setup
sleep 5

echo ""
echo "════════════════════════════════════════"
echo " Running multi-module tests"
echo "════════════════════════════════════════"
echo ""

# ── Test 1: test-impl has project dependency on test-api ─────────────────────

test_inter_project_dependency() {
    echo "[Test 1] test-impl has project dependency on test-api"
    local params='{"name":"jdt_get_classpath","arguments":{"projectName":"test-impl"}}'
    local response
    response=$(send_and_receive "tools/call" "$params") || { fail "get classpath (no response)"; return; }

    local content
    content=$(echo "$response" | jq -r '.result.content[0].text // empty' 2>/dev/null || true)

    if [ -z "$content" ]; then
        fail "get classpath" "empty response"
        return
    fi

    local ok=true

    # Check that projectDependencies contains test-api
    local project_deps
    project_deps=$(echo "$content" | jq '.projectDependencies' 2>/dev/null || echo "[]")
    local has_api_dep
    has_api_dep=$(echo "$project_deps" | jq '[.[].path] | any(contains("test-api"))' 2>/dev/null || echo "false")

    if [ "$has_api_dep" != "true" ]; then
        echo "  ASSERTION FAILED: test-impl should have project dependency on test-api"
        echo "    projectDependencies: $project_deps"
        ok=false
    fi

    if $ok; then pass "inter-project dependency (test-impl -> test-api)"; else fail "inter-project dependency"; fi
}

# ── Test 2: test-impl can resolve types from test-api ────────────────────────

test_cross_module_type_resolution() {
    echo "[Test 2] test-impl can resolve Greeter from test-api"
    local params='{"name":"jdt_find_type","arguments":{"pattern":"Greeter"}}'
    local response
    response=$(send_and_receive "tools/call" "$params") || { fail "find type (no response)"; return; }

    local content
    content=$(echo "$response" | jq -r '.result.content[0].text // empty' 2>/dev/null || true)

    local ok=true
    if ! echo "$content" | grep -q "Greeter"; then
        echo "  ASSERTION FAILED: Greeter interface not found"
        echo "    response: $content"
        ok=false
    fi

    if $ok; then pass "cross-module type resolution"; else fail "cross-module type resolution"; fi
}

# ── Test 3: find_references finds cross-module references ────────────────────

test_cross_module_references() {
    echo "[Test 3] find_references finds Greeter usage in test-impl"
    local params='{"name":"jdt_find_references","arguments":{"elementName":"org.test.api.Greeter","elementType":"TYPE"}}'
    local response
    response=$(send_and_receive "tools/call" "$params") || { fail "find references (no response)"; return; }

    local content
    content=$(echo "$response" | jq -r '.result.content[0].text // empty' 2>/dev/null || true)

    local ok=true
    # Should find references in GreeterImpl (implements Greeter, import)
    if ! echo "$content" | grep -q "GreeterImpl"; then
        echo "  ASSERTION FAILED: expected reference to Greeter in GreeterImpl"
        echo "    response: $content"
        ok=false
    fi

    if $ok; then pass "cross-module references"; else fail "cross-module references"; fi
}

# ── Test 4: rename updates references across modules ─────────────────────────

test_cross_module_rename() {
    echo "[Test 4] rename Greeter -> Saluter updates GreeterImpl"
    local params='{"name":"jdt_rename_element","arguments":{"elementName":"org.test.api.Greeter","newName":"Saluter","elementType":"TYPE"}}'
    local response
    response=$(send_and_receive "tools/call" "$params") || { fail "rename (no response)"; return; }

    local content
    content=$(echo "$response" | jq -r '.result.content[0].text // empty' 2>/dev/null || true)

    local ok=true

    # Check status is SUCCESS (not WARNING)
    local status
    status=$(echo "$content" | jq -r '.status // empty' 2>/dev/null || true)
    if [ "$status" = "WARNING" ]; then
        echo "  ASSERTION FAILED: rename returned WARNING (no references updated)"
        echo "    message: $(echo "$content" | jq -r '.message // empty' 2>/dev/null)"
        ok=false
    elif [ "$status" != "SUCCESS" ]; then
        echo "  ASSERTION FAILED: expected SUCCESS, got: $status"
        echo "    response: $content"
        ok=false
    fi

    # Verify the change has children (reference updates)
    local child_count
    child_count=$(echo "$content" | jq '.changes.childCount // 0' 2>/dev/null || echo "0")
    if [ "$child_count" -eq 0 ]; then
        echo "  ASSERTION FAILED: expected childCount > 0 (references should be updated)"
        ok=false
    fi

    if $ok; then pass "cross-module rename (childCount=$child_count)"; else fail "cross-module rename"; fi

    # Verify the actual file content was updated
    if [ -f "$TEST_PROJECT_DIR/test-impl/src/main/java/org/test/impl/GreeterImpl.java" ]; then
        if grep -q "import org.test.api.Saluter" "$TEST_PROJECT_DIR/test-impl/src/main/java/org/test/impl/GreeterImpl.java"; then
            pass "cross-module rename (import updated in GreeterImpl.java)"
        else
            fail "cross-module rename" "import not updated in GreeterImpl.java"
            echo "    actual content:"
            cat "$TEST_PROJECT_DIR/test-impl/src/main/java/org/test/impl/GreeterImpl.java" | sed 's/^/      /'
        fi
    fi
}

# ── Run all tests ────────────────────────────────────────────────────────────

test_inter_project_dependency
test_cross_module_type_resolution
test_cross_module_references
test_cross_module_rename

# ── Summary ──────────────────────────────────────────────────────────────────

print_summary

if [ "$TESTS_FAILED" -gt 0 ]; then
    echo "Server stderr (last 30 lines):"
    tail -30 "$STDERR_FILE" 2>/dev/null || true
    exit 1
fi

exit 0
