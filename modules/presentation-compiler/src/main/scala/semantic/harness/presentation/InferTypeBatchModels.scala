package semantic.harness.presentation

import io.circe.Decoder
import io.circe.Encoder
import io.circe.HCursor
import io.circe.parser
import io.circe.syntax.*
import java.nio.charset.StandardCharsets

object InferTypeBatchBounds:
  val MaxRequestFileBytes: Long = 4L * 1024 * 1024
  val MaxRequests: Int = 128
  val MaxRequestIdBytes: Int = 128
  val MaxFilePathBytes: Int = 16 * 1024
  val MaxSourceFileBytes: Long = 8L * 1024 * 1024
  val MaxRenderingBytes: Int = 256 * 1024
  val MaxMessageBytes: Int = 16 * 1024
  val MaxDistinctSourceFiles: Int = 128
  val MaxEncodedOutputBytes: Long = 32L * 1024 * 1024

  def utf8Bytes(value: String): Int =
    value.getBytes(StandardCharsets.UTF_8).length

  def withinUtf8(value: String, maximum: Int): Boolean =
    utf8Bytes(value) <= maximum

final case class InferTypeBatchRequestItem(
    id: String,
    file: String,
    line: Int,
    column: Int
)

object InferTypeBatchRequestItem:
  private val Fields = Set("id", "file", "line", "column")

  given Encoder[InferTypeBatchRequestItem] = Encoder.forProduct4(
    "id",
    "file",
    "line",
    "column"
  )(item => (item.id, item.file, item.line, item.column))

  given Decoder[InferTypeBatchRequestItem] = Decoder.instance { cursor =>
    rejectUnknown(cursor, Fields, "batch request item").flatMap { _ =>
      for
        id <- cursor.get[String]("id")
        file <- cursor.get[String]("file")
        line <- cursor.get[Int]("line")
        column <- cursor.get[Int]("column")
      yield InferTypeBatchRequestItem(id, file, line, column)
    }
  }

  private def rejectUnknown(
      cursor: HCursor,
      allowed: Set[String],
      label: String
  ): Decoder.Result[Unit] =
    val unknown = cursor.keys.getOrElse(Iterable.empty).toSet.diff(allowed)
    if unknown.isEmpty then Right(())
    else Left(io.circe.DecodingFailure(s"Unknown $label field(s): ${unknown.toList.sorted.mkString(", ")}", cursor.history))

final case class InferTypeBatchRequest(
    schemaVersion: String = InferTypeBatchRequest.SchemaVersion,
    requests: List[InferTypeBatchRequestItem]
)

object InferTypeBatchRequest:
  val SchemaVersion = "semantic-scala.infer-type-batch-request.v1"
  private val Fields = Set("schemaVersion", "requests")
  private val IdPattern = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}".r

  given Encoder[InferTypeBatchRequest] =
    Encoder.forProduct2("schemaVersion", "requests")(request =>
      (request.schemaVersion, request.requests)
    )

  given Decoder[InferTypeBatchRequest] = Decoder.instance { cursor =>
    val unknown = cursor.keys.getOrElse(Iterable.empty).toSet.diff(Fields)
    if unknown.nonEmpty then
      Left(
        io.circe.DecodingFailure(
          s"Unknown batch request field(s): ${unknown.toList.sorted.mkString(", ")}",
          cursor.history
        )
      )
    else
      for
        schemaVersion <- cursor.get[String]("schemaVersion")
        requests <- cursor.get[List[InferTypeBatchRequestItem]]("requests")
      yield InferTypeBatchRequest(schemaVersion, requests)
  }

  def decodeAndValidate(json: String): Either[String, InferTypeBatchRequest] =
    parser
      .decode[InferTypeBatchRequest](json)
      .left
      .map(_ => "Malformed infer-type batch request JSON")
      .flatMap(validate)

  def validate(request: InferTypeBatchRequest): Either[String, InferTypeBatchRequest] =
    if request.schemaVersion != SchemaVersion then
      Left(s"Unsupported infer-type batch request schema: ${request.schemaVersion}")
    else if request.requests.isEmpty then
      Left("Infer-type batch requests must be nonempty")
    else if request.requests.size > InferTypeBatchBounds.MaxRequests then
      Left(s"Infer-type batch request count exceeds ${InferTypeBatchBounds.MaxRequests}")
    else
      request.requests.foldLeft[Either[String, Set[String]]](Right(Set.empty)) {
        (validated, item) =>
          validated.flatMap { ids =>
            val idBytes = InferTypeBatchBounds.utf8Bytes(item.id)
            val fileBytes = InferTypeBatchBounds.utf8Bytes(item.file)
            if !IdPattern.matches(item.id) then
              Left("Infer-type batch request ID has an invalid format")
            else if idBytes > InferTypeBatchBounds.MaxRequestIdBytes then
              Left(s"Infer-type batch request ID exceeds ${InferTypeBatchBounds.MaxRequestIdBytes} UTF-8 bytes")
            else if ids.contains(item.id) then
              Left(s"Duplicate infer-type batch request ID: ${item.id}")
            else if item.file.isEmpty then
              Left("Infer-type batch request file must be nonempty")
            else if fileBytes > InferTypeBatchBounds.MaxFilePathBytes then
              Left(s"Infer-type batch request file exceeds ${InferTypeBatchBounds.MaxFilePathBytes} UTF-8 bytes")
            else if item.line <= 0 then
              Left("Infer-type batch request line must be positive")
            else if item.column <= 0 then
              Left("Infer-type batch request column must be positive")
            else Right(ids + item.id)
          }
      }.map(_ => request)

