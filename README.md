# Eclipse JDT MCP Server

MCP-Server (Model Context Protocol) für Java Development Tools (JDT). Stellt JDT-Funktionen für KI-Coding-Assistenten wie Claude Code, Cursor und andere bereit.

**Standalone CLI** - Läuft ohne Eclipse IDE, direkt als MCP-Subprocess (stdio). Einfach installieren, in ein Java-Projekt wechseln, Claude Code starten.

**Inspiriert von:** Spring Tools 5 embedded MCP Server von Martin Lippert

## Features

Der Server stellt **44 MCP-Tools** in 9 Kategorien bereit:

### Project Info (5 Tools)

| Tool | Beschreibung |
|------|-------------|
| `jdt_list_projects` | **START HERE**: Alle Java-Projekte im Workspace auflisten |
| `jdt_get_classpath` | Classpath eines Projekts abrufen (Source-Folder, Libraries, Output-Folder) |
| `jdt_get_compilation_errors` | Kompilierungsfehler und Warnungen mit Datei, Zeile und Nachricht |
| `jdt_get_project_structure` | Projektstruktur-Übersicht (Java-Version, Source-Folder, Packages) |
| `jdt_refresh_project` | **WICHTIG**: Workspace aktualisieren nach externen Dateiänderungen (Write/Edit, git) |

### Navigation (4 Tools)

| Tool | Beschreibung |
|------|-------------|
| `jdt_find_type` | Typen nach Namensmuster suchen (`*Service`, `User*`) |
| `jdt_get_method_signature` | Methodensignaturen mit Parametern, Rückgabetyp, Modifiern |
| `jdt_find_implementations` | Alle Implementierungen eines Interfaces oder Subklassen finden |
| `jdt_find_callers` | Alle Aufrufer einer Methode finden |

### Code Analysis (4 Tools)

| Tool | Beschreibung |
|------|-------------|
| `jdt_parse_java_file` | Java-Datei parsen: Package, Imports, Typen, Methoden, Felder mit Offsets |
| `jdt_get_type_hierarchy` | Typhierarchie: Superklassen, Interfaces, Subklassen |
| `jdt_find_references` | Alle Verwendungen einer Klasse/Methode/Feld im Workspace finden |
| `jdt_get_source_range` | **QUELLCODE LESEN**: Tatsächlichen Code einer Methode/Klasse als Text zurückgeben |

### Creation (3 Tools)

| Tool | Beschreibung |
|------|-------------|
| `jdt_create_class` | Neue Java-Klasse erstellen |
| `jdt_create_interface` | Neues Java-Interface erstellen |
| `jdt_create_enum` | Neues Java-Enum mit Konstanten erstellen |

### Code Generation (9 Tools)

| Tool | Beschreibung |
|------|-------------|
| `jdt_add_method` | Methode zu bestehender Klasse hinzufügen |
| `jdt_add_field` | Feld zu bestehender Klasse hinzufügen |
| `jdt_add_import` | Import-Statements hinzufügen (korrekte Platzierung, keine Duplikate) |
| `jdt_implement_interface` | Interface implementieren und Method-Stubs generieren |
| `jdt_generate_getters_setters` | Getter/Setter generieren (überspringt existierende) |
| `jdt_generate_constructor` | Konstruktor(en) generieren (optional No-Args für JPA/Jackson) |
| `jdt_generate_equals_hashcode` | equals() und hashCode() mit java.util.Objects generieren |
| `jdt_generate_tostring` | toString() generieren: `User{id=1, name='John'}` |
| `jdt_generate_delegate_methods` | Delegation Pattern: Methoden die an ein anderes Objekt delegieren |

### Refactoring (10 Tools)

| Tool | Beschreibung |
|------|-------------|
| `jdt_rename_element` | Sicheres Umbenennen: Klasse/Methode/Feld umbenennen, alle Referenzen aktualisieren |
| `jdt_extract_method` | Code in neue Methode extrahieren (Parameter/Rückgabetyp automatisch erkannt) |
| `jdt_move_type` | Klasse in anderes Package verschieben, alle Imports aktualisieren |
| `jdt_organize_imports` | Imports aufräumen: unbenutzte entfernen, sortieren |
| `jdt_inline` | Variable/Ausdruck inlinen (Gegenteil von Extract) |
| `jdt_extract_interface` | Interface aus Klasse extrahieren |
| `jdt_change_method_signature` | Methodensignatur ändern, alle Aufrufer aktualisieren |
| `jdt_convert_to_lambda` | Anonyme Klasse zu Lambda-Ausdruck konvertieren |
| `jdt_encapsulate_field` | Feld kapseln: private machen + Getter/Setter, alle Zugriffe aktualisieren |
| `jdt_introduce_parameter` | Lokale Variable als Methodenparameter extrahieren |

### Execution (6 Tools)

