package semantic.harness.presentation

import io.circe.Decoder
import io.circe.Encoder
import io.circe.generic.semiauto.deriveDecoder
import io.circe.generic.semiauto.deriveEncoder
import java.nio.file.Path

final case class SourceRange(
  startLine: Int,
  startCharacter: Int,
  endLine: Int,
  endCharacter: Int
)

object SourceRange:
  given Encoder[SourceRange] = deriveEncoder
  given Decoder[SourceRange] = deriveDecoder

final case class SymbolAtResult(
  schemaVersion: String = SymbolAtResult.SchemaVersion,
  symbol: Option[String],
  displayName: Option[String],
  range: Option[SourceRange],
  source: String
)

object SymbolAtResult:
  val SchemaVersion: String = "semantic-scala.symbol-at-result.v1"

  given Encoder[SymbolAtResult] = deriveEncoder
  given Decoder[SymbolAtResult] = Decoder.instance { cursor =>
    for
      schemaVersion <- cursor.downField("schemaVersion").as[Option[String]]
      symbol <- cursor.downField("symbol").as[Option[String]]
      displayName <- cursor.downField("displayName").as[Option[String]]
      range <- cursor.downField("range").as[Option[SourceRange]]
      source <- cursor.downField("source").as[String]
    yield SymbolAtResult(
      schemaVersion = schemaVersion.getOrElse(SchemaVersion),
      symbol = symbol,
      displayName = displayName,
      range = range,
      source = source
    )
  }

enum InferTypeStatus:
  case Resolved
  case Unresolved

object InferTypeStatus:
  val ResolvedValue = "Resolved"
  val UnresolvedValue = "Unresolved"

  def value(status: InferTypeStatus): String =
    status match
      case InferTypeStatus.Resolved   => ResolvedValue
      case InferTypeStatus.Unresolved => UnresolvedValue

  given Encoder[InferTypeStatus] = Encoder.encodeString.contramap(value)
  given Decoder[InferTypeStatus] = Decoder.decodeString.emap { value =>
    value match
      case ResolvedValue   => Right(InferTypeStatus.Resolved)
      case UnresolvedValue => Right(InferTypeStatus.Unresolved)
      case other           => Left(s"Unknown infer-type status: $other")
  }

enum InferTypeRenderingKind:
  case ExpressionType
  case SymbolSignature
  case HoverCode
  case NoRendering

object InferTypeRenderingKind:
  val ExpressionTypeValue = "ExpressionType"
  val SymbolSignatureValue = "SymbolSignature"
  val HoverCodeValue = "HoverCode"
  val NoRenderingValue = "NoRendering"

  def value(kind: InferTypeRenderingKind): String =
    kind match
      case InferTypeRenderingKind.ExpressionType   => ExpressionTypeValue
      case InferTypeRenderingKind.SymbolSignature => SymbolSignatureValue
      case InferTypeRenderingKind.HoverCode        => HoverCodeValue
      case InferTypeRenderingKind.NoRendering      => NoRenderingValue

  given Encoder[InferTypeRenderingKind] = Encoder.encodeString.contramap(value)
  given Decoder[InferTypeRenderingKind] = Decoder.decodeString.emap { value =>
    value match
      case ExpressionTypeValue   => Right(InferTypeRenderingKind.ExpressionType)
      case SymbolSignatureValue => Right(InferTypeRenderingKind.SymbolSignature)
      case HoverCodeValue        => Right(InferTypeRenderingKind.HoverCode)
      case NoRenderingValue      => Right(InferTypeRenderingKind.NoRendering)
      case other                 => Left(s"Unknown infer-type rendering kind: $other")
  }

enum InferTypeContextKind:
  case NarrowRuntime
  case ExplicitClasspath
  case SbtClasspath

object InferTypeContextKind:
  val NarrowRuntimeValue = "NarrowRuntime"
  val ExplicitClasspathValue = "ExplicitClasspath"
  val SbtClasspathValue = "SbtClasspath"

  def value(kind: InferTypeContextKind): String =
    kind match
      case InferTypeContextKind.NarrowRuntime      => NarrowRuntimeValue
      case InferTypeContextKind.ExplicitClasspath => ExplicitClasspathValue
      case InferTypeContextKind.SbtClasspath      => SbtClasspathValue

  given Encoder[InferTypeContextKind] = Encoder.encodeString.contramap(value)
  given Decoder[InferTypeContextKind] = Decoder.decodeString.emap { value =>
    value match
      case NarrowRuntimeValue      => Right(InferTypeContextKind.NarrowRuntime)
      case ExplicitClasspathValue => Right(InferTypeContextKind.ExplicitClasspath)
      case SbtClasspathValue      => Right(InferTypeContextKind.SbtClasspath)
      case other                  => Left(s"Unknown infer-type context kind: $other")
  }