enum InferTypeBatchItemStatus:
  case Resolved
  case Unresolved
  case InvalidRequest
  case QueryFailure

object InferTypeBatchItemStatus:
  val ResolvedValue = "Resolved"
  val UnresolvedValue = "Unresolved"
  val InvalidRequestValue = "InvalidRequest"
  val QueryFailureValue = "QueryFailure"

  def value(status: InferTypeBatchItemStatus): String =
    status match
      case InferTypeBatchItemStatus.Resolved       => ResolvedValue
      case InferTypeBatchItemStatus.Unresolved     => UnresolvedValue
      case InferTypeBatchItemStatus.InvalidRequest => InvalidRequestValue
      case InferTypeBatchItemStatus.QueryFailure   => QueryFailureValue

  given Encoder[InferTypeBatchItemStatus] = Encoder.encodeString.contramap(value)
  given Decoder[InferTypeBatchItemStatus] = Decoder.decodeString.emap {
    case ResolvedValue       => Right(InferTypeBatchItemStatus.Resolved)
    case UnresolvedValue     => Right(InferTypeBatchItemStatus.Unresolved)
    case InvalidRequestValue => Right(InferTypeBatchItemStatus.InvalidRequest)
    case QueryFailureValue   => Right(InferTypeBatchItemStatus.QueryFailure)
    case other               => Left(s"Unknown infer-type batch item status: $other")
  }

final case class InferTypeBatchItemResult(
    index: Int,
    id: String,
    status: InferTypeBatchItemStatus,
    rendering: Option[String],
    renderingKind: InferTypeRenderingKind,
    source: String,
    position: InferTypePublicPosition,
    range: Option[SourceRange],
    warnings: List[String],
    message: Option[String]
)

object InferTypeBatchItemResult:
  given Encoder[InferTypeBatchItemResult] =
    Encoder
      .forProduct10(
        "index",
        "id",
        "status",
        "rendering",
        "renderingKind",
        "source",
        "position",
        "range",
        "warnings",
        "message"
      )((item: InferTypeBatchItemResult) =>
        (
          item.index,
          item.id,
          item.status,
          item.rendering,
          item.renderingKind,
          item.source,
          item.position,
          item.range,
          item.warnings,
          item.message
        )
      )
      .mapJson(_.dropNullValues)

  given Decoder[InferTypeBatchItemResult] = Decoder.forProduct10(
    "index",
    "id",
    "status",
    "rendering",
    "renderingKind",
    "source",
    "position",
    "range",
    "warnings",
    "message"
  )(InferTypeBatchItemResult.apply)

final case class InferTypeBatchReport(
    schemaVersion: String = InferTypeBatchReport.SchemaVersion,
    requestCount: Int,
    context: InferTypeContextSummary,
    contextWarnings: List[String],
    results: List[InferTypeBatchItemResult]
)

object InferTypeBatchReport:
  val SchemaVersion = "semantic-scala.infer-type-batch-result.v1"

  given Encoder[InferTypeBatchReport] = Encoder.forProduct5(
    "schemaVersion",
    "requestCount",
    "context",
    "contextWarnings",
    "results"
  )(report =>
    (
      report.schemaVersion,
      report.requestCount,
      report.context,
      report.contextWarnings,
      report.results
    )
  )

  given Decoder[InferTypeBatchReport] = Decoder.forProduct5(
    "schemaVersion",
    "requestCount",
    "context",
    "contextWarnings",
    "results"
  )(InferTypeBatchReport.apply)

  def encodeBounded(report: InferTypeBatchReport): Either[String, String] =
    val encoded = report.asJson.noSpaces
    if encoded.getBytes(StandardCharsets.UTF_8).length > InferTypeBatchBounds.MaxEncodedOutputBytes then
      Left(s"Infer-type batch output exceeds ${InferTypeBatchBounds.MaxEncodedOutputBytes} bytes")
    else Right(encoded)
