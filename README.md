# Eclipse JDT MCP Server

Ein Eclipse-Plugin, das einen MCP (Model Context Protocol) Server einbettet, um JDT-Funktionen (Java Development Tools) für KI-Coding-Assistenten wie Claude Code, Cursor und andere bereitzustellen.

**Inspiriert von:** Spring Tools 5 embedded MCP Server von Martin Lippert

## Features

Das Plugin stellt **44 MCP-Tools** in 9 Kategorien bereit:

### Project Info (5 Tools)

| Tool | Beschreibung |
|------|-------------|
| `jdt_list_projects` | **START HERE**: Alle Java-Projekte im Workspace auflisten. Gibt Projektnamen zurück, die in anderen Tools verwendet werden. |
| `jdt_get_classpath` | Classpath eines Projekts abrufen (Source-Folder, Libraries, Output-Folder) |
| `jdt_get_compilation_errors` | Kompilierungsfehler und Warnungen mit Datei, Zeile und Nachricht |
| `jdt_get_project_structure` | Projektstruktur-Übersicht (Java-Version, Source-Folder, Packages) |
| `jdt_refresh_project` | **WICHTIG**: Workspace aktualisieren nach externen Dateiänderungen (Write/Edit, git). Ohne Refresh arbeiten JDT-Tools auf veralteten Daten! |

### Navigation (4 Tools)

| Tool | Beschreibung |
|------|-------------|
| `jdt_find_type` | Typen nach Namensmuster suchen. Wildcards: `*` = beliebig, `?` = ein Zeichen. Beispiele: `*Service`, `User*` |
| `jdt_get_method_signature` | Methodensignaturen mit Parametern, Rückgabetyp, Modifiern. `*` für alle Methoden einer Klasse. |
| `jdt_find_implementations` | Alle IMPLEMENTIERUNGEN eines Interfaces oder SUBKLASSEN einer Klasse finden |
| `jdt_find_callers` | Alle AUFRUFER einer Methode finden. Gibt Klasse, Methode und Position zurück. |

### Code Analysis (4 Tools)

| Tool | Beschreibung |
|------|-------------|
| `jdt_parse_java_file` | Java-Datei parsen: Package, Imports, Typen, Methoden, Felder mit Offsets |
| `jdt_get_type_hierarchy` | Typhierarchie: Superklassen, Interfaces, Subklassen |
| `jdt_find_references` | ALLE Verwendungen einer Klasse/Methode/Feld im Workspace finden |
| `jdt_get_source_range` | **QUELLCODE LESEN**: Gibt den tatsächlichen Code einer Methode/Klasse als Text zurück |

### Creation (3 Tools)

| Tool | Beschreibung |
|------|-------------|
| `jdt_create_class` | Neue Java-Klasse erstellen. Erstellt Package falls nötig. |
| `jdt_create_interface` | Neues Java-Interface erstellen |
| `jdt_create_enum` | Neues Java-Enum mit Konstanten erstellen |

### Code Generation (5 Tools)

| Tool | Beschreibung |
|------|-------------|
| `jdt_generate_getters_setters` | Getter/Setter für Felder generieren. Überspringt existierende. |
| `jdt_generate_constructor` | Konstruktor(en) generieren. Optional: No-Args für JPA/Jackson. |
| `jdt_generate_equals_hashcode` | equals() und hashCode() mit java.util.Objects generieren |
| `jdt_generate_tostring` | toString() generieren: `User{id=1, name='John'}` |
| `jdt_generate_delegate_methods` | **DELEGATION PATTERN**: Methoden generieren die an ein anderes Objekt delegieren |

### Refactoring (10 Tools)

| Tool | Beschreibung |
|------|-------------|
| `jdt_rename_element` | SICHERES UMBENENNEN: Klasse/Methode/Feld umbenennen, alle Referenzen aktualisieren |
| `jdt_extract_method` | Code in neue Methode extrahieren. Parameter/Rückgabetyp automatisch erkannt. |
| `jdt_move_type` | Klasse in anderes Package verschieben, alle Imports aktualisieren |
| `jdt_organize_imports` | Imports aufräumen: unbenutzte entfernen, sortieren |
| `jdt_inline` | Variable/Ausdruck inlinen (Gegenteil von Extract) |
| `jdt_extract_interface` | Interface aus Klasse extrahieren - für bessere Abstraktion |
| `jdt_change_method_signature` | Methodensignatur ändern (Parameter hinzufügen/entfernen), alle Aufrufer aktualisieren |
| `jdt_convert_to_lambda` | Anonyme Klasse zu Lambda-Ausdruck konvertieren |
| `jdt_encapsulate_field` | Feld kapseln: private machen + Getter/Setter, alle Zugriffe aktualisieren |
| `jdt_introduce_parameter` | Lokale Variable als Methodenparameter extrahieren |

### Execution (6 Tools)

| Tool | Beschreibung |
|------|-------------|
| `jdt_maven_build` | Maven-Build ausführen: clean, compile, package, install, verify |
| `jdt_run_main` | Java-Klasse mit main() ausführen, stdout/stderr erfassen |
| `jdt_list_unit_tests` | Unit-Tests auflisten (*Test.java, Test*.java) |
| `jdt_list_integration_tests` | Integration-Tests auflisten (*IT.java, *IntegrationTest.java) |
| `jdt_run_unit_tests` | Unit-Tests via `mvn test` ausführen |
| `jdt_run_integration_tests` | Integration-Tests via `mvn verify` ausführen |

