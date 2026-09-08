#!/usr/bin/env bash
# E2E test for issue #82: JDT-MCP must derive a project's compiler compliance from its
# pom.xml (maven.compiler.release), not from the JVM that happens to launch the headless
# server. Imports tests/fixtures/fixture-java25 (release=25, uses a Java-25-only language
# feature) and asserts jdt_get_project_structure reports compliance=25 and
# jdt_get_compilation_errors is clean -- regardless of the server's own launch JDK.
#
# Requires: bash, jq, mkfifo
#
# Usage:  tests/compliance-test.sh [path/to/jdt-mcp-binary]
#         If no binary is given, the script searches the build output.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# shellcheck source=lib/mcp-helpers.sh
source "$SCRIPT_DIR/lib/mcp-helpers.sh"

RPC_ID_FILE="$(mktemp)"
echo 0 > "$RPC_ID_FILE"

next_rpc_id() {
    local next
    next=$(( $(cat "$RPC_ID_FILE") + 1 ))
    echo "$next" > "$RPC_ID_FILE"
    echo "$next"
}

# ── Find binary ────────────────────────────────────────────────────────────────

find_binary() {
    local explicit="${1:-}"
    if [ -n "$explicit" ]; then
        echo "$explicit"
        return
    fi

    local candidate="$PROJECT_ROOT/org.naturzukunft.jdt.mcp.product/target/products/jdt-mcp/linux/gtk/x86_64/bin/jdt-mcp"
    if [ -x "$candidate" ]; then
        echo "$candidate"
        return
    fi

    echo "ERROR: No binary found. Build with 'mvn clean package' first." >&2
    exit 1
}

BINARY=$(find_binary "${1:-}")
echo "Using binary: $BINARY"
if [ -n "${JAVA_HOME:-}" ]; then
    echo "JAVA_HOME=$JAVA_HOME (this is the launch JDK the fix must be independent of)"
fi

# ── Prepare fixture ────────────────────────────────────────────────────────────
# Copied (never symlinked) for consistency with the other E2E tests, even though this test
# does not mutate it.

FIXTURE_WORK_DIR="$(mktemp -d)"
SERVER_CWD="$(mktemp -d)"
cp -r "$SCRIPT_DIR/fixtures/fixture-java25" "$FIXTURE_WORK_DIR/"
PROJECT_DIR="$FIXTURE_WORK_DIR/fixture-java25"

cleanup_all() {
    cleanup
    [ -d "$FIXTURE_WORK_DIR" ] && rm -rf "$FIXTURE_WORK_DIR"
    [ -d "$SERVER_CWD" ] && rm -rf "$SERVER_CWD"
    [ -f "$RPC_ID_FILE" ] && rm -f "$RPC_ID_FILE"
    return 0
}
trap cleanup_all EXIT

# ── JSON-RPC with id matching ──────────────────────────────────────────────────

rpc() {
    local method="$1"
    local params="$2"
    local rpc_id
    rpc_id=$(next_rpc_id)

    local request
    request=$(jq -cn \
        --arg method "$method" \
        --argjson params "$params" \
        --argjson id "$rpc_id" \
        '{"jsonrpc":"2.0","method":$method,"params":$params,"id":$id}')

    echo "$request" >&3

    local timeout=180
    local elapsed=0
    while [ "$elapsed" -lt "$timeout" ]; do
        local response
        response=$(jq -c --argjson id "$rpc_id" 'select(.id == $id)' "$STDOUT_FILE" 2>/dev/null | tail -1 || true)
        if [ -n "$response" ]; then
            echo "$response"
            return 0
        fi
        if ! kill -0 "$SERVER_PID" 2>/dev/null; then
            echo "ERROR: server died while waiting for $method" >&2
            return 1
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done

    echo "ERROR: No response within ${timeout}s for method=$method id=$rpc_id" >&2
    return 1
}

call_tool() {
    local name="$1"
    local arguments="$2"
    local params
    params=$(jq -cn --arg name "$name" --argjson arguments "$arguments" '{"name":$name,"arguments":$arguments}')
    rpc "tools/call" "$params"
}

tool_text() {
    echo "$1" | jq -r '.result.content[0].text // empty'
}

echo "Starting server: $BINARY"
start_server "$BINARY" "$SERVER_CWD"
wait_for_ready 90

init_params='{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"compliance-test","version":"1.0"}}'
rpc "initialize" "$init_params" > /dev/null
send_notification "notifications/initialized"

test_compliance_from_pom() {
    echo ""
    echo "=== Test: compiler compliance derived from pom.xml (maven.compiler.release=25) ==="
    local ok=true

    local import_res
    import_res=$(call_tool "jdt_import_project" "$(jq -cn --arg p "$PROJECT_DIR" '{"path":$p}')")
    if [ "$(echo "$import_res" | jq -r '.result.isError // false')" = "true" ]; then
        echo "  ASSERTION FAILED: import reported isError"
        echo "    $(tool_text "$import_res" | head -c 400)"
        fail "compliance from pom.xml"
        return
    fi

    local struct_res struct_text compliance
    struct_res=$(call_tool "jdt_get_project_structure" '{"projectName":"fixture-java25"}')
    struct_text=$(tool_text "$struct_res")
    compliance=$(echo "$struct_text" | jq -r '.compliance // empty' 2>/dev/null || true)
    if [ "$compliance" != "25" ]; then
        echo "  ASSERTION FAILED: expected compliance=25 (from pom.xml), got '$compliance'"
        echo "    $(echo "$struct_text" | head -c 400)"
        ok=false
    fi

    local err_res err_text error_count
    err_res=$(call_tool "jdt_get_compilation_errors" '{"projectName":"fixture-java25"}')
    err_text=$(tool_text "$err_res")
    error_count=$(echo "$err_text" | jq -r '.errorCount // (.errors | length) // empty' 2>/dev/null || true)
    if [ -z "$error_count" ] || [ "$error_count" != "0" ]; then
        echo "  ASSERTION FAILED: expected 0 compilation errors at release 25, got '$error_count'"
        echo "    $(echo "$err_text" | head -c 800)"
        ok=false
    fi

    if $ok; then
        pass "compliance from pom.xml (compliance=25, 0 compilation errors)"
    else
        fail "compliance from pom.xml"
    fi
}

test_compliance_from_pom

print_summary

if [ "$TESTS_FAILED" -gt 0 ]; then
    echo "Server stderr (last 30 lines):"
    tail -30 "$STDERR_FILE" 2>/dev/null || true
    exit 1
fi

exit 0
