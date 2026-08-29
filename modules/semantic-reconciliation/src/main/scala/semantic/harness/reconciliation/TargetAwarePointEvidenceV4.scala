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
import semantic.harness.presentation.PresentationCompilerContext
import semantic.harness.presentation.PresentationCompilerService
import semantic.harness.presentation.SourceRange
import semantic.harness.presentation.SymbolAtResult
import semantic.harness.sbt_runner.SbtClasspathConfiguration
import semantic.harness.sbt_runner.SbtClasspathEntry
import semantic.harness.sbt_runner.SbtClasspathEntryEvidence
import semantic.harness.sbt_runner.SbtClasspathEntryKind
import semantic.harness.sbt_runner.SbtClasspathEvidenceCollector
import semantic.harness.sbt_runner.SbtPointContextAcquirer
import semantic.harness.sbt_runner.SbtPointContextFailure
import semantic.harness.sbt_runner.SbtPointContextReceipt
import semantic.harness.sbt_runner.SbtPointContextRequest
import semantic.harness.sbt_runner.SbtProjectId
import semantic.harness.sbt_runner.SbtScalaVersion
import semantic.harness.sbt_runner.ValidatedSbtJavaHome
import semantic.harness.semanticdb_reader.ArtifactSnapshot
import semantic.harness.semanticdb_reader.FreshnessBasis
import semantic.harness.semanticdb_reader.FreshnessEvidence
import semantic.harness.semanticdb_reader.SemanticdbForSource
import semantic.harness.semanticdb_reader.SemanticdbForSourceReportV2
import semantic.harness.semanticdb_reader.SemanticdbReader
import semantic.harness.semanticdb_reader.SemanticdbSourceMatchV2
import semantic.harness.semanticdb_reader.SourceArtifactFreshness
import semantic.harness.semanticdb_reader.SourceSnapshot

final case class SemanticPointEvidenceTargetRequestV4(
    workspace: Path,
    sourceFile: Path,
    line: Int,
    column: Int,
    project: SbtProjectId,
    requestedScalaVersion: Option[SbtScalaVersion] = None,
    targetJava: Option[ValidatedSbtJavaHome] = None
)

enum PointContextAcquisitionStatusV4:
  case Acquired
  case AcquisitionFailed
  case UnknownProject
  case ScalaSwitchFailed
  case ScalaAxisMismatch

object PointContextAcquisitionStatusV4:
  given Encoder[PointContextAcquisitionStatusV4] = Encoder.encodeString.contramap(_.toString)
  given Decoder[PointContextAcquisitionStatusV4] = pointEnumDecoder("point context acquisition status", values)

enum PointContextAcquisitionProfileV4:
  case PartialExistingOutputPointContext

object PointContextAcquisitionProfileV4:
  given Encoder[PointContextAcquisitionProfileV4] = Encoder.encodeString.contramap(_.toString)
  given Decoder[PointContextAcquisitionProfileV4] = pointEnumDecoder("point context acquisition profile", values)

enum PointContextAcquisitionEffectV4:
  case TargetSourceOutputsNotRequested

object PointContextAcquisitionEffectV4:
  given Encoder[PointContextAcquisitionEffectV4] = Encoder.encodeString.contramap(_.toString)
  given Decoder[PointContextAcquisitionEffectV4] = pointEnumDecoder("point context acquisition effect", values)

enum PointClasspathBasisV4:
  case ExistingSelectedClassDirectoryPlusExternalDependencies

object PointClasspathBasisV4:
  given Encoder[PointClasspathBasisV4] = Encoder.encodeString.contramap(_.toString)
  given Decoder[PointClasspathBasisV4] = pointEnumDecoder("point classpath basis", values)

enum PointContextCompletenessV4:
  case PartialExistingOutputs

object PointContextCompletenessV4:
  given Encoder[PointContextCompletenessV4] = Encoder.encodeString.contramap(_.toString)
  given Decoder[PointContextCompletenessV4] = pointEnumDecoder("point context completeness", values)

enum SelectedClassDirectoryStatusV4:
  case PresentIncluded
  case AbsentNotIncluded
  case UnavailableUnsafe

