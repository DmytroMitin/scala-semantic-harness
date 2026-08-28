package semantic.harness.semanticdb_reader

import java.nio.file.Path

final case class SemanticdbDocumentSnapshot private[semanticdb_reader] (
  index: Int,
  uri: String,
  semanticdbMd5: Option[String],
  hasEmbeddedText: Boolean,
  summary: SemanticFileSummary,
  private[semanticdb_reader] val embeddedText: Option[String]
)

final case class ArtifactSnapshot(
  path: Path,
  sha256: String,
  mtimeMillis: Long,
  documents: List[SemanticdbDocumentSnapshot]
)

final case class FreshnessAssessment(
  document: Option[SemanticdbDocumentSnapshot],
  freshness: SourceArtifactFreshness
)

final case class SemanticdbForSourceV2Inspection(
  report: SemanticdbForSourceReportV2,
  sourceSnapshot: SourceSnapshot,
  artifactSnapshots: Map[String, ArtifactSnapshot]
)

object FreshnessAssessor:
  private val Md5Pattern = "(?i)[0-9a-f]{32}".r
  private val SourceRoots = List("src/main/scala", "src/test/scala", "src/main/java", "src/test/java")

  def assess(
    source: SourceSnapshot,
    artifact: ArtifactSnapshot,
    sourceRelativePath: String
  ): FreshnessAssessment =
    val mapped = artifact.documents.filter(document => uriMatches(document.uri, sourceRelativePath))
    mapped match
      case Nil => unverifiable(source, artifact, None, UnverifiableReason.NoUniqueDocumentForSource)
      case _ :: _ :: _ => unverifiable(source, artifact, None, UnverifiableReason.AmbiguousDocumentIdentity)
      case document :: Nil => assessIdentity(source, artifact, document)

  private def assessIdentity(
    source: SourceSnapshot,
    artifact: ArtifactSnapshot,
    document: SemanticdbDocumentSnapshot
  ): FreshnessAssessment =
    source.unverifiableReason match
      case Some(reason) => unverifiable(source, artifact, Some(document), reason)
      case None =>
        val rawMd5 = document.semanticdbMd5
        val embedded = document.embeddedText
        rawMd5 match
          case Some(value) if !Md5Pattern.matches(value) =>
            unverifiable(source, artifact, Some(document), UnverifiableReason.MalformedDocumentDigest)
          case Some(value) =>
            val normalized = value.toLowerCase
            embedded match
              case Some(text) if Digests.md5Utf8(text) != normalized =>
                unverifiable(source, artifact, Some(document), UnverifiableReason.InconsistentDocumentIdentity)
              case _ =>
                val evidence = freshnessEvidence(
                  source,
                  artifact,
                  Some(document),
                  FreshnessBasis.SemanticdbMd5Utf8,
                  Some(normalized),
                  Some("SemanticdbMd5Compared")
                )
                FreshnessAssessment(
                  Some(document),
                  if source.md5.contains(normalized) then SourceArtifactFreshness.Fresh(evidence)
                  else SourceArtifactFreshness.Stale(evidence)
                )
          case None =>
            embedded match
              case Some(text) =>
                val evidence = freshnessEvidence(
                  source,
                  artifact,
                  Some(document),
                  FreshnessBasis.EmbeddedTextExact,
                  None,
                  Some("EmbeddedTextCompared")
                )
                FreshnessAssessment(
                  Some(document),
                  if source.content.contains(text) then SourceArtifactFreshness.Fresh(evidence)
                  else SourceArtifactFreshness.Stale(evidence)
                )
              case None =>
                unverifiable(source, artifact, Some(document), UnverifiableReason.MissingDocumentIdentity)

  private def unverifiable(
    source: SourceSnapshot,
    artifact: ArtifactSnapshot,
    document: Option[SemanticdbDocumentSnapshot],
    reason: UnverifiableReason
  ): FreshnessAssessment =
    val value = freshnessEvidence(source, artifact, document, FreshnessBasis.None, None, Some(reason.toString))
    FreshnessAssessment(document, SourceArtifactFreshness.Unverifiable(reason, value))

  private def freshnessEvidence(
    source: SourceSnapshot,
    artifact: ArtifactSnapshot,
    document: Option[SemanticdbDocumentSnapshot],
    basis: FreshnessBasis,
    normalizedSemanticdbMd5: Option[String],
    detailCode: Option[String]
  ): FreshnessEvidence = FreshnessEvidence(
    basis = basis,
    documentUri = document.map(_.uri),
    documentIndex = document.map(_.index),
    semanticdbMd5 = normalizedSemanticdbMd5.orElse(document.flatMap(_.semanticdbMd5).map(_.toLowerCase)),
    sourceMd5 = source.md5,
    sourceSnapshotSha256 = source.sha256,
    artifactSnapshotSha256 = Some(artifact.sha256),
    sourceMtimeMillis = source.mtimeMillis,
    artifactMtimeMillis = Some(artifact.mtimeMillis),
    detailCode = detailCode
  )

  private def uriMatches(uri: String, sourceRelativePath: String): Boolean =
    val normalizedUri = normalize(uri)
    val normalizedSource = normalize(sourceRelativePath)
    normalizedUri == normalizedSource ||
      sourceRootSuffix(normalizedUri).nonEmpty && sourceRootSuffix(normalizedUri) == sourceRootSuffix(normalizedSource)

  private def sourceRootSuffix(value: String): Option[String] =
    SourceRoots.flatMap { root =>
      val atStart = Option.when(value.startsWith(root))(0)
      val afterSeparator = Option(value.indexOf(s"/$root")).filter(_ >= 0).map(_ + 1)
      atStart.orElse(afterSeparator).map(value.substring)
    }.headOption

  private def normalize(value: String): String = value.replace('\\', '/').stripPrefix("./")
