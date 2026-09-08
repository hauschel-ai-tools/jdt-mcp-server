#!/usr/bin/env bash
# End-to-End code generation tests for the JDT MCP Server (standalone stdio mode).
#
# Focus: jdt_implement_interface has to insert the implements clause structurally
# (ASTRewrite/ListRewrite on the super interface list), not by scanning the source
# text between the type name and the next '{' (issue #101).
#
# The declarations in tests/fixtures/.../org/fixture/codegen cover the header shapes
# that defeat text scanning:
#   - a record, whose header carries a component list before the body brace
#   - a sealed class, whose permits clause follows the implements list
#   - a comment containing '{' inside the header, ahead of the real body brace
#   - an existing implements entry with nested generics, behind an extends clause
#   - several annotations plus a bounded type parameter containing "extends"
#   - a plain class with no clause at all (regression guard)
#
# Every assertion reads the FILESYSTEM, and each generated file is compiled with
# javac afterwards: a mangled header can still yield a green tool response.
#
# Requires: bash, jq, mkfifo; javac for the compile assertions (skipped if absent).
#
# Usage:  tests/codegen-test.sh [path/to/jdt-mcp-binary]
#         If no binary is given, the script searches the build output.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# shellcheck source=lib/mcp-helpers.sh
source "$SCRIPT_DIR/lib/mcp-helpers.sh"

RPC_TIMEOUT="${RPC_TIMEOUT:-600}"

# The request counter lives in a file: rpc() runs inside $(...) command
# substitutions, so a shell variable would be incremented in a subshell and reset
# to the same id for every call — duplicate ids break the response matching.
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

# ── Prepare fixtures ──────────────────────────────────────────────────────────
# Fixtures are copied (never symlinked) — the tests rewrite them.

FIXTURE_WORK_DIR="$(mktemp -d)"
SERVER_CWD="$(mktemp -d)"
cp -r "$SCRIPT_DIR/fixtures/fixture-parent" "$FIXTURE_WORK_DIR/"

PARENT_DIR="$FIXTURE_WORK_DIR/fixture-parent"
API_SRC="$PARENT_DIR/fixture-api/src/main/java"
CORE_SRC="$PARENT_DIR/fixture-core/src/main/java"
CODEGEN_SRC="$CORE_SRC/org/fixture/codegen"
TARGET_INTERFACE="org.fixture.api.Configurable"

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
# The helper in lib/mcp-helpers.sh returns the last stdout line, which breaks as
# soon as the server interleaves progress notifications. Match on the id instead.

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

    local response
    response=$(rpc "tools/call" "$params") || return 1
    echo "$response"
}

tool_text() {
    echo "$1" | jq -r '.result.content[0].text // empty'
}

tool_status() {
    tool_text "$1" | jq -r '.status // empty' 2>/dev/null || true
}

# ── Filesystem assertions ─────────────────────────────────────────────────────

# Collapses the file to a single whitespace-normalized line, so an assertion on a
# declaration header does not depend on how the rewrite wrapped it.
flatten_source() {
    tr '\n\t' '  ' < "$1" | tr -s ' '
}

assert_source_matches() {
    local file="$1"
    local pattern="$2"
    local description="$3"
    if [ -f "$file" ] && flatten_source "$file" | grep -Eq "$pattern"; then
        return 0
    fi
    echo "  ASSERTION FAILED: $description"
    echo "    file: $file"
    echo "    expected to match: $pattern"
    echo "    actual header: $(flatten_source "$file" 2>/dev/null | head -c 300)"
    return 1
}

assert_file_contains() {
    local file="$1"
    local pattern="$2"
    local description="$3"
    if [ -f "$file" ] && grep -qF "$pattern" "$file"; then
        return 0
    fi
    echo "  ASSERTION FAILED: $description"
    echo "    file: $file"
    echo "    expected to contain: $pattern"
    return 1
}

# Counts implements keywords in code lines only — the fixtures document their own
# header shape in Javadoc, and those lines must not be counted.
assert_implements_clause_count() {
    local file="$1"
    local expected="$2"
    local description="$3"
    local actual
    actual=$(grep -vE '^[[:space:]]*(\*|//|/\*)' "$file" | grep -cw implements || true)
    if [ "$actual" = "$expected" ]; then
        return 0
    fi
    echo "  ASSERTION FAILED: $description"
    echo "    file: $file"
    echo "    expected $expected code line(s) with 'implements', found $actual"
    grep -nvE '^[[:space:]]*(\*|//|/\*)' "$file" | grep -w implements | sed 's/^/      /' | head -5
    return 1
}

