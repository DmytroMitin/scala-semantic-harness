package semantic.harness.semanticdb_reader

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import scala.meta.internal.semanticdb.TextDocuments
import scala.util.control.NonFatal

private[semanticdb_reader] final case class SemanticdbArtifactCandidate(
  semanticdb: String,
  parseStatus: String,
  mtimeMillis: Option[Long],
  sizeBytes: Option[Long],
  contentHash: Option[String],
  documents: List[SemanticdbDocumentSummary],
  error: Option[String],
  hints: SemanticdbArtifactHints
)

private[semanticdb_reader] final case class SemanticdbArtifactContent(
  contentHash: String,
  sizeBytes: Long,
  artifactPaths: List[String],
  documents: List[SemanticdbDocumentSummary]
)

private[semanticdb_reader] final case class SemanticdbArtifactInventory(
  workspace: Path,
  candidates: List[SemanticdbArtifactCandidate],
  contents: List[SemanticdbArtifactContent]
)

private[semanticdb_reader] object SemanticdbArtifactInventory:
  val Parsed = "Parsed"
  val Unparseable = "Unparseable"

  private final case class RawCandidate(
    path: Path,
    semanticdb: String,
    mtimeMillis: Option[Long],
    sizeBytes: Option[Long],
    contentHash: Option[String],
    bytes: Option[Array[Byte]],
    readError: Option[String]
  )

  private enum ParsedContent:
    case Parsed(documents: List[SemanticdbDocumentSummary])
    case Empty
    case Failed(message: String)

  def inspect(workspace: Path): Either[String, SemanticdbArtifactInventory] =
    SemanticdbStatus.discoverFiles(workspace).map { case (normalizedWorkspace, files) =>
      val rawCandidates = files.map(path => readCandidate(normalizedWorkspace, path))
      val grouped = rawCandidates
        .flatMap(candidate => candidate.contentHash.map(_ -> candidate))
        .groupMap(_._1)(_._2)
        .toList
        .sortBy(_._1)
        .map { case (contentHash, members) =>
          contentHash -> members.sortBy(_.semanticdb)
        }
      val parsedByHash = grouped.map { case (contentHash, members) =>
        val bytes = members.head.bytes.getOrElse(throw new IllegalStateException("readable content without bytes"))
        contentHash -> parse(bytes)
      }.toMap
      val duplicateFacts = grouped.flatMap { case (contentHash, members) =>
        val paths = members.map(_.semanticdb)
        members.map(member => member.semanticdb -> (contentHash, paths))
      }.toMap
      val candidates = rawCandidates.map { candidate =>
        candidateResult(candidate, parsedByHash, duplicateFacts.get(candidate.semanticdb))
      }
      val contents = grouped.map { case (contentHash, members) =>
        val documents = parsedByHash(contentHash) match
          case ParsedContent.Parsed(value) => value
          case _                           => Nil
        SemanticdbArtifactContent(
          contentHash = contentHash,
          sizeBytes = members.head.sizeBytes.getOrElse(throw new IllegalStateException("readable content without size")),
          artifactPaths = members.map(_.semanticdb),
          documents = documents
        )
      }

      SemanticdbArtifactInventory(
        workspace = normalizedWorkspace,
        candidates = candidates,
        contents = contents
      )
    }

  private def readCandidate(workspace: Path, path: Path): RawCandidate =
    val semanticdb = SemanticdbStatus.relativePath(workspace, path)
    val mtime = metadata(Files.getLastModifiedTime(path).toMillis)
    try
      val bytes = Files.readAllBytes(path)
      RawCandidate(
        path = path,
        semanticdb = semanticdb,
        mtimeMillis = mtime,
        sizeBytes = Some(bytes.length.toLong),
        contentHash = Some(sha256(bytes)),
        bytes = Some(bytes),
        readError = None
      )
    catch
      case NonFatal(error) =>
        RawCandidate(
          path = path,
          semanticdb = semanticdb,
          mtimeMillis = mtime,
          sizeBytes = metadata(Files.size(path)),
          contentHash = None,
          bytes = None,
          readError = Some(error.getMessage)
        )

  private def parse(bytes: Array[Byte]): ParsedContent =
    try
      val documents = TextDocuments.parseFrom(bytes).documents.toList.map { document =>
        SemanticdbDocumentSummary(
          uri = document.uri,
          symbols = document.symbols.size,
          occurrences = document.occurrences.size
        )
      }
      if documents.isEmpty then ParsedContent.Empty
      else ParsedContent.Parsed(documents)
    catch
      case NonFatal(error) => ParsedContent.Failed(error.getMessage)

  private def candidateResult(
    candidate: RawCandidate,
    parsedByHash: Map[String, ParsedContent],
    duplicateFacts: Option[(String, List[String])]
  ): SemanticdbArtifactCandidate =
    candidate.readError match
      case Some(message) =>
        result(
          candidate = candidate,
          semanticdb = candidate.semanticdb,
          parseStatus = Unparseable,
          contentHash = None,
          documents = Nil,
          error = Some(
            SemanticdbStatus.bounded(
              s"Unable to read SemanticDB file ${candidate.path}: $message"
            )
          ),
          duplicateFacts = duplicateFacts
        )
      case None =>
        parsedByHash(candidate.contentHash.getOrElse(throw new IllegalStateException("readable candidate without hash"))) match
          case ParsedContent.Parsed(documents) =>
            result(
              candidate = candidate,
              semanticdb = candidate.semanticdb,
              parseStatus = Parsed,
              contentHash = candidate.contentHash,
              documents = documents,
              error = None,
              duplicateFacts = duplicateFacts
            )
          case ParsedContent.Empty =>
            result(
              candidate = candidate,
              semanticdb = candidate.semanticdb,
              parseStatus = Unparseable,
              contentHash = candidate.contentHash,
              documents = Nil,
              error = Some(
                SemanticdbStatus.bounded(
                  s"SemanticDB file contains no documents: ${candidate.path}"
                )
              ),
              duplicateFacts = duplicateFacts
            )
          case ParsedContent.Failed(message) =>
            result(
              candidate = candidate,
              semanticdb = candidate.semanticdb,
              parseStatus = Unparseable,
              contentHash = candidate.contentHash,
              documents = Nil,
              error = Some(
                SemanticdbStatus.bounded(
                  s"Unable to read SemanticDB file ${candidate.path}: $message"
                )
              ),
              duplicateFacts = duplicateFacts
            )

  private def result(
    candidate: RawCandidate,
    semanticdb: String,
    parseStatus: String,
    contentHash: Option[String],
    documents: List[SemanticdbDocumentSummary],
    error: Option[String],
    duplicateFacts: Option[(String, List[String])]
  ): SemanticdbArtifactCandidate =
    val hints = SemanticdbArtifactHintClassifier.classify(
      SemanticdbArtifactHintInput(
        artifactPath = semanticdb,
        workspaceScope = SemanticdbWorkspaceScope.InsideWorkspace,
        documentUris = documents.map(_.uri),
        matchedSourcePaths = Nil,
        duplicateGroupId = duplicateFacts.map(_._1),
        duplicateArtifactPaths = duplicateFacts.map(_._2).getOrElse(Nil)
      )
    )
    SemanticdbArtifactCandidate(
      semanticdb = semanticdb,
      parseStatus = parseStatus,
      mtimeMillis = candidate.mtimeMillis,
      sizeBytes = candidate.sizeBytes,
      contentHash = contentHash,
      documents = documents,
      error = error,
      hints = hints
    )

  private def sha256(bytes: Array[Byte]): String =
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val hex = digest.iterator.map(byte => f"${byte & 0xff}%02x").mkString
    s"sha256:$hex"

  private def metadata[A](read: => A): Option[A] =
    try Some(read)
    catch case NonFatal(_) => None
