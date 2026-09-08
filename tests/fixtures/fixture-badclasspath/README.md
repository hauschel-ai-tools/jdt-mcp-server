# fixture-badclasspath

Eclipse project (no Maven) whose `.classpath` points at `lib/missing-library.jar`, which does
not exist. JDT then reports two different things:

- a build path problem (`org.eclipse.jdt.core.buildpath_problem`) naming the missing library,
- a Java problem (`org.eclipse.jdt.core.problem`) on the project saying only
  "The project cannot be built until build path errors are resolved".

Used by `tests/buildpath-test.sh` to prove `jdt_get_compilation_errors` reports the first one
too (issue #115).
