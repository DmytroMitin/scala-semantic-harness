package semantic.harness.sbt_runner

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.concurrent.duration.DurationInt

class SbtPointContextReceiptSuite extends munit.FunSuite:
  test("protocol round-trips fixed Compile partial-existing-output context with axis and redacted JDK"):
    val workspace = Files.createTempDirectory("point-context-protocol-")
    val classes = Files.createDirectories(workspace.resolve("target/classes"))
    val semanticdb = Files.createDirectories(workspace.resolve("target/meta"))
    val dependency = Files.createFile(workspace.resolve("dependency.jar"))
    val selected = SbtClasspathCacheTestSupport.selectedJava(workspace.resolve("private-jdk"))
    val axis = scalaVersion("3.3.7")
    val request = SbtPointContextRequest(workspace, project("app"), Some(axis), Some(selected))
    val receipt = SbtPointContextReceipt(
      project("app"),
      SbtClasspathConfiguration.Compile,
      Some(axis),
      axis,
      classes,
      semanticdb,
      classDirectoryPresent = true,
      List(SbtClasspathEntry(dependency, SbtClasspathEntryKind.Jar)),
      Some(SbtJavaContext.token(selected))
    )
    try
      val rendered = SbtPointContextProtocol.render(receipt)
      assertEquals(SbtPointContextProtocol.parse(rendered, request), Right(receipt))
      assert(!rendered.contains(selected.canonicalHome.toString), clue(rendered))
    finally deleteRecursively(workspace)

  test("protocol rejects missing duplicate malformed oversized and requested/effective mismatch"):
    val workspace = Files.createTempDirectory("point-context-invalid-")
    val classes = workspace.resolve("target/classes")
    val semanticdb = workspace.resolve("target/meta")
    val requested = scalaVersion("3.3.7")
    val request = SbtPointContextRequest(workspace, project("app"), Some(requested))
    val receipt = SbtPointContextReceipt(
      project("app"),
      SbtClasspathConfiguration.Compile,
      Some(requested),
      requested,
      classes,
      semanticdb,
      classDirectoryPresent = false,
      Nil,
      None
    )
    try
      val valid = SbtPointContextProtocol.render(receipt)
      val invalid = List(
        valid.linesIterator.filterNot(_.startsWith("classDirectoryPresent\t")).mkString("\n") + "\n",
        valid + valid.linesIterator.find(_.startsWith("project\t")).get + "\n",
        valid + "unknown\tvalue\n",
        valid.replace("classDirectoryPresent\tfalse", "classDirectoryPresent\tmaybe"),
        valid.replace(
          s"effectiveScalaVersion\t${encoded("3.3.7")}",
          s"effectiveScalaVersion\t${encoded("3.3.8")}"
        ),
        valid.replace(SbtPointContextProtocol.Format, "semantic-scala.invalid")
      )
      invalid.foreach(value => assert(SbtPointContextProtocol.parse(value, request).isLeft, clue(value)))
      assert(SbtPointContextProtocol.parse("x" * (SbtPointContextProtocol.MaxProtocolBytes + 1), request).isLeft)
    finally deleteRecursively(workspace)

  test("injection requests only roots external dependencies and presence with sbt1 and sbt2 materialization"):
    val workspace = Files.createTempDirectory("point-context-injection-")
    try
      val settings = SbtPointContextInjection.globalSettings(
        SbtPointContextRequest(workspace, project("app"), Some(scalaVersion("3.3.7")))
      )
      assert(settings.contains("Compile / classDirectory"), clue(settings))
      assert(settings.contains("Compile / semanticdbTargetRoot"), clue(settings))
      assert(settings.contains("Compile / externalDependencyClasspath"), clue(settings))
      assert(settings.contains("scalaVersion.value"), clue(settings))
      assert(settings.contains("fileConverter.value"), clue(settings))
      assert(settings.contains("xsbti.VirtualFileRef"), clue(settings))
      List("fullClasspath", "products", "exportedProducts", "Compile / compile", "compile.value")
        .foreach(forbidden => assert(!settings.contains(forbidden), clue(settings)))
      assertEquals(SbtPointContextInjection.Task, "semanticScalaInternalPointContextReceipt")
    finally Files.deleteIfExists(workspace)

  test("process acquisition uses one fixed task and classifies switch unknown timeout protocol without fallback"):
    val workspace = Files.createTempDirectory("point-context-acquirer-")
    val request = SbtPointContextRequest(workspace, project("app"), Some(scalaVersion("9.9.9")))
    try
      val switch = CountingProcess(SbtPointContextProcessOutcome.Completed(
        SbtRunResult("task", 1, "[error] Switching to Scala 9.9.9 is not supported", "")
      ))
      val unknown = CountingProcess(SbtPointContextProcessOutcome.Completed(
        SbtRunResult("task", 1, "[error] Not a valid project ID: app", "")
      ))
      val timeout = CountingProcess(SbtPointContextProcessOutcome.TimedOut("", "[error] timeout"))
      val missing = CountingProcess(SbtPointContextProcessOutcome.Completed(SbtRunResult("task", 0, "", "")))

      assert(ProcessSbtPointContextAcquirer(1.second, switch).acquire(request).left.exists(_.isInstanceOf[SbtPointContextFailure.ScalaSwitch]))
      assert(ProcessSbtPointContextAcquirer(1.second, unknown).acquire(request).left.exists(_.isInstanceOf[SbtPointContextFailure.UnknownProject]))
      assert(ProcessSbtPointContextAcquirer(1.second, timeout).acquire(request).left.exists(_.isInstanceOf[SbtPointContextFailure.Process]))
      assert(ProcessSbtPointContextAcquirer(1.second, missing).acquire(request).left.exists(_.isInstanceOf[SbtPointContextFailure.Protocol]))
      List(switch, unknown, timeout, missing).foreach(process => assertEquals(process.calls, 1))
    finally Files.deleteIfExists(workspace)

  test("v5 protocol round-trips ordered present and absent same-axis internal Compile outputs"):
    val workspace = Files.createTempDirectory("point-context-v5-protocol-")
    val classes = Files.createDirectories(workspace.resolve("app/target/classes"))
    val semanticdb = Files.createDirectories(workspace.resolve("app/target/meta"))
    val direct = Files.createDirectories(workspace.resolve("macros/target/classes"))
    val missing = workspace.resolve("core/target/classes")
    val dependency = Files.createFile(workspace.resolve("dependency.jar"))
    val axis = scalaVersion("2.13.18")
    val request = SbtPointContextRequest(
      workspace,
      project("app"),
      Some(axis),
      includeExistingInternalOutputs = true
    )
    val receipt = SbtPointContextReceipt(
      project("app"),
      SbtClasspathConfiguration.Compile,
      Some(axis),
      axis,
      classes,
      semanticdb,
      classDirectoryPresent = true,
      List(SbtClasspathEntry(dependency, SbtClasspathEntryKind.Jar)),
      None,
      includeExistingInternalOutputs = true,
      internalDependencies = List(
        internal("macros", SbtInternalDependencyRole.Direct, axis, direct, present = true),
        internal("core", SbtInternalDependencyRole.Transitive, axis, missing, present = false)
      )
    )
    try
      val rendered = SbtPointContextProtocol.render(receipt)
      assert(rendered.startsWith(SbtPointContextProtocol.FormatV2 + "\n"), clue(rendered))
      assertEquals(SbtPointContextProtocol.parse(rendered, request), Right(receipt))
      assert(!Files.exists(missing))
    finally deleteRecursively(workspace)

  test("v5 injection traverses dependencies settings only while v4 injection remains unchanged"):
    val workspace = Files.createTempDirectory("point-context-v5-injection-")
    try
      val v4 = SbtPointContextInjection.globalSettings(
        SbtPointContextRequest(workspace, project("app"), Some(scalaVersion("2.13.18")))
      )
      val v5 = SbtPointContextInjection.globalSettings(
        SbtPointContextRequest(
          workspace,
          project("app"),
          Some(scalaVersion("2.13.18")),
          includeExistingInternalOutputs = true
        )
      )
      assert(!v4.contains("thisProject.dependencies"), clue(v4))
      List("thisProject", ".dependencies", "Project.extract(state.value)", "Compile / classDirectory")
        .foreach(required => assert(v5.contains(required), clue(v5)))
      List(
        "aggregate",
        "fullClasspath",
        "products",
        "exportedProducts",
        "internalDependencyClasspath",
        "Compile / compile",
        "compile.value"
      ).foreach(forbidden => assert(!v5.contains(forbidden), clue(v5)))
    finally Files.deleteIfExists(workspace)

  test("v5 protocol rejects wrong-axis internal outputs and duplicate projects"):
    val workspace = Files.createTempDirectory("point-context-v5-invalid-")
    val classes = Files.createDirectories(workspace.resolve("app/classes"))
    val semanticdb = Files.createDirectories(workspace.resolve("app/meta"))
    val internalDir = Files.createDirectories(workspace.resolve("core/classes"))
    val requested = scalaVersion("2.13.18")
    val wrong = scalaVersion("2.12.21")
    val request = SbtPointContextRequest(
      workspace,
      project("app"),
      Some(requested),
      includeExistingInternalOutputs = true
    )
    val base = SbtPointContextReceipt(
      project("app"),
      SbtClasspathConfiguration.Compile,
      Some(requested),
      requested,
      classes,
      semanticdb,
      classDirectoryPresent = true,
      Nil,
      None,
      includeExistingInternalOutputs = true,
      internalDependencies = List(
        internal("core", SbtInternalDependencyRole.Direct, wrong, internalDir, present = true)
      )
    )
    try
      assert(SbtPointContextProtocol.parse(SbtPointContextProtocol.render(base), request).isLeft)
      val duplicate = base.copy(internalDependencies = List(
        internal("core", SbtInternalDependencyRole.Direct, requested, internalDir, present = true),
        internal("core", SbtInternalDependencyRole.Transitive, requested, internalDir, present = true)
      ))
      assert(SbtPointContextProtocol.parse(SbtPointContextProtocol.render(duplicate), request).isLeft)
    finally deleteRecursively(workspace)

  test("v6 protocol round-trips only the selected-axis analysis path without changing v5"):
    val workspace = Files.createTempDirectory("point-context-v6-protocol-")
    val classes = Files.createDirectories(workspace.resolve("app/target/classes"))
    val semanticdb = Files.createDirectories(workspace.resolve("app/target/meta"))
    val internalDir = Files.createDirectories(workspace.resolve("macros/target/classes"))
    val analysis = Files.createDirectories(workspace.resolve("macros/target/zinc"))
      .resolve("inc_compile_2.13.zip")
    val axis = scalaVersion("2.13.18")
    val strictRequest = SbtPointContextRequest(
      workspace,
      project("app"),
      Some(axis),
      includeExistingInternalOutputs = true,
      requireFreshInternalOutputs = true
    )
    val strictReceipt = SbtPointContextReceipt(
      project("app"),
      SbtClasspathConfiguration.Compile,
      Some(axis),
      axis,
      classes,
      semanticdb,
      classDirectoryPresent = true,
      Nil,
      None,
      includeExistingInternalOutputs = true,
      internalDependencies = List(
        internal("macros", SbtInternalDependencyRole.Direct, axis, internalDir, present = true)
          .copy(
            compileAnalysisFile = Some(analysis),
            sourceLayout = Some(SbtInternalSourceLayoutReceipt(
              sourceDirectories = List(workspace.resolve("macros/src/main/scala"), workspace.resolve("macros/target/src_managed/main")),
              unmanagedSourceDirectories = List(workspace.resolve("macros/src/main/scala")),
              managedSourceDirectories = List(workspace.resolve("macros/target/src_managed/main")),
              sourceGeneratorCount = 0
            ))
          )
      ),
      requireFreshInternalOutputs = true
    )
    val v5Request = strictRequest.copy(requireFreshInternalOutputs = false)
    val v5Receipt = strictReceipt.copy(
      requireFreshInternalOutputs = false,
      internalDependencies = strictReceipt.internalDependencies.map(
        _.copy(compileAnalysisFile = None, sourceLayout = None)
      )
    )
    try
      val rendered = SbtPointContextProtocol.render(strictReceipt)
      assert(rendered.startsWith(SbtPointContextProtocol.FormatV3 + "\n"), clue(rendered))
      assertEquals(SbtPointContextProtocol.parse(rendered, strictRequest), Right(strictReceipt))
      assert(SbtPointContextProtocol.parse(rendered, v5Request).isLeft)
      assertEquals(SbtPointContextProtocol.parse(SbtPointContextProtocol.render(v5Receipt), v5Request), Right(v5Receipt))
    finally deleteRecursively(workspace)

  test("v6 settings add only analysis and non-running source-layout provenance to the v5 receipt"):
    val workspace = Files.createTempDirectory("point-context-v6-injection-")
    val axis = scalaVersion("2.13.18")
    try
      val v5 = SbtPointContextInjection.globalSettings(
        SbtPointContextRequest(workspace, project("app"), Some(axis), includeExistingInternalOutputs = true)
      )
      val v6 = SbtPointContextInjection.globalSettings(
        SbtPointContextRequest(
          workspace,
          project("app"),
          Some(axis),
          includeExistingInternalOutputs = true,
          requireFreshInternalOutputs = true
        )
      )
      assert(!v5.contains("compileAnalysisFile"), clue(v5))
      assert(v6.contains("Compile / compileAnalysisFile"), clue(v6))
      assert(v6.contains("Compile / sourceDirectories"), clue(v6))
      assert(v6.contains("Compile / unmanagedSourceDirectories"), clue(v6))
      assert(v6.contains("Compile / managedSourceDirectories"), clue(v6))
      assert(v6.contains("Compile / sourceGenerators"), clue(v6))
      assert(!v6.contains("Compile / managedSources"), clue(v6))
      List(
        "fullClasspath",
        "products",
        "exportedProducts",
        "internalDependencyClasspath",
        "Compile / compile)",
        "compile.value"
      ).foreach(forbidden => assert(!v6.contains(forbidden), clue(v6)))

      val invalid = SbtPointContextRequest(
        workspace,
        project("app"),
        Some(axis),
        requireFreshInternalOutputs = true
      )
      assert(SbtPointContextRequest.validate(invalid).isLeft)
    finally Files.deleteIfExists(workspace)

  private final case class CountingProcess(outcome: SbtPointContextProcessOutcome)
      extends SbtPointContextProcess:
    var calls = 0
    override def run(
        request: SbtPointContextRequest,
        globalBase: Path,
        receiptFile: Path,
        task: SbtFixedTask,
        timeout: scala.concurrent.duration.FiniteDuration
    ): SbtPointContextProcessOutcome =
      calls += 1
      outcome

  private def project(value: String): SbtProjectId =
    SbtProjectId.parse(value).fold(message => fail(message), identity)

  private def scalaVersion(value: String): SbtScalaVersion =
    SbtScalaVersion.parse(value).fold(message => fail(message), identity)

  private def encoded(value: String): String =
    java.util.Base64.getEncoder.encodeToString(value.getBytes(StandardCharsets.UTF_8))

  private def internal(
      value: String,
      role: SbtInternalDependencyRole,
      axis: SbtScalaVersion,
      directory: Path,
      present: Boolean
  ): SbtInternalDependencyReceipt =
    SbtInternalDependencyReceipt(
      projectRef = s"ThisBuild/$value",
      project = project(value),
      role = role,
      compileMapping = SbtCompileDependencyMapping.DefaultCompileToCompile,
      requestedScalaVersion = Some(scalaVersion("2.13.18")),
      effectiveScalaVersion = axis,
      configuration = SbtClasspathConfiguration.Compile,
      classDirectory = directory,
      classDirectoryPresent = present
    )

  private def deleteRecursively(root: Path): Unit =
    if Files.exists(root) then
      val paths = Files.walk(root)
      try paths.sorted(java.util.Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