| Tool | Beschreibung |
|------|-------------|
| `jdt_maven_build` | Maven-Build mit Auto-Detection von Maven Wrapper und Java-Version |
| `jdt_run_main` | Java-Klasse mit main() ausführen, stdout/stderr erfassen |
| `jdt_list_tests` | Tests auflisten (`pattern='unit'` für *Test.java, `'integration'` für *IT.java) |
| `jdt_run_tests` | Tests ausführen mit strukturiertem JSON-Output |
| `jdt_start_tests_async` | Lang laufende Tests asynchron starten (>30s, z.B. Spring Boot) |
| `jdt_get_test_result` | Status/Ergebnis eines asynchronen Testlaufs abrufen |

### Documentation (4 Tools)

| Tool | Beschreibung |
|------|-------------|
| `jdt_get_javadoc` | Javadoc für Klasse/Methode/Feld abrufen |
| `jdt_get_annotations` | Alle Annotationen eines Elements mit Werten abrufen |
| `jdt_find_annotated_elements` | Alle Elemente mit bestimmter Annotation finden (@Service, @Test, @Entity) |
| `jdt_generate_javadoc` | Javadoc-Kommentar generieren mit @param, @return, @throws |

### Code Quality (2 Tools)

| Tool | Beschreibung |
|------|-------------|
| `jdt_find_unused_code` | Unbenutzte Imports, private Felder und Methoden finden |
| `jdt_find_dead_code` | Unerreichbaren Code finden (nach return/throw, tote Branches) |

## Voraussetzungen

- Java 21+
- Maven 3.9+ (nur für Build aus Source)

## Installation (ein Befehl)

```bash
curl -sSL https://git.changinggraph.org/ai-tools/jdt-mcp-server/raw/branch/main/install.sh | bash
```

Das Script erkennt OS und Architektur, lädt die neueste Version herunter, installiert nach `~/.local/share/jdtls-mcp/` und konfiguriert Claude Code automatisch.

Danach:

```bash
cd /dein/java-projekt
claude
```

### Installation aus lokalem Build

```bash
git clone https://git.changinggraph.org/ai-tools/jdt-mcp-server.git
cd jdt-mcp-server
./install-local.sh
```

### Deinstallation

```bash
rm -rf ~/.local/share/jdtls-mcp ~/.local/bin/jdtls-mcp
claude mcp remove jdt-mcp
```

### Manuelle Installation

```bash
# Archiv herunterladen von:
# https://git.changinggraph.org/ai-tools/jdt-mcp-server/releases

# Entpacken
mkdir -p ~/.local/share/jdtls-mcp
tar xzf jdtls-mcp-linux.gtk.x86_64.tar.gz -C ~/.local/share/jdtls-mcp

# Claude Code konfigurieren
claude mcp add -s user jdt-mcp ~/.local/share/jdtls-mcp/bin/jdtls-mcp
```

### Erweiterte Optionen

| Umgebungsvariable | Beschreibung | Standard |
|-------------------|-------------|----------|
| `JDTMCP_TRANSPORT` | Transport: `stdio` oder `http` | `stdio` |
| `JDTMCP_WORKSPACE` | Eclipse Workspace-Verzeichnis | `/tmp/jdtls-mcp-<pid>` |
| `JAVA_HOME` | Java-Installation | System-Java |

```bash
# HTTP-Modus (für Debugging)
jdtls-mcp --http
```

## Typischer Workflow

1. **Start**: `jdt_list_projects` aufrufen um verfügbare Projekte zu sehen
2. **Erkunden**: `jdt_get_project_structure` für Übersicht, `jdt_find_type` zum Suchen
3. **Analysieren**: `jdt_parse_java_file` für Datei-Details, `jdt_find_references` für Verwendungen
4. **Ändern**: `jdt_create_class`, `jdt_generate_*`, `jdt_rename_element`
5. **Aktualisieren**: `jdt_refresh_project` nach externen Änderungen
6. **Bauen/Testen**: `jdt_maven_build`, `jdt_run_tests`

### Parameter-Formate

- **projectName**: Eclipse-Projektname (von `jdt_list_projects`)
- **className** (fully qualified): `com.example.MyClass`
- **methodName/fieldName**: `com.example.MyClass#methodName`
- **filePath**: Absoluter Pfad zur Java-Datei
- **offset**: Zeichenposition in Datei (von `jdt_parse_java_file`)

## Troubleshooting

### Server startet nicht

1. Log prüfen: `~/.jdt-mcp/jdt-mcp.log`
2. Java-Version prüfen: `java -version` (21+ erforderlich)
3. Binary testen: `jdtls-mcp` direkt ausführen, stderr-Ausgabe beobachten

### Veraltete Daten nach Dateiänderungen

`jdt_refresh_project` aufrufen! Der Server erkennt externe Änderungen nicht automatisch.

### Tests laufen zu lange

`jdt_start_tests_async` statt `jdt_run_tests` verwenden. MCP-Client-Timeout ist 60s.

## Lizenz

[EUPL-1.2](https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12) - European Union Public Licence

## Mitwirken

Beiträge sind willkommen! Bitte erstelle einen Issue oder Pull Request:
https://git.changinggraph.org/ai-tools/jdt-mcp-server
