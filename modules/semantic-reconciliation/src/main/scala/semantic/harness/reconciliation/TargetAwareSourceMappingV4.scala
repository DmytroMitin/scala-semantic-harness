package semantic.harness.reconciliation

import io.circe.Decoder
import io.circe.Encoder
import io.circe.generic.semiauto.deriveDecoder
import io.circe.generic.semiauto.deriveEncoder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import scala.jdk.CollectionConverters.*
import scala.util.Try
import semantic.harness.sbt_runner.SbtProjectId
import semantic.harness.sbt_runner.SbtScalaVersion
import semantic.harness.sbt_runner.SbtSourceMappingRootAcquirer
import semantic.harness.sbt_runner.SbtSourceMappingRootFailure
import semantic.harness.sbt_runner.SbtSourceMappingRootReceipt
import semantic.harness.sbt_runner.SbtSourceMappingRootRequest
import semantic.harness.sbt_runner.ValidatedSbtJavaHome
import semantic.harness.semanticdb_reader.ArtifactSnapshot
import semantic.harness.semanticdb_reader.SemanticdbForSource
import semantic.harness.semanticdb_reader.SemanticdbForSourceReportV2
import semantic.harness.semanticdb_reader.SemanticdbReader
import semantic.harness.semanticdb_reader.SemanticdbSourceMatchV2
import semantic.harness.semanticdb_reader.SourceArtifactFreshness
import semantic.harness.semanticdb_reader.SourceSnapshot

final case class SemanticdbForSourceTargetRequestV4(
    workspace: Path,
    sourceFile: Path,
    project: SbtProjectId,
    requestedScalaVersion: Option[SbtScalaVersion] = None,
    targetJava: Option[ValidatedSbtJavaHome] = None
)

enum TargetRootAcquisitionStatusV4:
  case Acquired
  case AcquisitionFailed
  case UnknownProject
  case ScalaSwitchFailed
  case ScalaAxisMismatch

object TargetRootAcquisitionStatusV4:
  given Encoder[TargetRootAcquisitionStatusV4] = Encoder.encodeString.contramap(_.toString)
  given Decoder[TargetRootAcquisitionStatusV4] = enumDecoderV4("target root acquisition status", values)

enum ScalaAxisStatusV4:
  case BuildDefault
  case RequestedMatched
  case SwitchFailure
  case MismatchFailure
  case Unavailable

object ScalaAxisStatusV4:
  given Encoder[ScalaAxisStatusV4] = Encoder.encodeString.contramap(_.toString)
  given Decoder[ScalaAxisStatusV4] = enumDecoderV4("Scala axis status", values)

enum TargetAcquisitionProfileV4:
  case RootOnlySourceMapping

object TargetAcquisitionProfileV4:
  given Encoder[TargetAcquisitionProfileV4] = Encoder.encodeString.contramap(_.toString)
  given Decoder[TargetAcquisitionProfileV4] = enumDecoderV4("target acquisition profile", values)

enum TargetAcquisitionEffectV4:
  case TargetSourceOutputsNotRequested

object TargetAcquisitionEffectV4:
  given Encoder[TargetAcquisitionEffectV4] = Encoder.encodeString.contramap(_.toString)
  given Decoder[TargetAcquisitionEffectV4] = enumDecoderV4("target acquisition effect", values)

enum TargetBuildPerformedV4:
  case NotRequested

object TargetBuildPerformedV4:
  given Encoder[TargetBuildPerformedV4] = Encoder.encodeString.contramap(_.toString)
  given Decoder[TargetBuildPerformedV4] = enumDecoderV4("target build state", values)

enum TargetRootPathStatusV4:
  case Represented
  case UnavailableUnsafeOrNonUnique

object TargetRootPathStatusV4:
  given Encoder[TargetRootPathStatusV4] = Encoder.encodeString.contramap(_.toString)
  given Decoder[TargetRootPathStatusV4] = enumDecoderV4("target root path status", values)

