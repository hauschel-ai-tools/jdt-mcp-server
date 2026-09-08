#!/usr/bin/env bash
# SessionStart hook (#106): the marketplace plugin's .mcp.json registers a
# server named "jdt-mcp" with command "jdt-mcp" -- without this hook, a user
# who installs the plugin from a marketplace (and never ran install.sh) gets
# an MCP server entry that resolves to nothing, which looks like success and
# fails silently on first tool call. This hook installs the launcher binary
# if it is missing, before Claude Code tries to start the server.
#
# Deliberately does NOT run install.sh's own `claude mcp add -s user jdt-mcp
# ...` step (JDTMCP_SKIP_CLAUDE=1): this plugin's .mcp.json is already the
# registration. Doing both would start a SECOND, separately-namespaced
# jdt-mcp server process (mcp__plugin_jdt-mcp-server_jdt-mcp__* next to
# mcp__jdt-mcp__*) pointed at the same launcher and the same per-directory
# Eclipse workspace -- RefactoringSupport.WORKSPACE_MUTATION_LOCK is an
# in-process ReentrantLock, it does not protect against a second JVM writing
# to the same workspace. See README section "Marketplace-Plugin" for what a
# user with an existing install.sh/user-scope registration should do.
#
# Never the reason a session fails to start -- any failure here (missing
# bash/timeout, install.sh missing/failing, no network, Java too old) is
# reported as additionalContext, never as a blocking error. Always exits 0.
set -u

PLUGIN_ROOT="${CLAUDE_PLUGIN_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
BIN_LINK="$HOME/.local/bin/jdt-mcp"
INSTALL_BIN="$HOME/.local/share/jdt-mcp/bin/jdt-mcp"

emit_context() {
  local text="$1"
  if command -v jq >/dev/null 2>&1; then
    jq -n --arg text "$text" '{hookSpecificOutput: {hookEventName: "SessionStart", additionalContext: $text}}' 2>/dev/null
  else
    printf '%s\n' "$text"
  fi
}

# Already installed (symlink from install.sh, or the install-dir binary
# directly) -- nothing to do, exit fast.
if [ -x "$BIN_LINK" ] || [ -x "$INSTALL_BIN" ]; then
  exit 0
fi

INSTALL_SCRIPT="$PLUGIN_ROOT/install.sh"
if [ ! -f "$INSTALL_SCRIPT" ]; then
  emit_context "jdt-mcp: Launcher fehlt und ${INSTALL_SCRIPT} wurde nicht gefunden (Plugin-Installation unvollständig?). Manuell installieren: curl -sSL https://github.com/hauschel-ai-tools/jdt-mcp-server/raw/main/install.sh | bash"
  exit 0
fi

if command -v timeout >/dev/null 2>&1; then
  output=$(JDTMCP_SKIP_CLAUDE=1 timeout 120 bash "$INSTALL_SCRIPT" 2>&1)
  rc=$?
else
  output=$(JDTMCP_SKIP_CLAUDE=1 bash "$INSTALL_SCRIPT" 2>&1)
  rc=$?
fi

if [ $rc -ne 0 ]; then
  tail_lines=$(printf '%s\n' "$output" | tail -3 | tr '\n' ' ')
  emit_context "jdt-mcp: automatische Installation des Launchers ist fehlgeschlagen (${tail_lines}). Java 21+ und Netzzugriff zu github.com prüfen, dann manuell: bash \"$INSTALL_SCRIPT\" (oder curl -sSL https://github.com/hauschel-ai-tools/jdt-mcp-server/raw/main/install.sh | bash)."
  exit 0
fi

emit_context "jdt-mcp: Launcher wurde automatisch installiert (${INSTALL_BIN})."
exit 0
