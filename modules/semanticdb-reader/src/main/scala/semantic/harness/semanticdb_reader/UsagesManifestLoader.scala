package semantic.harness.semanticdb_reader

import io.circe.Json
import io.circe.parser.parse
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.Using
import scala.util.control.NonFatal

private[harness] final case class UsagesManifestSource(
  path: String,
  module: String,
  sourceSet: String,
  generated: Boolean
)

private[harness] final case class UsagesManifestArtifact(
  path: String,
  module: String,
  sourceSet: String,
  generated: Boolean
)

private[harness] final case class LoadedUsagesManifest(
  inventoryClosed: Boolean,
  sources: List[UsagesManifestSource],
  artifacts: List[UsagesManifestArtifact]
)

private[harness] object UsagesManifestLoader:
  val SchemaVersion = "semantic-scala.usages-manifest.v1"
  val MaxManifestBytes = 16 * 1024 * 1024
  private val MaxSources = 50000
  private val MaxArtifacts = 256
  private val MaxPathBytes = 4096
  private val MaxIdentifierBytes = 1024
  private val Identifier = "^[A-Za-z0-9][A-Za-z0-9._-]*$".r
  private val RootFields = Set("schemaVersion", "inventoryClosed", "sources", "artifacts")
  private val EntryFields = Set("path", "module", "sourceSet", "generated")

  private[semanticdb_reader] trait FileAccess:
    def size(path: Path): Long
    def open(path: Path): InputStream

  private object NioFileAccess extends FileAccess:
    override def size(path: Path): Long = Files.size(path)
    override def open(path: Path): InputStream = Files.newInputStream(path)

  def load(workspaceInput: Path, manifestRelative: String): Either[UsagesPublicFailure, LoadedUsagesManifest] =
    loadWithFileAccess(workspaceInput, manifestRelative, NioFileAccess)

  private[semanticdb_reader] def loadWithFileAccess(
    workspaceInput: Path,
    manifestRelative: String,
    fileAccess: FileAccess
  ): Either[UsagesPublicFailure, LoadedUsagesManifest] =
    for
      workspace <- validateWorkspace(workspaceInput)
      manifestPath <- resolveFile(workspace, manifestRelative, Some(".json"), "manifest")
      size <- fileSize(manifestPath, fileAccess, "Unable to inspect manifest")
      _ <- Either.cond(
        size <= MaxManifestBytes,
        (),
        invalid(s"Manifest exceeds the $MaxManifestBytes-byte limit")
      )
      bytes <- readBoundedBytes(manifestPath, fileAccess, "Unable to read manifest")
      text <- strictUtf8(bytes)
      _ <- StrictJsonKeys.validate(text).left.map(invalid)
      json <- parse(text).left.map(_ => invalid("Manifest is not valid strict JSON"))
      manifest <- decodeManifest(json)
      _ <- validateUniquePaths(manifest.sources.map(_.path), "source")
      _ <- validateUniquePaths(manifest.artifacts.map(_.path), "artifact")
      _ <- validateDeclaredFiles(workspace, manifest)
    yield manifest.copy(
      sources = manifest.sources.sortBy(value => (value.path, value.module, value.sourceSet, value.generated)),
      artifacts = manifest.artifacts.sortBy(value => (value.path, value.module, value.sourceSet, value.generated))
    )

  private def decodeManifest(json: Json): Either[UsagesPublicFailure, LoadedUsagesManifest] =
    json.asObject match
      case None => Left(invalid("Manifest root must be an object"))
      case Some(root) =>
        for
          _ <- exactFields(root.keys.toSet, RootFields, "manifest")
          schema <- root("schemaVersion").flatMap(_.asString).toRight(invalid("Manifest schemaVersion must be a string"))
          _ <- Either.cond(schema == SchemaVersion, (), invalid(s"Unsupported manifest schemaVersion; expected $SchemaVersion"))
          closed <- root("inventoryClosed").flatMap(_.asBoolean).toRight(invalid("Manifest inventoryClosed must be a boolean"))
          sourcesJson <- root("sources").flatMap(_.asArray).toRight(invalid("Manifest sources must be an array"))
          artifactsJson <- root("artifacts").flatMap(_.asArray).toRight(invalid("Manifest artifacts must be an array"))
          _ <- Either.cond(sourcesJson.nonEmpty && sourcesJson.size <= MaxSources, (), invalid(s"Manifest sources must contain 1 to $MaxSources entries"))
          _ <- Either.cond(artifactsJson.nonEmpty && artifactsJson.size <= MaxArtifacts, (), invalid(s"Manifest artifacts must contain 1 to $MaxArtifacts entries"))
          sources <- traverse(sourcesJson.toList)(decodeSource)
          artifacts <- traverse(artifactsJson.toList)(decodeArtifact)
        yield LoadedUsagesManifest(closed, sources, artifacts)

  private def decodeSource(json: Json): Either[UsagesPublicFailure, UsagesManifestSource] =
    decodeEntry(json, "source").flatMap { case (path, module, sourceSet, generated) =>
      if !path.endsWith(".scala") && !path.endsWith(".java") then
        Left(invalid("Manifest source path must end in .scala or .java"))
      else Right(UsagesManifestSource(path, module, sourceSet, generated))
    }

  private def decodeArtifact(json: Json): Either[UsagesPublicFailure, UsagesManifestArtifact] =
    decodeEntry(json, "artifact").flatMap { case (path, module, sourceSet, generated) =>
      if !path.endsWith(".semanticdb") then
        Left(invalid("Manifest artifact path must end in .semanticdb"))
      else Right(UsagesManifestArtifact(path, module, sourceSet, generated))
    }

  private def decodeEntry(
    json: Json,
    label: String
  ): Either[UsagesPublicFailure, (String, String, String, Boolean)] =
    json.asObject match
      case None => Left(invalid(s"Manifest $label entry must be an object"))
      case Some(value) =>
        for
          _ <- exactFields(value.keys.toSet, EntryFields, s"manifest $label entry")
          path <- value("path").flatMap(_.asString).toRight(invalid(s"Manifest $label path must be a string"))
          module <- value("module").flatMap(_.asString).toRight(invalid(s"Manifest $label module must be a string"))
          sourceSet <- value("sourceSet").flatMap(_.asString).toRight(invalid(s"Manifest $label sourceSet must be a string"))
          generated <- value("generated").flatMap(_.asBoolean).toRight(invalid(s"Manifest $label generated must be a boolean"))
          normalized <- validateRelative(path, "Declared path")
          _ <- validateIdentifier(module, "Module")
          _ <- validateIdentifier(sourceSet, "Source-set")
        yield (normalized, module, sourceSet, generated)

  private def validateDeclaredFiles(
    workspace: Path,
    manifest: LoadedUsagesManifest
  ): Either[UsagesPublicFailure, Unit] =
    val paths = manifest.sources.map(_.path) ++ manifest.artifacts.map(_.path)
    traverse(paths)(path => resolveFile(workspace, path, None, "declared").map(_ => ())).map(_ => ())

  private def validateUniquePaths(values: List[String], label: String): Either[UsagesPublicFailure, Unit] =
    val duplicates = values.groupMapReduce(identity)(_ => 1)(_ + _).exists(_._2 > 1)
    Either.cond(!duplicates, (), invalid(s"Manifest contains a duplicate normalized $label path"))

  private def exactFields(
    actual: Set[String],
    expected: Set[String],
    label: String
  ): Either[UsagesPublicFailure, Unit] =
    Either.cond(actual == expected, (), invalid(s"Unknown or missing field in $label"))

  private def validateIdentifier(value: String, label: String): Either[UsagesPublicFailure, Unit] =
    Either.cond(
      utf8Bytes(value) <= MaxIdentifierBytes && Identifier.matches(value),
      (),
      invalid(s"$label identifier is invalid")
    )

  private def validateWorkspace(value: Path): Either[UsagesPublicFailure, Path] =
    try
      val normalized = value.toAbsolutePath.normalize()
      if Files.isSymbolicLink(normalized) then Left(unsafe("Workspace symbolic links are not permitted"))
      else if !Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) then Left(unsafe("Workspace does not exist"))
      else if !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS) then Left(unsafe("Workspace is not a directory"))
      else if normalized.toRealPath() != normalized then Left(unsafe("Workspace path contains a symbolic-link component"))
      else Right(normalized)
    catch
      case NonFatal(_) => Left(unsafe("Unable to validate workspace"))

  private def resolveFile(
    workspace: Path,
    relativeInput: String,
    requiredExtension: Option[String],
    label: String
  ): Either[UsagesPublicFailure, Path] =
    for
      relative <- validateRelative(relativeInput, s"$label path")
      _ <- requiredExtension match
        case Some(extension) => Either.cond(relative.endsWith(extension), (), invalid(s"$label path must end in $extension"))
        case None            => Right(())
      path <-
        try
          val segments = relative.split('/').toList
          var current = workspace
          var symbolic = false
          segments.foreach { segment =>
            current = current.resolve(segment)
            if Files.isSymbolicLink(current) then symbolic = true
          }
          val normalized = current.toAbsolutePath.normalize()
          if symbolic then Left(unsafe(s"$label path contains a symbolic-link component"))
          else if !normalized.startsWith(workspace) then Left(unsafe(s"$label path escapes workspace"))
          else if !Files.exists(normalized, LinkOption.NOFOLLOW_LINKS) then Left(unsafe(s"$label file does not exist"))
          else if !Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS) then Left(unsafe(s"$label path is not a regular file"))
          else if !normalized.toRealPath().startsWith(workspace) then Left(unsafe(s"$label path escapes workspace"))
          else Right(normalized)
        catch
          case NonFatal(_) => Left(unsafe(s"Unable to validate $label file"))
    yield path

  private def validateRelative(value: String, label: String): Either[UsagesPublicFailure, String] =
    if value.isEmpty then Left(invalid(s"$label must be non-empty"))
    else if utf8Bytes(value) > MaxPathBytes then Left(invalid(s"$label exceeds the byte limit"))
    else
      try
        val path = Path.of(value)
        val normalized = path.normalize().toString.replace('\\', '/')
        val segments = value.split("/", -1).toList
        if path.isAbsolute then Left(invalid(s"$label must be workspace-relative"))
        else if value.contains('\\') then Left(invalid(s"$label must use '/' separators"))
        else if normalized != value || segments.exists(segment => segment.isEmpty || segment == "." || segment == "..") then
          Left(invalid(s"$label is not normalized"))
        else Right(normalized)
      catch
        case NonFatal(_) => Left(invalid(s"$label is invalid"))

  private def fileSize(path: Path, fileAccess: FileAccess, message: String): Either[UsagesPublicFailure, Long] =
    try Right(fileAccess.size(path))
    catch case NonFatal(_) => Left(io(message))

  private def readBoundedBytes(
    path: Path,
    fileAccess: FileAccess,
    message: String
  ): Either[UsagesPublicFailure, Array[Byte]] =
    try
      Using.resource(fileAccess.open(path)) { input =>
        val bytes = input.readNBytes(MaxManifestBytes + 1)
        Either.cond(
          bytes.length <= MaxManifestBytes,
          bytes,
          invalid(s"Manifest exceeds the $MaxManifestBytes-byte limit")
        )
      }
    catch case NonFatal(_) => Left(io(message))

  private def strictUtf8(bytes: Array[Byte]): Either[UsagesPublicFailure, String] =
    try
      val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
      Right(decoder.decode(ByteBuffer.wrap(bytes)).toString)
    catch case NonFatal(_) => Left(invalid("Manifest is not strict UTF-8"))

  private[semanticdb_reader] def traverse[A, B](
    values: List[A]
  )(f: A => Either[UsagesPublicFailure, B]): Either[UsagesPublicFailure, List[B]] =
    val builder = List.newBuilder[B]

    @tailrec
    def loop(remaining: List[A]): Either[UsagesPublicFailure, List[B]] =
      remaining match
        case Nil => Right(builder.result())
        case head :: tail =>
          f(head) match
            case Left(failure) => Left(failure)
            case Right(value) =>
              builder += value
              loop(tail)

    loop(values)

  private def utf8Bytes(value: String): Int = value.getBytes(StandardCharsets.UTF_8).length
  private def invalid(message: String) = UsagesPublicFailure(failureKind = UsagesPublicFailureKind.InvalidInput, message = message)
  private def unsafe(message: String) = UsagesPublicFailure(failureKind = UsagesPublicFailureKind.UnsafeFilesystem, message = message)
  private def io(message: String) = UsagesPublicFailure(failureKind = UsagesPublicFailureKind.IoFailure, message = message)

  private object StrictJsonKeys:
    def validate(value: String): Either[String, Unit] =
      try
        val parser = KeyParser(value)
        parser.parseDocument()
        Right(())
      catch
        case error: IllegalArgumentException => Left(error.getMessage)

    private final class KeyParser(input: String):
      private var index = 0

      def parseDocument(): Unit =
        whitespace()
        value()
        whitespace()
        require(index == input.length, "Manifest is not valid strict JSON")

      private def value(): Unit =
        whitespace()
        current match
          case '{' => objectValue()
          case '[' => arrayValue()
          case '"' => stringValue()
          case 't' => literal("true")
          case 'f' => literal("false")
          case 'n' => literal("null")
          case character if character == '-' || character.isDigit => number()
          case _ => fail("Manifest is not valid strict JSON")

      private def objectValue(): Unit =
        consume('{')
        whitespace()
        val keys = mutable.HashSet.empty[String]
        if accept('}') then return
        var done = false
        while !done do
          whitespace()
          require(current == '"', "Manifest object key must be a string")
          val key = stringValue()
          require(keys.add(key), "Manifest contains a duplicate object key")
          whitespace()
          consume(':')
          value()
          whitespace()
          if accept('}') then done = true
          else consume(',')

      private def arrayValue(): Unit =
        consume('[')
        whitespace()
        if accept(']') then return
        var done = false
        while !done do
          value()
          whitespace()
          if accept(']') then done = true
          else consume(',')

      private def stringValue(): String =
        consume('"')
        val result = StringBuilder()
        var done = false
        while !done do
          require(index < input.length, "Manifest contains an unterminated string")
          val character = input.charAt(index)
          index += 1
          character match
            case '"' => done = true
            case '\\' =>
              require(index < input.length, "Manifest contains an invalid escape")
              val escaped = input.charAt(index)
              index += 1
              escaped match
                case '"'  => result += '"'
                case '\\' => result += '\\'
                case '/'  => result += '/'
                case 'b'  => result += '\b'
                case 'f'  => result += '\f'
                case 'n'  => result += '\n'
                case 'r'  => result += '\r'
                case 't'  => result += '\t'
                case 'u'  => result += unicodeEscape()
                case _    => fail("Manifest contains an invalid escape")
            case value if value < ' ' => fail("Manifest string contains a control character")
            case value => result += value
        result.result()

      private def unicodeEscape(): Char =
        require(index + 4 <= input.length, "Manifest contains an invalid Unicode escape")
        val digits = input.substring(index, index + 4)
        require(digits.forall(value => Character.digit(value, 16) >= 0), "Manifest contains an invalid Unicode escape")
        index += 4
        Integer.parseInt(digits, 16).toChar

      private def number(): Unit =
        accept('-')
        if accept('0') then
          require(!currentOption.exists(_.isDigit), "Manifest contains an invalid number")
        else
          require(currentOption.exists(value => value >= '1' && value <= '9'), "Manifest contains an invalid number")
          while currentOption.exists(_.isDigit) do index += 1
        if accept('.') then
          require(currentOption.exists(_.isDigit), "Manifest contains an invalid number")
          while currentOption.exists(_.isDigit) do index += 1
        if currentOption.exists(value => value == 'e' || value == 'E') then
          index += 1
          if currentOption.exists(value => value == '+' || value == '-') then index += 1
          require(currentOption.exists(_.isDigit), "Manifest contains an invalid number")
          while currentOption.exists(_.isDigit) do index += 1

      private def literal(expected: String): Unit =
        require(input.startsWith(expected, index), "Manifest is not valid strict JSON")
        index += expected.length

      private def whitespace(): Unit =
        while currentOption.exists(value => value == ' ' || value == '\n' || value == '\r' || value == '\t') do
          index += 1

      private def consume(expected: Char): Unit =
        require(accept(expected), "Manifest is not valid strict JSON")

      private def accept(expected: Char): Boolean =
        if currentOption.contains(expected) then
          index += 1
          true
        else false

      private def current: Char = currentOption.getOrElse(fail("Manifest is not valid strict JSON"))
      private def currentOption: Option[Char] = Option.when(index < input.length)(input.charAt(index))
      private def require(condition: Boolean, message: String): Unit = if !condition then fail(message)
      private def fail(message: String): Nothing = throw IllegalArgumentException(message)
