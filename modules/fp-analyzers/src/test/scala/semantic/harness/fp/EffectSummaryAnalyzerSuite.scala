package semantic.harness.fp

import io.circe.parser.decode
import io.circe.syntax.*
import java.nio.file.Path

class EffectSummaryAnalyzerSuite extends munit.FunSuite:
  test("EffectSummaryReport encodes and decodes as JSON"):
    val report = EffectSummaryReport(
      source = "UserRepo.scala",
      methods = List(
        EffectMethodSummary(
          name = "find",
          range = Some(EffectSourceRange(2, 2, 2, 42)),
          declaredReturnType = Some("F[Option[User]]"),
          inferredReturnType = None,
          effectCategory = "generic-effect",
          confidence = "declared",
          notes = List("Outer return type is an abstract unary effect F[_]."),
          ownerName = Some("UserRepo"),
          qualifiedName = Some("UserRepo.find"),
          enclosingKind = Some("trait"),
          packageName = Some("example"),
          packageQualifiedName = Some("example.UserRepo.find"),
          sourceFile = Some("UserRepo.scala")
        )
      )
    )

    val json = report.asJson.noSpaces
    assert(json.contains(""""schemaVersion":"semantic-scala.effect-summary.v1""""))
    assert(json.contains(""""ownerName":"UserRepo""""))
    assert(json.contains(""""qualifiedName":"UserRepo.find""""))
    assert(json.contains(""""enclosingKind":"trait""""))
    assert(json.contains(""""packageName":"example""""))
    assert(json.contains(""""packageQualifiedName":"example.UserRepo.find""""))
    assert(json.contains(""""sourceFile":"UserRepo.scala""""))
    assertEquals(decode[EffectSummaryReport](json), Right(report))

  test("EffectSummaryReport decodes legacy JSON without schemaVersion"):
    val json =
      """{
        |  "source": "UserRepo.scala",
        |  "methods": []
        |}""".stripMargin

    val decoded = decode[EffectSummaryReport](json)
    assertEquals(decoded.map(_.schemaVersion), Right(EffectSummaryReport.SchemaVersion))
    assertEquals(decoded.map(_.source), Right("UserRepo.scala"))
    assertEquals(decoded.map(_.methods), Right(Nil))

  test("EffectMethodSummary decodes legacy JSON without optional context fields"):
    val json =
      """{
        |  "name": "find",
        |  "range": null,
        |  "declaredReturnType": "F[Option[User]]",
        |  "inferredReturnType": null,
        |  "effectCategory": "generic-effect",
        |  "confidence": "declared",
        |  "notes": []
        |}""".stripMargin

    val decoded = decode[EffectMethodSummary](json)
    assertEquals(decoded.map(_.ownerName), Right(None))
    assertEquals(decoded.map(_.qualifiedName), Right(None))
    assertEquals(decoded.map(_.enclosingKind), Right(None))
    assertEquals(decoded.map(_.packageName), Right(None))
    assertEquals(decoded.map(_.packageQualifiedName), Right(None))
    assertEquals(decoded.map(_.sourceFile), Right(None))

  test("classifies declared effect categories"):
    assertCategory("Option[User]", "option")
    assertCategory("Either[String, User]", "either")
    assertCategory("Future[User]", "future")
    assertCategory("IO[Unit]", "io")
    assertCategory("ZIO[Any, Throwable, User]", "zio")
    assertCategory("Task[User]", "zio")
    assertCategory("UIO[Unit]", "zio")

  test("classifies generic and nested effects by outer return type"):
    assertCategory("F[User]", "generic-effect")
    assertCategory("G[Unit]", "generic-effect")
    val (category, confidence, notes) = EffectSummaryAnalyzer.classify("F[Option[User]]")
    assertEquals(category, "generic-effect")
    assertEquals(confidence, "declared")
    assert(notes.exists(_.contains("F[_]")))

  test("classifies plain and unknown return types conservatively"):
    assertCategory("String", "plain")
    assertCategory("Int", "plain")
    assertCategory("User", "plain")
    assertCategory("Validated[String, User]", "unknown")

  test("summarizes fixture methods and marks inferred return types unknown"):
    val report = EffectSummaryAnalyzer
      .summarize(Path.of("modules/fp-analyzers/src/test/resources/effect-fixtures/simple/UserRepo.scala"))
      .fold(message => fail(message), identity)

    assertEquals(report.schemaVersion, EffectSummaryReport.SchemaVersion)
    val methods = report.methods.map(method => method.name -> method).toMap
    assertEquals(methods("find").declaredReturnType, Some("F[Option[User]]"))
    assertEquals(methods("find").effectCategory, "generic-effect")
    assertEquals(methods("find").ownerName, Some("UserRepo"))
    assertEquals(methods("find").qualifiedName, Some("UserRepo.find"))
    assertEquals(methods("find").enclosingKind, Some("trait"))
    assertEquals(methods("find").packageName, Some("example"))
    assertEquals(methods("find").packageQualifiedName, Some("example.UserRepo.find"))
    assertEquals(methods("find").sourceFile, Some("modules/fp-analyzers/src/test/resources/effect-fixtures/simple/UserRepo.scala"))
    assertEquals(methods("maybe").effectCategory, "option")
    assertEquals(methods("parse").effectCategory, "either")
    assertEquals(methods("cached").effectCategory, "future")
    assertEquals(methods("flush").effectCategory, "io")
    assertEquals(methods("load").effectCategory, "zio")
    assertEquals(methods("task").effectCategory, "zio")
    assertEquals(methods("unit").effectCategory, "zio")
    assertEquals(methods("name").effectCategory, "plain")
    assertEquals(methods("inferred").declaredReturnType, None)
    assertEquals(methods("inferred").inferredReturnType, None)
    assertEquals(methods("inferred").effectCategory, "unknown")
    assertEquals(methods("inferred").confidence, "unknown")
    assert(methods("inferred").notes.exists(_.contains("does not infer")))

  test("extracts owner context for parser and main object methods"):
    val report = EffectSummaryAnalyzer.summarizeSource(
      "Main.scala",
      """package example
        |
        |final case class User(name: String)
        |
        |object Parser:
        |  def parseOption(raw: String): Option[User] = ???
        |  def parseEither(raw: String): Either[String, User] = ???
        |
        |object Main:
        |  def userName(raw: String): Either[String, String] = ???
        |""".stripMargin
    )

    val methods = report.methods.map(method => method.name -> method).toMap
    assertEquals(methods("parseOption").ownerName, Some("Parser"))
    assertEquals(methods("parseOption").qualifiedName, Some("Parser.parseOption"))
    assertEquals(methods("parseOption").enclosingKind, Some("object"))
    assertEquals(methods("parseOption").packageName, Some("example"))
    assertEquals(methods("parseOption").packageQualifiedName, Some("example.Parser.parseOption"))
    assertEquals(methods("parseOption").sourceFile, Some("Main.scala"))
    assertEquals(methods("parseEither").ownerName, Some("Parser"))
    assertEquals(methods("parseEither").qualifiedName, Some("Parser.parseEither"))
    assertEquals(methods("parseEither").packageName, Some("example"))
    assertEquals(methods("parseEither").packageQualifiedName, Some("example.Parser.parseEither"))
    assertEquals(methods("userName").ownerName, Some("Main"))
    assertEquals(methods("userName").qualifiedName, Some("Main.userName"))
    assertEquals(methods("userName").packageQualifiedName, Some("example.Main.userName"))

  test("extracts dotted package context for object methods"):
    val report = EffectSummaryAnalyzer.summarizeSource(
      "Parser.scala",
      """package example.foo
        |
        |final case class User(name: String)
        |
        |object Parser:
        |  def parseEither(raw: String): Either[String, User] = ???
        |""".stripMargin
    )

    val method = report.methods.find(_.name == "parseEither").getOrElse(fail("Expected parseEither summary"))
    assertEquals(method.qualifiedName, Some("Parser.parseEither"))
    assertEquals(method.packageName, Some("example.foo"))
    assertEquals(method.packageQualifiedName, Some("example.foo.Parser.parseEither"))
    assertEquals(method.sourceFile, Some("Parser.scala"))

  test("extracts Scala 3 colon package context for trait and class methods"):
    val report = EffectSummaryAnalyzer.summarizeSource(
      "Repo.scala",
      """package example:
        |
        |  trait UserRepo[F[_]]:
        |    def find(id: UserId): F[Option[User]]
        |
        |  final class BoxUserRepo extends UserRepo[Box]:
        |    def find(id: UserId): Box[Option[User]] = ???
        |""".stripMargin
    )

    val findMethods = report.methods.filter(_.name == "find")
    assertEquals(findMethods.map(_.qualifiedName), List(Some("UserRepo.find"), Some("BoxUserRepo.find")))
    assertEquals(findMethods.map(_.packageName), List(Some("example"), Some("example")))
    assertEquals(findMethods.map(_.packageQualifiedName), List(Some("example.UserRepo.find"), Some("example.BoxUserRepo.find")))

  test("missing package produces no package-qualified method name"):
    val report = EffectSummaryAnalyzer.summarizeSource(
      "NoPackage.scala",
      """object Parser:
        |  def parseEither(raw: String): Either[String, User] = ???
        |""".stripMargin
    )

    val method = report.methods.find(_.name == "parseEither").getOrElse(fail("Expected parseEither summary"))
    assertEquals(method.qualifiedName, Some("Parser.parseEither"))
    assertEquals(method.packageName, None)
    assertEquals(method.packageQualifiedName, None)
    assertEquals(method.sourceFile, Some("NoPackage.scala"))

  test("distinguishes duplicate method names with different owners"):
    val report = EffectSummaryAnalyzer.summarizeSource(
      "Main.scala",
      """package example
        |
        |trait UserRepo[F[_]]:
        |  def find(id: UserId): F[Option[User]]
        |
        |final class BoxUserRepo extends UserRepo[Box]:
        |  def find(id: UserId): Box[Option[User]] = ???
        |
        |object BoxUserRepo:
        |  def apply(): BoxUserRepo = new BoxUserRepo
        |""".stripMargin
    )

    val findMethods = report.methods.filter(_.name == "find")
    assertEquals(findMethods.map(_.qualifiedName), List(Some("UserRepo.find"), Some("BoxUserRepo.find")))
    assertEquals(findMethods.map(_.packageQualifiedName), List(Some("example.UserRepo.find"), Some("example.BoxUserRepo.find")))
    assertEquals(findMethods.map(_.enclosingKind), List(Some("trait"), Some("class")))

    val applyMethod = report.methods.find(_.name == "apply").getOrElse(fail("Expected apply summary"))
    assertEquals(applyMethod.ownerName, Some("BoxUserRepo"))
    assertEquals(applyMethod.qualifiedName, Some("BoxUserRepo.apply"))
    assertEquals(applyMethod.packageName, Some("example"))
    assertEquals(applyMethod.packageQualifiedName, Some("example.BoxUserRepo.apply"))
    assertEquals(applyMethod.sourceFile, Some("Main.scala"))
    assertEquals(applyMethod.enclosingKind, Some("object"))

  test("missing source returns a concise error"):
    val result = EffectSummaryAnalyzer.summarize(Path.of("modules/fp-analyzers/src/test/resources/effect-fixtures/simple/Missing.scala"))
    assert(result.left.exists(_.contains("does not exist")))

  private def assertCategory(returnType: String, expected: String): Unit =
    assertEquals(EffectSummaryAnalyzer.classify(returnType)._1, expected)
