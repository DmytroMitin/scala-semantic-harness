package semantic.harness.sbt_runner

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.FiniteDuration

trait SbtPointContextAcquirer:
  def acquire(
      request: SbtPointContextRequest
  ): Either[SbtPointContextFailure, SbtPointContextReceipt]

object SbtPointContextAcquirer:
  val DefaultTimeout: FiniteDuration = 180.seconds
  val default: SbtPointContextAcquirer =
    ProcessSbtPointContextAcquirer(DefaultTimeout, SbtPointContextProcess.default)

private[sbt_runner] final case class ProcessSbtPointContextAcquirer(
    timeout: FiniteDuration,
    process: SbtPointContextProcess,
    materializer: Option[SbtClasspathMaterializer] = None
) extends SbtPointContextAcquirer:
  override def acquire(
      request: SbtPointContextRequest
  ): Either[SbtPointContextFailure, SbtPointContextReceipt] =
    SbtPointContextRequest
      .validate(request)
      .left
      .map(SbtPointContextFailure.Validation.apply)
      .flatMap(acquireValidated)

  private def acquireValidated(
      request: SbtPointContextRequest
  ): Either[SbtPointContextFailure, SbtPointContextReceipt] =
    val temporaryRoot = Files.createTempDirectory("ss-sbt-point-context-")
    val globalBase = Files.createDirectory(temporaryRoot.resolve("global"))
    val receiptFile = temporaryRoot.resolve("receipt.protocol")
    try
      Files.writeString(
        globalBase.resolve("global.sbt"),
        SbtPointContextInjection.globalSettings(request),
        StandardCharsets.UTF_8
      )
      process.run(
        request,
        globalBase,
        receiptFile,
        SbtFixedTask.PointContextReceipt,
        timeout
      ) match
        case SbtPointContextProcessOutcome.Completed(result) if result.exitCode == 0 =>
          readReceipt(receiptFile, request)
            .flatMap(receipt =>
              materializer
                .getOrElse(SbtClasspathMaterializer.forWorkspace(request.workspace))
                .materialize(receipt, temporaryRoot)
            )
            .left
            .map { message =>
              if message.contains("effective Scala version") then
                SbtPointContextFailure.ScalaVersionMismatch(message)
              else SbtPointContextFailure.Protocol(message)
            }
        case SbtPointContextProcessOutcome.Completed(result) =>
          val message = processFailure(request, result)
          if isScalaSwitchFailure(request, result) then
            Left(SbtPointContextFailure.ScalaSwitch(message))
          else if isUnknownProject(result) then
            Left(SbtPointContextFailure.UnknownProject(message))
          else Left(SbtPointContextFailure.Process(message))
        case SbtPointContextProcessOutcome.TimedOut(stdout, stderr) =>
          Left(SbtPointContextFailure.Process(
            s"Unable to acquire sbt point context for project '${request.project.value}': " +
              s"sbt exceeded the ${timeout.toSeconds}-second timeout${suffix(diagnosticExcerpt(stdout, stderr))}"
          ))
        case SbtPointContextProcessOutcome.FailedToStart(message) =>
          Left(SbtPointContextFailure.Process(
            s"Unable to acquire sbt point context for project '${request.project.value}': " +
              s"unable to start sbt${suffix(Option(message).map(_.trim).filter(_.nonEmpty))}"
          ))
    finally deleteRecursively(temporaryRoot)

  private def readReceipt(
      path: Path,
      request: SbtPointContextRequest
  ): Either[String, SbtPointContextReceipt] =
    if !Files.isRegularFile(path) then
      Left("Invalid sbt point-context receipt: result file was not created")
    else
      val stream = Files.newInputStream(path)
      val bytes =
        try stream.readNBytes(SbtPointContextProtocol.MaxProtocolBytes + 1)
        finally stream.close()
      if bytes.length > SbtPointContextProtocol.MaxProtocolBytes then
        Left("Invalid sbt point-context receipt: protocol exceeds byte limit")
      else SbtPointContextProtocol.parse(String(bytes, StandardCharsets.UTF_8), request)

  private def combined(result: SbtRunResult): String = result.stdout + "\n" + result.stderr

  private def isScalaSwitchFailure(
      request: SbtPointContextRequest,
      result: SbtRunResult
  ): Boolean =
    request.requestedScalaVersion.nonEmpty && {
      val output = combined(result).toLowerCase(java.util.Locale.ROOT)
      output.contains("switching to scala") || output.contains("switch failed") ||
      output.contains("not a valid scala version") || output.contains("not supported")
    }

  private def isUnknownProject(result: SbtRunResult): Boolean =
    val output = combined(result)
    output.contains("Not a valid project ID") || output.contains("No project")

  private def processFailure(request: SbtPointContextRequest, result: SbtRunResult): String =
    s"Unable to acquire sbt point context for project '${request.project.value}': " +
      s"sbt exited with code ${result.exitCode}${suffix(diagnosticExcerpt(result.stdout, result.stderr))}"

  private def diagnosticExcerpt(stdout: String, stderr: String): Option[String] =
    val lines = List(stdout, stderr).flatMap(_.linesIterator).map(_.trim).filter(_.nonEmpty)
    val relevant = lines.filter(line =>
      line.startsWith("[error]") || line.contains("error") || line.contains("Error") ||
        line.contains("failed") || line.contains("Failed")
    )
    val selected = (if relevant.nonEmpty then relevant else lines.takeRight(8)).take(8)
    Option(selected.mkString("\n")).map(_.take(1200)).map(_.trim).filter(_.nonEmpty)

  private def suffix(value: Option[String]): String = value.fold("")(message => s": $message")

  private def deleteRecursively(root: Path): Unit =
    if Files.exists(root) then
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()

