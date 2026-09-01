package semantic.harness.reconciliation

import io.circe.Decoder
import io.circe.Encoder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.util.Try
import scala.util.control.NonFatal
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

private[reconciliation] final case class InternalOutputFreshnessRequest(
    callerId: String,
    workspace: Path,
    effectiveScalaVersion: String,
    classDirectory: Path,
    analysisFile: Option[Path],
    sourceLayout: Option[SbtInternalSourceLayoutReceipt]
)

final class InternalOutputFreshnessAssessor private (runtime: ZincFreshnessWorkerRuntime):
  import InternalOutputFreshnessReasonV6.*
  import InternalOutputFreshnessStatusV6.*

  def assess(
      workspace: Path,
      effectiveScalaVersion: String,
      classDirectory: Path,
      analysisFile: Option[Path],
      sourceLayout: Option[SbtInternalSourceLayoutReceipt]
  ): InternalOutputFreshnessAssessment =
    assessBatch(List(InternalOutputFreshnessRequest(
      "single",
      workspace,
      effectiveScalaVersion,
      classDirectory,
      analysisFile,
      sourceLayout
    )))("single")

  private[reconciliation] def assessBatch(
      requests: List[InternalOutputFreshnessRequest]
  ): Map[String, InternalOutputFreshnessAssessment] =
    if requests.isEmpty then Map.empty
    else if requests.size > ZincFreshnessWorkerProtocol.MaxInputs ||
        requests.map(_.callerId).distinct.size != requests.size then
      requests.map(request => request.callerId -> workerUnavailable(None)).toMap
    else
      val prepared = requests.map(request => request.callerId -> prepare(request)).toMap
      val eligible = prepared.values.collect { case Right(input) => input }.toList.sortBy(_.callerId)
      val workerResults =
        if eligible.isEmpty then Right(Map.empty[String, InternalOutputFreshnessAssessment])
        else
          try runtime.assess(eligible).flatMap(values => Either.cond(
            values.keySet == eligible.map(_.callerId).toSet,
            values,
            "partial worker result"
          ))
          catch case NonFatal(_) => Left("worker runtime failed")
      requests.map { request =>
        val assessment = prepared(request.callerId) match
          case Left(closed) => closed
          case Right(input) =>
            val relative = relativeIfSafe(request.workspace, request.analysisFile)
            workerResults.toOption.flatMap(_.get(input.callerId)) match
              case Some(value) => value.copy(analysisFile = relative)
              case None => workerUnavailable(relative)
        request.callerId -> assessment
      }.toMap

  private def prepare(
      request: InternalOutputFreshnessRequest
  ): Either[InternalOutputFreshnessAssessment, ZincFreshnessWorkerInput] =
    safeWorkspace(request.workspace) match
      case None => Left(unverifiable(DependencyClassDirectoryUnsafe, None))
      case Some(workspace) => safeDirectory(workspace, request.classDirectory) match
        case Left(reason) => Left(unverifiable(reason, relativeIfSafe(workspace, request.analysisFile)))
        case Right(None) => Left(unverifiable(
          DependencyClassDirectoryAbsent,
          relativeIfSafe(workspace, request.analysisFile)
        ))
        case Right(Some(classes)) => request.analysisFile match
          case None => Left(unverifiable(AnalysisPathUnavailable, None))
          case Some(candidate) => safeAnalysisPath(workspace, candidate) match
            case Left(reason) => Left(unverifiable(reason, None))
            case Right((analysis, relative)) if !Files.exists(analysis, LinkOption.NOFOLLOW_LINKS) =>
              Left(unverifiable(AnalysisFileMissing, Some(relative)))
            case Right((analysis, relative)) if !Files.isRegularFile(analysis, LinkOption.NOFOLLOW_LINKS) =>
              Left(unverifiable(AnalysisPathUnavailable, Some(relative)))
            case Right((analysis, relative)) => request.sourceLayout match
              case None => Left(unverifiable(GeneratedOrManagedSourceUnbounded, Some(relative)))
              case Some(layout) => Right(ZincFreshnessWorkerInput(
                request.callerId,
                workspace,
                request.effectiveScalaVersion,
                classes,
                analysis,
                layout
              ))

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
    safeWorkspace(workspace).flatMap(base => value.flatMap(path => safeAnalysisPath(base, path).toOption.map(_._2)))

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

  private def workerUnavailable(analysisFile: Option[String]): InternalOutputFreshnessAssessment =
    unverifiable(UnsupportedAnalysisFormatOrVersion, analysisFile)

  private def unverifiable(
      reason: InternalOutputFreshnessReasonV6,
      analysisFile: Option[String]
  ): InternalOutputFreshnessAssessment = InternalOutputFreshnessAssessment(
    Unverifiable,
    reason,
    analysisFile,
    None,
    None
  )

object InternalOutputFreshnessAssessor:
  val default: InternalOutputFreshnessAssessor = withRuntime(ZincFreshnessWorkerRuntime.default)

  private[reconciliation] def withRuntime(
      runtime: ZincFreshnessWorkerRuntime
  ): InternalOutputFreshnessAssessor = new InternalOutputFreshnessAssessor(runtime)