object SelectedClassDirectoryStatusV4:
  given Encoder[SelectedClassDirectoryStatusV4] = Encoder.encodeString.contramap(_.toString)
  given Decoder[SelectedClassDirectoryStatusV4] = pointEnumDecoder("selected class directory status", values)

enum CompiledOutputFreshnessV4:
  case NotAssessed

object CompiledOutputFreshnessV4:
  given Encoder[CompiledOutputFreshnessV4] = Encoder.encodeString.contramap(_.toString)
  given Decoder[CompiledOutputFreshnessV4] = pointEnumDecoder("compiled output freshness", values)

final case class PointContextEvidenceV4(
    project: String,
    configuration: String,
    status: PointContextAcquisitionStatusV4,
    requestedScalaVersion: Option[String],
    effectiveScalaVersion: Option[String],
    scalaAxisStatus: ScalaAxisStatusV4,
    targetJavaContext: Option[String],
    acquisitionProfile: PointContextAcquisitionProfileV4,
    acquisitionEffect: PointContextAcquisitionEffectV4,
    buildPerformed: TargetBuildPerformedV4,
    possibleEffects: List[String],
    pathStatus: TargetRootPathStatusV4,
    classDirectory: Option[String],
    semanticdbTargetRoot: Option[String],
    selectedClassDirectoryStatus: SelectedClassDirectoryStatusV4,
    compiledOutputFreshness: CompiledOutputFreshnessV4,
    classpathBasis: PointClasspathBasisV4,
    externalDependencyEntryCount: Option[Int],
    presentationCompilerContextEntryCount: Option[Int],
    contextCompleteness: PointContextCompletenessV4,
    failure: Option[String]
)

object PointContextEvidenceV4:
  given Encoder[PointContextEvidenceV4] = deriveEncoder
  given Decoder[PointContextEvidenceV4] = deriveDecoder

enum PointTargetSelectionStatusV4:
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
  case PointContextInputsChangedDuringRequest
  case TargetRootsOrAxisChangedDuringRequest
  case UnsafeOrUnverifiableOutputRoots

object PointTargetSelectionStatusV4:
  given Encoder[PointTargetSelectionStatusV4] = Encoder.encodeString.contramap(_.toString)
  given Decoder[PointTargetSelectionStatusV4] = pointEnumDecoder("point target selection status", values)

final case class PointTargetSelectionV4(
    status: PointTargetSelectionStatusV4,
    selectedArtifact: Option[SemanticdbSourceMatchV2],
    ownedCandidates: List[SemanticdbSourceMatchV2],
    reason: String
)

object PointTargetSelectionV4:
  given Encoder[PointTargetSelectionV4] = deriveEncoder
  given Decoder[PointTargetSelectionV4] = deriveDecoder

final case class PointEvidenceReportV4(
    schemaVersion: String = PointEvidenceReportV4.SchemaVersion,
    workspace: String,
    sourceFile: String,
    position: PointEvidencePosition,
    discovery: SemanticdbForSourceReportV2,
    targetContext: PointContextEvidenceV4,
    targetSelection: PointTargetSelectionV4,
    livePoint: PointLiveEvidence,
    reconciliation: ReconciliationResultV2
)

object PointEvidenceReportV4:
  val SchemaVersion = "semantic-scala.point-evidence-result.v4"
  given Encoder[PointEvidenceReportV4] = deriveEncoder
  given Decoder[PointEvidenceReportV4] = deriveDecoder[PointEvidenceReportV4].emap { value =>
    Either.cond(
      value.schemaVersion == SchemaVersion,
      value,
      s"point-evidence schemaVersion must be $SchemaVersion"
    )
  }

private final case class PointTargetInspectionV4(
    workspace: Path,
    sourceFile: Path,
    source: SourceSnapshot,
    sourceRelativePath: Option[String],
    discovery: SemanticdbForSourceReportV2,
    targetContext: PointContextEvidenceV4,
    targetSelection: PointTargetSelectionV4,
    receipt: Option[SbtPointContextReceipt],
    contextSnapshot: Option[PointContextSnapshot],
    selectedSnapshot: Option[ArtifactSnapshot]
)

