package semantic.harness.semanticdb_reader

import java.nio.file.Files
import java.nio.file.Path
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TextDocuments

class TypeclassSummarySpikeSuite extends munit.FunSuite:
  private val Fixture =
    Path.of(
      "benchmarks/runs/v0/typeclass-summary-design-spike/ResolvedPatterns.scala.semanticdb"
    )

  test("extracts selected given and implicit relationships from a real Scala 3 SemanticDB fixture"):
    val report = summarizeFixture()

    assertEquals(report.status, TypeclassSummarySpikeStatus.EvidenceFound)
    assertEquals(report.totalSyntheticCount, 16)
    assertEquals(report.scannedSyntheticCount, 16)
    assertEquals(report.truncated, false)
    assertEquals(report.useSites.size, 7)

    val selected = report.useSites.flatMap(_.selectedContexts)
    assertEquals(
      selected.map(_.displayName),
      List(
        "context-bound parameter",
        "intShow",
        "intShow",
        "optionFunctor",
        "importedShow",
        "legacyInt",
        "localShow"
      )
    )
    assertEquals(
      selected.map(_.declarationStyle),
      List(
        ContextDeclarationStyle.Implicit,
        ContextDeclarationStyle.Given,
        ContextDeclarationStyle.Given,
        ContextDeclarationStyle.Given,
        ContextDeclarationStyle.Given,
        ContextDeclarationStyle.Implicit,
        ContextDeclarationStyle.Given
      )
    )

    val higherKinded = selected.find(_.displayName == "optionFunctor").getOrElse(fail("Expected optionFunctor"))
    assertEquals(higherKinded.contextTypeSymbols, List("typeclassfixture/Functor#"))
    assertEquals(higherKinded.semanticdbSymbol, Some("typeclassfixture/Functor.optionFunctor."))

    val imported = selected.find(_.displayName == "importedShow").getOrElse(fail("Expected importedShow"))
    assertEquals(imported.contextTypeSymbols, List("typeclassfixture/Show#"))
    assert(imported.definitionRange.nonEmpty)

    val local = selected.find(_.displayName == "localShow").getOrElse(fail("Expected localShow"))
    assertEquals(local.identityKind, ContextIdentityKind.SourceLocal)
    assertEquals(local.semanticdbSymbol, None)
    assert(local.definitionRange.nonEmpty)

    val contextual = selected.head
    assertEquals(contextual.identityKind, ContextIdentityKind.ContextParameter)
    assertEquals(contextual.semanticdbSymbol, None)
    assertEquals(contextual.contextTypeSymbols, List("typeclassfixture/Show#"))

    val extensionSites = report.useSites.filter(_.referencedSymbols.contains("typeclassfixture/syntax.rendered()."))
    assertEquals(extensionSites.map(_.selectedContexts.head.displayName), List("intShow", "importedShow", "localShow"))

  test("is deterministic across repeated extraction"):
    val first = summarizeFixture()
    val second = summarizeFixture()
    assertEquals(second, first)

  test("returns NoEvidence for a source-only document without synthetics"):
    val report = TypeclassSummarySpike.summarize(TextDocument(uri = "SourceOnly.scala"))

    assertEquals(report.status, TypeclassSummarySpikeStatus.NoEvidence)
    assertEquals(report.useSites, Nil)
    assertEquals(report.totalSyntheticCount, 0)
    assertEquals(report.truncated, false)

  test("does not classify synthetic arguments without source-owned given or implicit metadata"):
    val document = fixtureDocument()
    val withoutIntShow =
      document.copy(symbols = document.symbols.filterNot(_.symbol == "typeclassfixture/Show.intShow."))
    val report = TypeclassSummarySpike.summarize(withoutIntShow)

    assert(!report.useSites.flatMap(_.selectedContexts).exists(_.displayName == "intShow"))
    assert(report.warnings.exists(_.contains("source-owned")))

  test("bounds synthetics and returned use sites with an explicit Truncated status"):
    val document = fixtureDocument()
    val selectedSynthetic = document.synthetics(2)
    val oversized =
      document.copy(synthetics = List.fill(TypeclassSummarySpike.MaxSynthetics + 1)(selectedSynthetic))
    val report = TypeclassSummarySpike.summarize(oversized)

    assertEquals(report.status, TypeclassSummarySpikeStatus.Truncated)
    assertEquals(report.totalSyntheticCount, TypeclassSummarySpike.MaxSynthetics + 1)
    assertEquals(report.scannedSyntheticCount, TypeclassSummarySpike.MaxSynthetics)
    assertEquals(report.useSites.size, TypeclassSummarySpike.MaxUseSites)
    assertEquals(report.truncated, true)

  test("rejects missing, wrong-extension, malformed, multi-document, and oversized inputs"):
    val missing = Path.of("target", "missing-typeclass-summary-spike.semanticdb")
    assert(TypeclassSummarySpike.summarize(missing).left.exists(_.contains("does not exist")))
    assert(TypeclassSummarySpike.summarize(Path.of("README.md")).left.exists(_.contains(".semanticdb")))

    withTemporarySemanticdb(Array[Byte](1, 2, 3)) { malformed =>
      assert(TypeclassSummarySpike.summarize(malformed).left.exists(_.contains("Unable to read")))
    }

    val document = fixtureDocument()
    val multi = TextDocuments(documents = List(document, document)).toByteArray
    withTemporarySemanticdb(multi) { path =>
      assert(TypeclassSummarySpike.summarize(path).left.exists(_.contains("exactly one document")))
    }

    val tooLarge = Array.fill[Byte]((TypeclassSummarySpike.MaxInputBytes + 1L).toInt)(0)
    withTemporarySemanticdb(tooLarge) { path =>
      assert(TypeclassSummarySpike.summarize(path).left.exists(_.contains("byte spike limit")))
    }

  test("report models exclude source text, raw compiler payloads, absolute paths, and local symbol IDs"):
    val report = summarizeFixture()
    assertEquals(
      report.productElementNames.toList,
      List(
        "status",
        "useSites",
        "totalSyntheticCount",
        "scannedSyntheticCount",
        "truncated",
        "warnings"
      )
    )
    val local = report.useSites.flatMap(_.selectedContexts).find(_.displayName == "localShow").get
    assertEquals(local.semanticdbSymbol, None)
    assert(!report.toString.contains("/home/"))
    assert(!report.toString.contains("local2"))
    assert(!report.toString.contains("local3"))

  private def summarizeFixture(): TypeclassSummarySpikeReport =
    TypeclassSummarySpike.summarize(Fixture).fold(message => fail(message), identity)

  private def fixtureDocument(): TextDocument =
    TextDocuments.parseFrom(Files.readAllBytes(Fixture)).documents.head

  private def withTemporarySemanticdb(bytes: Array[Byte])(use: Path => Unit): Unit =
    val path = Files.createTempFile("typeclass-summary-spike-", ".semanticdb")
    try
      Files.write(path, bytes)
      use(path)
    finally
      Files.deleteIfExists(path)