HAVE_JAVAC=false
if command -v javac >/dev/null 2>&1; then
    HAVE_JAVAC=true
fi

# Compiles a single fixture file against the fixture source path. Only the file
# itself and what it references is compiled, so one broken case does not poison
# the compile assertion of the next one.
assert_compiles() {
    local file="$1"
    local description="$2"
    if ! $HAVE_JAVAC; then
        return 0
    fi
    local out javac_log
    out="$(mktemp -d)"
    javac_log="$(mktemp)"
    if javac -nowarn --release 21 -sourcepath "$API_SRC:$CORE_SRC" -d "$out" "$file" > "$javac_log" 2>&1; then
        rm -rf "$out" "$javac_log"
        return 0
    fi
    echo "  ASSERTION FAILED: $description"
    echo "    file: $file"
    echo "    javac:"
    sed 's/^/      /' "$javac_log" | head -12
    rm -rf "$out" "$javac_log"
    return 1
}

# ── Start server and import the fixture ───────────────────────────────────────

start_server "$BINARY" "$SERVER_CWD"
echo "Server PID: $SERVER_PID"
wait_for_ready 120

init_params='{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"codegen-test","version":"1.0"}}'
rpc "initialize" "$init_params" > /dev/null
send_notification "notifications/initialized"

echo ""
echo "Importing fixture-parent..."
import_parent=$(call_tool "jdt_import_project" "$(jq -cn --arg p "$PARENT_DIR" '{"path":$p}')")
echo "  fixture-parent: $(tool_status "$import_parent")"

projects=$(tool_text "$(call_tool "jdt_list_projects" '{}')")
for required in fixture-api fixture-core; do
    if ! echo "$projects" | grep -q "$required"; then
        echo "FATAL: project $required not imported — codegen tests cannot run"
        echo "$projects" | head -20
        exit 1
    fi
done

echo ""
echo "════════════════════════════════════════"
echo " Running code generation end-to-end tests"
echo "════════════════════════════════════════"
echo ""

# ── Shared driver ─────────────────────────────────────────────────────────────
# Calls jdt_implement_interface for one fixture type and checks the response,
# the resulting declaration header on disk, the generated stubs and the compile.

implement_interface_case() {
    local label="$1"
    local class_name="$2"
    local file="$3"
    local header_pattern="$4"

    echo "[$label] jdt_implement_interface $class_name -> $TARGET_INTERFACE"

    if [ ! -f "$file" ]; then
        fail "$label" "fixture file missing: $file"
        return
    fi

    local response
    response=$(call_tool "jdt_implement_interface" \
        "$(jq -cn --arg c "$class_name" --arg i "$TARGET_INTERFACE" \
            '{"className":$c,"interfaceName":$i,"generateMethodStubs":true}')") \
        || { fail "$label" "no response"; return; }

    local ok=true
    local status text
    status=$(tool_status "$response")
    text=$(tool_text "$response")

    if [ "$(echo "$response" | jq -r '.result.isError // false')" = "true" ]; then
        echo "  ASSERTION FAILED: tool reported isError"
        echo "    $(echo "$text" | head -c 400)"
        ok=false
    fi
    if [ "$status" != "SUCCESS" ]; then
        echo "  ASSERTION FAILED: status == SUCCESS"
        echo "    actual: ${status:-<none>} / $(echo "$text" | head -c 400)"
        ok=false
    fi

    assert_source_matches "$file" "$header_pattern" \
        "implements clause written into the declaration header on disk" || ok=false
    assert_implements_clause_count "$file" 1 \
        "exactly one implements clause in the file" || ok=false
    assert_file_contains "$file" "import org.fixture.api.Configurable;" \
        "import for the implemented interface added on disk" || ok=false
    assert_file_contains "$file" "public void configure(String key, String value)" \
        "stub for configure(String, String) written to disk" || ok=false
    assert_file_contains "$file" "public String getConfig(String key)" \
        "stub for getConfig(String) written to disk" || ok=false
    assert_compiles "$file" "file still compiles after the rewrite" || ok=false

    if $ok; then pass "$label"; else fail "$label"; fi
}

