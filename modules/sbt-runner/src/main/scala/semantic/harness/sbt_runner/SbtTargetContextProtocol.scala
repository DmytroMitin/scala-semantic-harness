package semantic.harness.sbt_runner

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Base64
import scala.util.Try

object SbtTargetContextProtocol:
  val Format = "semantic-scala.internal-target-context-receipt.v1"
  val MaxProtocolBytes = 64 * 1024

  private val Required = Set(
    "project",
    "configuration",
    "classDirectory",
    "semanticdbTargetRoot",
    "scalaVersion"
  )
  private val Allowed = Required + "javaContext"

  def render(receipt: SbtTargetContextReceipt): String =
    val fields = List(
      Format,
      s"project\t${encode(receipt.project.value)}",
      s"configuration\t${SbtClasspathConfiguration.value(receipt.configuration)}",
      s"classDirectory\t${encode(receipt.classDirectory.toString)}",
      s"semanticdbTargetRoot\t${encode(receipt.semanticdbTargetRoot.toString)}",
      s"scalaVersion\t${encode(receipt.scalaVersion)}"
    ) ++ receipt.targetJavaContext.map(value => s"javaContext\t${encode(value)}")
    val entries = receipt.classpath.map(entry =>
      s"entry\t${SbtClasspathEntryKind.value(entry.kind)}\t${encode(entry.path.toString)}"
    )
    (fields ++ entries).mkString("\n") + "\n"

  def parse(
      value: String,
      request: SbtTargetContextRequest
  ): Either[String, SbtTargetContextReceipt] =
    if value.getBytes(StandardCharsets.UTF_8).length > MaxProtocolBytes then
      Left("Invalid sbt target-context receipt: protocol exceeds byte limit")
    else
      val lines = value.linesIterator.toList
      if lines.headOption != Some(Format) then
        Left("Invalid sbt target-context receipt: unsupported protocol marker")
      else
        parseLines(lines.drop(1)).flatMap { case (fields, entries) =>
          val missing = Required.diff(fields.keySet)
          if missing.nonEmpty then
            Left("Invalid sbt target-context receipt: required field is missing")
          else
            for
              projectValue <- decode(fields("project"))
              _ <- Either.cond(
                projectValue == request.project.value,
                (),
                "Invalid sbt target-context receipt: project mismatch"
              )
              _ <- Either.cond(
                fields("configuration") == SbtClasspathConfiguration.CompileValue,
                (),
                "Invalid sbt target-context receipt: configuration mismatch"
              )
              classDirectory <- decodePath(fields("classDirectory"), "class directory")
              semanticdbTargetRoot <- decodePath(fields("semanticdbTargetRoot"), "SemanticDB target root")
              scalaVersion <- decode(fields("scalaVersion"))
              _ <- Either.cond(
                scalaVersion.nonEmpty,
                (),
                "Invalid sbt target-context receipt: Scala version is empty"
              )
              javaContext <- fields.get("javaContext") match
                case Some(encoded) => decode(encoded).map(Some(_))
                case None          => Right(None)
              _ <- Either.cond(
                javaContext == request.targetJava.map(SbtJavaContext.token),
                (),
                "Invalid sbt target-context receipt: target Java context mismatch"
              )
              classpath <- entries.foldRight[Either[String, List[SbtClasspathEntry]]](Right(Nil)) {
                (entry, result) =>
                  for
                    validated <- SbtClasspathEntry.validate(entry).left.map(message =>
                      s"Invalid sbt target-context receipt: $message"
                    )
                    tail <- result
                  yield validated :: tail
              }
            yield SbtTargetContextReceipt(
              request.project,
              SbtClasspathConfiguration.Compile,
              classDirectory,
              semanticdbTargetRoot,
              classpath,
              scalaVersion,
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
                s"Invalid sbt target-context receipt: $message"
              )
              path <- decodePath(pathValue, "classpath entry")
            yield (fields, entries :+ SbtClasspathEntry(path, kind))
          case key :: fieldValue :: Nil if Allowed.contains(key) && !fields.contains(key) =>
            Right((fields.updated(key, fieldValue), entries))
          case key :: _ if fields.contains(key) =>
            Left(s"Invalid sbt target-context receipt: duplicate field $key")
          case _ => Left("Invalid sbt target-context receipt: unknown or malformed field")
      }
    }

  private def encode(value: String): String =
    Base64.getEncoder.encodeToString(value.getBytes(StandardCharsets.UTF_8))

  private def decode(value: String): Either[String, String] =
    Try(String(Base64.getDecoder.decode(value), StandardCharsets.UTF_8)).toEither.left.map(_ =>
      "Invalid sbt target-context receipt: malformed base64"
    )

  private def decodePath(value: String, description: String): Either[String, Path] =
    decode(value).flatMap(decoded =>
      Try(Path.of(decoded).toAbsolutePath.normalize()).toEither.left.map(_ =>
        s"Invalid sbt target-context receipt: $description path is invalid"
      )
    )
