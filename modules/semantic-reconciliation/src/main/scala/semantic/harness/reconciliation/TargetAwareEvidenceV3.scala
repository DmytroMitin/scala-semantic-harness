package semantic.harness.reconciliation

import io.circe.Decoder
import io.circe.Encoder
import io.circe.generic.semiauto.deriveDecoder
import io.circe.generic.semiauto.deriveEncoder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import semantic.harness.presentation.PresentationCompilerContext
import semantic.harness.presentation.PresentationCompilerService
import semantic.harness.presentation.SourceRange
import semantic.harness.presentation.SymbolAtResult
import semantic.harness.sbt_runner.SbtProjectId
import semantic.harness.sbt_runner.SbtTargetContextAcquirer
import semantic.harness.sbt_runner.SbtTargetContextFailure
import semantic.harness.sbt_runner.SbtTargetContextReceipt
import semantic.harness.sbt_runner.SbtTargetContextRequest
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

final case class SemanticdbForSourceTargetRequest(
    workspace: Path,
    sourceFile: Path,
    project: SbtProjectId,
    targetJava: Option[ValidatedSbtJavaHome] = None
)

final case class SemanticPointEvidenceTargetRequest(
    workspace: Path,
    sourceFile: Path,
    line: Int,
    column: Int,
    project: SbtProjectId,
    targetJava: Option[ValidatedSbtJavaHome] = None
)

enum TargetContextAcquisitionStatusV3:
  case Acquired
  case AcquisitionFailed
  case UnknownProject

object TargetContextAcquisitionStatusV3:
  given Encoder[TargetContextAcquisitionStatusV3] = Encoder.encodeString.contramap(_.toString)
  given Decoder[TargetContextAcquisitionStatusV3] = enumDecoder("TargetContextAcquisitionStatusV3", values)

enum TargetContextAcquisitionOriginV3:
  case FreshSbtEvaluation

object TargetContextAcquisitionOriginV3:
  given Encoder[TargetContextAcquisitionOriginV3] = Encoder.encodeString.contramap(_.toString)
  given Decoder[TargetContextAcquisitionOriginV3] = enumDecoder("TargetContextAcquisitionOriginV3", values)

enum TargetPathProvenanceStatusV3:
  case Represented
  case UnavailableUnsafe

object TargetPathProvenanceStatusV3:
  given Encoder[TargetPathProvenanceStatusV3] = Encoder.encodeString.contramap(_.toString)
  given Decoder[TargetPathProvenanceStatusV3] = enumDecoder("TargetPathProvenanceStatusV3", values)

final case class TargetContextEvidenceV3(
    project: String,
    configuration: String,
    status: TargetContextAcquisitionStatusV3,
    acquisitionOrigin: Option[TargetContextAcquisitionOriginV3],
    pathProvenanceStatus: TargetPathProvenanceStatusV3,
    classDirectory: Option[String],
    semanticdbTargetRoot: Option[String],
    classpathEntryCount: Option[Int],
    scalaVersion: Option[String],
    targetJavaContext: Option[String],
    failure: Option[String]
)

object TargetContextEvidenceV3:
  given Encoder[TargetContextEvidenceV3] = deriveEncoder
  given Decoder[TargetContextEvidenceV3] = deriveDecoder

enum TargetArtifactSelectionStatusV3:
  case SelectedTargetOwnedFresh
  case SelectedTargetOwnedQualifiedUnverifiable
  case NoCandidateOwnedByTarget
  case MultipleCandidatesOwnedByTarget
  case StaleTargetOwnedCandidate
  case TargetContextAcquisitionFailed
  case UnknownProject
  case SourceChangedDuringRequest
  case UnsafeOrUnverifiableOutputRootOwnership

object TargetArtifactSelectionStatusV3:
  given Encoder[TargetArtifactSelectionStatusV3] = Encoder.encodeString.contramap(_.toString)
  given Decoder[TargetArtifactSelectionStatusV3] = enumDecoder("TargetArtifactSelectionStatusV3", values)

