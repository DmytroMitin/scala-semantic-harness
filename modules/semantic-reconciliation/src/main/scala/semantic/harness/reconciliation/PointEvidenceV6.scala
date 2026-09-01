package semantic.harness.reconciliation

import io.circe.Decoder
import io.circe.Encoder
import io.circe.Json
import io.circe.generic.semiauto.deriveDecoder
import io.circe.generic.semiauto.deriveEncoder
import io.circe.syntax.*
import java.nio.file.Path
import semantic.harness.sbt_runner.SbtCompileDependencyMapping
import semantic.harness.sbt_runner.SbtInternalDependencyGraph
import semantic.harness.sbt_runner.SbtInternalDependencyRole
import semantic.harness.sbt_runner.SbtProjectId
import semantic.harness.sbt_runner.SbtScalaVersion
import semantic.harness.sbt_runner.ValidatedSbtJavaHome

final case class SemanticPointEvidenceTargetRequestV6(
    workspace: Path,
    sourceFile: Path,
    line: Int,
    column: Int,
    project: SbtProjectId,
    requestedScalaVersion: Option[SbtScalaVersion] = None,
    targetJava: Option[ValidatedSbtJavaHome] = None
)

enum PointContextAcquisitionProfileV6:
  case StrictFreshInternalCompileOutputPointContext

object PointContextAcquisitionProfileV6:
  given Encoder[PointContextAcquisitionProfileV6] = Encoder.encodeString.contramap(_.toString)
  given Decoder[PointContextAcquisitionProfileV6] = closedEnum("point context acquisition profile v6", values)

enum PointClasspathBasisV6:
  case ExistingSelectedAndFreshInternalCompileOutputsPlusExternalDependencies

object PointClasspathBasisV6:
  given Encoder[PointClasspathBasisV6] = Encoder.encodeString.contramap(_.toString)
  given Decoder[PointClasspathBasisV6] = closedEnum("point classpath basis v6", values)

enum InternalClassDirectoryStatusV6:
  case PresentFreshIncluded
  case PresentStaleExcluded
  case PresentUnverifiableExcluded
  case AbsentNotIncluded
  case UnavailableUnsafe

object InternalClassDirectoryStatusV6:
  given Encoder[InternalClassDirectoryStatusV6] = Encoder.encodeString.contramap(_.toString)
  given Decoder[InternalClassDirectoryStatusV6] = closedEnum("internal class directory status v6", values)

final case class InternalCompileDependencyEvidenceV6(
    projectRef: String,
    project: String,
    role: SbtInternalDependencyRole,
    compileMapping: SbtCompileDependencyMapping,
    requestedScalaVersion: Option[String],
    effectiveScalaVersion: String,
    scalaAxisStatus: ScalaAxisStatusV4,
    configuration: String,
    classDirectory: Option[String],
    analysisFile: Option[String],
    directoryStatus: InternalClassDirectoryStatusV6,
    freshnessStatus: InternalOutputFreshnessStatusV6,
    freshnessReason: InternalOutputFreshnessReasonV6,
    recordedSourceCount: Option[Int],
    recordedProductCount: Option[Int],
    contributedToPresentationCompilerContext: Boolean,
    acquisitionEffect: InternalDependencyAcquisitionEffectV5
)

