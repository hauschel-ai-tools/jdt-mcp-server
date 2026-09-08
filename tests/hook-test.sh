#!/usr/bin/env bash
# Regression tests for the marketplace plugin's SessionStart hook (#106) and
# install.sh's legacy-cleanup / concurrency-lock logic (PR #110 review).
#
# Every test runs against an isolated, temporary $HOME -- Fred's real
# installation (~/.local/share/jdt-mcp, ~/.local/bin/jdt-mcp, his `claude mcp`
# config) is never touched. Network access is avoided throughout via
# JDTMCP_DRY_RUN=1 (install.sh installs a stub launcher instead of
# downloading a release), so this needs no built product and no internet.
#
# Usage: tests/hook-test.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
HOOK_SCRIPT="$PROJECT_ROOT/hooks/ensure-server.sh"
INSTALL_SCRIPT="$PROJECT_ROOT/install.sh"

# shellcheck source=lib/mcp-helpers.sh
source "$SCRIPT_DIR/lib/mcp-helpers.sh"

# ── Helpers ──────────────────────────────────────────────────────────────────

# A PATH with only the commands install.sh/ensure-server.sh actually need,
# built from symlinks to this system's real binaries -- but never java, so
# tests can simulate "Java 21+ missing" deterministically instead of relying
# on the CI runner happening to lack Java.
build_bin_dir() {
    local dir; dir=$(mktemp -d)
    local cmd
    for cmd in bash mkdir uname cat sed tr head mktemp chmod ln rm tail \
               printf jq env dirname basename cp grep cut stat date sleep \
               timeout; do
        local p; p=$(command -v "$cmd" 2>/dev/null) || continue
        ln -sf "$p" "$dir/$cmd"
    done
    echo "$dir"
}

NO_JAVA_BIN="$(build_bin_dir)"

# A `claude` stub that records every invocation to a marker file, so tests can
# assert the hook never triggers `claude mcp add` itself (JDTMCP_SKIP_CLAUDE=1
# must reach install.sh -- see PR #110 review, finding on double registration).
make_claude_stub() {
    local stubdir marker
    stubdir=$(mktemp -d)
    marker=$(mktemp -u)
    cat > "$stubdir/claude" <<EOF
#!/bin/sh
echo "CALLED: \$*" >> "$marker"
exit 0
EOF
    chmod +x "$stubdir/claude"
    echo "$stubdir|$marker"
}

launcher_exists() {
    local home="$1"
    [ -x "$home/.local/bin/jdt-mcp" ] || [ -x "$home/.local/share/jdt-mcp/bin/jdt-mcp" ]
}

# ── Tests ────────────────────────────────────────────────────────────────────

test_launcher_present() {
    echo "[Test 1] Launcher vorhanden -> Hook tut nichts, schneller Exit 0"
    local home; home=$(mktemp -d)
    mkdir -p "$home/.local/share/jdt-mcp/bin"
    cat > "$home/.local/share/jdt-mcp/bin/jdt-mcp" <<'EOF'
#!/bin/sh
exit 0
EOF
    chmod +x "$home/.local/share/jdt-mcp/bin/jdt-mcp"

    local out rc=0
    out=$(HOME="$home" CLAUDE_PLUGIN_ROOT="$PROJECT_ROOT" PATH="/usr/bin:/bin" \
        bash "$HOOK_SCRIPT" 2>&1) || rc=$?

    if [ "$rc" -eq 0 ] && [ -z "$out" ]; then
        pass "Launcher vorhanden: kein Output, Exit 0"
    else
        echo "  rc=$rc out=$out"
        fail "Launcher vorhanden: kein Output, Exit 0"
    fi
}

