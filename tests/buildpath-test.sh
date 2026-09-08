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
# LOCAL MAVEN REPOSITORY: the #116 scenario only arises when the reactor siblings are
# resolvable as JARs, so the fixture modules are installed with their real coordinates
# (org.fixture:*:1.0.0-SNAPSHOT) into the local repository. Any pre-existing org/fixture tree
# is moved aside first and restored on exit; artifacts installed by this run are removed
# again, so the repository is left as it was found.
#
# The repository is NOT isolated from the developer's or the CI cache: the server resolves
# dependencies by shelling out to 'mvn dependency:build-classpath' itself
# (ProjectImporter.addMavenDependencies), which uses whatever local repository Maven is
# configured with. Passing -Dmaven.repo.local here would only redirect this script's own
# install and leave the server looking somewhere else. The path is therefore asked of Maven
# instead of overridden. Real isolation needs a repository override the server respects: #124.
#
# NO NETWORK: if the install fails, the #116 tests are skipped instead of failing the suite -
# nothing about the server is broken then. Set JDTMCP_REQUIRE_MAVEN=1 to make it an error.
#
# Requires: bash, jq, mkfifo, mvn (the server shells out to 'mvn dependency:build-classpath')
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

# Ask Maven for the local repository rather than assuming ~/.m2/repository: whatever it
# answers is what the server's own 'mvn' shell-out will use too, so backup and restore below
# act on the directory that actually gets written.
M2_REPO="$(mvn -B -q help:evaluate -Dexpression=settings.localRepository -DforceStdout 2>/dev/null | tail -1 || true)"
if [ -z "$M2_REPO" ] || [ ! -d "$M2_REPO" ]; then
    M2_REPO="$HOME/.m2/repository"
fi
FIXTURE_ARTIFACTS_DIR="$M2_REPO/org/fixture"
FIXTURE_ARTIFACTS_PREEXISTING=0
M2_BACKUP=""
MAVEN_FIXTURES_AVAILABLE=0

echo "Fixtures at: $FIXTURE_WORK_DIR"

# Leaves the local repository exactly as the script found it.
restore_local_repository() {
    if [ "$FIXTURE_ARTIFACTS_PREEXISTING" = "1" ]; then
        if [ -n "$M2_BACKUP" ] && [ -d "$M2_BACKUP/org-fixture" ]; then
            rm -rf "$FIXTURE_ARTIFACTS_DIR"
            mkdir -p "$(dirname "$FIXTURE_ARTIFACTS_DIR")"
            mv "$M2_BACKUP/org-fixture" "$FIXTURE_ARTIFACTS_DIR"
            echo "Restored the pre-existing org.fixture artifacts in $M2_REPO"
        fi
    elif [ -d "$FIXTURE_ARTIFACTS_DIR" ]; then
        rm -rf "$FIXTURE_ARTIFACTS_DIR"
        echo "Removed the org.fixture artifacts installed by this run from $M2_REPO"
    fi
    if [ -n "$M2_BACKUP" ] && [ -d "$M2_BACKUP" ]; then
        rm -rf "$M2_BACKUP"
    fi
    return 0
}

CLEANUP_DONE=0
cleanup_all() {
    if [ "$CLEANUP_DONE" = "1" ]; then
        return 0
    fi
    CLEANUP_DONE=1
    cleanup
    restore_local_repository
    [ -d "$FIXTURE_WORK_DIR" ] && rm -rf "$FIXTURE_WORK_DIR"
    [ -d "$SERVER_CWD" ] && rm -rf "$SERVER_CWD"
    [ -f "$RPC_ID_FILE" ] && rm -f "$RPC_ID_FILE"
    return 0
}

# Bash does not run the EXIT trap when a signal interrupts a foreground child - and this
# script spends most of its time in `sleep` inside the poll loops. Without an INT/TERM trap,
# Ctrl-C would leave the backed-up org/fixture tree in a temp directory and the local
# repository without it. The handler exits itself so the script cannot carry on with a
# cleaned-up workspace; CLEANUP_DONE keeps the EXIT trap that follows from repeating the work.
on_signal() {
    cleanup_all
    trap - EXIT
    exit "$1"
}
trap cleanup_all EXIT
trap 'on_signal 130' INT
trap 'on_signal 143' TERM

# ── Install the reactor siblings into the local Maven repository ──────────────
# Without this 'mvn dependency:build-classpath' fails for fixture-app and the whole #116
# scenario (sibling resolved to a JAR) cannot arise. fixture-broken is left out on purpose:
# it has deliberate compile errors and would fail the install.

echo ""
echo "Installing fixture-api and fixture-core into $M2_REPO ..."
if [ -d "$FIXTURE_ARTIFACTS_DIR" ]; then
    FIXTURE_ARTIFACTS_PREEXISTING=1
    M2_BACKUP="$(mktemp -d)"
    mv "$FIXTURE_ARTIFACTS_DIR" "$M2_BACKUP/org-fixture"
    echo "  moved pre-existing org.fixture artifacts aside (restored on exit)"
