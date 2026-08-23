package semantic.harness.tasty

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*
import scala.util.Try

final case class ValidatedTastySource(
    workspace: Path,
    path: Path,
    relativePath: String,
    sha256: String,
    utf16Offset: Int
)

object TastySourceInput:
  val MaxSourceBytes = 2L * 1024L * 1024L

  def validate(
      workspaceInput: Path,
      sourceInput: String,
      line: Int,
      column: Int
  ): Either[String, ValidatedTastySource] =
    Try(validateUnsafe(workspaceInput, sourceInput, line, column)).toEither
      .left.map(_ => "TASTy point source could not be validated safely")
      .flatten

  private def validateUnsafe(
      workspaceInput: Path,
      sourceInput: String,
      line: Int,
      column: Int
  ): Either[String, ValidatedTastySource] =
    val workspace = workspaceInput.toAbsolutePath.normalize()
    val relative = Path.of(sourceInput)
    if !Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(workspace) then
      Left("Workspace must be an existing non-symbolic-link directory")
    else if relative.isAbsolute then Left("TASTy point source must be workspace-relative")
    else
      val source = workspace.resolve(relative).normalize()
      if !source.startsWith(workspace) then Left("TASTy point source escapes the workspace")
      else if Files.isSymbolicLink(source) || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) then
        Left("TASTy point source must be an existing non-symbolic-link file")
      else if !source.getFileName.toString.endsWith(".scala") then Left("TASTy point source must be a .scala file")
      else if Files.size(source) > MaxSourceBytes then Left(s"TASTy point source exceeds $MaxSourceBytes bytes")
      else
        val workspaceReal = workspace.toRealPath()
        val sourceReal = source.toRealPath()
        if !sourceReal.startsWith(workspaceReal) then Left("TASTy point source resolves outside the workspace")
        else
          TastyDigest.readBounded(sourceReal, MaxSourceBytes)
            .left.map(_ => s"TASTy point source exceeds $MaxSourceBytes bytes")
            .flatMap { bytes => decodeUtf8(bytes).flatMap { text =>
            utf16Offset(text, line, column).map { offset =>
              ValidatedTastySource(
                workspaceReal,
                sourceReal,
                workspaceReal.relativize(sourceReal).toString.replace(java.io.File.separatorChar, '/'),
                TastyDigest.sha256(bytes),
                offset
              )
            }
          }}

  private def decodeUtf8(bytes: Array[Byte]): Either[String, String] =
    Try(
      StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString
    ).toEither.left.map(_ => "TASTy point source is not valid UTF-8")

  private def utf16Offset(text: String, line: Int, column: Int): Either[String, Int] =
    if line < 1 || column < 1 then Left("TASTy point coordinates must be positive and one-based")
    else
      var currentLine = 1
      var lineStart = 0
      while currentLine < line && lineStart <= text.length do
        val newline = text.indexOf('\n', lineStart)
        if newline < 0 then lineStart = text.length + 1
        else
          lineStart = newline + 1
          currentLine += 1
      if currentLine != line || lineStart > text.length then Left("TASTy point line is outside the source")
      else
        val newline = text.indexOf('\n', lineStart)
        val rawEnd = if newline < 0 then text.length else newline
        val lineEnd = if rawEnd > lineStart && text.charAt(rawEnd - 1) == '\r' then rawEnd - 1 else rawEnd
        val offset = lineStart + column - 1
        if offset > lineEnd then Left("TASTy point column is outside the source line")
        else Right(offset)

final case class TastyArtifactBounds(
    maxCandidates: Int = 512,
    maxFileBytes: Long = 8L * 1024L * 1024L,
    maxTotalBytes: Long = 64L * 1024L * 1024L
)

final case class TastyArtifactCandidate(
    path: Path,
    relativePath: String,
    byteSize: Long,
    sha256: String
)

enum TastyArtifactInventoryFailure:
  case Unavailable
  case RejectedByBounds

object TastyArtifactInventory:
  def inspect(
      workspaceInput: Path,
      classDirectoryInput: Path,
      bounds: TastyArtifactBounds = TastyArtifactBounds()
  ): Either[TastyArtifactInventoryFailure, List[TastyArtifactCandidate]] =
    Try(inspectUnsafe(workspaceInput, classDirectoryInput, bounds)).toEither
      .fold(_ => Left(TastyArtifactInventoryFailure.Unavailable), identity)

  private def inspectUnsafe(
      workspaceInput: Path,
      classDirectoryInput: Path,
      bounds: TastyArtifactBounds
  ): Either[TastyArtifactInventoryFailure, List[TastyArtifactCandidate]] =
    val workspace = workspaceInput.toRealPath()
    val root = classDirectoryInput.toAbsolutePath.normalize()
    if !root.startsWith(workspace) || Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS) then
      Left(TastyArtifactInventoryFailure.Unavailable)
    else
      val rootReal = root.toRealPath()
      if !rootReal.startsWith(workspace) then Left(TastyArtifactInventoryFailure.Unavailable)
      else
        val paths = Files.walk(rootReal)
        val entries =
          try paths.iterator().asScala.toList
          finally paths.close()
        if entries.exists(Files.isSymbolicLink(_)) then Left(TastyArtifactInventoryFailure.Unavailable)
        else
          val candidates = entries
            .filter(path => Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.getFileName.toString.endsWith(".tasty"))
            .sortBy(path => rootReal.relativize(path).toString.replace(java.io.File.separatorChar, '/'))
          val sizes = candidates.map(Files.size)
          if candidates.size > bounds.maxCandidates || sizes.exists(_ > bounds.maxFileBytes) || sizes.sum > bounds.maxTotalBytes then
            Left(TastyArtifactInventoryFailure.RejectedByBounds)
          else
            val bounded = candidates.zip(sizes).map { case (path, size) =>
              TastyDigest.readBounded(path, bounds.maxFileBytes).map { bytes =>
                TastyArtifactCandidate(
                  path,
                  rootReal.relativize(path).toString.replace(java.io.File.separatorChar, '/'),
                  size,
                  TastyDigest.sha256(bytes)
                )
              }
            }
            if bounded.exists(_.isLeft) then Left(TastyArtifactInventoryFailure.RejectedByBounds)
            else Right(bounded.flatMap(_.toOption))

private[tasty] object TastyDigest:
  def readBounded(path: Path, maxBytes: Long): Either[Unit, Array[Byte]] =
    if maxBytes > Int.MaxValue - 1 then Left(())
    else
      val stream = Files.newInputStream(path)
      val bytes = try stream.readNBytes(maxBytes.toInt + 1) finally stream.close()
      Either.cond(bytes.length <= maxBytes, bytes, ())

  def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map("%02x".format(_)).mkString
