package semantic.harness.reconciliation

import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.generic.semiauto.deriveDecoder
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax.*
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
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
import semantic.harness.sbt_runner.SbtCompileDependencyMapping
import semantic.harness.sbt_runner.SbtInternalDependencyExclusion
import semantic.harness.sbt_runner.SbtInternalDependencyExclusionReason
import semantic.harness.sbt_runner.SbtInternalDependencyGraph
import semantic.harness.sbt_runner.SbtInternalDependencyReceipt
import semantic.harness.sbt_runner.SbtInternalDependencyRole
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

final case class SemanticPointEvidenceTargetRequestV5(
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

enum PointContextAcquisitionProfileV5:
  case PartialExistingCompileOutputPointContext

object PointContextAcquisitionProfileV5:
  given Encoder[PointContextAcquisitionProfileV5] = Encoder.encodeString.contramap(_.toString)
  given Decoder[PointContextAcquisitionProfileV5] = pointEnumDecoder("point context acquisition profile v5", values)

enum PointClasspathBasisV5:
  case ExistingSelectedAndInternalCompileOutputsPlusExternalDependencies

object PointClasspathBasisV5:
  given Encoder[PointClasspathBasisV5] = Encoder.encodeString.contramap(_.toString)
  given Decoder[PointClasspathBasisV5] = pointEnumDecoder("point classpath basis v5", values)

enum PointContextCompletenessV5:
  case PartialExistingCompileOutputs

object PointContextCompletenessV5:
  given Encoder[PointContextCompletenessV5] = Encoder.encodeString.contramap(_.toString)
  given Decoder[PointContextCompletenessV5] = pointEnumDecoder("point context completeness v5", values)

enum InternalClassDirectoryStatusV5:
  case PresentIncluded
  case AbsentNotIncluded
  case UnavailableUnsafe

object InternalClassDirectoryStatusV5:
  given Encoder[InternalClassDirectoryStatusV5] = Encoder.encodeString.contramap(_.toString)
  given Decoder[InternalClassDirectoryStatusV5] = pointEnumDecoder("internal class directory status v5", values)

enum InternalDependencyAcquisitionEffectV5:
  case DependencySourceOutputsNotRequested

object InternalDependencyAcquisitionEffectV5:
  given Encoder[InternalDependencyAcquisitionEffectV5] = Encoder.encodeString.contramap(_.toString)
  given Decoder[InternalDependencyAcquisitionEffectV5] = pointEnumDecoder("internal dependency acquisition effect v5", values)

final case class InternalCompileDependencyEvidenceV5(
    projectRef: String,
    project: String,
    role: SbtInternalDependencyRole,
    compileMapping: SbtCompileDependencyMapping,
    requestedScalaVersion: Option[String],
    effectiveScalaVersion: String,
    scalaAxisStatus: ScalaAxisStatusV4,
    configuration: String,
    classDirectory: Option[String],
    directoryStatus: InternalClassDirectoryStatusV5,
    contributedToPresentationCompilerContext: Boolean,
    acquisitionEffect: InternalDependencyAcquisitionEffectV5
)

object InternalCompileDependencyEvidenceV5:
  given Encoder[SbtInternalDependencyRole] = Encoder.encodeString.contramap(_.toString)
  given Decoder[SbtInternalDependencyRole] = pointEnumDecoder("internal dependency role", SbtInternalDependencyRole.values)
  given Encoder[SbtCompileDependencyMapping] = Encoder.encodeString.contramap(_.toString)
  given Decoder[SbtCompileDependencyMapping] = pointEnumDecoder("Compile dependency mapping", SbtCompileDependencyMapping.values)
  given Encoder[InternalCompileDependencyEvidenceV5] = deriveEncoder
  given Decoder[InternalCompileDependencyEvidenceV5] = deriveDecoder[InternalCompileDependencyEvidenceV5].emap(validate)

  private def validate(
      value: InternalCompileDependencyEvidenceV5
  ): Either[String, InternalCompileDependencyEvidenceV5] =
    val projectValid = SbtProjectId.parse(value.project).isRight
    val referenceValid = value.projectRef == s"ThisBuild/${value.project}"
    val axisValid = value.scalaAxisStatus match
      case ScalaAxisStatusV4.BuildDefault => value.requestedScalaVersion.isEmpty
      case ScalaAxisStatusV4.RequestedMatched => value.requestedScalaVersion.exists(_.nonEmpty)
      case _ => false
    val directoryValid = value.directoryStatus match
      case InternalClassDirectoryStatusV5.PresentIncluded |
          InternalClassDirectoryStatusV5.AbsentNotIncluded =>
        value.classDirectory.exists(isRelativePublicPath)
      case InternalClassDirectoryStatusV5.UnavailableUnsafe => value.classDirectory.isEmpty
    val contributionValid = value.contributedToPresentationCompilerContext ==
      (value.directoryStatus == InternalClassDirectoryStatusV5.PresentIncluded)
    Either.cond(
      projectValid && referenceValid && value.compileMapping.admitted && axisValid &&
        value.effectiveScalaVersion.nonEmpty && value.configuration == "Compile" &&
        directoryValid && contributionValid,
      value,
      "point-evidence v5 internal dependency fields are inconsistent"
    )

  private[reconciliation] def isRelativePublicPath(value: String): Boolean =
    value.nonEmpty && !value.startsWith("/") && !value.matches("^[A-Za-z]:[\\\\/].*") &&
      !value.split("/", -1).contains("..")

final case class InternalDependencyExclusionEvidenceV5(
    projectRef: String,
    project: String,
    role: SbtInternalDependencyRole,
    compileMapping: SbtCompileDependencyMapping,
    reason: String,
    effectiveScalaVersion: Option[String]
)

object InternalDependencyExclusionEvidenceV5:
  import InternalCompileDependencyEvidenceV5.given
  given Encoder[InternalDependencyExclusionEvidenceV5] = deriveEncoder
  given Decoder[InternalDependencyExclusionEvidenceV5] = deriveDecoder[InternalDependencyExclusionEvidenceV5].emap(validate)

  private def validate(
      value: InternalDependencyExclusionEvidenceV5
  ): Either[String, InternalDependencyExclusionEvidenceV5] =
    val projectValid = SbtProjectId.parse(value.project).isRight
    val referenceValid = value.projectRef == s"ThisBuild/${value.project}" ||
      value.projectRef == s"ExternalBuild/${value.project}"
    val reasonValid = SbtInternalDependencyExclusionReason.parse(value.reason).isRight
    Either.cond(
      projectValid && referenceValid && reasonValid,
      value,
      "point-evidence v5 internal dependency exclusion fields are inconsistent"
    )

final case class PointContextEvidenceV5(
    project: String,
    configuration: String,
    status: PointContextAcquisitionStatusV4,
    requestedScalaVersion: Option[String],
    effectiveScalaVersion: Option[String],
    scalaAxisStatus: ScalaAxisStatusV4,
    targetJavaContext: Option[String],
    acquisitionProfile: PointContextAcquisitionProfileV5,
    acquisitionEffect: PointContextAcquisitionEffectV4,
    buildPerformed: TargetBuildPerformedV4,
    possibleEffects: List[String],
    pathStatus: TargetRootPathStatusV4,
    classDirectory: Option[String],
    semanticdbTargetRoot: Option[String],
    selectedClassDirectoryStatus: SelectedClassDirectoryStatusV4,
    compiledOutputFreshness: CompiledOutputFreshnessV4,
    classpathBasis: PointClasspathBasisV5,
    externalDependencyEntryCount: Option[Int],
    internalDependencies: List[InternalCompileDependencyEvidenceV5],
    internalDependencyExclusions: List[InternalDependencyExclusionEvidenceV5],
    internalDependencyDiscoveredCount: Option[Int],
    internalDependencyPresentIncludedCount: Option[Int],
    internalDependencyAbsentNotIncludedCount: Option[Int],
    internalDependencyUnavailableUnsafeCount: Option[Int],
    internalDependencyExcludedCount: Option[Int],
    presentationCompilerContextEntryCount: Option[Int],
    contextCompleteness: PointContextCompletenessV5,
    failure: Option[String]
)

object PointContextEvidenceV5:
  import InternalCompileDependencyEvidenceV5.given
  import InternalDependencyExclusionEvidenceV5.given
  given Encoder[PointContextEvidenceV5] = Encoder.instance { value =>
    Json.obj(
      "project" -> value.project.asJson,
      "configuration" -> value.configuration.asJson,
      "status" -> value.status.asJson,
      "requestedScalaVersion" -> value.requestedScalaVersion.asJson,
      "effectiveScalaVersion" -> value.effectiveScalaVersion.asJson,
      "scalaAxisStatus" -> value.scalaAxisStatus.asJson,
      "targetJavaContext" -> value.targetJavaContext.asJson,
      "acquisitionProfile" -> value.acquisitionProfile.asJson,
      "acquisitionEffect" -> value.acquisitionEffect.asJson,
      "buildPerformed" -> value.buildPerformed.asJson,
      "possibleEffects" -> value.possibleEffects.asJson,
      "pathStatus" -> value.pathStatus.asJson,
      "classDirectory" -> value.classDirectory.asJson,
      "semanticdbTargetRoot" -> value.semanticdbTargetRoot.asJson,
      "selectedClassDirectoryStatus" -> value.selectedClassDirectoryStatus.asJson,
      "compiledOutputFreshness" -> value.compiledOutputFreshness.asJson,
      "classpathBasis" -> value.classpathBasis.asJson,
      "externalDependencyEntryCount" -> value.externalDependencyEntryCount.asJson,
      "internalDependencies" -> value.internalDependencies.asJson,
      "internalDependencyExclusions" -> value.internalDependencyExclusions.asJson,
      "internalDependencyDiscoveredCount" -> value.internalDependencyDiscoveredCount.asJson,
      "internalDependencyPresentIncludedCount" -> value.internalDependencyPresentIncludedCount.asJson,
      "internalDependencyAbsentNotIncludedCount" -> value.internalDependencyAbsentNotIncludedCount.asJson,
      "internalDependencyUnavailableUnsafeCount" -> value.internalDependencyUnavailableUnsafeCount.asJson,
      "internalDependencyExcludedCount" -> value.internalDependencyExcludedCount.asJson,
      "presentationCompilerContextEntryCount" -> value.presentationCompilerContextEntryCount.asJson,
      "contextCompleteness" -> value.contextCompleteness.asJson,
      "failure" -> value.failure.asJson
    )
  }
  given Decoder[PointContextEvidenceV5] = Decoder.instance { cursor =>
    for
      project <- cursor.get[String]("project")
      configuration <- cursor.get[String]("configuration")
      status <- cursor.get[PointContextAcquisitionStatusV4]("status")
      requestedScalaVersion <- cursor.get[Option[String]]("requestedScalaVersion")
      effectiveScalaVersion <- cursor.get[Option[String]]("effectiveScalaVersion")
      scalaAxisStatus <- cursor.get[ScalaAxisStatusV4]("scalaAxisStatus")
      targetJavaContext <- cursor.get[Option[String]]("targetJavaContext")
      acquisitionProfile <- cursor.get[PointContextAcquisitionProfileV5]("acquisitionProfile")
      acquisitionEffect <- cursor.get[PointContextAcquisitionEffectV4]("acquisitionEffect")
      buildPerformed <- cursor.get[TargetBuildPerformedV4]("buildPerformed")
      possibleEffects <- cursor.get[List[String]]("possibleEffects")
      pathStatus <- cursor.get[TargetRootPathStatusV4]("pathStatus")
      classDirectory <- cursor.get[Option[String]]("classDirectory")
      semanticdbTargetRoot <- cursor.get[Option[String]]("semanticdbTargetRoot")
      selectedClassDirectoryStatus <- cursor.get[SelectedClassDirectoryStatusV4]("selectedClassDirectoryStatus")
      compiledOutputFreshness <- cursor.get[CompiledOutputFreshnessV4]("compiledOutputFreshness")
      classpathBasis <- cursor.get[PointClasspathBasisV5]("classpathBasis")
      externalDependencyEntryCount <- cursor.get[Option[Int]]("externalDependencyEntryCount")
      internalDependencies <- cursor.get[List[InternalCompileDependencyEvidenceV5]]("internalDependencies")
      internalDependencyExclusions <- cursor.get[List[InternalDependencyExclusionEvidenceV5]]("internalDependencyExclusions")
      internalDependencyDiscoveredCount <- cursor.get[Option[Int]]("internalDependencyDiscoveredCount")
      internalDependencyPresentIncludedCount <- cursor.get[Option[Int]]("internalDependencyPresentIncludedCount")
      internalDependencyAbsentNotIncludedCount <- cursor.get[Option[Int]]("internalDependencyAbsentNotIncludedCount")
      internalDependencyUnavailableUnsafeCount <- cursor.get[Option[Int]]("internalDependencyUnavailableUnsafeCount")
      internalDependencyExcludedCount <- cursor.get[Option[Int]]("internalDependencyExcludedCount")
      presentationCompilerContextEntryCount <- cursor.get[Option[Int]]("presentationCompilerContextEntryCount")
      contextCompleteness <- cursor.get[PointContextCompletenessV5]("contextCompleteness")
      failure <- cursor.get[Option[String]]("failure")
    yield PointContextEvidenceV5(
      project,
      configuration,
      status,
      requestedScalaVersion,
      effectiveScalaVersion,
      scalaAxisStatus,
      targetJavaContext,
      acquisitionProfile,
      acquisitionEffect,
      buildPerformed,
      possibleEffects,
      pathStatus,
      classDirectory,
      semanticdbTargetRoot,
      selectedClassDirectoryStatus,
      compiledOutputFreshness,
      classpathBasis,
      externalDependencyEntryCount,
      internalDependencies,
      internalDependencyExclusions,
      internalDependencyDiscoveredCount,
      internalDependencyPresentIncludedCount,
      internalDependencyAbsentNotIncludedCount,
      internalDependencyUnavailableUnsafeCount,
      internalDependencyExcludedCount,
      presentationCompilerContextEntryCount,
      contextCompleteness,
      failure
    )
  }.emap(validateCounts)

  private def validateCounts(value: PointContextEvidenceV5): Either[String, PointContextEvidenceV5] =
    val present = value.internalDependencies.count(_.directoryStatus == InternalClassDirectoryStatusV5.PresentIncluded)
    val absent = value.internalDependencies.count(_.directoryStatus == InternalClassDirectoryStatusV5.AbsentNotIncluded)
    val unsafe = value.internalDependencies.count(_.directoryStatus == InternalClassDirectoryStatusV5.UnavailableUnsafe)
    val contributionsValid = value.internalDependencies.forall { dependency =>
      val shouldContribute = dependency.directoryStatus == InternalClassDirectoryStatusV5.PresentIncluded
      dependency.contributedToPresentationCompilerContext == shouldContribute &&
        (dependency.directoryStatus == InternalClassDirectoryStatusV5.UnavailableUnsafe) == dependency.classDirectory.isEmpty
    }
    val selected = if value.selectedClassDirectoryStatus == SelectedClassDirectoryStatusV4.PresentIncluded then 1 else 0
    val representedAcquired =
      value.status == PointContextAcquisitionStatusV4.Acquired &&
        value.pathStatus == TargetRootPathStatusV4.Represented
    val acquisitionFailed = value.status != PointContextAcquisitionStatusV4.Acquired
    val requiredCounts = List(
      value.externalDependencyEntryCount,
      value.internalDependencyDiscoveredCount,
      value.internalDependencyPresentIncludedCount,
      value.internalDependencyAbsentNotIncludedCount,
      value.internalDependencyUnavailableUnsafeCount,
      value.internalDependencyExcludedCount,
      value.presentationCompilerContextEntryCount
    )
    val representedStateValid = !representedAcquired || (
      value.effectiveScalaVersion.exists(_.nonEmpty) &&
        value.classDirectory.exists(InternalCompileDependencyEvidenceV5.isRelativePublicPath) &&
        value.semanticdbTargetRoot.exists(InternalCompileDependencyEvidenceV5.isRelativePublicPath) &&
        value.selectedClassDirectoryStatus != SelectedClassDirectoryStatusV4.UnavailableUnsafe &&
        requiredCounts.forall(_.nonEmpty) && value.failure.isEmpty
    )
    val unsafeAcquiredStateValid = value.status != PointContextAcquisitionStatusV4.Acquired ||
      value.pathStatus != TargetRootPathStatusV4.UnavailableUnsafeOrNonUnique || (
        value.classDirectory.isEmpty && value.semanticdbTargetRoot.isEmpty &&
          value.selectedClassDirectoryStatus == SelectedClassDirectoryStatusV4.UnavailableUnsafe &&
          requiredCounts.forall(_.isEmpty) && value.internalDependencies.isEmpty &&
          value.internalDependencyExclusions.isEmpty && value.failure.exists(_.nonEmpty)
      )
    val failedStateValid = !acquisitionFailed || (
      value.pathStatus == TargetRootPathStatusV4.UnavailableUnsafeOrNonUnique &&
        value.classDirectory.isEmpty && value.semanticdbTargetRoot.isEmpty &&
        value.selectedClassDirectoryStatus == SelectedClassDirectoryStatusV4.UnavailableUnsafe &&
        requiredCounts.forall(_.isEmpty) && value.internalDependencies.isEmpty &&
        value.internalDependencyExclusions.isEmpty && value.failure.exists(_.nonEmpty)
    )
    val presentationValid = !representedAcquired || {
      (value.presentationCompilerContextEntryCount, value.externalDependencyEntryCount) match
        case (Some(total), Some(external)) => total == selected + present + external
        case _ => false
    }
    Either.cond(
      value.configuration == "Compile" &&
        value.internalDependencies.size <= SbtInternalDependencyGraph.MaxDependencyProjects &&
        value.internalDependencyExclusions.size <= SbtInternalDependencyGraph.MaxDependencyProjects &&
        (!representedAcquired || (
          value.internalDependencyDiscoveredCount.contains(value.internalDependencies.size) &&
            value.internalDependencyPresentIncludedCount.contains(present) &&
            value.internalDependencyAbsentNotIncludedCount.contains(absent) &&
            value.internalDependencyUnavailableUnsafeCount.contains(unsafe) &&
            value.internalDependencyExcludedCount.contains(value.internalDependencyExclusions.size)
        )) && contributionsValid && presentationValid && representedStateValid &&
        unsafeAcquiredStateValid && failedStateValid,
      value,
      "point-evidence v5 internal dependency receipt counts or contribution states are inconsistent"
    )

final case class PointEvidenceReportV5(
    schemaVersion: String = PointEvidenceReportV5.SchemaVersion,
    workspace: String,
    sourceFile: String,
    position: PointEvidencePosition,
    discovery: SemanticdbForSourceReportV2,
    targetContext: PointContextEvidenceV5,
    targetSelection: PointTargetSelectionV4,
    livePoint: PointLiveEvidence,
    reconciliation: ReconciliationResultV2
)

object PointEvidenceReportV5:
  val SchemaVersion = "semantic-scala.point-evidence-result.v5"
  given Encoder[PointEvidenceReportV5] = deriveEncoder
  given Decoder[PointEvidenceReportV5] = deriveDecoder[PointEvidenceReportV5].emap { value =>
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
    selectedSnapshot: Option[ArtifactSnapshot],
    includeExistingInternalOutputs: Boolean,
    requireFreshInternalOutputs: Boolean
)

final class PointEvidenceServiceV4 private (
    acquirer: SbtPointContextAcquirer,
    pointQuery: (Path, String, Int, Int, PresentationCompilerContext) => Either[String, SymbolAtResult],
    beforeFinalCheck: () => Unit,
    freshnessAssessor: InternalOutputFreshnessAssessor
):
  def inspect(
      request: SemanticPointEvidenceTargetRequestV4
  ): Either[String, PointEvidenceReportV4] =
    inspectMode(
      request,
      includeExistingInternalOutputs = false,
      requireFreshInternalOutputs = false
    ).map(_._1)

  private[reconciliation] def inspectV5(
      request: SemanticPointEvidenceTargetRequestV5
  ): Either[String, PointEvidenceReportV5] =
    val v4Request = SemanticPointEvidenceTargetRequestV4(
      request.workspace,
      request.sourceFile,
      request.line,
      request.column,
      request.project,
      request.requestedScalaVersion,
      request.targetJava
    )
    inspectMode(
      v4Request,
      includeExistingInternalOutputs = true,
      requireFreshInternalOutputs = false
    ).map { case (report, context, _) =>
      PointEvidenceReportV5(
        workspace = report.workspace,
        sourceFile = report.sourceFile,
        position = report.position,
        discovery = report.discovery,
        targetContext = context,
        targetSelection = report.targetSelection,
        livePoint = report.livePoint,
        reconciliation = report.reconciliation
      )
    }

  private[reconciliation] def inspectV6(
      request: SemanticPointEvidenceTargetRequestV6
  ): Either[String, PointEvidenceReportV6] =
    val v4Request = SemanticPointEvidenceTargetRequestV4(
      request.workspace,
      request.sourceFile,
      request.line,
      request.column,
      request.project,
      request.requestedScalaVersion,
      request.targetJava
    )
    inspectMode(
      v4Request,
      includeExistingInternalOutputs = true,
      requireFreshInternalOutputs = true
    ).map { case (report, _, context) =>
      PointEvidenceReportV6(
        workspace = report.workspace,
        sourceFile = report.sourceFile,
        position = report.position,
        discovery = report.discovery,
        targetContext = context,
        targetSelection = report.targetSelection,
        livePoint = report.livePoint,
        reconciliation = report.reconciliation
      )
    }

  private def inspectMode(
      request: SemanticPointEvidenceTargetRequestV4,
      includeExistingInternalOutputs: Boolean,
      requireFreshInternalOutputs: Boolean
  ): Either[String, (PointEvidenceReportV4, PointContextEvidenceV5, PointContextEvidenceV6)] =
    validate(request).flatMap { validated =>
      val source = SourceSnapshot.capture(validated.sourceFile)
      val acquired = acquirer.acquire(
        SbtPointContextRequest(
          validated.workspace,
          validated.project,
          validated.requestedScalaVersion,
          validated.targetJava,
          includeExistingInternalOutputs,
          requireFreshInternalOutputs
        )
      )
      SemanticdbForSource
        .inspectV2WithSnapshots(validated.workspace, validated.sourceFile, source)
        .map { discovery =>
          val receiptMatches = acquired.toOption.exists(
            receiptMatchesRequest(
              validated,
              _,
              includeExistingInternalOutputs,
              requireFreshInternalOutputs
            )
          )
          val contextSnapshot = acquired.toOption
            .filter(_ => receiptMatches)
            .flatMap(receipt => PointContextSafety
              .capture(
                validated.workspace,
                receipt,
                includeExistingInternalOutputs,
                requireFreshInternalOutputs,
                freshnessAssessor
              )
              .toOption)
          val targetContext = contextEvidence(validated, acquired, receiptMatches, contextSnapshot)
          val targetContextV5 = contextEvidenceV5(validated, acquired, receiptMatches, contextSnapshot)
          val targetContextV6 = contextEvidenceV6(validated, acquired, receiptMatches, contextSnapshot)
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

          val report = complete(
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
              selectedSnapshot,
              includeExistingInternalOutputs,
              requireFreshInternalOutputs
            )
          )
          val strictContextInvalidated = requireFreshInternalOutputs && Set(
            PointTargetSelectionStatusV4.PointContextInputsChangedDuringRequest,
            PointTargetSelectionStatusV4.TargetRootsOrAxisChangedDuringRequest
          ).contains(report.targetSelection.status)
          val finalReport = if strictContextInvalidated then report.copy(
            livePoint = PointLiveEvidence(
              PointLiveStatus.Unavailable,
              None,
              Some("Strict internal-output freshness changed during the request")
            )
          ) else report
          val finalContextV6 = if strictContextInvalidated then targetContextV6.copy(
            pathStatus = TargetRootPathStatusV4.UnavailableUnsafeOrNonUnique,
            classDirectory = None,
            semanticdbTargetRoot = None,
            selectedClassDirectoryStatus = SelectedClassDirectoryStatusV4.UnavailableUnsafe,
            externalDependencyEntryCount = None,
            internalDependencies = Nil,
            internalDependencyExclusions = Nil,
            internalDependencyDiscoveredCount = None,
            internalDependencyFreshIncludedCount = None,
            internalDependencyStaleExcludedCount = None,
            internalDependencyUnverifiableExcludedCount = None,
            internalDependencyAbsentNotIncludedCount = None,
            internalDependencyUnavailableUnsafeCount = None,
            internalDependencyExcludedCount = None,
            presentationCompilerContextEntryCount = None,
            failure = Some("PointContextInputsChangedDuringRequest")
          ) else targetContextV6
          (finalReport, targetContextV5, finalContextV6)
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
      receipt: SbtPointContextReceipt,
      includeExistingInternalOutputs: Boolean,
      requireFreshInternalOutputs: Boolean
  ): Boolean =
    receipt.project == request.project &&
      receipt.configuration == SbtClasspathConfiguration.Compile &&
      receipt.requestedScalaVersion == request.requestedScalaVersion &&
      request.requestedScalaVersion.forall(_ == receipt.effectiveScalaVersion) &&
      receipt.includeExistingInternalOutputs == includeExistingInternalOutputs &&
      receipt.requireFreshInternalOutputs == requireFreshInternalOutputs

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

  private def contextEvidenceV5(
      request: SemanticPointEvidenceTargetRequestV4,
      acquired: Either[SbtPointContextFailure, SbtPointContextReceipt],
      receiptMatches: Boolean,
      snapshot: Option[PointContextSnapshot]
  ): PointContextEvidenceV5 =
    val common = PointContextEvidenceV5(
      project = request.project.value,
      configuration = "Compile",
      status = PointContextAcquisitionStatusV4.AcquisitionFailed,
      requestedScalaVersion = request.requestedScalaVersion.map(_.value),
      effectiveScalaVersion = None,
      scalaAxisStatus = ScalaAxisStatusV4.Unavailable,
      targetJavaContext = None,
      acquisitionProfile = PointContextAcquisitionProfileV5.PartialExistingCompileOutputPointContext,
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
      classpathBasis = PointClasspathBasisV5.ExistingSelectedAndInternalCompileOutputsPlusExternalDependencies,
      externalDependencyEntryCount = None,
      internalDependencies = Nil,
      internalDependencyExclusions = Nil,
      internalDependencyDiscoveredCount = None,
      internalDependencyPresentIncludedCount = None,
      internalDependencyAbsentNotIncludedCount = None,
      internalDependencyUnavailableUnsafeCount = None,
      internalDependencyExcludedCount = None,
      presentationCompilerContextEntryCount = None,
      contextCompleteness = PointContextCompletenessV5.PartialExistingCompileOutputs,
      failure = None
    )
    acquired match
      case Right(receipt) if receiptMatches =>
        val internal = snapshot.toList.flatMap(_.internalDependencies.map(internalEvidence(request, _)))
        val exclusions = receipt.internalDependencyExclusions.map(exclusionEvidence)
        common.copy(
          status = PointContextAcquisitionStatusV4.Acquired,
          effectiveScalaVersion = Some(receipt.effectiveScalaVersion.value),
          scalaAxisStatus = if receipt.requestedScalaVersion.nonEmpty then
            ScalaAxisStatusV4.RequestedMatched
          else ScalaAxisStatusV4.BuildDefault,
          targetJavaContext = receipt.targetJavaContext,
          pathStatus = if snapshot.nonEmpty then TargetRootPathStatusV4.Represented
            else TargetRootPathStatusV4.UnavailableUnsafeOrNonUnique,
          classDirectory = snapshot.map(_.roots.classRelative),
          semanticdbTargetRoot = snapshot.map(_.roots.semanticdbRelative),
          selectedClassDirectoryStatus = snapshot.map(value =>
            if value.classDirectoryIncluded then SelectedClassDirectoryStatusV4.PresentIncluded
            else SelectedClassDirectoryStatusV4.AbsentNotIncluded
          ).getOrElse(SelectedClassDirectoryStatusV4.UnavailableUnsafe),
          externalDependencyEntryCount = snapshot.map(_.externalDependencyEntryCount),
          internalDependencies = internal,
          internalDependencyExclusions = snapshot.fold(List.empty[InternalDependencyExclusionEvidenceV5])(_ => exclusions),
          internalDependencyDiscoveredCount = snapshot.map(_.internalDependencies.size),
          internalDependencyPresentIncludedCount = snapshot.map(_.internalDependencies.count(
            _.status == PointInternalDirectoryStatus.PresentIncluded
          )),
          internalDependencyAbsentNotIncludedCount = snapshot.map(_.internalDependencies.count(
            _.status == PointInternalDirectoryStatus.AbsentNotIncluded
          )),
          internalDependencyUnavailableUnsafeCount = snapshot.map(_.internalDependencies.count(
            _.status == PointInternalDirectoryStatus.UnavailableUnsafe
          )),
          internalDependencyExcludedCount = snapshot.map(_ => exclusions.size),
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
          case SbtPointContextFailure.UnknownProject(_) =>
            PointContextAcquisitionStatusV4.UnknownProject -> ScalaAxisStatusV4.Unavailable
          case SbtPointContextFailure.ScalaSwitch(_) =>
            PointContextAcquisitionStatusV4.ScalaSwitchFailed -> ScalaAxisStatusV4.SwitchFailure
          case SbtPointContextFailure.ScalaVersionMismatch(_) =>
            PointContextAcquisitionStatusV4.ScalaAxisMismatch -> ScalaAxisStatusV4.MismatchFailure
          case _ => PointContextAcquisitionStatusV4.AcquisitionFailed -> ScalaAxisStatusV4.Unavailable
        common.copy(status = status, scalaAxisStatus = axisStatus, failure = Some(failureCode(failure)))

  private def internalEvidence(
      request: SemanticPointEvidenceTargetRequestV4,
      snapshot: PointInternalDependencySnapshot
  ): InternalCompileDependencyEvidenceV5 =
    val status = snapshot.status match
      case PointInternalDirectoryStatus.PresentIncluded => InternalClassDirectoryStatusV5.PresentIncluded
      case PointInternalDirectoryStatus.AbsentNotIncluded => InternalClassDirectoryStatusV5.AbsentNotIncluded
      case PointInternalDirectoryStatus.UnavailableUnsafe => InternalClassDirectoryStatusV5.UnavailableUnsafe
    InternalCompileDependencyEvidenceV5(
      projectRef = snapshot.receipt.projectRef,
      project = snapshot.receipt.project.value,
      role = snapshot.receipt.role,
      compileMapping = snapshot.receipt.compileMapping,
      requestedScalaVersion = snapshot.receipt.requestedScalaVersion.map(_.value),
      effectiveScalaVersion = snapshot.receipt.effectiveScalaVersion.value,
      scalaAxisStatus = if request.requestedScalaVersion.nonEmpty then ScalaAxisStatusV4.RequestedMatched
        else ScalaAxisStatusV4.BuildDefault,
      configuration = "Compile",
      classDirectory = snapshot.classRelative,
      directoryStatus = status,
      contributedToPresentationCompilerContext = status == InternalClassDirectoryStatusV5.PresentIncluded,
      acquisitionEffect = InternalDependencyAcquisitionEffectV5.DependencySourceOutputsNotRequested
    )

  private def contextEvidenceV6(
      request: SemanticPointEvidenceTargetRequestV4,
      acquired: Either[SbtPointContextFailure, SbtPointContextReceipt],
      receiptMatches: Boolean,
      snapshot: Option[PointContextSnapshot]
  ): PointContextEvidenceV6 =
    val common = PointContextEvidenceV6(
      project = request.project.value,
      configuration = "Compile",
      status = PointContextAcquisitionStatusV4.AcquisitionFailed,
      requestedScalaVersion = request.requestedScalaVersion.map(_.value),
      effectiveScalaVersion = None,
      scalaAxisStatus = ScalaAxisStatusV4.Unavailable,
      targetJavaContext = None,
      acquisitionProfile = PointContextAcquisitionProfileV6.StrictFreshInternalCompileOutputPointContext,
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
      classpathBasis = PointClasspathBasisV6.ExistingSelectedAndFreshInternalCompileOutputsPlusExternalDependencies,
      externalDependencyEntryCount = None,
      internalDependencies = Nil,
      internalDependencyExclusions = Nil,
      internalDependencyDiscoveredCount = None,
      internalDependencyFreshIncludedCount = None,
      internalDependencyStaleExcludedCount = None,
      internalDependencyUnverifiableExcludedCount = None,
      internalDependencyAbsentNotIncludedCount = None,
      internalDependencyUnavailableUnsafeCount = None,
      internalDependencyExcludedCount = None,
      presentationCompilerContextEntryCount = None,
      contextCompleteness = PointContextCompletenessV5.PartialExistingCompileOutputs,
      failure = None
    )
    acquired match
      case Right(receipt) if receiptMatches =>
        val internal = snapshot.toList.flatMap(_.internalDependencies.map(internalEvidenceV6(request, _)))
        val exclusions = receipt.internalDependencyExclusions.map(exclusionEvidence)
        common.copy(
          status = PointContextAcquisitionStatusV4.Acquired,
          effectiveScalaVersion = Some(receipt.effectiveScalaVersion.value),
          scalaAxisStatus = if receipt.requestedScalaVersion.nonEmpty then
            ScalaAxisStatusV4.RequestedMatched
          else ScalaAxisStatusV4.BuildDefault,
          targetJavaContext = receipt.targetJavaContext,
          pathStatus = if snapshot.nonEmpty then TargetRootPathStatusV4.Represented
            else TargetRootPathStatusV4.UnavailableUnsafeOrNonUnique,
          classDirectory = snapshot.map(_.roots.classRelative),
          semanticdbTargetRoot = snapshot.map(_.roots.semanticdbRelative),
          selectedClassDirectoryStatus = snapshot.map(value =>
            if value.classDirectoryIncluded then SelectedClassDirectoryStatusV4.PresentIncluded
            else SelectedClassDirectoryStatusV4.AbsentNotIncluded
          ).getOrElse(SelectedClassDirectoryStatusV4.UnavailableUnsafe),
          externalDependencyEntryCount = snapshot.map(_.externalDependencyEntryCount),
          internalDependencies = internal,
          internalDependencyExclusions = snapshot.fold(List.empty[InternalDependencyExclusionEvidenceV5])(_ => exclusions),
          internalDependencyDiscoveredCount = snapshot.map(_.internalDependencies.size),
          internalDependencyFreshIncludedCount = snapshot.map(_.internalDependencies.count(value =>
            value.freshness.exists(_.status == InternalOutputFreshnessStatusV6.Fresh)
          )),
          internalDependencyStaleExcludedCount = snapshot.map(_.internalDependencies.count(value =>
            value.freshness.exists(_.status == InternalOutputFreshnessStatusV6.Stale)
          )),
          internalDependencyUnverifiableExcludedCount = snapshot.map(_.internalDependencies.count(value =>
            value.status == PointInternalDirectoryStatus.PresentIncluded &&
              value.freshness.exists(_.status == InternalOutputFreshnessStatusV6.Unverifiable)
          )),
          internalDependencyAbsentNotIncludedCount = snapshot.map(_.internalDependencies.count(
            _.status == PointInternalDirectoryStatus.AbsentNotIncluded
          )),
          internalDependencyUnavailableUnsafeCount = snapshot.map(_.internalDependencies.count(
            _.status == PointInternalDirectoryStatus.UnavailableUnsafe
          )),
          internalDependencyExcludedCount = snapshot.map(_ => exclusions.size),
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
          case SbtPointContextFailure.UnknownProject(_) =>
            PointContextAcquisitionStatusV4.UnknownProject -> ScalaAxisStatusV4.Unavailable
          case SbtPointContextFailure.ScalaSwitch(_) =>
            PointContextAcquisitionStatusV4.ScalaSwitchFailed -> ScalaAxisStatusV4.SwitchFailure
          case SbtPointContextFailure.ScalaVersionMismatch(_) =>
            PointContextAcquisitionStatusV4.ScalaAxisMismatch -> ScalaAxisStatusV4.MismatchFailure
          case _ => PointContextAcquisitionStatusV4.AcquisitionFailed -> ScalaAxisStatusV4.Unavailable
        common.copy(status = status, scalaAxisStatus = axisStatus, failure = Some(failureCode(failure)))

  private def internalEvidenceV6(
      request: SemanticPointEvidenceTargetRequestV4,
      snapshot: PointInternalDependencySnapshot
  ): InternalCompileDependencyEvidenceV6 =
    val freshness = snapshot.freshness.getOrElse(InternalOutputFreshnessAssessment(
      InternalOutputFreshnessStatusV6.Unverifiable,
      InternalOutputFreshnessReasonV6.AnalysisPathUnavailable,
      None,
      None,
      None
    ))
    val directoryStatus = snapshot.status match
      case PointInternalDirectoryStatus.AbsentNotIncluded => InternalClassDirectoryStatusV6.AbsentNotIncluded
      case PointInternalDirectoryStatus.UnavailableUnsafe => InternalClassDirectoryStatusV6.UnavailableUnsafe
      case PointInternalDirectoryStatus.PresentIncluded => freshness.status match
        case InternalOutputFreshnessStatusV6.Fresh => InternalClassDirectoryStatusV6.PresentFreshIncluded
        case InternalOutputFreshnessStatusV6.Stale => InternalClassDirectoryStatusV6.PresentStaleExcluded
        case InternalOutputFreshnessStatusV6.Unverifiable => InternalClassDirectoryStatusV6.PresentUnverifiableExcluded
    InternalCompileDependencyEvidenceV6(
      projectRef = snapshot.receipt.projectRef,
      project = snapshot.receipt.project.value,
      role = snapshot.receipt.role,
      compileMapping = snapshot.receipt.compileMapping,
      requestedScalaVersion = snapshot.receipt.requestedScalaVersion.map(_.value),
      effectiveScalaVersion = snapshot.receipt.effectiveScalaVersion.value,
      scalaAxisStatus = if request.requestedScalaVersion.nonEmpty then ScalaAxisStatusV4.RequestedMatched
        else ScalaAxisStatusV4.BuildDefault,
      configuration = "Compile",
      classDirectory = snapshot.classRelative,
      analysisFile = freshness.analysisFile,
      directoryStatus = directoryStatus,
      freshnessStatus = freshness.status,
      freshnessReason = freshness.reason,
      recordedSourceCount = freshness.recordedSourceCount,
      recordedProductCount = freshness.recordedProductCount,
      contributedToPresentationCompilerContext =
        directoryStatus == InternalClassDirectoryStatusV6.PresentFreshIncluded,
      acquisitionEffect = InternalDependencyAcquisitionEffectV5.DependencySourceOutputsNotRequested
    )

  private def exclusionEvidence(
      exclusion: SbtInternalDependencyExclusion
  ): InternalDependencyExclusionEvidenceV5 =
    InternalDependencyExclusionEvidenceV5(
      exclusion.projectRef,
      exclusion.project.value,
      exclusion.role,
      exclusion.compileMapping,
      exclusion.reason.toString,
      exclusion.effectiveScalaVersion.map(_.value)
    )

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
      .filter(receiptMatchesRequest(
        request,
        _,
        inspection.includeExistingInternalOutputs,
        inspection.requireFreshInternalOutputs
      ))
      .flatMap(receipt => PointContextSafety.capture(
          request.workspace,
          receipt,
          inspection.includeExistingInternalOutputs,
          inspection.requireFreshInternalOutputs,
          freshnessAssessor,
          inspection.contextSnapshot.map(_.internalDependencies.flatMap(snapshot =>
            snapshot.freshness.map(snapshot.receipt.projectRef -> _)
          ).toMap)
        )
        .toOption)
    val rootsStable = (inspection.contextSnapshot, currentContext) match
      case (Some(before), Some(after)) =>
        before.roots == after.roots &&
          before.internalDependencies.map(_.rootIdentity) == after.internalDependencies.map(_.rootIdentity)
      case (None, None) => true
      case _ => false
    val inputsStable = (inspection.contextSnapshot, currentContext) match
      case (Some(before), Some(after)) =>
        before.inputEvidence == after.inputEvidence &&
          before.freshnessInputEvidence == after.freshnessInputEvidence &&
          before.internalDependencies.map(_.inputIdentity) == after.internalDependencies.map(_.inputIdentity)
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
      () => (),
      InternalOutputFreshnessAssessor.default
    )

  private[reconciliation] def withDependencies(
      acquirer: SbtPointContextAcquirer,
      pointQuery: (Path, String, Int, Int, PresentationCompilerContext) => Either[String, SymbolAtResult],
      beforeFinalCheck: () => Unit,
      freshnessAssessor: InternalOutputFreshnessAssessor = InternalOutputFreshnessAssessor.default
  ): PointEvidenceServiceV4 =
    new PointEvidenceServiceV4(acquirer, pointQuery, beforeFinalCheck, freshnessAssessor)

final class PointEvidenceServiceV5 private (delegate: PointEvidenceServiceV4):
  def inspect(
      request: SemanticPointEvidenceTargetRequestV5
  ): Either[String, PointEvidenceReportV5] = delegate.inspectV5(request)

object PointEvidenceServiceV5:
  def apply(): PointEvidenceServiceV5 =
    val compiler = PresentationCompilerService()
    withDependencies(
      SbtPointContextAcquirer.default,
      (path, source, line, column, context) =>
        compiler.symbolAtSnapshot(path, source, line, column, context),
      () => (),
      InternalOutputFreshnessAssessor.default
    )

  private[reconciliation] def withDependencies(
      acquirer: SbtPointContextAcquirer,
      pointQuery: (Path, String, Int, Int, PresentationCompilerContext) => Either[String, SymbolAtResult],
      beforeFinalCheck: () => Unit,
      freshnessAssessor: InternalOutputFreshnessAssessor = InternalOutputFreshnessAssessor.default
  ): PointEvidenceServiceV5 =
    new PointEvidenceServiceV5(
      PointEvidenceServiceV4.withDependencies(acquirer, pointQuery, beforeFinalCheck, freshnessAssessor)
    )

final class PointEvidenceServiceV6 private (delegate: PointEvidenceServiceV4):
  def inspect(
      request: SemanticPointEvidenceTargetRequestV6
  ): Either[String, PointEvidenceReportV6] = delegate.inspectV6(request)

object PointEvidenceServiceV6:
  def apply(): PointEvidenceServiceV6 =
    val compiler = PresentationCompilerService()
    withDependencies(
      SbtPointContextAcquirer.default,
      (path, source, line, column, context) =>
        compiler.symbolAtSnapshot(path, source, line, column, context),
      () => (),
      InternalOutputFreshnessAssessor.default
    )

  private[reconciliation] def withDependencies(
      acquirer: SbtPointContextAcquirer,
      pointQuery: (Path, String, Int, Int, PresentationCompilerContext) => Either[String, SymbolAtResult],
      beforeFinalCheck: () => Unit,
      freshnessAssessor: InternalOutputFreshnessAssessor
  ): PointEvidenceServiceV6 = new PointEvidenceServiceV6(
    PointEvidenceServiceV4.withDependencies(acquirer, pointQuery, beforeFinalCheck, freshnessAssessor)
  )

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
    internalDependencies: List[PointInternalDependencySnapshot],
    presentationEntries: List[Path],
    inputEvidence: List[SbtClasspathEntryEvidence],
    freshnessInputEvidence: Option[PointFreshnessInputEvidence]
)

private final case class PointFreshnessDirectoryIdentity(
    projectRef: String,
    role: String,
    identity: PointPathIdentity
)

private final case class PointFreshnessFileEvidence(
    projectRef: String,
    path: Path,
    existed: Boolean,
    fileKey: Option[String],
    bytes: Option[Long],
    sha256: Option[String]
)

private final case class PointFreshnessInputEvidence(
    directories: List[PointFreshnessDirectoryIdentity],
    directoryContents: List[SbtClasspathEntryEvidence],
    analysisFiles: List[PointFreshnessFileEvidence]
)

private enum PointInternalDirectoryStatus:
  case PresentIncluded
  case AbsentNotIncluded
  case UnavailableUnsafe

private final case class PointInternalDependencySnapshot(
    receipt: SbtInternalDependencyReceipt,
    identity: Option[PointPathIdentity],
    classRelative: Option[String],
    status: PointInternalDirectoryStatus,
    freshness: Option[InternalOutputFreshnessAssessment] = None
):
  def rootIdentity = (
    receipt.copy(compileAnalysisFile = None, sourceLayout = None),
    identity,
    classRelative,
    status
  )

  def inputIdentity = (receipt.compileAnalysisFile, receipt.sourceLayout, freshness)

private object PointContextSafety:
  def capture(
      workspace: Path,
      receipt: SbtPointContextReceipt,
      includeExistingInternalOutputs: Boolean = false,
      requireFreshInternalOutputs: Boolean = false,
      freshnessAssessor: InternalOutputFreshnessAssessor = InternalOutputFreshnessAssessor.default,
      reusedFreshness: Option[Map[String, InternalOutputFreshnessAssessment]] = None
  ): Either[String, PointContextSnapshot] =
    for
      workspaceReal <- realWorkspace(workspace)
      classIdentity <- safeRoot(workspaceReal, receipt.classDirectory, "class directory")
      semanticdbIdentity <- safeRoot(workspaceReal, receipt.semanticdbTargetRoot, "SemanticDB target root")
      classRelative = relative(workspaceReal, classIdentity.path)
      semanticdbRelative = relative(workspaceReal, semanticdbIdentity.path)
      _ <- Either.cond(
        classIdentity.path != semanticdbIdentity.path,
        (),
        "The selected target output roots are not unique"
      )
      _ <- Either.cond(
        !requireFreshInternalOutputs ||
          (!classRelative.contains('\\') && !semanticdbRelative.contains('\\')),
        (),
        "The selected target output roots cannot be represented safely in point-evidence v6"
      )
      _ <- Either.cond(
        classIdentity.existed == receipt.classDirectoryPresent,
        (),
        "The selected class-directory presence did not match the receipt"
      )
      external <- validateExternal(receipt.externalDependencyClasspath)
      internal <- if includeExistingInternalOutputs then
        captureInternal(
          workspaceReal,
          receipt,
          requireFreshInternalOutputs,
          freshnessAssessor,
          reusedFreshness
        )
      else Right(Nil)
      freshnessEvidence <- if requireFreshInternalOutputs then
        captureFreshnessInputEvidence(workspaceReal, internal).map(Some(_))
      else Right(None)
      internalEntries = internal.collect {
        case PointInternalDependencySnapshot(
              _,
              Some(identity),
              _,
              PointInternalDirectoryStatus.PresentIncluded,
              freshness
            ) if !requireFreshInternalOutputs || freshness.exists(
              _.status == InternalOutputFreshnessStatusV6.Fresh
            ) =>
          SbtClasspathEntry(identity.path, SbtClasspathEntryKind.Directory)
      }
      rawPresentation =
        Option.when(classIdentity.existed)(
          SbtClasspathEntry(classIdentity.path, SbtClasspathEntryKind.Directory)
        ).toList ++ internalEntries ++ external
      _ <- Either.cond(
        !includeExistingInternalOutputs || rawPresentation.map(_.path).distinct.size == rawPresentation.size,
        (),
        "The selected, internal, and external point-context entries are not unique"
      )
      presentation = distinctEntries(rawPresentation)
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
        classRelative,
        semanticdbRelative
      ),
      classIdentity.existed,
      external.size,
      internal,
      presentation.map(_.path),
      evidence,
      freshnessEvidence
    )

  private def captureInternal(
      workspaceReal: Path,
      receipt: SbtPointContextReceipt,
      requireFreshInternalOutputs: Boolean,
      freshnessAssessor: InternalOutputFreshnessAssessor,
      reusedFreshness: Option[Map[String, InternalOutputFreshnessAssessment]]
  ): Either[String, List[PointInternalDependencySnapshot]] =
    for
      _ <- Either.cond(
        receipt.internalDependencies.map(_.projectRef).distinct.size == receipt.internalDependencies.size,
        (),
        "The internal dependency project receipts are not unique"
      )
      rawSnapshots <- receipt.internalDependencies.foldLeft[
        Either[String, List[PointInternalDependencySnapshot]]
      ](Right(Nil)) { (result, dependency) =>
        result.flatMap { values =>
          validateInternal(receipt, dependency).map { _ =>
            val snapshot = safeRoot(
              workspaceReal,
              dependency.classDirectory,
              s"internal Compile class directory for ${dependency.projectRef}"
            ) match
              case Right(identity) if identity.existed == dependency.classDirectoryPresent &&
                  (!requireFreshInternalOutputs || !relative(workspaceReal, identity.path).contains('\\')) =>
                PointInternalDependencySnapshot(
                  dependency,
                  Some(identity),
                  Some(relative(workspaceReal, identity.path)),
                  if identity.existed then PointInternalDirectoryStatus.PresentIncluded
                  else PointInternalDirectoryStatus.AbsentNotIncluded,
                  None
                )
              case _ =>
                PointInternalDependencySnapshot(
                  dependency,
                  None,
                  None,
                  PointInternalDirectoryStatus.UnavailableUnsafe,
                  Option.when(requireFreshInternalOutputs)(InternalOutputFreshnessAssessment(
                    InternalOutputFreshnessStatusV6.Unverifiable,
                    InternalOutputFreshnessReasonV6.DependencyClassDirectoryUnsafe,
                    None,
                    None,
                    None
                  ))
                )
            values :+ snapshot
          }
        }
      }
      assessmentRequests = rawSnapshots.collect { case snapshot if snapshot.identity.nonEmpty =>
        val dependency = snapshot.receipt
        InternalOutputFreshnessRequest(
          dependency.projectRef,
          workspaceReal,
          dependency.effectiveScalaVersion.value,
          dependency.classDirectory,
          dependency.compileAnalysisFile,
          dependency.sourceLayout
        )
      }
      assessments <- if !requireFreshInternalOutputs then Right(Map.empty)
      else reusedFreshness match
        case Some(values) => Either.cond(
          values.keySet == rawSnapshots.map(_.receipt.projectRef).toSet,
          values,
          "The prior internal-output freshness batch could not be reused exactly"
        )
        case None => Right(freshnessAssessor.assessBatch(assessmentRequests))
      snapshots = rawSnapshots.map { snapshot =>
        if !requireFreshInternalOutputs || snapshot.freshness.nonEmpty then snapshot
        else snapshot.copy(freshness = assessments.get(snapshot.receipt.projectRef))
      }
    yield snapshots

  private def captureFreshnessInputEvidence(
      workspaceReal: Path,
      snapshots: List[PointInternalDependencySnapshot]
  ): Either[String, PointFreshnessInputEvidence] =
    val directoryRequests = snapshots.flatMap { snapshot =>
      val dependency = snapshot.receipt
      val sourceDirectories = dependency.sourceLayout.toList.flatMap(layout =>
        (layout.sourceDirectories ++ layout.unmanagedSourceDirectories ++ layout.managedSourceDirectories).distinct
      )
      (dependency.projectRef, "class-directory", dependency.classDirectory) ::
        sourceDirectories.map(path => (dependency.projectRef, "source-directory", path))
    }
    for
      directories <- directoryRequests.foldLeft[
        Either[String, List[PointFreshnessDirectoryIdentity]]
      ](Right(Nil)) { case (result, (projectRef, role, path)) =>
        result.flatMap(values => safeRoot(workspaceReal, path, s"freshness $role").map(identity =>
          values :+ PointFreshnessDirectoryIdentity(
            projectRef,
            role,
            identity
          )
        ))
      }
      existingDirectories = directories.map(_.identity).filter(_.existed).map(_.path).distinct
      contents <- if existingDirectories.isEmpty then Right(Nil)
      else SbtClasspathEvidenceCollector.default.collectEntries(existingDirectories.map(path =>
        SbtClasspathEntry(path, SbtClasspathEntryKind.Directory)
      )).left.map(_ => "The internal freshness input directories could not be captured within bounds")
      analysis <- captureAnalysisEvidence(workspaceReal, snapshots)
    yield PointFreshnessInputEvidence(directories, contents, analysis)

  private def captureAnalysisEvidence(
      workspaceReal: Path,
      snapshots: List[PointInternalDependencySnapshot]
  ): Either[String, List[PointFreshnessFileEvidence]] =
    snapshots.foldLeft[Either[String, (List[PointFreshnessFileEvidence], Long)]](Right(Nil -> 0L)) {
      (result, snapshot) => result.flatMap { case (values, total) =>
        snapshot.receipt.compileAnalysisFile match
          case None => Right(values -> total)
          case Some(candidate) => safeRegularFileEvidence(
            workspaceReal,
            snapshot.receipt.projectRef,
            candidate
          ).flatMap { evidence =>
            val updated = Math.addExact(total, evidence.bytes.getOrElse(0L))
            Either.cond(
              updated <= 256L * 1024L * 1024L,
              (values :+ evidence) -> updated,
              "The internal freshness analysis inputs exceeded their aggregate byte bound"
            )
          }
      }
    }.map(_._1)

  private def safeRegularFileEvidence(
      workspaceReal: Path,
      projectRef: String,
      candidate: Path
  ): Either[String, PointFreshnessFileEvidence] =
    try
      val normalized = candidate.toAbsolutePath.normalize()
      if !normalized.startsWith(workspaceReal) || containsSymlink(workspaceReal, normalized) then
        Left("An internal freshness analysis input was outside the safe workspace boundary")
      else if !Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) then
        Right(PointFreshnessFileEvidence(projectRef, normalized, false, None, None, None))
      else if !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) then
        Left("An internal freshness analysis input was not a regular file")
      else
        val real = normalized.toRealPath()
        val before = Files.readAttributes(real, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
        val size = before.size()
        if size > 64L * 1024L * 1024L then
          Left("An internal freshness analysis input exceeded its byte bound")
        else
          val digest = MessageDigest.getInstance("SHA-256")
          val stream = Files.newInputStream(real)
          val buffer = Array.ofDim[Byte](64 * 1024)
          var readTotal = 0L
          try
            var read = stream.read(buffer)
            while read >= 0 do
              if read > 0 then
                readTotal = Math.addExact(readTotal, read.toLong)
                if readTotal > 64L * 1024L * 1024L then
                  throw IllegalStateException("analysis input bound exceeded while reading")
                digest.update(buffer, 0, read)
              read = stream.read(buffer)
          finally stream.close()
          val after = Files.readAttributes(real, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
          if readTotal != size || after.size() != size || before.fileKey() != after.fileKey() then
            Left("An internal freshness analysis input changed while it was captured")
          else Right(PointFreshnessFileEvidence(
            projectRef,
            real,
            true,
            Option(after.fileKey()).map(_.toString),
            Some(size),
            Some(digest.digest().map(value => f"${value & 0xff}%02x").mkString)
          ))
    catch case _: Exception => Left("An internal freshness analysis input could not be captured safely")

  private def validateInternal(
      selected: SbtPointContextReceipt,
      dependency: SbtInternalDependencyReceipt
  ): Either[String, Unit] =
    Either.cond(
      dependency.projectRef == s"ThisBuild/${dependency.project.value}" &&
        dependency.configuration == SbtClasspathConfiguration.Compile &&
        dependency.compileMapping.admitted &&
        dependency.requestedScalaVersion == selected.requestedScalaVersion &&
        dependency.effectiveScalaVersion == selected.effectiveScalaVersion,
      (),
      s"The internal dependency receipt for ${dependency.projectRef} is not an admitted same-axis Compile output"
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
