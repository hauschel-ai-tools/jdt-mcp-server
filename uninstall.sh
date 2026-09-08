#!/bin/bash
#
# JDT MCP Server - Uninstaller
#
# Usage:
#   curl -sSL https://github.com/hauschel-ai-tools/jdt-mcp-server/raw/main/uninstall.sh | bash
#
#   Optionen via Umgebungsvariablen:
#     JDTMCP_INSTALL_DIR=~/my/dir  Installationsverzeichnis (falls angepasst)
#

set -euo pipefail

INSTALL_DIR="${JDTMCP_INSTALL_DIR:-$HOME/.local/share/jdt-mcp}"
BIN_LINK="$HOME/.local/bin/jdt-mcp"

# Alt-Installation vor der Umbenennung (Issue #107): Launcher hieß bis v1.1.0 "jdtls-mcp"
LEGACY_INSTALL_DIR="$HOME/.local/share/jdtls-mcp"
LEGACY_BIN_LINK="$HOME/.local/bin/jdtls-mcp"

# --- Farben (nur wenn Terminal) ---
if [ -t 1 ]; then
    GREEN='\033[0;32m'
    YELLOW='\033[0;33m'
    BOLD='\033[1m'
    NC='\033[0m'
else
    GREEN='' YELLOW='' BOLD='' NC=''
fi

info()  { echo -e "${GREEN}>>>${NC} $*"; }
warn()  { echo -e "${YELLOW}>>>${NC} $*"; }

echo ""
echo -e "${BOLD}JDT MCP Server - Uninstaller${NC}"
echo ""

# Version anzeigen falls vorhanden
if [ -x "$INSTALL_DIR/bin/jdt-mcp" ]; then
    version=$("$INSTALL_DIR/bin/jdt-mcp" --version 2>/dev/null || echo "unbekannt")
    info "Gefunden: $version"
fi

# Claude Code Konfiguration entfernen
if command -v claude &>/dev/null; then
    claude mcp remove -s user jdt-mcp 2>/dev/null && info "Claude Code: jdt-mcp entfernt" || true
fi

# Installation entfernen
if [ -d "$INSTALL_DIR" ]; then
    rm -rf "$INSTALL_DIR"
    info "Installation entfernt: $INSTALL_DIR"
else
    warn "Keine Installation gefunden unter $INSTALL_DIR"
fi

# Symlink entfernen
if [ -L "$BIN_LINK" ] || [ -f "$BIN_LINK" ]; then
    rm -f "$BIN_LINK"
    info "Symlink entfernt: $BIN_LINK"
fi

# Alt-Installation von vor der Umbenennung (Issue #107) mit entfernen, falls noch vorhanden
if [ -e "$LEGACY_INSTALL_DIR" ] && [ "$LEGACY_INSTALL_DIR" != "$INSTALL_DIR" ]; then
    rm -rf "$LEGACY_INSTALL_DIR"
    info "Alt-Installation entfernt: $LEGACY_INSTALL_DIR (Launcher hieß vor der Umbenennung 'jdtls-mcp')"
fi
if [ -L "$LEGACY_BIN_LINK" ] || [ -f "$LEGACY_BIN_LINK" ]; then
    rm -f "$LEGACY_BIN_LINK"
    info "Alter Symlink entfernt: $LEGACY_BIN_LINK"
fi

echo ""
echo -e "${BOLD}Deinstallation abgeschlossen.${NC}"
echo ""
