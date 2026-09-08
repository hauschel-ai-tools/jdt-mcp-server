# JDT MCP Server - Projekt-Regeln

## Build

Eclipse Plugin-Projekt mit Tycho. Der Build benötigt spezielle JVM-Optionen wegen großer XML-Dateien im Eclipse P2 Repository.

### Kompilieren

```bash
cd /home/naturzukunft/DEV/projects/java/jdt_mcp_plugin

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
file:///home/naturzukunft/DEV/projects/java/jdt_mcp_plugin/org.naturzukunft.jdt.mcp.site/target/repository
```

In Eclipse: Help → Install New Software → Add → Local → obigen Pfad wählen

## Verifikation: kein jdt-mcp in diesem Repo

Die übergeordnete Regel "JDT MCP statt mvn direkt" gilt hier nicht. Dieses Repo ist das
Werkzeug selbst: der per `.mcp.json` gestartete Server ist die Installation unter
`~/.local/share/jdtls-mcp`, also ein anderer Stand als der Branch. Er kann eine Änderung
am Server weder prüfen noch ausführen. Verifikation stattdessen:

- Modul-Build: `mvn -pl org.naturzukunft.jdt.mcp -am compile` (mit obigen `MAVEN_OPTS`)
- `tests/smoke-test.sh` und `tests/lifecycle-test.sh` gegen das gebaute Produkt
- `tests/refactoring-test.sh` als E2E-Test für Refactorings (importiert die Fixtures und prüft Assertions auf Disk, nicht nur die Tool-Antwort)
- Produkt-Build (`package`) einmal am Ende eines Arbeitspakets, nicht nach jedem Edit

## Git

- Repository: https://github.com/hauschel-ai-tools/jdt-mcp-server

## Projektstruktur

- `org.naturzukunft.jdt.mcp/` - Haupt-Plugin mit MCP-Server
- `org.naturzukunft.jdt.mcp.feature/` - Eclipse Feature
- `org.naturzukunft.jdt.mcp.site/` - Update Site
