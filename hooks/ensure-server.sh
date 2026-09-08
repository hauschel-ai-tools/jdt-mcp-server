#!/usr/bin/env bash
# SessionStart hook (#106): the marketplace plugin's .mcp.json registers a
# server named "jdt-mcp" with an absolute, ${HOME}-expanded command path
# (${HOME}/.local/share/jdt-mcp/bin/jdt-mcp) -- deliberately NOT the bare
# "jdt-mcp" that install.sh puts on the PATH via a symlink, because the
# process that spawns MCP servers is not guaranteed to see a PATH that
# includes ~/.local/bin (many .profile setups only extend PATH for login
# shells). Without this hook, a user who installs the plugin from a
# marketplace (and never ran install.sh) gets an MCP server entry that
# resolves to nothing, which looks like success and fails silently on first
# tool call. This hook installs the launcher binary at that exact path if it
# is missing, before Claude Code tries to start the server.
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
# install.sh itself now serializes concurrent runs (mkdir-based lock, see
# install.sh) for the case where two Claude Code sessions start at the same
# time and both find the launcher missing -- this hook does not need its own
# locking, it just has to interpret install.sh's outcome correctly (success,
# "another session is installing", or failure).
#
# Never the reason a session fails to start -- any failure here (missing
# bash/timeout, install.sh missing/failing, no network, Java too old, no
# $HOME) is reported as additionalContext, never as a blocking error. Always
# exits 0.
set -u

# set -u with an unset (not just empty) $HOME would otherwise abort on the
# very next line with "unbound variable" before any exit-0 path is reached --
# guard explicitly instead of relying on undefined behaviour.
HOME="${HOME:-}"
if [ -z "$HOME" ]; then
  printf 'jdt-mcp: $HOME ist nicht gesetzt, automatische Installation übersprungen.\n'
  exit 0
fi

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

launcher_present() {
  [ -x "$BIN_LINK" ] || [ -x "$INSTALL_BIN" ]
}

# Already installed (symlink from install.sh, or the install-dir binary that
# .mcp.json's command points at directly) -- nothing to do, exit fast.
if launcher_present; then
  exit 0
fi

INSTALL_SCRIPT="$PLUGIN_ROOT/install.sh"
if [ ! -f "$INSTALL_SCRIPT" ]; then
  emit_context "jdt-mcp: Launcher fehlt und ${INSTALL_SCRIPT} wurde nicht gefunden (Plugin-Installation unvollständig?). Manuell installieren: curl -sSL https://github.com/hauschel-ai-tools/jdt-mcp-server/raw/main/install.sh | bash"
  exit 0
fi

# GNU timeout is not guaranteed on macOS (only via Homebrew coreutils as
# gtimeout); fall back to gtimeout, then to no wrapper at all rather than
# silently skip the whole guard. install.sh's own curl calls carry
# --connect-timeout/--max-time, so "no wrapper" still has an upper bound.
run_installer() {
  if command -v timeout >/dev/null 2>&1; then
    timeout 180 bash "$INSTALL_SCRIPT"
  elif command -v gtimeout >/dev/null 2>&1; then
    gtimeout 180 bash "$INSTALL_SCRIPT"
  else
    bash "$INSTALL_SCRIPT"
  fi
}

output=$(JDTMCP_SKIP_CLAUDE=1 run_installer 2>&1)
rc=$?

if launcher_present; then
  # Pass through install.sh's own "not on PATH" warning if present -- .mcp.json
  # no longer needs PATH for this server, but a user invoking `jdt-mcp`
  # manually from a shell still does.
  path_hint=$(printf '%s\n' "$output" | grep -o '[^ ]* ist nicht im PATH\.' | head -1)
  if [ -n "$path_hint" ]; then
    emit_context "jdt-mcp: Launcher wurde automatisch installiert (${INSTALL_BIN}). Hinweis: ~/.local/bin ist nicht im PATH -- für den Befehl \"jdt-mcp\" von Hand \$HOME/.local/bin zum PATH hinzufügen (die Plugin-Registrierung selbst braucht das nicht)."
  else
    emit_context "jdt-mcp: Launcher wurde automatisch installiert (${INSTALL_BIN})."
  fi
  exit 0
fi

if [ $rc -ne 0 ]; then
  tail_lines=$(printf '%s\n' "$output" | tail -3 | tr '\n' ' ')
  if printf '%s' "$tail_lines" | grep -q "läuft bereits in einer anderen Session"; then
    emit_context "jdt-mcp: Installation läuft bereits in einer anderen Session, wird dort abgeschlossen."
  else
    emit_context "jdt-mcp: automatische Installation des Launchers ist fehlgeschlagen (${tail_lines}). Java 21+ und Netzzugriff zu github.com prüfen, dann manuell: bash \"$INSTALL_SCRIPT\" (oder curl -sSL https://github.com/hauschel-ai-tools/jdt-mcp-server/raw/main/install.sh | bash)."
  fi
  exit 0
fi

# rc=0 but launcher still missing: install.sh skipped the actual install
# because another session's lock was still held when our wait ran out.
emit_context "jdt-mcp: Installation läuft offenbar bereits in einer anderen Session und war beim Warten-Timeout noch nicht fertig. Session einfach normal starten -- der Server ist verfügbar, sobald die andere Installation durchgelaufen ist."
exit 0
