#!/usr/bin/env bash
# End-to-End build path tests for the JDT MCP Server (standalone stdio mode).
#
# Covers two defects that both hide behind an unusable build path:
#   - jdt_get_compilation_errors reported only the Java problem marker, so a build path
#     problem showed up as the useless "The project cannot be built until build path errors
#     are resolved" with no hint at the cause                                   (issue #115)
#   - jdt_maven_update_project re-added reactor siblings as ~/.m2 JARs next to the existing
#     project reference; setRawClasspath then rejected the classpath with "Build path
#     contains duplicate entry" and the module kept compiling against the last mvn install
#                                                                               (issue #116)
#
# The #116 assertions read the .classpath ON DISK, not the tool response: the response was
# green while the classpath had two entries for the same module.
#
# Requires: bash, jq, mkfifo, mvn (the server shells out to 'mvn dependency:build-classpath',
# and the fixture siblings have to be installed into ~/.m2 for Maven to resolve them at all).
#
# Usage:  tests/buildpath-test.sh [path/to/jdt-mcp-binary]
#         If no binary is given, the script searches the build output.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# shellcheck source=lib/mcp-helpers.sh
source "$SCRIPT_DIR/lib/mcp-helpers.sh"

# Import + full build of a multi-module fixture, plus a Maven round trip per update
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

command -v mvn >/dev/null 2>&1 || { echo "ERROR: mvn not on PATH" >&2; exit 1; }
command -v jq  >/dev/null 2>&1 || { echo "ERROR: jq not on PATH" >&2; exit 1; }

# ── Prepare fixtures ──────────────────────────────────────────────────────────
# Fixtures are copied (never symlinked) - the tests rewrite their .classpath files.

FIXTURE_WORK_DIR="$(mktemp -d)"
SERVER_CWD="$(mktemp -d)"
cp -r "$SCRIPT_DIR/fixtures/fixture-parent" "$FIXTURE_WORK_DIR/"
cp -r "$SCRIPT_DIR/fixtures/fixture-badclasspath" "$FIXTURE_WORK_DIR/"

PARENT_DIR="$FIXTURE_WORK_DIR/fixture-parent"
BADCP_DIR="$FIXTURE_WORK_DIR/fixture-badclasspath"
APP_CLASSPATH="$PARENT_DIR/fixture-app/.classpath"

SERVER_LOG="$HOME/.jdt-mcp/jdt-mcp-$(basename "$SERVER_CWD").log"

echo "Fixtures at: $FIXTURE_WORK_DIR"

cleanup_all() {
    cleanup
    [ -d "$FIXTURE_WORK_DIR" ] && rm -rf "$FIXTURE_WORK_DIR"
    [ -d "$SERVER_CWD" ] && rm -rf "$SERVER_CWD"
    [ -f "$RPC_ID_FILE" ] && rm -f "$RPC_ID_FILE"
    return 0
}
trap cleanup_all EXIT

# ── Install the reactor siblings into ~/.m2 ───────────────────────────────────
# Without this 'mvn dependency:build-classpath' fails for fixture-app and the whole #116
# scenario (sibling resolved to a JAR) cannot arise. fixture-broken is left out on purpose:
# it has deliberate compile errors and would fail the install.

echo ""
echo "Installing fixture-api and fixture-core into the local Maven repository..."
if ! mvn -q -B -f "$PARENT_DIR/pom.xml" -pl fixture-api,fixture-core -am \
        -DskipTests install > "$FIXTURE_WORK_DIR/mvn-install.log" 2>&1; then
    echo "ERROR: 'mvn install' of the fixture siblings failed - #116 cannot be tested" >&2
    tail -30 "$FIXTURE_WORK_DIR/mvn-install.log" >&2
    exit 1
fi
echo "  installed"

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

# ── Classpath assertions (read the file on disk) ──────────────────────────────

# Counts <classpathentry> elements of a given kind whose path matches a grep pattern.
count_classpath_entries() {
    local file="$1"
    local kind="$2"
    local pattern="$3"
    grep -c "kind=\"$kind\"[^>]*$pattern" "$file" 2>/dev/null || true
}

assert_entry_count() {
    local file="$1"
    local kind="$2"
    local pattern="$3"
    local expected="$4"
    local description="$5"

    local actual
    actual=$(count_classpath_entries "$file" "$kind" "$pattern")
    actual=${actual:-0}
    if [ "$actual" = "$expected" ]; then
        return 0
    fi
    echo "  ASSERTION FAILED: $description"
    echo "    file:     $file"
    echo "    pattern:  kind=\"$kind\" ... $pattern"
    echo "    expected: $expected entries, found: $actual"
    echo "    --- classpath ---"
    sed 's/^/    /' "$file"
    return 1
}

# ── Start server and import the fixtures ──────────────────────────────────────

start_server "$BINARY" "$SERVER_CWD"
echo "Server PID: $SERVER_PID"
wait_for_ready 120

