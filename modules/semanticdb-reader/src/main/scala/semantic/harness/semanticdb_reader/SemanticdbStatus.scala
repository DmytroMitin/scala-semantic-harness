package semantic.harness.semanticdb_reader

import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.util.control.NonFatal

object SemanticdbStatus:
  val StatusAvailable = "Available"
  val StatusPartial = "Partial"
  val StatusUnavailable = "Unavailable"
  val StatusUnparseable = "Unparseable"

  private val Parsed = "Parsed"
  private val Unparseable = "Unparseable"
  private val SourceRootSuffixes =
    List("src/main/scala", "src/test/scala", "src/main/java", "src/test/java")

  def inspect(workspace: Path): Either[String, SemanticdbStatusReport] =
    discoverFiles(workspace).map { case (normalizedWorkspace, files) =>
      val candidates = files.map(candidate(normalizedWorkspace, _))
      val parseable = candidates.count(_.parseStatus == Parsed)
      val unparseable = candidates.count(_.parseStatus == Unparseable)
      SemanticdbStatusReport(
        workspace = normalizedWorkspace.toString,
        status = status(candidates.size, parseable, unparseable),
        semanticdbFiles = candidates.size,
        parseableFiles = parseable,
        unparseableFiles = unparseable,
        sourceRoots = sourceRoots(candidates),
        candidates = candidates,
        errors = Nil
      )
    }

  private[semanticdb_reader] def discoverFiles(workspace: Path): Either[String, (Path, List[Path])] =
    val normalizedWorkspace = workspace.toAbsolutePath.normalize()
    if !Files.exists(normalizedWorkspace) then Left(s"Workspace does not exist: $normalizedWorkspace")
    else if !Files.isDirectory(normalizedWorkspace) then Left(s"Workspace is not a directory: $normalizedWorkspace")
    else
      try
        val stream = Files.walk(normalizedWorkspace)
        try
          val files = stream
            .iterator()
            .asScala
            .filter(path => Files.isRegularFile(path) && path.getFileName != null && path.getFileName.toString.endsWith(".semanticdb"))
            .toList
            .sortBy(path => relativePath(normalizedWorkspace, path))
          Right((normalizedWorkspace, files))
        finally stream.close()
      catch
        case NonFatal(error) =>
          Left(s"Unable to scan workspace for SemanticDB files: ${bounded(error.getMessage)}")

  private def candidate(workspace: Path, path: Path): SemanticdbStatusCandidate =
    val relative = relativePath(workspace, path)
    val mtime = modifiedTimeMillis(path)
    SemanticdbReader.read(path) match
      case Right(summary) =>
        SemanticdbStatusCandidate(
          semanticdb = relative,
          uri = Some(summary.uri),
          parseStatus = Parsed,
          symbols = Some(summary.symbols.size),
          occurrences = Some(summary.occurrences.size),
          mtimeMillis = mtime,
          error = None
        )
      case Left(message) =>
        SemanticdbStatusCandidate(
          semanticdb = relative,
          uri = None,
          parseStatus = Unparseable,
          symbols = None,
          occurrences = None,
          mtimeMillis = mtime,
          error = Some(bounded(message))
        )

  private[semanticdb_reader] def status(total: Int, parseable: Int, unparseable: Int): String =
    if total == 0 then StatusUnavailable
    else if parseable > 0 && unparseable > 0 then StatusPartial
    else if parseable > 0 then StatusAvailable
    else StatusUnparseable

  private def sourceRoots(candidates: List[SemanticdbStatusCandidate]): List[String] =
    candidates
      .flatMap { candidate =>
        candidate.uri.toList.flatMap(sourceRootsIn) ++ sourceRootsIn(candidate.semanticdb)
      }
      .distinct
      .sorted

  private def sourceRootsIn(value: String): List[String] =
    val normalized = value.replace('\\', '/')
    SourceRootSuffixes.filter(root => normalized.contains(root))

  private[semanticdb_reader] def relativePath(workspace: Path, path: Path): String =
    workspace.relativize(path.toAbsolutePath.normalize()).toString.replace('\\', '/')

  private def modifiedTimeMillis(path: Path): Long =
    try Files.getLastModifiedTime(path).toMillis
    catch case NonFatal(_) => 0L

  private[semanticdb_reader] def bounded(message: String): String =
    Option(message)
      .getOrElse("")
      .replaceAll("\\s+", " ")
      .trim
      .take(240)
