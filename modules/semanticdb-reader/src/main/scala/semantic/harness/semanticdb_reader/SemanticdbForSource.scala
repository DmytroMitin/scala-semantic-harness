package semantic.harness.semanticdb_reader

import java.nio.file.Files
import java.nio.file.Path

object SemanticdbForSource:
  val StatusUniqueMatch = "UniqueMatch"
  val StatusNoMatch = "NoMatch"
  val StatusAmbiguous = "Ambiguous"
  val StatusUnavailable = "Unavailable"
  val StatusUnparseable = "Unparseable"
  val StatusPartial = "Partial"

  val MatchUriExact = "UriExact"
  val MatchMetaInfSuffix = "MetaInfSuffix"
  val MatchSourceRootSuffix = "SourceRootSuffix"

  private val Parsed = "Parsed"
  private val SourceRoots =
    List("src/main/scala", "src/test/scala", "src/main/java", "src/test/java")

  def inspect(workspace: Path, sourceFile: Path): Either[String, SemanticdbForSourceReport] =
    val normalizedWorkspace = workspace.toAbsolutePath.normalize()
    val normalizedSource = sourceFile.toAbsolutePath.normalize()

    validateSource(normalizedSource).flatMap { _ =>
      SemanticdbStatus.inspect(normalizedWorkspace).map { discovery =>
        val sourceRelative =
          Option.when(normalizedSource.startsWith(normalizedWorkspace))(
            normalizedWorkspace.relativize(normalizedSource).toString.replace('\\', '/')
          )
        val sourceRootRelative = sourceRootSuffix(sourceRelative.getOrElse(normalizedSource.toString))
        val matches = discovery.candidates
          .flatMap(candidate => matchCandidate(candidate, sourceRelative, sourceRootRelative))
          .sortBy(result => (matchRank(result.matchKind), result.semanticdb))
        val relevantUnparseable = matches.filter(_.parseStatus != Parsed)
        val warnings = relevantUnparseable.map { candidate =>
          s"Matched candidate ${candidate.semanticdb} is unparseable; path evidence is partial"
        }

        SemanticdbForSourceReport(
          workspace = discovery.workspace,
          sourceFile = normalizedSource.toString,
          sourceRelativePath = sourceRelative,
          status = status(discovery, matches),
          semanticdbFiles = discovery.semanticdbFiles,
          parseableFiles = discovery.parseableFiles,
          unparseableFiles = discovery.unparseableFiles,
          matches = matches,
          candidatesConsidered = discovery.candidates.size,
          warnings = warnings,
          errors = discovery.errors
        )
      }
    }

  def inspectV2(workspace: Path, sourceFile: Path): Either[String, SemanticdbForSourceReportV2] =
    inspectV2WithSnapshots(workspace, sourceFile, SourceSnapshot.capture(sourceFile)).map(_.report)

  def inspectV2(
    workspace: Path,
    sourceFile: Path,
    sourceSnapshot: SourceSnapshot
  ): Either[String, SemanticdbForSourceReportV2] =
    inspectV2WithSnapshots(workspace, sourceFile, sourceSnapshot).map(_.report)

  def inspectV2WithSnapshots(
    workspace: Path,
    sourceFile: Path,
    sourceSnapshot: SourceSnapshot
  ): Either[String, SemanticdbForSourceV2Inspection] =
    val normalizedWorkspace = workspace.toAbsolutePath.normalize()
    val normalizedSource = sourceFile.toAbsolutePath.normalize()
    validateSource(normalizedSource).flatMap { _ =>
      SemanticdbStatus.discoverFiles(normalizedWorkspace).map { case (_, files) =>
        val sourceRelative = Option.when(normalizedSource.startsWith(normalizedWorkspace))(
          normalize(normalizedWorkspace.relativize(normalizedSource).toString)
        )
        val sourceIdentityPath = sourceRelative.getOrElse(normalize(normalizedSource.toString))
        val sourceRootRelative = sourceRootSuffix(sourceIdentityPath)
        var parseableFiles = 0
        var unparseableFiles = 0
        var artifactSnapshots = Map.empty[String, ArtifactSnapshot]
        val matches = files.flatMap { path =>
          val semanticdb = SemanticdbStatus.relativePath(normalizedWorkspace, path)
          SemanticdbReader.readSnapshot(path) match
            case Right(snapshot) =>
              parseableFiles += 1
              artifactSnapshots = artifactSnapshots.updated(semanticdb, snapshot)
              matchKind(snapshot, semanticdb, sourceRelative, sourceRootRelative).map { kind =>
                val assessment = FreshnessAssessor.assess(sourceSnapshot, snapshot, sourceIdentityPath)
                val mapped = assessment.document
                SemanticdbSourceMatchV2(
                  semanticdb = semanticdb,
                  uri = snapshot.documents.headOption.map(_.uri),
                  parseStatus = Parsed,
                  matchKind = kind,
                  symbols = mapped.map(_.summary.symbols.size).orElse(Some(snapshot.documents.map(_.summary.symbols.size).sum)),
                  occurrences = mapped.map(_.summary.occurrences.size).orElse(Some(snapshot.documents.map(_.summary.occurrences.size).sum)),
                  mtimeMillis = snapshot.mtimeMillis,
                  error = None,
                  documentCount = Some(snapshot.documents.size),
                  documentUri = mapped.map(_.uri),
                  documentIndex = mapped.map(_.index),
                  artifactSnapshotSha256 = Some(snapshot.sha256),
                  freshness = Some(assessment.freshness)
                )
              }
            case Left(message) =>
              unparseableFiles += 1
              pathMatchKind(semanticdb, sourceRelative, sourceRootRelative).map { kind =>
                SemanticdbSourceMatchV2(
                  semanticdb = semanticdb,
                  uri = None,
                  parseStatus = "Unparseable",
                  matchKind = kind,
                  symbols = None,
                  occurrences = None,
                  mtimeMillis = modifiedTimeMillis(path),
                  error = Some(SemanticdbStatus.bounded(message)),
                  documentCount = None,
                  documentUri = None,
                  documentIndex = None,
                  artifactSnapshotSha256 = None,
                  freshness = None
                )
              }
        }.sortBy(result => (matchRank(result.matchKind), result.semanticdb))
        val warnings = matches.filter(_.parseStatus != Parsed).map(candidate =>
          s"Matched candidate ${candidate.semanticdb} is unparseable; path evidence is partial"
        )
        val report = SemanticdbForSourceReportV2(
          workspace = normalizedWorkspace.toString,
          sourceFile = normalizedSource.toString,
          sourceRelativePath = sourceRelative,
          status = statusV2(files.size, parseableFiles, matches),
          semanticdbFiles = files.size,
          parseableFiles = parseableFiles,
          unparseableFiles = unparseableFiles,
          matches = matches,
          candidatesConsidered = files.size,
          warnings = warnings,
          errors = Nil
        )
        SemanticdbForSourceV2Inspection(report, sourceSnapshot, artifactSnapshots)
      }
    }

  private def validateSource(sourceFile: Path): Either[String, Unit] =
    if !Files.exists(sourceFile) then Left(s"Source file does not exist: $sourceFile")
    else if !Files.isRegularFile(sourceFile) then Left(s"Source path is not a file: $sourceFile")
    else Right(())

  private def matchCandidate(
    candidate: SemanticdbStatusCandidate,
    sourceRelative: Option[String],
    sourceRootRelative: Option[String]
  ): Option[SemanticdbSourceMatch] =
    val uri = candidate.uri.map(normalize)
    val metaInf = metaInfSuffix(candidate.semanticdb)
    val candidatePath = Some(normalize(candidate.semanticdb).stripSuffix(".semanticdb"))
    val matchKind =
      if sourceRelative.exists(relative => uri.contains(normalize(relative))) then Some(MatchUriExact)
      else if sourceRelative.exists(relative => metaInf.contains(normalize(relative))) then Some(MatchMetaInfSuffix)
      else if sourceRootRelative.exists { sourceRoot =>
        uri.flatMap(sourceRootSuffix).contains(sourceRoot) ||
        metaInf.flatMap(sourceRootSuffix).contains(sourceRoot) ||
        candidatePath.flatMap(sourceRootSuffix).contains(sourceRoot)
      } then Some(MatchSourceRootSuffix)
      else None

    matchKind.map { kind =>
      SemanticdbSourceMatch(
        semanticdb = candidate.semanticdb,
        uri = candidate.uri,
        parseStatus = candidate.parseStatus,
        matchKind = kind,
        symbols = candidate.symbols,
        occurrences = candidate.occurrences,
        mtimeMillis = candidate.mtimeMillis,
        error = candidate.error
      )
    }

  private def matchKind(
    snapshot: ArtifactSnapshot,
    semanticdb: String,
    sourceRelative: Option[String],
    sourceRootRelative: Option[String]
  ): Option[String] =
    val uris = snapshot.documents.map(document => normalize(document.uri))
    if sourceRelative.exists(relative => uris.contains(normalize(relative))) then Some(MatchUriExact)
    else pathMatchKind(semanticdb, sourceRelative, sourceRootRelative).orElse {
      Option.when(sourceRootRelative.exists(sourceRoot => uris.flatMap(sourceRootSuffix).contains(sourceRoot)))(MatchSourceRootSuffix)
    }

  private def pathMatchKind(
    semanticdb: String,
    sourceRelative: Option[String],
    sourceRootRelative: Option[String]
  ): Option[String] =
    val metaInf = metaInfSuffix(semanticdb)
    val candidatePath = normalize(semanticdb).stripSuffix(".semanticdb")
    if sourceRelative.exists(relative => metaInf.contains(normalize(relative))) then Some(MatchMetaInfSuffix)
    else if sourceRootRelative.exists { sourceRoot =>
      metaInf.flatMap(sourceRootSuffix).contains(sourceRoot) ||
        sourceRootSuffix(candidatePath).contains(sourceRoot)
    } then Some(MatchSourceRootSuffix)
    else None

  private def status(
    discovery: SemanticdbStatusReport,
    matches: List[SemanticdbSourceMatch]
  ): String =
    if discovery.semanticdbFiles == 0 then StatusUnavailable
    else if matches.isEmpty && discovery.parseableFiles == 0 then StatusUnparseable
    else if matches.isEmpty then StatusNoMatch
    else if matches.exists(_.parseStatus != Parsed) then StatusPartial
    else if matches.size == 1 then StatusUniqueMatch
    else StatusAmbiguous

  private def statusV2(
    semanticdbFiles: Int,
    parseableFiles: Int,
    matches: List[SemanticdbSourceMatchV2]
  ): String =
    if semanticdbFiles == 0 then StatusUnavailable
    else if matches.isEmpty && parseableFiles == 0 then StatusUnparseable
    else if matches.isEmpty then StatusNoMatch
    else if matches.exists(_.parseStatus != Parsed) then StatusPartial
    else if matches.size == 1 then StatusUniqueMatch
    else StatusAmbiguous

  private def metaInfSuffix(value: String): Option[String] =
    val normalized = normalize(value)
    val marker = "META-INF/semanticdb/"
    val index = normalized.indexOf(marker)
    Option.when(index >= 0)(normalized.substring(index + marker.length).stripSuffix(".semanticdb"))

  private def sourceRootSuffix(value: String): Option[String] =
    val normalized = normalize(value)
    SourceRoots
      .flatMap { root =>
        val atStart = Option.when(normalized.startsWith(root))(0)
        val afterSeparator =
          Option(normalized.indexOf(s"/$root")).filter(_ >= 0).map(_ + 1)
        atStart.orElse(afterSeparator).map(normalized.substring)
      }
      .headOption

  private def normalize(value: String): String =
    value.replace('\\', '/').stripPrefix("./")

  private def matchRank(matchKind: String): Int =
    matchKind match
      case MatchUriExact         => 0
      case MatchMetaInfSuffix    => 1
      case MatchSourceRootSuffix => 2
      case _                     => 3

  private def modifiedTimeMillis(path: Path): Long =
    try Files.getLastModifiedTime(path).toMillis
    catch case _: Exception => 0L
