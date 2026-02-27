#!/usr/bin/env bash
# MCP Test Helper Functions
# Provides utilities for smoke-testing the JDT MCP Server via stdio.

set -euo pipefail

# ── State ──────────────────────────────────────────────────────────────────────
TESTS_PASSED=0
TESTS_FAILED=0
TESTS_SKIPPED=0
REQUEST_ID=0
SERVER_PID=""
STDIN_PIPE=""
STDOUT_FILE=""
STDERR_FILE=""
WORK_DIR=""

# ── Test Project ───────────────────────────────────────────────────────────────

create_test_project() {
    local dir="$1"
    mkdir -p "$dir/src"

    cat > "$dir/.project" <<'XMLEOF'
<?xml version="1.0" encoding="UTF-8"?>
<projectDescription>
    <name>smoke-test-project</name>
    <comment></comment>
    <projects></projects>
    <buildSpec>
        <buildCommand>
            <name>org.eclipse.jdt.core.javabuilder</name>
            <arguments></arguments>
        </buildCommand>
    </buildSpec>
    <natures>
        <nature>org.eclipse.jdt.core.javanature</nature>
    </natures>
</projectDescription>
XMLEOF

    cat > "$dir/src/Hello.java" <<'JAVAEOF'
public class Hello {
    public static void main(String[] args) {
        System.out.println("Hello from smoke test");
    }
}
JAVAEOF
}

# ── Server Lifecycle ───────────────────────────────────────────────────────────

start_server() {
    local binary="$1"
    local project_dir="$2"

    WORK_DIR="$(mktemp -d)"
    STDIN_PIPE="$WORK_DIR/stdin.pipe"
    STDOUT_FILE="$WORK_DIR/stdout.log"
    STDERR_FILE="$WORK_DIR/stderr.log"

    mkfifo "$STDIN_PIPE"

    # Start server with project dir as working directory
    (cd "$project_dir" && "$binary" < "$STDIN_PIPE" > "$STDOUT_FILE" 2> "$STDERR_FILE") &
    SERVER_PID=$!

    # Open write end of the pipe on FD 3 – keeps the pipe open for multiple writes
    exec 3>"$STDIN_PIPE"
}

wait_for_ready() {
    local timeout="${1:-90}"
    local elapsed=0

    echo "Waiting for server to be ready (timeout: ${timeout}s)..."
    while [ $elapsed -lt "$timeout" ]; do
        if grep -q "MCP server running on stdio" "$STDERR_FILE" 2>/dev/null; then
            echo "Server ready after ${elapsed}s"
            return 0
        fi
        # Check if server died
        if ! kill -0 "$SERVER_PID" 2>/dev/null; then
            echo "ERROR: Server process died during startup"
            echo "--- stderr ---"
            cat "$STDERR_FILE" 2>/dev/null || true
            return 1
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done

    echo "ERROR: Server not ready after ${timeout}s"
    echo "--- stderr ---"
    cat "$STDERR_FILE" 2>/dev/null || true
    return 1
}

# ── JSON-RPC Communication ─────────────────────────────────────────────────────

next_id() {
    REQUEST_ID=$((REQUEST_ID + 1))
    echo "$REQUEST_ID"
}

send_and_receive() {
    local method="$1"
    local params="${2:-null}"
    local id
    id=$(next_id)

    local request
    request=$(jq -cn \
        --arg method "$method" \
        --argjson params "$params" \
        --argjson id "$id" \
        '{"jsonrpc":"2.0","method":$method,"params":$params,"id":$id}')

    # Count lines before sending
    local lines_before
    lines_before=$(wc -l < "$STDOUT_FILE" 2>/dev/null || echo 0)

    # Send request via FD 3
    echo "$request" >&3

    # Wait for a new line in stdout (response)
    local timeout=30
    local elapsed=0
    while [ $elapsed -lt $timeout ]; do
        local lines_now
        lines_now=$(wc -l < "$STDOUT_FILE" 2>/dev/null || echo 0)
        if [ "$lines_now" -gt "$lines_before" ]; then
            # Return the last line (most recent response)
            tail -1 "$STDOUT_FILE"
            return 0
        fi
        sleep 0.5
        elapsed=$((elapsed + 1))
    done

    echo "ERROR: No response within ${timeout}s for method=$method id=$id" >&2
    return 1
}