### Documentation (4 Tools)

| Tool | Beschreibung |
|------|-------------|
| `jdt_get_javadoc` | Javadoc für Klasse/Methode/Feld abrufen (inkl. @param, @return, @throws) |
| `jdt_get_annotations` | Alle Annotationen eines Elements mit Werten abrufen (@Entity, @Column(name="...")) |
| `jdt_find_annotated_elements` | Alle Elemente mit bestimmter Annotation finden (@Service, @Test, @Entity) |
| `jdt_generate_javadoc` | Javadoc-Kommentar generieren mit @param, @return, @throws |

### Code Quality (3 Tools)

| Tool | Beschreibung |
|------|-------------|
| `jdt_quick_fix` | **FEHLER AUTOMATISCH BEHEBEN**: Import hinzufügen, Cast korrigieren, Typos fixen |
| `jdt_find_unused_code` | Unbenutzte Imports, private Felder und Methoden finden |
| `jdt_find_dead_code` | Unerreichbaren Code finden (nach return/throw, tote Branches) |

## Voraussetzungen

- Java 17+
- Eclipse IDE 2025-12 oder neuer
- Maven 3.9+

## Build

```bash
mvn clean package -DskipTests
```

Das P2-Repository wird erstellt unter: `org.naturzukunft.jdt.mcp.site/target/repository`

## Installation

### Option 1: Via P2 Update Site

1. In Eclipse: `Help > Install New Software...`
2. URL hinzufügen: `https://naturzukunft.codeberg.page/jdt-mcp-server/`
3. "Eclipse JDT MCP Server" auswählen und installieren

### Option 2: Aus Source in Eclipse PDE

1. Repository klonen
2. In Eclipse: `File > Import > Existing Projects into Workspace`
3. Alle Projekte auswählen
4. `Run > Run As > Eclipse Application`

## Konfiguration

### Eclipse Preferences

`Window > Preferences > JDT MCP Server`

| Einstellung | Beschreibung | Standard |
|-------------|--------------|----------|
| Enable MCP Server | Server aktivieren/deaktivieren | true |
| Port Range Start | Erster Port für dynamische Zuweisung | 51000 |
| Port Range End | Letzter Port für dynamische Zuweisung | 51100 |

### AI Client Konfiguration

#### Claude Code (empfohlen: HTTP Transport)

In `~/.claude/settings.json`:

```json
{
  "mcpServers": {
    "eclipse-jdt": {
      "url": "http://localhost:51000/mcp",
      "type": "http"
    }
  }
}
```

#### Alternative: SSE Transport

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

**Hinweis:** Der tatsächliche Port wird in der Eclipse-Konsole angezeigt.

## Tool-Referenz

### Typischer Workflow

1. **Start**: `jdt_list_projects` aufrufen um verfügbare Projekte zu sehen
2. **Erkunden**: `jdt_get_project_structure` für Übersicht, `jdt_find_type` zum Suchen
3. **Analysieren**: `jdt_parse_java_file` für Datei-Details, `jdt_find_references` für Verwendungen
4. **Ändern**: `jdt_create_class`, `jdt_generate_*`, `jdt_rename_element`
5. **Aktualisieren**: `jdt_refresh_project` nach externen Änderungen
6. **Bauen/Testen**: `jdt_maven_build`, `jdt_run_unit_tests`

### Parameter-Formate

- **projectName**: Eclipse-Projektname (von `jdt_list_projects`)
- **className** (fully qualified): `com.example.MyClass` (Package + Klasse)
- **methodName/fieldName**: `com.example.MyClass#methodName`
- **filePath**: Absoluter Pfad, z.B. `/home/user/project/src/main/java/com/example/MyClass.java`
- **offset**: Zeichenposition in Datei (von `jdt_parse_java_file`)

## Troubleshooting

### Server startet nicht

1. Eclipse-Konsole auf `[JDT MCP]` Meldungen prüfen
2. Port-Bereich prüfen (keine Konflikte mit anderen Diensten)

### AI Client verbindet nicht

1. Korrekten Port in der Eclipse-Konsole ablesen: `[JDT MCP] HTTP Server started on port XXXXX`
2. HTTP Transport bevorzugen: `"type": "http"` mit `/mcp` Endpoint
3. Firewall-Einstellungen prüfen

### Veraltete Daten nach Dateiänderungen

`jdt_refresh_project` aufrufen! Eclipse erkennt externe Änderungen nicht automatisch.

### Port-Konflikt mit Spring Tools MCP

Spring Tools MCP verwendet Port 50627. Dieses Plugin verwendet 51000-51100 um Konflikte zu vermeiden.

## Lizenz

[EUPL-1.2](https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12) - European Union Public Licence

## Mitwirken

Beiträge sind willkommen! Bitte erstelle einen Issue oder Pull Request auf Codeberg:
https://codeberg.org/naturzukunft/jdt-mcp-server