final case class TargetRootContextEvidenceV4(
    project: String,
    configuration: String,
    status: TargetRootAcquisitionStatusV4,
    requestedScalaVersion: Option[String],
    effectiveScalaVersion: Option[String],
    scalaAxisStatus: ScalaAxisStatusV4,
    targetJavaContext: Option[String],
    acquisitionProfile: TargetAcquisitionProfileV4,
    acquisitionEffect: TargetAcquisitionEffectV4,
    buildPerformed: TargetBuildPerformedV4,
    possibleEffects: List[String],
    pathStatus: TargetRootPathStatusV4,
    classDirectory: Option[String],
    semanticdbTargetRoot: Option[String],
    failure: Option[String]
)

object TargetRootContextEvidenceV4:
  given Encoder[TargetRootContextEvidenceV4] = deriveEncoder
  given Decoder[TargetRootContextEvidenceV4] = deriveDecoder

enum TargetArtifactSelectionStatusV4:
  case SelectedTargetOwnedFresh
  case SelectedTargetOwnedQualifiedUnverifiable
  case NoCandidateOwnedByTarget
  case MultipleCandidatesOwnedByTarget
  case StaleTargetOwnedCandidate
  case TargetContextAcquisitionFailed
  case UnknownProject
  case ScalaSwitchFailed
  case ScalaAxisMismatch
  case SourceChangedDuringRequest
  case SelectedArtifactChangedOrRemapped
  case UnsafeOrUnverifiableOutputRoots

object TargetArtifactSelectionStatusV4:
  given Encoder[TargetArtifactSelectionStatusV4] = Encoder.encodeString.contramap(_.toString)
  given Decoder[TargetArtifactSelectionStatusV4] = enumDecoderV4("target artifact selection status", values)

final case class TargetArtifactSelectionV4(
    status: TargetArtifactSelectionStatusV4,
    selectedArtifact: Option[SemanticdbSourceMatchV2],
    ownedCandidates: List[SemanticdbSourceMatchV2],
    reason: String
)

object TargetArtifactSelectionV4:
  given Encoder[TargetArtifactSelectionV4] = deriveEncoder
  given Decoder[TargetArtifactSelectionV4] = deriveDecoder

final case class SemanticdbForSourceReportV4(
    schemaVersion: String = SemanticdbForSourceReportV4.SchemaVersion,
    workspace: String,
    sourceFile: String,
    sourceRelativePath: Option[String],
    discovery: SemanticdbForSourceReportV2,
    targetContext: TargetRootContextEvidenceV4,
    targetSelection: TargetArtifactSelectionV4
)

object SemanticdbForSourceReportV4:
  val SchemaVersion = "semantic-scala.semanticdb-for-source.v4"
  given Encoder[SemanticdbForSourceReportV4] = deriveEncoder
  given Decoder[SemanticdbForSourceReportV4] = deriveDecoder[SemanticdbForSourceReportV4].emap { value =>
    Either.cond(
      value.schemaVersion == SchemaVersion,
      value,
      s"semanticdb-for-source schemaVersion must be $SchemaVersion"
    )
  }