final class PointEvidenceServiceV4 private (
    acquirer: SbtPointContextAcquirer,
    pointQuery: (Path, String, Int, Int, PresentationCompilerContext) => Either[String, SymbolAtResult],
    beforeFinalCheck: () => Unit
):
  def inspect(
      request: SemanticPointEvidenceTargetRequestV4
  ): Either[String, PointEvidenceReportV4] =
    validate(request).flatMap { validated =>
      val source = SourceSnapshot.capture(validated.sourceFile)
      val acquired = acquirer.acquire(
        SbtPointContextRequest(
          validated.workspace,
          validated.project,
          validated.requestedScalaVersion,
          validated.targetJava
        )
      )
      SemanticdbForSource
        .inspectV2WithSnapshots(validated.workspace, validated.sourceFile, source)
        .map { discovery =>
          val receiptMatches = acquired.toOption.exists(receiptMatchesRequest(validated, _))
          val contextSnapshot = acquired.toOption
            .filter(_ => receiptMatches)
            .flatMap(receipt => PointContextSafety.capture(validated.workspace, receipt).toOption)
          val targetContext = contextEvidence(validated, acquired, receiptMatches, contextSnapshot)
          val (selection, selectedSnapshot) = acquired match
            case Right(_) if !receiptMatches =>
              failed(
                PointTargetSelectionStatusV4.ScalaAxisMismatch,
                "The acquired target context did not match the requested project or Scala axis"
              ) -> None
            case Right(receipt) => select(
                validated.workspace,
                receipt,
                contextSnapshot,
                discovery.report.matches,
                discovery.artifactSnapshots
              )
            case Left(SbtPointContextFailure.UnknownProject(_)) =>
              failed(PointTargetSelectionStatusV4.UnknownProject, "The selected sbt project is unknown") -> None
            case Left(SbtPointContextFailure.ScalaSwitch(_)) =>
              failed(PointTargetSelectionStatusV4.ScalaSwitchFailed, "The requested Scala axis switch failed") -> None
            case Left(SbtPointContextFailure.ScalaVersionMismatch(_)) =>
              failed(PointTargetSelectionStatusV4.ScalaAxisMismatch, "The effective Scala axis did not match the request") -> None
            case Left(_) =>
              failed(PointTargetSelectionStatusV4.TargetContextAcquisitionFailed, "The partial point context could not be acquired") -> None

          complete(
            validated,
            PointTargetInspectionV4(
              validated.workspace,
              validated.sourceFile,
              source,
              discovery.report.sourceRelativePath,
              discovery.report,
              targetContext,
              selection,
              acquired.toOption,
              contextSnapshot,
              selectedSnapshot
            )
          )
        }
    }

  private def validate(
      request: SemanticPointEvidenceTargetRequestV4
  ): Either[String, SemanticPointEvidenceTargetRequestV4] =
    if request.line <= 0 then Left(s"Line must be positive: ${request.line}")
    else if request.column <= 0 then Left(s"Column must be positive: ${request.column}")
    else
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
      catch case _: Exception => Left("Target-aware point request could not be validated safely")

  private def receiptMatchesRequest(
      request: SemanticPointEvidenceTargetRequestV4,
      receipt: SbtPointContextReceipt
  ): Boolean =
    receipt.project == request.project &&
      receipt.configuration == SbtClasspathConfiguration.Compile &&
      receipt.requestedScalaVersion == request.requestedScalaVersion &&
      request.requestedScalaVersion.forall(_ == receipt.effectiveScalaVersion)

  private def contextEvidence(
      request: SemanticPointEvidenceTargetRequestV4,
      acquired: Either[SbtPointContextFailure, SbtPointContextReceipt],
      receiptMatches: Boolean,
      snapshot: Option[PointContextSnapshot]
  ): PointContextEvidenceV4 =
    val common = PointContextEvidenceV4(
      project = request.project.value,
      configuration = "Compile",
      status = PointContextAcquisitionStatusV4.AcquisitionFailed,
      requestedScalaVersion = request.requestedScalaVersion.map(_.value),
      effectiveScalaVersion = None,
      scalaAxisStatus = ScalaAxisStatusV4.Unavailable,
      targetJavaContext = None,
      acquisitionProfile = PointContextAcquisitionProfileV4.PartialExistingOutputPointContext,
      acquisitionEffect = PointContextAcquisitionEffectV4.TargetSourceOutputsNotRequested,
      buildPerformed = TargetBuildPerformedV4.NotRequested,
      possibleEffects = List(
        "BuildDefinitionOrPluginLoading",
        "DependencyResolution",
        "MetadataOrCacheWrites"
      ),
      pathStatus = TargetRootPathStatusV4.UnavailableUnsafeOrNonUnique,
      classDirectory = None,
      semanticdbTargetRoot = None,
      selectedClassDirectoryStatus = SelectedClassDirectoryStatusV4.UnavailableUnsafe,
      compiledOutputFreshness = CompiledOutputFreshnessV4.NotAssessed,
      classpathBasis = PointClasspathBasisV4.ExistingSelectedClassDirectoryPlusExternalDependencies,
      externalDependencyEntryCount = None,
      presentationCompilerContextEntryCount = None,
      contextCompleteness = PointContextCompletenessV4.PartialExistingOutputs,
      failure = None
    )
    acquired match
      case Right(receipt) if receiptMatches =>
        common.copy(
          status = PointContextAcquisitionStatusV4.Acquired,
          effectiveScalaVersion = Some(receipt.effectiveScalaVersion.value),
          scalaAxisStatus = if receipt.requestedScalaVersion.nonEmpty then ScalaAxisStatusV4.RequestedMatched else ScalaAxisStatusV4.BuildDefault,
          targetJavaContext = receipt.targetJavaContext,
          pathStatus = if snapshot.nonEmpty then TargetRootPathStatusV4.Represented else TargetRootPathStatusV4.UnavailableUnsafeOrNonUnique,
          classDirectory = snapshot.map(_.roots.classRelative),
          semanticdbTargetRoot = snapshot.map(_.roots.semanticdbRelative),
          selectedClassDirectoryStatus = snapshot.map(value =>
            if value.classDirectoryIncluded then SelectedClassDirectoryStatusV4.PresentIncluded
            else SelectedClassDirectoryStatusV4.AbsentNotIncluded
          ).getOrElse(SelectedClassDirectoryStatusV4.UnavailableUnsafe),
          externalDependencyEntryCount = snapshot.map(_.externalDependencyEntryCount),
          presentationCompilerContextEntryCount = snapshot.map(_.presentationEntries.size),
          failure = Option.when(snapshot.isEmpty)("UnsafeOrNonUniqueRootsOrInputs")
        )
      case Right(receipt) =>
        common.copy(
          status = PointContextAcquisitionStatusV4.ScalaAxisMismatch,
          effectiveScalaVersion = Some(receipt.effectiveScalaVersion.value),
          scalaAxisStatus = ScalaAxisStatusV4.MismatchFailure,
          targetJavaContext = receipt.targetJavaContext,
          failure = Some("ScalaVersionMismatch")
        )
      case Left(failure) =>
        val (status, axisStatus) = failure match
          case SbtPointContextFailure.UnknownProject(_) => PointContextAcquisitionStatusV4.UnknownProject -> ScalaAxisStatusV4.Unavailable
          case SbtPointContextFailure.ScalaSwitch(_) => PointContextAcquisitionStatusV4.ScalaSwitchFailed -> ScalaAxisStatusV4.SwitchFailure
          case SbtPointContextFailure.ScalaVersionMismatch(_) => PointContextAcquisitionStatusV4.ScalaAxisMismatch -> ScalaAxisStatusV4.MismatchFailure
          case _ => PointContextAcquisitionStatusV4.AcquisitionFailed -> ScalaAxisStatusV4.Unavailable
        common.copy(status = status, scalaAxisStatus = axisStatus, failure = Some(failureCode(failure)))

  private def select(
      workspace: Path,
      receipt: SbtPointContextReceipt,
      context: Option[PointContextSnapshot],
      candidates: List[SemanticdbSourceMatchV2],
      snapshots: Map[String, ArtifactSnapshot]
  ): (PointTargetSelectionV4, Option[ArtifactSnapshot]) = context match
    case None =>
      failed(
        PointTargetSelectionStatusV4.UnsafeOrUnverifiableOutputRoots,
        "The selected target roots or point-context inputs are unsafe or unverifiable"
      ) -> None
    case Some(snapshot) =>
      PointContextSafety.owned(workspace, snapshot.roots, candidates) match
        case Left(message) =>
          failed(PointTargetSelectionStatusV4.UnsafeOrUnverifiableOutputRoots, message) -> None
        case Right(Nil) =>
          failed(PointTargetSelectionStatusV4.NoCandidateOwnedByTarget, "No matched SemanticDB candidate is owned by the selected target") -> None
        case Right(owned) if owned.size > 1 =>
          PointTargetSelectionV4(
            PointTargetSelectionStatusV4.MultipleCandidatesOwnedByTarget,
            None,
            owned,
            s"${owned.size} matched SemanticDB candidates are owned by the selected target"
          ) -> None
        case Right(candidate :: Nil) =>
          snapshots.get(candidate.semanticdb) match
            case None =>
              failed(
                PointTargetSelectionStatusV4.UnsafeOrUnverifiableOutputRoots,
                "The target-owned candidate has no immutable artifact snapshot",
                List(candidate)
              ) -> None
            case Some(artifactSnapshot) => candidate.freshness match
              case Some(SourceArtifactFreshness.Fresh(_)) =>
                selected(PointTargetSelectionStatusV4.SelectedTargetOwnedFresh, candidate, "Exactly one target-owned Fresh candidate was selected") -> Some(artifactSnapshot)
              case Some(SourceArtifactFreshness.Unverifiable(_, _)) =>
                selected(PointTargetSelectionStatusV4.SelectedTargetOwnedQualifiedUnverifiable, candidate, "Exactly one target-owned candidate was selected with qualified Unverifiable freshness") -> Some(artifactSnapshot)
              case Some(SourceArtifactFreshness.Stale(_)) =>
                selected(PointTargetSelectionStatusV4.StaleTargetOwnedCandidate, candidate, "The unique target-owned candidate is Stale") -> None
              case Some(SourceArtifactFreshness.SourceChangedDuringRequest(_, _, _)) =>
                failed(PointTargetSelectionStatusV4.SourceChangedDuringRequest, "The source changed during candidate discovery", List(candidate)) -> None
              case None =>
                failed(PointTargetSelectionStatusV4.UnsafeOrUnverifiableOutputRoots, "The target-owned candidate has no freshness evidence", List(candidate)) -> None
        case Right(owned) =>
          PointTargetSelectionV4(
            PointTargetSelectionStatusV4.MultipleCandidatesOwnedByTarget,
            None,
            owned,
            s"${owned.size} matched SemanticDB candidates are owned by the selected target"
          ) -> None

  private def complete(
      request: SemanticPointEvidenceTargetRequestV4,
      inspection: PointTargetInspectionV4
  ): PointEvidenceReportV4 =
    val context = inspection.contextSnapshot.map(snapshot =>
      PresentationCompilerContext.explicit(snapshot.presentationEntries, Some(request.workspace))
    )
    val selectedArtifact = inspection.targetSelection.selectedArtifact
    val computation = (selectedArtifact, inspection.selectedSnapshot, context) match
      case (Some(_), Some(snapshot), Some(pcContext)) =>
        FreshnessReconciliationService.withPointQuery(
          (path, content, line, column) => pointQuery(path, content, line, column, pcContext)
        ).reconcileSnapshots(
          request.sourceFile,
          inspection.source,
          inspection.sourceRelativePath.getOrElse(request.sourceFile.toString),
          request.line,
          request.column,
          snapshot,
          queryWhenStale = false
        )
      case _ =>
        val liveResult = context.map(pcContext => inspection.source.content match
          case Some(content) => pointQuery(request.sourceFile, content, request.line, request.column, pcContext)
          case None => Left("The captured source snapshot has no supported UTF-8 content")
        )
        FreshnessReconciliationComputation(
          ReconciliationResultV2(
            file = request.sourceFile.toString,
            semanticdb = selectedArtifact.map(_.semanticdb),
            queryPosition = pointRange(request.line, request.column),
            freshness = selectedArtifact.flatMap(_.freshness),
            outcome = ReconciliationOutcomeV2.NotAttempted(notAttempted(inspection.targetSelection.status))
          ),
          liveResult
        )

    beforeFinalCheck()
    val sourceChanged = changedSource(inspection.source, request.sourceFile)
    val currentContext = inspection.receipt
      .filter(receiptMatchesRequest(request, _))
      .flatMap(receipt => PointContextSafety.capture(request.workspace, receipt).toOption)
    val rootsStable = (inspection.contextSnapshot, currentContext) match
      case (Some(before), Some(after)) => before.roots == after.roots
      case (None, None) => true
      case _ => false
    val inputsStable = (inspection.contextSnapshot, currentContext) match
      case (Some(before), Some(after)) => before.inputEvidence == after.inputEvidence
      case (None, None) => true
      case _ => false
    val artifactStable = (inspection.targetSelection.selectedArtifact, inspection.selectedSnapshot) match
      case (Some(_), Some(snapshot)) =>
        SemanticdbReader.readSnapshot(snapshot.path).exists(current => current.sha256 == snapshot.sha256)
      case _ => true

    val finalStatus =
      if sourceChanged.nonEmpty then Some(
        PointTargetSelectionStatusV4.SourceChangedDuringRequest -> "The source changed during the target-aware point request"
      )
      else if !rootsStable then Some(
        PointTargetSelectionStatusV4.TargetRootsOrAxisChangedDuringRequest -> "The selected target roots or Scala axis changed or could not be revalidated"
      )
      else if !inputsStable then Some(
        PointTargetSelectionStatusV4.PointContextInputsChangedDuringRequest -> "The captured partial point-context inputs changed during the request"
      )
      else if !artifactStable then Some(
        PointTargetSelectionStatusV4.SelectedArtifactChangedOrRemapped -> "The selected SemanticDB artifact changed or was remapped"
      )
      else None

    val finalSelection = finalStatus.fold(inspection.targetSelection) { case (status, reason) =>
      failed(status, reason, inspection.targetSelection.ownedCandidates)
    }
    val finalReconciliation = finalStatus match
      case Some((PointTargetSelectionStatusV4.SourceChangedDuringRequest, _)) =>
        val (before, after) = sourceChanged.get
        computation.report.copy(
          freshness = Some(SourceArtifactFreshness.SourceChangedDuringRequest(
            before,
            after,
            freshnessEvidence(inspection.source, selectedArtifact.flatMap(_.freshness))
          )),
          outcome = ReconciliationOutcomeV2.NotAttempted(ReconciliationNotAttemptedReasonV2.SourceChangedDuringRequest)
        )
      case Some((PointTargetSelectionStatusV4.SelectedArtifactChangedOrRemapped, _)) =>
        computation.report.copy(
          outcome = ReconciliationOutcomeV2.NotAttempted(ReconciliationNotAttemptedReasonV2.SelectedArtifactChangedOrRemapped)
        )
      case Some(_) =>
        computation.report.copy(
          outcome = ReconciliationOutcomeV2.NotAttempted(ReconciliationNotAttemptedReasonV2.ArtifactEvidenceUnavailable)
        )
      case None => computation.report

    PointEvidenceReportV4(
      workspace = inspection.workspace.toString,
      sourceFile = inspection.sourceFile.toString,
      position = PointEvidencePosition(request.line, request.column, PointEvidencePosition.Encoding),
      discovery = inspection.discovery,
      targetContext = inspection.targetContext,
      targetSelection = finalSelection,
      livePoint = live(computation.liveResult),
      reconciliation = finalReconciliation
    )

  private def selected(
      status: PointTargetSelectionStatusV4,
      candidate: SemanticdbSourceMatchV2,
      reason: String
  ): PointTargetSelectionV4 = PointTargetSelectionV4(status, Some(candidate), List(candidate), reason)

  private def failed(
      status: PointTargetSelectionStatusV4,
      reason: String,
      owned: List[SemanticdbSourceMatchV2] = Nil
  ): PointTargetSelectionV4 = PointTargetSelectionV4(status, None, owned, reason)

  private def changedSource(source: SourceSnapshot, path: Path): Option[(String, String)] =
    source.sha256
      .flatMap(before => SourceSnapshot.recaptureSha256(path).map(before -> _))
      .filter { case (before, after) => before != after }

  private def freshnessEvidence(
      source: SourceSnapshot,
      freshness: Option[SourceArtifactFreshness]
  ): FreshnessEvidence = freshness.map(SourceArtifactFreshness.evidence).getOrElse(
    FreshnessEvidence(
      FreshnessBasis.None,
      None,
      None,
      None,
      source.md5,
      source.sha256,
      None,
      source.mtimeMillis,
      None,
      Some("SourceChangedDuringRequest")
    )
  )

  private def notAttempted(
      status: PointTargetSelectionStatusV4
  ): ReconciliationNotAttemptedReasonV2 = status match
    case PointTargetSelectionStatusV4.MultipleCandidatesOwnedByTarget => ReconciliationNotAttemptedReasonV2.AmbiguousArtifactCandidates
    case PointTargetSelectionStatusV4.StaleTargetOwnedCandidate => ReconciliationNotAttemptedReasonV2.StaleArtifact
    case PointTargetSelectionStatusV4.SourceChangedDuringRequest => ReconciliationNotAttemptedReasonV2.SourceChangedDuringRequest
    case PointTargetSelectionStatusV4.SelectedArtifactChangedOrRemapped => ReconciliationNotAttemptedReasonV2.SelectedArtifactChangedOrRemapped
    case PointTargetSelectionStatusV4.UnsafeOrUnverifiableOutputRoots |
        PointTargetSelectionStatusV4.PointContextInputsChangedDuringRequest |
        PointTargetSelectionStatusV4.TargetRootsOrAxisChangedDuringRequest =>
      ReconciliationNotAttemptedReasonV2.ArtifactEvidenceUnavailable
    case _ => ReconciliationNotAttemptedReasonV2.NoArtifactCandidate

  private def live(value: Option[Either[String, SymbolAtResult]]): PointLiveEvidence = value match
    case Some(Right(result)) if result.symbol.nonEmpty => PointLiveEvidence(PointLiveStatus.Resolved, Some(result), None)
    case Some(Right(result)) => PointLiveEvidence(PointLiveStatus.Unresolved, Some(result), None)
    case Some(Left(reason)) => PointLiveEvidence(PointLiveStatus.Unavailable, None, Some(reason))
    case None => PointLiveEvidence(PointLiveStatus.Unavailable, None, Some("Target-aligned live point evidence was not available"))

  private def pointRange(line: Int, column: Int): SourceRange =
    SourceRange(line - 1, column - 1, line - 1, column - 1)

  private def failureCode(failure: SbtPointContextFailure): String = failure match
    case SbtPointContextFailure.Validation(_) => "ValidationFailed"
    case SbtPointContextFailure.ScalaSwitch(_) => "ScalaSwitchFailed"
    case SbtPointContextFailure.ScalaVersionMismatch(_) => "ScalaVersionMismatch"
    case SbtPointContextFailure.UnknownProject(_) => "UnknownProject"
    case SbtPointContextFailure.Process(_) => "ProcessFailed"
    case SbtPointContextFailure.Protocol(_) => "ProtocolFailed"

