#!/bin/bash
#
# JDT MCP Server - Local Build Installer
#
# Installiert den JDT MCP Server aus dem lokalen Build-Output
# und konfiguriert Claude Code automatisch.
#
# Usage:
#   ./install-local.sh              # baut und installiert
#   JDTMCP_SKIP_BUILD=1 ./install-local.sh  # nur installieren (ohne Build)
#
#   Optionen via Umgebungsvariablen:
#     JDTMCP_SKIP_BUILD=1            Kein Maven-Build, vorhandenes Archiv nutzen
#     JDTMCP_INSTALL_DIR=~/my/dir    Installationsverzeichnis
#     JDTMCP_SKIP_CLAUDE=1           Claude Code Config nicht ändern
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PRODUCT_DIR="$SCRIPT_DIR/org.naturzukunft.jdt.mcp.product/target/products"
INSTALL_DIR="${JDTMCP_INSTALL_DIR:-$HOME/.local/share/jdtls-mcp}"
BIN_DIR="$HOME/.local/bin"
SKIP_BUILD="${JDTMCP_SKIP_BUILD:-0}"
SKIP_CLAUDE="${JDTMCP_SKIP_CLAUDE:-0}"

# --- Farben (nur wenn Terminal) ---
if [ -t 1 ]; then
    GREEN='\033[0;32m'
    YELLOW='\033[0;33m'
    RED='\033[0;31m'
    BOLD='\033[1m'
    NC='\033[0m'
else
    GREEN='' YELLOW='' RED='' BOLD='' NC=''
fi

info()  { echo -e "${GREEN}>>>${NC} $*"; }
warn()  { echo -e "${YELLOW}>>>${NC} $*"; }
error() { echo -e "${RED}>>>${NC} $*" >&2; exit 1; }

# --- Archiv finden ---
find_archive() {
    local os arch
    os="$(uname -s)"
    arch="$(uname -m)"

    local platform
    case "$os" in
        Linux)  platform="linux.gtk" ;;
        Darwin) platform="macosx.cocoa" ;;
        *)      error "Nicht unterstütztes Betriebssystem: $os" ;;
    esac

    case "$arch" in
        x86_64|amd64) arch="x86_64" ;;
        aarch64|arm64) arch="aarch64" ;;
        *)             error "Nicht unterstützte Architektur: $arch" ;;
    esac

    ARCHIVE="$PRODUCT_DIR/jdtls-mcp-${platform}.${arch}.tar.gz"

    if [ ! -f "$ARCHIVE" ]; then
        error "Build-Archiv nicht gefunden: $ARCHIVE\n    Erst bauen mit: ./install-local.sh (oder JDTMCP_SKIP_BUILD=0)"
    fi

    info "Archiv: $ARCHIVE"
}

# --- Installieren ---
install() {
    if [ -d "$INSTALL_DIR" ]; then
        warn "Vorherige Installation wird ersetzt: $INSTALL_DIR"
        rm -rf "$INSTALL_DIR"
    fi

    mkdir -p "$INSTALL_DIR"
    tar xzf "$ARCHIVE" -C "$INSTALL_DIR" --warning=no-unknown-keyword
    chmod +x "$INSTALL_DIR/bin/jdtls-mcp"

    # Write version file for --version flag
    local version="dev-local"
    local git_desc
    git_desc=$(git -C "$SCRIPT_DIR" describe --tags 2>/dev/null || true)
    if [ -n "$git_desc" ]; then
        version="${git_desc#v}"
    fi
    echo "$version" > "$INSTALL_DIR/.version"

    mkdir -p "$BIN_DIR"
    ln -sf "$INSTALL_DIR/bin/jdtls-mcp" "$BIN_DIR/jdtls-mcp"
    info "Installiert nach $INSTALL_DIR"
    info "Symlink: $BIN_DIR/jdtls-mcp"
}

# --- Claude Code konfigurieren ---
configure_claude() {
    if [ "$SKIP_CLAUDE" = "1" ]; then
        warn "Claude Code Konfiguration übersprungen (JDTMCP_SKIP_CLAUDE=1)"
        return
    fi

    local claude_settings="$HOME/.claude.json"
    local launcher="$INSTALL_DIR/bin/jdtls-mcp"

    if ! command -v claude &>/dev/null && [ ! -f "$claude_settings" ]; then
        warn "Claude Code nicht gefunden - überspringe Konfiguration"
        warn "Manuell konfigurieren: claude mcp add jdt-mcp $launcher"
        return
    fi

    # Bestehende jdt-mcp Config entfernen (könnte SSE/HTTP statt stdio sein)
    if command -v claude &>/dev/null; then
        claude mcp remove -s user jdt-mcp 2>/dev/null || true
    fi

    if command -v claude &>/dev/null; then
        info "Konfiguriere Claude Code..."
        claude mcp add -s user jdt-mcp "$launcher" && info "Claude Code: jdt-mcp konfiguriert (stdio)" || warn "Claude Code Konfiguration fehlgeschlagen. Manuell: claude mcp add jdt-mcp $launcher"
    else
        warn "claude CLI nicht im PATH. Manuell konfigurieren:"
        warn "  claude mcp add jdt-mcp $launcher"
    fi
}

# --- Maven Build ---
build() {
    if [ "$SKIP_BUILD" = "1" ]; then
        info "Build übersprungen (JDTMCP_SKIP_BUILD=1)"
        return
    fi

    info "Baue mit Maven..."
    export MAVEN_OPTS="-Djdk.xml.maxGeneralEntitySizeLimit=0 -Djdk.xml.entityExpansionLimit=0 -Djdk.xml.totalEntitySizeLimit=0"
    (cd "$SCRIPT_DIR" && mvn -B --no-transfer-progress clean package) || error "Maven-Build fehlgeschlagen"
    info "Build erfolgreich"
}

# --- Main ---
main() {
    echo ""
    echo -e "${BOLD}JDT MCP Server - Local Build Installer${NC}"
    echo ""

    build
    find_archive
    install
    configure_claude

    echo ""
    echo -e "${BOLD}Installation abgeschlossen!${NC}"
    echo ""
    echo "  Installation:  $INSTALL_DIR"
    echo "  Befehl:        jdtls-mcp"
    echo ""

    if [[ ":$PATH:" != *":$BIN_DIR:"* ]]; then
        echo -e "${YELLOW}Hinweis:${NC} $BIN_DIR ist nicht im PATH."
        echo "    export PATH=\"\$HOME/.local/bin:\$PATH\""
        echo ""
    fi

    echo "  Nutzung:"
    echo "    cd /dein/java-projekt && claude"
    echo ""
}

main "$@"