object InternalCompileDependencyEvidenceV6:
  import InternalCompileDependencyEvidenceV5.given
  given Encoder[InternalCompileDependencyEvidenceV6] = deriveEncoder
  given Decoder[InternalCompileDependencyEvidenceV6] = deriveDecoder[InternalCompileDependencyEvidenceV6].emap(validate)

  private def validate(
      value: InternalCompileDependencyEvidenceV6
  ): Either[String, InternalCompileDependencyEvidenceV6] =
    val projectValid = SbtProjectId.parse(value.project).isRight &&
      value.projectRef == s"ThisBuild/${value.project}"
    val axisValid = value.scalaAxisStatus match
      case ScalaAxisStatusV4.BuildDefault => value.requestedScalaVersion.isEmpty
      case ScalaAxisStatusV4.RequestedMatched =>
        value.requestedScalaVersion.contains(value.effectiveScalaVersion)
      case _ => false
    val pathsValid = value.directoryStatus match
      case InternalClassDirectoryStatusV6.UnavailableUnsafe => value.classDirectory.isEmpty
      case _ => value.classDirectory.exists(isSafeV6Path)
    val analysisValid = value.analysisFile.forall(isSafeV6Path)
    val countsValid = List(value.recordedSourceCount, value.recordedProductCount).flatten.forall(_ >= 0)
    val matrixValid = value.freshnessStatus match
      case InternalOutputFreshnessStatusV6.Fresh =>
        value.freshnessReason == InternalOutputFreshnessReasonV6.SourceAndProductContentMatch &&
          value.directoryStatus == InternalClassDirectoryStatusV6.PresentFreshIncluded &&
          value.contributedToPresentationCompilerContext && value.analysisFile.nonEmpty &&
          value.recordedSourceCount.exists(_ > 0) && value.recordedProductCount.exists(_ > 0)
      case InternalOutputFreshnessStatusV6.Stale =>
        Set(
          InternalOutputFreshnessReasonV6.SourceContentMismatch,
          InternalOutputFreshnessReasonV6.ProductContentMismatch
        ).contains(value.freshnessReason) &&
          value.directoryStatus == InternalClassDirectoryStatusV6.PresentStaleExcluded &&
          value.analysisFile.nonEmpty && value.recordedSourceCount.exists(_ > 0) &&
          value.recordedProductCount.exists(_ > 0) && !value.contributedToPresentationCompilerContext
      case InternalOutputFreshnessStatusV6.Unverifiable =>
        val reasonMatchesDirectory = value.directoryStatus match
          case InternalClassDirectoryStatusV6.PresentUnverifiableExcluded =>
            value.freshnessReason match
              case InternalOutputFreshnessReasonV6.AnalysisPathUnavailable =>
                value.recordedSourceCount.isEmpty && value.recordedProductCount.isEmpty
              case InternalOutputFreshnessReasonV6.AnalysisFileMissing |
                  InternalOutputFreshnessReasonV6.UnsupportedAnalysisFormatOrVersion |
                  InternalOutputFreshnessReasonV6.CorruptOrUnreadableAnalysis =>
                value.analysisFile.nonEmpty && value.recordedSourceCount.isEmpty &&
                  value.recordedProductCount.isEmpty
              case InternalOutputFreshnessReasonV6.SourceInventoryIncompleteOrUnbounded |
                  InternalOutputFreshnessReasonV6.ProductInventoryIncompleteOrUnbounded |
                  InternalOutputFreshnessReasonV6.MissingExpectedProduct |
                  InternalOutputFreshnessReasonV6.UnsafeSourceOrProductPath |
                  InternalOutputFreshnessReasonV6.GeneratedOrManagedSourceUnbounded |
                  InternalOutputFreshnessReasonV6.ScalaAxisMismatch |
                  InternalOutputFreshnessReasonV6.SourceProductRelationsInconsistent =>
                value.analysisFile.nonEmpty && value.recordedSourceCount.nonEmpty &&
                  value.recordedProductCount.nonEmpty
              case _ => false
          case InternalClassDirectoryStatusV6.AbsentNotIncluded =>
            value.freshnessReason == InternalOutputFreshnessReasonV6.DependencyClassDirectoryAbsent &&
              value.recordedSourceCount.isEmpty && value.recordedProductCount.isEmpty
          case InternalClassDirectoryStatusV6.UnavailableUnsafe =>
            value.freshnessReason == InternalOutputFreshnessReasonV6.DependencyClassDirectoryUnsafe &&
              value.analysisFile.isEmpty
          case _ => false
        reasonMatchesDirectory && !value.contributedToPresentationCompilerContext
    Either.cond(
      projectValid && value.compileMapping.admitted && axisValid &&
        value.effectiveScalaVersion.nonEmpty && value.configuration == "Compile" &&
        pathsValid && analysisValid && countsValid && matrixValid,
      value,
      "point-evidence v6 internal dependency freshness fields are inconsistent"
    )

  private def isSafeV6Path(value: String): Boolean =
    !value.contains('\\') && InternalCompileDependencyEvidenceV5.isRelativePublicPath(value)

