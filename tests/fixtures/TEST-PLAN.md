# JDT MCP Server — Comprehensive Test Plan

## Overview

This test plan covers **all 52 JDT MCP tools** using the fixture projects.

### Fixture Projects

Two **independent** project trees (like real-world rdf/ + platform/):

```
tests/fixtures/
├── fixture-parent/          ← Maven multi-module (imported as 1st project tree)
│   ├── fixture-api/         ← Interfaces, annotations, enums
│   ├── fixture-core/        ← Implementations (depends on api)
│   ├── fixture-app/         ← Cross-module callers, Main (depends on core)
│   ├── fixture-broken/      ← Intentional compile errors, unused code
│   ├── fixture-gradle/      ← Import test: Gradle project
│   ├── fixture-eclipse/     ← Import test: Eclipse .project
│   └── fixture-plain/       ← Import test: plain Java directory
└── fixture-external/        ← Separate Maven project (imported as 2nd project tree)
                               depends on fixture-api + fixture-core via workspace resolution
```

| Project | Type | Import | Purpose |
|---------|------|--------|---------|
| `fixture-parent` | Maven parent | `jdt_import_project(fixture-parent/)` | Multi-module container |
| `fixture-api` | Maven module | (auto, child of parent) | Interfaces, annotations, enums |
| `fixture-core` | Maven module | (auto, child of parent) | Implementations, refactoring targets |
| `fixture-app` | Maven module | (auto, child of parent) | Cross-module callers, Main class |
| `fixture-broken` | Maven module | (auto, child of parent) | Intentional compile errors, unused code |
| `fixture-gradle` | Gradle project | `jdt_import_project(fixture-gradle/)` | Import type test |
| `fixture-eclipse` | Eclipse project | `jdt_import_project(fixture-eclipse/)` | Import type test |
| `fixture-plain` | Plain Java | `jdt_import_project(fixture-plain/)` | Import type test |
| **`fixture-external`** | **Separate Maven** | **`jdt_import_project(fixture-external/)`** | **Cross-project refactoring (like rdf/ vs platform/)** |

### Key Classes and Their Test Roles

| Class | Project | Used to test |
|-------|---------|-------------|
| `Processor<T>` | api | Interface: findImplementations, findReferences, getTypeHierarchy, getJavadoc |
| `Configurable` | api | 2nd interface: implementInterface, extractInterface |
| `Priority` | api | Enum: findType, createEnum comparison |
| `@Tracked` | api | Custom annotation: findAnnotatedElements |
| `@Auditable` | api | Annotation with params: getAnnotations |
| `BaseProcessor<T>` | core | Abstract class: type hierarchy, findImplementations |
| `SimpleProcessor` | core | Main refactoring target: rename, extract, encapsulate, generate* |
| `BatchProcessor` | core | convertToLambda (anon Comparator), method ref (::), unused code |
| `ProcessorFactory` | core | findCallers, inline, introduceParameter |
| `DataHolder` | core | generate*, encapsulateField |
| `HelperUtil` | core | moveType target |
| `AppService` | app | Cross-module caller (same parent), generateJavadoc |
| `Main` | app | runMain |
| `BrokenClass` | broken | getCompilationErrors |
| `UnusedStuff` | broken | findUnusedCode, organizeImports |
| **`ExternalService`** | **external** | **Cross-project refs (separate import), rename/move across project boundaries** |

---

## Setup

```
1. Copy tests/fixtures/fixture-parent/ AND tests/fixtures/fixture-external/ to a temp workspace directory
2. jdt_import_project(path=<workspace>/fixture-parent)   → Maven multi-module (4 modules + parent)
3. jdt_import_project(path=<workspace>/fixture-external)  → Separate Maven project
4. jdt_list_projects → verify: fixture-api, fixture-core, fixture-app, fixture-broken, fixture-external
5. Wait for indexing (poll jdt_get_compilation_errors on fixture-api until 0 errors)
6. Verify cross-project resolution: jdt_get_classpath(fixture-external) → projectDependencies contains fixture-api AND fixture-core
```

