# sbt-runner

Process integration for the build/test JSON oracle and deterministic
project-classpath acquisition.

The build-oracle runner preserves ordinary root `compile`/`test` selection when
no project is selected. An optional `SbtProjectId` uses the shared strict
`[A-Za-z][A-Za-z0-9_-]*` policy, then supplies one fixed sbt command sequence:
`project <id>` followed by `Compile / compile` or the private structured Test
task. It does not use
a shell or accept caller-provided tasks, configurations, scopes, separators,
or command fragments. Unknown valid projects fail as that bounded invocation;
they never fall back to the root. A successful selected run is not a
whole-workspace result.

Every build-oracle, classpath, and TASTy request owns one fresh temporary sbt
global base, one short request-owned runtime/socket directory, and one
foreground `sbt --server --batch` process. It never attaches to an ambient sbt
server. Output drains and timeouts are bounded; timeout cleanup targets only
the owned process tree. Temporary settings, protocols, and socket
state are removed after every outcome.

The private Test task executes the common `Test / executeTests` task and
aggregates sbt's structured `SuiteResult` counters. This runs all selected
tests even on sbt 2, where the public `test` input key may select only tests
not already satisfied by its incremental state. Passed, failure, error,
skipped, ignored, canceled, and pending counters produce one bounded
request-owned completion record. Human console summaries and JUnit XML are not
count sources, and framework events and properties are never projected
publicly.

Both build-oracle execution and classpath acquisition can receive one shared
validated target Java home. Input is an absolute installed home (a top-level
SDKMAN-style symlink is allowed and canonicalized), with a contained
executable platform launcher and bounded launcher/release/version evidence.
Only the child sbt environment receives canonical `JAVA_HOME` and a leading
home `bin` entry in `PATH`; unrelated environment and the existing sandbox
treatment are retained. The parent JVM and global Java state are unchanged.
No selector performs no probe or extra Java validation, and there is no JDK
discovery, installation, download, or fallback.

`SbtClasspathAcquirer` accepts an existing workspace, a project ID matching
`[A-Za-z][A-Za-z0-9_-]*`, and exact configuration `Compile` or `Test`. It
creates an isolated temporary sbt global base, injects a private task, selects
the project, evaluates the scoped `fullClasspath`, and reads a dedicated
versioned line protocol. It never parses human `show` output and does not
modify the target build definition.

The result contains ordered, normalized, de-duplicated `Directory`/`Jar`
entries. Project/configuration/header/path mismatches and missing or unsupported
entries are protocol failures; validation, process, and protocol failures stay
separate. The default classpath timeout is 180 seconds. Temporary settings and
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

No-selector storage remains outside workspaces at absolute
`$XDG_CACHE_HOME/semantic-scala/sbt-classpath/v1`, or
`${user.home}/.cache/semantic-scala/sbt-classpath/v1` when no absolute XDG
root is available. Explicit target Java uses the isolated sibling `v2` root
and strict v2 protocol/cache identity with opaque home/runtime evidence. It
never reads or migrates v1. Different homes cannot cross-reuse; same-home
runtime drift fails as a typed mismatch without invoking sbt. Tests inject
their root. POSIX permissions are owner-only
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

The strict private record markers are
`semantic-scala.internal-sbt-classpath-cache.v1` for no selector and v2 for
explicit target Java; neither is a public schema.
No source contents, environment dumps, secrets, or raw sbt logs are stored.
Compiler-option/plugin acquisition, BSP/Metals discovery, and an `infer-type`
MCP tool remain absent.
