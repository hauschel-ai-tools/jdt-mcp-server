#!/usr/bin/env bash
# End-to-End refactoring tests for the JDT MCP Server (standalone stdio mode).
#
# Covers the headless-mode defects where a refactoring reported success but the
# workspace on disk was not (fully) updated:
#   - jdt_move_type across two independently imported projects   (issue #79)
#   - jdt_rename_element PACKAGE with renameSubpackages=true      (issue #77)
#   - jdt_rename_element METHOD on a generic interface method     (issue #29)
#   - jdt_encapsulate_field (jdt.ui code template store headless)  (issue #98)
#
# Every assertion reads the FILESYSTEM, not the tool response, because the tool
# response was green for both defects while the buffers never reached the disk.
#
# Requires: bash, jq, mkfifo
#
# Usage:  tests/refactoring-test.sh [path/to/jdtls-mcp-binary]
#         If no binary is given, the script searches the build output.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# shellcheck source=lib/mcp-helpers.sh
source "$SCRIPT_DIR/lib/mcp-helpers.sh"

# Refactorings search the whole workspace and can run for minutes on a cold index.
RPC_TIMEOUT="${RPC_TIMEOUT:-600}"

# The request counter lives in a file: rpc() runs inside $(...) command
# substitutions, and a shell variable incremented in a subshell would reset to
# the same id for every call — duplicate ids make the response matching below
# return an older response.
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

# ── Prepare fixtures ──────────────────────────────────────────────────────────
# Fixtures are copied (never symlinked) — the tests rewrite them.

FIXTURE_WORK_DIR="$(mktemp -d)"
SERVER_CWD="$(mktemp -d)"
cp -r "$SCRIPT_DIR/fixtures/fixture-parent" "$FIXTURE_WORK_DIR/"
cp -r "$SCRIPT_DIR/fixtures/fixture-external" "$FIXTURE_WORK_DIR/"

PARENT_DIR="$FIXTURE_WORK_DIR/fixture-parent"
EXTERNAL_DIR="$FIXTURE_WORK_DIR/fixture-external"
CORE_SRC="$PARENT_DIR/fixture-core/src/main/java/org/fixture"
APP_SERVICE="$PARENT_DIR/fixture-app/src/main/java/org/fixture/app/AppService.java"
EXTERNAL_SERVICE="$EXTERNAL_DIR/src/main/java/org/fixture/external/ExternalService.java"
API_PROCESSOR="$PARENT_DIR/fixture-api/src/main/java/org/fixture/api/Processor.java"
SIMPLE_PROCESSOR="$CORE_SRC/core/SimpleProcessor.java"
BATCH_PROCESSOR="$CORE_SRC/core/BatchProcessor.java"
API_CONFIGURABLE="$PARENT_DIR/fixture-api/src/main/java/org/fixture/api/Configurable.java"
PROCESSOR_FACTORY="$CORE_SRC/core/ProcessorFactory.java"

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
# soon as the server interleaves progress notifications — long-running
# refactorings always do. Match on the response id instead.

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

# Calls a tool and echoes the payload of result.content[0].text (tools answer JSON as text).
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

assert_file_exists() {
    local file="$1"
    local description="$2"
    if [ -f "$file" ]; then
        return 0
    fi
    echo "  ASSERTION FAILED: $description"
    echo "    missing file: $file"
    return 1
}

assert_file_absent() {
    local file="$1"
    local description="$2"
    if [ ! -e "$file" ]; then
        return 0
    fi
    echo "  ASSERTION FAILED: $description"
    echo "    file still present: $file"
    return 1
}