**Important:** fixture-parent and fixture-external are **independently imported** (two separate `jdt_import_project` calls), just like rdf/ and platform/ in the real workspace. JDT resolves dependencies via workspace project matching, not via Maven repository.

---

## Test Groups

Tests are organized into **independent groups** that can run **in parallel** where noted.
Within each group, tests run sequentially.

### Parallelization Strategy

Read-only tests (Groups A-E) can all run in parallel since they don't modify files.
Mutating tests (Groups F-K) should run sequentially, each starting from a clean fixture copy.

```
 PARALLEL ─┬─ Group A: ProjectInfoTools (read-only)
            ├─ Group B: CodeAnalysisTools (read-only)
            ├─ Group C: NavigationTools (read-only)
            ├─ Group D: DocumentationTools (read-only, except generateJavadoc)
            ├─ Group E: CodeQualityTools (read-only)
            └─ Group F: Import Tests (separate workspace)

 SEQUENTIAL ─── Group G: CreationTools
                Group H: CodeGenerationTools
                Group I: RefactoringTools
                Group J: ExecutionTools
                Group K: reload_workspace (last, destroys state)
```

---

## Group A: ProjectInfoTools (read-only)

### A1: jdt_get_version
```
Call: jdt_get_version()
Assert: response has "version" and "serverName" fields
Assert: version matches semver pattern
```

### A2: jdt_list_projects
```
Call: jdt_list_projects()
Assert: projectCount >= 5 (4 Maven modules + parent)
Assert: projects contain "fixture-api", "fixture-core", "fixture-app", "fixture-broken"
Assert: each has javaVersion, location, mavenArtifactId
```

### A3: jdt_get_classpath
```
Call: jdt_get_classpath(projectName="fixture-core")
Assert: projectDependencies contains path with "fixture-api"
Assert: sourceFolders non-empty
Assert: jreVersion present
```

### A4: jdt_get_compilation_errors
```
Call: jdt_get_compilation_errors(projectName="fixture-broken")
Assert: errorCount >= 3 (type mismatch, unknown type, missing import)
Assert: errors reference "BrokenClass.java"

Call: jdt_get_compilation_errors(projectName="fixture-api")
Assert: errorCount == 0
```

### A5: jdt_get_project_structure
```
Call: jdt_get_project_structure(projectName="fixture-core")
Assert: sourceFolders contain "src/main/java" and "src/test/java"
Assert: packages include "org.fixture.core" and "org.fixture.core.internal"
```

### A6: jdt_refresh_project
```
Call: jdt_refresh_project(projectName="fixture-api")
Assert: status == "SUCCESS"
```

### A7: jdt_maven_update_project
```
Call: jdt_maven_update_project(projectName="fixture-core")
Assert: status == "SUCCESS"
```

### A8: Test-scope Dependencies im Classpath (#46)
```
Call: jdt_get_classpath(projectName="fixture-core")
Assert: libraries contain "junit-jupiter-api" JAR
Assert: libraries contain "junit-jupiter-engine" JAR
Assert: libraries contain "opentest4j" JAR
Assert: library count >= 8 (JUnit 5 + transitive deps)

Call: jdt_get_compilation_errors(projectName="fixture-core")
Assert: errorCount == 0 (no "org.junit cannot be resolved" errors in test files)

Call: jdt_get_compilation_errors(projectName="fixture-external")
Assert: errorCount == 0 (no JUnit-related errors)
```

---

## Group B: CodeAnalysisTools (read-only)

### B1: jdt_parse_java_file
```
Call: jdt_parse_java_file(filePath="<WS>/fixture-core/.../SimpleProcessor.java")
Assert: packageName == "org.fixture.core"
Assert: types[0].name == "SimpleProcessor"
Assert: types[0].superclass contains "BaseProcessor"
Assert: types[0].interfaces contains "Configurable"
Assert: methods include "process", "canHandle", "configure", "formatResult", "processAndFormat"
Assert: fields include "priority", "config", "publicField"
```

