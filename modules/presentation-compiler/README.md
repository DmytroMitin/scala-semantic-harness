# presentation-compiler

Minimal dynamic semantic queries backed by the Scala 3 presentation compiler.

Current command surface:

```bash
semantic-scala symbol-at --file <path> --line <n> --col <n> --json
semantic-scala infer-type --file <path> --line <n> --col <n> \
  [--workspace <path>] [--classpath <entry>]... --json
semantic-scala infer-type --file <path> --line <n> --col <n> \
  --workspace <path> --sbt-project <id> \
  --sbt-configuration Compile|Test \
  [--sbt-cache-mode fresh|refresh|reuse] \
  [--sbt-java-home <absolute-directory>] --json
semantic-scala infer-type-batch --requests <batch-request.json> \
  --workspace <path> --sbt-project <id> \
  --sbt-configuration Compile|Test \
  [--sbt-cache-mode fresh|refresh|reuse] \
  [--sbt-java-home <absolute-directory>] --json
```

Line and column inputs are one-based for CLI ergonomics. The implementation
converts UTF-16 columns to the zero-based offset expected by the presentation
compiler, including LF/CRLF, end-of-line, and EOF boundaries.

`infer-type` is backed by the pinned compiler's public `hover` API. Its primary
meaning is the compiler-rendered hover type/signature at one point: an
explicitly labelled expression type when available, otherwise the public
symbol signature, otherwise one leading Scala hover code block. The result
records the rendering kind, query position, optional compiler range, safe
context summary, and deterministic warnings. It is not canonical type identity
or proof that the source or whole project compiles.

No `--classpath` preserves narrow-runtime mode. Each repeatable
`--classpath <entry>` is one existing compiled classes directory or JAR.
Entries are normalized, de-duplicated in caller order, and validated. An
optional existing `--workspace` directory supplies presentation-compiler
folder context only. Production code never invokes a build, parses a classpath
list, scans targets, or discovers/downloads dependencies. The built-in Scala
runtime is also present in explicit mode; `classpathEntryCount` counts only
normalized caller-supplied entries, not that complete effective classpath.

The CLI can now acquire entries from an explicitly selected sbt
project/configuration before calling this module. That orchestration lives in
CLI and sbt-runner: presentation-compiler still receives ordinary explicit
entries, has no sbt-runner dependency, and never launches sbt. Sbt-backed
public context reports `SbtClasspath`, project, configuration, entry count,
`acquisitionOrigin`, and `freshnessAssessment` without paths. Fresh/refresh
use `FreshSbt` / `FreshBySbtEvaluation`; explicit evidence-validated reuse uses
`CachedExplicitReuse` / `ReusedWithMatchingEvidence`. Those optional fields
preserve historical v1 decoding and remain absent in narrow/manual context.
No cache path, hash, timestamp, or age is public.

An explicit `--sbt-java-home` selects an already-installed Java only for the
target sbt child used to acquire the classpath. No selector keeps the existing
v1 acquisition/cache behavior. Explicit selection uses an isolated v2 context
so it cannot reuse no-selector or other-home records. The presentation
compiler module still sees only ordinary classpath entries; the selected home
and bounded probe evidence are not public context fields.

Public JSON schema `semantic-scala.infer-type-result.v1` reports `Resolved` or
neutral `Unresolved`, rendering kind (`ExpressionType`, `SymbolSignature`,
`HoverCode`, or `NoRendering`), one-based UTF-16 query position, optional
zero-based LSP range, and safe context facts. It never exposes explicit paths
or raw hover Markdown.

The batch request/result markers are
`semantic-scala.infer-type-batch-request.v1` and
`semantic-scala.infer-type-batch-result.v1`. Batch input is strict, ordered,
nonempty, and bounded at 128 requests. Stable item outcomes are `Resolved`,
neutral `Unresolved`, `InvalidRequest`, and `QueryFailure`; item failures do
not cancel later items after shared context acquisition.

Each batch selects one presentation compiler reused sequentially across its
items. Controlled public-API comparison matched per-item compiler behavior
across files, duplicate positions, source errors, and mixed failures while
materially reducing 4/16/64-item latency. The compiler is created and shut down
exactly once; there are no parallel requests or process-global sessions.
Each item source is captured once for that query and checked afterward. This
does not provide an atomic workspace snapshot.

## Limits

- Single-query input remains one source; batch input supports up to 128 ordered
  items across workspace-contained sources.
- No Metals, BSP, LSP, TASTy, MCP, graph storage, vector search, or FP analyzer
  integration.
- No project-wide workspace indexing.
- No classpath inference beyond narrow runtime entries plus caller-supplied
  explicit compiled entries.
- Canonical symbol strings are returned when the compiler API provides them.
- Empty hover cannot distinguish whitespace/comments, source errors, and
  incomplete classpath; `Unresolved` reports this ambiguity without a cause.
- Compiled classpath entries do not provide an uncompiled sibling-source graph
  or unsaved workspace state.
- Eight selected real CLI/presentation source queries resolved against
  compiled staged sibling output, but this does not prove live/uncompiled
  sibling-source support.
- No project/configuration inference, daemon/long-lived process-global
  compiler, or `infer-type` MCP tool exists. The batch compiler lives only for
  one command. The private classpath cache is owned entirely by sbt-runner and
  CLI orchestration.
- Sbt-backed orchestration does not acquire compiler options/plugins and does
  not add a live sibling-source model.
