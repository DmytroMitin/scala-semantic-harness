package semantic.harness.reconciliation

import io.circe.Decoder
import io.circe.Encoder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.zip.ZipException
import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.*
import scala.util.Try
import sbt.internal.inc.Analysis
import sbt.internal.inc.FarmHash
import sbt.internal.inc.FileAnalysisStore
import sbt.internal.inc.HashUtil
import semantic.harness.sbt_runner.SbtInternalSourceLayoutReceipt

enum InternalOutputFreshnessStatusV6:
  case Fresh
  case Stale
  case Unverifiable

object InternalOutputFreshnessStatusV6:
  given Encoder[InternalOutputFreshnessStatusV6] = Encoder.encodeString.contramap(_.toString)
  given Decoder[InternalOutputFreshnessStatusV6] = Decoder.decodeString.emap(value =>
    values.find(_.toString == value).toRight(s"Invalid internal output freshness status v6: $value")
  )

enum InternalOutputFreshnessReasonV6:
  case SourceAndProductContentMatch
  case SourceContentMismatch
  case ProductContentMismatch
  case AnalysisPathUnavailable
  case AnalysisFileMissing
  case UnsupportedAnalysisFormatOrVersion
  case CorruptOrUnreadableAnalysis
  case SourceInventoryIncompleteOrUnbounded
  case ProductInventoryIncompleteOrUnbounded
  case MissingExpectedProduct
  case UnsafeSourceOrProductPath
  case GeneratedOrManagedSourceUnbounded
  case ScalaAxisMismatch
  case DependencyClassDirectoryAbsent
  case DependencyClassDirectoryUnsafe
  case SourceProductRelationsInconsistent

object InternalOutputFreshnessReasonV6:
  given Encoder[InternalOutputFreshnessReasonV6] = Encoder.encodeString.contramap(_.toString)
  given Decoder[InternalOutputFreshnessReasonV6] = Decoder.decodeString.emap(value =>
    values.find(_.toString == value).toRight(s"Invalid internal output freshness reason v6: $value")
  )

final case class InternalOutputFreshnessAssessment(
    status: InternalOutputFreshnessStatusV6,
    reason: InternalOutputFreshnessReasonV6,
    analysisFile: Option[String],
    recordedSourceCount: Option[Int],
    recordedProductCount: Option[Int]
)

private[reconciliation] enum ZincAnalysisReadFailure:
  case AnalysisPathUnavailable
  case UnsupportedFormatOrVersion
  case CorruptOrUnreadable

private[reconciliation] final case class ZincAnalysisSnapshot(
    compilerVersion: String,
    sourceStamps: Map[String, String],
    productStamps: Map[String, String],
    sourceProducts: Map[String, Set[String]]
)

private[reconciliation] trait ZincAnalysisReader:
  def read(path: Path): Either[ZincAnalysisReadFailure, ZincAnalysisSnapshot]

private[reconciliation] object ZincAnalysisReader:
  val default: ZincAnalysisReader = OfficialZincAnalysisReader

private[reconciliation] object InternalOutputFreshnessLimits:
  val MaxInventoryFiles = 50000
  val MaxInventoryEntries = 100000
  val MaxContentFileBytes = 8 * 1024 * 1024
  val MaxContentAggregateBytes = 256L * 1024 * 1024
  val MaxAnalysisFileBytes = 64L * 1024 * 1024
  val MaxAnalysisEntries = 16
  val MaxAnalysisEntryBytes = 128L * 1024 * 1024
  val MaxAnalysisUncompressedBytes = 256L * 1024 * 1024