private[sbt_runner] object SbtPointContextInjection:
  val Task = SbtFixedTask.PointContextReceipt.selectedTask

  private val Settings =
    SbtInjectedClasspathMaterialization.Settings +
      s"""@transient val $Task = taskKey[Unit]("Export one bounded partial existing-output point-context receipt")
         |
         |$Task := {
         |  val selectedClassDirectory = (Compile / classDirectory).value.getCanonicalFile
         |  val selectedSemanticdbTargetRoot = (Compile / semanticdbTargetRoot).value.getCanonicalFile
         |  val selectedExternalDependencies = (Compile / externalDependencyClasspath).value
         |  val selectedScalaVersion = scalaVersion.value
         |  val converter = fileConverter.value
         |  val encoder = java.util.Base64.getEncoder
         |  def encoded(value: String): String =
         |    encoder.encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
         |  val entries = selectedExternalDependencies.map(entry =>
         |    semanticScalaInternalClasspathFile(entry, converter)
         |  ).map { entry =>
         |    val kind =
         |      if (entry.isDirectory) "Directory"
         |      else if (entry.isFile) "Jar"
         |      else "Unsupported"
         |    s"entry\t$$kind\t$${encoded(entry.getAbsolutePath)}"
         |  }
         |  val requestedScala = sys.env.get("SEMANTIC_SCALA_REQUESTED_SCALA_VERSION").toSeq.map { value =>
         |    s"requestedScalaVersion\t$${encoded(value)}"
         |  }
         |  val javaContext = sys.env.get("SEMANTIC_SCALA_TARGET_CONTEXT_JAVA").toSeq.map { value =>
         |    s"javaContext\t$${encoded(value)}"
         |  }
         |  IO.writeLines(
         |    file(sys.env("SEMANTIC_SCALA_POINT_CONTEXT_RECEIPT")),
         |    Seq(
         |      "${SbtPointContextProtocol.Format}",
         |      s"project\t$${encoded(thisProjectRef.value.project)}",
         |      "configuration\tCompile"
         |    ) ++ requestedScala ++ Seq(
         |      s"effectiveScalaVersion\t$${encoded(selectedScalaVersion)}",
         |      s"classDirectory\t$${encoded(selectedClassDirectory.getAbsolutePath)}",
         |      s"semanticdbTargetRoot\t$${encoded(selectedSemanticdbTargetRoot.getAbsolutePath)}",
         |      s"classDirectoryPresent\t$${selectedClassDirectory.isDirectory}"
         |    ) ++ javaContext ++ entries
         |  )
         |}
         |""".stripMargin

  private def internalSettings(requireFreshInternalOutputs: Boolean): String =
    val profileDescription = if requireFreshInternalOutputs then
      "Export one bounded strict-freshness partial existing Compile-output point-context receipt"
    else "Export one bounded partial existing Compile-output point-context receipt"
    val format = if requireFreshInternalOutputs then SbtPointContextProtocol.FormatV3
      else SbtPointContextProtocol.FormatV2
    val analysisLookup = if requireFreshInternalOutputs then
      s"""|              val dependencyAnalysisFile = try {
         |                Some(extracted.runTask(ref / Compile / compileAnalysisFile, state.value)._2.getCanonicalFile)
         |              } catch {
         |                case scala.util.control.NonFatal(_) => None
         |              }
         |              val dependencySourceLayout = try {
         |                def absolute(file: File): File = file.toPath.toAbsolutePath.normalize.toFile
         |                val all = extracted.get(ref / Compile / sourceDirectories).map(absolute).distinct
         |                val unmanaged = extracted.get(ref / Compile / unmanagedSourceDirectories).map(absolute).distinct
         |                val managed = extracted.get(ref / Compile / managedSourceDirectories).map(absolute).distinct
         |                val generators = extracted.get(ref / Compile / sourceGenerators).size
         |                val bounded = Seq(all.size, unmanaged.size, managed.size).forall(
         |                  _ <= ${SbtInternalSourceLayoutReceipt.MaxSourceDirectories}
         |                ) && generators <= ${SbtInternalSourceLayoutReceipt.MaxSourceGenerators}
         |                if (bounded) Some((all, unmanaged, managed, generators)) else None
         |              } catch {
         |                case scala.util.control.NonFatal(_) => None
         |              }
         |""".stripMargin
    else "              val dependencyAnalysisFile = None\n              val dependencySourceLayout = None\n"
    val analysisColumn = if requireFreshInternalOutputs then
      """ ++ Seq(
        encoded(dependencyAnalysisFile.fold("")(_.getAbsolutePath)),
        dependencySourceLayout.isDefined.toString,
        encoded(dependencySourceLayout.fold("")(_._1.map(_.getAbsolutePath).mkString("\u0000"))),
        encoded(dependencySourceLayout.fold("")(_._2.map(_.getAbsolutePath).mkString("\u0000"))),
        encoded(dependencySourceLayout.fold("")(_._3.map(_.getAbsolutePath).mkString("\u0000"))),
        dependencySourceLayout.fold(0)(_._4).toString
      )"""
    else ""
    SbtInjectedClasspathMaterialization.Settings +
      s"""@transient val $Task = taskKey[Unit]("$profileDescription")
         |
         |$Task := {
         |  val selectedRef = thisProjectRef.value
         |  val selectedProject = thisProject.value
         |  val selectedClassDirectory = (Compile / classDirectory).value.getCanonicalFile
         |  val selectedSemanticdbTargetRoot = (Compile / semanticdbTargetRoot).value.getCanonicalFile
         |  val selectedExternalDependencies = (Compile / externalDependencyClasspath).value
         |  val selectedScalaVersion = scalaVersion.value
         |  val extracted = Project.extract(state.value)
         |  val converter = fileConverter.value
         |  val encoder = java.util.Base64.getEncoder
         |  val identifier = "[A-Za-z][A-Za-z0-9_-]*"
         |  val admittedMappings = Set("DefaultCompileToCompile", "ExplicitCompileToCompile")
         |  def encoded(value: String): String =
         |    encoder.encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
         |  def mapping(value: Option[String]): String = value match {
         |    case None => "DefaultCompileToCompile"
         |    case Some(raw) =>
         |      val normalized = raw.trim.toLowerCase(java.util.Locale.ROOT)
         |      if (normalized == "compile") "ExplicitCompileToCompile"
         |      else if (normalized.isEmpty) "UnsupportedOrAmbiguous"
         |      else {
         |        val clauses = normalized.split(";", -1).toVector
         |        val parsed = clauses.map(_.split("->", -1).toVector)
         |        val valid = parsed.forall(parts =>
         |          parts.size == 2 && parts(0).matches(identifier) && parts(1).matches(identifier)
         |        )
         |        if (valid && parsed.exists(parts => parts(0) == "compile" && parts(1) == "compile"))
         |          "ExplicitCompileToCompile"
         |        else if (valid || (!normalized.contains(";") && normalized.matches(identifier)))
         |          "ExcludedNoCompileToCompile"
         |        else "UnsupportedOrAmbiguous"
         |      }
         |  }
         |  val directProjects = selectedProject.dependencies.iterator
         |    .filter(dependency =>
         |      dependency.project.build == selectedRef.build && admittedMappings(mapping(dependency.configuration))
         |    )
         |    .map(_.project)
         |    .toSet
         |  val visited = scala.collection.mutable.LinkedHashSet[String](selectedRef.project)
         |  val active = scala.collection.mutable.LinkedHashSet[String](selectedRef.project)
         |  val internalRows = scala.collection.mutable.ArrayBuffer.empty[String]
         |  val excludedRows = scala.collection.mutable.ArrayBuffer.empty[String]
         |  def role(from: ProjectRef, ref: ProjectRef): String =
         |    if (from == selectedRef || directProjects(ref)) "Direct" else "Transitive"
         |  def excluded(
         |      refKind: String,
         |      project: String,
         |      dependencyRole: String,
         |      mappingStatus: String,
         |      reason: String,
         |      effectiveScalaVersion: Option[String]
         |  ): Unit = {
         |    excludedRows += Seq(
         |      "excludedInternal",
         |      encoded(s"$$refKind/$$project"),
         |      encoded(project),
         |      dependencyRole,
         |      mappingStatus,
         |      reason,
         |      encoded(effectiveScalaVersion.getOrElse(""))
         |    ).mkString("\t")
         |  }
         |  def visit(from: ProjectRef, dependency: ClasspathDep[ProjectRef]): Unit = {
         |    val ref = dependency.project
         |    val project = ref.project
         |    if (!project.matches(identifier)) sys.error("Internal dependency project ID cannot be represented safely")
         |    val dependencyRole = role(from, ref)
         |    val mappingStatus = mapping(dependency.configuration)
         |    if (ref.build != selectedRef.build) {
         |      excluded("ExternalBuild", project, dependencyRole, mappingStatus, "ForeignBuildProjectRef", None)
         |    } else if (!admittedMappings(mappingStatus)) {
         |      val reason = if (mappingStatus == "ExcludedNoCompileToCompile")
         |        "NoCompileToCompileMapping"
         |      else "UnsupportedOrAmbiguousMapping"
         |      excluded("ThisBuild", project, dependencyRole, mappingStatus, reason, None)
         |    } else if (active(project)) {
         |      excluded("ThisBuild", project, dependencyRole, mappingStatus, "CycleToSelectedOrActiveProject", None)
         |    } else if (!visited(project)) {
         |      if (visited.size - 1 >= ${SbtInternalDependencyGraph.MaxDependencyProjects}) {
         |        excluded("ThisBuild", project, dependencyRole, mappingStatus, "DependencyBoundExceeded", None)
         |      } else {
         |        visited += project
         |        active += project
         |        val dependencyProject = try {
         |          Some(extracted.get(ref / thisProject))
         |        } catch {
         |          case scala.util.control.NonFatal(_) => None
         |        }
         |        dependencyProject match {
         |        case None =>
         |          excluded("ThisBuild", project, dependencyRole, mappingStatus, "ProjectSettingsUnavailable", None)
         |        case Some(projectDefinition) =>
         |          val dependencyScalaVersion = try {
         |            Some(extracted.get(ref / scalaVersion))
         |          } catch {
         |            case scala.util.control.NonFatal(_) => None
         |          }
         |          dependencyScalaVersion match {
         |          case None =>
         |            excluded("ThisBuild", project, dependencyRole, mappingStatus, "ProjectSettingsUnavailable", None)
         |          case Some(axis) if axis != selectedScalaVersion =>
         |            excluded("ThisBuild", project, dependencyRole, mappingStatus, "ScalaAxisMismatch", Some(axis))
         |          case Some(axis) =>
         |            val dependencyClassDirectory = try {
         |              Some(extracted.get(ref / Compile / classDirectory).getCanonicalFile)
         |            } catch {
         |              case scala.util.control.NonFatal(_) => None
         |            }
         |            dependencyClassDirectory match {
         |            case None =>
         |              excluded("ThisBuild", project, dependencyRole, mappingStatus, "ClassDirectorySettingUnavailable", Some(axis))
         |            case Some(directory) =>
         |$analysisLookup
         |              val requestedScala = sys.env.get("SEMANTIC_SCALA_REQUESTED_SCALA_VERSION").getOrElse("")
         |              internalRows += (Seq(
         |                "internal",
         |                encoded(s"ThisBuild/$$project"),
         |                encoded(project),
         |                dependencyRole,
         |                mappingStatus,
         |                encoded(requestedScala),
         |                encoded(axis),
         |                "Compile",
         |                encoded(directory.getAbsolutePath),
         |                directory.isDirectory.toString
         |              )$analysisColumn).mkString("\t")
         |            }
         |          }
         |          projectDefinition.dependencies.foreach(visit(ref, _))
         |        }
         |        active -= project
         |      }
         |    }
         |  }
         |  selectedProject.dependencies.foreach(visit(selectedRef, _))
         |  val entries = selectedExternalDependencies.map(entry =>
         |    semanticScalaInternalClasspathFile(entry, converter)
         |  ).map { entry =>
         |    val kind =
         |      if (entry.isDirectory) "Directory"
         |      else if (entry.isFile) "Jar"
         |      else "Unsupported"
         |    s"entry\t$$kind\t$${encoded(entry.getAbsolutePath)}"
         |  }
         |  val requestedScala = sys.env.get("SEMANTIC_SCALA_REQUESTED_SCALA_VERSION").toSeq.map { value =>
         |    s"requestedScalaVersion\t$${encoded(value)}"
         |  }
         |  val javaContext = sys.env.get("SEMANTIC_SCALA_TARGET_CONTEXT_JAVA").toSeq.map { value =>
         |    s"javaContext\t$${encoded(value)}"
         |  }
         |  IO.writeLines(
         |    file(sys.env("SEMANTIC_SCALA_POINT_CONTEXT_RECEIPT")),
         |    Seq(
         |      "$format",
         |      s"project\t$${encoded(selectedRef.project)}",
         |      "configuration\tCompile"
         |    ) ++ requestedScala ++ Seq(
         |      s"effectiveScalaVersion\t$${encoded(selectedScalaVersion)}",
         |      s"classDirectory\t$${encoded(selectedClassDirectory.getAbsolutePath)}",
         |      s"semanticdbTargetRoot\t$${encoded(selectedSemanticdbTargetRoot.getAbsolutePath)}",
         |      s"classDirectoryPresent\t$${selectedClassDirectory.isDirectory}"
         |    ) ++ javaContext ++ entries ++ internalRows ++ excludedRows
         |  )
         |}
         |""".stripMargin

  private val V5Settings = internalSettings(requireFreshInternalOutputs = false)
  private val V6Settings = internalSettings(requireFreshInternalOutputs = true)

  def globalSettings(request: SbtPointContextRequest): String =
    if request.requireFreshInternalOutputs then V6Settings
    else if request.includeExistingInternalOutputs then V5Settings
    else Settings

