package semantic.harness.presentation

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.concurrent.duration.*

class InferTypeBatchLifecycleComparisonSuite extends munit.FunSuite:
  override val munitTimeout = 2.minutes

  private val workspace = Path.of(".").toAbsolutePath.normalize()
  private val fixture =
    "modules/presentation-compiler/src/test/resources/presentation-fixtures/infer-type/InferTypeFixture.scala"

  test("controlled lifecycle comparison records 1, 4, 16, and 64 item observations"):
    val base = itemAt("base", fixture, "optional.map", "optional.".length + 1)
    List(1, 4, 16, 64).foreach { count =>
      val requests = List.tabulate(count)(index => base.copy(id = s"item-$index"))
      val sharedStart = System.nanoTime()
      val shared = InferTypeBatchService(InferTypeBatchCompilerStrategy.SharedSequential)
        .infer(workspace, requests, PresentationCompilerContext(workspace = Some(workspace)))
        .fold(message => fail(message), identity)
      val sharedMillis = (System.nanoTime() - sharedStart) / 1000000
      val perItemStart = System.nanoTime()
      val perItem = InferTypeBatchService(InferTypeBatchCompilerStrategy.PerItem)
        .infer(workspace, requests, PresentationCompilerContext(workspace = Some(workspace)))
        .fold(message => fail(message), identity)
      val perItemMillis = (System.nanoTime() - perItemStart) / 1000000
      assertEquals(shared, perItem)
      println(
        s"infer-type batch compiler comparison: items=$count shared=${sharedMillis}ms per-item=${perItemMillis}ms"
      )
    }

  private def itemAt(
      id: String,
      file: String,
      marker: String,
      delta: Int
  ): InferTypeBatchRequestItem =
    val source = Files.readString(workspace.resolve(file), StandardCharsets.UTF_8)
    val offset = source.indexOf(marker)
    assert(offset >= 0, clue(marker))
    val target = offset + delta
    val lineStart = source.lastIndexOf('\n', math.max(0, target - 1)) + 1
    val line = source.substring(0, lineStart).count(_ == '\n') + 1
    InferTypeBatchRequestItem(id, file, line, target - lineStart + 1)
