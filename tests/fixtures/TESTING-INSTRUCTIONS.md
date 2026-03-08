# JDT MCP Server — Test-Anleitung für KI-Agenten

Du bist ein Test-Agent, der alle 52 Tools des JDT MCP Servers systematisch testet.
Du hast Zugriff auf die `jdt_*` MCP-Tools und auf Dateisystem-Tools (Read, Grep, Bash).

## 1. Workspace vorbereiten

```bash
# Frisches Temp-Verzeichnis als Workspace
WORKSPACE=$(mktemp -d)

# Beide Projekt-Trees kopieren (NICHT symlinken — Tests verändern Dateien)
cp -r tests/fixtures/fixture-parent "$WORKSPACE/fixture-parent"
cp -r tests/fixtures/fixture-external "$WORKSPACE/fixture-external"
```

Das Ergebnis:
```
$WORKSPACE/
├── fixture-parent/          ← Maven multi-module (4 Module + Gradle/Eclipse/Plain)
│   ├── pom.xml
│   ├── fixture-api/
│   ├── fixture-core/
│   ├── fixture-app/
│   ├── fixture-broken/
│   ├── fixture-gradle/
│   ├── fixture-eclipse/
│   └── fixture-plain/
└── fixture-external/        ← Separates Maven-Projekt (Cross-Projekt-Tests)
    └── pom.xml
```

## 2. Projekte importieren

**Wichtig:** Zwei separate Import-Aufrufe (simuliert rdf/ + platform/ in Produktion):

```
jdt_import_project(path="$WORKSPACE/fixture-parent")
jdt_import_project(path="$WORKSPACE/fixture-external")
```

### Verifizierung

```
jdt_list_projects()
```

Erwartete Projekte (mindestens):
- `fixture-api`, `fixture-core`, `fixture-app`, `fixture-broken` (aus fixture-parent)
- `fixture-external` (separater Import)

### Auf Indexierung warten

Warte bis JDT fertig indiziert hat:
```
jdt_get_compilation_errors(projectName="fixture-api")  → errorCount == 0
jdt_get_compilation_errors(projectName="fixture-core") → errorCount == 0
```

### Cross-Projekt-Auflösung prüfen

```
jdt_get_classpath(projectName="fixture-external")
```
Assert: `projectDependencies` enthält Pfade zu `fixture-api` UND `fixture-core`.
Falls nicht → JDT hat die Workspace-Resolution nicht aufgelöst, Tests abbrechen.

## 3. Tests ausführen

Siehe `TEST-PLAN.md` für die vollständige Testbeschreibung.

### Parallelisierung

**Read-only Gruppen (A-F) parallel ausführen:**
- Starte jeweils einen Sub-Agent pro Gruppe
- Diese Tests lesen nur, keine Konflikte möglich

```
parallel:
  Agent "Group A: ProjectInfoTools"   → Tests A1-A7
  Agent "Group B: CodeAnalysisTools"  → Tests B1-B4
  Agent "Group C: NavigationTools"    → Tests C1-C4
  Agent "Group D: DocumentationTools" → Tests D1-D3
  Agent "Group E: CodeQualityTools"   → Tests E1-E2
  Agent "Group F: ImportTests"        → Tests F1-F5 (eigener Workspace pro Test)
```

**Mutierende Gruppen (G-K) sequentiell ausführen:**

Jede Gruppe verändert Dateien. Zwischen den Gruppen Workspace zurücksetzen:

```
sequential:
  1. Group G: CreationTools       → Tests G1-G3
  2. reset_workspace()
  3. Group H: CodeGenerationTools → Tests H1-H9
  4. reset_workspace()
  5. Group I: RefactoringTools    → Tests I1-I13 (JEWEILS reset dazwischen!)
  6. reset_workspace()
  7. Group J: ExecutionTools      → Tests J1-J5
  8. Group K: WorkspaceManagement → Tests K1-K2 (zuletzt, destruktiv)
```

### Workspace zurücksetzen

Zwischen mutierenden Tests:

```bash
# Dateien zurücksetzen
rm -rf "$WORKSPACE/fixture-parent" "$WORKSPACE/fixture-external"
cp -r tests/fixtures/fixture-parent "$WORKSPACE/fixture-parent"
cp -r tests/fixtures/fixture-external "$WORKSPACE/fixture-external"
```