final class SemanticdbForSourceServiceV4 private (
    acquirer: SbtSourceMappingRootAcquirer,
    beforeFinalCheck: () => Unit
):
  def inspect(
      request: SemanticdbForSourceTargetRequestV4
  ): Either[String, SemanticdbForSourceReportV4] =
    validate(request).flatMap { validated =>
      val source = SourceSnapshot.capture(validated.sourceFile)
      val acquired = acquirer.acquire(
        SbtSourceMappingRootRequest(
          validated.workspace,
          validated.project,
          validated.requestedScalaVersion,
          validated.targetJava
        )
      )
      SemanticdbForSource
        .inspectV2WithSnapshots(validated.workspace, validated.sourceFile, source)
        .map { inspection =>
          val roots = acquired.toOption.flatMap(receipt =>
            TargetRootSafety.capture(validated.workspace, receipt).toOption
          )
          val context = targetContext(validated, acquired, roots)
          val (initialSelection, selectedSnapshot) = acquired match
            case Right(receipt) => select(
                validated.workspace,
                receipt,
                roots,
                inspection.report.matches,
                inspection.artifactSnapshots
              )
            case Left(SbtSourceMappingRootFailure.UnknownProject(_)) =>
              failed(TargetArtifactSelectionStatusV4.UnknownProject, "The selected sbt project is unknown") -> None
            case Left(SbtSourceMappingRootFailure.ScalaSwitch(_)) =>
              failed(TargetArtifactSelectionStatusV4.ScalaSwitchFailed, "The requested Scala axis switch failed") -> None
            case Left(SbtSourceMappingRootFailure.ScalaVersionMismatch(_)) =>
              failed(TargetArtifactSelectionStatusV4.ScalaAxisMismatch, "The effective Scala axis did not match the request") -> None
            case Left(_) =>
              failed(TargetArtifactSelectionStatusV4.TargetContextAcquisitionFailed, "The target roots could not be acquired") -> None

          beforeFinalCheck()
          val finalSelection =
            if sourceChanged(source, validated.sourceFile) then
              failed(
                TargetArtifactSelectionStatusV4.SourceChangedDuringRequest,
                "The source changed during the target-aware source-mapping request",
                initialSelection.ownedCandidates
              )
            else if !rootsStable(validated.workspace, acquired.toOption, roots) then
              failed(
                TargetArtifactSelectionStatusV4.UnsafeOrUnverifiableOutputRoots,
                "The selected target output roots changed or could not be revalidated",
                initialSelection.ownedCandidates
              )
            else if !artifactStable(initialSelection, selectedSnapshot) then
              failed(
                TargetArtifactSelectionStatusV4.SelectedArtifactChangedOrRemapped,
                "The selected SemanticDB artifact changed or was remapped",
                initialSelection.ownedCandidates
              )
            else initialSelection

          SemanticdbForSourceReportV4(
            workspace = validated.workspace.toString,
            sourceFile = validated.sourceFile.toString,
            sourceRelativePath = inspection.report.sourceRelativePath,
            discovery = inspection.report,
            targetContext = context,
            targetSelection = finalSelection
          )
        }
    }

  private def validate(
      request: SemanticdbForSourceTargetRequestV4
  ): Either[String, SemanticdbForSourceTargetRequestV4] =
    try
      val workspace = request.workspace.toAbsolutePath.normalize()
      val source = request.sourceFile.toAbsolutePath.normalize()
      if !Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(workspace) then
        Left("Target-aware workspace must be one non-symbolic-link directory")
      else if !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(source) then
        Left("Target-aware source must be one non-symbolic-link regular file")
      else if source.getFileName == null || !source.getFileName.toString.endsWith(".scala") then
        Left("Target-aware source must be a .scala file")
      else
        val workspaceReal = workspace.toRealPath()
        val sourceReal = source.toRealPath()
        if !sourceReal.startsWith(workspaceReal) then Left("Target-aware source resolves outside the workspace")
        else Right(request.copy(workspace = workspaceReal, sourceFile = sourceReal))
    catch case _: Exception => Left("Target-aware request could not be validated safely")

  private def targetContext(
      request: SemanticdbForSourceTargetRequestV4,
      acquired: Either[SbtSourceMappingRootFailure, SbtSourceMappingRootReceipt],
      roots: Option[TargetRootSnapshot]
  ): TargetRootContextEvidenceV4 =
    val common = TargetRootContextEvidenceV4(
      project = request.project.value,
      configuration = "Compile",
      status = TargetRootAcquisitionStatusV4.AcquisitionFailed,
      requestedScalaVersion = request.requestedScalaVersion.map(_.value),
      effectiveScalaVersion = None,
      scalaAxisStatus = ScalaAxisStatusV4.Unavailable,
      targetJavaContext = None,
      acquisitionProfile = TargetAcquisitionProfileV4.RootOnlySourceMapping,
      acquisitionEffect = TargetAcquisitionEffectV4.TargetSourceOutputsNotRequested,
      buildPerformed = TargetBuildPerformedV4.NotRequested,
      possibleEffects = List(
        "BuildDefinitionOrPluginLoading",
        "DependencyResolution",
        "MetadataOrCacheWrites"
      ),
      pathStatus = TargetRootPathStatusV4.UnavailableUnsafeOrNonUnique,
      classDirectory = None,
      semanticdbTargetRoot = None,
      failure = None
    )
    acquired match
      case Right(receipt) =>
        common.copy(
          status = TargetRootAcquisitionStatusV4.Acquired,
          effectiveScalaVersion = Some(receipt.effectiveScalaVersion.value),
          scalaAxisStatus = if receipt.requestedScalaVersion.nonEmpty then ScalaAxisStatusV4.RequestedMatched else ScalaAxisStatusV4.BuildDefault,
          targetJavaContext = receipt.targetJavaContext,
          pathStatus = if roots.nonEmpty then TargetRootPathStatusV4.Represented else TargetRootPathStatusV4.UnavailableUnsafeOrNonUnique,
          classDirectory = roots.map(_.classRelative),
          semanticdbTargetRoot = roots.map(_.semanticdbRelative),
          failure = Option.when(roots.isEmpty)("UnsafeOrNonUniqueRoots")
        )
      case Left(failure) =>
        val (status, axisStatus) = failure match
          case SbtSourceMappingRootFailure.UnknownProject(_) => TargetRootAcquisitionStatusV4.UnknownProject -> ScalaAxisStatusV4.Unavailable
          case SbtSourceMappingRootFailure.ScalaSwitch(_) => TargetRootAcquisitionStatusV4.ScalaSwitchFailed -> ScalaAxisStatusV4.SwitchFailure
          case SbtSourceMappingRootFailure.ScalaVersionMismatch(_) => TargetRootAcquisitionStatusV4.ScalaAxisMismatch -> ScalaAxisStatusV4.MismatchFailure
          case _ => TargetRootAcquisitionStatusV4.AcquisitionFailed -> ScalaAxisStatusV4.Unavailable
        common.copy(status = status, scalaAxisStatus = axisStatus, failure = Some(failureCode(failure)))

  private def select(
      workspace: Path,
      receipt: SbtSourceMappingRootReceipt,
      roots: Option[TargetRootSnapshot],
      candidates: List[SemanticdbSourceMatchV2],
      snapshots: Map[String, ArtifactSnapshot]
  ): (TargetArtifactSelectionV4, Option[ArtifactSnapshot]) = roots match
    case None =>
      failed(
        TargetArtifactSelectionStatusV4.UnsafeOrUnverifiableOutputRoots,
        "The selected target roots are unsafe, unverifiable, or non-unique"
      ) -> None
    case Some(rootSnapshot) =>
      TargetRootSafety.owned(workspace, rootSnapshot, candidates) match
        case Left(message) =>
          failed(TargetArtifactSelectionStatusV4.UnsafeOrUnverifiableOutputRoots, message) -> None
        case Right(Nil) =>
          failed(TargetArtifactSelectionStatusV4.NoCandidateOwnedByTarget, "No matched SemanticDB candidate is owned by the selected target") -> None
        case Right(owned) if owned.size > 1 =>
          TargetArtifactSelectionV4(
            TargetArtifactSelectionStatusV4.MultipleCandidatesOwnedByTarget,
            None,
            owned,
            s"${owned.size} matched SemanticDB candidates are owned by the selected target"
          ) -> None
        case Right(candidate :: Nil) =>
          snapshots.get(candidate.semanticdb) match
            case None =>
              failed(
                TargetArtifactSelectionStatusV4.UnsafeOrUnverifiableOutputRoots,
                "The target-owned candidate has no immutable artifact snapshot",
                List(candidate)
              ) -> None
            case Some(snapshot) => candidate.freshness match
              case Some(SourceArtifactFreshness.Fresh(_)) =>
                selected(TargetArtifactSelectionStatusV4.SelectedTargetOwnedFresh, candidate, "Exactly one target-owned Fresh candidate was selected") -> Some(snapshot)
              case Some(SourceArtifactFreshness.Unverifiable(_, _)) =>
                selected(TargetArtifactSelectionStatusV4.SelectedTargetOwnedQualifiedUnverifiable, candidate, "Exactly one target-owned candidate was selected with qualified Unverifiable freshness") -> Some(snapshot)
              case Some(SourceArtifactFreshness.Stale(_)) =>
                selected(TargetArtifactSelectionStatusV4.StaleTargetOwnedCandidate, candidate, "The unique target-owned candidate is Stale") -> None
              case Some(SourceArtifactFreshness.SourceChangedDuringRequest(_, _, _)) =>
                failed(TargetArtifactSelectionStatusV4.SourceChangedDuringRequest, "The source changed during candidate discovery", List(candidate)) -> None
              case None =>
                failed(TargetArtifactSelectionStatusV4.UnsafeOrUnverifiableOutputRoots, "The target-owned candidate has no freshness evidence", List(candidate)) -> None
        case Right(owned) =>
          TargetArtifactSelectionV4(
            TargetArtifactSelectionStatusV4.MultipleCandidatesOwnedByTarget,
            None,
            owned,
            s"${owned.size} matched SemanticDB candidates are owned by the selected target"
          ) -> None

  private def selected(
      status: TargetArtifactSelectionStatusV4,
      candidate: SemanticdbSourceMatchV2,
      reason: String
  ): TargetArtifactSelectionV4 = TargetArtifactSelectionV4(status, Some(candidate), List(candidate), reason)

  private def failed(
      status: TargetArtifactSelectionStatusV4,
      reason: String,
      owned: List[SemanticdbSourceMatchV2] = Nil
  ): TargetArtifactSelectionV4 = TargetArtifactSelectionV4(status, None, owned, reason)

  private def sourceChanged(source: SourceSnapshot, path: Path): Boolean =
    source.sha256
      .flatMap(before => SourceSnapshot.recaptureSha256(path).map(before -> _))
      .exists { case (before, after) => before != after }

  private def rootsStable(
      workspace: Path,
      receipt: Option[SbtSourceMappingRootReceipt],
      before: Option[TargetRootSnapshot]
  ): Boolean = (receipt, before) match
    case (Some(value), Some(snapshot)) =>
      TargetRootSafety.capture(workspace, value).contains(snapshot)
    case (Some(_), None) => false
    case (None, None) => true
    case _ => false

  private def artifactStable(
      selection: TargetArtifactSelectionV4,
      snapshot: Option[ArtifactSnapshot]
  ): Boolean = selection.status match
    case TargetArtifactSelectionStatusV4.SelectedTargetOwnedFresh |
        TargetArtifactSelectionStatusV4.SelectedTargetOwnedQualifiedUnverifiable =>
      snapshot.exists(value =>
        SemanticdbReader.readSnapshot(value.path).exists(current => current.sha256 == value.sha256)
      )
    case _ => true

  private def failureCode(failure: SbtSourceMappingRootFailure): String = failure match
    case SbtSourceMappingRootFailure.Validation(_) => "ValidationFailed"
    case SbtSourceMappingRootFailure.ScalaSwitch(_) => "ScalaSwitchFailed"
    case SbtSourceMappingRootFailure.ScalaVersionMismatch(_) => "ScalaVersionMismatch"
    case SbtSourceMappingRootFailure.UnknownProject(_) => "UnknownProject"
    case SbtSourceMappingRootFailure.Process(_) => "ProcessFailed"
    case SbtSourceMappingRootFailure.Protocol(_) => "ProtocolFailed"

