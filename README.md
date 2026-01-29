# Eclipse JDT MCP Server

Ein Eclipse-Plugin, das einen MCP (Model Context Protocol) Server einbettet, um JDT-Funktionen (Java Development Tools) für KI-Coding-Assistenten wie Claude Code, Cursor und andere bereitzustellen.

**Inspiriert von:** Spring Tools 5 embedded MCP Server von Martin Lippert

## Features

Das Plugin stellt 11 MCP-Tools bereit:

| Tool | Beschreibung |
|------|-------------|
| `jdt_list_projects` | Alle Java-Projekte im Workspace auflisten |
| `jdt_get_classpath` | Classpath eines Projekts abrufen |
| `jdt_get_compilation_errors` | Kompilierungsfehler und Warnungen |
| `jdt_get_project_structure` | Projektstruktur-Übersicht |
| `jdt_parse_java_file` | Java-Datei parsen (Package, Imports, Typen, Methoden, Felder) |
| `jdt_get_type_hierarchy` | Typhierarchie abrufen (Superklassen, Interfaces, Subklassen) |
| `jdt_find_references` | Referenzen auf Klassen, Methoden oder Felder finden |
| `jdt_find_type` | Typen nach Namensmuster suchen (Wildcards unterstützt) |
| `jdt_get_method_signature` | Methodensignaturen mit Parametern und Rückgabetyp |
| `jdt_rename_element` | Java-Element umbenennen (Refactoring) |
| `jdt_extract_method` | Code in neue Methode extrahieren (Preview) |

## Voraussetzungen

- Java 17+
- Eclipse IDE 2024-03 oder neuer
- Maven 3.9+

## Build

### Lokaler Build

```bash
# Dependencies in lib/ kopieren
mvn dependency:copy-dependencies -DoutputDirectory=lib

# Plugin bauen
mvn clean verify
```

### P2 Update Site erstellen

Für ein vollständiges P2-Repository wird ein Parent-Projekt mit Feature und Update-Site benötigt. Siehe Abschnitt "Projekt-Struktur für P2 Repository".

## Installation

### Option 1: Aus Source in Eclipse PDE

1. Repository klonen
2. In Eclipse: `File > Import > Existing Projects into Workspace`
3. Projekt `org.naturzukunft.jdt.mcp` auswählen
4. `Run > Run As > Eclipse Application`

### Option 2: Via P2 Update Site

1. In Eclipse: `Help > Install New Software...`
2. URL hinzufügen: `https://naturzukunft.codeberg.page/jdt-mcp-server/`
3. "Eclipse JDT MCP Server" auswählen und installieren

## Konfiguration

### Eclipse Preferences

`Window > Preferences > JDT MCP Server`

| Einstellung | Beschreibung | Standard |
|-------------|--------------|----------|
| Enable MCP Server | Server aktivieren/deaktivieren | false |
| Port Range Start | Erster Port für dynamische Zuweisung | 51000 |
| Port Range End | Letzter Port für dynamische Zuweisung | 51100 |

### AI Client Konfiguration

#### Claude Code

In `~/.claude.json` oder Projekt-`.claude.json`:

```json
{
  "mcpServers": {
    "eclipse-jdt": {
      "url": "http://localhost:51000/sse",
      "transport": "sse"
    }
  }
}
```

#### Cursor

In `.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "eclipse-jdt": {
      "url": "http://localhost:51000/sse",
      "transport": "sse"
    }
  }
}
```

**Hinweis:** Der tatsächliche Port wird in der Eclipse-Konsole angezeigt und kann in den Preferences abgelesen werden.

## Projekt-Struktur für P2 Repository

Für ein vollständiges P2-Repository mit Update-Site:

```
jdt-mcp-server/
├── pom.xml                              # Parent POM
├── org.naturzukunft.jdt.mcp/            # Plugin
├── org.naturzukunft.jdt.mcp.feature/    # Feature
└── org.naturzukunft.jdt.mcp.site/       # P2 Update Site
```

### Parent POM (pom.xml im Root)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>org.naturzukunft.jdt</groupId>
    <artifactId>jdt-mcp-server-parent</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>

    <modules>
        <module>org.naturzukunft.jdt.mcp</module>
        <module>org.naturzukunft.jdt.mcp.feature</module>
        <module>org.naturzukunft.jdt.mcp.site</module>
    </modules>

    <properties>
        <tycho.version>4.0.4</tycho.version>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <repositories>
        <repository>
            <id>eclipse-2024-03</id>
            <layout>p2</layout>
            <url>https://download.eclipse.org/releases/2024-03</url>
        </repository>
    </repositories>

    <build>
        <plugins>
            <plugin>
                <groupId>org.eclipse.tycho</groupId>
                <artifactId>tycho-maven-plugin</artifactId>
                <version>${tycho.version}</version>
                <extensions>true</extensions>
            </plugin>
            <plugin>
                <groupId>org.eclipse.tycho</groupId>
                <artifactId>target-platform-configuration</artifactId>
                <version>${tycho.version}</version>
                <configuration>
                    <environments>
                        <environment>
                            <os>linux</os>
                            <ws>gtk</ws>
                            <arch>x86_64</arch>
                        </environment>
                        <environment>
                            <os>win32</os>
                            <ws>win32</ws>
                            <arch>x86_64</arch>
                        </environment>
                        <environment>
                            <os>macosx</os>
                            <ws>cocoa</ws>
                            <arch>x86_64</arch>
                        </environment>
                    </environments>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