private object OfficialZincAnalysisReader extends ZincAnalysisReader:
  private val AnalysisEntry = "inc_compile.bin"
  private val ApiEntry = "api_companions.bin"

  override def read(path: Path): Either[ZincAnalysisReadFailure, ZincAnalysisSnapshot] =
    validateContainer(path).flatMap { _ =>
      try
        val contents = FileAnalysisStore.binary(path.toFile).unsafeGet()
        contents.getAnalysis match
          case analysis: Analysis =>
            val sourceEntries = analysis.stamps.sources.iterator.toVector
            val productEntries = analysis.stamps.products.iterator.toVector
            if !sourceEntries.forall(_._2.isInstanceOf[FarmHash]) ||
                !productEntries.forall(_._2.isInstanceOf[FarmHash]) then
              Left(ZincAnalysisReadFailure.UnsupportedFormatOrVersion)
            else
              val sourceStamps = sourceEntries.map { case (ref, stamp) => ref.id -> stamp.writeStamp }.toMap
              val productStamps = productEntries.map { case (ref, stamp) => ref.id -> stamp.writeStamp }.toMap
              val sourceProducts = analysis.relations.allSources.iterator.map { source =>
                source.id -> analysis.relations.products(source).iterator.map(_.id).toSet
              }.toMap
              val bounded = sourceStamps.size <= InternalOutputFreshnessLimits.MaxInventoryFiles &&
                productStamps.size <= InternalOutputFreshnessLimits.MaxInventoryFiles &&
                sourceProducts.size <= InternalOutputFreshnessLimits.MaxInventoryFiles &&
                sourceProducts.valuesIterator.map(_.size.toLong).sum <= InternalOutputFreshnessLimits.MaxInventoryFiles
              Either.cond(
                bounded,
                ZincAnalysisSnapshot(
                  contents.getMiniSetup.compilerVersion,
                  sourceStamps,
                  productStamps,
                  sourceProducts
                ),
                ZincAnalysisReadFailure.UnsupportedFormatOrVersion
              )
          case _ => Left(ZincAnalysisReadFailure.UnsupportedFormatOrVersion)
      catch
        case _: NoSuchElementException => Left(ZincAnalysisReadFailure.UnsupportedFormatOrVersion)
        case _: ZipException => Left(ZincAnalysisReadFailure.CorruptOrUnreadable)
        case _: Exception => Left(ZincAnalysisReadFailure.CorruptOrUnreadable)
    }

  private def validateContainer(path: Path): Either[ZincAnalysisReadFailure, Unit] =
    try
      if Files.size(path) > InternalOutputFreshnessLimits.MaxAnalysisFileBytes then
        Left(ZincAnalysisReadFailure.UnsupportedFormatOrVersion)
      else
        val zip = ZipFile(path.toFile)
        try
          val entries = zip.entries().asScala.toVector
          val sizes = entries.map(_.getSize)
          val bounded = entries.size <= InternalOutputFreshnessLimits.MaxAnalysisEntries &&
            sizes.forall(size => size >= 0 && size <= InternalOutputFreshnessLimits.MaxAnalysisEntryBytes) &&
            sizes.sum <= InternalOutputFreshnessLimits.MaxAnalysisUncompressedBytes
          val names = entries.map(_.getName).toSet
          Either.cond(
            bounded && names.contains(AnalysisEntry) && names.contains(ApiEntry),
            (),
            ZincAnalysisReadFailure.UnsupportedFormatOrVersion
          )
        finally zip.close()
    catch
      case _: ZipException => Left(ZincAnalysisReadFailure.CorruptOrUnreadable)
      case _: Exception => Left(ZincAnalysisReadFailure.CorruptOrUnreadable)

private[reconciliation] object ZincContentStamp:
  def current(path: Path): String = FarmHash.ofPath(path).writeStamp

  def boundedCurrent(path: Path): Either[Unit, (String, Long)] =
    try
      val stream = Files.newInputStream(path)
      val bytes = try stream.readNBytes(InternalOutputFreshnessLimits.MaxContentFileBytes + 1)
      finally stream.close()
      if bytes.length > InternalOutputFreshnessLimits.MaxContentFileBytes then Left(())
      else Right(FarmHash.fromLong(HashUtil.farmHash(bytes)).writeStamp -> bytes.length.toLong)
    catch case _: Exception => Left(())

