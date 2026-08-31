package semantic.harness.sbt_runner

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Base64
import scala.util.Try

object SbtPointContextProtocol:
  val Format = "semantic-scala.internal-point-context-receipt.v1"
  val FormatV2 = "semantic-scala.internal-point-context-receipt.v2"
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

  private final case class ParsedLines(
      fields: Map[String, String],
      entries: List[SbtClasspathEntry],
      internals: List[List[String]],
      exclusions: List[List[String]]
  )

  def render(receipt: SbtPointContextReceipt): String =
    val marker = if receipt.includeExistingInternalOutputs then FormatV2 else Format
    val fields = List(
      marker,
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
    val internals = receipt.internalDependencies.map { entry =>
      List(
        "internal",
        encode(entry.projectRef),
        encode(entry.project.value),
        entry.role.toString,
        entry.compileMapping.toString,
        encode(entry.requestedScalaVersion.fold("")(_.value)),
        encode(entry.effectiveScalaVersion.value),
        SbtClasspathConfiguration.value(entry.configuration),
        encode(entry.classDirectory.toString),
        entry.classDirectoryPresent.toString
      ).mkString("\t")
    }
    val exclusions = receipt.internalDependencyExclusions.map { entry =>
      List(
        "excludedInternal",
        encode(entry.projectRef),
        encode(entry.project.value),
        entry.role.toString,
        entry.compileMapping.toString,
        entry.reason.toString,
        encode(entry.effectiveScalaVersion.fold("")(_.value))
      ).mkString("\t")
    }
    (fields ++ entries ++ internals ++ exclusions).mkString("\n") + "\n"

  def parse(
      value: String,
      request: SbtPointContextRequest
  ): Either[String, SbtPointContextReceipt] =
    if value.getBytes(StandardCharsets.UTF_8).length > MaxProtocolBytes then
      Left("Invalid sbt point-context receipt: protocol exceeds byte limit")
    else
      val lines = value.linesIterator.toList
      val expectedMarker = if request.includeExistingInternalOutputs then FormatV2 else Format
      if lines.headOption != Some(expectedMarker) then
        Left("Invalid sbt point-context receipt: unsupported protocol marker")
      else
        parseLines(lines.drop(1), request.includeExistingInternalOutputs).flatMap { parsed =>
          val fields = parsed.fields
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
              classDirectoryPresent <- parsePresence(fields("classDirectoryPresent"), "class directory")
              javaContext <- fields.get("javaContext") match
                case Some(encoded) => decode(encoded).map(Some(_))
                case None => Right(None)
              _ <- require(
                javaContext == request.targetJava.map(SbtJavaContext.token),
                "target Java context mismatch"
              )
              classpath <- parsed.entries.foldRight[Either[String, List[SbtClasspathEntry]]](Right(Nil)) {
                (entry, result) =>
                  for
                    validated <- SbtClasspathEntry.validate(entry).left.map(message =>
                      s"Invalid sbt point-context receipt: $message"
                    )
                    tail <- result
                  yield validated :: tail
              }
              internals <- parseInternals(parsed.internals, request, effective)
              exclusions <- parseExclusions(parsed.exclusions, effective)
              internalProjects = internals.map(_.project.value)
              _ <- require(
                internalProjects.distinct.size == internalProjects.size,
                "internal dependency project is duplicated"
              )
              _ <- require(
                internalProjects.size + exclusions.size <= SbtInternalDependencyGraph.MaxDependencyProjects,
                "internal dependency count exceeds the fixed bound"
              )
              _ <- require(!internalProjects.contains(request.project.value), "selected project appears as an internal dependency")
            yield SbtPointContextReceipt(
              request.project,
              SbtClasspathConfiguration.Compile,
              requested,
              effective,
              classDirectory,
              semanticdbTargetRoot,
              classDirectoryPresent,
              classpath,
              javaContext,
              request.includeExistingInternalOutputs,
              internals,
              exclusions
            )
        }

  private def parseLines(
      lines: List[String],
      allowInternal: Boolean
  ): Either[String, ParsedLines] =
    lines.foldLeft[Either[String, ParsedLines]](
      Right(ParsedLines(Map.empty, Nil, Nil, Nil))
    ) { (result, line) =>
      result.flatMap { parsed =>
        line.split("\t", -1).toList match
          case "entry" :: kindValue :: pathValue :: Nil =>
            for
              kind <- SbtClasspathEntryKind.parse(kindValue).left.map(message =>
                s"Invalid sbt point-context receipt: $message"
              )
              path <- decodePath(pathValue, "external dependency classpath entry")
            yield parsed.copy(entries = parsed.entries :+ SbtClasspathEntry(path, kind))
          case values @ ("internal" :: _) if allowInternal && values.size == 10 =>
            Right(parsed.copy(internals = parsed.internals :+ values.tail))
          case values @ ("excludedInternal" :: _) if allowInternal && values.size == 7 =>
            Right(parsed.copy(exclusions = parsed.exclusions :+ values.tail))
          case key :: fieldValue :: Nil if Allowed.contains(key) && !parsed.fields.contains(key) =>
            Right(parsed.copy(fields = parsed.fields.updated(key, fieldValue)))
          case key :: _ if parsed.fields.contains(key) =>
            Left(s"Invalid sbt point-context receipt: duplicate field $key")
          case _ => Left("Invalid sbt point-context receipt: unknown or malformed field")
      }
    }

  private def parseInternals(
      rows: List[List[String]],
      request: SbtPointContextRequest,
      selectedEffective: SbtScalaVersion
  ): Either[String, List[SbtInternalDependencyReceipt]] =
    rows.foldLeft[Either[String, List[SbtInternalDependencyReceipt]]](Right(Nil)) {
      case (result, projectRefRaw :: projectRaw :: roleRaw :: mappingRaw :: requestedRaw ::
            effectiveRaw :: configurationRaw :: classDirectoryRaw :: presentRaw :: Nil) =>
        for
          values <- result
          projectRef <- decode(projectRefRaw)
          projectValue <- decode(projectRaw)
          project <- SbtProjectId.parse(projectValue).left.map(invalid)
          _ <- require(projectRef == s"ThisBuild/${project.value}", "internal project ref is not safely represented")
          role <- parseRole(roleRaw)
          mapping <- SbtCompileDependencyMapping.parse(mappingRaw).left.map(invalid)
          _ <- require(mapping.admitted, "included internal dependency mapping is not admitted")
          requestedText <- decode(requestedRaw)
          requested <- if requestedText.isEmpty then Right(None) else
            SbtScalaVersion.parse(requestedText).left.map(invalid).map(Some(_))
          _ <- require(requested == request.requestedScalaVersion, "internal requested Scala version mismatch")
          effective <- decodeAxis(effectiveRaw)
          _ <- require(effective == selectedEffective, "internal effective Scala version mismatch")
          _ <- require(configurationRaw == SbtClasspathConfiguration.CompileValue, "internal configuration mismatch")
          classDirectory <- decodePath(classDirectoryRaw, "internal class directory")
          present <- parsePresence(presentRaw, "internal class directory")
        yield values :+ SbtInternalDependencyReceipt(
          projectRef,
          project,
          role,
          mapping,
          requested,
          effective,
          SbtClasspathConfiguration.Compile,
          classDirectory,
          present
        )
      case (_, _) => Left("Invalid sbt point-context receipt: malformed internal dependency")
    }

  private def parseExclusions(
      rows: List[List[String]],
      selectedEffective: SbtScalaVersion
  ): Either[String, List[SbtInternalDependencyExclusion]] =
    rows.foldLeft[Either[String, List[SbtInternalDependencyExclusion]]](Right(Nil)) {
      case (result, projectRefRaw :: projectRaw :: roleRaw :: mappingRaw :: reasonRaw :: effectiveRaw :: Nil) =>
        for
          values <- result
          projectRef <- decode(projectRefRaw)
          projectValue <- decode(projectRaw)
          project <- SbtProjectId.parse(projectValue).left.map(invalid)
          _ <- require(
            projectRef == s"ThisBuild/${project.value}" || projectRef == s"ExternalBuild/${project.value}",
            "excluded internal project ref is not safely represented"
          )
          role <- parseRole(roleRaw)
          mapping <- SbtCompileDependencyMapping.parse(mappingRaw).left.map(invalid)
          reason <- SbtInternalDependencyExclusionReason.parse(reasonRaw).left.map(invalid)
          effectiveText <- decode(effectiveRaw)
          effective <- if effectiveText.isEmpty then Right(None) else
            SbtScalaVersion.parse(effectiveText).left.map(invalid).map(Some(_))
          _ <- effective match
            case Some(axis) if reason != SbtInternalDependencyExclusionReason.ScalaAxisMismatch =>
              require(axis == selectedEffective, "excluded internal effective Scala version mismatch")
            case _ => Right(())
        yield values :+ SbtInternalDependencyExclusion(
          projectRef,
          project,
          role,
          mapping,
          reason,
          effective
        )
      case (_, _) => Left("Invalid sbt point-context receipt: malformed internal dependency exclusion")
    }

  private def parseRole(value: String): Either[String, SbtInternalDependencyRole] =
    SbtInternalDependencyRole.values.find(_.toString == value)
      .toRight("Invalid sbt point-context receipt: invalid internal dependency role")

  private def parsePresence(value: String, description: String): Either[String, Boolean] = value match
    case "true" => Right(true)
    case "false" => Right(false)
    case _ => Left(s"Invalid sbt point-context receipt: $description presence is invalid")

  private def require(condition: Boolean, message: String): Either[String, Unit] =
    Either.cond(condition, (), s"Invalid sbt point-context receipt: $message")

  private def invalid(message: String): String = s"Invalid sbt point-context receipt: $message"

  private def encode(value: String): String =
    Base64.getEncoder.encodeToString(value.getBytes(StandardCharsets.UTF_8))

  private def decode(value: String): Either[String, String] =
    Try(String(Base64.getDecoder.decode(value), StandardCharsets.UTF_8)).toEither.left.map(_ =>
      "Invalid sbt point-context receipt: malformed base64"
    )

  private def decodeAxis(value: String): Either[String, SbtScalaVersion] =
    decode(value).flatMap(SbtScalaVersion.parse).left.map(invalid)

  private def decodePath(value: String, description: String): Either[String, Path] =
    decode(value).flatMap(decoded =>
      Try(Path.of(decoded).toAbsolutePath.normalize()).toEither.left.map(_ =>
        s"Invalid sbt point-context receipt: $description path is invalid"
      )
    )