object SemanticdbForSourceServiceV4:
  def apply(): SemanticdbForSourceServiceV4 =
    withDependencies(SbtSourceMappingRootAcquirer.default, () => ())

  private[reconciliation] def withDependencies(
      acquirer: SbtSourceMappingRootAcquirer,
      beforeFinalCheck: () => Unit
  ): SemanticdbForSourceServiceV4 =
    new SemanticdbForSourceServiceV4(acquirer, beforeFinalCheck)

private final case class TargetPathIdentity(path: Path, existed: Boolean, fileKey: Option[String])

private final case class TargetRootSnapshot(
    classIdentity: TargetPathIdentity,
    semanticdbIdentity: TargetPathIdentity,
    classRelative: String,
    semanticdbRelative: String
)

private object TargetRootSafety:
  def capture(
      workspace: Path,
      receipt: SbtSourceMappingRootReceipt
  ): Either[String, TargetRootSnapshot] =
    for
      workspaceReal <- realWorkspace(workspace)
      classIdentity <- safePath(workspaceReal, receipt.classDirectory, "class directory")
      semanticdbIdentity <- safePath(workspaceReal, receipt.semanticdbTargetRoot, "SemanticDB target root")
      _ <- Either.cond(
        classIdentity.path != semanticdbIdentity.path,
        (),
        "The selected target output roots are not unique"
      )
    yield TargetRootSnapshot(
      classIdentity,
      semanticdbIdentity,
      relative(workspaceReal, classIdentity.path),
      relative(workspaceReal, semanticdbIdentity.path)
    )

  def owned(
      workspace: Path,
      roots: TargetRootSnapshot,
      candidates: List[SemanticdbSourceMatchV2]
  ): Either[String, List[SemanticdbSourceMatchV2]] =
    realWorkspace(workspace).flatMap { workspaceReal =>
      candidates.foldLeft[Either[String, List[SemanticdbSourceMatchV2]]](Right(Nil)) {
        (result, candidate) =>
          result.flatMap { owned =>
            val path = workspaceReal.resolve(candidate.semanticdb).normalize()
            safeCandidate(workspaceReal, path).map { candidateReal =>
              if candidateReal.startsWith(roots.semanticdbIdentity.path) then owned :+ candidate
              else owned
            }
          }
      }
    }

  private def realWorkspace(workspace: Path): Either[String, Path] =
    Try(workspace.toRealPath()).toEither.left.map(_ => "The workspace could not be revalidated")

  private def safePath(
      workspaceReal: Path,
      candidate: Path,
      description: String
  ): Either[String, TargetPathIdentity] =
    try
      val normalized = candidate.toAbsolutePath.normalize()
      if !normalized.startsWith(workspaceReal) then Left(s"The reported $description is outside the workspace")
      else if containsSymlink(workspaceReal, normalized) then Left(s"The reported $description traverses a symbolic link")
      else if Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) then
        if !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) then Left(s"The reported $description is not a directory")
        else
          val real = normalized.toRealPath()
          val attributes = Files.readAttributes(real, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
          Right(TargetPathIdentity(real, existed = true, Option(attributes.fileKey()).map(_.toString)))
      else
        Right(TargetPathIdentity(normalized, existed = false, None))
    catch case _: Exception => Left(s"The reported $description could not be verified safely")

  private def safeCandidate(workspace: Path, path: Path): Either[String, Path] =
    try
      if !path.startsWith(workspace) then Left("A SemanticDB candidate escapes the workspace")
      else if Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) then
        Left("A SemanticDB candidate is not a safe regular file")
      else if containsSymlink(workspace, path) then Left("A SemanticDB candidate traverses a symbolic link")
      else
        val real = path.toRealPath()
        Either.cond(real.startsWith(workspace), real, "A SemanticDB candidate resolves outside the workspace")
    catch case _: Exception => Left("A SemanticDB candidate could not be verified safely")

  private def containsSymlink(base: Path, target: Path): Boolean =
    val relative = base.relativize(target)
    relative.iterator().asScala.foldLeft((base, false)) { case ((current, found), component) =>
      val next = current.resolve(component)
      (next, found || Files.isSymbolicLink(next))
    }._2

  private def relative(workspace: Path, path: Path): String =
    workspace.relativize(path).toString.replace(java.io.File.separatorChar, '/')

private def enumDecoderV4[A](name: String, values: Array[A]): Decoder[A] =
  Decoder.decodeString.emap(value => values.find(_.toString == value).toRight(s"Invalid $name: $value"))
