package semantic.harness.presentation

import dotty.tools.pc.ScalaPresentationCompiler
import java.nio.file.Files
import java.nio.file.Path
import scala.deriving.Mirror
import scala.jdk.CollectionConverters.*
import scala.jdk.OptionConverters.*
import scala.meta.internal.metals.CompilerOffsetParams

final case class PresentationCompilerService() extends DynamicSemanticService:
  override def symbolAt(file: Path, line: Int, column: Int): Either[String, SymbolAtResult] =
    validate(file, line, column).flatMap(source => symbolAtSnapshot(file, source, line, column))

  def symbolAtSnapshot(file: Path, source: String, line: Int, column: Int): Either[String, SymbolAtResult] =
    validateSnapshot(file, line, column).flatMap { _ =>
      SourcePosition.offset(source, line, column).flatMap { value =>
        val compiler = ScalaPresentationCompiler(classpath = runtimeClasspath)
        try
          val uri = file.toAbsolutePath.normalize().toUri
          val params = CompilerOffsetParams(uri, source, value)
          val result = compiler.definition(params).get()
          val locations = result.locations().asScala.toList
          val symbol = symbolIdentity(result.symbol())
          Right(
            SymbolAtResult(
              symbol = symbol,
              displayName = symbol.flatMap(displayName),
              range = locations.headOption.map(location => sourceRange(location.getRange)),
              source = file.toString
            )
          )
        catch
          case exception: Exception =>
            Left(s"Unable to query symbol at $file:$line:$column: ${exception.getMessage}")
        finally compiler.shutdown()
      }
    }

  override def inferType(request: InferTypeRequest): Either[String, InferTypeResult] =
    validate(request.file, request.line, request.column).flatMap { source =>
      PresentationCompilerContext.validate(request.context).flatMap { context =>
        SourcePosition.offset(source, request.line, request.column).flatMap { value =>
          val (contextKind, classpathEntries) =
            context.classpath match
              case PresentationCompilerClasspath.NarrowRuntime =>
                (InferTypeContextKind.NarrowRuntime, Nil)
              case PresentationCompilerClasspath.Explicit(entries) =>
                (InferTypeContextKind.ExplicitClasspath, entries)
          val compiler = ScalaPresentationCompiler(
            classpath = (runtimeClasspath ++ classpathEntries).distinct,
            folderPath = context.workspace
          )
          try
            val uri = request.file.toAbsolutePath.normalize().toUri
            val params = CompilerOffsetParams(uri, source, value)
            val hover = compiler.hover(params).get()
            val position = InferTypeQueryPosition(request.line, request.column)

            if hover.isEmpty then
              Right(noTypeResult(request.file, position, contextKind, classpathEntries.size, context.workspace.nonEmpty, None, None))
            else
              val signature = hover.get().signature().toScala.filter(_.nonEmpty)
              val range = hover.get().getRange().toScala.map(sourceRange)
              val raw = hoverMarkup(hover.get().toLsp())
              CompilerHoverRendering.select(raw, signature) match
                case Some(rendering) =>
                  Right(
                    InferTypeResult(
                      status = InferTypeStatus.Resolved,
                      rendering = Some(rendering.value),
                      renderingKind = rendering.kind,
                      source = request.file.toString,
                      position = position,
                      range = range,
                      rawCompilerRendering = raw,
                      contextKind = contextKind,
                      classpathEntryCount = classpathEntries.size,
                      workspaceProvided = context.workspace.nonEmpty,
                      warnings = resolvedWarnings(contextKind)
                    )
                  )
                case None =>
                  Right(noTypeResult(request.file, position, contextKind, classpathEntries.size, context.workspace.nonEmpty, range, raw))
          catch
            case exception: Exception =>
              Left(s"Unable to query type at ${request.file}:${request.line}:${request.column}: ${exception.getMessage}")
          finally
            compiler.shutdown()
        }
      }
    }

  private def validate(file: Path, line: Int, column: Int): Either[String, String] =
    if line <= 0 then Left(s"Line must be positive: $line")
    else if column <= 0 then Left(s"Column must be positive: $column")
    else if !Files.exists(file) then Left(s"Source file does not exist: $file")
    else if !Files.isRegularFile(file) then Left(s"Source path is not a file: $file")
    else if file.getFileName == null || !file.getFileName.toString.endsWith(".scala") then
      Left(s"Source path must point to a .scala file: $file")
    else Right(Files.readString(file))

  private def validateSnapshot(file: Path, line: Int, column: Int): Either[String, Unit] =
    if line <= 0 then Left(s"Line must be positive: $line")
    else if column <= 0 then Left(s"Column must be positive: $column")
    else if file.getFileName == null || !file.getFileName.toString.endsWith(".scala") then
      Left(s"Source path must point to a .scala file: $file")
    else Right(())

  private def hoverMarkup(hover: org.eclipse.lsp4j.Hover): Option[String] =
    Option(hover)
      .flatMap(value => Option(value.getContents))
      .filter(_.isRight)
      .flatMap(contents => Option(contents.getRight))
      .flatMap(content => Option(content.getValue))
      .filter(_.nonEmpty)

  private def noTypeResult(
      file: Path,
      position: InferTypeQueryPosition,
      contextKind: InferTypeContextKind,
      classpathEntryCount: Int,
      workspaceProvided: Boolean,
      range: Option[SourceRange],
      raw: Option[String]
  ): InferTypeResult =
    InferTypeResult(
      status = InferTypeStatus.Unresolved,
      rendering = None,
      renderingKind = InferTypeRenderingKind.NoRendering,
      source = file.toString,
      position = position,
      range = range,
      rawCompilerRendering = raw,
      contextKind = contextKind,
      classpathEntryCount = classpathEntryCount,
      workspaceProvided = workspaceProvided,
      warnings = resolvedWarnings(contextKind) :+
        "The public presentation-compiler hover API cannot determine why no usable rendering was returned."
    )

  private def resolvedWarnings(contextKind: InferTypeContextKind): List[String] =
    val common = List(
      "Compiler hover rendering is version-dependent.",
      "Compiler hover rendering is not canonical type identity.",
      "A compiler hover query does not prove whole-project compilation."
    )
    contextKind match
      case InferTypeContextKind.NarrowRuntime =>
        common :+ "Project dependencies and sibling compiled outputs may be unavailable in narrow-runtime context."
      case InferTypeContextKind.ExplicitClasspath => common
        :+ "Beyond the built-in Scala runtime, only the supplied compiled classpath entries are available."
        :+ "Sibling uncompiled or open sources are not modeled."
      case InferTypeContextKind.SbtClasspath => common
        :+ "The compiled classpath was acquired by the caller from an explicitly selected sbt scope."
        :+ "Sibling uncompiled or open sources are not modeled."

  private def sourceRange(range: org.eclipse.lsp4j.Range): SourceRange =
    SourceRange(
      startLine = range.getStart.getLine,
      startCharacter = range.getStart.getCharacter,
      endLine = range.getEnd.getLine,
      endCharacter = range.getEnd.getCharacter
    )

  private def symbolIdentity(symbol: String): Option[String] =
    Option(symbol).filter(value => value.nonEmpty && value != "`<none>`.")

  private def displayName(symbol: String): Option[String] =
    Option(symbol)
      .filter(_.nonEmpty)
      .map(_.takeWhile(_ != '(').stripSuffix(".").stripSuffix("#"))
      .flatMap(value => value.split("[/#.]").lastOption)
      .filter(_.nonEmpty)

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
