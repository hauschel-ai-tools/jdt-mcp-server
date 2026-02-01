# JDT MCP Server - Projekt-Regeln

## Build

Eclipse Plugin-Projekt mit Tycho. Der Build benötigt spezielle JVM-Optionen wegen großer XML-Dateien im Eclipse P2 Repository.

### Kompilieren

```bash
cd /home/naturzukunft/DEV/ws/lippert/projects/jdt_mcp_plugin

# Cache löschen falls korrupt
rm -rf ~/.m2/repository/.cache/tycho/https/download.eclipse.org/releases/2025-12

# Build mit XML Entity Limits deaktiviert
MAVEN_OPTS="-Djdk.xml.maxGeneralEntitySizeLimit=0 -Djdk.xml.entityExpansionLimit=0 -Djdk.xml.totalEntitySizeLimit=0" mvn compile
```

### Vollständiger Build (Package) - für Eclipse Installation nötig!

```bash
MAVEN_OPTS="-Djdk.xml.maxGeneralEntitySizeLimit=0 -Djdk.xml.entityExpansionLimit=0 -Djdk.xml.totalEntitySizeLimit=0" mvn clean package
```

**WICHTIG:** Für die Installation in Eclipse muss `package` (nicht nur `compile`) ausgeführt werden! Nur dann wird die Update-Site erstellt.

### Update-Site für Eclipse

Nach erfolgreichem Build liegt die Update-Site unter:
```
file:///home/naturzukunft/DEV/ws/lippert/projects/jdt_mcp_plugin/org.naturzukunft.jdt.mcp.site/target/repository
```

In Eclipse: Help → Install New Software → Add → Local → obigen Pfad wählen

## Git

- Repository: https://git.changinggraph.org/ai-tools/jdt-mcp-server
- Push mit automation-Token

### KRITISCH: Commit-Autor

**NIEMALS ohne --author committen!** Immer so:

```bash
git commit --author="automation" -m "message"
```

Ohne `--author` wird als lokaler User (naturzukunft) committed - DAS IST FALSCH!

## Projektstruktur

- `org.naturzukunft.jdt.mcp/` - Haupt-Plugin mit MCP-Server
- `org.naturzukunft.jdt.mcp.feature/` - Eclipse Feature
- `org.naturzukunft.jdt.mcp.site/` - Update Site