final case class TargetArtifactSelectionV3(
    status: TargetArtifactSelectionStatusV3,
    selectedArtifact: Option[SemanticdbSourceMatchV2],
    ownedCandidates: List[SemanticdbSourceMatchV2],
    reason: String
)

object TargetArtifactSelectionV3:
  given Encoder[TargetArtifactSelectionV3] = deriveEncoder
  given Decoder[TargetArtifactSelectionV3] = deriveDecoder

final case class SemanticdbForSourceReportV3(
    schemaVersion: String = SemanticdbForSourceReportV3.SchemaVersion,
    workspace: String,
    sourceFile: String,
    sourceRelativePath: Option[String],
    discovery: SemanticdbForSourceReportV2,
    targetContext: TargetContextEvidenceV3,
    targetSelection: TargetArtifactSelectionV3
)

object SemanticdbForSourceReportV3:
  val SchemaVersion = "semantic-scala.semanticdb-for-source.v3"
  given Encoder[SemanticdbForSourceReportV3] = deriveEncoder
  given Decoder[SemanticdbForSourceReportV3] = deriveDecoder[SemanticdbForSourceReportV3].emap { value =>
    Either.cond(value.schemaVersion == SchemaVersion, value, s"semanticdb-for-source schemaVersion must be $SchemaVersion")
  }

final case class PointEvidenceReportV3(
    schemaVersion: String = PointEvidenceReportV3.SchemaVersion,
    workspace: String,
    sourceFile: String,
    position: PointEvidencePosition,
    discovery: SemanticdbForSourceReportV2,
    targetContext: TargetContextEvidenceV3,
    targetSelection: TargetArtifactSelectionV3,
    livePoint: PointLiveEvidence,
    reconciliation: ReconciliationResultV2
)

object PointEvidenceReportV3:
  val SchemaVersion = "semantic-scala.point-evidence-result.v3"
  given Encoder[PointEvidenceReportV3] = deriveEncoder
  given Decoder[PointEvidenceReportV3] = deriveDecoder[PointEvidenceReportV3].emap { value =>
    Either.cond(value.schemaVersion == SchemaVersion, value, s"point-evidence schemaVersion must be $SchemaVersion")
  }

private final case class TargetAwareInspection(
    report: SemanticdbForSourceReportV3,
    source: SourceSnapshot,
    receipt: Option[SbtTargetContextReceipt],
    selectedSnapshot: Option[ArtifactSnapshot]
)