## CI/CD mit Woodpecker

Das Repository enthält eine `.woodpecker.yml` für automatische Builds auf Codeberg.

Bei jedem Push auf `main`:
1. Maven Build
2. P2 Repository erstellen
3. Deployment auf Codeberg Pages

## Tool-Referenz

### jdt_list_projects

Listet alle Java-Projekte im Eclipse Workspace.

**Parameter:** keine

**Beispiel-Antwort:**
```json
{
  "projectCount": 2,
  "projects": [
    {
      "name": "my-project",
      "location": "/home/user/workspace/my-project",
      "open": true,
      "javaVersion": "17"
    }
  ]
}
```

### jdt_parse_java_file

Parst eine Java-Datei und gibt die Struktur zurück.

**Parameter:**
- `filePath` (string, required): Absoluter Pfad zur Java-Datei

**Beispiel-Antwort:**
```json
{
  "packageName": "com.example",
  "imports": ["java.util.List", "java.util.Map"],
  "types": [
    {
      "name": "MyClass",
      "fullyQualifiedName": "com.example.MyClass",
      "isClass": true,
      "isInterface": false,
      "methods": [...],
      "fields": [...]
    }
  ]
}
```

### jdt_get_type_hierarchy

Gibt die Typhierarchie für eine Klasse zurück.

**Parameter:**
- `className` (string, required): Voll qualifizierter Klassenname

### jdt_find_references

Findet alle Referenzen auf ein Java-Element.

**Parameter:**
- `elementName` (string, required): Voll qualifizierter Elementname
- `elementType` (string, required): `CLASS`, `METHOD` oder `FIELD`

### jdt_find_type

Sucht nach Typen im Workspace.

**Parameter:**
- `pattern` (string, required): Namensmuster (Wildcards `*` und `?` unterstützt)

### jdt_get_method_signature

Gibt Methodensignaturen zurück.

**Parameter:**
- `className` (string, required): Voll qualifizierter Klassenname
- `methodName` (string, required): Methodenname (oder `*` für alle)

### jdt_get_classpath

Gibt den aufgelösten Classpath zurück.

**Parameter:**
- `projectName` (string, required): Name des Java-Projekts

### jdt_get_compilation_errors

Gibt Kompilierungsfehler und Warnungen zurück.

**Parameter:**
- `projectName` (string, required): Name des Java-Projekts

### jdt_get_project_structure

Gibt eine Übersicht der Projektstruktur zurück.

**Parameter:**
- `projectName` (string, required): Name des Java-Projekts

### jdt_rename_element

Benennt ein Java-Element um (mit Refactoring-Unterstützung).

**Parameter:**
- `elementName` (string, required): Aktueller voll qualifizierter Name
- `newName` (string, required): Neuer Name
- `elementType` (string, required): `CLASS`, `METHOD` oder `FIELD`

### jdt_extract_method

Extrahiert Code in eine neue Methode (Preview).

**Parameter:**
- `filePath` (string, required): Absoluter Dateipfad
- `startOffset` (integer, required): Start-Offset der Selektion
- `endOffset` (integer, required): End-Offset der Selektion
- `methodName` (string, required): Name für die neue Methode

## Troubleshooting

### Server startet nicht

1. Prüfen ob "Enable MCP Server" in Preferences aktiviert ist
2. Eclipse-Konsole auf Fehlermeldungen prüfen
3. Port-Bereich prüfen (keine Konflikte mit anderen Diensten)

### AI Client verbindet nicht

1. Korrekten Port in der Eclipse-Konsole ablesen
2. URL-Format prüfen: `http://localhost:PORT/sse`
3. Firewall-Einstellungen prüfen

### Port-Konflikt mit Spring Tools MCP

Spring Tools MCP verwendet standardmäßig Port 50627. Dieses Plugin verwendet 51000-51100 um Konflikte zu vermeiden.

## Lizenz

[EUPL-1.2](https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12) - European Union Public Licence

Siehe [LICENSE](LICENSE) für den vollständigen Lizenztext.

## Mitwirken

Beiträge sind willkommen! Bitte erstelle einen Issue oder Pull Request auf Codeberg:
https://codeberg.org/naturzukunft/jdt-mcp-server
