package semantic.harness.semanticdb_reader

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import scala.collection.mutable.ListBuffer
import scala.meta.internal.semanticdb.Range
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.Synthetic
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TextDocuments

private[semanticdb_reader] object UsagesBySymbolSpikeTestSupport:
  val Target = "example/Service#run()."
  val OtherOwner = "example/Other#run()."
  val Overload = "example/Service#run(+1)."

  final class PhaseDeadlineScript(
    expireAt: UsageDeadlinePhase,
    expireAtOccurrence: Int = 1
  ) extends UsageDeadlineObserver:
    private var now = 0L
    private var absoluteDeadline = Long.MaxValue
    private var targetOccurrences = 0
    val starts = ListBuffer.empty[(Long, Long)]
    val phases = ListBuffer.empty[UsageDeadlinePhase]

    val clock: UsageMonotonicClock = new UsageMonotonicClock:
      def nanoTime(): Long = now

    def started(startedNanos: Long, absoluteDeadlineNanos: Long): Unit =
      starts += startedNanos -> absoluteDeadlineNanos
      absoluteDeadline = absoluteDeadlineNanos

    def reached(phase: UsageDeadlinePhase): Unit =
      phases += phase
      if phase == expireAt then
        targetOccurrences += 1
        if targetOccurrences == expireAtOccurrence then now = absoluteDeadline

    def occurrencesOf(phase: UsageDeadlinePhase): Int =
      phases.count(_ == phase)

    def expireNow(): Unit =
      now = absoluteDeadline

  def workspace(label: String): Path =
    Files.createTempDirectory(s"usages-by-symbol-$label-")

  def writeSource(workspace: Path, relative: String, content: String): DeclaredUsageSource =
    val path = workspace.resolve(relative)
    Files.createDirectories(path.getParent)
    Files.writeString(path, content, StandardCharsets.UTF_8)
    DeclaredUsageSource(relative, moduleFor(relative), sourceSetFor(relative), generated = false)

  def writeGeneratedSource(
    workspace: Path,
    relative: String,
    content: String
  ): DeclaredUsageSource =
    val declaration = writeSource(workspace, relative, content)
    declaration.copy(generated = true)

  def writeArtifact(
    workspace: Path,
    relative: String,
    documents: Seq[TextDocument],
    module: String,
    sourceSet: String,
    generated: Boolean = false
  ): DeclaredUsageArtifact =
    val path = workspace.resolve(relative)
    Files.createDirectories(path.getParent)
    Files.write(path, TextDocuments(documents = documents).toByteArray)
    DeclaredUsageArtifact(relative, module, sourceSet, generated)

  def copyArtifact(
    workspace: Path,
    source: DeclaredUsageArtifact,
    relative: String,
    module: String = "",
    sourceSet: String = "",
    generated: Option[Boolean] = None
  ): DeclaredUsageArtifact =
    val target = workspace.resolve(relative)
    Files.createDirectories(target.getParent)
    Files.copy(workspace.resolve(source.path), target)
    DeclaredUsageArtifact(
      relative,
      Option(module).filter(_.nonEmpty).getOrElse(source.module),
      Option(sourceSet).filter(_.nonEmpty).getOrElse(source.sourceSet),
      generated.getOrElse(source.generated)
    )

  def document(
    uri: String,
    sourceText: String,
    occurrences: Seq[SymbolOccurrence],
    synthetics: Seq[Synthetic] = Nil,
    digest: Option[String] = None
  ): TextDocument =
    TextDocument(
      uri = uri,
      md5 = digest.getOrElse(md5(sourceText.getBytes(StandardCharsets.UTF_8))),
      occurrences = occurrences,
      synthetics = synthetics
    )

  def occurrence(
    symbol: String,
    role: SymbolOccurrence.Role,
    line: Int,
    start: Int,
    end: Int
  ): SymbolOccurrence =
    SymbolOccurrence(
      range = Some(Range(line, start, line, end)),
      symbol = symbol,
      role = role
    )

  def request(
    workspace: Path,
    sources: List[DeclaredUsageSource],
    artifacts: List[DeclaredUsageArtifact],
    target: UsagesBySymbolTarget = UsagesBySymbolTarget.ExplicitGlobal(Target),
    inventoryClosed: Boolean = true,
    selectors: UsagesBySymbolSelectors =
      UsagesBySymbolSelectors(includeDefinitions = true, includeGenerated = true)
  ): UsagesBySymbolRequest =
    UsagesBySymbolRequest(
      workspace = workspace,
      inventoryClosed = inventoryClosed,
      sources = sources,
      artifacts = artifacts,
      target = target,
      selectors = selectors
    )

  def run(
    request: UsagesBySymbolRequest,
    limits: UsagesBySymbolLimits = UsagesBySymbolLimits.Default,
    clock: UsageMonotonicClock = UsageMonotonicClock.System,
    deadlineObserver: UsageDeadlineObserver = UsageDeadlineObserver.Noop
  ): UsagesBySymbolReport =
    UsagesBySymbolSpike.run(request, limits, clock, deadlineObserver).fold(
      value => throw new AssertionError(s"Unexpected failure: $value"),
      identity
    )

  def failure(
    request: UsagesBySymbolRequest,
    limits: UsagesBySymbolLimits = UsagesBySymbolLimits.Default
  ): UsagesBySymbolFailure =
    UsagesBySymbolSpike.run(request, limits).swap.fold(
      value => throw new AssertionError(s"Expected failure, got: $value"),
      identity
    )

  def md5(bytes: Array[Byte]): String =
    MessageDigest.getInstance("MD5").digest(bytes).map("%02x".format(_)).mkString

  private def moduleFor(relative: String): String =
    relative.split('/').headOption.getOrElse("root")

  private def sourceSetFor(relative: String): String =
    if relative.contains("/src/test/") then "test"
    else "main"
