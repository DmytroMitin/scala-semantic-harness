package semantic.harness.semanticdb_reader

import java.nio.file.Files
import java.nio.file.Path
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.Synthetic
import scala.meta.internal.semanticdb.TextDocument

class UsagesBySymbolSpikeSuite extends munit.FunSuite:
  import UsagesBySymbolSpikeTestSupport.*

  test("aggregates one exact global symbol across main and test while suppressing duplicates"):
    val matrix = usageMatrix("aggregate")

    val report = run(matrix.request)

    assertEquals(report.state, UsagesBySymbolState.EvidenceFound)
    assertEquals(report.coverage.declaredArtifacts, 4)
    assertEquals(report.coverage.uniqueArtifactContents, 3)
    assertEquals(report.coverage.duplicateCopies, 1)
    assertEquals(report.coverage.scannedOrdinaryOccurrences, 7)
    assertEquals(report.coverage.matchingOccurrences, 3)
    assertEquals(report.coverage.returnedOccurrences, 3)
    assertEquals(
      report.occurrences.map(value => value.source -> value.role),
      List(
        Some(matrix.definition.path) -> UsageOccurrenceRole.Definition,
        Some(matrix.mainUse.path) -> UsageOccurrenceRole.Reference,
        Some(matrix.testUse.path) -> UsageOccurrenceRole.Reference
      )
    )
    assertEquals(report.duplicateGroups.map(_.copyCount), List(2))
    assert(report.occurrences.forall(_.stableSymbol.contains(Target)))
    assert(!report.occurrences.exists(_.stableSymbol.contains(OtherOwner)))
    assert(!report.occurrences.exists(_.stableSymbol.contains(Overload)))

  test("same spelling, other owners, and overloads are excluded by exact identity"):
    val matrix = usageMatrix("identity")
    val report = run(matrix.request.copy(target = UsagesBySymbolTarget.ExplicitGlobal(OtherOwner)))

    assertEquals(report.state, UsagesBySymbolState.EvidenceFound)
    assertEquals(report.coverage.matchingOccurrences, 2)
    assertEquals(report.occurrences.map(_.stableSymbol), List.fill(2)(Some(OtherOwner)))

    val overload = run(matrix.request.copy(target = UsagesBySymbolTarget.ExplicitGlobal(Overload)))
    assertEquals(overload.coverage.matchingOccurrences, 2)
    assertEquals(overload.occurrences.map(_.stableSymbol), List.fill(2)(Some(Overload)))

  test("definition inclusion and module source-set selectors remain explicit"):
    val matrix = usageMatrix("selectors")
    val referencesOnly = run(
      matrix.request.copy(
        selectors = matrix.request.selectors.copy(includeDefinitions = false)
      )
    )

    assertEquals(referencesOnly.state, UsagesBySymbolState.EvidenceFound)
    assertEquals(referencesOnly.occurrences.map(_.role), List.fill(2)(UsageOccurrenceRole.Reference))

    val testOnly = run(
      matrix.request.copy(
        selectors = UsagesBySymbolSelectors(
          includeDefinitions = true,
          modules = Set("module-b"),
          sourceSets = Set("test"),
          includeGenerated = true
        )
      )
    )
    assertEquals(testOnly.state, UsagesBySymbolState.CoverageIncomplete)
    assertEquals(testOnly.coverage.selectedSources, 1)
    assertEquals(testOnly.coverage.excludedSources, 2)
    assertEquals(testOnly.occurrences.map(_.source), List(Some(matrix.testUse.path)))

  test("generated entries are excluded unless selected and exclusion is incomplete coverage"):
    val workspace = UsagesBySymbolSpikeTestSupport.workspace("generated")
    val source = writeGeneratedSource(
      workspace,
      "generated/src/main/scala/example/Generated.scala",
      "package example\nobject Generated\n"
    )
    val artifact = writeArtifact(
      workspace,
      "out/generated.semanticdb",
      Seq(
        document(
          source.path,
          Files.readString(workspace.resolve(source.path)),
          Seq(occurrence(Target, SymbolOccurrence.Role.REFERENCE, 1, 7, 16))
        )
      ),
      source.module,
      source.sourceSet,
      generated = true
    )
    val base = request(workspace, List(source), List(artifact))

    val excluded = run(
      base.copy(selectors = base.selectors.copy(includeGenerated = false))
    )
    assertEquals(excluded.state, UsagesBySymbolState.CoverageIncomplete)
    assertEquals(excluded.coverage.selectedArtifacts, 0)
    assertEquals(excluded.occurrences, Nil)

    val included = run(base)
    assertEquals(included.state, UsagesBySymbolState.EvidenceFound)
    assertEquals(included.occurrences.map(_.generated), List(true))

  test("raw duplicate metadata conflicts are inconsistent rather than guessed"):
    val workspace = UsagesBySymbolSpikeTestSupport.workspace("duplicate-conflict")
    val source = writeSource(
      workspace,
      "module-a/src/main/scala/example/A.scala",
      "package example\nobject A\n"
    )
    val first = writeArtifact(
      workspace,
      "out/a.semanticdb",
      Seq(
        document(
          source.path,
          Files.readString(workspace.resolve(source.path)),
          Seq(occurrence(Target, SymbolOccurrence.Role.REFERENCE, 1, 7, 8))
        )
      ),
      source.module,
      source.sourceSet
    )
    val conflict = copyArtifact(
      workspace,
      first,
      "out/b.semanticdb",
      module = "module-b"
    )

    val report = run(request(workspace, List(source), List(first, conflict)))

    assertEquals(report.state, UsagesBySymbolState.ArtifactStaleOrInconsistent)
    assertEquals(report.coverage.duplicateCopies, 1)
    assert(report.warnings.exists(_.contains("ConflictingDuplicateMetadata")))

  test("fresh and stale source digests are distinct while stale occurrences remain readable"):
    val matrix = usageMatrix("freshness")
    val fresh = run(matrix.request)
    assertEquals(fresh.state, UsagesBySymbolState.EvidenceFound)
    assertEquals(fresh.coverage.freshDocuments, 3)
    assertEquals(fresh.coverage.staleDocuments, 0)

    Files.writeString(
      matrix.workspace.resolve(matrix.mainUse.path),
      "package example\nobject Changed\n"
    )
    val stale = run(matrix.request)
    assertEquals(stale.state, UsagesBySymbolState.ArtifactStaleOrInconsistent)
    assertEquals(stale.coverage.staleDocuments, 1)
    assertEquals(stale.coverage.matchingOccurrences, 3)
    assertEquals(stale.occurrences.size, 3)
    assert(stale.occurrences.exists(_.freshness == UsageFreshness.Stale))

  test("missing ambiguous and conflicting source mapping remain explicit"):
    val missingWorkspace = UsagesBySymbolSpikeTestSupport.workspace("mapping-missing")
    val declared = writeSource(
      missingWorkspace,
      "module-a/src/main/scala/example/Declared.scala",
      "package example\nobject Declared\n"
    )
    val missingArtifact = writeArtifact(
      missingWorkspace,
      "out/missing.semanticdb",
      Seq(
        TextDocument(
          uri = "src/main/scala/example/Other.scala",
          md5 = "0123456789abcdef0123456789abcdef",
          occurrences = Seq(occurrence(Target, SymbolOccurrence.Role.REFERENCE, 0, 0, 3))
        )
      ),
      declared.module,
      declared.sourceSet
    )
    val missing = run(request(missingWorkspace, List(declared), List(missingArtifact)))
    assertEquals(missing.state, UsagesBySymbolState.CoverageIncomplete)
    assertEquals(missing.coverage.unmappedDocuments, 1)
    assertEquals(missing.occurrences.map(_.source), List(None))

    val ambiguousWorkspace = UsagesBySymbolSpikeTestSupport.workspace("mapping-ambiguous")
    val suffix = "src/main/scala/example/A.scala"
    val first = writeSource(ambiguousWorkspace, s"module-a/$suffix", "object A\n")
    val second = writeSource(ambiguousWorkspace, s"module-b/$suffix", "object B\n")
    val ambiguousArtifact = writeArtifact(
      ambiguousWorkspace,
      "out/ambiguous.semanticdb",
      Seq(
        TextDocument(
          uri = suffix,
          md5 = "0123456789abcdef0123456789abcdef",
          occurrences = Seq(occurrence(Target, SymbolOccurrence.Role.REFERENCE, 0, 0, 1))
        )
      ),
      first.module,
      first.sourceSet
    )
    val ambiguous = run(
      request(ambiguousWorkspace, List(first, second), List(ambiguousArtifact))
    )
    assertEquals(ambiguous.state, UsagesBySymbolState.CoverageIncomplete)
    assertEquals(ambiguous.coverage.ambiguousDocuments, 1)

    val conflictWorkspace = UsagesBySymbolSpikeTestSupport.workspace("mapping-conflict")
    val conflictSource = writeSource(
      conflictWorkspace,
      "module-a/src/main/scala/example/A.scala",
      "object A\n"
    )
    val one = writeArtifact(
      conflictWorkspace,
      "out/one.semanticdb",
      Seq(
        document(
          conflictSource.path,
          "object A\n",
          Seq(occurrence(Target, SymbolOccurrence.Role.REFERENCE, 0, 0, 1))
        )
      ),
      conflictSource.module,
      conflictSource.sourceSet
    )
    val two = writeArtifact(
      conflictWorkspace,
      "out/two.semanticdb",
      Seq(
        document(
          conflictSource.path,
          "object A\n",
          Seq(
            occurrence(Target, SymbolOccurrence.Role.REFERENCE, 0, 0, 1),
            occurrence(OtherOwner, SymbolOccurrence.Role.REFERENCE, 0, 2, 3)
          )
        )
      ),
      conflictSource.module,
      conflictSource.sourceSet
    )
    val conflict = run(
      request(conflictWorkspace, List(conflictSource), List(one, two))
    )
    assertEquals(conflict.state, UsagesBySymbolState.ArtifactStaleOrInconsistent)
    assert(conflict.warnings.exists(_.contains("MultipleDistinctDocumentsMapToOneSource")))

  test("open inventory is incomplete and closed fresh zero-match inventory is bounded no-usages"):
    val workspace = UsagesBySymbolSpikeTestSupport.workspace("closed-zero")
    val sourceText = "package example\nobject A\n"
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
          Seq(occurrence(OtherOwner, SymbolOccurrence.Role.REFERENCE, 1, 7, 8))
        )
      ),
      source.module,
      source.sourceSet
    )
    val closed = request(workspace, List(source), List(artifact))

    val noUsages = run(closed)
    assertEquals(noUsages.state, UsagesBySymbolState.NoUsagesObserved)
    assertEquals(noUsages.coverage.matchingOccurrences, 0)
    assertEquals(noUsages.coverage.inventoryClosed, true)

    val open = run(closed.copy(inventoryClosed = false))
    assertEquals(open.state, UsagesBySymbolState.CoverageIncomplete)
    assertEquals(open.coverage.inventoryClosed, false)

  test("explicit local symbols are unsupported"):
    val matrix = usageMatrix("explicit-local")
    val report = run(
      matrix.request.copy(target = UsagesBySymbolTarget.ExplicitGlobal("local0"))
    )

    assertEquals(report.state, UsagesBySymbolState.UnsupportedConstruct)
    assertEquals(report.target, None)
    assertEquals(report.occurrences, Nil)

  test("point-selected local symbol is document-only and local identity is not exposed"):
    val workspace = UsagesBySymbolSpikeTestSupport.workspace("point-local")
    val sourceText = "object A:\n  val localValue = 1\n  localValue\n"
    val source = writeSource(
      workspace,
      "module-a/src/main/scala/example/A.scala",
      sourceText
    )
    val otherText = "object B:\n  val other = 1\n  other\n"
    val other = writeSource(
      workspace,
      "module-b/src/main/scala/example/B.scala",
      otherText
    )
    val targetArtifact = writeArtifact(
      workspace,
      "out/a.semanticdb",
      Seq(
        document(
          source.path,
          sourceText,
          Seq(
            occurrence("local0", SymbolOccurrence.Role.DEFINITION, 1, 6, 16),
            occurrence("local0", SymbolOccurrence.Role.REFERENCE, 2, 2, 12)
          )
        )
      ),
      source.module,
      source.sourceSet
    )
    val otherArtifact = writeArtifact(
      workspace,
      "out/b.semanticdb",
      Seq(
        document(
          other.path,
          otherText,
          Seq(
            occurrence("local0", SymbolOccurrence.Role.DEFINITION, 1, 6, 11),
            occurrence("local0", SymbolOccurrence.Role.REFERENCE, 2, 2, 7)
          )
        )
      ),
      other.module,
      other.sourceSet
    )
    val point = UsagesBySymbolTarget.Point(source.path, line = 2, column = 7, targetArtifact.path)
    val report = run(
      request(workspace, List(source, other), List(targetArtifact, otherArtifact), point)
    )

    assertEquals(report.state, UsagesBySymbolState.EvidenceFound)
    assertEquals(report.target.map(_.identityKind), Some(UsageIdentityKind.LocalDocumentOnly))
    assertEquals(report.target.flatMap(_.stableSymbol), None)
    assertEquals(report.target.flatMap(_.localSymbolMarker), Some(UsagesBySymbolSpike.LocalSymbolMarker))
    assertEquals(report.occurrences.size, 2)
    assertEquals(report.occurrences.map(_.source).distinct, List(Some(source.path)))
    assert(report.occurrences.forall(_.stableSymbol.isEmpty))
    assert(!report.toString.contains("local0"))

  test("an earlier local can shift identity without cross-document aggregation"):
    def localReport(label: String, symbol: String): UsagesBySymbolReport =
      val workspace = UsagesBySymbolSpikeTestSupport.workspace(label)
      val sourceText = "object A:\n  val localValue = 1\n  localValue\n"
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
            Seq(
              occurrence(symbol, SymbolOccurrence.Role.DEFINITION, 1, 6, 16),
              occurrence(symbol, SymbolOccurrence.Role.REFERENCE, 2, 2, 12)
            )
          )
        ),
        source.module,
        source.sourceSet
      )
      run(
        request(
          workspace,
          List(source),
          List(artifact),
          UsagesBySymbolTarget.Point(source.path, 2, 7, artifact.path)
        )
      )

    val before = localReport("local-before", "local0")
    val after = localReport("local-after", "local1")

    assertEquals(before.target.map(_.localSymbolMarker), after.target.map(_.localSymbolMarker))
    assertEquals(before.occurrences.size, 2)
    assertEquals(after.occurrences.size, 2)
    assert(!before.toString.contains("local0"))
    assert(!after.toString.contains("local1"))

  test("point resolution handles global, unresolved, ambiguous, and UTF-16 columns"):
    val workspace = UsagesBySymbolSpikeTestSupport.workspace("point-modes")
    val sourceText = "object A:\n  val emoji = \"😀\"; use\n"
    val source = writeSource(
      workspace,
      "module-a/src/main/scala/example/A.scala",
      sourceText
    )
    val utf16Start = sourceText.linesIterator.toList(1).indexOf("use")
    val artifact = writeArtifact(
      workspace,
      "out/a.semanticdb",
      Seq(
        document(
          source.path,
          sourceText,
          Seq(occurrence(Target, SymbolOccurrence.Role.REFERENCE, 1, utf16Start, utf16Start + 3))
        )
      ),
      source.module,
      source.sourceSet
    )
    val base = request(
      workspace,
      List(source),
      List(artifact),
      UsagesBySymbolTarget.Point(source.path, 2, utf16Start + 1, artifact.path)
    )
    val resolved = run(base)
    assertEquals(resolved.state, UsagesBySymbolState.EvidenceFound)
    assertEquals(resolved.target.flatMap(_.stableSymbol), Some(Target))

    val unresolved = run(
      base.copy(target = UsagesBySymbolTarget.Point(source.path, 1, 1, artifact.path))
    )
    assertEquals(unresolved.state, UsagesBySymbolState.TargetUnresolved)

    val ambiguousArtifact = writeArtifact(
      workspace,
      "out/ambiguous.semanticdb",
      Seq(
        document(
          source.path,
          sourceText,
          Seq(
            occurrence(Target, SymbolOccurrence.Role.REFERENCE, 1, utf16Start, utf16Start + 3),
            occurrence(OtherOwner, SymbolOccurrence.Role.REFERENCE, 1, utf16Start, utf16Start + 3)
          )
        )
      ),
      source.module,
      source.sourceSet
    )
    val ambiguous = run(
      request(
        workspace,
        List(source),
        List(ambiguousArtifact),
        UsagesBySymbolTarget.Point(source.path, 2, utf16Start + 1, ambiguousArtifact.path)
      )
    )
    assertEquals(ambiguous.state, UsagesBySymbolState.TargetAmbiguous)

  test("omitted occurrence data prevents a complete zero claim"):
    val workspace = UsagesBySymbolSpikeTestSupport.workspace("omitted-occurrences")
    val sourceText = "object A\n"
    val source = writeSource(
      workspace,
      "module-a/src/main/scala/example/A.scala",
      sourceText
    )
    val artifact = writeArtifact(
      workspace,
      "out/a.semanticdb",
      Seq(document(source.path, sourceText, Nil)),
      source.module,
      source.sourceSet
    )

    val report = run(request(workspace, List(source), List(artifact)))

    assertEquals(report.state, UsagesBySymbolState.CoverageIncomplete)
    assertEquals(report.coverage.documentsWithoutOccurrences, 1)
    assert(report.warnings.exists(_.contains("DocumentOccurrenceDataAbsent")))

  test("synthetics and non-ordinary roles do not expand the exact occurrence set"):
    val workspace = UsagesBySymbolSpikeTestSupport.workspace("exclusions")
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
          Seq(
            occurrence(Target, SymbolOccurrence.Role.REFERENCE, 0, 0, 1),
            SymbolOccurrence(symbol = Target, role = SymbolOccurrence.Role.UNKNOWN_ROLE)
          ),
          synthetics = Seq(Synthetic())
        )
      ),
      source.module,
      source.sourceSet
    )

    val report = run(request(workspace, List(source), List(artifact)))

    assertEquals(report.state, UsagesBySymbolState.EvidenceFound)
    assertEquals(report.coverage.scannedOrdinaryOccurrences, 1)
    assertEquals(report.coverage.documentsWithSynthetics, 1)
    assertEquals(report.occurrences.size, 1)
    assert(report.warnings.exists(_.contains("SyntheticsExcluded")))

  test("repeated runs are structurally equal and privacy-safe"):
    val matrix = usageMatrix("deterministic")
    val first = run(matrix.request)
    val second = run(matrix.request)

    assertEquals(second, first)
    assert(!first.toString.contains(matrix.workspace.toString))
    assert(!first.toString.contains("package example"))
    assert(!first.toString.contains("/home/"))

  private final case class UsageMatrix(
    workspace: Path,
    definition: DeclaredUsageSource,
    mainUse: DeclaredUsageSource,
    testUse: DeclaredUsageSource,
    request: UsagesBySymbolRequest
  )

  private def usageMatrix(label: String): UsageMatrix =
    val workspace = UsagesBySymbolSpikeTestSupport.workspace(label)
    val definitionText = "package example\nobject Definitions\n"
    val mainText = "package example\nobject MainUse\n"
    val testText = "package example\nobject TestUse\n"
    val definition = writeSource(
      workspace,
      "module-a/src/main/scala/example/Definitions.scala",
      definitionText
    )
    val mainUse = writeSource(
      workspace,
      "module-b/src/main/scala/example/MainUse.scala",
      mainText
    )
    val testUse = writeSource(
      workspace,
      "module-b/src/test/scala/example/TestUse.scala",
      testText
    )
    val definitionsArtifact = writeArtifact(
      workspace,
      "out/a/Definitions.scala.semanticdb",
      Seq(
        document(
          definition.path,
          definitionText,
          Seq(
            occurrence(Target, SymbolOccurrence.Role.DEFINITION, 1, 7, 18),
            occurrence(Overload, SymbolOccurrence.Role.DEFINITION, 1, 7, 18),
            occurrence(OtherOwner, SymbolOccurrence.Role.DEFINITION, 1, 7, 18)
          )
        )
      ),
      definition.module,
      definition.sourceSet
    )
    val mainArtifact = writeArtifact(
      workspace,
      "out/b/MainUse.scala.semanticdb",
      Seq(
        document(
          mainUse.path,
          mainText,
          Seq(
            occurrence(Target, SymbolOccurrence.Role.REFERENCE, 1, 7, 14),
            occurrence(Overload, SymbolOccurrence.Role.REFERENCE, 1, 7, 14),
            occurrence(OtherOwner, SymbolOccurrence.Role.REFERENCE, 1, 7, 14)
          )
        )
      ),
      mainUse.module,
      mainUse.sourceSet
    )
    val duplicate = copyArtifact(
      workspace,
      mainArtifact,
      "out/b-copy/MainUse.scala.semanticdb"
    )
    val testArtifact = writeArtifact(
      workspace,
      "out/test/TestUse.scala.semanticdb",
      Seq(
        document(
          testUse.path,
          testText,
          Seq(occurrence(Target, SymbolOccurrence.Role.REFERENCE, 1, 7, 14))
        )
      ),
      testUse.module,
      testUse.sourceSet
    )
    UsageMatrix(
      workspace,
      definition,
      mainUse,
      testUse,
      request(
        workspace,
        List(definition, mainUse, testUse),
        List(definitionsArtifact, mainArtifact, duplicate, testArtifact)
      )
    )
