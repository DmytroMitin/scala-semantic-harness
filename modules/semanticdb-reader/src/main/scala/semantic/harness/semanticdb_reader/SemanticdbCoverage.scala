package semantic.harness.semanticdb_reader

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

object SemanticdbCoverage:
  val InventoryKind = "WorkspaceRecursiveSourceScanV1"
  val StatusNoInventorySources = "NoInventorySources"
  val StatusNoSemanticdbDocuments = "NoSemanticdbDocuments"
  val StatusNoCoveredSources = "NoCoveredSources"
  val StatusPartial = "Partial"
  val StatusCompleteWithinInventory = "CompleteWithinInventory"

  val SourceCovered = "Covered"
  val SourceUncovered = "Uncovered"
  val SourceAmbiguous = "Ambiguous"

  val MatchUriExact = "UriExact"
  val MatchUriSuffix = "UriSuffix"

  val DefaultSourceEntryLimit = 200
  val DefaultDocumentEntryLimit = 200
  val DefaultArtifactPathSampleLimit = 20

  val Extensions = List(".scala", ".java")
  val ExcludedDirectoryNames =
    List(".git", ".idea", ".metals", ".bloop", ".scala-build", "target", "out", "node_modules")

  private final case class SourceFile(path: String, language: String)

  private final case class SourceInventory(
    workspace: Path,
    sources: List[SourceFile],
    warnings: List[String]
  )

  private final case class DocumentEvidence(
    documentEvidenceId: String,
    uri: String,
    normalizedUri: String,
    contentHash: String,
    documentIndex: Int,
    artifactCopies: Int,
    artifactPathSamples: List[String],
    returnedArtifactPaths: Int,
    artifactPathsTruncated: Boolean,
    symbols: Int,
    occurrences: Int
  )

  private final case class SourceMatch(source: SourceFile, matchKind: String)

  private final case class MatchedEvidence(
    evidence: DocumentEvidence,
    sourceMatches: List[SourceMatch]
  )

  def inspect(
    workspace: Path,
    sourceEntryLimit: Int = DefaultSourceEntryLimit,
    documentEntryLimit: Int = DefaultDocumentEntryLimit,
    artifactPathSampleLimit: Int = DefaultArtifactPathSampleLimit
  ): Either[String, SemanticdbCoverageReport] =
    validateLimits(sourceEntryLimit, documentEntryLimit, artifactPathSampleLimit).flatMap { _ =>
      scanSources(workspace).flatMap { sourceInventory =>
        SemanticdbArtifactInventory.inspect(sourceInventory.workspace).map { artifactInventory =>
          buildReport(
            sourceInventory,
            artifactInventory,
            sourceEntryLimit,
            documentEntryLimit,
            artifactPathSampleLimit
          )
        }
      }
    }

  private def buildReport(
    sourceInventory: SourceInventory,
    artifactInventory: SemanticdbArtifactInventory,
    sourceEntryLimit: Int,
    documentEntryLimit: Int,
    artifactPathSampleLimit: Int
  ): SemanticdbCoverageReport =
    val evidence = documentEvidence(artifactInventory, artifactPathSampleLimit)
    val matchedEvidence = evidence.map { item =>
      MatchedEvidence(item, sourceMatches(item.normalizedUri, sourceInventory.sources))
    }
    val sourceEntries = sourceInventory.sources.map { source =>
      sourceEntry(source, matchedEvidence)
    }
    val unmatched = matchedEvidence.filter(_.sourceMatches.isEmpty).map(_.evidence)
    val covered = sourceEntries.count(_.coverage == SourceCovered)
    val uncovered = sourceEntries.count(_.coverage == SourceUncovered)
    val ambiguous = sourceEntries.count(_.coverage == SourceAmbiguous)
    val returnedSources = sourceEntries.take(sourceEntryLimit)
    val returnedUnmatched = unmatched.take(documentEntryLimit).map(unmatchedDocument)
    val sourceEntriesTruncated = returnedSources.size < sourceEntries.size
    val unmatchedTruncated = returnedUnmatched.size < unmatched.size
    val pathSamplesTruncated = evidence.count(_.artifactPathsTruncated)
    val basenameWarnings = matchedEvidence.collect {
      case item if pathSegmentCount(item.evidence.normalizedUri) < 2 =>
        SemanticdbStatus.bounded(
          s"Ignored SemanticDB URI '${item.evidence.uri}' from ${item.evidence.documentEvidenceId}; basename-only identity is not used"
        )
    }
    val artifactWarnings = artifactInventory.candidates.collect {
      case candidate if candidate.parseStatus != SemanticdbArtifactInventory.Parsed =>
        SemanticdbStatus.bounded(
          s"SemanticDB artifact ${candidate.semanticdb} has no parseable document evidence: ${candidate.error.getOrElse("unknown parse failure")}"
        )
    }
    val boundingWarnings =
      List(
        Option.when(pathSamplesTruncated > 0)(
          s"Artifact path samples were truncated for $pathSamplesTruncated unique document evidence item(s) at limit $artifactPathSampleLimit"
        ),
        Option.when(sourceEntriesTruncated)(
          s"Source entries were truncated: returned ${returnedSources.size} of ${sourceEntries.size} at limit $sourceEntryLimit"
        ),
        Option.when(unmatchedTruncated)(
          s"Unmatched document entries were truncated: returned ${returnedUnmatched.size} of ${unmatched.size} at limit $documentEntryLimit"
        )
      ).flatten

    SemanticdbCoverageReport(
      workspace = sourceInventory.workspace.toString,
      coverageStatus = coverageStatus(sourceEntries, evidence.nonEmpty),
      inventoryBasis = SemanticdbCoverageInventoryBasis(
        kind = InventoryKind,
        extensions = Extensions,
        excludedDirectoryNames = ExcludedDirectoryNames,
        followsSymbolicLinks = false,
        includesFilesOutsideConventionalRoots = true
      ),
      sourceFiles = sourceEntries.size,
      coveredSourceFiles = covered,
      uncoveredSourceFiles = uncovered,
      ambiguousSourceFiles = ambiguous,
      semanticdbArtifactFiles = artifactInventory.candidates.size,
      uniqueArtifactContents = artifactInventory.contents.size,
      rawDocumentEntries = artifactInventory.candidates.map(_.documents.size).sum,
      uniqueDocumentEvidence = evidence.size,
      matchedDocumentEvidence = matchedEvidence.count(_.sourceMatches.nonEmpty),
      unmatchedDocumentEvidence = unmatched.size,
      ambiguousDocumentEvidence = matchedEvidence.count(_.sourceMatches.size > 1),
      totalSourceEntries = sourceEntries.size,
      returnedSourceEntries = returnedSources.size,
      sourceEntryLimit = sourceEntryLimit,
      sourceEntriesTruncated = sourceEntriesTruncated,
      totalUnmatchedDocumentEntries = unmatched.size,
      returnedUnmatchedDocumentEntries = returnedUnmatched.size,
      documentEntryLimit = documentEntryLimit,
      unmatchedDocumentEntriesTruncated = unmatchedTruncated,
      sources = returnedSources,
      unmatchedDocuments = returnedUnmatched,
      warnings = sourceInventory.warnings ++ artifactWarnings ++ basenameWarnings ++ boundingWarnings,
      errors = Nil
    )

  private def documentEvidence(
    inventory: SemanticdbArtifactInventory,
    artifactPathSampleLimit: Int
  ): List[DocumentEvidence] =
    inventory.contents.flatMap { content =>
      val samples = content.artifactPaths.take(artifactPathSampleLimit)
      content.documents.zipWithIndex.map { case (document, index) =>
        DocumentEvidence(
          documentEvidenceId = s"${content.contentHash}#document-$index",
          uri = document.uri,
          normalizedUri = normalizeUri(document.uri),
          contentHash = content.contentHash,
          documentIndex = index,
          artifactCopies = content.artifactPaths.size,
          artifactPathSamples = samples,
          returnedArtifactPaths = samples.size,
          artifactPathsTruncated = samples.size < content.artifactPaths.size,
          symbols = document.symbols,
          occurrences = document.occurrences
        )
      }
    }.sortBy(item => (item.contentHash, item.documentIndex))

  private def sourceMatches(normalizedUri: String, sources: List[SourceFile]): List[SourceMatch] =
    if pathSegmentCount(normalizedUri) < 2 then Nil
    else
      sources.flatMap { source =>
        val matchKind =
          if source.path == normalizedUri then Some(MatchUriExact)
          else if source.path.endsWith(s"/$normalizedUri") then Some(MatchUriSuffix)
          else None
        matchKind.map(kind => SourceMatch(source, kind))
      }

  private def sourceEntry(
    source: SourceFile,
    evidence: List[MatchedEvidence]
  ): SemanticdbSourceCoverageEntry =
    val matching = evidence.flatMap { item =>
      item.sourceMatches
        .find(_.source.path == source.path)
        .map(sourceMatch => item -> sourceMatch.matchKind)
    }
    val coverage =
      if matching.isEmpty then SourceUncovered
      else if matching.size == 1 && matching.head._1.sourceMatches.size == 1 then SourceCovered
      else SourceAmbiguous
    val warnings =
      if matching.size > 1 then
        List(
          SemanticdbStatus.bounded(
            s"Multiple byte-distinct SemanticDB document evidence items match ${source.path}"
          )
        )
      else if matching.headOption.exists(_._1.sourceMatches.size > 1) then
        List(
          SemanticdbStatus.bounded(
            s"SemanticDB document evidence also matches other inventory sources; no source was selected for ${source.path}"
          )
        )
      else Nil

    SemanticdbSourceCoverageEntry(
      source = source.path,
      language = source.language,
      coverage = coverage,
      matchKind = Option.when(coverage == SourceCovered)(matching.head._2),
      matches = matching.map { case (item, matchKind) =>
        coverageMatch(item.evidence, matchKind)
      },
      warnings = warnings
    )

  private def coverageMatch(
    evidence: DocumentEvidence,
    matchKind: String
  ): SemanticdbCoverageMatch =
    SemanticdbCoverageMatch(
      documentEvidenceId = evidence.documentEvidenceId,
      uri = evidence.uri,
      normalizedUri = evidence.normalizedUri,
      contentHash = evidence.contentHash,
      documentIndex = evidence.documentIndex,
      matchKind = matchKind,
      artifactCopies = evidence.artifactCopies,
      artifactPathSamples = evidence.artifactPathSamples,
      returnedArtifactPaths = evidence.returnedArtifactPaths,
      artifactPathsTruncated = evidence.artifactPathsTruncated,
      symbols = evidence.symbols,
      occurrences = evidence.occurrences
    )

  private def unmatchedDocument(
    evidence: DocumentEvidence
  ): SemanticdbUnmatchedDocumentEvidence =
    SemanticdbUnmatchedDocumentEvidence(
      documentEvidenceId = evidence.documentEvidenceId,
      uri = evidence.uri,
      normalizedUri = evidence.normalizedUri,
      contentHash = evidence.contentHash,
      documentIndex = evidence.documentIndex,
      artifactCopies = evidence.artifactCopies,
      artifactPathSamples = evidence.artifactPathSamples,
      returnedArtifactPaths = evidence.returnedArtifactPaths,
      artifactPathsTruncated = evidence.artifactPathsTruncated,
      symbols = evidence.symbols,
      occurrences = evidence.occurrences
    )

  private def coverageStatus(
    sources: List[SemanticdbSourceCoverageEntry],
    hasDocumentEvidence: Boolean
  ): String =
    if sources.isEmpty then StatusNoInventorySources
    else if !hasDocumentEvidence then StatusNoSemanticdbDocuments
    else if !sources.exists(_.coverage == SourceCovered) then StatusNoCoveredSources
    else if sources.forall(_.coverage == SourceCovered) then StatusCompleteWithinInventory
    else StatusPartial

  private def scanSources(workspace: Path): Either[String, SourceInventory] =
    val normalizedWorkspace = workspace.toAbsolutePath.normalize()
    if !Files.exists(normalizedWorkspace) then Left(s"Workspace does not exist: $normalizedWorkspace")
    else if !Files.isDirectory(normalizedWorkspace) then Left(s"Workspace is not a directory: $normalizedWorkspace")
    else
      val sources = ListBuffer.empty[SourceFile]
      val warnings = ListBuffer.empty[String]
      try
        Files.walkFileTree(
          normalizedWorkspace,
          new SimpleFileVisitor[Path]:
            override def preVisitDirectory(directory: Path, attributes: BasicFileAttributes): FileVisitResult =
              val excluded =
                directory != normalizedWorkspace &&
                  Option(directory.getFileName).exists(name => ExcludedDirectoryNames.contains(name.toString))
              if excluded then FileVisitResult.SKIP_SUBTREE
              else FileVisitResult.CONTINUE

            override def visitFile(file: Path, attributes: BasicFileAttributes): FileVisitResult =
              if attributes.isRegularFile then
                val relative = normalizedWorkspace.relativize(file.toAbsolutePath.normalize()).toString.replace('\\', '/')
                language(relative).foreach(value => sources += SourceFile(relative, value))
              FileVisitResult.CONTINUE

            override def visitFileFailed(file: Path, error: IOException): FileVisitResult =
              warnings += SemanticdbStatus.bounded(
                s"Unable to inspect source inventory path $file: ${error.getMessage}"
              )
              FileVisitResult.CONTINUE

            override def postVisitDirectory(directory: Path, error: IOException): FileVisitResult =
              if error != null then
                warnings += SemanticdbStatus.bounded(
                  s"Unable to finish source inventory directory $directory: ${error.getMessage}"
                )
              FileVisitResult.CONTINUE
        )
        Right(
          SourceInventory(
            workspace = normalizedWorkspace,
            sources = sources.toList.sortBy(_.path),
            warnings = warnings.toList.sorted
          )
        )
      catch
        case NonFatal(error) =>
          Left(
            s"Unable to scan workspace for source files: ${SemanticdbStatus.bounded(error.getMessage)}"
          )

  private def language(path: String): Option[String] =
    if path.endsWith(".scala") then Some("Scala")
    else if path.endsWith(".java") then Some("Java")
    else None

  private def normalizeUri(uri: String): String =
    uri.replace('\\', '/').stripPrefix("./")

  private def pathSegmentCount(path: String): Int =
    path.split('/').count(_.nonEmpty)

  private def validateLimits(
    sourceEntryLimit: Int,
    documentEntryLimit: Int,
    artifactPathSampleLimit: Int
  ): Either[String, Unit] =
    if sourceEntryLimit < 0 then Left(s"Source entry limit must be non-negative: $sourceEntryLimit")
    else if documentEntryLimit < 0 then Left(s"Document entry limit must be non-negative: $documentEntryLimit")
    else if artifactPathSampleLimit < 0 then
      Left(s"Artifact path sample limit must be non-negative: $artifactPathSampleLimit")
    else Right(())
