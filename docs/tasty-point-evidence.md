# TASTy point evidence

`tasty-point-evidence` is an opt-in CLI-only alpha-3 SNAPSHOT operation for one
Scala 3 source point. It provides normalized, bounded post-compile evidence
without adding a ninth MCP tool or replaying a target compiler plugin in the
inspector.

The harness host and linked Presentation Compiler use Scala 3.9.0, but this
operation takes its compiler authority from the selected target receipt. Its
product-owned inspector is compiled and run against that target's acquired
exact stable Scala 3 line; it does not reuse the host compiler version as a
substitute.

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

The private receipt is mechanically qualified on sbt 1.12.15 and 2.0.6. It
uses one fixed selected-project command sequence, classifies Compile success
through sbt's task-result API rather than console text, and reports
`CompileFailed` as a domain result. Dependency classpath entries use the
build's supported file conversion; no virtual-file ID, cache layout, or log is
used to guess an artifact path.

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
or a general compiler-plugin semantic claim. Frozen successful evidence now
includes Macro-Paradise on sbt 1.12.15 / Scala 3.8.4 and an independent
scala-newtype-compat plugin consumer on sbt 2.0.6 / Scala 3.7.4, both with
inspector replay flags false. Two version-specific cases keep the broader
compiler-plugin dimension evidence-partial.

## Marginal-utility boundary

On the frozen Macro-Paradise target above, strong ordinary tooling also
recovered fact-equivalent point evidence: a fresh selected Compile, standard
Scala 3.8.4 `-print-tasty`, source-offset mapping, and `javap` supplied the
same decision-relevant generated C/M/F/S facts. semantic-scala materially
improved normalization, deterministic point selection, explicit uncertainty,
same-request source stability, artifact identity, and no-replay provenance,
but it did not supply a fact missing from that baseline.

This is one target/toolchain comparison, not a claim that TASTy evidence is
generally redundant or that semantic-scala is generally better or worse than
Metals, IntelliJ, or raw Scala tooling. It supports composition, robustness,
and provenance value rather than a demonstrated primitive moat. A future
primitive-differentiation claim needs a materially different case where strong
ordinary tooling leaves a decision-relevant fact gap. See
[`early-feedback.md`](early-feedback.md) for the comparison packet requested
from real-project evaluators.