test_launcher_missing_installs() {
    echo "[Test 2] Launcher fehlt -> install.sh (Dry-Run) installiert, claude NICHT aufgerufen"
    local home; home=$(mktemp -d)
    local stub_info stubdir marker
    stub_info=$(make_claude_stub)
    stubdir="${stub_info%|*}"
    marker="${stub_info#*|}"

    local out rc=0
    out=$(HOME="$home" CLAUDE_PLUGIN_ROOT="$PROJECT_ROOT" \
        PATH="$stubdir:/usr/bin:/bin" JDTMCP_DRY_RUN=1 \
        bash "$HOOK_SCRIPT" 2>&1) || rc=$?

    local ok=true
    [ "$rc" -eq 0 ] || { echo "  rc=$rc"; ok=false; }
    launcher_exists "$home" || { echo "  Launcher wurde nicht angelegt"; ok=false; }
    printf '%s' "$out" | grep -q "installiert" || { echo "  Keine Erfolgsmeldung im Output: $out"; ok=false; }
    if [ -f "$marker" ]; then
        echo "  claude-Stub wurde aufgerufen (JDTMCP_SKIP_CLAUDE griff nicht): $(cat "$marker")"
        ok=false
    fi

    if $ok; then pass "Launcher fehlt: Dry-Run-Install, keine claude-Registrierung"; else fail "Launcher fehlt: Dry-Run-Install, keine claude-Registrierung"; fi
}

test_no_java() {
    echo "[Test 3] Kein Java erreichbar -> saubere additionalContext-Meldung, kein Launcher, Exit 0"
    local home; home=$(mktemp -d)
    local stub_info stubdir
    stub_info=$(make_claude_stub)
    stubdir="${stub_info%|*}"

    local out rc=0
    out=$(env -u JAVA_HOME HOME="$home" CLAUDE_PLUGIN_ROOT="$PROJECT_ROOT" \
        PATH="$stubdir:$NO_JAVA_BIN" JDTMCP_DRY_RUN=1 \
        bash "$HOOK_SCRIPT" 2>&1) || rc=$?

    local ok=true
    [ "$rc" -eq 0 ] || { echo "  rc=$rc"; ok=false; }
    launcher_exists "$home" && { echo "  Launcher wurde trotz fehlendem Java angelegt"; ok=false; }
    printf '%s' "$out" | grep -qi "java" || { echo "  Keine Java-Meldung im Output: $out"; ok=false; }

    if $ok; then pass "Kein Java: saubere Meldung statt Absturz"; else fail "Kein Java: saubere Meldung statt Absturz"; fi
}

test_home_unset() {
    echo "[Test 4] \$HOME unset (nicht nur leer) -> kein 'unbound variable'-Abbruch, Exit 0"
    local out rc=0
    out=$(env -u HOME CLAUDE_PLUGIN_ROOT="$PROJECT_ROOT" PATH="/usr/bin:/bin" \
        bash "$HOOK_SCRIPT" 2>&1) || rc=$?

    if [ "$rc" -eq 0 ]; then
        pass "\$HOME unset: Exit 0 statt Skript-Abbruch"
    else
        echo "  rc=$rc out=$out"
        fail "\$HOME unset: Exit 0 statt Skript-Abbruch"
    fi
}

test_lock_busy() {
    echo "[Test 5] Installations-Lock von einer 'anderen Session' gehalten -> Hook wartet kurz, meldet, Exit 0"
    local home; home=$(mktemp -d)
    mkdir -p "$home/.local/share/jdt-mcp.lock"
    local stub_info stubdir marker
    stub_info=$(make_claude_stub)
    stubdir="${stub_info%|*}"
    marker="${stub_info#*|}"

    local out rc=0
    out=$(HOME="$home" CLAUDE_PLUGIN_ROOT="$PROJECT_ROOT" \
        PATH="$stubdir:/usr/bin:/bin" JDTMCP_DRY_RUN=1 JDTMCP_LOCK_WAIT_MAX=2 \
        bash "$HOOK_SCRIPT" 2>&1) || rc=$?

    local ok=true
    [ "$rc" -eq 0 ] || { echo "  rc=$rc"; ok=false; }
    launcher_exists "$home" && { echo "  Launcher wurde trotz gehaltenem Lock angelegt (Race?)"; ok=false; }
    printf '%s' "$out" | grep -qi "Session" || { echo "  Keine Lock-Meldung im Output: $out"; ok=false; }
    [ -f "$marker" ] && { echo "  claude-Stub wurde trotzdem aufgerufen"; ok=false; }

    if $ok; then pass "Lock belegt: wartet, meldet, installiert nicht parallel"; else fail "Lock belegt: wartet, meldet, installiert nicht parallel"; fi
}

