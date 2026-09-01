package semantic.harness.reconciliation.worker

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path}
import java.util.Base64
import java.util.zip.{ZipException, ZipFile}
import scala.collection.JavaConverters._
import scala.util.Try
import sbt.internal.inc.{Analysis, FarmHash, FileAnalysisStore, HashUtil}

private[worker] final case class WorkerInput(
    callerId: String,
    workspace: Path,
    effectiveScalaVersion: String,
    classDirectory: Path,
    analysisFile: Path,
    sourceGeneratorCount: Int,
    sourceDirectories: Set[Path],
    unmanagedSourceDirectories: Set[Path],
    managedSourceDirectories: Set[Path]
)

private[worker] final case class WorkerResult(
    callerId: String,
    status: String,
    reason: String,
    sourceCount: Option[Int],
    productCount: Option[Int]
)

private[worker] final case class AnalysisSnapshot(
    compilerVersion: String,
    sourceStamps: Map[String, String],
    productStamps: Map[String, String],
    sourceProducts: Map[String, Set[String]]
)

private[worker] object WorkerLimits {
  val MaxProtocolBytes = 1024 * 1024
  val MaxFieldBytes = 4 * 1024
  val MaxInputs = 128
  val MaxInventoryFiles = 50000
  val MaxInventoryEntries = 100000
  val MaxContentFileBytes = 8 * 1024 * 1024
  val MaxContentAggregateBytes = 256L * 1024 * 1024
  val MaxAnalysisFileBytes = 64L * 1024 * 1024
  val MaxAnalysisEntries = 16
  val MaxAnalysisEntryBytes = 128L * 1024 * 1024
  val MaxAnalysisUncompressedBytes = 256L * 1024 * 1024
}

private[worker] object ContentStamp {
  def current(path: Path): String = FarmHash.ofPath(path).writeStamp

  def boundedCurrent(path: Path): Either[Unit, (String, Long)] =
    try {
      val stream = Files.newInputStream(path)
      val bytes = try stream.readNBytes(WorkerLimits.MaxContentFileBytes + 1)
      finally stream.close()
      if (bytes.length > WorkerLimits.MaxContentFileBytes) Left(())
      else Right(FarmHash.fromLong(HashUtil.farmHash(bytes)).writeStamp -> bytes.length.toLong)
    } catch { case _: Exception => Left(()) }
}

private[worker] object OfficialAnalysisReader {
  private val AnalysisEntry = "inc_compile.bin"
  private val ApiEntry = "api_companions.bin"

  def read(path: Path): Either[String, AnalysisSnapshot] =
    validateContainer(path).flatMap { _ =>
      try {
        val contents = FileAnalysisStore.binary(path.toFile).unsafeGet()
        contents.getAnalysis match {
          case analysis: Analysis =>
            val sourceEntries = analysis.stamps.sources.iterator.toVector
            val productEntries = analysis.stamps.products.iterator.toVector
            if (!sourceEntries.forall(_._2.isInstanceOf[FarmHash]) ||
                !productEntries.forall(_._2.isInstanceOf[FarmHash]))
              Left("UnsupportedAnalysisFormatOrVersion")
            else {
              val sourceStamps = sourceEntries.map { case (ref, stamp) => ref.id -> stamp.writeStamp }.toMap
              val productStamps = productEntries.map { case (ref, stamp) => ref.id -> stamp.writeStamp }.toMap
              val sourceProducts = analysis.relations.allSources.iterator.map { source =>
                source.id -> analysis.relations.products(source).iterator.map(_.id).toSet
              }.toMap
              val bounded = sourceStamps.size <= WorkerLimits.MaxInventoryFiles &&
                productStamps.size <= WorkerLimits.MaxInventoryFiles &&
                sourceProducts.size <= WorkerLimits.MaxInventoryFiles &&
                sourceProducts.valuesIterator.map(_.size.toLong).sum <= WorkerLimits.MaxInventoryFiles
              if (bounded) Right(AnalysisSnapshot(
                contents.getMiniSetup.compilerVersion,
                sourceStamps,
                productStamps,
                sourceProducts
              )) else Left("UnsupportedAnalysisFormatOrVersion")
            }
          case _ => Left("UnsupportedAnalysisFormatOrVersion")
        }
      } catch {
        case _: NoSuchElementException => Left("UnsupportedAnalysisFormatOrVersion")
        case _: ZipException => Left("CorruptOrUnreadableAnalysis")
        case _: Exception => Left("CorruptOrUnreadableAnalysis")
      }
    }

  private def validateContainer(path: Path): Either[String, Unit] =
    try {
      if (Files.size(path) > WorkerLimits.MaxAnalysisFileBytes)
        Left("UnsupportedAnalysisFormatOrVersion")
      else {
        val zip = new ZipFile(path.toFile)
        try {
          val entries = zip.entries().asScala.toVector
          val sizes = entries.map(_.getSize)
          val bounded = entries.size <= WorkerLimits.MaxAnalysisEntries &&
            sizes.forall(size => size >= 0 && size <= WorkerLimits.MaxAnalysisEntryBytes) &&
            sizes.sum <= WorkerLimits.MaxAnalysisUncompressedBytes
          val names = entries.map(_.getName).toSet
          if (bounded && names.contains(AnalysisEntry) && names.contains(ApiEntry)) Right(())
          else Left("UnsupportedAnalysisFormatOrVersion")
        } finally zip.close()
      }
    } catch {
      case _: ZipException => Left("CorruptOrUnreadableAnalysis")
      case _: Exception => Left("CorruptOrUnreadableAnalysis")
    }
}

