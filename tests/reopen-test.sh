#!/usr/bin/env bash
# E2E test for issue #114: reopening an already imported Maven multi-module workspace must not
# produce false-positive build path errors, and must not re-resolve the classpath over and over.
#
# Phases (all on ONE Eclipse workspace and one server working directory, so every restart after
# phase A goes through "Opened existing project"):
#   A  first import into an empty workspace
#   B  stale .classpath: the POM gained dependencies since the last import
#   C  a source file was broken on disk while the server was down
#   D  that source file was repaired while the server was down
#   E  a POM was touched without a content change (`git checkout`): re-resolves once, then settles
#   F  a POM changed for real: re-resolves exactly once
#
# Every phase asserts against the server log as well, otherwise the whole suite could pass on a
# server that silently imported everything from scratch instead of reopening.
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
EXPECTED_COMPLIANCE="compliance 21 from"

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

for tool in jq mvn mkfifo; do
    if ! command -v "$tool" >/dev/null 2>&1; then
        echo "ERROR: $tool not on PATH — this test needs it." >&2
        exit 1
    fi
done

# ── Prepare fixture and workspace ──────────────────────────────────────────────

FIXTURE_WORK_DIR="$(mktemp -d)"
cp -r "$SCRIPT_DIR/fixtures/fixture-parent" "$FIXTURE_WORK_DIR/"
PROJECT_DIR="$FIXTURE_WORK_DIR/fixture-parent"
MODULE_DIR="$PROJECT_DIR/$MODULE_NAME"
CLASSPATH_FILE="$MODULE_DIR/.classpath"
MODULE_POM="$MODULE_DIR/pom.xml"
SOURCE_FILE="$MODULE_DIR/src/main/java/org/fixture/api/Tracked.java"
SOURCE_BACKUP="$FIXTURE_WORK_DIR/Tracked.java.orig"
cp "$SOURCE_FILE" "$SOURCE_BACKUP"

# A checkout may carry a .classpath from an earlier local run of the fixture — start from a clean
# slate so the first phase really is a first import.
find "$PROJECT_DIR" -name '.classpath' -delete

# One workspace for all phases — reopening it is what this test is about.
export JDTMCP_WORKSPACE="$FIXTURE_WORK_DIR/workspace"
# The launcher deletes the workspace when a run looks like a crash. This test restarts the
# server on purpose and must keep the workspace across restarts.
export JDTMCP_RECOVERY=1

SERVER_LOG="$HOME/.jdt-mcp/jdt-mcp-$(basename "$PROJECT_DIR").log"
SERVER_WORK_DIRS=()
LOG_OFFSET=0
PHASE_OK=true

# Answers of the last jdt_get_compilation_errors call, kept in a file: error_count_of() runs in a
# command substitution, so a shell variable set inside it would not survive.
LAST_ANSWER_FILE="$(mktemp)"

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
    # Everything the server logs from here on belongs to this phase.
    LOG_OFFSET=$(wc -l < "$SERVER_LOG" 2>/dev/null || echo 0)
    start_server "$BINARY" "$PROJECT_DIR"
    SERVER_WORK_DIRS+=("$WORK_DIR")
    wait_for_ready 120
    local init_params='{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"reopen-test","version":"1.0"}}'
    rpc "initialize" "$init_params" > /dev/null
    send_notification "notifications/initialized"
    wait_for_import_finished
}

# "ready for stdio" only means the transport is up; import and build run afterwards. Assertions on
# the log would race with them, so wait for the line the import thread logs when it is done.
wait_for_import_finished() {
    local timeout=300
    local elapsed=0
    while [ "$elapsed" -lt "$timeout" ]; do
        if phase_log | grep -qF "Project import and build finished"; then
            echo "Import and build finished after ${elapsed}s"
            return 0
        fi
        if ! kill -0 "$SERVER_PID" 2>/dev/null; then
            echo "ERROR: server died during import" >&2
            return 1
        fi
        sleep 1
        elapsed=$((elapsed + 1))
    done
    echo "ERROR: import did not finish within ${timeout}s" >&2
    return 1
}