test_lock_stale_removed() {
    echo "[Test 6] Verwaister Lock (aelter als Stale-Schwelle) wird entfernt, Installation laeuft normal"
    local home; home=$(mktemp -d)
    mkdir -p "$home/.local/share/jdt-mcp.lock"
    touch -t 202501010000 "$home/.local/share/jdt-mcp.lock"

    local out rc=0
    out=$(HOME="$home" JDTMCP_DRY_RUN=1 JDTMCP_SKIP_CLAUDE=1 JDTMCP_LOCK_STALE_AFTER=5 JDTMCP_LOCK_WAIT_MAX=2 \
        PATH="/usr/bin:/bin" bash "$INSTALL_SCRIPT" 2>&1) || rc=$?

    local ok=true
    [ "$rc" -eq 0 ] || { echo "  rc=$rc"; ok=false; }
    launcher_exists "$home" || { echo "  Launcher wurde nicht angelegt"; ok=false; }
    printf '%s' "$out" | grep -qi "verwaist" || { echo "  Kein Hinweis auf verwaisten Lock: $out"; ok=false; }
    [ -d "$home/.local/share/jdt-mcp.lock" ] && { echo "  Lock-Verzeichnis nicht aufgeraeumt"; ok=false; }

    if $ok; then pass "Verwaister Lock: wird entfernt, Installation laeuft"; else fail "Verwaister Lock: wird entfernt, Installation laeuft"; fi
}

test_legacy_cleanup_present() {
    echo "[Test 7] Alt-Installation (jdtls-mcp) vorhanden -> wird von install.sh entfernt"
    local home; home=$(mktemp -d)
    mkdir -p "$home/.local/share/jdtls-mcp/bin" "$home/.local/bin"
    touch "$home/.local/share/jdtls-mcp/bin/jdtls-mcp"
    ln -sf "$home/.local/share/jdtls-mcp/bin/jdtls-mcp" "$home/.local/bin/jdtls-mcp"

    local out rc=0
    out=$(HOME="$home" JDTMCP_DRY_RUN=1 JDTMCP_SKIP_CLAUDE=1 PATH="/usr/bin:/bin" \
        bash "$INSTALL_SCRIPT" 2>&1) || rc=$?

    local ok=true
    [ "$rc" -eq 0 ] || { echo "  rc=$rc"; ok=false; }
    [ -e "$home/.local/share/jdtls-mcp" ] && { echo "  Alt-Installationsverzeichnis noch vorhanden"; ok=false; }
    [ -e "$home/.local/bin/jdtls-mcp" ] && { echo "  Alter Symlink noch vorhanden"; ok=false; }
    printf '%s' "$out" | grep -q "Alt-Installation" || { echo "  Kein Cleanup-Hinweis im Output: $out"; ok=false; }
    launcher_exists "$home" || { echo "  Neue Installation fehlt"; ok=false; }

    if $ok; then pass "Alt-Installation vorhanden: wird bereinigt"; else fail "Alt-Installation vorhanden: wird bereinigt"; fi
}

test_legacy_cleanup_absent() {
    echo "[Test 8] Keine Alt-Installation -> kein Cleanup-Hinweis, normale Installation"
    local home; home=$(mktemp -d)

    local out rc=0
    out=$(HOME="$home" JDTMCP_DRY_RUN=1 JDTMCP_SKIP_CLAUDE=1 PATH="/usr/bin:/bin" \
        bash "$INSTALL_SCRIPT" 2>&1) || rc=$?

    local ok=true
    [ "$rc" -eq 0 ] || { echo "  rc=$rc"; ok=false; }
    printf '%s' "$out" | grep -q "Alt-Installation" && { echo "  Faelschlicher Cleanup-Hinweis ohne Alt-Installation: $out"; ok=false; }
    launcher_exists "$home" || { echo "  Installation fehlt"; ok=false; }

    if $ok; then pass "Keine Alt-Installation: keine falsche Meldung"; else fail "Keine Alt-Installation: keine falsche Meldung"; fi
}

echo ""
echo "════════════════════════════════════════"
echo " Running hook / install.sh regression tests"
echo "════════════════════════════════════════"
echo ""

test_launcher_present
test_launcher_missing_installs
test_no_java
test_home_unset
test_lock_busy
test_lock_stale_removed
test_legacy_cleanup_present
test_legacy_cleanup_absent

print_summary

if [ "$TESTS_FAILED" -gt 0 ]; then
    exit 1
fi
exit 0
