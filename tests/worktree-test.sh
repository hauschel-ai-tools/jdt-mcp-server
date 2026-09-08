#!/usr/bin/env bash
# E2E test for issue #126: a second checkout of an already imported project (a git worktree next
# to the main checkout) must become its own set of workspace projects. Before the fix the importer
# reopened the main checkout's project of the same name, so every build, test and diagnosis ran
# against the wrong tree and reported green for changes that were never looked at.
#
# One server, started in the main checkout, with the worktree imported on top via
# jdt_import_project. Checks:
#   1  the worktree's modules are imported as '<name>@<worktree dir>' and located in the worktree
#   2  jdt_list_projects tells both checkouts apart by location and importRoot
#   3  a worktree module depends on its own sibling, the main module on its own
#   4  jdt_maven_build names the directory it built
#   5  a broken source in the worktree shows up on the worktree project only
#   6  an unknown projectName lists the known projects with their locations
#
# Requires: bash, jq, mkfifo, mvn (the importer shells out to dependency:build-classpath)
#
# Usage:  tests/worktree-test.sh [path/to/jdt-mcp-binary]
#         If no binary is given, the script searches the build output.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# shellcheck source=lib/mcp-helpers.sh
source "$SCRIPT_DIR/lib/mcp-helpers.sh"

WORKTREE_ID="126"

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

    candidate="$(find "$PROJECT_ROOT/org.naturzukunft.jdt.mcp.product/target/products" -name jdt-mcp -type f -perm -u+x 2>/dev/null | head -1)"
    if [ -n "$candidate" ]; then
        echo "$candidate"
        return
    fi

    echo "ERROR: jdt-mcp binary not found. Build first: mvn clean package" >&2
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

# ── Prepare main checkout, worktree and workspace ──────────────────────────────

FIXTURE_WORK_DIR="$(mktemp -d)"
MAIN_DIR="$FIXTURE_WORK_DIR/fixture-parent"
WORKTREE_DIR="$FIXTURE_WORK_DIR/fixture-parent-worktrees/$WORKTREE_ID"
cp -r "$SCRIPT_DIR/fixtures/fixture-parent" "$MAIN_DIR"
mkdir -p "$(dirname "$WORKTREE_DIR")"
cp -r "$SCRIPT_DIR/fixtures/fixture-parent" "$WORKTREE_DIR"
find "$FIXTURE_WORK_DIR" -name '.classpath' -delete

export JDTMCP_WORKSPACE="$FIXTURE_WORK_DIR/workspace"
export JDTMCP_RECOVERY=1

SERVER_LOG="$HOME/.jdt-mcp/jdt-mcp-$(basename "$MAIN_DIR").log"
LOG_OFFSET=$(wc -l < "$SERVER_LOG" 2>/dev/null || echo 0)