# ── Assertions ─────────────────────────────────────────────────────────────────

phase_log() {
    tail -n +$((LOG_OFFSET + 1)) "$SERVER_LOG" 2>/dev/null || true
}

assert_log() {
    local pattern="$1"
    if ! phase_log | grep -qF -- "$pattern"; then
        echo "  ASSERTION FAILED: server log of this phase does not contain: $pattern"
        PHASE_OK=false
    fi
}

refute_log() {
    local pattern="$1"
    if phase_log | grep -qF -- "$pattern"; then
        echo "  ASSERTION FAILED: server log of this phase unexpectedly contains: $pattern"
        echo "    $(phase_log | grep -F -- "$pattern" | head -3)"
        PHASE_OK=false
    fi
}

assert_error_count() {
    local expected="$1"
    local actual
    actual=$(error_count_of "$MODULE_NAME")
    if [ "$actual" != "$expected" ]; then
        echo "  ASSERTION FAILED: expected errorCount $expected for $MODULE_NAME, got '$actual'"
        echo "    $(head -c 700 "$LAST_ANSWER_FILE" 2>/dev/null)"
        PHASE_OK=false
    fi
}

assert_error_count_at_least() {
    local minimum="$1"
    local actual
    actual=$(error_count_of "$MODULE_NAME")
    if [ -z "$actual" ] || [ "$actual" -lt "$minimum" ]; then
        echo "  ASSERTION FAILED: expected at least $minimum errors for $MODULE_NAME, got '$actual'"
        echo "    $(head -c 700 "$LAST_ANSWER_FILE" 2>/dev/null)"
        PHASE_OK=false
    fi
}

error_count_of() {
    local project="$1"
    local res text
    res=$(call_tool "jdt_get_compilation_errors" "$(jq -cn --arg p "$project" '{"projectName":$p}')")
    text=$(tool_text "$res")
    echo "$text" > "$LAST_ANSWER_FILE"
    echo "$text" | jq -r '.errorCount // empty' 2>/dev/null || true
}

finish_phase() {
    local name="$1"
    stop_server
    if $PHASE_OK; then
        pass "$name"
    else
        fail "$name"
        echo "  --- server log of the failed phase ---"
        phase_log | grep -E "ProjectImporter|HeadlessApplication" | tail -20 || true
    fi
    PHASE_OK=true
}

# ── Fixture manipulation ───────────────────────────────────────────────────────

# Replaces the resolved libraries with one entry pointing at a JAR that does not exist: what a
# .classpath from an earlier import looks like once the POM has moved on in both directions.
break_classpath() {
    awk -v ghost="$GHOST_JAR" '
        /kind="lib"/ { next }
        /<\/classpath>/ { printf "    <classpathentry kind=\"lib\" path=\"%s\"/>\n", ghost }
        { print }
    ' "$CLASSPATH_FILE" > "$CLASSPATH_FILE.tmp"
    mv "$CLASSPATH_FILE.tmp" "$CLASSPATH_FILE"
    echo "  prepared stale .classpath:"
    sed 's/^/    /' "$CLASSPATH_FILE"
}

# Changes the POM's content, the way adding a dependency would.
change_pom() {
    local marker="$1"
    awk -v marker="$marker" '
        /<\/project>/ { printf "    <!-- %s -->\n", marker }
        { print }
    ' "$MODULE_POM" > "$MODULE_POM.tmp"
    mv "$MODULE_POM.tmp" "$MODULE_POM"
}

# Moves the POM's modification time without changing a byte, the way a checkout does.
touch_pom() {
    touch "$MODULE_POM"
}

# ── Phase A: first import into an empty workspace ──────────────────────────────

phase_a_first_import() {
    echo ""
    echo "=== Phase A: first import (fresh workspace) ==="
    start_phase_server "phase A"

    assert_error_count 0
    assert_log "Configured Maven project: $MODULE_NAME"
    assert_log "$EXPECTED_COMPLIANCE"
    assert_log "triggering incremental workspace build"
    refute_log "Opened existing project"
    if [ ! -f "$CLASSPATH_FILE" ]; then
        echo "  ASSERTION FAILED: import did not write $CLASSPATH_FILE"
        PHASE_OK=false
    fi

    finish_phase "first import is clean, sets compliance and writes .classpath"
}