final case class PointContextEvidenceV6(
    project: String,
    configuration: String,
    status: PointContextAcquisitionStatusV4,
    requestedScalaVersion: Option[String],
    effectiveScalaVersion: Option[String],
    scalaAxisStatus: ScalaAxisStatusV4,
    targetJavaContext: Option[String],
    acquisitionProfile: PointContextAcquisitionProfileV6,
    acquisitionEffect: PointContextAcquisitionEffectV4,
    buildPerformed: TargetBuildPerformedV4,
    possibleEffects: List[String],
    pathStatus: TargetRootPathStatusV4,
    classDirectory: Option[String],
    semanticdbTargetRoot: Option[String],
    selectedClassDirectoryStatus: SelectedClassDirectoryStatusV4,
    compiledOutputFreshness: CompiledOutputFreshnessV4,
    classpathBasis: PointClasspathBasisV6,
    externalDependencyEntryCount: Option[Int],
    internalDependencies: List[InternalCompileDependencyEvidenceV6],
    internalDependencyExclusions: List[InternalDependencyExclusionEvidenceV5],
    internalDependencyDiscoveredCount: Option[Int],
    internalDependencyFreshIncludedCount: Option[Int],
    internalDependencyStaleExcludedCount: Option[Int],
    internalDependencyUnverifiableExcludedCount: Option[Int],
    internalDependencyAbsentNotIncludedCount: Option[Int],
    internalDependencyUnavailableUnsafeCount: Option[Int],
    internalDependencyExcludedCount: Option[Int],
    presentationCompilerContextEntryCount: Option[Int],
    contextCompleteness: PointContextCompletenessV5,
    failure: Option[String]
)

