#!/usr/bin/env bash
# E2E test for issue #114: reopening an already imported Maven multi-module workspace must not
# produce false-positive build path errors.
#
# Two independent causes, one per phase:
#   B) stale .classpath  — a previous import wrote .classpath, the POM changed afterwards.
#      The reopened project must re-resolve its classpath instead of reusing the stale file.
#   C/D) stale resource tree / markers — sources changed on disk while the server was down.
#      The reopened project must be refreshed and rebuilt, so neither new errors are missed (C)
#      nor fixed errors keep being reported (D).
#
# All four phases share one Eclipse workspace (JDTMCP_WORKSPACE) and one server working
# directory, so every restart hits the "Opened existing project" path in ProjectImporter.
# Assertions are made on disk (.classpath content) as well as on the tool answer.
#
# Requires: bash, jq, mkfifo, mvn (the importer shells out to dependency:build-classpath)
#
# Usage:  tests/reopen-test.sh [path/to/jdt-mcp-binary]
#         If no binary is given, the script searches the build output.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# shellcheck source=lib/mcp-helpers.sh
source "$SCRIPT_DIR/lib/mcp-helpers.sh"

MODULE_NAME="fixture-api"
GHOST_JAR="/nonexistent/ghost-lib-9.9.9.jar"

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

if ! command -v mvn >/dev/null 2>&1; then
    echo "ERROR: mvn not on PATH — the importer needs it to resolve module dependencies." >&2
    exit 1
fi

# ── Prepare fixture and workspace ──────────────────────────────────────────────

FIXTURE_WORK_DIR="$(mktemp -d)"
cp -r "$SCRIPT_DIR/fixtures/fixture-parent" "$FIXTURE_WORK_DIR/"
PROJECT_DIR="$FIXTURE_WORK_DIR/fixture-parent"
MODULE_DIR="$PROJECT_DIR/$MODULE_NAME"
CLASSPATH_FILE="$MODULE_DIR/.classpath"
SOURCE_FILE="$MODULE_DIR/src/main/java/org/fixture/api/Tracked.java"
SOURCE_BACKUP="$FIXTURE_WORK_DIR/Tracked.java.orig"
cp "$SOURCE_FILE" "$SOURCE_BACKUP"

# One workspace for all phases — reopening it is what this test is about.
export JDTMCP_WORKSPACE="$FIXTURE_WORK_DIR/workspace"
# The launcher deletes the workspace when a run looks like a crash. This test restarts the
# server on purpose and must keep the workspace across restarts.
export JDTMCP_RECOVERY=1

SERVER_LOG="$HOME/.jdt-mcp/jdt-mcp-$(basename "$PROJECT_DIR").log"
SERVER_WORK_DIRS=()

