package semantic.harness.core

import io.circe.Decoder
import io.circe.Encoder
import io.circe.generic.semiauto.deriveDecoder
import io.circe.generic.semiauto.deriveEncoder

final case class DiagnosticPosition(
  file: String,
  line: Int,
  column: Int
)

object DiagnosticPosition:
  given Encoder[DiagnosticPosition] = deriveEncoder
  given Decoder[DiagnosticPosition] = deriveDecoder

final case class Diagnostic(
  severity: String,
  message: String,
  position: Option[DiagnosticPosition]
)

object Diagnostic:
  given Encoder[Diagnostic] = deriveEncoder
  given Decoder[Diagnostic] = deriveDecoder

final case class CompileReport(
  schemaVersion: String = CompileReport.SchemaVersion,
  success: Boolean,
  diagnostics: List[Diagnostic]
)

object CompileReport:
  val SchemaVersion: String = "semantic-scala.compile-result.v1"
  val ErrorsSchemaVersion: String = "semantic-scala.errors-result.v1"

  given Encoder[CompileReport] = deriveEncoder
  given Decoder[CompileReport] = Decoder.instance { cursor =>
    for
      schemaVersion <- cursor.downField("schemaVersion").as[Option[String]]
      success <- cursor.downField("success").as[Boolean]
      diagnostics <- cursor.downField("diagnostics").as[List[Diagnostic]]
    yield CompileReport(
      schemaVersion = schemaVersion.getOrElse(SchemaVersion),
      success = success,
      diagnostics = diagnostics
    )
  }

final case class TestReport(
  schemaVersion: String = TestReport.SchemaVersion,
  success: Boolean,
  total: Int,
  passed: Int,
  failed: Int,
  failures: List[Diagnostic]
)

object TestReport:
  val SchemaVersion: String = "semantic-scala.test-result.v1"

  given Encoder[TestReport] = deriveEncoder
  given Decoder[TestReport] = Decoder.instance { cursor =>
    for
      schemaVersion <- cursor.downField("schemaVersion").as[Option[String]]
      success <- cursor.downField("success").as[Boolean]
      total <- cursor.downField("total").as[Int]
      passed <- cursor.downField("passed").as[Int]
      failed <- cursor.downField("failed").as[Int]
      failures <- cursor.downField("failures").as[List[Diagnostic]]
    yield TestReport(
      schemaVersion = schemaVersion.getOrElse(SchemaVersion),
      success = success,
      total = total,
      passed = passed,
      failed = failed,
      failures = failures
    )
  }