Dann JDT informieren:
```
jdt_refresh_project()  (ohne projectName → gesamter Workspace)
```

Warte bis Indexierung abgeschlossen:
```
jdt_get_compilation_errors(projectName="fixture-api") → errorCount == 0
```

**Alternative (schneller):** Falls nur wenige Dateien geändert wurden, reicht ein gezieltes Refresh:
```
jdt_refresh_project(projectName="fixture-core")
```

## 4. Einzelnen Test ausführen

Jeder Test folgt diesem Muster:

```
1. CALL:    Tool mit den im TEST-PLAN.md beschriebenen Parametern aufrufen
2. ASSERT:  Ergebnis prüfen (status, Felder, Werte)
3. VERIFY:  Bei mutierenden Tests: Dateien lesen/greppen, compile errors prüfen
4. RECORD:  Ergebnis dokumentieren (PASS / FAIL / ERROR + Details)
```

### Assertions

- **status:** Prüfe `status == "SUCCESS"` im Tool-Ergebnis
- **Cross-Projekt:** Bei Refactorings IMMER prüfen ob `fixture-external` auch aktualisiert wurde
- **Compile-Fehler:** Nach jedem mutierenden Test `jdt_get_compilation_errors` für alle betroffenen Projekte
- **Grep:** Nach Rename/Move mit Grep verifizieren dass alter Name weg und neuer Name vorhanden

### Fehlerbehandlung

- **Tool gibt Fehler zurück:** Fehler dokumentieren (exceptionType, message), Test als FAIL markieren
- **Unerwarteter NPE:** Stack-Trace aus Server-Log lesen falls verfügbar
- **"potential matches" Warning:** Ist OK wenn der Test trotzdem durchläuft (status=SUCCESS)
- **"out of sync":** `jdt_refresh_project()` aufrufen und Test wiederholen (1x)

## 5. Ergebnisse dokumentieren

Erstelle eine Ergebnistabelle:

```markdown
## Testergebnis v<VERSION> (<DATUM>)

| Test | Tool | Ergebnis | Details |
|------|------|----------|---------|
| A1 | jdt_get_version | ✅ PASS | v0.x.y |
| A2 | jdt_list_projects | ✅ PASS | 6 Projekte |
| ... | ... | ... | ... |
| I2 | jdt_rename_element CLASS | ❌ FAIL | ExternalService nicht aktualisiert |
| I5 | jdt_move_type | ⚠️ WARNING | "potential matches" aber SUCCESS |
```

### Zusammenfassung am Ende

```markdown
### Zusammenfassung
- **Getestet:** <Anzahl> von 52 Tools
- **PASS:** <n>
- **FAIL:** <n> (Liste der fehlgeschlagenen Tools)
- **SKIP:** <n> (warum?)
- **Neue Bugs:** (falls gefunden)
- **Behobene Bugs:** (falls vorher bekannt und jetzt OK)
```

## 6. Spezielle Hinweise

### Cross-Projekt ist der wichtigste Test-Aspekt

Das Besondere an diesem Test-Setup ist die Trennung in `fixture-parent` und `fixture-external`.
Die meisten Bugs treten bei **Cross-Projekt-Refactoring** auf (zwei unabhängig importierte Projekte).
Prüfe bei JEDEM Refactoring-Test (Gruppe I) ob Änderungen in `fixture-external` ankommen.

### Offsets für Inline/Extract/ConvertToLambda

Manche Tools brauchen Byte-Offsets. So ermittelst du sie:

```
1. jdt_parse_java_file(filePath=...) → Methoden/Felder mit Source-Ranges
2. Datei lesen, Zeile finden, Bytes bis zur Zielposition zählen
3. Alternativ: head -<zeile-1> <datei> | wc -c + Spaltenposition
```

### fixture-broken hat absichtliche Fehler

`BrokenClass.java` kompiliert NICHT. Das ist gewollt für:
- `jdt_get_compilation_errors` → muss Fehler finden
- `jdt_organize_imports` auf `UnusedStuff.java` → muss funktionieren trotz Fehler im selben Modul

### Import-Tests (Gruppe F) brauchen eigene Workspaces

Für Gradle/Eclipse/Plain-Import-Tests: Kopiere das jeweilige Fixture in ein frisches Temp-Dir
und importiere nur dieses. Nicht im Haupt-Workspace mischen.