cleanup_all() {
    stop_server || true
    for dir in "${SERVER_WORK_DIRS[@]:-}"; do
        [ -n "$dir" ] && [ -d "$dir" ] && rm -rf "$dir"
    done
    [ -d "$FIXTURE_WORK_DIR" ] && rm -rf "$FIXTURE_WORK_DIR"
    [ -f "$RPC_ID_FILE" ] && rm -f "$RPC_ID_FILE"
    [ -n "${LAST_ANSWER_FILE:-}" ] && rm -f "$LAST_ANSWER_FILE"
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

    local timeout=300
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

# ── Server restarts sharing one workspace ──────────────────────────────────────

stop_server() {
    [ -z "${SERVER_PID:-}" ] && return 0
    # Close stdin — the client owning our lifetime is the documented shutdown path, and only a
    # clean shutdown persists workspace state (which is what the next phase reopens).
    exec 3>&- 2>/dev/null || true
    local waited=0
    while kill -0 "$SERVER_PID" 2>/dev/null && [ "$waited" -lt 60 ]; do
        sleep 1
        waited=$((waited + 1))
    done
    if kill -0 "$SERVER_PID" 2>/dev/null; then
        echo "  WARNING: server did not exit within ${waited}s, killing"
        kill "$SERVER_PID" 2>/dev/null || true
    fi
    wait "$SERVER_PID" 2>/dev/null || true
    SERVER_PID=""
    return 0
}

start_phase_server() {
    local phase="$1"
    echo ""
    echo "--- starting server ($phase) ---"
    start_server "$BINARY" "$PROJECT_DIR"
    SERVER_WORK_DIRS+=("$WORK_DIR")
    wait_for_ready 120
    local init_params='{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"reopen-test","version":"1.0"}}'
    rpc "initialize" "$init_params" > /dev/null
    send_notification "notifications/initialized"
}

# Answers of the last jdt_get_compilation_errors call, kept in a file: error_count_of() runs in
# a command substitution, so a shell variable set inside it would not survive.
LAST_ANSWER_FILE="$(mktemp)"

error_count_of() {
    local project="$1"
    local res text
    res=$(call_tool "jdt_get_compilation_errors" "$(jq -cn --arg p "$project" '{"projectName":$p}')")
    text=$(tool_text "$res")
    echo "$text" > "$LAST_ANSWER_FILE"
    echo "$text" | jq -r '.errorCount // empty' 2>/dev/null || true
}

dump_diagnostics() {
    echo "  --- tool answer (truncated) ---"
    echo "    $(head -c 800 "$LAST_ANSWER_FILE" 2>/dev/null)"
    echo "  --- server log (import/build lines) ---"
    grep -E "ProjectImporter|HeadlessApplication" "$SERVER_LOG" 2>/dev/null | tail -20 || true
}

# ── Phase A: first import into an empty workspace ──────────────────────────────

phase_a_first_import() {
    echo ""
    echo "=== Phase A: first import (fresh workspace) ==="
    start_phase_server "phase A"

    local ok=true
    local count
    count=$(error_count_of "$MODULE_NAME")
    if [ "$count" != "0" ]; then
        echo "  ASSERTION FAILED: expected 0 errors on first import, got '$count'"
        dump_diagnostics
        ok=false
    fi
    if [ ! -f "$CLASSPATH_FILE" ]; then
        echo "  ASSERTION FAILED: import did not write $CLASSPATH_FILE"
        ok=false
    fi

    stop_server

    if $ok; then
        pass "first import is clean and writes .classpath"
    else
        fail "first import is clean and writes .classpath"
    fi
}

# ── Phase B: stale .classpath (issue #114, cause 1) ────────────────────────────

phase_b_stale_classpath() {
    echo ""
    echo "=== Phase B: reopen with stale .classpath (POM newer than .classpath) ==="

    # Simulate a .classpath from an earlier import that the POM has moved on from, in both
    # directions: the dependencies the module needs today are missing (here: everything the POM
    # resolves, so the JUnit imports in src/test stop compiling), and a library it used to have is
    # still listed although it is gone from the local repository. Backdating the file is what makes
    # the POM look newer, which is the signal the importer keys on.
    grep -v 'kind="lib"' "$CLASSPATH_FILE" > "$CLASSPATH_FILE.tmp"
    mv "$CLASSPATH_FILE.tmp" "$CLASSPATH_FILE"
    sed -i "s|</classpath>|\t<classpathentry kind=\"lib\" path=\"$GHOST_JAR\"/>\n</classpath>|" "$CLASSPATH_FILE"
    touch -d '1 hour ago' "$CLASSPATH_FILE"
    echo "  prepared stale .classpath:"
    sed 's/^/    /' "$CLASSPATH_FILE"

    start_phase_server "phase B"

    local ok=true
    local count
    count=$(error_count_of "$MODULE_NAME")
    if [ "$count" != "0" ]; then
        echo "  ASSERTION FAILED: expected 0 errors after reopen with stale .classpath, got '$count'"
        dump_diagnostics
        ok=false
    fi
    if grep -q "$GHOST_JAR" "$CLASSPATH_FILE"; then
        echo "  ASSERTION FAILED: stale entry $GHOST_JAR still in $CLASSPATH_FILE"
        echo "    $(cat "$CLASSPATH_FILE")"
        ok=false
    fi

    stop_server

    if $ok; then
        pass "reopen re-resolves a stale .classpath (no false-positive build path errors)"
    else
        fail "reopen re-resolves a stale .classpath (no false-positive build path errors)"
    fi
}

# ── Phase C: source broken while the server was down (cause 2, detection) ──────

phase_c_new_error_while_down() {
    echo ""
    echo "=== Phase C: source broken on disk while the server was down ==="

    cat >> "$SOURCE_FILE" <<'JAVAEOF'

class ReopenTestBroken {
    int broken = "not an int";
}
JAVAEOF

    start_phase_server "phase C"

    local ok=true
    local count
    count=$(error_count_of "$MODULE_NAME")
    if [ -z "$count" ] || [ "$count" -lt 1 ]; then
        echo "  ASSERTION FAILED: expected at least 1 error after reopen, got '$count'"
        dump_diagnostics
        ok=false
    fi

    stop_server

    if $ok; then
        pass "reopen sees sources that changed while the server was down"
    else
        fail "reopen sees sources that changed while the server was down"
    fi
}

# ── Phase D: source repaired while the server was down (cause 2, stale markers) ─

phase_d_error_fixed_while_down() {
    echo ""
    echo "=== Phase D: source repaired on disk while the server was down ==="

    cp "$SOURCE_BACKUP" "$SOURCE_FILE"

    start_phase_server "phase D"

    local ok=true
    local count
    count=$(error_count_of "$MODULE_NAME")
    if [ "$count" != "0" ]; then
        echo "  ASSERTION FAILED: expected 0 errors after reopen (markers of the previous run must not survive), got '$count'"
        dump_diagnostics
        ok=false
    fi

    stop_server

    if $ok; then
        pass "reopen drops markers of problems that were fixed while the server was down"
    else
        fail "reopen drops markers of problems that were fixed while the server was down"
    fi
}

phase_a_first_import
phase_b_stale_classpath
phase_c_new_error_while_down
phase_d_error_fixed_while_down

print_summary

if [ "$TESTS_FAILED" -gt 0 ]; then
    echo "Server log (last 40 import/build lines):"
    grep -E "ProjectImporter|HeadlessApplication" "$SERVER_LOG" 2>/dev/null | tail -40 || true
    exit 1
fi

exit 0
