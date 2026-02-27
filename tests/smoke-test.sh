#!/usr/bin/env bash
# Smoke / Integration Tests for JDT MCP Server (standalone stdio mode)
# Requires: bash, jq, mkfifo
#
# Usage:  tests/smoke-test.sh [path/to/jdtls-mcp-binary]
#         If no binary is given, the script searches the build output.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# shellcheck source=lib/mcp-helpers.sh
source "$SCRIPT_DIR/lib/mcp-helpers.sh"

# ── Find binary ────────────────────────────────────────────────────────────────

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

# ── Prepare test project ──────────────────────────────────────────────────────

TEST_PROJECT_DIR="$(mktemp -d)"
create_test_project "$TEST_PROJECT_DIR"
echo "Test project at: $TEST_PROJECT_DIR"

# ── Cleanup on exit ───────────────────────────────────────────────────────────

cleanup_all() {
    cleanup
    [ -d "$TEST_PROJECT_DIR" ] && rm -rf "$TEST_PROJECT_DIR"
}
trap cleanup_all EXIT

# ── Start server ───────────────────────────────────────────────────────────────

start_server "$BINARY" "$TEST_PROJECT_DIR"
echo "Server PID: $SERVER_PID"
wait_for_ready 90

echo ""
echo "════════════════════════════════════════"
echo " Running smoke tests"
echo "════════════════════════════════════════"
echo ""

# ── Test 1: initialize ─────────────────────────────────────────────────────────

test_initialize() {
    echo "[Test 1] initialize"
    local params='{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"smoke-test","version":"1.0"}}'
    local response
    response=$(send_and_receive "initialize" "$params") || { fail "initialize (no response)"; return; }

    local ok=true
    assert_json_field "$response" ".result.protocolVersion" "2024-11-05" "protocolVersion" || ok=false
    assert_json_exists "$response" ".result.serverInfo" "serverInfo present" || ok=false
    assert_json_exists "$response" ".result.capabilities" "capabilities present" || ok=false

    if $ok; then pass "initialize"; else fail "initialize"; fi
}

# ── Test 2: initialized notification ───────────────────────────────────────────

test_initialized_notification() {
    echo "[Test 2] initialized notification"
    local lines_before
    lines_before=$(count_stdout_lines)

    send_notification "notifications/initialized"

    # Wait 2s – no response expected for notifications
    sleep 2
    local lines_after
    lines_after=$(count_stdout_lines)

    if [ "$lines_after" -eq "$lines_before" ]; then
        pass "initialized notification (no response)"
    else
        fail "initialized notification" "unexpected response received"
    fi
}

# ── Test 3: ping ───────────────────────────────────────────────────────────────

test_ping() {
    echo "[Test 3] ping"
    local response
    response=$(send_and_receive "ping") || { fail "ping (no response)"; return; }

    local ok=true
    assert_json_field "$response" ".result" "{}" "empty result object" || ok=false

    if $ok; then pass "ping"; else fail "ping"; fi
}

# ── Test 4: tools/list ─────────────────────────────────────────────────────────

test_tools_list() {
    echo "[Test 4] tools/list"
    local response
    response=$(send_and_receive "tools/list") || { fail "tools/list (no response)"; return; }

    local ok=true
    local tool_count
    tool_count=$(echo "$response" | jq '.result.tools | length' 2>/dev/null || echo 0)

    if [ "$tool_count" -lt 40 ]; then
        echo "  ASSERTION FAILED: expected >= 40 tools, got $tool_count"
        ok=false
    fi

    # Check some known tools exist
    local known_tools=("jdt_list_projects" "jdt_find_type" "jdt_parse_java_file" "jdt_maven_build")
    for tool_name in "${known_tools[@]}"; do
        local found
        found=$(echo "$response" | jq --arg name "$tool_name" '[.result.tools[].name] | index($name)' 2>/dev/null)
        if [ "$found" = "null" ]; then
            echo "  ASSERTION FAILED: tool '$tool_name' not found in tools list"
            ok=false
        fi
    done

    if $ok; then pass "tools/list ($tool_count tools)"; else fail "tools/list"; fi
}