object PointContextEvidenceV6:
  import InternalCompileDependencyEvidenceV5.given
  import InternalDependencyExclusionEvidenceV5.given
  given Encoder[PointContextEvidenceV6] = Encoder.instance { value =>
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
      "internalDependencyFreshIncludedCount" -> value.internalDependencyFreshIncludedCount.asJson,
      "internalDependencyStaleExcludedCount" -> value.internalDependencyStaleExcludedCount.asJson,
      "internalDependencyUnverifiableExcludedCount" -> value.internalDependencyUnverifiableExcludedCount.asJson,
      "internalDependencyAbsentNotIncludedCount" -> value.internalDependencyAbsentNotIncludedCount.asJson,
      "internalDependencyUnavailableUnsafeCount" -> value.internalDependencyUnavailableUnsafeCount.asJson,
      "internalDependencyExcludedCount" -> value.internalDependencyExcludedCount.asJson,
      "presentationCompilerContextEntryCount" -> value.presentationCompilerContextEntryCount.asJson,
      "contextCompleteness" -> value.contextCompleteness.asJson,
      "failure" -> value.failure.asJson
    )
  }
  given Decoder[PointContextEvidenceV6] = Decoder.instance { cursor =>
    for
      project <- cursor.get[String]("project")
      configuration <- cursor.get[String]("configuration")
      status <- cursor.get[PointContextAcquisitionStatusV4]("status")
      requestedScalaVersion <- cursor.get[Option[String]]("requestedScalaVersion")
      effectiveScalaVersion <- cursor.get[Option[String]]("effectiveScalaVersion")
      scalaAxisStatus <- cursor.get[ScalaAxisStatusV4]("scalaAxisStatus")
      targetJavaContext <- cursor.get[Option[String]]("targetJavaContext")
      acquisitionProfile <- cursor.get[PointContextAcquisitionProfileV6]("acquisitionProfile")
      acquisitionEffect <- cursor.get[PointContextAcquisitionEffectV4]("acquisitionEffect")
      buildPerformed <- cursor.get[TargetBuildPerformedV4]("buildPerformed")
      possibleEffects <- cursor.get[List[String]]("possibleEffects")
      pathStatus <- cursor.get[TargetRootPathStatusV4]("pathStatus")
      classDirectory <- cursor.get[Option[String]]("classDirectory")
      semanticdbTargetRoot <- cursor.get[Option[String]]("semanticdbTargetRoot")
      selectedClassDirectoryStatus <- cursor.get[SelectedClassDirectoryStatusV4]("selectedClassDirectoryStatus")
      compiledOutputFreshness <- cursor.get[CompiledOutputFreshnessV4]("compiledOutputFreshness")
      classpathBasis <- cursor.get[PointClasspathBasisV6]("classpathBasis")
      externalDependencyEntryCount <- cursor.get[Option[Int]]("externalDependencyEntryCount")
      internalDependencies <- cursor.get[List[InternalCompileDependencyEvidenceV6]]("internalDependencies")
      internalDependencyExclusions <- cursor.get[List[InternalDependencyExclusionEvidenceV5]]("internalDependencyExclusions")
      internalDependencyDiscoveredCount <- cursor.get[Option[Int]]("internalDependencyDiscoveredCount")
      internalDependencyFreshIncludedCount <- cursor.get[Option[Int]]("internalDependencyFreshIncludedCount")
      internalDependencyStaleExcludedCount <- cursor.get[Option[Int]]("internalDependencyStaleExcludedCount")
      internalDependencyUnverifiableExcludedCount <- cursor.get[Option[Int]]("internalDependencyUnverifiableExcludedCount")
      internalDependencyAbsentNotIncludedCount <- cursor.get[Option[Int]]("internalDependencyAbsentNotIncludedCount")
      internalDependencyUnavailableUnsafeCount <- cursor.get[Option[Int]]("internalDependencyUnavailableUnsafeCount")
      internalDependencyExcludedCount <- cursor.get[Option[Int]]("internalDependencyExcludedCount")
      presentationCompilerContextEntryCount <- cursor.get[Option[Int]]("presentationCompilerContextEntryCount")
      contextCompleteness <- cursor.get[PointContextCompletenessV5]("contextCompleteness")
      failure <- cursor.get[Option[String]]("failure")
    yield PointContextEvidenceV6(
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
      internalDependencyFreshIncludedCount,
      internalDependencyStaleExcludedCount,
      internalDependencyUnverifiableExcludedCount,
      internalDependencyAbsentNotIncludedCount,
      internalDependencyUnavailableUnsafeCount,
      internalDependencyExcludedCount,
      presentationCompilerContextEntryCount,
      contextCompleteness,
      failure
    )
  }.emap(validate)

  private def validate(value: PointContextEvidenceV6): Either[String, PointContextEvidenceV6] =
    val represented = value.status == PointContextAcquisitionStatusV4.Acquired &&
      value.pathStatus == TargetRootPathStatusV4.Represented
    val unavailable = !represented
    val fresh = value.internalDependencies.count(
      _.directoryStatus == InternalClassDirectoryStatusV6.PresentFreshIncluded
    )
    val stale = value.internalDependencies.count(
      _.directoryStatus == InternalClassDirectoryStatusV6.PresentStaleExcluded
    )
    val unverifiable = value.internalDependencies.count(
      _.directoryStatus == InternalClassDirectoryStatusV6.PresentUnverifiableExcluded
    )
    val absent = value.internalDependencies.count(
      _.directoryStatus == InternalClassDirectoryStatusV6.AbsentNotIncluded
    )
    val unsafe = value.internalDependencies.count(
      _.directoryStatus == InternalClassDirectoryStatusV6.UnavailableUnsafe
    )
    val countValues = List(
      value.externalDependencyEntryCount,
      value.internalDependencyDiscoveredCount,
      value.internalDependencyFreshIncludedCount,
      value.internalDependencyStaleExcludedCount,
      value.internalDependencyUnverifiableExcludedCount,
      value.internalDependencyAbsentNotIncludedCount,
      value.internalDependencyUnavailableUnsafeCount,
      value.internalDependencyExcludedCount,
      value.presentationCompilerContextEntryCount
    )
    val effectsValid = value.possibleEffects == List(
      "BuildDefinitionOrPluginLoading",
      "DependencyResolution",
      "MetadataOrCacheWrites"
    )
    val javaContextValid = value.targetJavaContext.forall(_.matches("[0-9a-f]{64}"))
    val countsNonnegative = countValues.flatten.forall(_ >= 0)
    val selected = if value.selectedClassDirectoryStatus == SelectedClassDirectoryStatusV4.PresentIncluded then 1 else 0
    val axisValid = value.status match
      case PointContextAcquisitionStatusV4.Acquired => value.scalaAxisStatus match
        case ScalaAxisStatusV4.BuildDefault =>
          value.requestedScalaVersion.isEmpty && value.effectiveScalaVersion.exists(_.nonEmpty)
        case ScalaAxisStatusV4.RequestedMatched =>
          value.requestedScalaVersion.nonEmpty && value.requestedScalaVersion == value.effectiveScalaVersion
        case _ => false
      case PointContextAcquisitionStatusV4.ScalaSwitchFailed =>
        value.requestedScalaVersion.exists(_.nonEmpty) && value.effectiveScalaVersion.isEmpty &&
          value.scalaAxisStatus == ScalaAxisStatusV4.SwitchFailure
      case PointContextAcquisitionStatusV4.ScalaAxisMismatch =>
        value.requestedScalaVersion.exists(_.nonEmpty) &&
          value.scalaAxisStatus == ScalaAxisStatusV4.MismatchFailure
      case PointContextAcquisitionStatusV4.AcquisitionFailed |
          PointContextAcquisitionStatusV4.UnknownProject =>
        value.effectiveScalaVersion.isEmpty && value.scalaAxisStatus == ScalaAxisStatusV4.Unavailable
    val representedValid = !represented || (
      value.classDirectory.exists(isSafeV6Path) &&
        value.semanticdbTargetRoot.exists(isSafeV6Path) &&
        value.failure.isEmpty && countValues.forall(_.nonEmpty) &&
        value.internalDependencyDiscoveredCount.contains(value.internalDependencies.size) &&
        value.internalDependencyFreshIncludedCount.contains(fresh) &&
        value.internalDependencyStaleExcludedCount.contains(stale) &&
        value.internalDependencyUnverifiableExcludedCount.contains(unverifiable) &&
        value.internalDependencyAbsentNotIncludedCount.contains(absent) &&
        value.internalDependencyUnavailableUnsafeCount.contains(unsafe) &&
        value.internalDependencyExcludedCount.contains(value.internalDependencyExclusions.size) &&
        value.presentationCompilerContextEntryCount.exists(total =>
          total > 0 && total == selected + fresh + value.externalDependencyEntryCount.getOrElse(-1)
        )
    )
    val unavailableValid = !unavailable || (
      value.pathStatus == TargetRootPathStatusV4.UnavailableUnsafeOrNonUnique &&
        value.classDirectory.isEmpty && value.semanticdbTargetRoot.isEmpty &&
        value.selectedClassDirectoryStatus == SelectedClassDirectoryStatusV4.UnavailableUnsafe &&
        value.internalDependencies.isEmpty && value.internalDependencyExclusions.isEmpty &&
        countValues.forall(_.isEmpty) && value.failure.exists(_.nonEmpty)
    )
    Either.cond(
      SbtProjectId.parse(value.project).isRight && value.configuration == "Compile" &&
        effectsValid && javaContextValid && countsNonnegative &&
        axisValid &&
        value.internalDependencies.size <= SbtInternalDependencyGraph.MaxDependencyProjects &&
        value.internalDependencyExclusions.size <= SbtInternalDependencyGraph.MaxDependencyProjects &&
        representedValid && unavailableValid,
      value,
      "point-evidence v6 freshness counts or top-level state are inconsistent"
    )

  private def isSafeV6Path(value: String): Boolean =
    !value.contains('\\') && InternalCompileDependencyEvidenceV5.isRelativePublicPath(value)

final case class PointEvidenceReportV6(
    schemaVersion: String = PointEvidenceReportV6.SchemaVersion,
    workspace: String,
    sourceFile: String,
    position: PointEvidencePosition,
    discovery: semantic.harness.semanticdb_reader.SemanticdbForSourceReportV2,
    targetContext: PointContextEvidenceV6,
    targetSelection: PointTargetSelectionV4,
    livePoint: PointLiveEvidence,
    reconciliation: ReconciliationResultV2
)

object PointEvidenceReportV6:
  val SchemaVersion = "semantic-scala.point-evidence-result.v6"
  given Encoder[PointEvidenceReportV6] = deriveEncoder
  given Decoder[PointEvidenceReportV6] = deriveDecoder[PointEvidenceReportV6].emap { value =>
    Either.cond(
      value.schemaVersion == SchemaVersion,
      value,
      s"point-evidence schemaVersion must be $SchemaVersion"
    )
  }

private def closedEnum[A](name: String, values: Array[A]): Decoder[A] =
  Decoder.decodeString.emap(value => values.find(_.toString == value).toRight(s"Invalid $name: $value"))