### B2: jdt_get_type_hierarchy
```
Call: jdt_get_type_hierarchy(className="org.fixture.core.SimpleProcessor")
Assert: hierarchy includes BaseProcessor, Object
Assert: interfaces include Processor, Configurable
```

### B3: jdt_find_references (cross-module AND cross-project)
```
Call: jdt_find_references(elementName="org.fixture.api.Processor", elementType="TYPE")
Assert: referenceCount >= 4
Assert: references in fixture-core AND fixture-app (cross-module) AND fixture-external (cross-project)

Call: jdt_find_references(elementName="org.fixture.core.HelperUtil", elementType="TYPE")
Assert: references in fixture-app AND fixture-external (both use HelperUtil)

Call: jdt_find_references(elementName="org.fixture.core.ProcessorFactory#createDefault", elementType="METHOD")
Assert: callers in fixture-app (AppService), fixture-app (Main), AND fixture-external (ExternalService)
```

### B4: jdt_get_source_range
```
Call: jdt_get_source_range(elementName="org.fixture.core.SimpleProcessor#formatResult", elementType="METHOD")
Assert: source contains "String prefix"
Assert: source contains "String suffix"
```

---

## Group C: NavigationTools (read-only)

### C1: jdt_find_type
```
Call: jdt_find_type(pattern="*Processor")
Assert: matches include SimpleProcessor, BatchProcessor, BaseProcessor
Assert: each has fullyQualifiedName, file

Call: jdt_find_type(pattern="Configurable")
Assert: match has isInterface=true
```

### C2: jdt_get_method_signature
```
Call: jdt_get_method_signature(className="org.fixture.core.SimpleProcessor", methodName="process")
Assert: 1 parameter "item" of type String, returnType String

Call: jdt_get_method_signature(className="org.fixture.core.SimpleProcessor", methodName="*")
Assert: lists all methods (>= 8)
```

### C3: jdt_find_implementations
```
Call: jdt_find_implementations(typeName="org.fixture.api.Processor")
Assert: implementations include BaseProcessor, SimpleProcessor, BatchProcessor
```

### C4: jdt_find_callers (cross-project)
```
Call: jdt_find_callers(className="org.fixture.core.ProcessorFactory", methodName="createDefault")
Assert: callerCount >= 3 (ProcessorFactory.create, AppService, Main.main, ExternalService)
Assert: callers span multiple modules AND separate projects (fixture-external)
```

---

## Group D: DocumentationTools (read-only parts)

### D1: jdt_get_javadoc
```
Call: jdt_get_javadoc(elementName="org.fixture.api.Processor", elementType="CLASS")
Assert: hasJavadoc == true
Assert: javadoc contains "@param <T>"

Call: jdt_get_javadoc(elementName="org.fixture.core.DataHolder#id", elementType="FIELD")
Assert: hasJavadoc == false
```

### D2: jdt_get_annotations
```
Call: jdt_get_annotations(elementName="org.fixture.core.SimpleProcessor", elementType="CLASS")
Assert: includes @Auditable with value="processor", enabled=true

Call: jdt_get_annotations(elementName="org.fixture.core.SimpleProcessor#process", elementType="METHOD")
Assert: includes @Override
```

### D3: jdt_find_annotated_elements
```
Call: jdt_find_annotated_elements(annotationName="Tracked")
Assert: finds BaseProcessor, BatchProcessor, AppService, ExternalService (4 classes, across projects)

Call: jdt_find_annotated_elements(annotationName="Auditable", projectName="fixture-core")
Assert: finds SimpleProcessor, DataHolder
```

---

## Group E: CodeQualityTools (read-only)

### E1: jdt_find_unused_code
```
Call: jdt_find_unused_code(projectName="fixture-broken")
Assert: finds unused imports (List, Map, Processor) in UnusedStuff.java
Assert: finds unused fields (unusedField1, unusedField2)
Assert: finds unused methods (unusedMethod1, unusedMethod2)

Call: jdt_find_unused_code(projectName="fixture-core")
Assert: finds unusedPrivateMethod and unusedCounter in BatchProcessor
```

