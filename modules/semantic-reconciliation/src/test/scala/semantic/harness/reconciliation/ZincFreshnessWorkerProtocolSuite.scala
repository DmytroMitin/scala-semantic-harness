package semantic.harness.reconciliation

import java.nio.charset.StandardCharsets
import java.nio.file.Path
import semantic.harness.sbt_runner.SbtInternalSourceLayoutReceipt

class ZincFreshnessWorkerProtocolSuite extends munit.FunSuite:
  private val workspace = Path.of("/workspace")
  private val layout = SbtInternalSourceLayoutReceipt(
    sourceDirectories = List(workspace.resolve("producer/src/main/scala")),
    unmanagedSourceDirectories = List(workspace.resolve("producer/src/main/scala")),
    managedSourceDirectories = List(workspace.resolve("producer/target/src_managed/main")),
    sourceGeneratorCount = 0
  )

  test("one request batches multiple caller IDs without exposing paths in a response"):
    val inputs = List(input("producer-a"), input("producer-b"))
    val encoded = ZincFreshnessWorkerProtocol.encodeRequest(inputs).toOption.get
    val text = String(encoded, StandardCharsets.UTF_8)
    assertEquals(text.linesIterator.count(_.startsWith("item\t")), 2)

    val response =
      s"""${ZincFreshnessWorkerProtocol.ResponseMarker}
         |result\tproducer-a\tFresh\tSourceAndProductContentMatch\t1\t2
         |result\tproducer-b\tStale\tSourceContentMismatch\t3\t4
         |""".stripMargin.getBytes(StandardCharsets.UTF_8)
    val decoded = ZincFreshnessWorkerProtocol.decodeResponse(response, inputs.map(_.callerId).toSet)
      .toOption.get
    assertEquals(decoded("producer-a").status, InternalOutputFreshnessStatusV6.Fresh)
    assertEquals(decoded("producer-b").reason, InternalOutputFreshnessReasonV6.SourceContentMismatch)
    assert(!String(response, StandardCharsets.UTF_8).contains("/workspace"))

  test("protocol rejects duplicate missing unknown malformed and version-mismatched results"):
    val ids = Set("producer-a", "producer-b")
    val validA = "result\tproducer-a\tFresh\tSourceAndProductContentMatch\t1\t2"
    val validB = "result\tproducer-b\tUnverifiable\tUnsupportedAnalysisFormatOrVersion\t-\t-"
    val cases = List(
      s"${ZincFreshnessWorkerProtocol.ResponseMarker}\n$validA\n$validA\n$validB\n",
      s"${ZincFreshnessWorkerProtocol.ResponseMarker}\n$validA\n",
      s"${ZincFreshnessWorkerProtocol.ResponseMarker}\n$validA\n$validB\nresult\tproducer-c\tFresh\tSourceAndProductContentMatch\t1\t2\n",
      s"${ZincFreshnessWorkerProtocol.ResponseMarker}\nmalformed\n$validA\n$validB\n",
      s"semantic-scala-zinc-freshness-worker.v2\n$validA\n$validB\n"
    )
    cases.foreach(value => assert(ZincFreshnessWorkerProtocol.decodeResponse(
      value.getBytes(StandardCharsets.UTF_8), ids
    ).isLeft))

  test("response rejects out-of-bound counts and incompatible reason/count shapes"):
    val invalidRows = List(
      "result\tproducer-a\tFresh\tSourceAndProductContentMatch\t50001\t1",
      "result\tproducer-a\tUnverifiable\tAnalysisFileMissing\t1\t1",
      "result\tproducer-a\tUnverifiable\tMissingExpectedProduct\t-\t-",
      "result\tproducer-a\tUnverifiable\tDependencyClassDirectoryAbsent\t-\t-"
    )
    invalidRows.foreach { row =>
      val response = s"${ZincFreshnessWorkerProtocol.ResponseMarker}\n$row\n"
      assert(ZincFreshnessWorkerProtocol.decodeResponse(
        response.getBytes(StandardCharsets.UTF_8), Set("producer-a")
      ).isLeft, clue(row))
    }

  test("request and response byte and field bounds fail closed"):
    val hugeField = "x" * (ZincFreshnessWorkerProtocol.MaxFieldBytes + 1)
    assert(ZincFreshnessWorkerProtocol.encodeRequest(List(input(hugeField))).isLeft)

    val oversized = Array.fill[Byte](ZincFreshnessWorkerProtocol.MaxProtocolBytes + 1)('x'.toByte)
    assert(ZincFreshnessWorkerProtocol.decodeResponse(oversized, Set("producer-a")).isLeft)

  test("the worker dependency identity is the exact admitted frozen graph"):
    assertEquals(ZincFreshnessWorkerIdentity.Protocol, "semantic-scala-zinc-freshness-worker.v1")
    assertEquals(ZincFreshnessWorkerIdentity.ScalaVersion, "2.13.18")
    assertEquals(ZincFreshnessWorkerIdentity.ZincVersion, "1.12.1")
    assertEquals(ZincFreshnessWorkerIdentity.JnaVersion, "5.14.0")
    assertEquals(ZincFreshnessWorkerIdentity.DependencyArtifactCount, 42)
    assertEquals(ZincFreshnessWorkerIdentity.DependencyArtifactBytes, 38241533L)
    assertEquals(
      ZincFreshnessWorkerIdentity.DependencyInventorySha256,
      "c5abc92c5a51596c8f13142370bc2cdd1081d7c6e08e4fddbe3c83d6767fee36"
    )
    assertEquals(
      ZincFreshnessWorkerIdentity.DependencyContentInventorySha256,
      "a95bb11bdb1e92b5a294767e24d9699f6dac9da328d419c8a8f7d9dfe97523d4"
    )
    assertEquals(
      ZincFreshnessWorkerIdentity.WorkerJarSha256,
      "cffcddbee0795d436664185a0ca0e3206a6542b5d01bd0e487da3da23cbcfd69"
    )

  private def input(id: String): ZincFreshnessWorkerInput = ZincFreshnessWorkerInput(
    callerId = id,
    workspace = workspace,
    effectiveScalaVersion = "2.13.18",
    classDirectory = workspace.resolve("producer/target/scala-2.13/classes"),
    analysisFile = workspace.resolve("producer/target/scala-2.13/zinc/inc_compile.zip"),
    sourceLayout = layout
  )