enum InferTypeAcquisitionOrigin:
  case FreshSbt
  case CachedExplicitReuse

object InferTypeAcquisitionOrigin:
  val FreshSbtValue = "FreshSbt"
  val CachedExplicitReuseValue = "CachedExplicitReuse"

  def value(origin: InferTypeAcquisitionOrigin): String =
    origin match
      case InferTypeAcquisitionOrigin.FreshSbt             => FreshSbtValue
      case InferTypeAcquisitionOrigin.CachedExplicitReuse => CachedExplicitReuseValue

  given Encoder[InferTypeAcquisitionOrigin] =
    Encoder.encodeString.contramap(value)
  given Decoder[InferTypeAcquisitionOrigin] = Decoder.decodeString.emap {
    case FreshSbtValue             => Right(InferTypeAcquisitionOrigin.FreshSbt)
    case CachedExplicitReuseValue => Right(InferTypeAcquisitionOrigin.CachedExplicitReuse)
    case other                     => Left(s"Unknown infer-type acquisition origin: $other")
  }

enum InferTypeFreshnessAssessment:
  case FreshBySbtEvaluation
  case ReusedWithMatchingEvidence

object InferTypeFreshnessAssessment:
  val FreshBySbtEvaluationValue = "FreshBySbtEvaluation"
  val ReusedWithMatchingEvidenceValue = "ReusedWithMatchingEvidence"

  def value(assessment: InferTypeFreshnessAssessment): String =
    assessment match
      case InferTypeFreshnessAssessment.FreshBySbtEvaluation =>
        FreshBySbtEvaluationValue
      case InferTypeFreshnessAssessment.ReusedWithMatchingEvidence =>
        ReusedWithMatchingEvidenceValue

  given Encoder[InferTypeFreshnessAssessment] =
    Encoder.encodeString.contramap(value)
  given Decoder[InferTypeFreshnessAssessment] = Decoder.decodeString.emap {
    case FreshBySbtEvaluationValue =>
      Right(InferTypeFreshnessAssessment.FreshBySbtEvaluation)
    case ReusedWithMatchingEvidenceValue =>
      Right(InferTypeFreshnessAssessment.ReusedWithMatchingEvidence)
    case other => Left(s"Unknown infer-type freshness assessment: $other")
  }

final case class InferTypeQueryPosition(
  line: Int,
  column: Int
)

object InferTypeQueryPosition:
  given Encoder[InferTypeQueryPosition] = deriveEncoder
  given Decoder[InferTypeQueryPosition] = deriveDecoder

enum PresentationCompilerClasspath:
  case NarrowRuntime
  case Explicit(entries: List[Path])

final case class PresentationCompilerContext(
  classpath: PresentationCompilerClasspath = PresentationCompilerClasspath.NarrowRuntime,
  workspace: Option[Path] = None
)