### E2: jdt_find_dead_code
```
Call: jdt_find_dead_code(projectName="fixture-core")
(Assert based on what JDT detects)
```

---

## Group F: Import Tests (separate workspace per test)

Each import test copies the specific fixture project to a fresh temp dir.

### F1: jdt_import_project — Maven multi-module
```
Copy: fixture-parent/ → temp dir
Call: jdt_import_project(path=<temp>)
Assert: importedCount >= 5 (parent + 4 modules)
Call: jdt_list_projects → verify all modules
```

### F2: jdt_import_project — Gradle
```
Copy: fixture-gradle/ → temp dir
Call: jdt_import_project(path=<temp>/fixture-gradle)
Assert: status == "SUCCESS"
Call: jdt_find_type(pattern="GradleService")
Assert: found with fullyQualifiedName "org.fixture.gradle.GradleService"
```

### F3: jdt_import_project — Eclipse .project
```
Copy: fixture-eclipse/ → temp dir
Call: jdt_import_project(path=<temp>/fixture-eclipse)
Assert: status == "SUCCESS"
Call: jdt_find_type(pattern="EclipseService")
Assert: found
```

### F4: jdt_import_project — Plain Java
```
Copy: fixture-plain/ → temp dir
Call: jdt_import_project(path=<temp>/fixture-plain)
Assert: status == "SUCCESS"
Call: jdt_find_type(pattern="PlainService")
Assert: found
```

### F5: jdt_remove_project
```
Call: jdt_remove_project(projectName="fixture-broken", deleteContents=false)
Assert: status == "SUCCESS"
Call: jdt_list_projects → fixture-broken gone
Call: jdt_import_project to re-add it
```

---

## Group G: CreationTools (mutating, sequential)

### G1: jdt_create_class
```
Call: jdt_create_class(projectName="fixture-core", packageName="org.fixture.core", className="NewService")
Assert: status == "SUCCESS"
Verify: jdt_find_type(pattern="NewService") finds it
```

### G2: jdt_create_interface
```
Call: jdt_create_interface(projectName="fixture-api", packageName="org.fixture.api", interfaceName="Cacheable")
Assert: status == "SUCCESS"
Verify: jdt_find_type(pattern="Cacheable") finds it
```

### G3: jdt_create_enum
```
Call: jdt_create_enum(projectName="fixture-api", packageName="org.fixture.api", enumName="Status")
Assert: status == "SUCCESS"
Verify: jdt_parse_java_file shows isEnum=true
```

---

## Group H: CodeGenerationTools (mutating, sequential)

Target: `DataHolder` class (starts with 4 public fields, no methods)

### H1: jdt_add_field
```
Call: jdt_add_field(className="org.fixture.core.DataHolder", fieldName="description", fieldType="String", visibility="private")
Assert: status == "SUCCESS"
```

### H2: jdt_add_method
```
Call: jdt_add_method(className="org.fixture.core.DataHolder", methodName="reset", returnType="void", body="this.count = 0;\nthis.active = false;")
Assert: status == "SUCCESS"
```

### H3: jdt_add_import
```
Call: jdt_add_import(className="org.fixture.core.DataHolder", imports="java.util.List, java.util.Optional")
Assert: totalAdded == 2
Call again: assert skippedImports contains both (already exist)
```

### H4: jdt_generate_getters_setters
```
Call: jdt_generate_getters_setters(className="org.fixture.core.DataHolder", fieldNames="id,name")
Assert: generates getId, setId, getName, setName
```

### H5: jdt_generate_constructor
```
Call: jdt_generate_constructor(className="org.fixture.core.DataHolder", fieldNames="id,name")
Assert: constructor generated
```

### H6: jdt_generate_equals_hashcode
```
Call: jdt_generate_equals_hashcode(className="org.fixture.core.DataHolder", fieldNames="id")
Assert: equals and hashCode generated
```

### H7: jdt_generate_tostring
```
Call: jdt_generate_tostring(className="org.fixture.core.DataHolder")
Assert: toString generated
```

