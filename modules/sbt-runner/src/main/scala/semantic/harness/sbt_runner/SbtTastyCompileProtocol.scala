package semantic.harness.sbt_runner

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Base64
import scala.util.Try

object SbtTastyCompileProtocol:
  val Format = "semantic-scala.internal-tasty-compile-receipt.v1"
  val MaxProtocolBytes = 64 * 1024
  private val Required = Set(
    "project",
    "configuration",
    "status",
    "scalaVersion",
    "classDirectory",
    "sourceIncluded",
    "dependencyClasspath"
  )
  private val Allowed = Required + "javaContext"

  def render(receipt: SbtTastyCompileReceipt): String =
    val lines = List(
      Format,
      s"project\t${encode(receipt.project.value)}",
      s"configuration\t${SbtClasspathConfiguration.value(receipt.configuration)}",
      s"status\t${SbtTastyCompileStatus.value(receipt.compileStatus)}",
      s"scalaVersion\t${encode(receipt.scalaVersion.getOrElse(""))}",
      s"classDirectory\t${encode(receipt.classDirectory.fold("")(_.toString))}",
      s"sourceIncluded\t${receipt.sourceIncluded}",
      s"dependencyClasspath\t${encode(receipt.dependencyClasspath.mkString(java.io.File.pathSeparator))}"
    ) ++ receipt.targetJavaContext.map(value => s"javaContext\t${encode(value)}")
    lines.mkString("\n") + "\n"

  def parse(
      value: String,
      request: SbtTastyCompileRequest
  ): Either[String, SbtTastyCompileReceipt] =
    if value.getBytes(StandardCharsets.UTF_8).length > MaxProtocolBytes then
      Left("Invalid TASTy compile receipt: protocol exceeds byte limit")
    else
      val lines = value.linesIterator.toList
      if lines.headOption != Some(Format) then
        Left("Invalid TASTy compile receipt: unsupported protocol marker")
      else
        val parsed = lines.drop(1).foldLeft[Either[String, Map[String, String]]](Right(Map.empty)) {
          (result, line) =>
            result.flatMap { fields =>
              line.split("\t", 2).toList match
                case key :: fieldValue :: Nil if Allowed.contains(key) && !fields.contains(key) =>
                  Right(fields.updated(key, fieldValue))
                case key :: _ if fields.contains(key) =>
                  Left(s"Invalid TASTy compile receipt: duplicate field $key")
                case _ => Left("Invalid TASTy compile receipt: unknown or malformed field")
            }
        }
        parsed.flatMap { fields =>
          val missing = Required.diff(fields.keySet)
          if missing.nonEmpty then Left("Invalid TASTy compile receipt: required field is missing")
          else
            for
              projectValue <- decode(fields("project"))
              _ <- Either.cond(
                projectValue == request.project.value,
                (),
                "Invalid TASTy compile receipt: project mismatch"
              )
              _ <- Either.cond(
                fields("configuration") == "Compile",
                (),
                "Invalid TASTy compile receipt: configuration mismatch"
              )
              status <- SbtTastyCompileStatus.parse(fields("status"))
              scalaVersion <- decode(fields("scalaVersion"))
              classDirectory <- decode(fields("classDirectory"))
              sourceIncluded <- parseBoolean(fields("sourceIncluded"))
              dependencyClasspath <- decode(fields("dependencyClasspath")).flatMap(parseClasspath)
              javaContext <- fields.get("javaContext") match
                case Some(encoded) => decode(encoded).map(Some(_))
                case None          => Right(None)
              _ <- Either.cond(
                javaContext == request.targetJava.map(SbtJavaContext.token),
                (),
                "Invalid TASTy compile receipt: target Java context mismatch"
              )
              classPath <- Try(Path.of(classDirectory)).toEither.left.map(_ =>
                "Invalid TASTy compile receipt: class directory path is invalid"
              )
            yield SbtTastyCompileReceipt(
              project = request.project,
              configuration = SbtClasspathConfiguration.Compile,
              compileStatus = status,
              scalaVersion = Option(scalaVersion).filter(_.nonEmpty),
              classDirectory = Option(classPath).filter(_ => classDirectory.nonEmpty),
              sourceIncluded = sourceIncluded,
              targetJavaContext = javaContext,
              dependencyClasspath = dependencyClasspath
            )
        }

  private def encode(value: String): String =
    Base64.getEncoder.encodeToString(value.getBytes(StandardCharsets.UTF_8))

  private def decode(value: String): Either[String, String] =
    Try(new String(Base64.getDecoder.decode(value), StandardCharsets.UTF_8)).toEither.left.map(_ =>
      "Invalid TASTy compile receipt: malformed base64"
    )

  private def parseBoolean(value: String): Either[String, Boolean] =
    value match
      case "true"  => Right(true)
      case "false" => Right(false)
      case _       => Left("Invalid TASTy compile receipt: invalid boolean")

  private def parseClasspath(value: String): Either[String, List[Path]] =
    if value.isEmpty then Right(Nil)
    else
      value.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator), -1).toList
        .foldRight[Either[String, List[Path]]](Right(Nil)) {
        (entry, result) =>
          for
            path <- Try(Path.of(entry)).toEither.left.map(_ =>
              "Invalid TASTy compile receipt: dependency classpath entry is invalid"
            )
            tail <- result
          yield path :: tail
        }

private[sbt_runner] object SbtTastyCompileInjection:
  val Task = "semanticScalaInternalTastyCompileReceipt"

  private val Settings =
    s"""val $Task = taskKey[Unit]("Compile and export one bounded TASTy receipt")
       |
       |$Task := {
       |  val compileResult = (Compile / compile).result.value
       |  val selectedScalaVersion = scalaVersion.value
       |  val selectedClassDirectory = (Compile / classDirectory).value.getCanonicalFile
       |  val selectedFullClasspath = (Compile / fullClasspath).value.map(_.data.getCanonicalFile)
       |  val selectedSources = (Compile / sources).value.map(_.getCanonicalFile)
       |  val requestedSource = file(sys.env("SEMANTIC_SCALA_TASTY_SOURCE")).getCanonicalFile
       |  val status = compileResult match {
       |    case sbt.Inc(_)   => "Failed"
       |    case sbt.Value(_) => "Succeeded"
       |  }
       |  val encoder = java.util.Base64.getEncoder
       |  def encoded(value: String): String =
       |    encoder.encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
       |  val javaContext = sys.env.get("SEMANTIC_SCALA_TASTY_JAVA_CONTEXT").toSeq.map { value =>
       |    s"javaContext\t$${encoded(value)}"
       |  }
       |  IO.writeLines(
       |    file(sys.env("SEMANTIC_SCALA_TASTY_RECEIPT")),
       |    Seq(
       |      "${SbtTastyCompileProtocol.Format}",
       |      s"project\t$${encoded(thisProjectRef.value.project)}",
       |      "configuration\tCompile",
       |      s"status\t$$status",
       |      s"scalaVersion\t$${encoded(selectedScalaVersion)}",
       |      s"classDirectory\t$${encoded(selectedClassDirectory.getAbsolutePath)}",
       |      s"sourceIncluded\t$${selectedSources.contains(requestedSource)}",
       |      s"dependencyClasspath\t$${encoded(selectedFullClasspath.mkString(java.io.File.pathSeparator))}"
       |    ) ++ javaContext
       |  )
       |}
       |""".stripMargin

  def globalSettings(request: SbtTastyCompileRequest): String = Settings