# ── Phase B: stale .classpath (issue #114, cause 1) ────────────────────────────

phase_b_stale_classpath() {
    echo ""
    echo "=== Phase B: reopen after the POM gained dependencies ==="
    break_classpath
    change_pom "phase B: dependency added"

    start_phase_server "phase B"

    assert_log "Opened existing project: $MODULE_NAME — re-resolving classpath"
    assert_log "triggering full workspace build"
    assert_error_count 0
    if grep -q "$GHOST_JAR" "$CLASSPATH_FILE"; then
        echo "  ASSERTION FAILED: stale entry $GHOST_JAR still in $CLASSPATH_FILE"
        echo "    $(cat "$CLASSPATH_FILE")"
        PHASE_OK=false
    fi

    finish_phase "reopen re-resolves a stale .classpath (no false-positive build path errors)"
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

    assert_log "Opened existing project: $MODULE_NAME (classpath up to date)"
    assert_log "triggering full workspace build"
    assert_error_count_at_least 1

    finish_phase "reopen sees sources that changed while the server was down"
}

# ── Phase D: source repaired while the server was down (cause 2, stale markers) ─

phase_d_error_fixed_while_down() {
    echo ""
    echo "=== Phase D: source repaired on disk while the server was down ==="

    cp "$SOURCE_BACKUP" "$SOURCE_FILE"

    start_phase_server "phase D"

    assert_log "Opened existing project: $MODULE_NAME (classpath up to date)"
    assert_log "triggering full workspace build"
    assert_error_count 0

    finish_phase "reopen drops markers of problems that were fixed while the server was down"
}

# ── Phase E: POM touched without a content change ──────────────────────────────
# The regression guard for anchoring freshness to the .classpath's own timestamp: JDT does not
# rewrite that file when the resolved classpath is unchanged, so such an anchor would never
# advance and every start would re-resolve again.

phase_e_pom_touched() {
    echo ""
    echo "=== Phase E: POM touched, content unchanged (first start re-resolves once) ==="
    touch_pom

    start_phase_server "phase E, first start"
    assert_log "Opened existing project: $MODULE_NAME — re-resolving classpath"
    assert_error_count 0
    finish_phase "a touched POM re-resolves once"

    echo ""
    echo "=== Phase E: second start after the same touch (must not re-resolve again) ==="
    start_phase_server "phase E, second start"
    assert_log "Opened existing project: $MODULE_NAME (classpath up to date)"
    assert_log "$EXPECTED_COMPLIANCE"
    refute_log "re-resolving classpath"
    assert_error_count 0
    finish_phase "the re-resolution settles instead of repeating on every start"
}

# ── Phase F: POM changed for real ──────────────────────────────────────────────

phase_f_pom_changed() {
    echo ""
    echo "=== Phase F: POM changed for real (re-resolves exactly once) ==="
    change_pom "phase F: another change"

    start_phase_server "phase F, first start"
    assert_log "Opened existing project: $MODULE_NAME — re-resolving classpath"
    assert_log "triggering full workspace build"
    assert_error_count 0
    finish_phase "a changed POM re-resolves"

    echo ""
    echo "=== Phase F: second start after the same change (must not re-resolve again) ==="
    start_phase_server "phase F, second start"
    assert_log "Opened existing project: $MODULE_NAME (classpath up to date)"
    refute_log "re-resolving classpath"
    assert_error_count 0
    finish_phase "a changed POM re-resolves exactly once"
}

phase_a_first_import
phase_b_stale_classpath
phase_c_new_error_while_down
phase_d_error_fixed_while_down
phase_e_pom_touched
phase_f_pom_changed

print_summary

if [ "$TESTS_FAILED" -gt 0 ]; then
    exit 1
fi

exit 0
