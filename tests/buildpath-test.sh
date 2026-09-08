#!/usr/bin/env bash
# End-to-End build path tests for the JDT MCP Server (standalone stdio mode).
#
# Covers the defect where jdt_get_compilation_errors reported only the Java problem marker,
# so a build path problem showed up as the useless "The project cannot be built until build
# path errors are resolved" with no hint at the cause                          (issue #115)
#
# Requires: bash, jq, mkfifo
#
# Usage:  tests/buildpath-test.sh [path/to/jdt-mcp-binary]
#         If no binary is given, the script searches the build output.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# shellcheck source=lib/mcp-helpers.sh
source "$SCRIPT_DIR/lib/mcp-helpers.sh"

# Import plus a full build of the fixtures
RPC_TIMEOUT="${RPC_TIMEOUT:-600}"

# The request counter lives in a file: rpc() runs inside $(...) command substitutions, and a
# shell variable incremented in a subshell would reset to the same id for every call.
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

command -v jq >/dev/null 2>&1 || { echo "ERROR: jq not on PATH" >&2; exit 1; }

# ── Prepare fixtures ──────────────────────────────────────────────────────────
# Fixtures are copied (never symlinked) - the tests rewrite their .classpath files.

FIXTURE_WORK_DIR="$(mktemp -d)"
SERVER_CWD="$(mktemp -d)"
cp -r "$SCRIPT_DIR/fixtures/fixture-badclasspath" "$FIXTURE_WORK_DIR/"

BADCP_DIR="$FIXTURE_WORK_DIR/fixture-badclasspath"

echo "Fixtures at: $FIXTURE_WORK_DIR"

cleanup_all() {
    cleanup
    [ -d "$FIXTURE_WORK_DIR" ] && rm -rf "$FIXTURE_WORK_DIR"
    [ -d "$SERVER_CWD" ] && rm -rf "$SERVER_CWD"
    [ -f "$RPC_ID_FILE" ] && rm -f "$RPC_ID_FILE"
    return 0
}
trap cleanup_all EXIT

# ── JSON-RPC with id matching and a long timeout ──────────────────────────────
# The helper in lib/mcp-helpers.sh returns the last stdout line, which breaks as soon as the
# server interleaves progress notifications. Match on the response id instead.

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

    local elapsed=0
    while [ "$elapsed" -lt "$RPC_TIMEOUT" ]; do
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

    echo "ERROR: No response within ${RPC_TIMEOUT}s for method=$method id=$rpc_id" >&2
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

tool_status() {
    tool_text "$1" | jq -r '.status // empty' 2>/dev/null || true
}

# ── Start server and import the fixture ──────────────────────────────────────

start_server "$BINARY" "$SERVER_CWD"
echo "Server PID: $SERVER_PID"
wait_for_ready 120

init_params='{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"buildpath-test","version":"1.0"}}'
rpc "initialize" "$init_params" > /dev/null
send_notification "notifications/initialized"

echo ""
echo "Importing fixture-badclasspath..."
import_badcp=$(call_tool "jdt_import_project" "$(jq -cn --arg p "$BADCP_DIR" '{"path":$p}')")
echo "  fixture-badclasspath: $(tool_status "$import_badcp")"

projects=$(tool_text "$(call_tool "jdt_list_projects" '{}')")
for required in fixture-badclasspath; do
    if ! echo "$projects" | grep -q "$required"; then
        echo "FATAL: project $required not imported - build path tests cannot run"
        echo "$projects" | head -20
        exit 1
    fi
done

echo ""
echo "════════════════════════════════════════"
echo " Running build path end-to-end tests"
echo "════════════════════════════════════════"
echo ""

# ── Test 1: build path problems are reported, with their message (#115) ───────

echo "Test 1: jdt_get_compilation_errors reports the missing required library (#115)"
errors_json=$(tool_text "$(call_tool "jdt_get_compilation_errors" '{"projectName":"fixture-badclasspath"}')")

buildpath_messages=$(echo "$errors_json" \
    | jq -r '[.errors[]?, .warnings[]?] | map(select(.kind == "BUILDPATH")) | .[].message' 2>/dev/null || true)

if echo "$buildpath_messages" | grep -qi "missing required library"; then
    pass "build path problem is reported with its own message"
    echo "    message: $(echo "$buildpath_messages" | head -1)"
else
    fail "build path problem is reported with its own message" \
         "no BUILDPATH entry mentioning 'missing required library'"
    echo "    response: $(echo "$errors_json" | jq -c '{errorCount, buildPathProblemCount, errors: [.errors[]? | {kind, message}]}' 2>/dev/null || echo "$errors_json")"
fi

echo "Test 2: buildPathProblemCount is reported and counted in errorCount (#115)"
bp_count=$(echo "$errors_json" | jq -r '.buildPathProblemCount // -1')
err_count=$(echo "$errors_json" | jq -r '.errorCount // -1')
bp_errors=$(echo "$errors_json" | jq -r '[.errors[]? | select(.kind == "BUILDPATH")] | length')
if [ "$bp_count" -ge 1 ] && [ "$err_count" -ge "$bp_errors" ] && [ "$bp_errors" -ge 1 ]; then
    pass "buildPathProblemCount=$bp_count, errorCount=$err_count includes $bp_errors BUILDPATH error(s)"
else
    fail "buildPathProblemCount is reported and counted in errorCount" \
         "buildPathProblemCount=$bp_count errorCount=$err_count buildpathErrors=$bp_errors"
fi

echo "Test 3: Java problems keep their own kind (#115)"
java_kinds=$(echo "$errors_json" | jq -r '[.errors[]?, .warnings[]?] | map(select(.kind == "JAVA")) | length')
first_kind=$(echo "$errors_json" | jq -r '.errors[0].kind // empty')
if [ "$first_kind" = "BUILDPATH" ] && [ "$java_kinds" -ge 0 ]; then
    pass "build path problems are listed first, Java problems tagged kind=JAVA ($java_kinds)"
else
    fail "build path problems are listed first" "errors[0].kind=$first_kind"
fi

print_summary

if [ "$TESTS_FAILED" -gt 0 ]; then
    exit 1
fi
exit 0