### H8: jdt_implement_interface
```
Call: jdt_implement_interface(className="org.fixture.core.DataHolder", interfaceName="java.io.Serializable")
Assert: status == "SUCCESS"
```

### H9: jdt_generate_delegate_methods
```
Call: jdt_generate_delegate_methods(className="org.fixture.core.SimpleProcessor", fieldName="config")
Assert: generates delegation methods from Map
```

---

## Group I: RefactoringTools (mutating, sequential, fresh fixture copy per test)

### I1: jdt_rename_element — FIELD
```
Call: jdt_rename_element(elementName="org.fixture.core.SimpleProcessor#publicField", newName="visibleField", elementType="FIELD")
Assert: status == "SUCCESS"
Verify: grep "visibleField" in SimpleProcessor.java
```

### I2: jdt_rename_element — CLASS (cross-module + cross-project)
```
Call: jdt_rename_element(elementName="org.fixture.core.HelperUtil", newName="StringUtil", elementType="CLASS")
Assert: status == "SUCCESS"
Verify: AppService.java imports/uses StringUtil (cross-module, same parent)
Verify: ExternalService.java imports/uses StringUtil (cross-project, separate import!)
Verify: 0 compile errors in fixture-app AND fixture-external
```

### I3: jdt_rename_element — METHOD (cross-project)
```
Call: jdt_rename_element(elementName="org.fixture.core.ProcessorFactory#createDefault", newName="createStandard", elementType="METHOD")
Assert: status == "SUCCESS"
Verify: callers in AppService, Main (cross-module) AND ExternalService (cross-project) updated
Verify: 0 compile errors in all projects
```

### I4: jdt_rename_element — METHOD on interface (cross-project)
```
Call: jdt_rename_element(elementName="org.fixture.api.Processor#process", newName="execute", elementType="METHOD")
Assert: status == "SUCCESS"
Verify: all implementations (BaseProcessor, SimpleProcessor, BatchProcessor) updated
Verify: callers in AppService, Main AND ExternalService updated
Verify: "potential matches" does NOT block
Verify: 0 compile errors in ALL projects (api, core, app, external)
```

### I5: jdt_move_type (cross-project)
```
Call: jdt_move_type(typeName="org.fixture.core.HelperUtil", targetPackage="org.fixture.core.internal")
Assert: status == "SUCCESS"
Verify: AppService import updated (cross-module)
Verify: ExternalService import updated (cross-project!)
Verify: 0 compile errors in fixture-app AND fixture-external
Note: InternalHelper already exists in org.fixture.core.internal — HelperUtil moves alongside it
```

### I6: jdt_extract_method
```
Parse SimpleProcessor.java to get offset of formatResult body (lines with prefix, suffix, result)
Call: jdt_extract_method(filePath=<path>, startOffset=<start>, endOffset=<end>, methodName="buildFormattedString")
Assert: status == "SUCCESS"
```

### I7: jdt_extract_interface
```
Call: jdt_extract_interface(className="org.fixture.core.SimpleProcessor", interfaceName="ProcessorService", methodNames=["formatResult", "processAndFormat"])
Assert: status == "SUCCESS"
Verify: ProcessorService.java created
Verify: SimpleProcessor implements ProcessorService
```

### I8: jdt_inline
```
Parse ProcessorFactory.java, find offset of "name" variable in createNamed()
Call: jdt_inline(filePath=<path>, offset=<offset>, preview=true)
Assert: status == "SUCCESS" (or document if NPE persists)
```

### I9: jdt_change_method_signature
```
Call: jdt_change_method_signature(className="org.fixture.app.AppService", methodName="runWithPriority", addParameters=[{"type": "boolean", "name": "verbose", "defaultValue": "false"}])
Assert: status == "SUCCESS" (or document error)
Verify: callers updated with default value
```

### I10: jdt_convert_to_lambda
```
Parse BatchProcessor.java, find offset inside anonymous Comparator
Call: jdt_convert_to_lambda(filePath=<path>, offset=<offset>)
Assert: status == "SUCCESS"
Verify: anonymous class replaced with lambda (a, b) -> a.compareTo(b)
```

