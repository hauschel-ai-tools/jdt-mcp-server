#!/usr/bin/env bash
# Process-lifecycle tests for the standalone launcher (issue #80).
#
# Verifies that the JVM never outlives its client:
#   1. stdin EOF            -> orderly exit 0, no workspace recovery
#   2. SIGTERM to wrapper   -> forwarded, JVM gone
#   3. stopped JVM + SIGTERM-> resumed and terminated (kill -CONT before -TERM)
#   4. SIGKILL to wrapper   -> JVM notices parent death and exits on its own
#   5. JVM runs in its own process group (group-wide stop signals miss it)
#
# Usage:  tests/lifecycle-test.sh [path/to/jdtls-mcp-binary]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# shellcheck source=lib/mcp-helpers.sh
source "$SCRIPT_DIR/lib/mcp-helpers.sh"

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

TEST_PROJECT_DIR="$(mktemp -d)"
create_test_project "$TEST_PROJECT_DIR"

# ── Per-scenario server handling ─────────────────────────────────────────────

WRAPPER_PID=""
JVM_PID=""
RUN_DIR=""

start_wrapper() {
    RUN_DIR="$(mktemp -d)"
    mkfifo "$RUN_DIR/stdin.pipe"
    : > "$RUN_DIR/stderr.log"

    # exec so that $! is the wrapper itself, not an intermediate shell
    JDTMCP_WORKSPACE="$RUN_DIR/workspace" \
        bash -c 'cd "$1" && exec "$2"' _ "$TEST_PROJECT_DIR" "$BINARY" \
        < "$RUN_DIR/stdin.pipe" > "$RUN_DIR/stdout.log" 2> "$RUN_DIR/stderr.log" &
    WRAPPER_PID=$!
    exec 3>"$RUN_DIR/stdin.pipe"

    local elapsed=0
    while ! grep -q "MCP server running on stdio" "$RUN_DIR/stderr.log" 2>/dev/null; do
        if ! kill -0 "$WRAPPER_PID" 2>/dev/null; then
            echo "ERROR: wrapper died during startup"; cat "$RUN_DIR/stderr.log"; return 1
        fi
        if [ $elapsed -ge 90 ]; then
            echo "ERROR: server not ready after 90s"; cat "$RUN_DIR/stderr.log"; return 1
        fi
        sleep 1; elapsed=$((elapsed + 1))
    done

    JVM_PID=$(pgrep -P "$WRAPPER_PID" -f "org.eclipse.equinox.launcher" | head -1)
    if [ -z "$JVM_PID" ]; then
        echo "ERROR: JVM child of wrapper $WRAPPER_PID not found"; return 1
    fi
    echo "  wrapper=$WRAPPER_PID jvm=$JVM_PID ready after ${elapsed}s"
}

# Waits until the given PID is gone (zombies count as gone once reaped by their parent).
wait_gone() {
    local pid="$1" timeout="$2" elapsed=0
    while kill -0 "$pid" 2>/dev/null && [ "$(ps -o stat= -p "$pid" 2>/dev/null)" != "Z" ]; do
        if [ $elapsed -ge "$timeout" ]; then return 1; fi
        sleep 1; elapsed=$((elapsed + 1))
    done
    echo "  pid $pid gone after ${elapsed}s"
}

# Reaps the wrapper and returns its exit code (0 if it was already reaped).
wrapper_exit_code() {
    local code=0
    wait "$WRAPPER_PID" 2>/dev/null || code=$?
    echo "$code"
}

close_stdin() {
    # exec on an already-closed fd would terminate a non-interactive shell
    if { true >&3; } 2>/dev/null; then
        exec 3>&-
    fi
}

teardown() {
    close_stdin
    for pid in "$JVM_PID" "$WRAPPER_PID"; do
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null; then
            kill -CONT "$pid" 2>/dev/null || true
            kill -KILL "$pid" 2>/dev/null || true
        fi
    done
    [ -n "$WRAPPER_PID" ] && wait "$WRAPPER_PID" 2>/dev/null || true
    [ -n "$RUN_DIR" ] && rm -rf "$RUN_DIR"
    WRAPPER_PID=""; JVM_PID=""; RUN_DIR=""
}

cleanup_all() {
    teardown
    [ -d "$TEST_PROJECT_DIR" ] && rm -rf "$TEST_PROJECT_DIR"
}
trap cleanup_all EXIT

assert_no_recovery() {
    if grep -q "workspace corrupt" "$RUN_DIR/stderr.log"; then
        echo "  FAIL: wrapper ran workspace recovery on a client-initiated shutdown"
        return 1
    fi
    if [ ! -d "$RUN_DIR/workspace" ]; then
        echo "  FAIL: workspace directory was deleted"
        return 1
    fi
}