private[worker] final class WorkerEngine private (
    reader: Path => Either[String, AnalysisSnapshot]
) {
  def assess(input: WorkerInput): WorkerResult = {
    val workspace = safeWorkspace(input.workspace)
    workspace match {
      case None => unavailable(input, "DependencyClassDirectoryUnsafe")
      case Some(base) => safeDirectory(base, input.classDirectory) match {
        case Left(reason) => unavailable(input, reason)
        case Right(None) => unavailable(input, "DependencyClassDirectoryAbsent")
        case Right(Some(classes)) => assessAnalysis(base, classes, input)
      }
    }
  }

  private def assessAnalysis(workspace: Path, classes: Path, input: WorkerInput): WorkerResult =
    safeAnalysisPath(workspace, input.analysisFile) match {
      case Left(reason) => unavailable(input, reason)
      case Right(analysis) if !Files.exists(analysis, LinkOption.NOFOLLOW_LINKS) =>
        unavailable(input, "AnalysisFileMissing")
      case Right(analysis) if !Files.isRegularFile(analysis, LinkOption.NOFOLLOW_LINKS) =>
        unavailable(input, "AnalysisPathUnavailable")
      case Right(analysis) => reader(analysis) match {
        case Left(reason) => unavailable(input, reason)
        case Right(snapshot) => assessSnapshot(workspace, classes, input, snapshot)
      }
    }

  private def assessSnapshot(
      workspace: Path,
      classes: Path,
      input: WorkerInput,
      snapshot: AnalysisSnapshot
  ): WorkerResult = {
    val farmHashStamp = "farm\\([0-9a-fA-F]+\\)".r
    val counts = Some(snapshot.sourceStamps.size) -> Some(snapshot.productStamps.size)
    def failed(reason: String) = WorkerResult(input.callerId, "Unverifiable", reason, counts._1, counts._2)
    val stampsSupported = (snapshot.sourceStamps.valuesIterator ++ snapshot.productStamps.valuesIterator)
      .forall(stamp => farmHashStamp.pattern.matcher(stamp).matches())
    val boundedSnapshot = snapshot.sourceStamps.size <= WorkerLimits.MaxInventoryFiles &&
      snapshot.productStamps.size <= WorkerLimits.MaxInventoryFiles &&
      snapshot.sourceProducts.size <= WorkerLimits.MaxInventoryFiles &&
      snapshot.sourceProducts.valuesIterator.map(_.size.toLong).sum <= WorkerLimits.MaxInventoryFiles
    if (!stampsSupported) failed("UnsupportedAnalysisFormatOrVersion")
    else if (!boundedSnapshot) failed("SourceInventoryIncompleteOrUnbounded")
    else if (snapshot.compilerVersion != input.effectiveScalaVersion) failed("ScalaAxisMismatch")
    else if (snapshot.sourceStamps.isEmpty || snapshot.productStamps.isEmpty)
      failed("SourceProductRelationsInconsistent")
    else {
      val resolvedSources = resolveIdentities(workspace, snapshot.sourceStamps.keySet, None)
      val resolvedProducts = resolveIdentities(workspace, snapshot.productStamps.keySet, Some(classes))
      (resolvedSources, resolvedProducts) match {
        case (Left(_), _) | (_, Left(_)) => failed("UnsafeSourceOrProductPath")
        case (Right(sources), Right(products)) =>
          if (products.values.exists(path => !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)))
            failed("MissingExpectedProduct")
          else if (sources.values.exists(path => !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)))
            failed("SourceInventoryIncompleteOrUnbounded")
          else if (!relationsConsistent(snapshot, sources.keySet, products.keySet))
            failed("SourceProductRelationsInconsistent")
          else sourceInventory(workspace, input) match {
            case Left(reason) => failed(reason)
            case Right(currentSources) if currentSources != sources.values.toSet =>
              failed("SourceInventoryIncompleteOrUnbounded")
            case Right(_) => productInventory(classes) match {
              case Left(reason) => failed(reason)
              case Right(currentProducts) if currentProducts != products.values.toSet =>
                failed("ProductInventoryIncompleteOrUnbounded")
              case Right(_) => verifyStamps(snapshot.sourceStamps, sources) match {
                case Left(_) => failed("SourceInventoryIncompleteOrUnbounded")
                case Right(true) => WorkerResult(input.callerId, "Stale", "SourceContentMismatch", counts._1, counts._2)
                case Right(false) => verifyStamps(snapshot.productStamps, products) match {
                  case Left(_) => failed("ProductInventoryIncompleteOrUnbounded")
                  case Right(true) => WorkerResult(input.callerId, "Stale", "ProductContentMismatch", counts._1, counts._2)
                  case Right(false) => WorkerResult(
                    input.callerId, "Fresh", "SourceAndProductContentMatch", counts._1, counts._2
                  )
                }
              }
            }
          }
      }
    }
  }

  private def relationsConsistent(
      snapshot: AnalysisSnapshot,
      sources: Set[String],
      products: Set[String]
  ): Boolean = snapshot.sourceProducts.keySet == sources &&
    snapshot.sourceProducts.values.forall(_.nonEmpty) &&
    snapshot.sourceProducts.values.flatten.toSet == products

  private def sourceInventory(workspace: Path, input: WorkerInput): Either[String, Set[Path]] = {
    if (input.sourceGeneratorCount != 0) Left("GeneratedOrManagedSourceUnbounded")
    else {
      val all = safeSourceRoots(workspace, input.sourceDirectories)
      val unmanaged = safeSourceRoots(workspace, input.unmanagedSourceDirectories)
      val managed = safeSourceRoots(workspace, input.managedSourceDirectories)
      (all, unmanaged, managed) match {
        case (Right(allRoots), Right(unmanagedRoots), Right(managedRoots))
            if allRoots == unmanagedRoots ++ managedRoots =>
          inventory(managedRoots, sourceFile = true, workspace) match {
            case Right(files) if files.nonEmpty => Left("GeneratedOrManagedSourceUnbounded")
            case Left(_) => Left("GeneratedOrManagedSourceUnbounded")
            case Right(_) => inventory(unmanagedRoots, sourceFile = true, workspace)
              .left.map(_ => "SourceInventoryIncompleteOrUnbounded")
          }
        case _ => Left("GeneratedOrManagedSourceUnbounded")
      }
    }
  }

  private def safeSourceRoots(workspace: Path, values: Set[Path]): Either[Unit, Set[Path]] =
    values.foldLeft[Either[Unit, Set[Path]]](Right(Set.empty)) { (result, candidate) =>
      result.flatMap { roots =>
        try {
          val normalized = candidate.toAbsolutePath.normalize()
          if (!normalized.startsWith(workspace) || containsSymlink(workspace, normalized)) Left(())
          else Right(roots + normalized)
        } catch { case _: Exception => Left(()) }
      }
    }

  private def verifyStamps(
      stamps: Map[String, String],
      paths: Map[String, Path]
  ): Either[Unit, Boolean] =
    stamps.toList.sortBy(_._1).foldLeft[Either[Unit, (Boolean, Long)]](Right(false -> 0L)) {
      case (result, (id, recorded)) => result.flatMap { case (mismatch, total) =>
        ContentStamp.boundedCurrent(paths(id)).flatMap { case (current, bytes) =>
          val next = total + bytes
          if (next <= WorkerLimits.MaxContentAggregateBytes) Right((mismatch || current != recorded) -> next)
          else Left(())
        }
      }
    }.map(_._1)

  private def productInventory(classes: Path): Either[String, Set[Path]] =
    inventory(Set(classes), sourceFile = false, classes)
      .left.map(_ => "ProductInventoryIncompleteOrUnbounded")

  private def inventory(roots: Set[Path], sourceFile: Boolean, safetyBase: Path): Either[Unit, Set[Path]] =
    try {
      val values = roots.foldLeft((Set.empty[Path], 0)) { case ((all, visitedBefore), root) =>
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) all -> visitedBefore
        else if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || containsSymlink(safetyBase, root))
          throw new IllegalStateException("unsafe inventory root")
        else {
          val stream = Files.walk(root)
          try stream.iterator().asScala.foldLeft((all, visitedBefore)) { case ((found, visited), path) =>
            if (visited >= WorkerLimits.MaxInventoryEntries || found.size >= WorkerLimits.MaxInventoryFiles)
              throw new IllegalStateException("inventory bound exceeded")
            if (Files.isSymbolicLink(path)) throw new IllegalStateException("symbolic link in inventory")
            else if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                (!sourceFile || path.getFileName.toString.endsWith(".scala") ||
                  path.getFileName.toString.endsWith(".java")))
              (found + path.toRealPath()) -> (visited + 1)
            else found -> (visited + 1)
          } finally stream.close()
        }
      }
      Right(values._1)
    } catch { case _: Exception => Left(()) }

  private def resolveIdentities(
      workspace: Path,
      identities: Set[String],
      requiredRoot: Option[Path]
  ): Either[Unit, Map[String, Path]] =
    identities.foldLeft[Either[Unit, Map[String, Path]]](Right(Map.empty)) { (result, id) =>
      result.flatMap(values => resolveIdentity(workspace, id, requiredRoot).map(path => values.updated(id, path)))
    }

  private def resolveIdentity(
      workspace: Path,
      identity: String,
      requiredRoot: Option[Path]
  ): Either[Unit, Path] =
    try {
      val raw = if (identity.startsWith("${BASE}/")) workspace.resolve(identity.stripPrefix("${BASE}/"))
      else {
        val path = Path.of(identity)
        if (path.isAbsolute) path else throw new IllegalArgumentException("unsupported Zinc identity")
      }
      val normalized = raw.toAbsolutePath.normalize()
      if (!normalized.startsWith(workspace) || requiredRoot.exists(root => !normalized.startsWith(root)) ||
          containsSymlink(workspace, normalized)) Left(())
      else Right(if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) normalized.toRealPath() else normalized)
    } catch { case _: Exception => Left(()) }

  private def safeWorkspace(workspace: Path): Option[Path] =
    Try(workspace.toRealPath()).toOption.filter(path =>
      Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)
    )

  private def safeDirectory(workspace: Path, candidate: Path): Either[String, Option[Path]] =
    try {
      val normalized = candidate.toAbsolutePath.normalize()
      if (!normalized.startsWith(workspace) || containsSymlink(workspace, normalized))
        Left("DependencyClassDirectoryUnsafe")
      else if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) Right(None)
      else if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) Left("DependencyClassDirectoryUnsafe")
      else Right(Some(normalized.toRealPath()))
    } catch { case _: Exception => Left("DependencyClassDirectoryUnsafe") }

  private def safeAnalysisPath(workspace: Path, candidate: Path): Either[String, Path] =
    try {
      val normalized = candidate.toAbsolutePath.normalize()
      if (!normalized.startsWith(workspace) || containsSymlink(workspace, normalized))
        Left("AnalysisPathUnavailable")
      else Right(normalized)
    } catch { case _: Exception => Left("AnalysisPathUnavailable") }

  private def containsSymlink(base: Path, target: Path): Boolean =
    if (!target.startsWith(base)) true
    else base.relativize(target).iterator().asScala.foldLeft((base, false)) {
      case ((current, found), component) =>
        val next = current.resolve(component)
        next -> (found || Files.isSymbolicLink(next))
    }._2

  private def unavailable(input: WorkerInput, reason: String): WorkerResult =
    WorkerResult(input.callerId, "Unverifiable", reason, None, None)
}