final case class SemanticdbForSourceServiceV3(
    acquirer: SbtTargetContextAcquirer = SbtTargetContextAcquirer.default
):
  def inspect(
      request: SemanticdbForSourceTargetRequest
  ): Either[String, SemanticdbForSourceReportV3] = inspectWithSnapshots(request).map(_.report)

  private[reconciliation] def inspectWithSnapshots(
      request: SemanticdbForSourceTargetRequest
  ): Either[String, TargetAwareInspection] =
    validate(request).flatMap { validated =>
      val source = SourceSnapshot.capture(validated.sourceFile)
      val acquired = acquirer.acquire(
        SbtTargetContextRequest(validated.workspace, validated.project, validated.targetJava)
      )
      SemanticdbForSource
        .inspectV2WithSnapshots(validated.workspace, validated.sourceFile, source)
        .map { inspection =>
          val context = targetContext(validated.workspace, validated.project, acquired)
          val (selection, selectedSnapshot) = acquired match
            case Right(receipt) => select(validated.workspace, receipt, inspection.report.matches, inspection.artifactSnapshots)
            case Left(SbtTargetContextFailure.UnknownProject(_)) =>
              failedSelection(TargetArtifactSelectionStatusV3.UnknownProject, "The selected sbt project is unknown") -> None
            case Left(_) =>
              failedSelection(TargetArtifactSelectionStatusV3.TargetContextAcquisitionFailed, "The selected target context could not be acquired") -> None
          val stableSelection = sourceChange(source, validated.sourceFile) match
            case Some(_) => failedSelection(
                TargetArtifactSelectionStatusV3.SourceChangedDuringRequest,
                "The source changed during the target-aware request"
              )
            case None => selection
          TargetAwareInspection(
            SemanticdbForSourceReportV3(
              workspace = validated.workspace.toString,
              sourceFile = validated.sourceFile.toString,
              sourceRelativePath = inspection.report.sourceRelativePath,
              discovery = inspection.report,
              targetContext = context,
              targetSelection = stableSelection
            ),
            source,
            acquired.toOption,
            Option.when(stableSelection == selection)(selectedSnapshot).flatten
          )
        }
    }

  private def validate(
      request: SemanticdbForSourceTargetRequest
  ): Either[String, SemanticdbForSourceTargetRequest] =
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
      workspace: Path,
      project: SbtProjectId,
      acquired: Either[SbtTargetContextFailure, SbtTargetContextReceipt]
  ): TargetContextEvidenceV3 = acquired match
    case Right(receipt) =>
      val classDirectory = safeRelative(workspace, receipt.classDirectory)
      val semanticdbTargetRoot = safeRelative(workspace, receipt.semanticdbTargetRoot)
      TargetContextEvidenceV3(
        project.value,
        "Compile",
        TargetContextAcquisitionStatusV3.Acquired,
        Some(TargetContextAcquisitionOriginV3.FreshSbtEvaluation),
        if classDirectory.nonEmpty && semanticdbTargetRoot.nonEmpty then TargetPathProvenanceStatusV3.Represented
        else TargetPathProvenanceStatusV3.UnavailableUnsafe,
        classDirectory,
        semanticdbTargetRoot,
        Some(receipt.classpath.size),
        Some(receipt.scalaVersion),
        receipt.targetJavaContext,
        None
      )
    case Left(failure) =>
      val status = failure match
        case SbtTargetContextFailure.UnknownProject(_) => TargetContextAcquisitionStatusV3.UnknownProject
        case _ => TargetContextAcquisitionStatusV3.AcquisitionFailed
      TargetContextEvidenceV3(
        project.value,
        "Compile",
        status,
        None,
        TargetPathProvenanceStatusV3.UnavailableUnsafe,
        None,
        None,
        None,
        None,
        None,
        Some(failureCode(failure))
      )

  private def select(
      workspace: Path,
      receipt: SbtTargetContextReceipt,
      candidates: List[SemanticdbSourceMatchV2],
      snapshots: Map[String, ArtifactSnapshot]
  ): (TargetArtifactSelectionV3, Option[ArtifactSnapshot]) =
    TargetOwnership.owned(workspace, receipt.semanticdbTargetRoot, candidates) match
      case Left(message) =>
        failedSelection(TargetArtifactSelectionStatusV3.UnsafeOrUnverifiableOutputRootOwnership, message) -> None
      case Right(Nil) =>
        failedSelection(TargetArtifactSelectionStatusV3.NoCandidateOwnedByTarget, "No matched SemanticDB candidate is owned by the selected target") -> None
      case Right(owned) if owned.size > 1 =>
        TargetArtifactSelectionV3(
          TargetArtifactSelectionStatusV3.MultipleCandidatesOwnedByTarget,
          None,
          owned,
          s"${owned.size} matched SemanticDB candidates are owned by the selected target"
        ) -> None
      case Right(candidate :: Nil) =>
        val owned = List(candidate)
        snapshots.get(candidate.semanticdb) match
          case None =>
            TargetArtifactSelectionV3(
              TargetArtifactSelectionStatusV3.UnsafeOrUnverifiableOutputRootOwnership,
              None,
              owned,
              "The target-owned candidate has no readable immutable artifact snapshot"
            ) -> None
          case Some(snapshot) =>
            candidate.freshness match
              case Some(SourceArtifactFreshness.Fresh(_)) =>
                selected(TargetArtifactSelectionStatusV3.SelectedTargetOwnedFresh, candidate, owned, "Exactly one target-owned Fresh candidate was selected") -> Some(snapshot)
              case Some(SourceArtifactFreshness.Unverifiable(_, _)) =>
                selected(TargetArtifactSelectionStatusV3.SelectedTargetOwnedQualifiedUnverifiable, candidate, owned, "Exactly one target-owned candidate was selected with qualified Unverifiable freshness") -> Some(snapshot)
              case Some(SourceArtifactFreshness.Stale(_)) =>
                selected(TargetArtifactSelectionStatusV3.StaleTargetOwnedCandidate, candidate, owned, "The unique target-owned candidate is Stale") -> None
              case Some(SourceArtifactFreshness.SourceChangedDuringRequest(_, _, _)) =>
                selected(TargetArtifactSelectionStatusV3.SourceChangedDuringRequest, candidate, owned, "The source changed during candidate inspection") -> None
              case None =>
                TargetArtifactSelectionV3(
                  TargetArtifactSelectionStatusV3.UnsafeOrUnverifiableOutputRootOwnership,
                  None,
                  owned,
                  "The target-owned candidate has no freshness evidence"
                ) -> None
      case Right(owned) =>
        TargetArtifactSelectionV3(
          TargetArtifactSelectionStatusV3.MultipleCandidatesOwnedByTarget,
          None,
          owned,
          s"${owned.size} matched SemanticDB candidates are owned by the selected target"
        ) -> None

  private def selected(
      status: TargetArtifactSelectionStatusV3,
      candidate: SemanticdbSourceMatchV2,
      owned: List[SemanticdbSourceMatchV2],
      reason: String
  ): TargetArtifactSelectionV3 = TargetArtifactSelectionV3(status, Some(candidate), owned, reason)

  private def failedSelection(
      status: TargetArtifactSelectionStatusV3,
      reason: String
  ): TargetArtifactSelectionV3 = TargetArtifactSelectionV3(status, None, Nil, reason)

  private def safeRelative(workspace: Path, path: Path): Option[String] =
    try
      val real = path.toRealPath()
      Option.when(real.startsWith(workspace.toRealPath()))(
        workspace.toRealPath().relativize(real).toString.replace(java.io.File.separatorChar, '/')
      )
    catch case _: Exception => None

  private def sourceChange(source: SourceSnapshot, path: Path): Option[(String, String)] =
    source.sha256
      .flatMap(before => SourceSnapshot.recaptureSha256(path).map(before -> _))
      .filter { case (before, after) => before != after }

  private def failureCode(failure: SbtTargetContextFailure): String = failure match
    case SbtTargetContextFailure.Validation(_) => "ValidationFailed"
    case SbtTargetContextFailure.UnknownProject(_) => "UnknownProject"
    case SbtTargetContextFailure.Process(_) => "ProcessFailed"
    case SbtTargetContextFailure.Protocol(_) => "ProtocolFailed"