# ── Scenarios ────────────────────────────────────────────────────────────────

test_stdin_eof() {
    echo "[Test 1] stdin EOF -> orderly exit, no recovery"
    start_wrapper || { fail "stdin EOF (startup)"; return; }
    local ok=true

    close_stdin
    wait_gone "$JVM_PID" 60 || { echo "  JVM still alive after stdin EOF"; ok=false; }
    wait_gone "$WRAPPER_PID" 10 || { echo "  wrapper still alive"; ok=false; }
    local code; code=$(wrapper_exit_code)
    [ "$code" -eq 0 ] || { echo "  wrapper exit code $code, expected 0"; ok=false; }
    assert_no_recovery || ok=false
    if grep -qiE "job control|Done|Terminated" "$RUN_DIR/stderr.log"; then
        echo "  FAIL: job-control noise on stderr"; ok=false
    fi

    if $ok; then pass "stdin EOF"; else fail "stdin EOF"; fi
    teardown
}

test_sigterm_wrapper() {
    echo "[Test 2] SIGTERM to wrapper -> forwarded to JVM"
    start_wrapper || { fail "SIGTERM (startup)"; return; }
    local ok=true

    kill -TERM "$WRAPPER_PID"
    wait_gone "$JVM_PID" 60 || { echo "  JVM survived SIGTERM to wrapper"; ok=false; }
    wait_gone "$WRAPPER_PID" 10 || { echo "  wrapper still alive"; ok=false; }
    wrapper_exit_code >/dev/null
    assert_no_recovery || ok=false

    if $ok; then pass "SIGTERM to wrapper"; else fail "SIGTERM to wrapper"; fi
    teardown
}

test_stopped_jvm_sigterm() {
    echo "[Test 3] stopped JVM + SIGTERM to wrapper -> resumed and terminated"
    start_wrapper || { fail "stopped JVM (startup)"; return; }
    local ok=true

    kill -STOP "$JVM_PID"
    sleep 1
    local state; state=$(ps -o stat= -p "$JVM_PID")
    [[ "$state" == T* ]] || { echo "  JVM not stopped (state $state)"; ok=false; }

    kill -TERM "$WRAPPER_PID"
    wait_gone "$JVM_PID" 60 || { echo "  stopped JVM was not resumed/terminated"; ok=false; }
    wait_gone "$WRAPPER_PID" 10 || { echo "  wrapper still alive"; ok=false; }
    wrapper_exit_code >/dev/null

    if $ok; then pass "stopped JVM + SIGTERM"; else fail "stopped JVM + SIGTERM"; fi
    teardown
}

test_sigkill_wrapper() {
    echo "[Test 4] SIGKILL to wrapper -> JVM detects parent death"
    start_wrapper || { fail "SIGKILL (startup)"; return; }
    local ok=true

    kill -KILL "$WRAPPER_PID"
    wrapper_exit_code >/dev/null
    # stdin stays open (FD 3) — only the parent-death watchdog can end the JVM here
    wait_gone "$JVM_PID" 60 || { echo "  orphaned JVM did not exit"; ok=false; }

    if $ok; then pass "SIGKILL to wrapper"; else fail "SIGKILL to wrapper"; fi
    teardown
}

test_process_group() {
    echo "[Test 5] JVM runs in its own process group"
    start_wrapper || { fail "process group (startup)"; return; }
    local ok=true

    local wrapper_pgid jvm_pgid
    wrapper_pgid=$(ps -o pgid= -p "$WRAPPER_PID" | tr -d ' ')
    jvm_pgid=$(ps -o pgid= -p "$JVM_PID" | tr -d ' ')
    echo "  wrapper pgid=$wrapper_pgid jvm pgid=$jvm_pgid"
    [ "$wrapper_pgid" != "$jvm_pgid" ] || { echo "  JVM shares the wrapper's process group"; ok=false; }
    [ "$jvm_pgid" = "$JVM_PID" ] || { echo "  JVM is not its own group leader"; ok=false; }

    if $ok; then pass "own process group"; else fail "own process group"; fi
    teardown
}

echo ""
echo "════════════════════════════════════════"
echo " Running lifecycle tests"
echo "════════════════════════════════════════"
echo ""

test_stdin_eof
test_sigterm_wrapper
test_stopped_jvm_sigterm
test_sigkill_wrapper
test_process_group

print_summary

if [ "$TESTS_FAILED" -gt 0 ]; then
    exit 1
fi
exit 0