send_raw_and_receive() {
    local raw_json="$1"

    # Count lines before sending
    local lines_before
    lines_before=$(wc -l < "$STDOUT_FILE" 2>/dev/null || echo 0)

    # Send raw request via FD 3
    echo "$raw_json" >&3

    # Wait for a new line in stdout (response)
    local timeout=30
    local elapsed=0
    while [ $elapsed -lt $timeout ]; do
        local lines_now
        lines_now=$(wc -l < "$STDOUT_FILE" 2>/dev/null || echo 0)
        if [ "$lines_now" -gt "$lines_before" ]; then
            tail -1 "$STDOUT_FILE"
            return 0
        fi
        sleep 0.5
        elapsed=$((elapsed + 1))
    done

    echo "ERROR: No response within ${timeout}s for raw request" >&2
    return 1
}

send_notification() {
    local method="$1"
    local params="${2:-null}"

    local notification
    notification=$(jq -cn \
        --arg method "$method" \
        --argjson params "$params" \
        '{"jsonrpc":"2.0","method":$method,"params":$params}')

    echo "$notification" >&3
}

count_stdout_lines() {
    wc -l < "$STDOUT_FILE" 2>/dev/null || echo 0
}

# ── Assertions ─────────────────────────────────────────────────────────────────

assert_json_field() {
    local json="$1"
    local jq_path="$2"
    local expected="$3"
    local description="${4:-$jq_path == $expected}"

    local actual
    actual=$(echo "$json" | jq -r "$jq_path" 2>/dev/null)

    if [ "$actual" = "$expected" ]; then
        return 0
    else
        echo "  ASSERTION FAILED: $description"
        echo "    expected: $expected"
        echo "    actual:   $actual"
        return 1
    fi
}

assert_json_exists() {
    local json="$1"
    local jq_path="$2"
    local description="${3:-$jq_path exists}"

    local result
    if result=$(echo "$json" | jq -e "$jq_path" 2>/dev/null) && [ "$result" != "null" ]; then
        return 0
    else
        echo "  ASSERTION FAILED: $description"
        echo "    path $jq_path is null or missing"
        return 1
    fi
}

assert_json_contains() {
    local json="$1"
    local jq_path="$2"
    local substring="$3"
    local description="${4:-$jq_path contains '$substring'}"

    local actual
    actual=$(echo "$json" | jq -r "$jq_path" 2>/dev/null)

    if echo "$actual" | grep -q "$substring"; then
        return 0
    else
        echo "  ASSERTION FAILED: $description"
        echo "    expected to contain: $substring"
        echo "    actual: $actual"
        return 1
    fi
}

# ── Test Result Tracking ───────────────────────────────────────────────────────

pass() {
    local name="$1"
    TESTS_PASSED=$((TESTS_PASSED + 1))
    echo "  PASS: $name"
}

fail() {
    local name="$1"
    local detail="${2:-}"
    TESTS_FAILED=$((TESTS_FAILED + 1))
    echo "  FAIL: $name"
    [ -n "$detail" ] && echo "    $detail"
}

skip() {
    local name="$1"
    local reason="${2:-}"
    TESTS_SKIPPED=$((TESTS_SKIPPED + 1))
    echo "  SKIP: $name${reason:+ ($reason)}"
}

print_summary() {
    echo ""
    echo "════════════════════════════════════════"
    echo " Results: $TESTS_PASSED passed, $TESTS_FAILED failed, $TESTS_SKIPPED skipped"
    echo "════════════════════════════════════════"
    echo ""
}

# ── Cleanup ────────────────────────────────────────────────────────────────────

cleanup() {
    echo "Cleaning up..."

    # Close the write end of the pipe (FD 3) – signals EOF to the server
    exec 3>&- 2>/dev/null || true

    # Kill the server
    if [ -n "$SERVER_PID" ]; then
        kill "$SERVER_PID" 2>/dev/null || true
        wait "$SERVER_PID" 2>/dev/null || true
    fi

    # Remove temp dir
    if [ -n "$WORK_DIR" ] && [ -d "$WORK_DIR" ]; then
        rm -rf "$WORK_DIR"
    fi
}