cleanup_all() {
    exec 3>&- 2>/dev/null || true
    if [ -n "${SERVER_PID:-}" ]; then
        local waited=0
        while kill -0 "$SERVER_PID" 2>/dev/null && [ "$waited" -lt 30 ]; do
            sleep 1
            waited=$((waited + 1))
        done
        kill "$SERVER_PID" 2>/dev/null || true
        wait "$SERVER_PID" 2>/dev/null || true
    fi
    [ -n "${WORK_DIR:-}" ] && [ -d "$WORK_DIR" ] && rm -rf "$WORK_DIR"
    [ -d "$FIXTURE_WORK_DIR" ] && rm -rf "$FIXTURE_WORK_DIR"
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

server_log() {
    tail -n +$((LOG_OFFSET + 1)) "$SERVER_LOG" 2>/dev/null || true
}

wait_for_import_finished() {
    local timeout=300
    local elapsed=0
    while [ "$elapsed" -lt "$timeout" ]; do
        if server_log | grep -qF "Project import and build finished"; then
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

CHECK_OK=true

expect() {
    local description="$1"
    local actual="$2"
    local expected="$3"
    if [ "$actual" != "$expected" ]; then
        echo "  ASSERTION FAILED: $description"
        echo "    expected: $expected"
        echo "    actual:   $actual"
        CHECK_OK=false
    fi
}

finish_check() {
    local name="$1"
    if $CHECK_OK; then
        pass "$name"
    else
        fail "$name"
    fi
    CHECK_OK=true
}

project_location() {
    echo "$1" | jq -r --arg n "$2" '.projects[] | select(.name == $n) | .location // empty'
}

project_import_root() {
    echo "$1" | jq -r --arg n "$2" '.projects[] | select(.name == $n) | .importRoot // empty'
}

project_dependencies() {
    local text
    text=$(tool_text "$(call_tool "jdt_get_classpath" "$(jq -cn --arg p "$1" '{"projectName":$p}')")")
    echo "$text" | jq -r '[.projectDependencies[].path] | sort | join(",")'
}

error_count_of() {
    local text
    text=$(tool_text "$(call_tool "jdt_get_compilation_errors" "$(jq -cn --arg p "$1" '{"projectName":$p}')")")
    echo "$text" | jq -r '.errorCount // empty' 2>/dev/null || true
}

# ── Start server in the main checkout ──────────────────────────────────────────

echo "=== JDT MCP Worktree Test (#126) ==="
echo "Main checkout: $MAIN_DIR"
echo "Worktree:      $WORKTREE_DIR"

start_server "$BINARY" "$MAIN_DIR"
wait_for_ready 120
rpc "initialize" '{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"worktree-test","version":"1.0"}}' > /dev/null
send_notification "notifications/initialized"
wait_for_import_finished

# ── 1. Import the worktree ─────────────────────────────────────────────────────

echo ""
echo "Test 1: the worktree is imported as its own projects (#126)"
IMPORT_TEXT=$(tool_text "$(call_tool "jdt_import_project" "$(jq -cn --arg p "$WORKTREE_DIR" '{"path":$p}')")")
expect "import status" "$(echo "$IMPORT_TEXT" | jq -r '.status')" "SUCCESS"
IMPORTED_NAMES=$(echo "$IMPORT_TEXT" | jq -r '[.projects[].name] | sort | join(",")')
expect "imported project names" "$IMPORTED_NAMES" \
    "fixture-api@$WORKTREE_ID,fixture-app@$WORKTREE_ID,fixture-broken@$WORKTREE_ID,fixture-core@$WORKTREE_ID"
OUTSIDE_WORKTREE=$(echo "$IMPORT_TEXT" | jq -r --arg wt "$WORKTREE_DIR/" '[.projects[].location | select(startswith($wt) | not)] | join(",")')
expect "every imported location lies in the worktree" "$OUTSIDE_WORKTREE" ""
if ! server_log | grep -qF "is taken by $MAIN_DIR/fixture-api"; then
    echo "  ASSERTION FAILED: server log does not explain the renamed import"
    CHECK_OK=false
fi
finish_check "worktree modules imported under suffixed names"

# ── 2. Both checkouts are told apart ───────────────────────────────────────────

echo ""
echo "Test 2: jdt_list_projects tells the checkouts apart by location and importRoot"
LIST_TEXT=$(tool_text "$(call_tool "jdt_list_projects" '{}')")
expect "main fixture-api location" "$(project_location "$LIST_TEXT" fixture-api)" "$MAIN_DIR/fixture-api"
expect "worktree fixture-api location" "$(project_location "$LIST_TEXT" "fixture-api@$WORKTREE_ID")" "$WORKTREE_DIR/fixture-api"
expect "main fixture-api importRoot" "$(project_import_root "$LIST_TEXT" fixture-api)" "$MAIN_DIR"
expect "worktree fixture-api importRoot" "$(project_import_root "$LIST_TEXT" "fixture-api@$WORKTREE_ID")" "$WORKTREE_DIR"
finish_check "list_projects shows location and importRoot per checkout"

# ── 3. Siblings resolve within their own checkout ──────────────────────────────

echo ""
echo "Test 3: a module depends on the sibling of its own checkout"
expect "worktree fixture-core dependencies" "$(project_dependencies "fixture-core@$WORKTREE_ID")" "/fixture-api@$WORKTREE_ID"
expect "main fixture-core dependencies" "$(project_dependencies fixture-core)" "/fixture-api"
finish_check "sibling references stay inside the checkout"

# ── 4. Maven build names its directory ─────────────────────────────────────────

echo ""
echo "Test 4: jdt_maven_build reports the directory it built"
BUILD_TEXT=$(tool_text "$(call_tool "jdt_maven_build" "$(jq -cn --arg p "fixture-api@$WORKTREE_ID" '{"projectName":$p,"goals":"compile","skipTests":true}')")")
expect "build location" "$(echo "$BUILD_TEXT" | jq -r '.location // empty')" "$WORKTREE_DIR/fixture-api"
BUILD_STATUS=$(echo "$BUILD_TEXT" | jq -r '.status // empty')
if [ "$BUILD_STATUS" != "SUCCESS" ]; then
    echo "  NOTE: maven build ended with status '$BUILD_STATUS' (needs the plugins in the local repository); only the location is asserted"
fi
finish_check "maven build result names the worktree directory"

# ── 5. A broken worktree source is seen on the worktree project only ───────────

echo ""
echo "Test 5: a compile error in the worktree stays in the worktree"
echo "this is not java" >> "$WORKTREE_DIR/fixture-api/src/main/java/org/fixture/api/Tracked.java"
call_tool "jdt_refresh_project" "$(jq -cn --arg p "fixture-api@$WORKTREE_ID" '{"projectName":$p}')" > /dev/null
WORKTREE_ERRORS=""
for _ in $(seq 1 60); do
    WORKTREE_ERRORS=$(error_count_of "fixture-api@$WORKTREE_ID")
    if [ -n "$WORKTREE_ERRORS" ] && [ "$WORKTREE_ERRORS" -gt 0 ] 2>/dev/null; then
        break
    fi
    sleep 1
done
if [ -z "$WORKTREE_ERRORS" ] || [ "$WORKTREE_ERRORS" -lt 1 ]; then
    echo "  ASSERTION FAILED: expected at least one error on fixture-api@$WORKTREE_ID, got '$WORKTREE_ERRORS'"
    CHECK_OK=false
fi
expect "main fixture-api stays clean" "$(error_count_of fixture-api)" "0"
finish_check "errors are attributed to the right checkout"

# ── 6. Unknown project name is answered with the known ones ────────────────────

echo ""
echo "Test 6: an unknown projectName lists the known projects with locations"
NOT_FOUND=$(call_tool "jdt_maven_build" '{"projectName":"fixture-parent","goals":"compile"}')
expect "isError" "$(echo "$NOT_FOUND" | jq -r '.result.isError')" "true"
NOT_FOUND_TEXT=$(tool_text "$NOT_FOUND")
expect "message" "$(echo "$NOT_FOUND_TEXT" | jq -r '.message // empty')" "Project not found: fixture-parent"
expect "known worktree project" "$(echo "$NOT_FOUND_TEXT" | jq -r --arg n "fixture-api@$WORKTREE_ID" '.knownProjects[] | select(.name == $n) | .location')" "$WORKTREE_DIR/fixture-api"
expect "known main project" "$(echo "$NOT_FOUND_TEXT" | jq -r '.knownProjects[] | select(.name == "fixture-api") | .location')" "$MAIN_DIR/fixture-api"
if ! echo "$NOT_FOUND_TEXT" | jq -r '.hint // empty' | grep -q "jdt_import_project"; then
    echo "  ASSERTION FAILED: hint does not point at jdt_import_project"
    CHECK_OK=false
fi
finish_check "project-not-found answer is self-healing"

print_summary

if [ "$TESTS_FAILED" -gt 0 ]; then
    exit 1
fi

exit 0