final class PointEvidenceServiceV3 private (
    sourceService: SemanticdbForSourceServiceV3,
    pointQuery: (Path, String, Int, Int, PresentationCompilerContext) => Either[String, SymbolAtResult],
    beforeFinalCheck: () => Unit
):
  def inspect(request: SemanticPointEvidenceTargetRequest): Either[String, PointEvidenceReportV3] =
    if request.line <= 0 then Left(s"Line must be positive: ${request.line}")
    else if request.column <= 0 then Left(s"Column must be positive: ${request.column}")
    else
      val sourceRequest = SemanticdbForSourceTargetRequest(
        request.workspace,
        request.sourceFile,
        request.project,
        request.targetJava
      )
      sourceService.inspectWithSnapshots(sourceRequest).map(complete(request, _))

  private def complete(
      request: SemanticPointEvidenceTargetRequest,
      inspection: TargetAwareInspection
  ): PointEvidenceReportV3 =
    val source = inspection.source
    val receipt = inspection.receipt
    val context = receipt.map(value =>
      PresentationCompilerContext.explicit(value.classpath.map(_.path), Some(request.workspace))
    )
    val selected = inspection.report.targetSelection.selectedArtifact
    val computation = (selected, inspection.selectedSnapshot, context) match
      case (Some(_), Some(snapshot), Some(pcContext)) =>
        val reconciler = FreshnessReconciliationService.withPointQuery(
          (path, content, line, column) => pointQuery(path, content, line, column, pcContext)
        )
        reconciler.reconcileSnapshots(
          request.sourceFile,
          source,
          inspection.report.sourceRelativePath.getOrElse(request.sourceFile.toAbsolutePath.normalize().toString),
          request.line,
          request.column,
          snapshot,
          queryWhenStale = false
        )
      case _ =>
        val live = context.map(pcContext => source.content match
          case Some(content) => pointQuery(request.sourceFile, content, request.line, request.column, pcContext)
          case None => Left("The captured source snapshot has no supported UTF-8 content")
        )
        FreshnessReconciliationComputation(
          ReconciliationResultV2(
            file = request.sourceFile.toAbsolutePath.normalize().toString,
            semanticdb = selected.map(_.semanticdb),
            queryPosition = pointRange(request.line, request.column),
            freshness = selected.flatMap(_.freshness),
            outcome = ReconciliationOutcomeV2.NotAttempted(notAttempted(inspection.report.targetSelection.status))
          ),
          live
        )

    beforeFinalCheck()
    val sourceMutation = changedSource(source, request.sourceFile)
    val artifactStable = (selected, inspection.selectedSnapshot, receipt) match
      case (Some(candidate), Some(snapshot), Some(targetReceipt)) =>
        stableArtifact(request.workspace, targetReceipt, candidate, snapshot)
      case _ => true
    val finalSelection = sourceMutation match
      case Some(_) => TargetArtifactSelectionV3(
          TargetArtifactSelectionStatusV3.SourceChangedDuringRequest,
          None,
          inspection.report.targetSelection.ownedCandidates,
          "The source changed during the target-aware point request"
        )
      case None => inspection.report.targetSelection
    val finalReconciliation = sourceMutation match
      case Some((before, after)) =>
        computation.report.copy(
          freshness = Some(SourceArtifactFreshness.SourceChangedDuringRequest(
            before,
            after,
            freshnessEvidence(source, selected.flatMap(_.freshness))
          )),
          outcome = ReconciliationOutcomeV2.NotAttempted(ReconciliationNotAttemptedReasonV2.SourceChangedDuringRequest)
        )
      case None if !artifactStable =>
        computation.report.copy(
          outcome = ReconciliationOutcomeV2.NotAttempted(ReconciliationNotAttemptedReasonV2.SelectedArtifactChangedOrRemapped)
        )
      case None => computation.report

    PointEvidenceReportV3(
      workspace = inspection.report.workspace,
      sourceFile = inspection.report.sourceFile,
      position = PointEvidencePosition(request.line, request.column, PointEvidencePosition.Encoding),
      discovery = inspection.report.discovery,
      targetContext = inspection.report.targetContext,
      targetSelection = finalSelection,
      livePoint = live(computation.liveResult),
      reconciliation = finalReconciliation
    )

  private def stableArtifact(
      workspace: Path,
      receipt: SbtTargetContextReceipt,
      candidate: SemanticdbSourceMatchV2,
      snapshot: ArtifactSnapshot
  ): Boolean =
    TargetOwnership.owned(workspace, receipt.semanticdbTargetRoot, List(candidate)) match
      case Right(`candidate` :: Nil) =>
        SemanticdbReader.readSnapshot(snapshot.path).exists(current => current.sha256 == snapshot.sha256)
      case _ => false

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

  private def notAttempted(status: TargetArtifactSelectionStatusV3): ReconciliationNotAttemptedReasonV2 = status match
    case TargetArtifactSelectionStatusV3.MultipleCandidatesOwnedByTarget => ReconciliationNotAttemptedReasonV2.AmbiguousArtifactCandidates
    case TargetArtifactSelectionStatusV3.StaleTargetOwnedCandidate => ReconciliationNotAttemptedReasonV2.StaleArtifact
    case TargetArtifactSelectionStatusV3.SourceChangedDuringRequest => ReconciliationNotAttemptedReasonV2.SourceChangedDuringRequest
    case TargetArtifactSelectionStatusV3.UnsafeOrUnverifiableOutputRootOwnership => ReconciliationNotAttemptedReasonV2.ArtifactEvidenceUnavailable
    case _ => ReconciliationNotAttemptedReasonV2.NoArtifactCandidate

  private def live(value: Option[Either[String, SymbolAtResult]]): PointLiveEvidence = value match
    case Some(Right(result)) if result.symbol.nonEmpty => PointLiveEvidence(PointLiveStatus.Resolved, Some(result), None)
    case Some(Right(result)) => PointLiveEvidence(PointLiveStatus.Unresolved, Some(result), None)
    case Some(Left(reason)) => PointLiveEvidence(PointLiveStatus.Unavailable, None, Some(reason))
    case None => PointLiveEvidence(PointLiveStatus.Unavailable, None, Some("Target-aligned live point evidence was not available"))

  private def pointRange(line: Int, column: Int): SourceRange =
    SourceRange(line - 1, column - 1, line - 1, column - 1)

