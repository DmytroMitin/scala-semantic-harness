ThisBuild / organization := "com.github.dmytromitin"
ThisBuild / scalaVersion := "3.3.3"
ThisBuild / version := "0.1.0-alpha.2"
ThisBuild / homepage := Some(url("https://github.com/DmytroMitin/scala-semantic-harness"))
ThisBuild / licenses := List(
  "Apache-2.0" -> url("https://www.apache.org/licenses/LICENSE-2.0.txt")
)
ThisBuild / scmInfo := Some(
  ScmInfo(
    browseUrl = url("https://github.com/DmytroMitin/scala-semantic-harness"),
    connection = "scm:git:https://github.com/DmytroMitin/scala-semantic-harness.git",
    devConnection = Some("scm:git:git@github.com:DmytroMitin/scala-semantic-harness.git")
  )
)
ThisBuild / developers := List(
  Developer(
    id = "dmytromitin",
    name = "Dmytro Mitin",
    email = "",
    url = url("https://github.com/DmytroMitin")
  )
)
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / publishMavenStyle := true
ThisBuild / pomIncludeRepository := (_ => false)

lazy val circeVersion = "0.14.9"
lazy val munitVersion = "1.0.0"
lazy val semanticdbVersion = "4.16.1"
lazy val presentationCompilerVersion = "3.3.3"

lazy val stage = taskKey[File]("Stage the semantic-scala CLI launcher")

def generatedVersionSettings(packageName: String) = Seq(
  Compile / sourceGenerators += Def.task {
    val output = (Compile / sourceManaged).value / packageName.replace('.', '/') / "BuildVersion.scala"
    val packageScope = packageName.split('.').last
    IO.write(
      output,
      s"""package $packageName
         |
         |private[$packageScope] object BuildVersion:
         |  val value: String = "${version.value}"
         |""".stripMargin
    )
    Seq(output)
  }.taskValue
)

lazy val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-deprecation",
    "-feature",
    "-unchecked"
  ),
  publish / skip := false,
  Test / publishArtifact := false,
  Compile / packageSrc / publishArtifact := true,
  Compile / packageDoc / publishArtifact := true
)

lazy val root = (project in file("."))
  .aggregate(
    core,
    cli,
    sbtRunner,
    semanticdbReader,
    presentationCompiler,
    semanticReconciliation,
    mcpServer,
    fpAnalyzers,
    benchmark
  )
  .settings(
    name := "scala-semantic-harness",
    publish / skip := true
  )

lazy val core = (project in file("modules/core"))
  .settings(commonSettings)
  .settings(
    name := "semantic-harness-core",
    description := "Internal core models for the semantic-scala applications",
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "org.scalameta" %% "munit" % munitVersion % Test
    )
  )

lazy val cli = (project in file("modules/cli"))
  .dependsOn(core, sbtRunner, semanticdbReader, presentationCompiler, semanticReconciliation, fpAnalyzers)
  .settings(commonSettings)
  .settings(generatedVersionSettings("semantic.harness.cli"))
  .settings(
    name := "semantic-scala-cli",
    description := "Command-line application for the semantic-scala evidence harness",
    Compile / mainClass := Some("semantic.harness.cli.Main"),
    libraryDependencies += "org.scalameta" %% "munit" % munitVersion % Test,
    stage := {
      val outputDir = target.value / "stage"
      val binDir = outputDir / "bin"
      val libDir = outputDir / "lib"
      val script = binDir / "semantic-scala"
      val main = (Compile / mainClass).value.getOrElse(sys.error("Compile / mainClass is not set"))
      val classpath = (Compile / fullClasspath).value.map(_.data)

      IO.delete(outputDir)
      IO.createDirectory(binDir)
      IO.createDirectory(libDir)

      val stagedClasspath = classpath.zipWithIndex.map { case (entry, index) =>
        val name =
          if (entry.isDirectory) s"classes-$index"
          else s"$index-${entry.getName}"
        val destination = libDir / name

        if (entry.isDirectory) IO.copyDirectory(entry, destination)
        else IO.copyFile(entry, destination)

        s"$$APP_HOME/lib/$name"
      }.mkString(java.io.File.pathSeparator)

      IO.write(
        script,
        s"""#!/usr/bin/env bash
           |set -euo pipefail
           |
           |SCRIPT_DIR="$$(cd "$$(dirname "$${BASH_SOURCE[0]}")" && pwd)"
           |APP_HOME="$$(cd "$$SCRIPT_DIR/.." && pwd)"
           |CLASSPATH="$stagedClasspath"
           |JAVA_ARGS=(-XX:+PerfDisableSharedMem)
           |if java --help-extra 2>&1 | grep -q -- "--sun-misc-unsafe-memory-access"; then
           |  JAVA_ARGS+=(--sun-misc-unsafe-memory-access=allow)
           |fi
           |
           |exec java "$${JAVA_ARGS[@]}" -cp "$$CLASSPATH" $main "$$@"
           |""".stripMargin
      )
      script.setExecutable(true)
      outputDir
    }
  )