assert_no_java_files() {
    local dir="$1"
    local description="$2"
    local leftovers
    leftovers=$(find "$dir" -name '*.java' 2>/dev/null | head -5 || true)
    if [ -z "$leftovers" ]; then
        return 0
    fi
    echo "  ASSERTION FAILED: $description"
    echo "    still on disk: $leftovers"
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

assert_file_lacks() {
    local file="$1"
    local pattern="$2"
    local description="$3"
    if [ -f "$file" ] && grep -qF "$pattern" "$file"; then
        echo "  ASSERTION FAILED: $description"
        echo "    file: $file"
        echo "    still contains: $pattern"
        return 1
    fi
    return 0
}

# ── Start server and import both projects ─────────────────────────────────────

start_server "$BINARY" "$SERVER_CWD"
echo "Server PID: $SERVER_PID"
wait_for_ready 120

init_params='{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"refactoring-test","version":"1.0"}}'
rpc "initialize" "$init_params" > /dev/null
send_notification "notifications/initialized"

echo ""
echo "Importing fixture-parent and fixture-external (two separate imports)..."
import_parent=$(call_tool "jdt_import_project" "$(jq -cn --arg p "$PARENT_DIR" '{"path":$p}')")
import_external=$(call_tool "jdt_import_project" "$(jq -cn --arg p "$EXTERNAL_DIR" '{"path":$p}')")
echo "  fixture-parent:   $(tool_status "$import_parent")"
echo "  fixture-external: $(tool_status "$import_external")"

projects=$(tool_text "$(call_tool "jdt_list_projects" '{}')")
for required in fixture-core fixture-app fixture-external; do
    if ! echo "$projects" | grep -q "$required"; then
        echo "FATAL: project $required not imported — refactoring tests cannot run"
        echo "$projects" | head -20
        exit 1
    fi
done

echo ""
echo "════════════════════════════════════════"
echo " Running refactoring end-to-end tests"
echo "════════════════════════════════════════"
echo ""

# ── Test 0: rename a generic interface method, overrides included (#29) ───────
# Runs before the other tests: it must see the fixture with its original package
# layout (Test 2 renames org.fixture.core).

test_rename_generic_interface_method() {
    echo "[Test 0] jdt_rename_element METHOD org.fixture.api.Processor#process -> execute (#29)"

    local response
    response=$(call_tool "jdt_rename_element" \
        '{"elementName":"org.fixture.api.Processor#process","newName":"execute","elementType":"METHOD"}') \
        || { fail "jdt_rename_element METHOD (no response)"; return; }

    local ok=true
    local status
    status=$(tool_status "$response")
    local text
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

    # Declaration in the generic interface itself
    assert_file_contains "$API_PROCESSOR" "String execute(T item);" \
        "interface declaration renamed on disk" || ok=false

    # Overriding implementations in another module — the actual defect of #29
    assert_file_contains "$SIMPLE_PROCESSOR" "public String execute(String item)" \
        "override in fixture-core (SimpleProcessor) renamed on disk" || ok=false
    assert_file_lacks "$SIMPLE_PROCESSOR" "public String process(String item)" \
        "no stale override declaration in SimpleProcessor" || ok=false
    assert_file_contains "$BATCH_PROCESSOR" "public String execute(List<String> items)" \
        "override in fixture-core (BatchProcessor) renamed on disk" || ok=false
    assert_file_lacks "$BATCH_PROCESSOR" "public String process(List<String> items)" \
        "no stale override declaration in BatchProcessor" || ok=false

    # Self-call inside the implementing class
    assert_file_contains "$SIMPLE_PROCESSOR" "String processed = execute(item);" \
        "self-call inside SimpleProcessor renamed on disk" || ok=false

    # Callers through the interface type
    assert_file_contains "$APP_SERVICE" "processor.execute(sanitized)" \
        "caller in fixture-app updated on disk" || ok=false
    assert_file_contains "$EXTERNAL_SERVICE" "processor.execute(sanitized)" \
        "cross-project caller in fixture-external updated on disk" || ok=false

    if $ok; then pass "jdt_rename_element METHOD on generic interface"; else fail "jdt_rename_element METHOD on generic interface"; fi
}

# ── Test 0b: rename a non-generic interface method (regression guard, #29) ────
# The counterpart to Test 0: without a type variable JDT finds the overrides
# itself. This test fails if the override completion of #29 starts to fire in
# cases that already work (a false WARNING or a double rename).

test_rename_plain_interface_method() {
    echo "[Test 0b] jdt_rename_element METHOD org.fixture.api.Configurable#configure -> setOption (#29 guard)"

    local response
    response=$(call_tool "jdt_rename_element" \
        '{"elementName":"org.fixture.api.Configurable#configure","newName":"setOption","elementType":"METHOD"}') \
        || { fail "jdt_rename_element METHOD non-generic (no response)"; return; }

    local ok=true
    local status
    status=$(tool_status "$response")
    local text
    text=$(tool_text "$response")

    if [ "$status" != "SUCCESS" ]; then
        echo "  ASSERTION FAILED: status == SUCCESS (no leftover override expected here)"
        echo "    actual: ${status:-<none>} / $(echo "$text" | head -c 400)"
        ok=false
    fi

    assert_file_contains "$API_CONFIGURABLE" "void setOption(String key, String value);" \
        "interface declaration renamed on disk" || ok=false
    assert_file_contains "$SIMPLE_PROCESSOR" "public void setOption(String key, String value)" \
        "override in fixture-core renamed on disk" || ok=false
    assert_file_contains "$PROCESSOR_FACTORY" "sp.setOption(\"name\", name);" \
        "caller in fixture-core updated on disk" || ok=false

    if $ok; then pass "jdt_rename_element METHOD non-generic"; else fail "jdt_rename_element METHOD non-generic"; fi
}

# ── Test 1: jdt_move_type writes to disk, cross-project (#79) ─────────────────

test_move_type_cross_project() {
    echo "[Test 1] jdt_move_type org.fixture.core.HelperUtil -> org.fixture.core.internal (#79)"

    local old_file="$CORE_SRC/core/HelperUtil.java"
    local new_file="$CORE_SRC/core/internal/HelperUtil.java"

    if [ ! -f "$old_file" ]; then
        fail "move_type precondition" "fixture file missing: $old_file"
        return
    fi

    local response
    response=$(call_tool "jdt_move_type" \
        '{"typeName":"org.fixture.core.HelperUtil","targetPackage":"org.fixture.core.internal","updateReferences":true}') \
        || { fail "jdt_move_type (no response)"; return; }

    local ok=true
    local status
    status=$(tool_status "$response")

    if [ "$(echo "$response" | jq -r '.result.isError // false')" = "true" ]; then
        echo "  ASSERTION FAILED: tool reported isError"
        echo "    $(tool_text "$response" | head -c 400)"
        ok=false
    fi
    if [ "$status" != "SUCCESS" ]; then
        echo "  ASSERTION FAILED: status == SUCCESS"
        echo "    actual: ${status:-<none>} / $(tool_text "$response" | head -c 400)"
        ok=false
    fi

    assert_file_exists "$new_file" "moved file exists on disk" || ok=false
    assert_file_absent "$old_file" "original file removed from disk" || ok=false
    assert_file_contains "$new_file" "package org.fixture.core.internal;" \
        "package declaration updated on disk" || ok=false
    assert_file_contains "$APP_SERVICE" "import org.fixture.core.internal.HelperUtil;" \
        "cross-module reference (fixture-app) updated on disk" || ok=false
    assert_file_contains "$EXTERNAL_SERVICE" "import org.fixture.core.internal.HelperUtil;" \
        "cross-project reference (fixture-external) updated on disk" || ok=false

    if $ok; then pass "jdt_move_type cross-project"; else fail "jdt_move_type cross-project"; fi
}

# ── Test 2: package rename with subpackages (#77) ─────────────────────────────

test_rename_package_with_subpackages() {
    echo "[Test 2] jdt_rename_element PACKAGE org.fixture.core -> org.fixture.kernel, renameSubpackages=true (#77)"

    local response
    response=$(call_tool "jdt_rename_element" \
        '{"elementName":"org.fixture.core","newName":"org.fixture.kernel","elementType":"PACKAGE","renameSubpackages":true}') \
        || { fail "jdt_rename_element PACKAGE (no response)"; return; }

    local ok=true
    local status
    status=$(tool_status "$response")
    local text
    text=$(tool_text "$response")

    if [ "$(echo "$response" | jq -r '.result.isError // false')" = "true" ]; then
        echo "  ASSERTION FAILED: tool reported isError"
        echo "    $(echo "$text" | head -c 400)"
        ok=false
    fi
    if echo "$text" | grep -q "AssertionFailedException"; then
        echo "  ASSERTION FAILED: response mentions AssertionFailedException"
        echo "    $(echo "$text" | head -c 400)"
        ok=false
    fi
    if [ "$status" != "SUCCESS" ]; then
        echo "  ASSERTION FAILED: status == SUCCESS"
        echo "    actual: ${status:-<none>} / $(echo "$text" | head -c 400)"
        ok=false
    fi

    assert_file_exists "$CORE_SRC/kernel/SimpleProcessor.java" \
        "renamed package directory on disk" || ok=false
    assert_file_contains "$CORE_SRC/kernel/SimpleProcessor.java" "package org.fixture.kernel;" \
        "package declaration updated on disk" || ok=false
    assert_file_exists "$CORE_SRC/kernel/internal/InternalHelper.java" \
        "subpackage moved on disk (renameSubpackages)" || ok=false
    assert_file_contains "$CORE_SRC/kernel/internal/InternalHelper.java" \
        "package org.fixture.kernel.internal;" \
        "subpackage declaration updated on disk" || ok=false
    assert_no_java_files "$CORE_SRC/core" "old package directory emptied on disk" || ok=false

    assert_file_contains "$APP_SERVICE" "import org.fixture.kernel." \
        "cross-module imports (fixture-app) updated on disk" || ok=false
    assert_file_lacks "$APP_SERVICE" "org.fixture.core." \
        "no stale org.fixture.core reference in fixture-app" || ok=false
    assert_file_contains "$EXTERNAL_SERVICE" "import org.fixture.kernel." \
        "cross-project imports (fixture-external) updated on disk" || ok=false
    assert_file_lacks "$EXTERNAL_SERVICE" "org.fixture.core." \
        "no stale org.fixture.core reference in fixture-external" || ok=false

    if $ok; then pass "jdt_rename_element PACKAGE with subpackages"; else fail "jdt_rename_element PACKAGE with subpackages"; fi
}

# ── Test 3: jdt_encapsulate_field renders getter/setter headless (#98) ────────
# Runs AFTER test 2, so org.fixture.core has already become org.fixture.kernel.
# DataHolder therefore lives at kernel/DataHolder.java at this point.

test_encapsulate_field() {
    echo "[Test 3] jdt_encapsulate_field org.fixture.kernel.DataHolder#name (#98)"

    local holder="$CORE_SRC/kernel/DataHolder.java"

    if [ ! -f "$holder" ]; then
        fail "encapsulate_field precondition" "fixture file missing: $holder"
        return
    fi

    local response
    response=$(call_tool "jdt_encapsulate_field" \
        '{"className":"org.fixture.kernel.DataHolder","fieldName":"name"}') \
        || { fail "jdt_encapsulate_field (no response)"; return; }

    local ok=true
    local status
    status=$(tool_status "$response")
    local text
    text=$(tool_text "$response")

    if [ "$(echo "$response" | jq -r '.result.isError // false')" = "true" ]; then
        echo "  ASSERTION FAILED: tool reported isError"
        echo "    $(echo "$text" | head -c 400)"
        ok=false
    fi
    # The headless defect surfaced as a bare NullPointerException from the
    # jdt.ui code template store — never let that reach the client again.
    if echo "$text" | grep -qE "NullPointerException|ProjectTemplateStore"; then
        echo "  ASSERTION FAILED: response leaks a template-store NPE"
        echo "    $(echo "$text" | head -c 400)"
        ok=false
    fi
    if [ "$status" != "SUCCESS" ]; then
        echo "  ASSERTION FAILED: status == SUCCESS"
        echo "    actual: ${status:-<none>} / $(echo "$text" | head -c 400)"
        ok=false
    fi

    assert_file_contains "$holder" "private String name;" \
        "field made private on disk" || ok=false
    assert_file_contains "$holder" "public String getName()" \
        "getter written to disk" || ok=false
    assert_file_contains "$holder" "public void setName(" \
        "setter written to disk" || ok=false
    assert_file_contains "$holder" "return name;" \
        "getter body rendered (template store bootstrapped)" || ok=false
    assert_file_contains "$holder" "this.name = name;" \
        "setter body rendered (template store bootstrapped)" || ok=false

    if $ok; then pass "jdt_encapsulate_field"; else fail "jdt_encapsulate_field"; fi
}

test_rename_generic_interface_method
test_rename_plain_interface_method
test_move_type_cross_project
test_rename_package_with_subpackages
test_encapsulate_field

print_summary

if [ "$TESTS_FAILED" -gt 0 ]; then
    echo "Server stderr (last 30 lines):"
    tail -30 "$STDERR_FILE" 2>/dev/null || true
    exit 1
fi

exit 0
