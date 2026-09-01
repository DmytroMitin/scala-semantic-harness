package semantic.harness.reconciliation

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Base64
import scala.util.Try
import semantic.harness.sbt_runner.SbtInternalSourceLayoutReceipt

private[reconciliation] object ZincFreshnessWorkerIdentity:
  val Protocol = "semantic-scala-zinc-freshness-worker.v1"
  val ScalaVersion = "2.13.18"
  val ZincVersion = "1.12.1"
  val JnaVersion = "5.14.0"
  val DependencyArtifactCount = 42
  val DependencyArtifactBytes = 38241533L
  val DependencyInventorySha256 = "c5abc92c5a51596c8f13142370bc2cdd1081d7c6e08e4fddbe3c83d6767fee36"
  val DependencyContentInventorySha256 = "a95bb11bdb1e92b5a294767e24d9699f6dac9da328d419c8a8f7d9dfe97523d4"
  val WorkerJarSha256 = "cffcddbee0795d436664185a0ca0e3206a6542b5d01bd0e487da3da23cbcfd69"

private[reconciliation] final case class ZincFreshnessWorkerInput(
    callerId: String,
    workspace: Path,
    effectiveScalaVersion: String,
    classDirectory: Path,
    analysisFile: Path,
    sourceLayout: SbtInternalSourceLayoutReceipt
)

private[reconciliation] object ZincFreshnessWorkerProtocol:
  val RequestMarker = ZincFreshnessWorkerIdentity.Protocol + ".request"
  val ResponseMarker = ZincFreshnessWorkerIdentity.Protocol + ".response"
  val MaxProtocolBytes = 1024 * 1024
  val MaxFieldBytes = 4 * 1024
  val MaxInputs = 128
  val MaxRecordedFiles = 50000

  def encodeRequest(inputs: List[ZincFreshnessWorkerInput]): Either[String, Array[Byte]] =
    if inputs.isEmpty || inputs.size > MaxInputs || inputs.map(_.callerId).distinct.size != inputs.size then
      Left("invalid worker input cardinality")
    else
      val lines = RequestMarker :: inputs.flatMap { input =>
        val item = List(
          "item",
          input.callerId,
          encode(input.workspace.toString),
          input.effectiveScalaVersion,
          encode(input.classDirectory.toString),
          encode(input.analysisFile.toString),
          input.sourceLayout.sourceGeneratorCount.toString
        ).mkString("\t")
        val roots = List(
          "all" -> input.sourceLayout.sourceDirectories,
          "unmanaged" -> input.sourceLayout.unmanagedSourceDirectories,
          "managed" -> input.sourceLayout.managedSourceDirectories
        ).flatMap { case (kind, paths) =>
          paths.map(path => List("source", input.callerId, kind, encode(path.toString)).mkString("\t"))
        }
        item :: roots
      }
      val rawFields = lines.flatMap(_.split("\t", -1))
      val bytes = (lines.mkString("\n") + "\n").getBytes(StandardCharsets.UTF_8)
      Either.cond(
        rawFields.forall(_.getBytes(StandardCharsets.UTF_8).length <= MaxFieldBytes) &&
          bytes.length <= MaxProtocolBytes,
        bytes,
        "worker request exceeds a fixed bound"
      )

  def decodeResponse(
      bytes: Array[Byte],
      expectedIds: Set[String]
  ): Either[String, Map[String, InternalOutputFreshnessAssessment]] =
    if bytes.length > MaxProtocolBytes || expectedIds.isEmpty || expectedIds.size > MaxInputs then
      Left("worker response exceeds a fixed bound")
    else
      val lines = String(bytes, StandardCharsets.UTF_8).linesIterator.toList
      if lines.headOption != Some(ResponseMarker) then Left("worker protocol version mismatch")
      else
        lines.tail.foldLeft[Either[String, Map[String, InternalOutputFreshnessAssessment]]](Right(Map.empty)) {
          (result, line) => result.flatMap { values =>
            val fields = line.split("\t", -1).toList
            if fields.exists(_.getBytes(StandardCharsets.UTF_8).length > MaxFieldBytes) then
              Left("worker response field exceeds a fixed bound")
            else fields match
              case "result" :: id :: statusRaw :: reasonRaw :: sourceRaw :: productRaw :: Nil
                  if expectedIds.contains(id) && !values.contains(id) =>
                for
                  status <- InternalOutputFreshnessStatusV6.values.find(_.toString == statusRaw)
                    .toRight("invalid worker freshness status")
                  reason <- InternalOutputFreshnessReasonV6.values.find(_.toString == reasonRaw)
                    .toRight("invalid worker freshness reason")
                  sourceCount <- count(sourceRaw)
                  productCount <- count(productRaw)
                  assessment = InternalOutputFreshnessAssessment(status, reason, None, sourceCount, productCount)
                  _ <- Either.cond(validAssessment(assessment), (), "invalid worker assessment")
                yield values.updated(id, assessment)
              case _ => Left("malformed, duplicate, or unknown worker result")
          }
        }.flatMap(values => Either.cond(
          values.keySet == expectedIds,
          values,
          "partial worker response"
        ))

  private def encode(value: String): String =
    Base64.getUrlEncoder.withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8))

  private def count(value: String): Either[String, Option[Int]] =
    if value == "-" then Right(None)
    else value.toIntOption.filter(value => value >= 0 && value <= MaxRecordedFiles)
      .map(Some(_)).toRight("invalid worker count")

  private def validAssessment(value: InternalOutputFreshnessAssessment): Boolean = value.status match
    case InternalOutputFreshnessStatusV6.Fresh =>
      value.reason == InternalOutputFreshnessReasonV6.SourceAndProductContentMatch &&
        value.recordedSourceCount.exists(_ > 0) && value.recordedProductCount.exists(_ > 0)
    case InternalOutputFreshnessStatusV6.Stale =>
      Set(
        InternalOutputFreshnessReasonV6.SourceContentMismatch,
        InternalOutputFreshnessReasonV6.ProductContentMismatch
      ).contains(value.reason) && value.recordedSourceCount.exists(_ > 0) &&
        value.recordedProductCount.exists(_ > 0)
    case InternalOutputFreshnessStatusV6.Unverifiable =>
      val withoutCounts = Set(
        InternalOutputFreshnessReasonV6.AnalysisPathUnavailable,
        InternalOutputFreshnessReasonV6.AnalysisFileMissing,
        InternalOutputFreshnessReasonV6.UnsupportedAnalysisFormatOrVersion,
        InternalOutputFreshnessReasonV6.CorruptOrUnreadableAnalysis
      )
      val withCounts = Set(
        InternalOutputFreshnessReasonV6.SourceInventoryIncompleteOrUnbounded,
        InternalOutputFreshnessReasonV6.ProductInventoryIncompleteOrUnbounded,
        InternalOutputFreshnessReasonV6.MissingExpectedProduct,
        InternalOutputFreshnessReasonV6.UnsafeSourceOrProductPath,
        InternalOutputFreshnessReasonV6.GeneratedOrManagedSourceUnbounded,
        InternalOutputFreshnessReasonV6.ScalaAxisMismatch,
        InternalOutputFreshnessReasonV6.SourceProductRelationsInconsistent
      )
      (withoutCounts.contains(value.reason) && value.recordedSourceCount.isEmpty &&
        value.recordedProductCount.isEmpty) ||
        (withCounts.contains(value.reason) && value.recordedSourceCount.exists(_ > 0) &&
          value.recordedProductCount.exists(_ > 0))
