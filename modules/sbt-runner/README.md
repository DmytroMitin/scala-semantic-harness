# sbt-runner

Process integration for the build/test JSON oracle and deterministic
project-classpath acquisition.

`SbtClasspathAcquirer` accepts an existing workspace, a project ID matching
`[A-Za-z][A-Za-z0-9_-]*`, and exact configuration `Compile` or `Test`. It
creates an isolated temporary sbt global base, injects a private task, selects
the project, evaluates the scoped `fullClasspath`, and reads a dedicated
versioned line protocol. It never parses human `show` output and does not
modify the target build definition.

The result contains ordered, normalized, de-duplicated `Directory`/`Jar`
entries. Project/configuration/header/path mismatches and missing or unsupported
entries are protocol failures; validation, process, and protocol failures stay
separate. The default process timeout is 180 seconds. Temporary settings and
the private protocol result are removed after success or failure.

Classpath evaluation may compile or refresh outputs and may run ordinary build
logic. Successful sbt logs are discarded; failures use bounded diagnostics.
No classpath paths are exposed by this module's public CLI projection.

`SbtClasspathCacheService` adds three explicit orchestration modes:

- `fresh` delegates to current acquisition and never accesses persistent
  cache state;
- `refresh` holds the exact-key lock, hashes conventional inputs before and
  after sbt acquisition, hashes all acquired JAR/directory content, and
  atomically replaces the private record only after complete validation;
- `reuse` holds the same lock, strictly reads the exact record, recomputes all
  covered evidence, and returns the recorded ordered entries only on an exact
  match. It never invokes sbt.

CLI batch orchestration calls this service exactly once per ordered batch.
Fresh still acquires once and never accesses persistent cache; refresh
acquires/publishes once; reuse validates one exact entry once without sbt.
The service remains unaware of request items and presentation-compiler
lifecycle, so no cache operation is repeated per item.

Production storage is outside workspaces at absolute
`$XDG_CACHE_HOME/semantic-scala/sbt-classpath/v1`, or
`${user.home}/.cache/semantic-scala/sbt-classpath/v1` when no absolute XDG
root is available. Tests inject their root. POSIX permissions are owner-only
where supported. One lock per identity has a 190-second bound; publication
uses a same-directory owner-only temporary file, force, strict reread, and
atomic replacement only. Symlinks, malformed/unknown fields, unsupported
versions, evidence overflow, stale evidence, permission failures, and failed
atomic moves fail closed with no fallback.

Conventional input coverage hashes root `*.sbt`, every regular file under
`project/`, and nested conventional
`src/main|test/{scala,java,resources}` trees. It excludes `.git`, `target`,
`.bloop`, `.metals`, `.idea`, `.scala-build`, and `node_modules`. JAR evidence
hashes full bytes; directory evidence hashes sorted relative path, size, and
full bytes for every regular file. The documented bounds fail instead of
silently reducing coverage. Matching evidence does not prove arbitrary sbt
logic fresh.

The strict private record marker is
`semantic-scala.internal-sbt-classpath-cache.v1`; it is not a public schema.
No source contents, environment dumps, secrets, or raw sbt logs are stored.
Compiler-option/plugin acquisition, BSP/Metals discovery, and an `infer-type`
MCP tool remain absent.