private[sbt_runner] enum SbtPointContextProcessOutcome:
  case Completed(result: SbtRunResult)
  case TimedOut(stdout: String, stderr: String)
  case FailedToStart(message: String)

private[sbt_runner] trait SbtPointContextProcess:
  def run(
      request: SbtPointContextRequest,
      globalBase: Path,
      receiptFile: Path,
      task: SbtFixedTask,
      timeout: FiniteDuration
  ): SbtPointContextProcessOutcome

private[sbt_runner] object SbtPointContextProcess:
  val default: SbtPointContextProcess = ProcessSbtPointContextProcess()

private[sbt_runner] final case class ProcessSbtPointContextProcess(
    environmentEntries: Map[String, String] = Map.empty
) extends SbtPointContextProcess:
  override def run(
      request: SbtPointContextRequest,
      globalBase: Path,
      receiptFile: Path,
      task: SbtFixedTask,
      timeout: FiniteDuration
  ): SbtPointContextProcessOutcome =
    val operationEnvironment = Map(
      "SEMANTIC_SCALA_POINT_CONTEXT_RECEIPT" -> receiptFile.toString
    ) ++ request.requestedScalaVersion.map(axis =>
      "SEMANTIC_SCALA_REQUESTED_SCALA_VERSION" -> axis.value
    ) ++ request.targetJava.map(selected =>
      "SEMANTIC_SCALA_TARGET_CONTEXT_JAVA" -> SbtJavaContext.token(selected)
    )
    SbtProcessLifecycle.run(
      request.workspace,
      globalBase,
      globalBase.getParent.resolve("r"),
      SbtCommandSequence.selected(request.project, task, request.requestedScalaVersion),
      request.targetJava,
      environmentEntries ++ operationEnvironment,
      timeout
    ) match
      case SbtProcessOutcome.Completed(result) => SbtPointContextProcessOutcome.Completed(result)
      case SbtProcessOutcome.TimedOut(stdout, stderr) =>
        SbtPointContextProcessOutcome.TimedOut(stdout, stderr)
      case SbtProcessOutcome.FailedToStart(message) =>
        SbtPointContextProcessOutcome.FailedToStart(message)