object PresentationCompilerContext:
  def explicit(classpathEntries: List[Path], workspace: Option[Path] = None): PresentationCompilerContext =
    PresentationCompilerContext(PresentationCompilerClasspath.Explicit(classpathEntries), workspace)

  def validate(context: PresentationCompilerContext): Either[String, PresentationCompilerContext] =
    val workspace = context.workspace.map(_.toAbsolutePath.normalize())
    validateWorkspace(workspace).flatMap { _ =>
      context.classpath match
        case PresentationCompilerClasspath.NarrowRuntime =>
          Right(PresentationCompilerContext(PresentationCompilerClasspath.NarrowRuntime, workspace))
        case PresentationCompilerClasspath.Explicit(entries) =>
          if entries.isEmpty then Left("Explicit classpath must contain at least one entry")
          else
            validateEntries(normalizeDistinct(entries)).map { normalized =>
              PresentationCompilerContext(PresentationCompilerClasspath.Explicit(normalized), workspace)
            }
    }

  private def validateEntries(entries: List[Path]): Either[String, List[Path]] =
    entries.foldLeft[Either[String, List[Path]]](Right(Nil)) { (validated, path) =>
      validated.flatMap { result =>
        if !java.nio.file.Files.exists(path) then Left(s"Classpath entry does not exist: $path")
        else if java.nio.file.Files.isDirectory(path) then Right(result :+ path)
        else if java.nio.file.Files.isRegularFile(path) &&
            path.getFileName.toString.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")
        then
          Right(result :+ path)
        else if java.nio.file.Files.isRegularFile(path) then
          Left(s"Classpath entry is not a JAR file or directory: $path")
        else Left(s"Classpath entry is neither a JAR file nor directory: $path")
      }
    }

  private def validateWorkspace(workspace: Option[Path]): Either[String, Unit] =
    workspace match
      case Some(path) if !java.nio.file.Files.exists(path) =>
        Left(s"Workspace does not exist: $path")
      case Some(path) if !java.nio.file.Files.isDirectory(path) =>
        Left(s"Workspace is not a directory: $path")
      case _ =>
        Right(())

  private def normalizeDistinct(paths: List[Path]): List[Path] =
    paths.foldLeft(List.empty[Path]) { (result, path) =>
      val normalized = path.toAbsolutePath.normalize()
      if result.contains(normalized) then result else result :+ normalized
    }

final case class InferTypeRequest(
  file: Path,
  line: Int,
  column: Int,
  context: PresentationCompilerContext = PresentationCompilerContext()
)

final case class InferTypeResult(
  status: InferTypeStatus,
  rendering: Option[String],
  renderingKind: InferTypeRenderingKind,
  source: String,
  position: InferTypeQueryPosition,
  range: Option[SourceRange],
  rawCompilerRendering: Option[String],
  contextKind: InferTypeContextKind,
  classpathEntryCount: Int,
  workspaceProvided: Boolean,
  warnings: List[String]
)

object InferTypeResult:
  given Encoder[InferTypeResult] = deriveEncoder
  given Decoder[InferTypeResult] = deriveDecoder

final case class InferTypePublicPosition(
  line: Int,
  column: Int,
  encoding: String
)

object InferTypePublicPosition:
  val Utf16Encoding = "UTF-16"

  given Encoder[InferTypePublicPosition] = deriveEncoder
  given Decoder[InferTypePublicPosition] = deriveDecoder

final case class InferTypeContextSummary(
  kind: InferTypeContextKind,
  classpathEntryCount: Int,
  workspaceProvided: Boolean,
  sbtProject: Option[String] = None,
  sbtConfiguration: Option[String] = None,
  acquisitionOrigin: Option[InferTypeAcquisitionOrigin] = None,
  freshnessAssessment: Option[InferTypeFreshnessAssessment] = None
)

object InferTypeContextSummary:
  given Encoder[InferTypeContextSummary] =
    deriveEncoder[InferTypeContextSummary].mapJson(_.dropNullValues)
  given Decoder[InferTypeContextSummary] = deriveDecoder

final case class InferTypeReport(
  schemaVersion: String = InferTypeReport.SchemaVersion,
  status: InferTypeStatus,
  rendering: Option[String],
  renderingKind: InferTypeRenderingKind,
  source: String,
  position: InferTypePublicPosition,
  range: Option[SourceRange],
  context: InferTypeContextSummary,
  warnings: List[String]
)

object InferTypeReport:
  val SchemaVersion = "semantic-scala.infer-type-result.v1"

  def from(result: InferTypeResult): InferTypeReport =
    InferTypeReport(
      status = result.status,
      rendering = result.rendering,
      renderingKind = result.renderingKind,
      source = result.source,
      position = InferTypePublicPosition(
        line = result.position.line,
        column = result.position.column,
        encoding = InferTypePublicPosition.Utf16Encoding
      ),
      range = result.range,
      context = InferTypeContextSummary(
        kind = result.contextKind,
        classpathEntryCount = result.classpathEntryCount,
        workspaceProvided = result.workspaceProvided,
        sbtProject = None,
        sbtConfiguration = None,
        acquisitionOrigin = None,
        freshnessAssessment = None
      ),
      warnings = result.warnings
    )

  given Encoder[InferTypeReport] = deriveEncoder
  given Decoder[InferTypeReport] = deriveDecoder
