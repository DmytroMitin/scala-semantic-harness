package semantic.harness.sbt_runner

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Base64
import scala.util.Try

object SbtClasspathProtocol:
  val Format = "semantic-scala.internal-sbt-classpath.v1"
  val FormatV2 = "semantic-scala.internal-sbt-classpath.v2"

  def parse(
      content: String,
      request: SbtClasspathRequest
  ): Either[SbtClasspathFailure.Protocol, SbtClasspathResult] =
    val lines = content.linesIterator.toList
    val expectedFormat = request.targetJava.fold(Format)(_ => FormatV2)
    lines match
      case format :: projectLine :: configurationLine :: remaining if format == expectedFormat =>
        for
          projectText <- field(projectLine, "project")
          project <- decode(projectText, "project")
          _ <-
            if project == request.project.value then Right(())
            else Left(protocol(s"project mismatch: expected '${request.project.value}', received '$project'"))
          configurationText <- field(configurationLine, "configuration")
          configuration <- SbtClasspathConfiguration
            .parse(configurationText)
            .left
            .map(message => protocol(message))
          _ <-
            if configuration == request.configuration then Right(())
            else
              Left(
                protocol(
                  s"configuration mismatch: expected '${SbtClasspathConfiguration.value(request.configuration)}', " +
                    s"received '${SbtClasspathConfiguration.value(configuration)}'"
                )
              )
          tokenAndEntries <- parseJavaContext(remaining, request)
          (javaContextToken, entryLines) = tokenAndEntries
          entries <- parseEntries(entryLines)
          normalized <- normalizeAndValidate(entries)
          _ <-
            if normalized.nonEmpty then Right(())
            else Left(protocol("acquired classpath contained no entries"))
        yield SbtClasspathResult(
          request.project,
          request.configuration,
          normalized,
          javaContextToken
        )
      case Nil =>
        Left(protocol("result file was empty"))
      case format :: _ if format != expectedFormat =>
        Left(protocol(s"unexpected protocol marker '$format'"))
      case _ =>
        Left(protocol("result file did not contain the required header fields"))

  def render(result: SbtClasspathResult): String =
    val header = List(
      result.javaContextToken.fold(Format)(_ => FormatV2),
      s"project\t${encode(result.project.value)}",
      s"configuration\t${SbtClasspathConfiguration.value(result.configuration)}"
    ) ++ result.javaContextToken.toList.map(value => s"javaContext\t$value")
    val entries = result.entries.map { entry =>
      s"entry\t${SbtClasspathEntryKind.value(entry.kind)}\t${encode(entry.path.toString)}"
    }
    (header ++ entries).mkString("", "\n", "\n")

  private def parseJavaContext(
      lines: List[String],
      request: SbtClasspathRequest
  ): Either[SbtClasspathFailure.Protocol, (Option[String], List[String])] =
    request.targetJava match
      case None => Right((None, lines))
      case Some(selected) =>
        lines match
          case contextLine :: entries =>
            field(contextLine, "javaContext").flatMap { received =>
              val expected = SbtJavaContext.token(selected)
              if received == expected then Right((Some(expected), entries))
              else Left(protocol("target Java context mismatch"))
            }
          case Nil => Left(protocol("missing target Java context field"))

  private def parseEntries(
      lines: List[String]
  ): Either[SbtClasspathFailure.Protocol, List[SbtClasspathEntry]] =
    lines.foldLeft[Either[SbtClasspathFailure.Protocol, List[SbtClasspathEntry]]](Right(Nil)) {
      (parsed, line) =>
        parsed.flatMap { entries =>
          line.split("\t", -1).toList match
            case "entry" :: kindText :: encodedPath :: Nil =>
              for
                kind <- SbtClasspathEntryKind.parse(kindText).left.map(message => protocol(message))
                pathText <- decode(encodedPath, "classpath entry")
                path <-
                  Try(Path.of(pathText)).toEither.left.map(_ => protocol("classpath entry was not a valid path"))
              yield entries :+ SbtClasspathEntry(path, kind)
            case _ =>
              Left(protocol("malformed classpath entry record"))
        }
    }

  private def normalizeAndValidate(
      entries: List[SbtClasspathEntry]
  ): Either[SbtClasspathFailure.Protocol, List[SbtClasspathEntry]] =
    entries.foldLeft[Either[SbtClasspathFailure.Protocol, List[SbtClasspathEntry]]](Right(Nil)) {
      (validated, entry) =>
        validated.flatMap { result =>
          SbtClasspathEntry.validate(entry).left.map(message => protocol(message)).map { normalized =>
            if result.exists(_.path == normalized.path) then result
            else result :+ normalized
          }
        }
    }

  private def field(
      line: String,
      expectedName: String
  ): Either[SbtClasspathFailure.Protocol, String] =
    line.split("\t", -1).toList match
      case name :: value :: Nil if name == expectedName => Right(value)
      case _ => Left(protocol(s"missing or malformed '$expectedName' field"))

  private def encode(value: String): String =
    Base64.getEncoder.encodeToString(value.getBytes(StandardCharsets.UTF_8))

  private def decode(
      value: String,
      description: String
  ): Either[SbtClasspathFailure.Protocol, String] =
    Try(String(Base64.getDecoder.decode(value), StandardCharsets.UTF_8)).toEither
      .left
      .map(_ => protocol(s"$description was not valid Base64"))

  private def protocol(message: String): SbtClasspathFailure.Protocol =
    SbtClasspathFailure.Protocol(s"Invalid sbt classpath protocol: $message")