### I11: jdt_encapsulate_field
```
Call: jdt_encapsulate_field(className="org.fixture.core.SimpleProcessor", fieldName="publicField")
Assert: status == "SUCCESS"
Verify: field now private, getter/setter created
```

### I12: jdt_introduce_parameter
```
Parse ProcessorFactory.java, find offset of "default-processor" string literal in getDefaultName()
Call: jdt_introduce_parameter(filePath=<path>, offset=<offset>, length=<len>, parameterName="defaultName")
Assert: status == "SUCCESS"
```

### I13: jdt_organize_imports
```
Call: jdt_organize_imports(filePath="<path to UnusedStuff.java>")
Assert: unused imports removed (List, Map, Processor)
```

---

## Group J: ExecutionTools (mutating, sequential)

### J1: jdt_maven_build
```
Call: jdt_maven_build(projectName="fixture-api", goals="clean compile", skipTests=true)
Assert: status == "SUCCESS" or output contains "BUILD SUCCESS"
```

### J2: jdt_run_main
```
Prerequisite: fixture-app compiled
Call: jdt_run_main(className="org.fixture.app.Main", args="arg1 arg2")
Assert: output contains "Processed:" and "Args:"
```

### J3: jdt_list_tests
```
Call: jdt_list_tests(projectName="fixture-core")
Assert: finds SimpleProcessorTest, BatchProcessorTest
Assert: methods include testProcess, testCanHandle, testConfigure, testFilterNonEmpty

Call: jdt_list_tests(projectName="fixture-external")
Assert: finds ExternalServiceTest
Assert: methods include testExecute, testPriority, testExecuteAndFormat
```

### J4: jdt_run_tests
```
Call: jdt_run_tests(projectName="fixture-core", className="SimpleProcessorTest")
Assert: all tests pass

Call: jdt_run_tests(projectName="fixture-core", className="SimpleProcessorTest", methodName="testProcess")
Assert: 1 test executed, passed

Call: jdt_run_tests(projectName="fixture-external", className="ExternalServiceTest")
Assert: all 3 tests pass (testExecute, testPriority, testExecuteAndFormat)
Note: testExecuteAndFormat verifies cross-project method calls work at runtime
```

### J5: jdt_start_tests_async + jdt_get_test_result
```
Call: jdt_start_tests_async(projectName="fixture-core", className="BatchProcessorTest")
Assert: returns runId

Call: jdt_get_test_result(runId=<from above>)
Assert: tests completed, all passed
```

---

## Group K: Workspace Management (destructive, run last)

### K1: jdt_reload_workspace
```
Call: jdt_reload_workspace()
Assert: status == "SUCCESS"
Assert: importedCount includes all previously loaded projects
(Known issue: may only import top-level project)
```

### K2: jdt_generate_javadoc (mutating)
```
Call: jdt_generate_javadoc(elementName="org.fixture.app.AppService#runWithPriority", elementType="METHOD")
Assert: generated javadoc contains @param input, @param priority, @return
Verify: jdt_get_javadoc now returns hasJavadoc=true
```

---

## Parallelization Notes for Test Agent

When an AI agent runs these tests, it can maximize throughput:

1. **Start with Setup** — import fixture-parent, wait for indexing
2. **Run Groups A-E in parallel** — all read-only, no conflicts
3. **Run Group F in parallel** — each test uses its own workspace copy
4. **Run Groups G, H sequentially** — they build on each other (DataHolder gets fields, then methods, then getters...)
5. **Run Group I tests independently** — each refactoring test should start from a **clean fixture copy** (git checkout or re-copy), so they can theoretically run in parallel with separate workspaces. In practice, with a single JDT server, run sequentially.
6. **Run Group J** — needs compiled code
7. **Run Group K last** — reload_workspace is destructive

### Fresh Fixture for Mutating Tests

For Groups G-I, the test agent should:
```
1. git checkout -- fixture-parent/   (or re-copy from clean snapshot)
2. jdt_refresh_project (all projects)
3. Run the test
4. Verify results
5. Repeat for next test
```