object PointEvidenceServiceV4:
  def apply(): PointEvidenceServiceV4 =
    val compiler = PresentationCompilerService()
    withDependencies(
      SbtPointContextAcquirer.default,
      (path, source, line, column, context) =>
        compiler.symbolAtSnapshot(path, source, line, column, context),
      () => ()
    )

  private[reconciliation] def withDependencies(
      acquirer: SbtPointContextAcquirer,
      pointQuery: (Path, String, Int, Int, PresentationCompilerContext) => Either[String, SymbolAtResult],
      beforeFinalCheck: () => Unit
  ): PointEvidenceServiceV4 =
    new PointEvidenceServiceV4(acquirer, pointQuery, beforeFinalCheck)

private final case class PointPathIdentity(path: Path, existed: Boolean, fileKey: Option[String])

private final case class PointRootSnapshot(
    classIdentity: PointPathIdentity,
    semanticdbIdentity: PointPathIdentity,
    classRelative: String,
    semanticdbRelative: String
)

private final case class PointContextSnapshot(
    roots: PointRootSnapshot,
    classDirectoryIncluded: Boolean,
    externalDependencyEntryCount: Int,
    presentationEntries: List[Path],
    inputEvidence: List[SbtClasspathEntryEvidence]
)

private object PointContextSafety:
  def capture(
      workspace: Path,
      receipt: SbtPointContextReceipt
  ): Either[String, PointContextSnapshot] =
    for
      workspaceReal <- realWorkspace(workspace)
      classIdentity <- safeRoot(workspaceReal, receipt.classDirectory, "class directory")
      semanticdbIdentity <- safeRoot(workspaceReal, receipt.semanticdbTargetRoot, "SemanticDB target root")
      _ <- Either.cond(
        classIdentity.path != semanticdbIdentity.path,
        (),
        "The selected target output roots are not unique"
      )
      _ <- Either.cond(
        classIdentity.existed == receipt.classDirectoryPresent,
        (),
        "The selected class-directory presence did not match the receipt"
      )
      external <- validateExternal(receipt.externalDependencyClasspath)
      presentation = distinctEntries(
        Option.when(classIdentity.existed)(
          SbtClasspathEntry(classIdentity.path, SbtClasspathEntryKind.Directory)
        ).toList ++ external
      )
      _ <- Either.cond(
        presentation.nonEmpty,
        (),
        "The partial point context has no usable existing output or external dependency entry"
      )
      evidence <- SbtClasspathEvidenceCollector.default.collectEntries(presentation).left.map(_ =>
        "The partial point-context input identity set could not be captured within bounds"
      )
    yield PointContextSnapshot(
      PointRootSnapshot(
        classIdentity,
        semanticdbIdentity,
        relative(workspaceReal, classIdentity.path),
        relative(workspaceReal, semanticdbIdentity.path)
      ),
      classIdentity.existed,
      external.size,
      presentation.map(_.path),
      evidence
    )

  def owned(
      workspace: Path,
      roots: PointRootSnapshot,
      candidates: List[SemanticdbSourceMatchV2]
  ): Either[String, List[SemanticdbSourceMatchV2]] =
    realWorkspace(workspace).flatMap { workspaceReal =>
      if !roots.semanticdbIdentity.existed then Right(Nil)
      else candidates.foldLeft[Either[String, List[SemanticdbSourceMatchV2]]](Right(Nil)) {
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

  private def validateExternal(
      entries: List[SbtClasspathEntry]
  ): Either[String, List[SbtClasspathEntry]] =
    entries.foldLeft[Either[String, List[SbtClasspathEntry]]](Right(Nil)) { (result, entry) =>
      for
        values <- result
        validated <- SbtClasspathEntry.validate(entry).left.map(_ =>
          "An external dependency classpath entry is unavailable or unsafe"
        )
        canonical <- Try(validated.path.toRealPath()).toEither.left.map(_ =>
          "An external dependency classpath entry could not be canonicalized"
        )
      yield values :+ validated.copy(path = canonical)
    }

  private def distinctEntries(entries: List[SbtClasspathEntry]): List[SbtClasspathEntry] =
    entries.foldLeft(List.empty[SbtClasspathEntry]) { (values, entry) =>
      if values.exists(_.path == entry.path) then values else values :+ entry
    }

  private def realWorkspace(workspace: Path): Either[String, Path] =
    Try(workspace.toRealPath()).toEither.left.map(_ => "The workspace could not be revalidated")

  private def safeRoot(
      workspaceReal: Path,
      candidate: Path,
      description: String
  ): Either[String, PointPathIdentity] =
    try
      val normalized = candidate.toAbsolutePath.normalize()
      if !normalized.startsWith(workspaceReal) then Left(s"The reported $description is outside the workspace")
      else if containsSymlink(workspaceReal, normalized) then Left(s"The reported $description traverses a symbolic link")
      else if Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) then
        if !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) then Left(s"The reported $description is not a directory")
        else
          val real = normalized.toRealPath()
          val attributes = Files.readAttributes(real, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
          Right(PointPathIdentity(real, existed = true, Option(attributes.fileKey()).map(_.toString)))
      else Right(PointPathIdentity(normalized, existed = false, None))
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

private def pointEnumDecoder[A](name: String, values: Array[A]): Decoder[A] =
  Decoder.decodeString.emap(value => values.find(_.toString == value).toRight(s"Invalid $name: $value"))
