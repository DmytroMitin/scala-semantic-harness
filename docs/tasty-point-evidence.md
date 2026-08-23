# TASTy point evidence

`tasty-point-evidence` is an opt-in CLI-only alpha-3 SNAPSHOT operation for one
Scala 3 source point. It closes a specific post-compile evidence gap without
adding a ninth MCP tool or replaying a target compiler plugin in the inspector.

```bash
semantic-scala tasty-point-evidence \
  --workspace <dir> \
  --sbt-project <id> \
  --file <workspace-relative.scala> \
  --line <one-based> \
  --col <one-based-UTF16> \
  [--sbt-java-home <absolute-installed-jdk-home>] \
  --json
```

The project selector is required and configuration is fixed to `Compile`.
The command does not accept caller-selected TASTy files, output directories,
classpath entries, compiler jars, compiler options, or plugins.

## Evidence and freshness

One bounded sbt child owns the authoritative selected `Compile` and a private
versioned receipt containing compile outcome, target Scala version, selected
Compile sources/output, dependency classpath, and optional target-Java context.
The requested source is hashed before compilation and again after artifact
capture and inspection. A byte change produces `SourceChangedDuringRequest`;
old artifacts are never used after compile failure.

Candidate `.tasty` files are accepted only under the receipt output directory.
Symlinks fail closed. The v1 bounds are 512 candidates, 8 MiB per artifact,
and 64 MiB aggregate. The exact selected artifact is represented by its byte
size and SHA-256 digest, never an absolute path.

## Exact inspector boundary

Stable three-component Scala 3 versions resolve only the official
`org.scala-lang:scala3-tasty-inspector_3` coordinate from Maven Central. A
product-owned worker source is compiled and run with that exact toolchain in
temporary child JVMs. The worker has fixed time, output, and heap bounds; its
environment is cleared; private protocols are versioned and byte-bounded; and
temporary state is deleted deterministically.

The inspector receives no target `scalacOptions`, `-Xplugin`, `-P:` options,
plugin entry points, or handlers. The earlier authoritative target Compile can
execute target build and plugin code. Process separation contains ordinary
faults and resources; it is not a security sandbox.

Containing typed trees must report the requested source and contain the exact
one-based UTF-16 point. Selection is deterministic: smallest source span,
symbol-bearing tree, later start, earlier end, lexical kind, then lexical
receipt-relative artifact identity. Name-only matches are never selected.

## Result and exit semantics

The JSON schema is `semantic-scala.tasty-point-evidence.v1`. Domain statuses
are `Resolved`, `NoTypedTreeAtPoint`, `CompileFailed`, `ArtifactUnavailable`,
`SourceChangedDuringRequest`, `UnsupportedTargetScala`,
`InspectorUnavailable`, and `InspectorFailed`. A completed domain result is
JSON with exit code 0. Input validation, receipt/protocol, and launch failures
use a nonzero exit.

The result proves only the recorded selected-project/source/artifact request.
It is not whole-workspace atomicity, a live pre-compile query, Scala 2 support,
or a general compiler-plugin semantic claim.