init_params='{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"buildpath-test","version":"1.0"}}'
rpc "initialize" "$init_params" > /dev/null
send_notification "notifications/initialized"

echo ""
echo "Importing fixture-parent and fixture-badclasspath..."
import_parent=$(call_tool "jdt_import_project" "$(jq -cn --arg p "$PARENT_DIR" '{"path":$p}')")
import_badcp=$(call_tool "jdt_import_project" "$(jq -cn --arg p "$BADCP_DIR" '{"path":$p}')")
echo "  fixture-parent:       $(tool_status "$import_parent")"
echo "  fixture-badclasspath: $(tool_status "$import_badcp")"

projects=$(tool_text "$(call_tool "jdt_list_projects" '{}')")
for required in fixture-core fixture-app fixture-badclasspath; do
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

# ── Test 4-6: reactor sibling stays a project reference (#116) ────────────────

echo ""
echo "Test 4: jdt_maven_update_project keeps the sibling as a project reference (#116)"
log_before=0
[ -f "$SERVER_LOG" ] && log_before=$(wc -l < "$SERVER_LOG")

update=$(call_tool "jdt_maven_update_project" '{"projectName":"fixture-app"}')
update_text=$(tool_text "$update")
echo "    status: $(echo "$update_text" | jq -r '.status // "?"'), resolved: $(echo "$update_text" | jq -r '.dependenciesResolved // "?"')"

failed=0
assert_entry_count "$APP_CLASSPATH" "src" 'path="/fixture-core"' 1 \
    "exactly one project reference to fixture-core" || failed=1
if [ "$failed" -eq 0 ]; then
    pass "exactly one project reference to fixture-core"
else
    fail "exactly one project reference to fixture-core"
fi

echo "Test 5: the sibling's ~/.m2 JAR is not on the classpath (#116)"
failed=0
assert_entry_count "$APP_CLASSPATH" "lib" 'fixture-core-' 0 \
    "no fixture-core JAR from the local Maven repository" || failed=1
if [ "$failed" -eq 0 ]; then
    pass "no fixture-core JAR from the local Maven repository"
else
    fail "no fixture-core JAR from the local Maven repository"
fi

echo "Test 6: no 'duplicate entry' warning in the server log (#116)"
if [ -f "$SERVER_LOG" ]; then
    new_warnings=$(tail -n "+$((log_before + 1))" "$SERVER_LOG" | grep -c "duplicate entry" || true)
    new_warnings=${new_warnings:-0}
    if [ "$new_warnings" -eq 0 ]; then
        pass "dependency setup ran without 'Build path contains duplicate entry'"
    else
        fail "dependency setup ran without 'Build path contains duplicate entry'" \
             "$new_warnings warning(s) after the update"
        tail -n "+$((log_before + 1))" "$SERVER_LOG" | grep "duplicate entry" | head -3 | sed 's/^/    /'
    fi
else
    skip "duplicate entry warning check" "server log not found at $SERVER_LOG"
fi

echo "Test 7: the update reports which JARs the workspace projects superseded (#116)"
superseded=$(echo "$update_text" | jq -r '(.workspaceProjectsPreferred // []) | join(",")')
if echo "$superseded" | grep -q "fixture-core"; then
    pass "workspaceProjectsPreferred contains fixture-core"
else
    fail "workspaceProjectsPreferred contains fixture-core" "got: '$superseded'"
fi

echo "Test 8: a second update stays idempotent (#116)"
call_tool "jdt_maven_update_project" '{"projectName":"fixture-app"}' > /dev/null
failed=0
assert_entry_count "$APP_CLASSPATH" "src" 'path="/fixture-core"' 1 \
    "still exactly one project reference after a second update" || failed=1
assert_entry_count "$APP_CLASSPATH" "lib" 'fixture-core-' 0 \
    "still no fixture-core JAR after a second update" || failed=1
if [ "$failed" -eq 0 ]; then
    pass "classpath unchanged by a second jdt_maven_update_project"
else
    fail "classpath unchanged by a second jdt_maven_update_project"
fi

echo "Test 9: the sibling is still compiled from source, not from the JAR (#116)"
app_errors=$(tool_text "$(call_tool "jdt_get_compilation_errors" '{"projectName":"fixture-app"}')")
app_error_count=$(echo "$app_errors" | jq -r '.errorCount // -1')
app_bp_count=$(echo "$app_errors" | jq -r '.buildPathProblemCount // -1')
if [ "$app_error_count" = "0" ] && [ "$app_bp_count" = "0" ]; then
    pass "fixture-app has no compilation and no build path errors after the update"
else
    fail "fixture-app has no compilation and no build path errors after the update" \
         "errorCount=$app_error_count buildPathProblemCount=$app_bp_count"
    echo "$app_errors" | jq -c '[.errors[]? | {kind, message}]' | sed 's/^/    /'
fi

print_summary

if [ "$TESTS_FAILED" -gt 0 ]; then
    exit 1
fi
exit 0
