package semantic.harness.semanticdb_reader

import java.nio.file.Path

object SemanticdbStatusV2:
  val DefaultCandidateLimit = 200
  val DefaultDuplicatePathLimit = 20
  val CoverageNotAssessed = "NotAssessed"

  private final case class DuplicateFacts(
    duplicateGroupId: String,
    duplicateCount: Int,
    representative: String
  )

  def inspect(
    workspace: Path,
    candidateLimit: Int = DefaultCandidateLimit,
    duplicatePathLimit: Int = DefaultDuplicatePathLimit
  ): Either[String, SemanticdbStatusReportV2] =
    if candidateLimit < 0 then Left(s"Candidate limit must be non-negative: $candidateLimit")
    else if duplicatePathLimit < 0 then Left(s"Duplicate path limit must be non-negative: $duplicatePathLimit")
    else
      SemanticdbArtifactInventory.inspect(workspace).map { inventory =>
        val duplicateFacts = inventory.contents.flatMap { content =>
          content.artifactPaths.map { path =>
            path -> DuplicateFacts(
              duplicateGroupId = content.contentHash,
              duplicateCount = content.artifactPaths.size,
              representative = content.artifactPaths.head
            )
          }
        }.toMap
        val duplicateGroups = inventory.contents
          .filter(_.artifactPaths.size > 1)
          .sortBy(_.artifactPaths.head)
          .map(content => duplicateGroup(content, duplicatePathLimit))
        val candidates = inventory.candidates
          .take(candidateLimit)
          .map(candidate => candidateReport(candidate, duplicateFacts.get(candidate.semanticdb)))
        val parseable = inventory.candidates.count(_.parseStatus == SemanticdbArtifactInventory.Parsed)
        val unparseable = inventory.candidates.count(_.parseStatus == SemanticdbArtifactInventory.Unparseable)
        val uniqueContentFiles = inventory.contents.size
        val duplicateFiles = inventory.contents.map(content => content.artifactPaths.size - 1).sum
        val warnings = inventory.candidates.collect {
          case candidate if candidate.contentHash.isEmpty =>
            s"Raw artifact metadata unavailable for ${candidate.semanticdb}; excluded from content counts and duplicate grouping"
        }

        SemanticdbStatusReportV2(
          workspace = inventory.workspace.toString,
          artifactStatus = SemanticdbStatus.status(inventory.candidates.size, parseable, unparseable),
          coverageStatus = CoverageNotAssessed,
          semanticdbFiles = inventory.candidates.size,
          uniqueContentFiles = uniqueContentFiles,
          duplicateFiles = duplicateFiles,
          duplicateGroupCount = duplicateGroups.size,
          parseableFiles = parseable,
          unparseableFiles = unparseable,
          totalCandidates = inventory.candidates.size,
          returnedCandidates = candidates.size,
          candidateLimit = candidateLimit,
          candidatesTruncated = candidates.size < inventory.candidates.size,
          duplicateGroups = duplicateGroups,
          candidates = candidates,
          warnings = warnings,
          errors = Nil
        )
      }

  private def candidateReport(
    candidate: SemanticdbArtifactCandidate,
    duplicateFacts: Option[DuplicateFacts]
  ): SemanticdbStatusCandidateV2 =
    SemanticdbStatusCandidateV2(
      semanticdb = candidate.semanticdb,
      parseStatus = candidate.parseStatus,
      mtimeMillis = candidate.mtimeMillis,
      sizeBytes = candidate.sizeBytes,
      contentHash = candidate.contentHash,
      duplicateGroupId = duplicateFacts.map(_.duplicateGroupId),
      duplicateCount = duplicateFacts.map(_.duplicateCount),
      duplicateRepresentative = duplicateFacts.map(_.representative),
      documentCount = candidate.documents.size,
      documentsParsed = candidate.documents.size,
      documentsIgnored = 0,
      documentUris = candidate.documents.map(_.uri),
      documents = candidate.documents,
      totalSymbols = candidate.documents.map(_.symbols).sum,
      totalOccurrences = candidate.documents.map(_.occurrences).sum,
      error = candidate.error
    )

  private def duplicateGroup(
    content: SemanticdbArtifactContent,
    pathLimit: Int
  ): SemanticdbDuplicateGroup =
    val representative = content.artifactPaths.head
    val samplePaths = content.artifactPaths.take(pathLimit)
    SemanticdbDuplicateGroup(
      duplicateGroupId = content.contentHash,
      contentHash = content.contentHash,
      sizeBytes = content.sizeBytes,
      fileCount = content.artifactPaths.size,
      representative = representative,
      samplePaths = samplePaths,
      returnedPaths = samplePaths.size,
      pathsTruncated = samplePaths.size < content.artifactPaths.size
    )
