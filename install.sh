#!/bin/bash
#
# JDT MCP Server - Installer
#
# Installiert den JDT MCP Server und konfiguriert Claude Code automatisch.
#
# Usage:
#   curl -sSL https://github.com/hauschel-ai-tools/jdt-mcp-server/raw/main/install.sh | bash
#
#   Optionen via Umgebungsvariablen:
#     JDTMCP_VERSION=1.0.0            Version (default: latest)
#     JDTMCP_INSTALL_DIR=~/my/dir     Installationsverzeichnis
#     JDTMCP_SKIP_CLAUDE=1            Claude Code Config nicht ändern
#

set -euo pipefail

# --- Konfiguration ---
REPO="hauschel-ai-tools/jdt-mcp-server"
BASE_URL="https://github.com"
API_PREFIX="https://api.github.com/repos"
INSTALL_DIR="${JDTMCP_INSTALL_DIR:-$HOME/.local/share/jdt-mcp}"
BIN_DIR="$HOME/.local/bin"
SKIP_CLAUDE="${JDTMCP_SKIP_CLAUDE:-0}"

# Alt-Installation vor der Umbenennung (Issue #107): Launcher hieß bis v1.1.0 "jdtls-mcp"
LEGACY_INSTALL_DIR="$HOME/.local/share/jdtls-mcp"
LEGACY_BIN_LINK="$BIN_DIR/jdtls-mcp"
LEGACY_CLEANED=0

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

# --- OS und Architektur erkennen ---
detect_platform() {
    local os arch
    os="$(uname -s)"
    arch="$(uname -m)"

    case "$os" in
        Linux)  PLATFORM="linux.gtk" ;;
        Darwin) PLATFORM="macosx.cocoa" ;;
        *)      error "Nicht unterstütztes Betriebssystem: $os" ;;
    esac

    case "$arch" in
        x86_64|amd64) ARCH="x86_64" ;;
        aarch64|arm64) ARCH="aarch64" ;;
        *)             error "Nicht unterstützte Architektur: $arch" ;;
    esac

    ARCHIVE_NAME="jdt-mcp-${PLATFORM}.${ARCH}"

    case "$os" in
        Linux)  ARCHIVE_EXT="tar.gz" ;;
        Darwin) ARCHIVE_EXT="tar.gz" ;;
    esac

    info "Plattform: ${PLATFORM}.${ARCH}"
}

# --- Java prüfen ---
check_java() {
    local java_cmd=""
    if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
        java_cmd="$JAVA_HOME/bin/java"
    elif command -v java &>/dev/null; then
        java_cmd="java"
    else
        error "Java nicht gefunden. Bitte Java 21+ installieren und JAVA_HOME setzen."
    fi

    local version
    version=$("$java_cmd" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
    if [ "$version" -lt 21 ] 2>/dev/null; then
        error "Java $version gefunden, aber Java 21+ wird benötigt."
    fi
    info "Java $version gefunden"
}

# --- Version ermitteln ---
resolve_version() {
    if [ -n "${JDTMCP_VERSION:-}" ]; then
        VERSION="$JDTMCP_VERSION"
        info "Version: $VERSION (manuell gesetzt)"
        return
    fi

    info "Ermittle neueste Version..."
    local api_url="${API_PREFIX}/${REPO}/releases/latest"
    local response
    response=$(curl -sSf "$api_url" 2>/dev/null) || error "Konnte Releases nicht abrufen. Ist $BASE_URL erreichbar?"

    VERSION=$(echo "$response" | grep -o '"tag_name": *"[^"]*"' | head -1 | sed 's/.*"\([^"]*\)"$/\1/')
    if [ -z "$VERSION" ]; then
        error "Kein Release gefunden unter $BASE_URL/$REPO/releases"
    fi
    # Strip leading 'v' if present
    VERSION="${VERSION#v}"
    info "Version: $VERSION (latest)"
}

# --- Alt-Installation (jdtls-mcp) bereinigen ---
cleanup_legacy() {
    if [ -e "$LEGACY_INSTALL_DIR" ] && [ "$LEGACY_INSTALL_DIR" != "$INSTALL_DIR" ]; then
        warn "Alt-Installation gefunden: $LEGACY_INSTALL_DIR (Launcher hieß vor der Umbenennung 'jdtls-mcp') - wird entfernt"
        rm -rf "$LEGACY_INSTALL_DIR"
        LEGACY_CLEANED=1
    fi
    if [ -L "$LEGACY_BIN_LINK" ] || [ -e "$LEGACY_BIN_LINK" ]; then
        warn "Alter Symlink gefunden: $LEGACY_BIN_LINK - wird entfernt"
        rm -f "$LEGACY_BIN_LINK"
        LEGACY_CLEANED=1
    fi
}

# --- Download & Installation ---
install() {
    local download_url="$BASE_URL/$REPO/releases/download/v${VERSION}/${ARCHIVE_NAME}.${ARCHIVE_EXT}"

    info "Download: $download_url"

    TMP_DIR=$(mktemp -d)
    trap 'rm -rf "$TMP_DIR"' EXIT

    local archive="$TMP_DIR/${ARCHIVE_NAME}.${ARCHIVE_EXT}"
    curl -sSfL -o "$archive" "$download_url" || error "Download fehlgeschlagen. Existiert Version v${VERSION}?"

    # Vorherige Installation prüfen
    if [ -d "$INSTALL_DIR" ]; then
        local old_version="unbekannt"
        if [ -x "$INSTALL_DIR/bin/jdt-mcp" ]; then
            old_version=$("$INSTALL_DIR/bin/jdt-mcp" --version 2>/dev/null | sed 's/JDT MCP Server //' || echo "unbekannt")
        fi
        if [ "$old_version" = "$VERSION" ]; then
            info "Version $VERSION ist bereits installiert - wird neu installiert"
        else
            info "Update: $old_version -> $VERSION"
        fi
        rm -rf "$INSTALL_DIR"
    fi

    mkdir -p "$INSTALL_DIR"

    info "Entpacke nach $INSTALL_DIR ..."
    case "$ARCHIVE_EXT" in
        tar.gz) tar xzf "$archive" -C "$INSTALL_DIR" --warning=no-unknown-keyword ;;
        zip)    unzip -qo "$archive" -d "$INSTALL_DIR" ;;
    esac

    # Launcher ausführbar machen
    chmod +x "$INSTALL_DIR/bin/jdt-mcp"

    # Symlink in ~/.local/bin
    mkdir -p "$BIN_DIR"
    ln -sf "$INSTALL_DIR/bin/jdt-mcp" "$BIN_DIR/jdt-mcp"
    info "Symlink: $BIN_DIR/jdt-mcp"
}

