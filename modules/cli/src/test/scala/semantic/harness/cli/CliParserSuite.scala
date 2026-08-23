package semantic.harness.cli

import io.circe.parser.parse
import java.nio.file.Files
import java.nio.file.Path
import semantic.harness.sbt_runner.SbtClasspathConfiguration
import semantic.harness.sbt_runner.SbtClasspathCacheMode
import semantic.harness.sbt_runner.SbtProjectId

class CliParserSuite extends munit.FunSuite:
  test("parses help shortcuts"):
    assertEquals(CliParser.parse(Nil), ParseResult.Parsed(CliCommand.Help(None)))
    assertEquals(CliParser.parse(List("--help")), ParseResult.Parsed(CliCommand.Help(None)))
    assertEquals(CliParser.parse(List("help")), ParseResult.Parsed(CliCommand.Help(None)))

  test("dispatches known help topics and rejects unknown topics"):
    val known = CliApp.run(List("help", "semanticdb-for-source"))
    assertEquals(known.exitCode, 0)
    assertEquals(known.stderr, None)
    assert(
      known.stdout.exists(
        _.contains("semantic-scala semanticdb-for-source --file <path> --workspace <path> [--json]")
      )
    )

    val unknown = CliApp.run(List("help", "not-a-command"))
    assertEquals(unknown.exitCode, 1)
    assertEquals(unknown.stdout, None)
    assert(unknown.stderr.exists(_.contains("Unknown help topic: not-a-command")))

    val status = CliApp.run(List("help", "semanticdb-status"))
    assertEquals(status.exitCode, 0)
    assert(status.stdout.exists(_.contains("--schema-version v1|v2")))
    assert(status.stdout.exists(_.contains("v1 is the default")))
    assert(status.stdout.exists(_.contains("coverage is NotAssessed")))

    val coverage = CliApp.run(List("help", "semanticdb-coverage"))
    assertEquals(coverage.exitCode, 0)
    assert(coverage.stdout.exists(_.contains("recursive Scala/Java source inventory")))
    assert(coverage.stdout.exists(_.contains("generated and metadata directories are excluded")))
    assert(coverage.stdout.exists(_.contains("does not imply freshness or build-target completeness")))
    assert(coverage.stdout.exists(_.contains("Does not generate SemanticDB or run sbt")))

  test("build-oracle help documents optional bounded sbt project selection"):
    List(
      "compile" -> "Compile / compile",
      "errors" -> "Compile / compile",
      "test" -> "Test / test"
    ).foreach { case (command, scope) =>
      val result = CliApp.run(List("help", command))
      assertEquals(result.exitCode, 0)
      val text = result.stdout.getOrElse(fail(s"missing help for $command"))
      assert(
        text.contains(
          s"semantic-scala $command [--sbt-project <id>] [--sbt-java-home <absolute-directory>] [--json]"
        ),
        clue(text)
      )
      assert(text.contains(scope), clue(text))
      assert(text.contains("validated sbt project ID"), clue(text))
      assert(text.contains("not whole-workspace correctness"), clue(text))
    }

  test("parses version shortcuts"):
    assertEquals(CliParser.parse(List("version")), ParseResult.Parsed(CliCommand.Version))
    assertEquals(CliParser.parse(List("--version")), ParseResult.Parsed(CliCommand.Version))

  test("parses task 002 commands with optional json flag"):
    assertEquals(CliParser.parse(List("compile")), ParseResult.Parsed(CliCommand.Compile(None, false)))
    assertEquals(CliParser.parse(List("compile", "--json")), ParseResult.Parsed(CliCommand.Compile(None, true)))
    assertEquals(CliParser.parse(List("test", "--json")), ParseResult.Parsed(CliCommand.Test(None, true)))
    assertEquals(CliParser.parse(List("errors", "--json")), ParseResult.Parsed(CliCommand.Errors(None, true)))

  test("parses one optional safe sbt project for build-oracle commands"):
    assertEquals(
      CliParser.parse(List("compile", "--sbt-project", "core2_13", "--json")),
      ParseResult.Parsed(CliCommand.Compile(Some(project("core2_13")), true))
    )
    assertEquals(
      CliParser.parse(List("errors", "--json", "--sbt-project", "app-2")),
      ParseResult.Parsed(CliCommand.Errors(Some(project("app-2")), true))
    )
    assertEquals(
      CliParser.parse(List("test", "--sbt-project", "tests_2")),
      ParseResult.Parsed(CliCommand.Test(Some(project("tests_2")), false))
    )

  test("build-oracle commands accept one absolute sbt Java home and reject unsafe forms"):
    List("compile", "errors", "test").foreach { command =>
      val parsed = CliParser.parse(
        List(command, "--sbt-java-home", "/opt/jdks/target", "--json")
      )
      assert(parsed match
        case ParseResult.Parsed(CliCommand.Compile(_, _, Some(value))) =>
          value == "/opt/jdks/target"
        case ParseResult.Parsed(CliCommand.Errors(_, _, Some(value))) =>
          value == "/opt/jdks/target"
        case ParseResult.Parsed(CliCommand.Test(_, _, Some(value))) =>
          value == "/opt/jdks/target"
        case _ => false
      , clue(parsed))

      val relative = CliParser.parse(
        List(command, "--sbt-java-home", "relative/jdk", "--json")
      )
      val duplicate = CliParser.parse(
        List(
          command,
          "--sbt-java-home",
          "/opt/jdks/one",
          "--sbt-java-home",
          "/opt/jdks/two"
        )
      )
      assert(relative.isInstanceOf[ParseResult.Invalid], clue(relative))
      assert(duplicate.isInstanceOf[ParseResult.Invalid], clue(duplicate))
    }

  test("rejects invalid and duplicate sbt projects for build-oracle commands"):
    List("compile", "errors", "test").foreach { command =>
      val invalid = CliParser.parse(List(command, "--sbt-project", "bad;test", "--json"))
      assert(invalid.isInstanceOf[ParseResult.Invalid], clue(invalid))
      assert(invalid.toString.contains("sbt project ID must start with a letter"), clue(invalid))

      val duplicate = CliParser.parse(
        List(command, "--sbt-project", "core2_13", "--sbt-project", "other")
      )
      assert(duplicate.isInstanceOf[ParseResult.Invalid], clue(duplicate))
      assert(duplicate.toString.contains("may only be supplied once"), clue(duplicate))
    }

  test("parses symbols command with semanticdb path and optional json flag"):
    assertEquals(
      CliParser.parse(List("symbols", "--semanticdb", "Main.scala.semanticdb")),
      ParseResult.Parsed(CliCommand.Symbols("Main.scala.semanticdb", false))
    )
    assertEquals(
      CliParser.parse(List("symbols", "--semanticdb", "Main.scala.semanticdb", "--json")),
      ParseResult.Parsed(CliCommand.Symbols("Main.scala.semanticdb", true))
    )
    assertEquals(
      CliParser.parse(List("symbols", "--json", "--semanticdb", "Main.scala.semanticdb")),
      ParseResult.Parsed(CliCommand.Symbols("Main.scala.semanticdb", true))
    )

  test("parses symbol-at command with source position and optional json flag"):
    assertEquals(
      CliParser.parse(List("symbol-at", "--file", "Main.scala", "--line", "6", "--col", "16")),
      ParseResult.Parsed(CliCommand.SymbolAt("Main.scala", 6, 16, false))
    )
    assertEquals(
      CliParser.parse(List("symbol-at", "--file", "Main.scala", "--line", "6", "--col", "16", "--json")),
      ParseResult.Parsed(CliCommand.SymbolAt("Main.scala", 6, 16, true))
    )

  test("parses point-evidence with an explicit workspace, source, and UTF-16 position"):
    assertEquals(
      CliParser.parse(
        List(
          "point-evidence",
          "--json",
          "--col",
          "16",
          "--workspace",
          ".",
          "--file",
          "src/main/scala/example/Main.scala",
          "--line",
          "6"
        )
      ),
      ParseResult.Parsed(
        CliCommand.PointEvidence(
          file = "src/main/scala/example/Main.scala",
          workspace = ".",
          line = 6,
          column = 16,
          json = true
        )
      )
    )

  test("parses the CLI-only fixed-Compile TASTy point evidence contract"):
    val args = List(
      "tasty-point-evidence",
      "--workspace", ".",
      "--sbt-project", "pluginTests",
      "--file", "plugin-tests/src/main/scala/Example.scala",
      "--line", "10",
      "--col", "36",
      "--sbt-java-home", "/opt/jdks/target",
      "--json"
    )
    assertEquals(
      CliParser.parse(args),
      ParseResult.Parsed(
        CliCommand.TastyPointEvidence(
          ".",
          project("pluginTests"),
          "plugin-tests/src/main/scala/Example.scala",
          10,
          36,
          Some("/opt/jdks/target"),
          json = true
        )
      )
    )

  test("TASTy point evidence requires project and JSON and rejects unsafe selectors"):
    val valid = List(
      "tasty-point-evidence", "--workspace", ".", "--sbt-project", "app",
      "--file", "Main.scala", "--line", "1", "--col", "1", "--json"
    )
    val invalid = List(
      valid.patch(3, Nil, 2),
      valid.filterNot(_ == "--json"),
      valid.updated(valid.indexOf("app"), "bad;task"),
      valid.updated(valid.indexOf("1"), "0"),
      valid ++ List("--sbt-project", "other"),
      valid ++ List("--configuration", "Test")
    )
    invalid.foreach(args => assert(CliParser.parse(args).isInstanceOf[ParseResult.Invalid], clue(args)))

    val help = CliApp.run(List("help", "tasty-point-evidence"))
    assertEquals(help.exitCode, 0)
    assert(help.stdout.exists(_.contains("fixed Compile")))
    assert(help.stdout.exists(_.contains("domain outcomes return JSON with exit code 0")))

  test("rejects incomplete, non-positive, duplicate, and unsupported point-evidence inputs"):
    val base = List(
      "point-evidence",
      "--workspace",
      ".",
      "--file",
      "Main.scala",
      "--line",
      "1",
      "--col",
      "1"
    )
    val invalid = List(
      base.dropRight(2),
      base.updated(base.indexOf("1"), "0"),
      base ++ List("--line", "2"),
      base ++ List("--semanticdb", "Main.scala.semanticdb")
    )

    invalid.foreach(args =>
      assert(CliParser.parse(args).isInstanceOf[ParseResult.Invalid], clue(args))
    )
    assertEquals(
      CliParser.parse(List("symbol-at", "--json", "--file", "Main.scala", "--line", "6", "--col", "16")),
      ParseResult.Parsed(CliCommand.SymbolAt("Main.scala", 6, 16, true))
    )

  test("parses infer-type with flexible order and repeatable classpath entries"):
    assertEquals(
      CliParser.parse(List("infer-type", "--file", "Main.scala", "--line", "6", "--col", "16")),
      ParseResult.Parsed(
        CliCommand.InferType("Main.scala", 6, 16, None, Nil, None, None, None, false)
      )
    )
    assertEquals(
      CliParser.parse(
        List(
          "infer-type",
          "--json",
          "--classpath",
          "api/target/classes",
          "--workspace",
          ".",
          "--col",
          "16",
          "--classpath",
          "lib/domain.jar",
          "--file",
          "Main.scala",
          "--line",
          "6"
        )
      ),
      ParseResult.Parsed(
        CliCommand.InferType(
          "Main.scala",
          6,
          16,
          Some("."),
          List("api/target/classes", "lib/domain.jar"),
          None,
          None,
          None,
          true
        )
      )
    )

  test("parses explicit safe sbt project and configuration selectors"):
    val parsed = CliParser.parse(
      List(
        "infer-type",
        "--sbt-configuration",
        "Test",
        "--file",
        "TestConsumer.scala",
        "--workspace",
        ".",
        "--col",
        "9",
        "--sbt-project",
        "app-2",
        "--line",
        "4",
        "--json"
      )
    )

    assertEquals(
      parsed,
      ParseResult.Parsed(
        CliCommand.InferType(
          "TestConsumer.scala",
          4,
          9,
          Some("."),
          Nil,
          Some(project("app-2")),
          Some(SbtClasspathConfiguration.Test),
          Some(SbtClasspathCacheMode.Fresh),
          true
        )
      )
    )

  test("parses exact explicit sbt cache modes and defaults omission to fresh"):
    val base = List(
      "infer-type",
      "--file",
      "Main.scala",
      "--line",
      "1",
      "--col",
      "1",
      "--workspace",
      ".",
      "--sbt-project",
      "app-2",
      "--sbt-configuration",
      "Compile"
    )

    List(
      "fresh" -> SbtClasspathCacheMode.Fresh,
      "refresh" -> SbtClasspathCacheMode.Refresh,
      "reuse" -> SbtClasspathCacheMode.Reuse
    ).foreach { case (text, expected) =>
      val parsed = CliParser.parse(base ++ List("--sbt-cache-mode", text))
      assert(
        parsed.toString.contains(s"Some($expected)"),
        clue(parsed)
      )
    }
    assert(CliParser.parse(base).toString.contains("Some(Fresh)"))

  test("target Java selection is accepted only for sbt-backed infer-type commands"):
    val home = Path.of("/opt/task166-jdk")
    val infer = List(
      "infer-type",
      "--file",
      "Main.scala",
      "--line",
      "1",
      "--col",
      "1",
      "--workspace",
      ".",
      "--sbt-project",
      "app-2",
      "--sbt-configuration",
      "Compile"
    )
    val batch = List(
      "infer-type-batch",
      "--requests",
      "batch.json",
      "--workspace",
      ".",
      "--sbt-project",
      "app-2",
      "--sbt-configuration",
      "Compile",
      "--json"
    )

    assert(
      CliParser.parse(infer ++ List("--sbt-java-home", home.toString)).toString
        .contains(s"Some(${home.toString})")
    )
    assert(
      CliParser.parse(batch ++ List("--sbt-java-home", home.toString)).toString
        .contains(s"Some(${home.toString})")
    )
    List(
      infer.take(7) ++ List("--sbt-java-home", home.toString),
      infer ++ List("--sbt-java-home", "relative-jdk"),
      infer ++ List("--sbt-java-home", home.toString, "--sbt-java-home", home.toString),
      batch ++ List("--sbt-java-home", "relative-jdk")
    ).foreach(args =>
      assert(CliParser.parse(args).isInstanceOf[ParseResult.Invalid], clue(args))
    )

  test("rejects cache mode without sbt context, unsupported values, and duplicates"):
    val query = List(
      "infer-type",
      "--file",
      "Main.scala",
      "--line",
      "1",
      "--col",
      "1"
    )
    val sbt = query ++ List(
      "--workspace",
      ".",
      "--sbt-project",
      "app-2",
      "--sbt-configuration",
      "Compile"
    )
    val invalid = List(
      query ++ List("--sbt-cache-mode", "reuse"),
      sbt ++ List("--sbt-cache-mode", "Reuse"),
      sbt ++ List("--sbt-cache-mode", "reuse", "--sbt-cache-mode", "fresh")
    )

    invalid.foreach(args =>
      assert(CliParser.parse(args).isInstanceOf[ParseResult.Invalid], clue(args))
    )

  test("parses bounded infer-type-batch selectors and defaults cache mode to fresh"):
    val base = List(
      "infer-type-batch",
      "--requests",
      "batch.json",
      "--workspace",
      ".",
      "--sbt-project",
      "cli",
      "--sbt-configuration",
      "Compile",
      "--json"
    )
    assertEquals(
      CliParser.parse(base),
      ParseResult.Parsed(
        CliCommand.InferTypeBatch(
          "batch.json",
          ".",
          project("cli"),
          SbtClasspathConfiguration.Compile,
          SbtClasspathCacheMode.Fresh,
          json = true
        )
      )
    )
    assert(
      CliParser
        .parse(base ++ List("--sbt-cache-mode", "reuse"))
        .toString
        .contains("Reuse")
    )

  test("infer-type-batch requires JSON and every explicit sbt selector"):
    val complete = List(
      "infer-type-batch",
      "--requests",
      "batch.json",
      "--workspace",
      ".",
      "--sbt-project",
      "cli",
      "--sbt-configuration",
      "Compile",
      "--json"
    )
    List(
      complete.filterNot(_ == "--json"),
      complete.drop(2),
      complete.patch(complete.indexOf("--workspace"), Nil, 2),
      complete.patch(complete.indexOf("--sbt-project"), Nil, 2),
      complete.patch(complete.indexOf("--sbt-configuration"), Nil, 2),
      complete ++ List("--requests", "other.json"),
      complete ++ List("--classpath", "classes"),
      complete ++ List("--sbt-cache-mode", "automatic")
    ).foreach(args =>
      assert(CliParser.parse(args).isInstanceOf[ParseResult.Invalid], clue(args))
    )

  test("rejects unsafe, incomplete, mixed, duplicate, and unsupported sbt selectors"):
    val base = List(
      "infer-type",
      "--file",
      "Main.scala",
      "--line",
      "1",
      "--col",
      "1"
    )
    val invalid = List(
      base ++ List("--sbt-project", "app-2"),
      base ++ List("--sbt-configuration", "Compile"),
      base ++ List("--sbt-project", "app-2", "--sbt-configuration", "Compile"),
      base ++ List(
        "--workspace",
        ".",
        "--classpath",
        "classes",
        "--sbt-project",
        "app-2",
        "--sbt-configuration",
        "Compile"
      ),
      base ++ List(
        "--workspace",
        ".",
        "--sbt-project",
        "app;test",
        "--sbt-configuration",
        "Compile"
      ),
      base ++ List(
        "--workspace",
        ".",
        "--sbt-project",
        "app/Compile",
        "--sbt-configuration",
        "Compile"
      ),
      base ++ List(
        "--workspace",
        ".",
        "--sbt-project",
        "app-2",
        "--sbt-configuration",
        "compile"
      ),
      base ++ List(
        "--workspace",
        ".",
        "--sbt-project",
        "app-2",
        "--sbt-project",
        "api",
        "--sbt-configuration",
        "Compile"
      ),
      base ++ List(
        "--workspace",
        ".",
        "--sbt-project",
        "app-2",
        "--sbt-configuration",
        "Compile",
        "--sbt-configuration",
        "Test"
      )
    )

    invalid.foreach(args =>
      assert(CliParser.parse(args).isInstanceOf[ParseResult.Invalid], clue(args))
    )

  test("parses reconcile-symbol command with source position, SemanticDB path, and optional json flag"):
    assertEquals(
      CliParser.parse(List("reconcile-symbol", "--file", "Main.scala", "--line", "6", "--col", "16", "--semanticdb", "Main.scala.semanticdb")),
      ParseResult.Parsed(CliCommand.ReconcileSymbol("Main.scala", 6, 16, "Main.scala.semanticdb", false))
    )
    assertEquals(
      CliParser.parse(List("reconcile-symbol", "--file", "Main.scala", "--line", "6", "--col", "16", "--semanticdb", "Main.scala.semanticdb", "--json")),
      ParseResult.Parsed(CliCommand.ReconcileSymbol("Main.scala", 6, 16, "Main.scala.semanticdb", true))
    )
    assertEquals(
      CliParser.parse(List("reconcile-symbol", "--json", "--file", "Main.scala", "--line", "6", "--col", "16", "--semanticdb", "Main.scala.semanticdb")),
      ParseResult.Parsed(CliCommand.ReconcileSymbol("Main.scala", 6, 16, "Main.scala.semanticdb", true))
    )

  test("parses effect-summary command with source file and optional json flag"):
    assertEquals(
      CliParser.parse(List("effect-summary", "--file", "UserRepo.scala")),
      ParseResult.Parsed(CliCommand.EffectSummary("UserRepo.scala", false))
    )
    assertEquals(
      CliParser.parse(List("effect-summary", "--file", "UserRepo.scala", "--json")),
      ParseResult.Parsed(CliCommand.EffectSummary("UserRepo.scala", true))
    )
    assertEquals(
      CliParser.parse(List("effect-summary", "--json", "--file", "UserRepo.scala")),
      ParseResult.Parsed(CliCommand.EffectSummary("UserRepo.scala", true))
    )

  test("parses semanticdb-status command with workspace and optional json flag"):
    assertEquals(
      CliParser.parse(List("semanticdb-status", "--workspace", ".")),
      ParseResult.Parsed(CliCommand.SemanticdbStatus(".", SemanticdbStatusVersion.V1, false))
    )
    assertEquals(
      CliParser.parse(List("semanticdb-status", "--workspace", ".", "--json")),
      ParseResult.Parsed(CliCommand.SemanticdbStatus(".", SemanticdbStatusVersion.V1, true))
    )
    assertEquals(
      CliParser.parse(List("semanticdb-status", "--schema-version", "v1", "--json", "--workspace", ".")),
      ParseResult.Parsed(CliCommand.SemanticdbStatus(".", SemanticdbStatusVersion.V1, true))
    )
    assertEquals(
      CliParser.parse(List("semanticdb-status", "--json", "--workspace", ".", "--schema-version", "v2")),
      ParseResult.Parsed(CliCommand.SemanticdbStatus(".", SemanticdbStatusVersion.V2, true))
    )

  test("parses semanticdb-for-source command with source, workspace, and optional json flag"):
    assertEquals(
      CliParser.parse(List("semanticdb-for-source", "--file", "src/main/scala/example/Main.scala", "--workspace", ".")),
      ParseResult.Parsed(CliCommand.SemanticdbForSource("src/main/scala/example/Main.scala", ".", false))
    )
    assertEquals(
      CliParser.parse(List("semanticdb-for-source", "--json", "--workspace", ".", "--file", "src/main/scala/example/Main.scala")),
      ParseResult.Parsed(CliCommand.SemanticdbForSource("src/main/scala/example/Main.scala", ".", true))
    )
    assertEquals(
      CliParser.parse(List("semanticdb-status", "--json", "--workspace", ".")),
      ParseResult.Parsed(CliCommand.SemanticdbStatus(".", SemanticdbStatusVersion.V1, true))
    )

  test("parses semanticdb-coverage command with flexible option order"):
    assertEquals(
      CliParser.parse(List("semanticdb-coverage", "--workspace", ".")),
      ParseResult.Parsed(CliCommand.SemanticdbCoverage(".", false))
    )
    assertEquals(
      CliParser.parse(List("semanticdb-coverage", "--workspace", ".", "--json")),
      ParseResult.Parsed(CliCommand.SemanticdbCoverage(".", true))
    )

  test("parses the two usages target productions and canonical selectors"):
    val explicit = CliParser.parse(
      List(
        "usages", "--source-set", "test", "--workspace", ".", "--module", "z",
        "--manifest", "usages.json", "--symbol", "example/Foo#bar().",
        "--module", "a", "--module", "a", "--include-definitions",
        "--include-generated", "--limit", "7", "--json"
      )
    )
    assert(explicit.toString.contains("ExplicitGlobal(example/Foo#bar().)"), clue(explicit))
    assert(explicit.toString.contains("List(a, z)"), clue(explicit))
    assert(explicit.toString.contains("List(test)"), clue(explicit))

    val point = CliParser.parse(
      List(
        "usages", "--workspace", ".", "--manifest", "usages.json",
        "--file", "src/Foo.scala", "--line", "3", "--col", "5",
        "--semanticdb", "out/Foo.scala.semanticdb"
      )
    )
    assert(point.toString.contains("Point(src/Foo.scala,3,5,out/Foo.scala.semanticdb)"), clue(point))

  test("executes every retained Task 091 grammar case against the real parser"):
    val path = repositoryFile(
      "benchmarks/runs/v0/usages-by-symbol-public-cli-contract-schema-design-gate/examples/grammar-cases.json"
    )
    val json = parse(Files.readString(path)).fold(error => fail(error.message), identity)
    val cases = json.asArray.getOrElse(fail("Expected grammar case array"))
    cases.foreach { value =>
      val cursor = value.hcursor
      val name = cursor.get[String]("name").fold(error => fail(error.message), identity)
      val expected = cursor.get[Boolean]("valid").fold(error => fail(error.message), identity)
      val args = cursor.get[List[String]]("args").fold(error => fail(error.message), identity)
      val actual = CliParser.parse(args).isInstanceOf[ParseResult.Parsed]
      assertEquals(actual, expected, name)
    }

  test("usages rejects duplicate singleton and switch flags plus mixed partial and overflow targets"):
    val base = List("usages", "--workspace", ".", "--manifest", "usages.json")
    val invalid = List(
      base ++ List("--symbol", "x#", "--workspace", "."),
      base ++ List("--symbol", "x#", "--json", "--json"),
      base ++ List("--symbol", "x#", "--include-definitions", "--include-definitions"),
      base ++ List("--symbol", "x#", "--file", "A.scala", "--line", "1", "--col", "1", "--semanticdb", "a.semanticdb"),
      base ++ List("--file", "A.scala", "--line", "1"),
      base ++ List("--file", "A.scala", "--line", "2147483648", "--col", "1", "--semanticdb", "a.semanticdb"),
      base ++ List("--symbol", "x#", "--limit", "501"),
      base ++ List("--symbol", "x#", "--module", "bad/value")
    )
    invalid.foreach(args => assert(CliParser.parse(args).isInstanceOf[ParseResult.Invalid], clue(args)))

    List("plain-name", ";multi", "bad\u0000symbol").foreach { symbol =>
      assert(
        CliParser.parse(base ++ List("--symbol", symbol)).isInstanceOf[ParseResult.Invalid],
        clue(symbol)
      )
    }

    val jsonFailure = CliApp.run(base ++ List("--symbol", "x#", "--json", "--json"))
    assertEquals(jsonFailure.exitCode, 1)
    assertEquals(jsonFailure.stderr, None)
    assert(jsonFailure.stdout.exists(_.contains("semantic-scala.usages-failure.v1")))
    assert(jsonFailure.stdout.exists(_.contains("InvalidInput")))
    assertEquals(
      CliParser.parse(List("semanticdb-coverage", "--json", "--workspace", ".")),
      ParseResult.Parsed(CliCommand.SemanticdbCoverage(".", true))
    )

  test("rejects unknown commands and unsupported flags"):
    assert(CliParser.parse(List("unknown")).isInstanceOf[ParseResult.Invalid])
    assert(CliParser.parse(List("compile", "--verbose")).isInstanceOf[ParseResult.Invalid])
    assert(CliParser.parse(List("semanticdb-status", "--workspace")).isInstanceOf[ParseResult.Invalid])
    assert(CliParser.parse(List("semanticdb-status", "--workspace", ".", "--verbose")).isInstanceOf[ParseResult.Invalid])
    val invalidSchema = CliParser.parse(List("semanticdb-status", "--workspace", ".", "--schema-version", "v3"))
    assert(invalidSchema.isInstanceOf[ParseResult.Invalid])
    assert(invalidSchema.toString.contains("Unsupported schema version"))
    assert(CliParser.parse(List("semanticdb-for-source", "--workspace", ".")).isInstanceOf[ParseResult.Invalid])
    assert(CliParser.parse(List("semanticdb-for-source", "--file", "Main.scala", "--workspace", ".", "--verbose")).isInstanceOf[ParseResult.Invalid])
    assert(CliParser.parse(List("semanticdb-coverage", "--workspace")).isInstanceOf[ParseResult.Invalid])
    assert(CliParser.parse(List("semanticdb-coverage", "--workspace", ".", "--verbose")).isInstanceOf[ParseResult.Invalid])
    assert(CliParser.parse(List("symbols", "--semanticdb")).isInstanceOf[ParseResult.Invalid])
    assert(CliParser.parse(List("symbol-at", "--file", "Main.scala", "--line", "x", "--col", "1")).isInstanceOf[ParseResult.Invalid])
    assert(CliParser.parse(List("symbol-at", "--file", "Main.scala", "--line", "1")).isInstanceOf[ParseResult.Invalid])
    assert(CliParser.parse(List("symbol-at", "--file", "Main.scala", "--line", "1", "--col", "1", "--classpath", "classes")).isInstanceOf[ParseResult.Invalid])
    assert(CliParser.parse(List("infer-type", "--file", "Main.scala", "--line", "x", "--col", "1")).isInstanceOf[ParseResult.Invalid])
    assert(CliParser.parse(List("infer-type", "--file", "Main.scala", "--line", "1")).isInstanceOf[ParseResult.Invalid])
    assert(CliParser.parse(List("infer-type", "--file", "Main.scala", "--line", "1", "--col", "1", "--classpath")).isInstanceOf[ParseResult.Invalid])
    assert(CliParser.parse(List("infer-type", "--file", "Main.scala", "--line", "1", "--col", "1", "--unknown", "value")).isInstanceOf[ParseResult.Invalid])
    assert(CliParser.parse(List("infer-type", "--file", "Main.scala", "--file", "Other.scala", "--line", "1", "--col", "1")).isInstanceOf[ParseResult.Invalid])
    assert(CliParser.parse(List("infer-type", "--file", "Main.scala", "--line", "1", "--col", "1", "--schema-version", "v1")).isInstanceOf[ParseResult.Invalid])
    assert(CliParser.parse(List("reconcile-symbol", "--file", "Main.scala", "--line", "x", "--col", "1", "--semanticdb", "Main.scala.semanticdb")).isInstanceOf[ParseResult.Invalid])
    assert(CliParser.parse(List("reconcile-symbol", "--file", "Main.scala", "--line", "1", "--col", "1")).isInstanceOf[ParseResult.Invalid])
    assert(CliParser.parse(List("reconcile-symbol", "--file", "Main.scala", "--line", "1", "--col", "1", "--semanticdb", "Main.scala.semanticdb", "--verbose")).isInstanceOf[ParseResult.Invalid])
    assert(CliParser.parse(List("effect-summary", "--file")).isInstanceOf[ParseResult.Invalid])
    assert(CliParser.parse(List("effect-summary", "--file", "UserRepo.scala", "--verbose")).isInstanceOf[ParseResult.Invalid])

  private def project(value: String): SbtProjectId =
    SbtProjectId.parse(value).fold(message => fail(message), identity)

  private def repositoryFile(relative: String): Path =
    Iterator.iterate(Path.of("").toAbsolutePath)(_.getParent)
      .takeWhile(_ != null)
      .map(_.resolve(relative))
      .find(Files.exists(_))
      .getOrElse(fail(s"Unable to locate repository file: $relative"))
