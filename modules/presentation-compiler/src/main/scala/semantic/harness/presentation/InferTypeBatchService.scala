package semantic.harness.presentation

import dotty.tools.pc.ScalaPresentationCompiler
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import scala.deriving.Mirror
import scala.jdk.OptionConverters.*
import scala.meta.internal.metals.CompilerOffsetParams

private[presentation] enum InferTypeBatchCompilerStrategy:
  case PerItem
  case SharedSequential

final case class InferTypeBatchService(
    private[presentation] val strategy: InferTypeBatchCompilerStrategy =
      InferTypeBatchCompilerStrategy.SharedSequential,
    private[presentation] val onCompilerCreated: () => Unit = () => (),
    private[presentation] val onCompilerClosed: () => Unit = () => (),
    private[presentation] val afterCompilerQuery: InferTypeBatchRequestItem => Unit = _ => ()
):
  def infer(
      workspace: Path,
      requests: List[InferTypeBatchRequestItem],
      context: PresentationCompilerContext
  ): Either[String, List[InferTypeBatchItemResult]] =
    for
      normalizedWorkspace <- validateWorkspace(workspace)
      normalizedContext <- PresentationCompilerContext.validate(context)
      results <- strategy match
        case InferTypeBatchCompilerStrategy.PerItem =>
          Right(
            requests.zipWithIndex.map { case (request, index) =>
              prepare(normalizedWorkspace, request, index) match
                case Left(result) => result
                case Right(prepared) =>
                  withCompiler(normalizedContext)(compiler =>
                    queryPrepared(compiler, normalizedContext, prepared)
                  )
            }
          )
        case InferTypeBatchCompilerStrategy.SharedSequential =>
          withCompilerEither(normalizedContext) { compiler =>
            requests.zipWithIndex.map { case (request, index) =>
              prepare(normalizedWorkspace, request, index) match
                case Left(result)     => result
                case Right(prepared) => queryPrepared(compiler, normalizedContext, prepared)
            }
          }
    yield results

  private final case class Prepared(
      index: Int,
      item: InferTypeBatchRequestItem,
      path: Path,
      publicSource: String,
      source: String,
      before: BasicFileAttributes,
      offset: Int
  )

  private def prepare(
      workspace: Path,
      item: InferTypeBatchRequestItem,
      index: Int
  ): Either[InferTypeBatchItemResult, Prepared] =
    try prepareUnsafe(workspace, item, index)
    catch
      case _: java.nio.file.InvalidPathException =>
        Left(
          itemFailure(
            index,
            item,
            item.file,
            InferTypeBatchItemStatus.InvalidRequest,
            "Source path is invalid"
          )
        )

  private def prepareUnsafe(
      workspace: Path,
      item: InferTypeBatchRequestItem,
      index: Int
  ): Either[InferTypeBatchItemResult, Prepared] =
    val requested = Path.of(item.file)
    val path =
      if requested.isAbsolute then requested.normalize()
      else workspace.resolve(requested).normalize()
    val publicSource =
      if path.startsWith(workspace) then
        workspace.relativize(path).toString.replace(java.io.File.separatorChar, '/')
      else item.file

    def invalid(message: String): Left[InferTypeBatchItemResult, Prepared] =
      Left(itemFailure(index, item, publicSource, InferTypeBatchItemStatus.InvalidRequest, message))

    if !path.startsWith(workspace) then invalid("Source path escapes the selected workspace")
    else if Files.isSymbolicLink(path) then invalid("Source file symbolic links are not permitted")
    else if !Files.exists(path, LinkOption.NOFOLLOW_LINKS) then invalid("Source file does not exist")
    else if !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) then invalid("Source path is not a regular file")
    else if path.getFileName == null || !path.getFileName.toString.endsWith(".scala") then
      invalid("Source path must point to a .scala file")
    else
      try
        val workspaceReal = workspace.toRealPath()
        val real = path.toRealPath()
        if !real.startsWith(workspaceReal) then invalid("Source path resolves outside the selected workspace")
        else
          val size = Files.size(path)
          if size > InferTypeBatchBounds.MaxSourceFileBytes then
            invalid(s"Source file exceeds ${InferTypeBatchBounds.MaxSourceFileBytes} bytes")
          else
            val before = Files.readAttributes(path, classOf[BasicFileAttributes], LinkOption.NOFOLLOW_LINKS)
            val stream = Files.newInputStream(path)
            val bytes =
              try stream.readNBytes((InferTypeBatchBounds.MaxSourceFileBytes + 1).toInt)
              finally stream.close()
            if bytes.length > InferTypeBatchBounds.MaxSourceFileBytes then
              invalid(s"Source file exceeds ${InferTypeBatchBounds.MaxSourceFileBytes} bytes")
            else
              val decoder = StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
              val source = decoder.decode(ByteBuffer.wrap(bytes)).toString
              SourcePosition.offset(source, item.line, item.column) match
                case Left(_)     => invalid("Source position is outside the source file")
                case Right(offset) =>
                  Right(Prepared(index, item, path, publicSource, source, before, offset))
      catch
        case _: java.nio.charset.MalformedInputException =>
          invalid("Source file is not valid UTF-8")
        case _: Exception =>
          invalid("Source file could not be validated safely")

  private def queryPrepared(
      compiler: ScalaPresentationCompiler,
      context: PresentationCompilerContext,
      prepared: Prepared
  ): InferTypeBatchItemResult =
    val item = prepared.item
    try
      val params = CompilerOffsetParams(
        prepared.path.toAbsolutePath.normalize().toUri,
        prepared.source,
        prepared.offset
      )
      val hover = compiler.hover(params).get()
      afterCompilerQuery(item)
      val result =
        if hover.isEmpty then
          InferTypeBatchItemResult(
            index = prepared.index,
            id = item.id,
            status = InferTypeBatchItemStatus.Unresolved,
            rendering = None,
            renderingKind = InferTypeRenderingKind.NoRendering,
            source = prepared.publicSource,
            position = publicPosition(item),
            range = None,
            warnings = List(
              "The public presentation-compiler hover API cannot determine why no usable rendering was returned."
            ),
            message = None
          )
        else
          val value = hover.get()
          val signature = value.signature().toScala.filter(_.nonEmpty)
          val range = value.getRange().toScala.map(sourceRange)
          val raw = Option(value.toLsp())
            .flatMap(h => Option(h.getContents))
            .filter(_.isRight)
            .flatMap(contents => Option(contents.getRight))
            .flatMap(content => Option(content.getValue))
            .filter(_.nonEmpty)
          CompilerHoverRendering.select(raw, signature) match
            case Some(rendering)
                if InferTypeBatchBounds.withinUtf8(
                  rendering.value,
                  InferTypeBatchBounds.MaxRenderingBytes
                ) =>
              InferTypeBatchItemResult(
                index = prepared.index,
                id = item.id,
                status = InferTypeBatchItemStatus.Resolved,
                rendering = Some(rendering.value),
                renderingKind = rendering.kind,
                source = prepared.publicSource,
                position = publicPosition(item),
                range = range,
                warnings = Nil,
                message = None
              )
            case Some(_) =>
              itemFailure(
                prepared.index,
                item,
                prepared.publicSource,
                InferTypeBatchItemStatus.QueryFailure,
                s"Compiler rendering exceeds ${InferTypeBatchBounds.MaxRenderingBytes} bytes"
              )
            case None =>
              InferTypeBatchItemResult(
                index = prepared.index,
                id = item.id,
                status = InferTypeBatchItemStatus.Unresolved,
                rendering = None,
                renderingKind = InferTypeRenderingKind.NoRendering,
                source = prepared.publicSource,
                position = publicPosition(item),
                range = range,
                warnings = List(
                  "The public presentation-compiler hover API cannot determine why no usable rendering was returned."
                ),
                message = None
              )
      if sourceChanged(prepared) then
        itemFailure(
          prepared.index,
          item,
          prepared.publicSource,
          InferTypeBatchItemStatus.QueryFailure,
          "Source file changed during query processing"
        )
      else result
    catch
      case _: Exception =>
        itemFailure(
          prepared.index,
          item,
          prepared.publicSource,
          InferTypeBatchItemStatus.QueryFailure,
          "Presentation compiler query failed for this item"
        )

  private def sourceChanged(prepared: Prepared): Boolean =
    try
      val after = Files.readAttributes(
        prepared.path,
        classOf[BasicFileAttributes],
        LinkOption.NOFOLLOW_LINKS
      )
      after.size() != prepared.before.size() ||
      after.lastModifiedTime() != prepared.before.lastModifiedTime() ||
      after.fileKey() != prepared.before.fileKey()
    catch
      case _: Exception => true

  private def withCompiler(
      context: PresentationCompilerContext
  )(use: ScalaPresentationCompiler => InferTypeBatchItemResult): InferTypeBatchItemResult =
    val compiler = newCompiler(context)
    try use(compiler)
    finally closeCompiler(compiler)

  private def withCompilerEither(
      context: PresentationCompilerContext
  )(use: ScalaPresentationCompiler => List[InferTypeBatchItemResult]): Either[String, List[InferTypeBatchItemResult]] =
    try
      val compiler = newCompiler(context)
      try Right(use(compiler))
      finally closeCompiler(compiler)
    catch
      case _: Exception => Left("Unable to create the bounded presentation-compiler session")

  private def newCompiler(context: PresentationCompilerContext): ScalaPresentationCompiler =
    val explicit = context.classpath match
      case PresentationCompilerClasspath.NarrowRuntime      => Nil
      case PresentationCompilerClasspath.Explicit(entries) => entries
    val compiler = ScalaPresentationCompiler(
      classpath = (runtimeClasspath ++ explicit).distinct,
      folderPath = context.workspace
    )
    onCompilerCreated()
    compiler

  private def closeCompiler(compiler: ScalaPresentationCompiler): Unit =
    try compiler.shutdown()
    finally onCompilerClosed()

  private def validateWorkspace(workspace: Path): Either[String, Path] =
    val normalized = workspace.toAbsolutePath.normalize()
    if !Files.exists(normalized) then Left("Workspace does not exist")
    else if !Files.isDirectory(normalized) then Left("Workspace is not a directory")
    else Right(normalized)

  private def itemFailure(
      index: Int,
      item: InferTypeBatchRequestItem,
      source: String,
      status: InferTypeBatchItemStatus,
      message: String
  ): InferTypeBatchItemResult =
    val bounded =
      if InferTypeBatchBounds.withinUtf8(message, InferTypeBatchBounds.MaxMessageBytes) then message
      else "Bounded item failure"
    InferTypeBatchItemResult(
      index = index,
      id = item.id,
      status = status,
      rendering = None,
      renderingKind = InferTypeRenderingKind.NoRendering,
      source = source,
      position = publicPosition(item),
      range = None,
      warnings = Nil,
      message = Some(bounded)
    )

  private def publicPosition(item: InferTypeBatchRequestItem): InferTypePublicPosition =
    InferTypePublicPosition(item.line, item.column, InferTypePublicPosition.Utf16Encoding)

  private def sourceRange(range: org.eclipse.lsp4j.Range): SourceRange =
    SourceRange(
      startLine = range.getStart.getLine,
      startCharacter = range.getStart.getCharacter,
      endLine = range.getEnd.getLine,
      endCharacter = range.getEnd.getCharacter
    )

  private def runtimeClasspath: Seq[Path] =
    List(
      classOf[scala.Option[?]],
      classOf[Mirror]
    ).flatMap { clazz =>
      Option(clazz.getProtectionDomain)
        .flatMap(domain => Option(domain.getCodeSource))
        .flatMap(source => Option(source.getLocation))
        .map(uri => Path.of(uri.toURI))
    }.map(_.toAbsolutePath.normalize()).distinct