# ── Test 5: unknown method ─────────────────────────────────────────────────────

test_unknown_method() {
    echo "[Test 5] unknown method"
    local response
    response=$(send_and_receive "nonexistent/method") || { fail "unknown method (no response)"; return; }

    local ok=true
    assert_json_field "$response" ".error.code" "-32601" "error code -32601" || ok=false
    assert_json_contains "$response" ".error.message" "Method not found" "error message" || ok=false

    if $ok; then pass "unknown method"; else fail "unknown method"; fi
}

# ── Test 6: invalid jsonrpc version ────────────────────────────────────────────

test_invalid_jsonrpc() {
    echo "[Test 6] invalid jsonrpc version"
    local raw='{"jsonrpc":"1.0","method":"ping","id":999}'
    local response
    response=$(send_raw_and_receive "$raw") || { fail "invalid jsonrpc (no response)"; return; }

    local ok=true
    assert_json_field "$response" ".error.code" "-32600" "error code -32600" || ok=false

    if $ok; then pass "invalid jsonrpc version"; else fail "invalid jsonrpc version"; fi
}

# ── Test 7: unknown tool ──────────────────────────────────────────────────────

test_unknown_tool() {
    echo "[Test 7] unknown tool"
    local params='{"name":"nonexistent_tool","arguments":{}}'
    local response
    response=$(send_and_receive "tools/call" "$params") || { fail "unknown tool (no response)"; return; }

    local ok=true
    assert_json_field "$response" ".error.code" "-32602" "error code -32602" || ok=false
    assert_json_contains "$response" ".error.message" "Unknown tool" "error message" || ok=false

    if $ok; then pass "unknown tool"; else fail "unknown tool"; fi
}

# ── Test 8: notification ignored ───────────────────────────────────────────────

test_notification_ignored() {
    echo "[Test 8] notifications/cancelled ignored"
    local lines_before
    lines_before=$(count_stdout_lines)

    send_notification "notifications/cancelled" '{"requestId":"123","reason":"test"}'

    sleep 2
    local lines_after
    lines_after=$(count_stdout_lines)

    if [ "$lines_after" -eq "$lines_before" ]; then
        pass "notifications/cancelled ignored (no response)"
    else
        fail "notifications/cancelled ignored" "unexpected response received"
    fi
}

# ── Test 9: list_projects tool call ────────────────────────────────────────────

test_list_projects() {
    echo "[Test 9] tools/call jdt_list_projects (non-fatal)"
    local params='{"name":"jdt_list_projects","arguments":{}}'
    local response
    response=$(send_and_receive "tools/call" "$params") || { skip "jdt_list_projects (no response)"; return; }

    # This test is advisory – project import may not complete in time
    local has_result
    has_result=$(echo "$response" | jq 'has("result")' 2>/dev/null || echo false)

    if [ "$has_result" = "true" ]; then
        local content
        content=$(echo "$response" | jq -r '.result.content[0].text // empty' 2>/dev/null || true)
        if echo "$content" | grep -q "smoke-test-project"; then
            pass "jdt_list_projects (found test project)"
        else
            skip "jdt_list_projects" "test project not found (import may be pending)"
        fi
    else
        skip "jdt_list_projects" "no result (may need more time)"
    fi
}

# ── Run all tests ──────────────────────────────────────────────────────────────

test_initialize
test_initialized_notification
test_ping
test_tools_list
test_unknown_method
test_invalid_jsonrpc
test_unknown_tool
test_notification_ignored
test_list_projects

# ── Summary ────────────────────────────────────────────────────────────────────

print_summary

if [ "$TESTS_FAILED" -gt 0 ]; then
    echo "Server stderr (last 30 lines):"
    tail -30 "$STDERR_FILE" 2>/dev/null || true
    exit 1
fi

exit 0