private[worker] object WorkerEngine {
  val default: WorkerEngine = withReader(OfficialAnalysisReader.read)
  def withReader(reader: Path => Either[String, AnalysisSnapshot]): WorkerEngine = new WorkerEngine(reader)
}

private[worker] object WorkerProtocol {
  val RequestMarker = "semantic-scala-zinc-freshness-worker.v1.request"
  val ResponseMarker = "semantic-scala-zinc-freshness-worker.v1.response"

  def decode(bytes: Array[Byte]): Either[String, List[WorkerInput]] = {
    if (bytes.length > WorkerLimits.MaxProtocolBytes) Left("request too large")
    else {
      val lines = new String(bytes, StandardCharsets.UTF_8).linesIterator.toList
      if (lines.headOption != Some(RequestMarker)) Left("protocol mismatch")
      else {
        val itemLines = lines.tail.filter(_.startsWith("item\t"))
        val sourceLines = lines.tail.filter(_.startsWith("source\t"))
        if (itemLines.size > WorkerLimits.MaxInputs || itemLines.isEmpty ||
            itemLines.size + sourceLines.size != lines.tail.size) Left("invalid request records")
        else {
          val items = itemLines.map(parseItem)
          if (items.exists(_.isLeft)) Left("invalid item")
          else {
            val bases = items.collect { case Right(value) => value }
            if (bases.map(_.callerId).distinct.size != bases.size) Left("duplicate caller ID")
            else sourceLines.foldLeft[Either[String, List[WorkerInput]]](Right(bases)) { (result, line) =>
              result.flatMap(values => addSource(values, line))
            }
          }
        }
      }
    }
  }

  def encode(results: List[WorkerResult]): Array[Byte] = {
    val lines = ResponseMarker :: results.map { result =>
      List(
        "result",
        result.callerId,
        result.status,
        result.reason,
        result.sourceCount.fold("-")(_.toString),
        result.productCount.fold("-")(_.toString)
      ).mkString("\t")
    }
    (lines.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8)
  }

  private def parseItem(line: String): Either[String, WorkerInput] = {
    val fields = line.split("\t", -1).toList
    if (fields.exists(_.getBytes(StandardCharsets.UTF_8).length > WorkerLimits.MaxFieldBytes)) Left("field too large")
    else fields match {
      case "item" :: id :: workspace :: scalaVersion :: classes :: analysis :: generators :: Nil =>
        for {
          base <- path(workspace)
          classDirectory <- path(classes)
          analysisFile <- path(analysis)
          count <- generators.toIntOption.filter(value => value >= 0 && value <= WorkerLimits.MaxInventoryFiles)
            .toRight("invalid generator count")
          _ <- Either.cond(validId(id) && scalaVersion.nonEmpty, (), "invalid identity")
        } yield WorkerInput(id, base, scalaVersion, classDirectory, analysisFile, count, Set.empty, Set.empty, Set.empty)
      case _ => Left("malformed item")
    }
  }

  private def addSource(values: List[WorkerInput], line: String): Either[String, List[WorkerInput]] = {
    val fields = line.split("\t", -1).toList
    if (fields.exists(_.getBytes(StandardCharsets.UTF_8).length > WorkerLimits.MaxFieldBytes)) Left("field too large")
    else fields match {
      case "source" :: id :: kind :: encoded :: Nil =>
        for {
          root <- path(encoded)
          index = values.indexWhere(_.callerId == id)
          _ <- Either.cond(index >= 0, (), "unknown caller ID")
          current = values(index)
          updated <- kind match {
            case "all" => Right(current.copy(sourceDirectories = current.sourceDirectories + root))
            case "unmanaged" => Right(current.copy(unmanagedSourceDirectories = current.unmanagedSourceDirectories + root))
            case "managed" => Right(current.copy(managedSourceDirectories = current.managedSourceDirectories + root))
            case _ => Left("unknown source root kind")
          }
        } yield values.updated(index, updated)
      case _ => Left("malformed source record")
    }
  }

  private def path(value: String): Either[String, Path] = Try {
    val decoded = Base64.getUrlDecoder.decode(value)
    if (decoded.length > WorkerLimits.MaxFieldBytes) throw new IllegalArgumentException("decoded field too large")
    Path.of(new String(decoded, StandardCharsets.UTF_8))
  }.toEither.left.map(_ => "invalid encoded path")

  private def validId(value: String): Boolean =
    value.nonEmpty && value.length <= 256 && value.forall(ch => ch.isLetterOrDigit || "._-/".contains(ch))
}

object ZincFreshnessWorkerMain {
  def main(args: Array[String]): Unit = {
    if (args.nonEmpty) System.exit(2)
    val bytes = System.in.readNBytes(WorkerLimits.MaxProtocolBytes + 1)
    WorkerProtocol.decode(bytes) match {
      case Left(_) => System.exit(2)
      case Right(inputs) =>
        val output = WorkerProtocol.encode(inputs.map(WorkerEngine.default.assess))
        if (output.length > WorkerLimits.MaxProtocolBytes) System.exit(2)
        System.out.write(output)
        System.out.flush()
    }
  }
}
