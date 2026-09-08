# Eclipse JDT MCP Server

[![Release](https://img.shields.io/github/v/release/hauschel-ai-tools/jdt-mcp-server)](https://github.com/hauschel-ai-tools/jdt-mcp-server/releases)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue)](LICENSE)

> **Note:** This repository moved to GitHub in September 2026. The former home on Forgejo (`git.changinggraph.org/ai-tools/jdt-mcp-server`) is archived; issue numbers were preserved.

An MCP server (Model Context Protocol) that exposes Eclipse JDT features to AI coding assistants like Claude Code, Cursor, and others. It provides **52 tools** across 9 categories — covering navigation, refactoring, code generation, test execution, and more. Runs as a **standalone CLI** (stdio) without requiring the Eclipse IDE. Install with a single `curl | bash` command, then use it from any Java project.

---

MCP-Server (Model Context Protocol) für Java Development Tools (JDT). Stellt JDT-Funktionen für KI-Coding-Assistenten wie Claude Code, Cursor und andere bereit.

**Standalone CLI** - Läuft ohne Eclipse IDE, direkt als MCP-Subprocess (stdio). Einfach installieren, in ein Java-Projekt wechseln, Claude Code starten.

**Inspiriert von:** Spring Tools 5 embedded MCP Server von Martin Lippert

## Features

Der Server stellt **52 MCP-Tools** in 9 Kategorien bereit:

### Project Info (5 Tools)

| Tool | Beschreibung |
|------|-------------|
| `jdt_list_projects` | **START HERE**: Alle Java-Projekte im Workspace auflisten |
| `jdt_get_classpath` | Classpath eines Projekts abrufen (Source-Folder, Libraries, Output-Folder) |
| `jdt_get_compilation_errors` | Kompilierungsfehler, Warnungen und Build-Path-Probleme mit Datei, Zeile und Nachricht |
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

## Unterstützte Plattformen

| Plattform | Architektur | Install-Script | Manuell |
|---|---|---|---|
| Linux | x86_64, aarch64 | `curl ... \| bash` | tar.gz |
| macOS | x86_64, aarch64 (Apple Silicon) | `curl ... \| bash` | tar.gz |
| Windows | x86_64 | - | zip + `jdt-mcp.cmd` |

## Installation (Linux & macOS)

```bash
curl -sSL https://github.com/hauschel-ai-tools/jdt-mcp-server/raw/main/install.sh | bash
```

Das Script erkennt OS und Architektur, lädt die neueste Version herunter, installiert nach `~/.local/share/jdt-mcp/` und konfiguriert Claude Code automatisch.

Danach:

```bash
cd /dein/java-projekt
claude
```

### Marketplace-Plugin (Claude Code)

Alternative zu `install.sh`: dieses Repo ist selbst ein Claude-Code-Plugin
(`.claude-plugin/plugin.json` + `.mcp.json`). Über einen Marketplace installiert
(`/plugin marketplace add ...` bzw. das Marketplace-übliche `/plugin install`) bringt
das Plugin die MCP-Server-Registrierung selbst mit — es ist kein separates
`install.sh` nötig, um `jdt-mcp` als Server bekannt zu machen.

Der Server braucht trotzdem den Launcher-Binary. Die Plugin-`.mcp.json` startet ihn über
einen absoluten, `${HOME}`-expandierten Pfad (`${HOME}/.local/share/jdt-mcp/bin/jdt-mcp`) statt
über den bloßen Befehlsnamen `jdt-mcp` — der Prozess, der MCP-Server startet, hat nicht
zwangsläufig `~/.local/bin` im PATH (viele `.profile`-Setups erweitern PATH nur für Login-Shells).
Dafür läuft beim Session-Start ein `SessionStart`-Hook (`hooks/ensure-server.sh`): fehlt der
Launcher (`~/.local/bin/jdt-mcp` bzw. `~/.local/share/jdt-mcp/bin/jdt-mcp`), führt er `install.sh`
aus dem Plugin-Verzeichnis aus — mit `JDTMCP_SKIP_CLAUDE=1`, installiert also **nur** den
Launcher, ohne zusätzlich `claude mcp add` aufzurufen (das übernimmt bereits die `.mcp.json` des
Plugins). Fehlt Java 21+ oder Netzzugriff, meldet der Hook das als Kontext-Hinweis in der Session,
statt die Session zu blockieren (Exit 0 in jedem Fall, mit Timeout um den `install.sh`-Aufruf).
`install.sh` selbst serialisiert parallele Läufe (z.B. zwei gleichzeitig gestartete Sessions ohne
Launcher) über einen `mkdir`-Lock mit Erkennung verwaister Locks — eine zweite Session wartet kurz
und überspringt Download/Entpacken dann, statt zwei Installationen gegeneinander laufen zu lassen.

**Schon eine Standalone-Installation per `install.sh` vorhanden** (also `jdt-mcp` bereits via
`claude mcp add -s user jdt-mcp ...` im User-Scope registriert)? Dann NICHT zusätzlich das
Marketplace-Plugin aktivieren — beide Registrierungen sind unterschiedlich benannt
(`mcp__jdt-mcp__*` im User-Scope vs. `mcp__plugin_jdt-mcp-server_jdt-mcp__*` im Plugin-Scope)
und würden nebeneinander laufen, also zwei separate Server-Prozesse gegen denselben
Eclipse-Workspace eines Arbeitsverzeichnisses starten. Der Workspace-Mutation-Lock im Server
ist ein prozessinterner `ReentrantLock` und schützt nicht vor einer zweiten JVM auf demselben
Workspace. Wer beides ausprobiert hat: entweder das Plugin deaktivieren, oder die
User-Scope-Registrierung entfernen (`claude mcp remove -s user jdt-mcp`) und beim Plugin bleiben.

### Update

Einfach den gleichen Befehl erneut ausführen:

```bash
curl -sSL https://github.com/hauschel-ai-tools/jdt-mcp-server/raw/main/install.sh | bash
```

Das Script erkennt die bestehende Installation und zeigt den Update-Pfad an (z.B. `Update: 0.2.1 -> 0.2.2`).

Der Launcher hieß bis v1.1.0 `jdtls-mcp`. Beim Update von einer älteren Version entfernt `install.sh` automatisch die Alt-Installation unter `~/.local/share/jdtls-mcp` (~180 MB) sowie den alten Symlink `~/.local/bin/jdtls-mcp`. Wer den Pfad manuell in `claude mcp add` eingetragen hatte (statt über dieses Script), muss die Registrierung selbst auf `jdt-mcp` (siehe unten) umstellen.

Installierte Version prüfen:

```bash
jdt-mcp --version
```

### Installation aus lokalem Build

```bash
git clone https://github.com/hauschel-ai-tools/jdt-mcp-server.git
cd jdt-mcp-server
./install-local.sh
```

### Deinstallation

```bash
curl -sSL https://github.com/hauschel-ai-tools/jdt-mcp-server/raw/main/uninstall.sh | bash
```

Oder manuell:

```bash
rm -rf ~/.local/share/jdt-mcp ~/.local/bin/jdt-mcp
claude mcp remove jdt-mcp
```

### Manuelle Installation (Linux/macOS)

```bash
# Archiv herunterladen von:
# https://github.com/hauschel-ai-tools/jdt-mcp-server/releases

# Entpacken
mkdir -p ~/.local/share/jdt-mcp
tar xzf jdt-mcp-linux.gtk.x86_64.tar.gz -C ~/.local/share/jdt-mcp

# Claude Code konfigurieren
claude mcp add -s user jdt-mcp ~/.local/share/jdt-mcp/bin/jdt-mcp
```

### Manuelle Installation (Windows)

```powershell
# ZIP-Archiv herunterladen von:
# https://github.com/hauschel-ai-tools/jdt-mcp-server/releases

# Entpacken (z.B. nach %LOCALAPPDATA%\jdt-mcp)
Expand-Archive jdt-mcp-win32.win32.x86_64.zip -DestinationPath "$env:LOCALAPPDATA\jdt-mcp"

# Claude Code konfigurieren
claude mcp add -s user jdt-mcp "$env:LOCALAPPDATA\jdt-mcp\bin\jdt-mcp.cmd"
```

### Erweiterte Optionen

| Umgebungsvariable | Beschreibung | Standard |
|-------------------|-------------|----------|
| `JDTMCP_TRANSPORT` | Transport: `stdio` oder `http` | `stdio` |
| `JDTMCP_WORKSPACE` | Eclipse Workspace-Verzeichnis | `~/.jdt-mcp/workspaces/<hash>` |
| `JAVA_HOME` | Java-Installation | System-Java |

```bash
# HTTP-Modus (für Debugging)
jdt-mcp --http
```

## Workspace-Management

Der JDT MCP Server verwaltet einen **Eclipse Workspace** pro Arbeitsverzeichnis. Der Workspace enthält die JDT-Metadaten (Index, Classpath, Build-State) — die eigentlichen Projektdateien bleiben an ihrem Platz.

### Wie Workspaces funktionieren

```
~/mein-java-projekt/          ← Arbeitsverzeichnis (user.dir)
  ├── pom.xml                 ← Maven-Projekt wird automatisch importiert
  ├── modul-a/                ← Multi-Module: jedes Modul wird ein eigenes JDT-Projekt
  └── modul-b/

~/.jdt-mcp/
  ├── workspaces/<md5-hash>/  ← Eclipse Workspace (pro Arbeitsverzeichnis)
  │   └── .metadata/          ← JDT-Index, Build-State, Projekt-Referenzen
  └── jdt-mcp-mein-java-projekt.log  ← Log (pro Arbeitsverzeichnis)
```

- **Automatischer Import**: Beim Start importiert der Server alle Projekte aus dem Arbeitsverzeichnis (Maven, Gradle, Eclipse `.project`)
- **Persistenter Workspace**: Der Workspace bleibt zwischen Neustarts erhalten — kein erneuter Import nötig
- **Classpath-Auffrischung**: Hat sich die `pom.xml` eines Moduls (oder eine Parent-`pom.xml`) seit der letzten Auflösung geändert, löst der Server die Abhängigkeiten dieses Moduls beim Start neu auf — sonst würde er gegen den Stand des letzten Imports bauen
- **Vollbau nach dem Wiederöffnen**: Sobald Projekte aus einer früheren Sitzung wiedergeöffnet werden, baut der Server den Workspace einmal vollständig, damit kein gespeicherter Fehler-Marker der letzten Sitzung überlebt
- **Ein Workspace pro Verzeichnis**: Jedes Arbeitsverzeichnis bekommt einen eigenen, isolierten Workspace (MD5-Hash des Pfads)

### Workspace zurücksetzen

Falls der Workspace korrupt ist oder Projekte nicht korrekt erkannt werden:

```bash
# Workspace-Verzeichnis für aktuelles Arbeitsverzeichnis finden
HASH=$(printf '%s' "$PWD" | md5sum | cut -d' ' -f1)
rm -rf ~/.jdt-mcp/workspaces/$HASH

# Server neu starten (Claude Code neu starten oder Session beenden)
```

Beim nächsten Start wird der Workspace automatisch neu erstellt und alle Projekte frisch importiert.

### Zusätzliche Projekte importieren

Projekte die nicht im Arbeitsverzeichnis liegen, können nachträglich importiert werden:

```
jdt_import_project(path="/pfad/zum/anderen/projekt")
```

### Workspace-Umgebungsvariablen

| Variable | Beschreibung | Standard |
|----------|-------------|----------|
| `JDTMCP_WORKSPACE` | Eigenes Workspace-Verzeichnis setzen | `~/.jdt-mcp/workspaces/<hash>` |

### Logs

Jede Server-Instanz loggt in eine eigene Datei basierend auf dem Arbeitsverzeichnis:

```bash
# Logs für ein bestimmtes Projekt anschauen
tail -f ~/.jdt-mcp/jdt-mcp-mein-java-projekt.log
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

1. Log prüfen: `~/.jdt-mcp/jdt-mcp-<projektname>.log`
2. Java-Version prüfen: `java -version` (21+ erforderlich)
3. Binary testen: `jdt-mcp` direkt ausführen, stderr-Ausgabe beobachten

### Veraltete Daten nach Dateiänderungen

`jdt_refresh_project` aufrufen! Der Server erkennt externe Änderungen nicht automatisch.

### Server-JVM bleibt nach Session-Ende übrig

Die JVM beendet sich selbst, sobald der Client stdin schließt, der Launcher-Wrapper stirbt oder ein Signal weitergeleitet wird. Bleibt trotzdem ein `java … org.naturzukunft.jdt.mcp.headless`-Prozess zurück, ist er meist gestoppt (Zustand `T` in `ps`) und braucht erst `kill -CONT`, dann `kill -TERM`:

```bash
pkill -CONT -f jdtmcp.headless; pkill -TERM -f jdtmcp.headless
```

### Tests laufen zu lange

`jdt_start_tests_async` statt `jdt_run_tests` verwenden. MCP-Client-Timeout ist 60s.

## Bekannte Einschränkungen

| Einschränkung | Betroffenes Tool | Ursache | Issue |
|---|---|---|---|
| Projekte mit JUnit Platform 1.x (JUnit 5) schlagen bei `jdt_run_tests`/`jdt_start_tests_async` fehl | `jdt_run_tests`, `jdt_start_tests_async` | `NoClassDefFoundError: org/junit/platform/engine/OutputDirectoryCreator` im gebündelten JUnit5-Runner — dessen Eclipse-JDT-Loader-Version erwartet eine neuere JUnit-Platform-API als 1.x liefert. JUnit Platform 1.x wird daher nicht unterstützt; auf JUnit 6 heben. JUnit 4 und JUnit 6 werden automatisch am Projekt-Classpath erkannt und funktionieren. Workaround für JUnit 5: `jdt_maven_build(goals="test")` | [#71](https://github.com/hauschel-ai-tools/jdt-mcp-server/issues/71) |
| Unbenutzte package-private Felder werden nicht erkannt | `jdt_find_unused_code` | JDT erkennt nur unbenutzte private Members, nicht package-private | [#72](https://github.com/hauschel-ai-tools/jdt-mcp-server/issues/72) |
| `jdt_inline` kann bestimmte statische Factory-Methoden nicht inlinen | `jdt_inline` | JDT-Bug: `InlineMethodRefactoring.create()` liefert `null` für manche Method-Patterns im Headless-Modus | [#81](https://github.com/hauschel-ai-tools/jdt-mcp-server/issues/81) |

## Entwicklung

### Smoke Tests

Der Server hat stdio-basierte Smoke Tests, die den MCP-Protokoll-Handshake und grundlegende Tool-Aufrufe prüfen:

```bash
tests/smoke-test.sh [path/to/jdt-mcp-binary]
```

Ohne Argument wird das Binary aus dem lokalen Build verwendet.

### Lifecycle Tests

Prüfen, dass die Server-JVM ihren Client nie überlebt (stdin-EOF, Signal-Weiterleitung, Parent-Death-Erkennung, eigene Prozessgruppe):

```bash
tests/lifecycle-test.sh [path/to/jdt-mcp-binary]
```

### Refactoring-Tests (End-to-End)

Importieren `tests/fixtures/fixture-parent` und `tests/fixtures/fixture-external` als zwei getrennte Projekte und prüfen nach jedem Refactoring den Zustand **auf der Festplatte**, nicht die Tool-Antwort — im Headless-Modus meldete ein Refactoring schon Erfolg, während die Änderungen nur im Puffer standen:

```bash
tests/refactoring-test.sh [path/to/jdt-mcp-binary]
```

### Code-Generierungs-Tests (End-to-End)

Importieren `tests/fixtures/fixture-parent` und lassen `jdt_implement_interface` auf Deklarations-Köpfe los, die eine reine Textsuche aushebeln (Record mit Komponentenliste, `sealed`-Klasse mit `permits`, Kommentar mit `{` im Kopf, verschachtelte Generics in einer bestehenden `implements`-Liste). Geprüft wird die Datei **auf der Festplatte**, anschließend wird sie mit `javac` übersetzt:

```bash
tests/codegen-test.sh [path/to/jdt-mcp-binary]
```

### Build-Path-Test (End-to-End)

Importiert `tests/fixtures/fixture-badclasspath` (fehlende Library im `.classpath`) und prüft, dass `jdt_get_compilation_errors` das konkrete Build-Path-Problem meldet statt nur den Sammelmarker:

```bash
tests/buildpath-test.sh [path/to/jdt-mcp-binary]
```

### Compiler-Compliance-Test (End-to-End)

Importiert `tests/fixtures/fixture-java25` (`maven.compiler.release=25`, nutzt Module-Import-Deklarationen) und prüft, dass `jdt_get_project_structure` `compliance=25` meldet und `jdt_get_compilation_errors` sauber ist — unabhängig davon, mit welcher JVM der Server selbst gestartet wurde:

```bash
tests/compliance-test.sh [path/to/jdt-mcp-binary]
```

### Unit-Tests

`org.naturzukunft.jdt.mcp` ist ein Tycho-`eclipse-plugin`-Modul ohne Tycho-Surefire-Testfragment. Klassen ohne Eclipse/OSGi-Abhängigkeit (z. B. `MavenCompilerCompliance`) werden stattdessen mit einem eigenständigen JUnit-5-Runner getestet, der gegen bereits lokal gecachte JUnit-Jars kompiliert (Quellen unter `org.naturzukunft.jdt.mcp/unit-tests/java/`, außerhalb des Tycho-Reactors):

```bash
tests/run-unit-tests.sh
```

## Lizenz

[Apache License 2.0](LICENSE)

## Mitwirken

Beiträge sind willkommen! Bitte erstelle einen Issue oder Pull Request:
https://github.com/hauschel-ai-tools/jdt-mcp-server

## Built by

Fred Hauschel – freelance Java architect & quality engineer, Munich.
IT freelancer since 1999, working AI-augmented. → https://hauschel.de