final class InternalOutputFreshnessAssessor private (reader: ZincAnalysisReader):
  import InternalOutputFreshnessReasonV6.*
  import InternalOutputFreshnessStatusV6.*

  def assess(
      workspace: Path,
      effectiveScalaVersion: String,
      classDirectory: Path,
      analysisFile: Option[Path],
      sourceLayout: Option[SbtInternalSourceLayoutReceipt]
  ): InternalOutputFreshnessAssessment =
    val workspaceReal = safeWorkspace(workspace)
    workspaceReal match
      case None => unverifiable(DependencyClassDirectoryUnsafe, None)
      case Some(base) =>
        safeDirectory(base, classDirectory) match
          case Left(reason) => unverifiable(reason, relativeIfSafe(base, analysisFile))
          case Right(None) => unverifiable(DependencyClassDirectoryAbsent, relativeIfSafe(base, analysisFile))
          case Right(Some(classes)) => assessAnalysis(base, effectiveScalaVersion, classes, analysisFile, sourceLayout)

  private def assessAnalysis(
      workspace: Path,
      effectiveScalaVersion: String,
      classes: Path,
      analysisFile: Option[Path],
      sourceLayout: Option[SbtInternalSourceLayoutReceipt]
  ): InternalOutputFreshnessAssessment = analysisFile match
    case None => unverifiable(AnalysisPathUnavailable, None)
    case Some(candidate) =>
      safeAnalysisPath(workspace, candidate) match
        case Left(reason) => unverifiable(reason, None)
        case Right((analysis, relative)) if !Files.exists(analysis, LinkOption.NOFOLLOW_LINKS) =>
          unverifiable(AnalysisFileMissing, Some(relative))
        case Right((analysis, relative)) if !Files.isRegularFile(analysis, LinkOption.NOFOLLOW_LINKS) =>
          unverifiable(AnalysisPathUnavailable, Some(relative))
        case Right((analysis, relative)) =>
          reader.read(analysis) match
            case Left(ZincAnalysisReadFailure.AnalysisPathUnavailable) =>
              unverifiable(AnalysisPathUnavailable, Some(relative))
            case Left(ZincAnalysisReadFailure.UnsupportedFormatOrVersion) =>
              unverifiable(UnsupportedAnalysisFormatOrVersion, Some(relative))
            case Left(ZincAnalysisReadFailure.CorruptOrUnreadable) =>
              unverifiable(CorruptOrUnreadableAnalysis, Some(relative))
            case Right(snapshot) => assessSnapshot(
                workspace,
                effectiveScalaVersion,
                classes,
                relative,
                snapshot,
                sourceLayout
              )

  private def assessSnapshot(
      workspace: Path,
      effectiveScalaVersion: String,
      classes: Path,
      analysisRelative: String,
      snapshot: ZincAnalysisSnapshot,
      sourceLayout: Option[SbtInternalSourceLayoutReceipt]
  ): InternalOutputFreshnessAssessment =
    val farmHashStamp = "farm\\([0-9a-fA-F]+\\)".r
    if !(snapshot.sourceStamps.valuesIterator ++ snapshot.productStamps.valuesIterator)
        .forall(stamp => farmHashStamp.matches(stamp)) then
      return unverifiable(UnsupportedAnalysisFormatOrVersion, Some(analysisRelative))
    val counts = Some(snapshot.sourceStamps.size) -> Some(snapshot.productStamps.size)
    def failed(reason: InternalOutputFreshnessReasonV6) =
      unverifiable(reason, Some(analysisRelative), counts._1, counts._2)

    val boundedSnapshot = snapshot.sourceStamps.size <= InternalOutputFreshnessLimits.MaxInventoryFiles &&
      snapshot.productStamps.size <= InternalOutputFreshnessLimits.MaxInventoryFiles &&
      snapshot.sourceProducts.size <= InternalOutputFreshnessLimits.MaxInventoryFiles &&
      snapshot.sourceProducts.valuesIterator.map(_.size.toLong).sum <= InternalOutputFreshnessLimits.MaxInventoryFiles
    if !boundedSnapshot then failed(SourceInventoryIncompleteOrUnbounded)
    else if snapshot.compilerVersion != effectiveScalaVersion then failed(ScalaAxisMismatch)
    else if snapshot.sourceStamps.isEmpty || snapshot.productStamps.isEmpty then
      failed(SourceProductRelationsInconsistent)
    else
      val resolvedSources = resolveIdentities(workspace, snapshot.sourceStamps.keySet, None)
      val resolvedProducts = resolveIdentities(workspace, snapshot.productStamps.keySet, Some(classes))
      (resolvedSources, resolvedProducts) match
        case (Left(_), _) | (_, Left(_)) => failed(UnsafeSourceOrProductPath)
        case (Right(sources), Right(products)) =>
          val missingProduct = products.values.exists(path => !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
          if missingProduct then failed(MissingExpectedProduct)
          else if sources.values.exists(path => !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) then
            failed(SourceInventoryIncompleteOrUnbounded)
          else if !relationsConsistent(snapshot, sources.keySet, products.keySet) then
            failed(SourceProductRelationsInconsistent)
          else sourceInventory(workspace, sourceLayout) match
            case Left(reason) => failed(reason)
            case Right(currentSources) if currentSources != sources.values.toSet =>
              failed(SourceInventoryIncompleteOrUnbounded)
            case Right(_) => productInventory(classes) match
              case Left(reason) => failed(reason)
              case Right(currentProducts) if currentProducts != products.values.toSet =>
                failed(ProductInventoryIncompleteOrUnbounded)
              case Right(_) =>
                verifyStamps(snapshot.sourceStamps, sources) match
                  case Left(_) => failed(SourceInventoryIncompleteOrUnbounded)
                  case Right(true) => stale(SourceContentMismatch, analysisRelative, counts)
                  case Right(false) => verifyStamps(snapshot.productStamps, products) match
                    case Left(_) => failed(ProductInventoryIncompleteOrUnbounded)
                    case Right(true) => stale(ProductContentMismatch, analysisRelative, counts)
                    case Right(false) => InternalOutputFreshnessAssessment(
                      Fresh,
                      SourceAndProductContentMatch,
                      Some(analysisRelative),
                      counts._1,
                      counts._2
                    )

  private def relationsConsistent(
      snapshot: ZincAnalysisSnapshot,
      sources: Set[String],
      products: Set[String]
  ): Boolean =
    snapshot.sourceProducts.keySet == sources &&
      snapshot.sourceProducts.values.forall(_.nonEmpty) &&
      snapshot.sourceProducts.values.flatten.toSet == products

  private def sourceInventory(
      workspace: Path,
      layout: Option[SbtInternalSourceLayoutReceipt]
  ): Either[InternalOutputFreshnessReasonV6, Set[Path]] = layout match
    case None => Left(GeneratedOrManagedSourceUnbounded)
    case Some(value) if value.sourceGeneratorCount != 0 => Left(GeneratedOrManagedSourceUnbounded)
    case Some(value) =>
      val all = safeSourceRoots(workspace, value.sourceDirectories)
      val unmanaged = safeSourceRoots(workspace, value.unmanagedSourceDirectories)
      val managed = safeSourceRoots(workspace, value.managedSourceDirectories)
      (all, unmanaged, managed) match
        case (Right(allRoots), Right(unmanagedRoots), Right(managedRoots))
            if allRoots == unmanagedRoots ++ managedRoots =>
          inventory(managedRoots, sourceFile = true, workspace) match
            case Right(files) if files.nonEmpty => Left(GeneratedOrManagedSourceUnbounded)
            case Left(_) => Left(GeneratedOrManagedSourceUnbounded)
            case Right(_) => inventory(unmanagedRoots, sourceFile = true, workspace)
              .left.map(_ => SourceInventoryIncompleteOrUnbounded)
        case _ => Left(GeneratedOrManagedSourceUnbounded)

  private def safeSourceRoots(
      workspace: Path,
      values: List[Path]
  ): Either[Unit, Set[Path]] =
    values.foldLeft[Either[Unit, Set[Path]]](Right(Set.empty)) { (result, candidate) =>
      result.flatMap { roots =>
        try
          val normalized = candidate.toAbsolutePath.normalize()
          if !normalized.startsWith(workspace) || containsSymlink(workspace, normalized) then Left(())
          else Right(roots + normalized)
        catch case _: Exception => Left(())
      }
    }

  private def verifyStamps(
      stamps: Map[String, String],
      paths: Map[String, Path]
  ): Either[Unit, Boolean] =
    stamps.toList.sortBy(_._1).foldLeft[Either[Unit, (Boolean, Long)]](Right(false -> 0L)) {
      case (result, (id, recorded)) => result.flatMap { case (mismatch, total) =>
        ZincContentStamp.boundedCurrent(paths(id)).flatMap { case (current, bytes) =>
          val next = total + bytes
          Either.cond(
            next <= InternalOutputFreshnessLimits.MaxContentAggregateBytes,
            (mismatch || current != recorded) -> next,
            ()
          )
        }
      }
    }.map(_._1)

  private def productInventory(
      classes: Path
  ): Either[InternalOutputFreshnessReasonV6, Set[Path]] =
    inventory(Set(classes), sourceFile = false, classes).left.map(_ => ProductInventoryIncompleteOrUnbounded)

  private def inventory(
      roots: Set[Path],
      sourceFile: Boolean,
    safetyBase: Path
  ): Either[Unit, Set[Path]] =
    try
      val values = roots.foldLeft((Set.empty[Path], 0)) { case ((all, visitedBefore), root) =>
        if !Files.exists(root, LinkOption.NOFOLLOW_LINKS) then all -> visitedBefore
        else if !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) || containsSymlink(safetyBase, root) then
          throw IllegalStateException("unsafe inventory root")
        else
          val stream = Files.walk(root)
          try
            stream.iterator().asScala.foldLeft((all, visitedBefore)) { case ((found, visited), path) =>
            if visited >= InternalOutputFreshnessLimits.MaxInventoryEntries ||
                found.size >= InternalOutputFreshnessLimits.MaxInventoryFiles then
              throw IllegalStateException("inventory bound exceeded")
            if Files.isSymbolicLink(path) then throw IllegalStateException("symbolic link in inventory")
            else if Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) &&
                (!sourceFile || path.getFileName.toString.endsWith(".scala") ||
                  path.getFileName.toString.endsWith(".java")) then (found + path.toRealPath()) -> (visited + 1)
            else found -> (visited + 1)
            }
          finally stream.close()
      }
      Right(values._1)
    catch case _: Exception => Left(())

  private def resolveIdentities(
      workspace: Path,
      identities: Set[String],
      requiredRoot: Option[Path]
  ): Either[Unit, Map[String, Path]] =
    identities.foldLeft[Either[Unit, Map[String, Path]]](Right(Map.empty)) { (result, id) =>
      result.flatMap { values =>
        resolveIdentity(workspace, id, requiredRoot).map(path => values.updated(id, path))
      }
    }

  private def resolveIdentity(
      workspace: Path,
      identity: String,
      requiredRoot: Option[Path]
  ): Either[Unit, Path] =
    try
      val raw =
        if identity.startsWith("${BASE}/") then workspace.resolve(identity.stripPrefix("${BASE}/"))
        else
          val path = Path.of(identity)
          if path.isAbsolute then path else throw IllegalArgumentException("unsupported Zinc identity")
      val normalized = raw.toAbsolutePath.normalize()
      if !normalized.startsWith(workspace) || requiredRoot.exists(root => !normalized.startsWith(root)) ||
          containsSymlink(workspace, normalized) then Left(())
      else Right(if Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) then normalized.toRealPath() else normalized)
    catch case _: Exception => Left(())

  private def safeWorkspace(workspace: Path): Option[Path] =
    Try(workspace.toRealPath()).toOption.filter(path =>
      Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path)
    )

  private def safeDirectory(
      workspace: Path,
      candidate: Path
  ): Either[InternalOutputFreshnessReasonV6, Option[Path]] =
    try
      val normalized = candidate.toAbsolutePath.normalize()
      if !normalized.startsWith(workspace) || containsSymlink(workspace, normalized) then
        Left(DependencyClassDirectoryUnsafe)
      else if !Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) then Right(None)
      else if !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) then Left(DependencyClassDirectoryUnsafe)
      else Right(Some(normalized.toRealPath()))
    catch case _: Exception => Left(DependencyClassDirectoryUnsafe)

  private def safeAnalysisPath(
      workspace: Path,
      candidate: Path
  ): Either[InternalOutputFreshnessReasonV6, (Path, String)] =
    try
      val normalized = candidate.toAbsolutePath.normalize()
      if !normalized.startsWith(workspace) || containsSymlink(workspace, normalized) then
        Left(AnalysisPathUnavailable)
      else
        val publicRelative = relative(workspace, normalized)
        if publicRelative.contains('\\') then Left(AnalysisPathUnavailable)
        else Right(normalized -> publicRelative)
    catch case _: Exception => Left(AnalysisPathUnavailable)

  private def relativeIfSafe(workspace: Path, value: Option[Path]): Option[String] =
    value.flatMap(path => safeAnalysisPath(workspace, path).toOption.map(_._2))

  private def containsSymlink(base: Path, target: Path): Boolean =
    if !target.startsWith(base) then true
    else
      base.relativize(target).iterator().asScala.foldLeft((base, false)) {
        case ((current, found), component) =>
          val next = current.resolve(component)
          next -> (found || Files.isSymbolicLink(next))
      }._2

  private def relative(workspace: Path, path: Path): String =
    workspace.relativize(path).toString.replace(java.io.File.separatorChar, '/')

  private def stale(
      reason: InternalOutputFreshnessReasonV6,
      analysisFile: String,
      counts: (Option[Int], Option[Int])
  ): InternalOutputFreshnessAssessment = InternalOutputFreshnessAssessment(
    Stale,
    reason,
    Some(analysisFile),
    counts._1,
    counts._2
  )

  private def unverifiable(
      reason: InternalOutputFreshnessReasonV6,
      analysisFile: Option[String],
      sourceCount: Option[Int] = None,
      productCount: Option[Int] = None
  ): InternalOutputFreshnessAssessment = InternalOutputFreshnessAssessment(
    Unverifiable,
    reason,
    analysisFile,
    sourceCount,
    productCount
  )

object InternalOutputFreshnessAssessor:
  val default: InternalOutputFreshnessAssessor = withReader(ZincAnalysisReader.default)

  private[reconciliation] def withReader(
      reader: ZincAnalysisReader
  ): InternalOutputFreshnessAssessor = new InternalOutputFreshnessAssessor(reader)