# --- Claude Code konfigurieren ---
configure_claude() {
    if [ "$SKIP_CLAUDE" = "1" ]; then
        warn "Claude Code Konfiguration übersprungen (JDTMCP_SKIP_CLAUDE=1)"
        return
    fi

    local claude_settings="$HOME/.claude.json"
    local launcher="$INSTALL_DIR/bin/jdt-mcp"

    # Prüfen ob Claude Code installiert ist
    if ! command -v claude &>/dev/null && [ ! -f "$claude_settings" ]; then
        warn "Claude Code nicht gefunden - überspringe Konfiguration"
        warn "Manuell konfigurieren: claude mcp add jdt-mcp $launcher"
        return
    fi

    # Bestehende jdt-mcp Config entfernen (könnte SSE/HTTP statt stdio sein)
    if command -v claude &>/dev/null; then
        claude mcp remove -s user jdt-mcp 2>/dev/null || true
    fi

    # Konfigurieren via claude CLI
    if command -v claude &>/dev/null; then
        info "Konfiguriere Claude Code..."
        claude mcp add -s user jdt-mcp "$launcher" && info "Claude Code: jdt-mcp konfiguriert (stdio)" || warn "Claude Code Konfiguration fehlgeschlagen. Manuell: claude mcp add jdt-mcp $launcher"
    else
        warn "claude CLI nicht im PATH. Manuell konfigurieren:"
        warn "  claude mcp add jdt-mcp $launcher"
    fi
}

# --- Zusammenfassung ---
print_summary() {
    echo ""
    echo -e "${BOLD}Installation abgeschlossen!${NC}"
    echo ""
    echo "  Installation:  $INSTALL_DIR"
    echo "  Befehl:        jdt-mcp"
    echo ""

    if [ "$LEGACY_CLEANED" = "1" ]; then
        echo -e "${YELLOW}Hinweis:${NC} Alt-Installation von vor der Umbenennung ('jdtls-mcp', ~180 MB) wurde entfernt."
        echo ""
    fi

    # Prüfen ob ~/.local/bin im PATH ist
    if [[ ":$PATH:" != *":$BIN_DIR:"* ]]; then
        echo -e "${YELLOW}Hinweis:${NC} $BIN_DIR ist nicht im PATH."
        echo "  Füge dies zu deiner Shell-Konfiguration hinzu:"
        echo ""
        echo "    export PATH=\"\$HOME/.local/bin:\$PATH\""
        echo ""
    fi

    echo "  Nutzung mit Claude Code:"
    echo "    cd /dein/java-projekt"
    echo "    claude"
    echo ""
    echo "  Deinstallation:"
    echo "    rm -rf $INSTALL_DIR $BIN_DIR/jdt-mcp"
    echo "    claude mcp remove jdt-mcp"
    echo ""
}

# --- Main ---
main() {
    echo ""
    echo -e "${BOLD}JDT MCP Server - Installer${NC}"
    echo ""

    detect_platform
    check_java
    resolve_version
    cleanup_legacy
    install
    configure_claude
    print_summary
}

main "$@"
