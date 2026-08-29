package semantic.harness.sbt_runner

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Base64
import scala.util.Try

object SbtSourceMappingRootProtocol:
  val Format = "semantic-scala.internal-source-mapping-root-receipt.v1"
  val MaxProtocolBytes = 16 * 1024

  private val Required = Set(
    "project",
    "configuration",
    "effectiveScalaVersion",
    "classDirectory",
    "semanticdbTargetRoot"
  )
  private val Allowed = Required ++ Set("requestedScalaVersion", "javaContext")

  def render(receipt: SbtSourceMappingRootReceipt): String =
    val fields = List(
      Format,
      s"project\t${encode(receipt.project.value)}",
      s"configuration\t${SbtClasspathConfiguration.value(receipt.configuration)}"
    ) ++ receipt.requestedScalaVersion.map(axis =>
      s"requestedScalaVersion\t${encode(axis.value)}"
    ) ++ List(
      s"effectiveScalaVersion\t${encode(receipt.effectiveScalaVersion.value)}",
      s"classDirectory\t${encode(receipt.classDirectory.toString)}",
      s"semanticdbTargetRoot\t${encode(receipt.semanticdbTargetRoot.toString)}"
    ) ++ receipt.targetJavaContext.map(value => s"javaContext\t${encode(value)}")
    fields.mkString("\n") + "\n"

  def parse(
      value: String,
      request: SbtSourceMappingRootRequest
  ): Either[String, SbtSourceMappingRootReceipt] =
    if value.getBytes(StandardCharsets.UTF_8).length > MaxProtocolBytes then
      Left("Invalid sbt source-mapping root receipt: protocol exceeds byte limit")
    else
      val lines = value.linesIterator.toList
      if lines.headOption != Some(Format) then
        Left("Invalid sbt source-mapping root receipt: unsupported protocol marker")
      else
        parseLines(lines.drop(1)).flatMap { fields =>
          val missing = Required.diff(fields.keySet)
          if missing.nonEmpty then
            Left("Invalid sbt source-mapping root receipt: required field is missing")
          else
            for
              projectValue <- decode(fields("project"))
              _ <- require(
                projectValue == request.project.value,
                "project mismatch"
              )
              _ <- require(
                fields("configuration") == SbtClasspathConfiguration.CompileValue,
                "configuration mismatch"
              )
              requested <- fields.get("requestedScalaVersion") match
                case Some(encoded) => decodeAxis(encoded).map(Some(_))
                case None => Right(None)
              _ <- require(
                requested == request.requestedScalaVersion,
                "requested Scala version mismatch"
              )
              effective <- decodeAxis(fields("effectiveScalaVersion"))
              _ <- request.requestedScalaVersion match
                case Some(axis) => require(
                    effective == axis,
                    s"effective Scala version '${effective.value}' does not match requested '${axis.value}'"
                  )
                case None => Right(())
              classDirectory <- decodePath(fields("classDirectory"), "class directory")
              semanticdbTargetRoot <- decodePath(
                fields("semanticdbTargetRoot"),
                "SemanticDB target root"
              )
              javaContext <- fields.get("javaContext") match
                case Some(encoded) => decode(encoded).map(Some(_))
                case None => Right(None)
              _ <- require(
                javaContext == request.targetJava.map(SbtJavaContext.token),
                "target Java context mismatch"
              )
            yield SbtSourceMappingRootReceipt(
              request.project,
              SbtClasspathConfiguration.Compile,
              requested,
              effective,
              classDirectory,
              semanticdbTargetRoot,
              javaContext
            )
        }

  private def parseLines(lines: List[String]): Either[String, Map[String, String]] =
    lines.foldLeft[Either[String, Map[String, String]]](Right(Map.empty)) {
      (result, line) =>
        result.flatMap { fields =>
          line.split("\t", -1).toList match
            case key :: fieldValue :: Nil if Allowed.contains(key) && !fields.contains(key) =>
              Right(fields.updated(key, fieldValue))
            case key :: _ if fields.contains(key) =>
              Left(s"Invalid sbt source-mapping root receipt: duplicate field $key")
            case _ =>
              Left("Invalid sbt source-mapping root receipt: unknown or malformed field")
        }
    }

  private def require(condition: Boolean, message: String): Either[String, Unit] =
    Either.cond(condition, (), s"Invalid sbt source-mapping root receipt: $message")

  private def encode(value: String): String =
    Base64.getEncoder.encodeToString(value.getBytes(StandardCharsets.UTF_8))

  private def decode(value: String): Either[String, String] =
    Try(String(Base64.getDecoder.decode(value), StandardCharsets.UTF_8)).toEither.left.map(_ =>
      "Invalid sbt source-mapping root receipt: malformed base64"
    )

  private def decodeAxis(value: String): Either[String, SbtScalaVersion] =
    decode(value).flatMap(SbtScalaVersion.parse).left.map(message =>
      s"Invalid sbt source-mapping root receipt: $message"
    )

  private def decodePath(value: String, description: String): Either[String, Path] =
    decode(value).flatMap(decoded =>
      Try(Path.of(decoded).toAbsolutePath.normalize()).toEither.left.map(_ =>
        s"Invalid sbt source-mapping root receipt: $description path is invalid"
      )
    )
