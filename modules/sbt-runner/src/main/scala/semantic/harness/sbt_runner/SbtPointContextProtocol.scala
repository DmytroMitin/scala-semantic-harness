package semantic.harness.sbt_runner

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Base64
import scala.util.Try

object SbtPointContextProtocol:
  val Format = "semantic-scala.internal-point-context-receipt.v1"
  val MaxProtocolBytes = 64 * 1024

  private val Required = Set(
    "project",
    "configuration",
    "effectiveScalaVersion",
    "classDirectory",
    "semanticdbTargetRoot",
    "classDirectoryPresent"
  )
  private val Allowed = Required ++ Set("requestedScalaVersion", "javaContext")

  def render(receipt: SbtPointContextReceipt): String =
    val fields = List(
      Format,
      s"project\t${encode(receipt.project.value)}",
      s"configuration\t${SbtClasspathConfiguration.value(receipt.configuration)}"
    ) ++ receipt.requestedScalaVersion.map(axis =>
      s"requestedScalaVersion\t${encode(axis.value)}"
    ) ++ List(
      s"effectiveScalaVersion\t${encode(receipt.effectiveScalaVersion.value)}",
      s"classDirectory\t${encode(receipt.classDirectory.toString)}",
      s"semanticdbTargetRoot\t${encode(receipt.semanticdbTargetRoot.toString)}",
      s"classDirectoryPresent\t${receipt.classDirectoryPresent}"
    ) ++ receipt.targetJavaContext.map(value => s"javaContext\t${encode(value)}")
    val entries = receipt.externalDependencyClasspath.map(entry =>
      s"entry\t${SbtClasspathEntryKind.value(entry.kind)}\t${encode(entry.path.toString)}"
    )
    (fields ++ entries).mkString("\n") + "\n"

  def parse(
      value: String,
      request: SbtPointContextRequest
  ): Either[String, SbtPointContextReceipt] =
    if value.getBytes(StandardCharsets.UTF_8).length > MaxProtocolBytes then
      Left("Invalid sbt point-context receipt: protocol exceeds byte limit")
    else
      val lines = value.linesIterator.toList
      if lines.headOption != Some(Format) then
        Left("Invalid sbt point-context receipt: unsupported protocol marker")
      else
        parseLines(lines.drop(1)).flatMap { case (fields, entries) =>
          val missing = Required.diff(fields.keySet)
          if missing.nonEmpty then
            Left("Invalid sbt point-context receipt: required field is missing")
          else
            for
              projectValue <- decode(fields("project"))
              _ <- require(projectValue == request.project.value, "project mismatch")
              _ <- require(
                fields("configuration") == SbtClasspathConfiguration.CompileValue,
                "configuration mismatch"
              )
              requested <- fields.get("requestedScalaVersion") match
                case Some(encoded) => decodeAxis(encoded).map(Some(_))
                case None => Right(None)
              _ <- require(requested == request.requestedScalaVersion, "requested Scala version mismatch")
              effective <- decodeAxis(fields("effectiveScalaVersion"))
              _ <- request.requestedScalaVersion match
                case Some(axis) => require(
                    effective == axis,
                    s"effective Scala version '${effective.value}' does not match requested '${axis.value}'"
                  )
                case None => Right(())
              classDirectory <- decodePath(fields("classDirectory"), "class directory")
              semanticdbTargetRoot <- decodePath(fields("semanticdbTargetRoot"), "SemanticDB target root")
              classDirectoryPresent <- fields("classDirectoryPresent") match
                case "true" => Right(true)
                case "false" => Right(false)
                case _ => Left("Invalid sbt point-context receipt: class directory presence is invalid")
              javaContext <- fields.get("javaContext") match
                case Some(encoded) => decode(encoded).map(Some(_))
                case None => Right(None)
              _ <- require(
                javaContext == request.targetJava.map(SbtJavaContext.token),
                "target Java context mismatch"
              )
              classpath <- entries.foldRight[Either[String, List[SbtClasspathEntry]]](Right(Nil)) {
                (entry, result) =>
                  for
                    validated <- SbtClasspathEntry.validate(entry).left.map(message =>
                      s"Invalid sbt point-context receipt: $message"
                    )
                    tail <- result
                  yield validated :: tail
              }
            yield SbtPointContextReceipt(
              request.project,
              SbtClasspathConfiguration.Compile,
              requested,
              effective,
              classDirectory,
              semanticdbTargetRoot,
              classDirectoryPresent,
              classpath,
              javaContext
            )
        }

  private def parseLines(
      lines: List[String]
  ): Either[String, (Map[String, String], List[SbtClasspathEntry])] =
    lines.foldLeft[Either[String, (Map[String, String], List[SbtClasspathEntry])]](
      Right((Map.empty, Nil))
    ) { (result, line) =>
      result.flatMap { case (fields, entries) =>
        line.split("\t", -1).toList match
          case "entry" :: kindValue :: pathValue :: Nil =>
            for
              kind <- SbtClasspathEntryKind.parse(kindValue).left.map(message =>
                s"Invalid sbt point-context receipt: $message"
              )
              path <- decodePath(pathValue, "external dependency classpath entry")
            yield (fields, entries :+ SbtClasspathEntry(path, kind))
          case key :: fieldValue :: Nil if Allowed.contains(key) && !fields.contains(key) =>
            Right((fields.updated(key, fieldValue), entries))
          case key :: _ if fields.contains(key) =>
            Left(s"Invalid sbt point-context receipt: duplicate field $key")
          case _ => Left("Invalid sbt point-context receipt: unknown or malformed field")
      }
    }

  private def require(condition: Boolean, message: String): Either[String, Unit] =
    Either.cond(condition, (), s"Invalid sbt point-context receipt: $message")

  private def encode(value: String): String =
    Base64.getEncoder.encodeToString(value.getBytes(StandardCharsets.UTF_8))

  private def decode(value: String): Either[String, String] =
    Try(String(Base64.getDecoder.decode(value), StandardCharsets.UTF_8)).toEither.left.map(_ =>
      "Invalid sbt point-context receipt: malformed base64"
    )

  private def decodeAxis(value: String): Either[String, SbtScalaVersion] =
    decode(value).flatMap(SbtScalaVersion.parse).left.map(message =>
      s"Invalid sbt point-context receipt: $message"
    )

  private def decodePath(value: String, description: String): Either[String, Path] =
    decode(value).flatMap(decoded =>
      Try(Path.of(decoded).toAbsolutePath.normalize()).toEither.left.map(_ =>
        s"Invalid sbt point-context receipt: $description path is invalid"
      )
    )