# ── Test 1: plain class, no extends, no implements (regression guard) ─────────

test_plain_class() {
    implement_interface_case \
        "Test 1 plain class" \
        "org.fixture.codegen.PlainTarget" \
        "$CODEGEN_SRC/PlainTarget.java" \
        'class PlainTarget +implements +Configurable +\{'
}

# ── Test 2: extends + existing implements entry with nested generics ──────────

test_generic_implements_list() {
    implement_interface_case \
        "Test 2 nested generics in existing implements list" \
        "org.fixture.codegen.GenericTarget" \
        "$CODEGEN_SRC/GenericTarget.java" \
        'class GenericTarget +extends +BaseHolder +implements +Transformer<Map<String, ?List<Integer>>>, ?Configurable +\{'
}

# ── Test 3: several annotations and a bounded type parameter ──────────────────

test_annotated_generic_class() {
    implement_interface_case \
        "Test 3 annotations and bounded type parameter" \
        "org.fixture.codegen.AnnotatedTarget" \
        "$CODEGEN_SRC/AnnotatedTarget.java" \
        'class AnnotatedTarget<T extends Comparable<\? super T>> +implements +Configurable +\{'
}

# ── Test 4: record — the header carries a component list (#101) ───────────────

test_record() {
    implement_interface_case \
        "Test 4 record with component list" \
        "org.fixture.codegen.CoordinatesTarget" \
        "$CODEGEN_SRC/CoordinatesTarget.java" \
        'record CoordinatesTarget\(double latitude, double longitude\) +implements +Configurable +\{'
}

# ── Test 5: sealed class — permits follows the implements list (#101) ─────────

test_sealed_class() {
    implement_interface_case \
        "Test 5 sealed class with permits clause" \
        "org.fixture.codegen.SealedTarget" \
        "$CODEGEN_SRC/SealedTarget.java" \
        'class SealedTarget +implements +Transformer<String>, ?Configurable +permits +SquareTarget +\{'
}

# ── Test 6: a comment containing '{' inside the header (#101) ─────────────────

test_commented_header() {
    implement_interface_case \
        "Test 6 comment with a brace inside the header" \
        "org.fixture.codegen.CommentedTarget" \
        "$CODEGEN_SRC/CommentedTarget.java" \
        'class CommentedTarget +extends +BaseHolder .*implements +Transformer<String>, ?Configurable +\{'
}

# ── Test 7: the workspace itself stays error free ─────────────────────────────
# Cross-check through JDT's own markers: the per-case javac run only sees the
# file it compiles, the marker check sees the whole project.

test_project_has_no_errors() {
    echo "[Test 7] fixture-core has no compilation errors after the rewrites"

    call_tool "jdt_refresh_project" '{"projectName":"fixture-core"}' > /dev/null || true

    local elapsed=0 error_count="" err_text=""
    while [ "$elapsed" -lt 60 ]; do
        err_text=$(tool_text "$(call_tool "jdt_get_compilation_errors" '{"projectName":"fixture-core"}')")
        error_count=$(echo "$err_text" | jq -r '.errorCount // empty' 2>/dev/null || true)
        if [ "$error_count" = "0" ]; then
            break
        fi
        sleep 3
        elapsed=$((elapsed + 3))
    done

    if [ "$error_count" = "0" ]; then
        pass "fixture-core free of compilation errors"
    else
        echo "  ASSERTION FAILED: expected errorCount 0, got '${error_count:-<none>}'"
        echo "$err_text" | jq -r '.errors[]? | "    \(.file):\(.lineNumber) \(.message)"' 2>/dev/null | head -12
        fail "fixture-core free of compilation errors"
    fi
}

if ! $HAVE_JAVAC; then
    skip "javac compile assertions" "javac not on PATH"
fi

test_plain_class
test_generic_implements_list
test_annotated_generic_class
test_record
test_sealed_class
test_commented_header
test_project_has_no_errors

print_summary

if [ "$TESTS_FAILED" -gt 0 ]; then
    echo "Server stderr (last 30 lines):"
    tail -30 "$STDERR_FILE" 2>/dev/null || true
    exit 1
fi

exit 0