lazy val sbtRunner = (project in file("modules/sbt-runner"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "semantic-harness-sbt-runner",
    description := "Internal sbt process integration for the semantic-scala applications",
    libraryDependencies += "org.scalameta" %% "munit" % munitVersion % Test
  )

lazy val semanticdbReader = (project in file("modules/semanticdb-reader"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "semantic-harness-semanticdb-reader",
    description := "Internal SemanticDB evidence reader for the semantic-scala applications",
    libraryDependencies ++= Seq(
      "org.scalameta" %% "semanticdb-shared" % semanticdbVersion cross CrossVersion.for3Use2_13,
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "org.scalameta" %% "munit" % munitVersion % Test
    )
  )

lazy val presentationCompiler = (project in file("modules/presentation-compiler"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "semantic-harness-presentation-compiler",
    description := "Internal Presentation Compiler integration for the semantic-scala applications",
    libraryDependencies ++= Seq(
      "org.scala-lang" %% "scala3-presentation-compiler" % presentationCompilerVersion,
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "org.scalameta" %% "munit" % munitVersion % Test
    )
  )

lazy val semanticReconciliation = (project in file("modules/semantic-reconciliation"))
  .dependsOn(core, semanticdbReader, presentationCompiler)
  .settings(commonSettings)
  .settings(
    name := "semantic-harness-semantic-reconciliation",
    description := "Internal static and dynamic evidence reconciliation for the semantic-scala applications",
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "org.scalameta" %% "munit" % munitVersion % Test
    )
  )

lazy val mcpServer = (project in file("modules/mcp-server"))
  .dependsOn(core, fpAnalyzers, presentationCompiler, semanticdbReader, semanticReconciliation)
  .settings(commonSettings)
  .settings(generatedVersionSettings("semantic.harness.mcp"))
  .settings(
    name := "semantic-harness-mcp-server",
    description := "Thin stdio MCP application backed by the semantic-scala CLI",
    Compile / mainClass := Some("semantic.harness.mcp.Main"),
    stage := {
      val outputDir = target.value / "stage"
      val binDir = outputDir / "bin"
      val libDir = outputDir / "lib"
      val script = binDir / "semantic-scala-mcp"
      val main = (Compile / mainClass).value.getOrElse(sys.error("Compile / mainClass is not set"))
      val classpath = (Compile / fullClasspath).value.map(_.data)

      IO.delete(outputDir)
      IO.createDirectory(binDir)
      IO.createDirectory(libDir)

      val stagedClasspath = classpath.zipWithIndex.map { case (entry, index) =>
        val name =
          if (entry.isDirectory) s"classes-$index"
          else s"$index-${entry.getName}"
        val destination = libDir / name

        if (entry.isDirectory) IO.copyDirectory(entry, destination)
        else IO.copyFile(entry, destination)

        s"$$APP_HOME/lib/$name"
      }.mkString(java.io.File.pathSeparator)

      IO.write(
        script,
        s"""#!/usr/bin/env bash
           |set -euo pipefail
           |
           |SCRIPT_DIR="$$(cd "$$(dirname "$${BASH_SOURCE[0]}")" && pwd)"
           |APP_HOME="$$(cd "$$SCRIPT_DIR/.." && pwd)"
           |CLASSPATH="$stagedClasspath"
           |
           |exec java -XX:+PerfDisableSharedMem -cp "$$CLASSPATH" $main "$$@"
           |""".stripMargin
      )
      script.setExecutable(true)
      outputDir
    },
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "org.scalameta" %% "munit" % munitVersion % Test
    )
  )

lazy val fpAnalyzers = (project in file("modules/fp-analyzers"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "semantic-harness-fp-analyzers",
    description := "Internal functional-programming analyzers for the semantic-scala applications",
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "org.scalameta" %% "munit" % munitVersion % Test
    )
  )

lazy val benchmark = (project in file("modules/benchmark"))
  .dependsOn(core)
  .settings(commonSettings)
  .settings(
    name := "semantic-harness-benchmark",
    description := "Non-published benchmark support for semantic-scala",
    publish / skip := true,
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "org.scalameta" %% "munit" % munitVersion % Test
    )
  )
