package semantic.harness.semanticdb_reader

import java.nio.file.Files
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.TextDocument

class UsagesBySymbolSpikeBoundsSuite extends munit.FunSuite:
  import UsagesBySymbolSpikeTestSupport.*

  test("hard bounds are the admitted Task 086 limits"):
    assertEquals(UsagesBySymbolLimits.HardMaxArtifacts, 256)
    assertEquals(UsagesBySymbolLimits.HardMaxDocuments, 4096)
    assertEquals(UsagesBySymbolLimits.HardMaxSources, 50000)
    assertEquals(UsagesBySymbolLimits.HardMaxAggregateArtifactBytes, 256L * 1024L * 1024L)
    assertEquals(UsagesBySymbolLimits.HardMaxArtifactBytes, 16L * 1024L * 1024L)
    assertEquals(UsagesBySymbolLimits.HardMaxSourceBytes, 8L * 1024L * 1024L)
    assertEquals(UsagesBySymbolLimits.HardMaxScannedOccurrences, 1000000)
    assertEquals(UsagesBySymbolLimits.HardMaxReturnedOccurrences, 500)
    assertEquals(UsagesBySymbolLimits.HardMaxResultEvidenceBytes, 1024 * 1024)
    assertEquals(UsagesBySymbolLimits.HardMaxStringBytes, 1024)
    assertEquals(UsagesBySymbolLimits.HardMaxPathBytes, 4096)
    assertEquals(UsagesBySymbolLimits.HardMaxUriBytes, 4096)
    assertEquals(UsagesBySymbolLimits.HardMaxWarnings, 100)
    assertEquals(UsagesBySymbolLimits.HardMaxDuplicateGroups, 100)
    assertEquals(UsagesBySymbolLimits.HardMaxDuplicatePathSamples, 20)
    assertEquals(UsagesBySymbolLimits.HardMaxSelectorValues, 256)
    assertEquals(UsagesBySymbolLimits.HardDeadlineNanos, 30L * 1000L * 1000L * 1000L)

  test("source artifact selector identifier and hard-limit configuration admission fail closed"):
    val base = simpleFixture("static-admission")
    val secondSource = writeSource(
      base.workspace,
      "module-b/src/main/scala/example/B.scala",
      "object B\n"
    )
    val secondArtifact = writeArtifact(
      base.workspace,
      "out/b.semanticdb",
      Seq(
        document(
          secondSource.path,
          "object B\n",
          Seq(occurrence(Target, SymbolOccurrence.Role.REFERENCE, 0, 0, 1))
        )
      ),
      secondSource.module,
      secondSource.sourceSet
    )

    val sourceFailure = failure(
      base.request.copy(sources = List(base.source, secondSource)),
      UsagesBySymbolLimits.Default.copy(maxSources = 1)
    )
    assertEquals(sourceFailure.kind, UsagesBySymbolFailureKind.InvalidInput)
    assert(sourceFailure.message.contains("source count"))

    val artifactFailure = failure(
      base.request.copy(artifacts = List(base.artifact, secondArtifact)),
      UsagesBySymbolLimits.Default.copy(maxArtifacts = 1)
    )
    assertEquals(artifactFailure.kind, UsagesBySymbolFailureKind.InvalidInput)
    assert(artifactFailure.message.contains("artifact count"))

    val selectorFailure = failure(
      base.request.copy(
        selectors = base.request.selectors.copy(modules = Set("a", "b"))
      ),
      UsagesBySymbolLimits.Default.copy(maxSelectorValues = 1)
    )
    assertEquals(selectorFailure.kind, UsagesBySymbolFailureKind.InvalidInput)
    assert(selectorFailure.message.contains("Selector"))

    val longModuleSource = base.source.copy(module = "abcd")
    val longModuleArtifact = base.artifact.copy(module = "abcd")
    val identifierFailure = failure(
      base.request.copy(
        sources = List(longModuleSource),
        artifacts = List(longModuleArtifact),
        target = UsagesBySymbolTarget.ExplicitGlobal("x#")
      ),
      UsagesBySymbolLimits.Default.copy(maxStringBytes = 3)
    )
    assertEquals(identifierFailure.kind, UsagesBySymbolFailureKind.InvalidInput)
    assert(identifierFailure.message.contains("Module identifier"))

    val excessiveLimits = UsagesBySymbolLimits.Default.copy(
      maxArtifacts = UsagesBySymbolLimits.HardMaxArtifacts + 1
    )
    val limitsFailure = UsagesBySymbolSpike.run(base.request, excessiveLimits).left.getOrElse(
      fail("Expected hard-limit configuration failure")
    )
    assertEquals(limitsFailure.kind, UsagesBySymbolFailureKind.InvalidInput)
    assert(limitsFailure.message.contains("hard bounds"))

  test("per-source per-artifact and aggregate artifact byte limits are enforced before scanning"):
    val base = simpleFixture("byte-admission")

    val sourceFailure = failure(
      base.request,
      UsagesBySymbolLimits.Default.copy(maxSourceBytes = 1)
    )
    assertEquals(sourceFailure.kind, UsagesBySymbolFailureKind.InvalidInput)
    assert(sourceFailure.message.contains("source exceeds"))

    val artifactFailure = failure(
      base.request,
      UsagesBySymbolLimits.Default.copy(maxArtifactBytes = 1)
    )
    assertEquals(artifactFailure.kind, UsagesBySymbolFailureKind.InvalidInput)
    assert(artifactFailure.message.contains("artifact exceeds"))

    val aggregateFailure = failure(
      base.request,
      UsagesBySymbolLimits.Default.copy(maxAggregateArtifactBytes = 1)
    )
    assertEquals(aggregateFailure.kind, UsagesBySymbolFailureKind.InvalidInput)
    assert(aggregateFailure.message.contains("Aggregate artifact bytes"))

  test("document scanned occurrence and returned occurrence bounds produce deterministic truncation"):
    val workspace = UsagesBySymbolSpikeTestSupport.workspace("scan-bounds")
    val firstText = "object A\n"
    val secondText = "object B\n"
    val first = writeSource(
      workspace,
      "module-a/src/main/scala/example/A.scala",
      firstText
    )
    val second = writeSource(
      workspace,
      "module-b/src/main/scala/example/B.scala",
      secondText
    )
    val artifact = writeArtifact(
      workspace,
      "out/multi.semanticdb",
      Seq(
        document(
          first.path,
          firstText,
          Seq(
            occurrence(Target, SymbolOccurrence.Role.REFERENCE, 0, 0, 1),
            occurrence(Target, SymbolOccurrence.Role.REFERENCE, 0, 2, 3),
            occurrence(Target, SymbolOccurrence.Role.REFERENCE, 0, 4, 5)
          )
        ),
        document(
          second.path,
          secondText,
          Seq(occurrence(Target, SymbolOccurrence.Role.REFERENCE, 0, 0, 1))
        )
      ),
      first.module,
      first.sourceSet
    )
    val base = request(workspace, List(first, second), List(artifact))

    val documentBound = run(
      base,
      UsagesBySymbolLimits.Default.copy(maxDocuments = 1)
    )
    assertEquals(documentBound.state, UsagesBySymbolState.Truncated)
    assert(documentBound.limits.hit.contains("DocumentLimit"))

    val scanBound = run(
      base,
      UsagesBySymbolLimits.Default.copy(maxScannedOccurrences = 2)
    )
    assertEquals(scanBound.state, UsagesBySymbolState.Truncated)
    assertEquals(scanBound.coverage.scannedOrdinaryOccurrences, 2)
    assert(scanBound.limits.hit.contains("ScannedOccurrenceLimit"))

    val returnBound = run(
      base,
      UsagesBySymbolLimits.Default.copy(maxReturnedOccurrences = 1)
    )
    assertEquals(returnBound.state, UsagesBySymbolState.Truncated)
    assertEquals(returnBound.coverage.matchingOccurrences, 4)
    assertEquals(returnBound.occurrences.size, 1)
    assert(returnBound.limits.hit.contains("ReturnedOccurrenceLimit"))

  test("result evidence warning duplicate-group and duplicate-path bounds produce truncation"):
    val base = simpleFixture("result-bound")
    val outputBound = run(
      base.request,
      UsagesBySymbolLimits.Default.copy(maxResultEvidenceBytes = 900)
    )
    assertEquals(outputBound.state, UsagesBySymbolState.Truncated)
    assert(outputBound.limits.hit.contains("ResultEvidenceByteLimit"))

    val warningWorkspace = UsagesBySymbolSpikeTestSupport.workspace("warning-bound")
    val source = writeSource(
      warningWorkspace,
      "module-a/src/main/scala/example/A.scala",
      "object A\n"
    )
    val warningArtifact = writeArtifact(
      warningWorkspace,
      "out/warnings.semanticdb",
      Seq(
        TextDocument(
          uri = "other/One.scala",
          md5 = "0123456789abcdef0123456789abcdef",
          occurrences = Seq(occurrence(Target, SymbolOccurrence.Role.REFERENCE, 0, 0, 1))
        ),
        TextDocument(
          uri = "other/Two.scala",
          md5 = "0123456789abcdef0123456789abcdef",
          occurrences = Seq(occurrence(Target, SymbolOccurrence.Role.REFERENCE, 0, 0, 1))
        )
      ),
      source.module,
      source.sourceSet
    )
    val warningBound = run(
      request(warningWorkspace, List(source), List(warningArtifact)),
      UsagesBySymbolLimits.Default.copy(maxWarnings = 1)
    )
    assertEquals(warningBound.state, UsagesBySymbolState.Truncated)
    assert(warningBound.limits.hit.contains("WarningLimit"))
    assertEquals(warningBound.warnings.size, 1)

    val duplicateWorkspace = UsagesBySymbolSpikeTestSupport.workspace("duplicate-bound")
    val sourceOne = writeSource(
      duplicateWorkspace,
      "module-a/src/main/scala/example/A.scala",
      "object A\n"
    )
    val sourceTwo = writeSource(
      duplicateWorkspace,
      "module-b/src/main/scala/example/B.scala",
      "object B\n"
    )
    val one = writeArtifact(
      duplicateWorkspace,
      "out/one.semanticdb",
      Seq(
        document(
          sourceOne.path,
          "object A\n",
          Seq(occurrence(Target, SymbolOccurrence.Role.REFERENCE, 0, 0, 1))
        )
      ),
      sourceOne.module,
      sourceOne.sourceSet
    )
    val oneCopy = copyArtifact(duplicateWorkspace, one, "out/one-copy.semanticdb")
    val two = writeArtifact(
      duplicateWorkspace,
      "out/two.semanticdb",
      Seq(
        document(
          sourceTwo.path,
          "object B\n",
          Seq(occurrence(Target, SymbolOccurrence.Role.REFERENCE, 0, 0, 1))
        )
      ),
      sourceTwo.module,
      sourceTwo.sourceSet
    )
    val twoCopy = copyArtifact(duplicateWorkspace, two, "out/two-copy.semanticdb")
    val duplicateRequest = request(
      duplicateWorkspace,
      List(sourceOne, sourceTwo),
      List(one, oneCopy, two, twoCopy)
    )
    val duplicateBound = run(
      duplicateRequest,
      UsagesBySymbolLimits.Default.copy(maxDuplicateGroups = 1)
    )
    assertEquals(duplicateBound.state, UsagesBySymbolState.Truncated)
    assert(duplicateBound.limits.hit.contains("DuplicateGroupLimit"))
    assertEquals(duplicateBound.duplicateGroups.size, 1)

    val extraCopy = copyArtifact(duplicateWorkspace, one, "out/one-copy-2.semanticdb")
    val pathBound = run(
      duplicateRequest.copy(artifacts = duplicateRequest.artifacts :+ extraCopy),
      UsagesBySymbolLimits.Default.copy(maxDuplicatePathSamples = 1)
    )
    assertEquals(pathBound.state, UsagesBySymbolState.Truncated)
    assert(pathBound.limits.hit.contains("DuplicatePathSampleLimit"))
    assert(pathBound.duplicateGroups.exists(_.pathsTruncated))

  test("cooperative monotonic deadline is checked during scanning"):
    val base = simpleFixture("deadline")
    val clock = new UsageMonotonicClock:
      private var value = -1L
      def nanoTime(): Long =
        value += 1L
        value

    val report = run(
      base.request,
      UsagesBySymbolLimits.Default.copy(deadlineNanos = 5L),
      clock
    )

    assertEquals(report.state, UsagesBySymbolState.Truncated)
    assert(report.limits.hit.contains("Deadline"))

  test("malformed SemanticDB is a typed operational parse failure"):
    val workspace = UsagesBySymbolSpikeTestSupport.workspace("malformed")
    val source = writeSource(
      workspace,
      "module-a/src/main/scala/example/A.scala",
      "object A\n"
    )
    val path = workspace.resolve("out/broken.semanticdb")
    Files.createDirectories(path.getParent)
    Files.write(path, Array[Byte](1, 2, 3))
    val artifact = DeclaredUsageArtifact(
      "out/broken.semanticdb",
      source.module,
      source.sourceSet,
      generated = false
    )

    val result = failure(request(workspace, List(source), List(artifact)))

    assertEquals(result.kind, UsagesBySymbolFailureKind.ParseFailure)
    assert(!result.message.contains(workspace.toString))
    assert(!result.message.contains("/home/"))

  test("absolute traversal symlink non-file containment and duplicate declaration conflicts are rejected"):
    val base = simpleFixture("path-safety")

    val absolute = failure(
      base.request.copy(
        sources = List(base.source.copy(path = base.workspace.resolve(base.source.path).toString))
      )
    )
    assertEquals(absolute.kind, UsagesBySymbolFailureKind.InvalidInput)

    val traversal = failure(
      base.request.copy(sources = List(base.source.copy(path = "../outside.scala")))
    )
    assertEquals(traversal.kind, UsagesBySymbolFailureKind.InvalidInput)

    val real = base.workspace.resolve(base.source.path)
    val link = base.workspace.resolve("module-a/src/main/scala/example/Link.scala")
    Files.createSymbolicLink(link, real.getFileName)
    val symlink = failure(
      base.request.copy(sources = List(base.source.copy(path =
        "module-a/src/main/scala/example/Link.scala"
      )))
    )
    assertEquals(symlink.kind, UsagesBySymbolFailureKind.UnsafeFilesystem)

    val directory = base.workspace.resolve("out/directory.semanticdb")
    Files.createDirectories(directory)
    val nonFile = failure(
      base.request.copy(
        artifacts = List(base.artifact.copy(path = "out/directory.semanticdb"))
      )
    )
    assertEquals(nonFile.kind, UsagesBySymbolFailureKind.UnsafeFilesystem)

    val linkedDirectory = base.workspace.resolve("linked")
    Files.createSymbolicLink(linkedDirectory, base.workspace.resolve("out").getFileName)
    val containedSymlink = failure(
      base.request.copy(
        artifacts = List(base.artifact.copy(path = "linked/a.semanticdb"))
      )
    )
    assertEquals(containedSymlink.kind, UsagesBySymbolFailureKind.UnsafeFilesystem)

    val duplicateConflict = failure(
      base.request.copy(
        sources = List(base.source, base.source.copy(module = "different"))
      )
    )
    assertEquals(duplicateConflict.kind, UsagesBySymbolFailureKind.InvalidInput)
    assert(duplicateConflict.message.contains("conflicting metadata"))

  test("overlong paths URIs symbols invalid points and invalid ranges remain bounded"):
    val base = simpleFixture("bounded-fields")
    val longSymbol = "x" * (UsagesBySymbolLimits.HardMaxStringBytes + 1)
    val symbolFailure = failure(
      base.request.copy(target = UsagesBySymbolTarget.ExplicitGlobal(longSymbol))
    )
    assertEquals(symbolFailure.kind, UsagesBySymbolFailureKind.InvalidInput)

    val longPath = "a" * (UsagesBySymbolLimits.HardMaxPathBytes + 1)
    val pathFailure = failure(
      base.request.copy(sources = List(base.source.copy(path = s"$longPath.scala")))
    )
    assertEquals(pathFailure.kind, UsagesBySymbolFailureKind.InvalidInput)

    val invalidPoint = failure(
      base.request.copy(
        target = UsagesBySymbolTarget.Point(
          base.source.path,
          line = 0,
          column = 1,
          base.artifact.path
        )
      )
    )
    assertEquals(invalidPoint.kind, UsagesBySymbolFailureKind.InvalidInput)

    val uriWorkspace = UsagesBySymbolSpikeTestSupport.workspace("uri-bound")
    val source = writeSource(
      uriWorkspace,
      "module-a/src/main/scala/example/A.scala",
      "object A\n"
    )
    val uriArtifact = writeArtifact(
      uriWorkspace,
      "out/a.semanticdb",
      Seq(
        TextDocument(
          uri = "too/long/A.scala",
          md5 = "0123456789abcdef0123456789abcdef",
          occurrences = Seq(occurrence(Target, SymbolOccurrence.Role.REFERENCE, 0, 0, 1))
        )
      ),
      source.module,
      source.sourceSet
    )
    val uriBound = run(
      request(uriWorkspace, List(source), List(uriArtifact)),
      UsagesBySymbolLimits.Default.copy(maxUriBytes = 4)
    )
    assertEquals(uriBound.state, UsagesBySymbolState.CoverageIncomplete)
    assert(uriBound.warnings.exists(_.contains("UnsafeOrOverlongDocumentUri")))

    val invalidRangeArtifact = writeArtifact(
      uriWorkspace,
      "out/invalid-range.semanticdb",
      Seq(
        document(
          source.path,
          "object A\n",
          Seq(
            SymbolOccurrence(
              range = Some(scala.meta.internal.semanticdb.Range(1, 5, 0, 1)),
              symbol = Target,
              role = SymbolOccurrence.Role.REFERENCE
            )
          )
        )
      ),
      source.module,
      source.sourceSet
    )
    val invalidRange = run(
      request(uriWorkspace, List(source), List(invalidRangeArtifact))
    )
    assertEquals(invalidRange.state, UsagesBySymbolState.ArtifactStaleOrInconsistent)
    assert(invalidRange.warnings.exists(_.contains("MatchingOccurrenceHasInvalidRange")))

  private final case class SimpleFixture(
    workspace: java.nio.file.Path,
    source: DeclaredUsageSource,
    artifact: DeclaredUsageArtifact,
    request: UsagesBySymbolRequest
  )

  private def simpleFixture(label: String): SimpleFixture =
    val workspace = UsagesBySymbolSpikeTestSupport.workspace(label)
    val sourceText = "object A\n"
    val source = writeSource(
      workspace,
      "module-a/src/main/scala/example/A.scala",
      sourceText
    )
    val artifact = writeArtifact(
      workspace,
      "out/a.semanticdb",
      Seq(
        document(
          source.path,
          sourceText,
          Seq(occurrence(Target, SymbolOccurrence.Role.REFERENCE, 0, 0, 1))
        )
      ),
      source.module,
      source.sourceSet
    )
    SimpleFixture(
      workspace,
      source,
      artifact,
      UsagesBySymbolSpikeTestSupport.request(workspace, List(source), List(artifact))
    )
