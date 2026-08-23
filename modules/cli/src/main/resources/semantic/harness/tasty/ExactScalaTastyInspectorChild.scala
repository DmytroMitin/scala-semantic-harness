package semantic.harness.tasty.child

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.Base64
import scala.collection.mutable.ListBuffer
import scala.quoted.*
import scala.tasty.inspector.*
import scala.util.Try

object ExactScalaTastyInspectorChild:
  private val InputMarker = "semantic-scala.internal-tasty-worker-input.v1"
  private val OutputMarker = "semantic-scala.internal-tasty-worker-output.v1"

  def main(args: Array[String]): Unit =
    if args.length != 1 then sys.error("expected one worker input")
    val lines = Files.readAllLines(Path.of(args(0)), StandardCharsets.UTF_8)
    if lines.isEmpty || lines.get(0) != InputMarker then sys.error("invalid worker input marker")
    val source = decode(single(lines, "source="))
    val workspace = decode(single(lines, "workspace="))
    val line = single(lines, "line=").toInt - 1
    val column = single(lines, "column=").toInt - 1
    val candidates = lines.toArray(new Array[String](lines.size)).toList
      .filter(_.startsWith("candidate="))
      .map(value => decode(value.stripPrefix("candidate=")))
    val dependencies = lines.toArray(new Array[String](lines.size)).toList
      .filter(_.startsWith("dependency="))
      .map(value => decode(value.stripPrefix("dependency=")))

    val selected = ListBuffer.empty[WorkerTree]
    var inspected = 0
    candidates.zipWithIndex.foreach { case (candidate, index) =>
      val inspector = PointInspector(workspace, source, line, column)
      val completed = Try(
        TastyInspector.inspectAllTastyFiles(List(candidate), Nil, dependencies)(inspector)
      ).getOrElse(false)
      if completed then
        inspected += 1
        inspector.best.foreach(tree => selected += tree.copy(candidateIndex = index))
    }

    println(OutputMarker)
    println("scalaVersion=" + encode(dotty.tools.dotc.config.Properties.versionNumberString))
    println("inspected=" + inspected)
    selected.foreach(tree => println(tree.protocolLine))

  private def single(lines: java.util.List[String], prefix: String): String =
    val values = lines.toArray(new Array[String](lines.size)).toList.filter(_.startsWith(prefix))
    if values.size != 1 then sys.error("invalid worker input field")
    values.head.stripPrefix(prefix)

  private def encode(value: String): String =
    Base64.getEncoder.encodeToString(value.getBytes(StandardCharsets.UTF_8))

  private def decode(value: String): String =
    new String(Base64.getDecoder.decode(value), StandardCharsets.UTF_8)

  private final case class WorkerTree(
      candidateIndex: Int,
      startOffset: Int,
      endOffset: Int,
      startLine: Int,
      startColumn: Int,
      endLine: Int,
      endColumn: Int,
      kind: String,
      symbol: String,
      displayName: String,
      signature: String,
      renderedType: String
  ):
    def protocolLine: String =
      List(
        "tree",
        candidateIndex.toString,
        startOffset.toString,
        endOffset.toString,
        startLine.toString,
        startColumn.toString,
        endLine.toString,
        endColumn.toString,
        encode(kind),
        encode(symbol),
        encode(displayName),
        encode(signature),
        encode(renderedType)
      ).mkString("\t")

  private final class PointInspector(workspace: String, source: String, line: Int, column: Int) extends Inspector:
    private val found = ListBuffer.empty[WorkerTree]

    def best: Option[WorkerTree] =
      found.toList.sortBy(tree => (
        tree.endOffset - tree.startOffset,
        if tree.symbol.nonEmpty then 0 else 1,
        -tree.startOffset,
        tree.endOffset,
        tree.kind
      )).headOption

    def inspect(using Quotes)(tastys: List[Tasty[quotes.type]]): Unit =
      import quotes.reflect.*
      def bounded(value: String): String = if value.length <= 4096 then value else value.take(4096)
      def safe(value: => String): String = _root_.scala.util.Try(value).getOrElse("")
      def sameSource(path: String): Boolean =
        _root_.scala.util.Try {
          val reported = Path.of(path)
          val resolved =
            if reported.isAbsolute then reported.normalize()
            else Path.of(workspace).resolve(reported).normalize()
          resolved == Path.of(source).toAbsolutePath.normalize()
        }.getOrElse(false)
      def contains(position: Position): Boolean =
        val startsBefore = position.startLine < line ||
          (position.startLine == line && position.startColumn <= column)
        val endsAfter = position.endLine > line ||
          (position.endLine == line && position.endColumn > column)
        startsBefore && endsAfter

      tastys.foreach { tasty =>
        object traverser extends TreeTraverser:
          override def traverseTree(tree: Tree)(owner: Symbol): Unit =
            val position = tree.pos
            if position.start >= 0 && position.end >= position.start &&
                sameSource(position.sourceFile.path) && contains(position) then
              val symbol = tree.symbol
              val renderedType = tree match
                case term: Term         => safe(term.tpe.widenTermRefByName.widen.show)
                case typeTree: TypeTree => safe(typeTree.tpe.widen.show)
                case _                  => ""
              found += WorkerTree(
                0,
                position.start,
                position.end,
                position.startLine + 1,
                position.startColumn + 1,
                position.endLine + 1,
                position.endColumn + 1,
                tree.getClass.getSimpleName.stripSuffix("$") ,
                if symbol.exists then bounded(safe(symbol.fullName)) else "",
                if symbol.exists then bounded(safe(symbol.name)) else "",
                if symbol.exists then bounded(safe(symbol.signature.toString)) else "",
                bounded(renderedType)
              )
            traverseTreeChildren(tree)(owner)
        traverser.traverseTree(tasty.ast)(Symbol.noSymbol)
      }