object PointEvidenceServiceV3:
  def apply(): PointEvidenceServiceV3 =
    val compiler = PresentationCompilerService()
    withDependencies(
      SbtTargetContextAcquirer.default,
      (path, source, line, column, context) =>
        compiler.symbolAtSnapshot(path, source, line, column, context),
      () => ()
    )

  private[reconciliation] def withDependencies(
      acquirer: SbtTargetContextAcquirer,
      pointQuery: (Path, String, Int, Int, PresentationCompilerContext) => Either[String, SymbolAtResult],
      beforeFinalCheck: () => Unit
  ): PointEvidenceServiceV3 =
    new PointEvidenceServiceV3(SemanticdbForSourceServiceV3(acquirer), pointQuery, beforeFinalCheck)

private object TargetOwnership:
  def owned(
      workspace: Path,
      semanticdbTargetRoot: Path,
      candidates: List[SemanticdbSourceMatchV2]
  ): Either[String, List[SemanticdbSourceMatchV2]] =
    validateRoot(workspace, semanticdbTargetRoot).flatMap { case (workspaceReal, rootReal) =>
      candidates.foldLeft[Either[String, List[SemanticdbSourceMatchV2]]](Right(Nil)) {
        (result, candidate) =>
          result.flatMap { owned =>
            val path = workspaceReal.resolve(candidate.semanticdb).normalize()
            validateCandidate(workspaceReal, path).map { candidateReal =>
              if candidateReal.startsWith(rootReal) then owned :+ candidate else owned
            }
          }
      }
    }

  private def validateRoot(workspace: Path, root: Path): Either[String, (Path, Path)] =
    try
      val workspaceReal = workspace.toRealPath()
      val normalized = root.toAbsolutePath.normalize()
      if !normalized.startsWith(workspaceReal) then Left("The reported SemanticDB target root is outside the workspace")
      else if Files.isSymbolicLink(normalized) || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) then
        Left("The reported SemanticDB target root is not a safe directory")
      else if containsSymlink(workspaceReal, normalized) then
        Left("The reported SemanticDB target root traverses a symbolic link")
      else Right(workspaceReal -> normalized.toRealPath())
    catch case _: Exception => Left("The reported SemanticDB target root could not be verified safely")

  private def validateCandidate(workspace: Path, path: Path): Either[String, Path] =
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

private def enumDecoder[A](name: String, values: Array[A]): Decoder[A] =
  Decoder.decodeString.emap(value => values.find(_.toString == value).toRight(s"Invalid $name: $value"))