fi

if mvn -q -B -f "$PARENT_DIR/pom.xml" -pl fixture-api,fixture-core -am \
        -DskipTests install \
        > "$FIXTURE_WORK_DIR/mvn-install.log" 2>&1; then
    MAVEN_FIXTURES_AVAILABLE=1
    echo "  installed"
else
    echo "  WARNING: 'mvn install' of the fixture siblings failed - no network, broken mirror?"
    echo "  The #116 tests need resolvable sibling JARs and will be SKIPPED."
    tail -10 "$FIXTURE_WORK_DIR/mvn-install.log" | sed 's/^/    /'
    if [ "${JDTMCP_REQUIRE_MAVEN:-0}" = "1" ]; then
        echo "ERROR: JDTMCP_REQUIRE_MAVEN=1 - treating the failed install as an error" >&2
        exit 1
    fi
fi

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

compilation_errors() {
    tool_text "$(call_tool "jdt_get_compilation_errors" "$(jq -cn --arg p "$1" '{"projectName":$p}')")"
}

# Auto-building reacts to a classpath change asynchronously, so poll instead of sleeping.
LAST_ERRORS_JSON=""
wait_for_error_count() {
    local project="$1"
    local expected="$2"
    local timeout="${3:-90}"
    local elapsed=0
    local count=""
    while [ "$elapsed" -lt "$timeout" ]; do
        LAST_ERRORS_JSON=$(compilation_errors "$project")
        count=$(echo "$LAST_ERRORS_JSON" | jq -r '.errorCount // -1')
        if [ "$count" = "$expected" ]; then
            return 0
        fi
        sleep 3
        elapsed=$((elapsed + 3))
    done
    echo "  (errorCount for $project stayed at ${count:-?}, expected $expected after ${timeout}s)"
    return 1
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
echo "Importing fixtures..."
import_badcp=$(call_tool "jdt_import_project" "$(jq -cn --arg p "$BADCP_DIR" '{"path":$p}')")
echo "  fixture-badclasspath: $(tool_status "$import_badcp")"

REQUIRED_PROJECTS="fixture-badclasspath"
if [ "$MAVEN_FIXTURES_AVAILABLE" = "1" ]; then
    import_parent=$(call_tool "jdt_import_project" "$(jq -cn --arg p "$PARENT_DIR" '{"path":$p}')")
    echo "  fixture-parent:       $(tool_status "$import_parent")"
    REQUIRED_PROJECTS="$REQUIRED_PROJECTS fixture-core fixture-app fixture-api"
fi

projects=$(tool_text "$(call_tool "jdt_list_projects" '{}')")
for required in $REQUIRED_PROJECTS; do
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

# ── Test 1-3: build path problems are reported, with their message (#115) ─────

echo "Test 1: jdt_get_compilation_errors reports the missing required library (#115)"
errors_json=$(compilation_errors "fixture-badclasspath")

buildpath_messages=$(echo "$errors_json" \
    | jq -r '[.errors[]?, .warnings[]?] | map(select(.kind == "BUILDPATH")) | .[].message' 2>/dev/null || true)

if echo "$buildpath_messages" | grep -qi "missing required library"; then
    pass "build path problem is reported with its own message"
    echo "    message: $(echo "$buildpath_messages" | head -1)"
else
    fail "build path problem is reported with its own message" \
         "no BUILDPATH entry mentioning 'missing required library'"
    echo "    response: $(echo "$errors_json" | jq -c '{errorCount, buildPathErrorCount, errors: [.errors[]? | {kind, message}]}' 2>/dev/null || echo "$errors_json")"
fi

echo "Test 2: buildPathErrorCount explains part of errorCount (#115)"
bp_count=$(echo "$errors_json" | jq -r '.buildPathErrorCount // -1')
err_count=$(echo "$errors_json" | jq -r '.errorCount // -1')
bp_errors=$(echo "$errors_json" | jq -r '[.errors[]? | select(.kind == "BUILDPATH")] | length')
if [ "$bp_count" -ge 1 ] && [ "$bp_count" = "$bp_errors" ] && [ "$err_count" -ge "$bp_count" ]; then
    pass "buildPathErrorCount=$bp_count equals the BUILDPATH entries in errors, errorCount=$err_count"
else
    fail "buildPathErrorCount equals the number of BUILDPATH errors and is part of errorCount" \
         "buildPathErrorCount=$bp_count errorCount=$err_count buildpathErrorsInList=$bp_errors"
fi

echo "Test 3: every problem carries a kind, Java problems included (#115)"
java_problems=$(echo "$errors_json" | jq -r '[.errors[]?, .warnings[]?] | map(select(.kind == "JAVA")) | length')
without_kind=$(echo "$errors_json" | jq -r '[.errors[]?, .warnings[]?] | map(select(.kind == null)) | length')
first_kind=$(echo "$errors_json" | jq -r '.errors[0].kind // empty')
if [ "$first_kind" = "BUILDPATH" ] && [ "$java_problems" -ge 1 ] && [ "$without_kind" = "0" ]; then
    pass "BUILDPATH listed first, $java_problems Java problem(s) tagged kind=JAVA, none untagged"
else
    fail "every problem carries a kind and BUILDPATH is listed first" \
         "errors[0].kind=$first_kind javaProblems=$java_problems withoutKind=$without_kind"
fi

# ── Test 4-10: reactor siblings stay project references (#116) ────────────────

if [ "$MAVEN_FIXTURES_AVAILABLE" != "1" ]; then
    echo ""
    for skipped in \
        "jdt_maven_update_project keeps the sibling as a project reference" \
        "the sibling's local-repository JAR is not on the classpath" \
        "no 'duplicate entry' warning in the server log" \
        "the update reports which JARs the workspace projects superseded" \
        "a second update stays idempotent" \
        "the sibling is still compiled from source" \
        "a transitive sibling gets a project reference instead of vanishing"; do
        skip "$skipped" "fixture siblings could not be installed into the local Maven repository"
    done
else

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

echo "Test 5: the sibling's local-repository JAR is not on the classpath (#116)"
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
classpath_before_second=$(cat "$APP_CLASSPATH")
call_tool "jdt_maven_update_project" '{"projectName":"fixture-app"}' > /dev/null
if [ "$classpath_before_second" = "$(cat "$APP_CLASSPATH")" ]; then
    pass "classpath byte-identical after a second jdt_maven_update_project"
else
    fail "classpath byte-identical after a second jdt_maven_update_project"
    diff <(echo "$classpath_before_second") "$APP_CLASSPATH" | sed 's/^/    /' || true
fi

echo "Test 9: the sibling is still compiled from source, not from the JAR (#116)"
if wait_for_error_count "fixture-app" 0; then
    app_bp_count=$(echo "$LAST_ERRORS_JSON" | jq -r '.buildPathErrorCount // -1')
    if [ "$app_bp_count" = "0" ]; then
        pass "fixture-app has no compilation and no build path errors after the update"
    else
        fail "fixture-app has no build path errors after the update" "buildPathErrorCount=$app_bp_count"
    fi
else
    fail "fixture-app has no compilation errors after the update"
    echo "$LAST_ERRORS_JSON" | jq -c '[.errors[]? | {kind, message}]' | sed 's/^/    /'
fi

# Regression for the review finding on this PR: the filter used to *drop* every JAR that
# matched a workspace project. fixture-app depends on fixture-core, which depends on
# fixture-api -- so fixture-api is resolved transitively and is NOT in fixture-app's POM.
# With the project reference missing, nothing added it back (setupInterProjectDependencies
# only parses direct POM dependencies) and fixture-app stopped compiling.
echo "Test 10: a transitive sibling gets a project reference instead of vanishing (#116)"
sed -i '/kind="src" path="\/fixture-api"/d' "$APP_CLASSPATH"
call_tool "jdt_refresh_project" '{"projectName":"fixture-app"}' > /dev/null

precondition_ok=0
elapsed=0
while [ "$elapsed" -lt 60 ]; do
    app_classpath_json=$(tool_text "$(call_tool "jdt_get_classpath" '{"projectName":"fixture-app"}')")
    if ! echo "$app_classpath_json" | jq -r '.projectDependencies[]?.path // empty' | grep -q "fixture-api"; then
        precondition_ok=1
        break
    fi
    sleep 3
    elapsed=$((elapsed + 3))
done

if [ "$precondition_ok" != "1" ]; then
    fail "transitive sibling regression" \
         "precondition not reached: JDT still reports fixture-api as a project dependency"
else
    update10=$(tool_text "$(call_tool "jdt_maven_update_project" '{"projectName":"fixture-app"}')")
    echo "    workspaceProjectsPreferred: $(echo "$update10" | jq -c '.workspaceProjectsPreferred // []')"

    failed=0
    assert_entry_count "$APP_CLASSPATH" "src" 'path="/fixture-api"' 1 \
        "exactly one project reference to the transitive sibling fixture-api" || failed=1
    assert_entry_count "$APP_CLASSPATH" "src" 'path="/fixture-core"' 1 \
        "still exactly one project reference to fixture-core" || failed=1
    assert_entry_count "$APP_CLASSPATH" "lib" 'fixture-api-' 0 \
        "no fixture-api JAR from the local Maven repository" || failed=1

    if [ "$failed" -eq 0 ] && wait_for_error_count "fixture-app" 0; then
        pass "transitive sibling fixture-api restored as a project reference, errorCount 0"
    else
        fail "transitive sibling fixture-api restored as a project reference, errorCount 0"
        echo "$LAST_ERRORS_JSON" | jq -c '[.errors[]? | {kind, message}]' | sed 's/^/    /' || true
    fi
fi

fi  # MAVEN_FIXTURES_AVAILABLE

print_summary

if [ "$TESTS_FAILED" -gt 0 ]; then
    exit 1
fi
exit 0
