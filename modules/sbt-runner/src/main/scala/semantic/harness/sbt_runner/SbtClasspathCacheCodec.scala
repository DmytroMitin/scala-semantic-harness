package semantic.harness.sbt_runner

import io.circe.Json
import io.circe.JsonObject
import io.circe.parser.parse
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import scala.util.Try

object SbtClasspathCacheCodec:
  private val TopFields = Set(
    "format",
    "acquisitionProtocol",
    "identity",
    "acquiredAtEpochMillis",
    "inputEvidence",
    "entryEvidenceCoverageVersion",
    "entries",
    "entryCount"
  )
  private val IdentityFields = Set(
    "cacheFormat",
    "acquisitionProtocol",
    "workspaceDigest",
    "project",
    "configuration",
    "storageKey"
  )
  private val InputFields =
    Set("coverageVersion", "fileCount", "totalBytes", "sha256", "projectRootPresent")
  private val EntryFields =
    Set("path", "kind", "fileCount", "totalBytes", "sha256")
  private val Sha256Pattern = "^[0-9a-f]{64}$".r

  def encode(record: SbtClasspathCacheRecord): Array[Byte] =
    Json
      .obj(
        "format" -> Json.fromString(record.format),
        "acquisitionProtocol" -> Json.fromString(record.acquisitionProtocol),
        "identity" -> encodeIdentity(record.identity),
        "acquiredAtEpochMillis" -> Json.fromLong(record.acquiredAtEpochMillis),
        "inputEvidence" -> encodeInput(record.inputEvidence),
        "entryEvidenceCoverageVersion" -> Json.fromString(
          record.entryEvidenceCoverageVersion
        ),
        "entries" -> Json.fromValues(record.entries.map(encodeEntry)),
        "entryCount" -> Json.fromInt(record.entryCount)
      )
      .noSpaces
      .getBytes(StandardCharsets.UTF_8)

  def decode(
      bytes: Array[Byte],
      expectedIdentity: SbtClasspathCacheIdentity
  ): Either[SbtClasspathCacheFailure, SbtClasspathCacheRecord] =
    if bytes.length.toLong > SbtClasspathCacheBounds.MaxCacheFileBytes then
      Left(
        SbtClasspathCacheFailure.Invalid(
          s"Sbt classpath cache record exceeds ${SbtClasspathCacheBounds.MaxCacheFileBytes} bytes"
        )
      )
    else
      decodeUtf8(bytes).flatMap { content =>
        parse(content)
          .left
          .map(error => invalid(s"malformed JSON: ${error.message}"))
          .flatMap(json => decodeRecord(json, expectedIdentity))
      }

  private def decodeRecord(
      json: Json,
      expectedIdentity: SbtClasspathCacheIdentity
  ): Either[SbtClasspathCacheFailure, SbtClasspathCacheRecord] =
    for
      objectValue <- requiredObject(json, "top-level record")
      _ <- exactFields(objectValue, TopFields, "top-level record")
      format <- requiredString(objectValue, "format")
      _ <-
        if format == SbtClasspathCacheRecord.Format then Right(())
        else Left(invalid(s"unsupported cache format '$format'"))
      acquisitionProtocol <- requiredString(objectValue, "acquisitionProtocol")
      _ <-
        if acquisitionProtocol == SbtClasspathProtocol.Format then Right(())
        else Left(invalid(s"unsupported acquisition protocol '$acquisitionProtocol'"))
      identityJson <- requiredField(objectValue, "identity")
      identity <- decodeIdentity(identityJson)
      _ <-
        if identity == expectedIdentity then Right(())
        else Left(invalid("cache identity does not match the selected workspace/project/configuration"))
      acquiredAt <- requiredLong(objectValue, "acquiredAtEpochMillis")
      _ <-
        if acquiredAt >= 0L then Right(())
        else Left(invalid("acquisition time must be non-negative"))
      inputJson <- requiredField(objectValue, "inputEvidence")
      input <- decodeInput(inputJson)
      entryCoverage <- requiredString(objectValue, "entryEvidenceCoverageVersion")
      _ <-
        if entryCoverage == SbtClasspathEntryEvidence.CoverageVersion then Right(())
        else Left(invalid(s"unsupported entry evidence coverage '$entryCoverage'"))
      entryCount <- requiredInt(objectValue, "entryCount")
      _ <-
        if entryCount > 0 && entryCount <= SbtClasspathCacheBounds.MaxClasspathEntries then
          Right(())
        else Left(invalid("cache entry count is outside supported bounds"))
      entriesJson <- requiredField(objectValue, "entries")
      entryArray <- entriesJson.asArray.toRight(invalid("entries must be an array"))
      _ <-
        if entryArray.size == entryCount then Right(())
        else Left(invalid("cache entry count does not match entries array"))
      entries <- entryArray.toList.foldLeft[
        Either[SbtClasspathCacheFailure, List[SbtClasspathEntryEvidence]]
      ](Right(Nil))((result, value) =>
        result.flatMap(values => decodeEntry(value).map(values :+ _))
      )
      _ <-
        if entries.map(_.path).distinct.size == entries.size then
          Right(())
        else Left(invalid("cache contains duplicate classpath entry records"))
      _ <- validateAggregateEntryBounds(entries)
    yield SbtClasspathCacheRecord(
      format = format,
      acquisitionProtocol = acquisitionProtocol,
      identity = identity,
      acquiredAtEpochMillis = acquiredAt,
      inputEvidence = input,
      entryEvidenceCoverageVersion = entryCoverage,
      entries = entries,
      entryCount = entryCount
    )

  private def encodeIdentity(identity: SbtClasspathCacheIdentity): Json =
    Json.obj(
      "cacheFormat" -> Json.fromString(identity.cacheFormat),
      "acquisitionProtocol" -> Json.fromString(identity.acquisitionProtocol),
      "workspaceDigest" -> Json.fromString(identity.workspaceDigest),
      "project" -> Json.fromString(identity.project.value),
      "configuration" -> Json.fromString(
        SbtClasspathConfiguration.value(identity.configuration)
      ),
      "storageKey" -> Json.fromString(identity.storageKey)
    )

  private def decodeIdentity(
      json: Json
  ): Either[SbtClasspathCacheFailure, SbtClasspathCacheIdentity] =
    for
      value <- requiredObject(json, "identity")
      _ <- exactFields(value, IdentityFields, "identity")
      cacheFormat <- requiredString(value, "cacheFormat")
      acquisitionProtocol <- requiredString(value, "acquisitionProtocol")
      workspaceDigest <- requiredString(value, "workspaceDigest")
      _ <- validateSha256(workspaceDigest, "workspace identity digest")
      projectText <- requiredString(value, "project")
      project <- SbtProjectId.parse(projectText).left.map(message => invalid(message))
      configurationText <- requiredString(value, "configuration")
      configuration <- SbtClasspathConfiguration
        .parse(configurationText)
        .left
        .map(message => invalid(message))
      storageKey <- requiredString(value, "storageKey")
      _ <- validateSha256(storageKey, "storage key")
    yield SbtClasspathCacheIdentity(
      cacheFormat,
      acquisitionProtocol,
      workspaceDigest,
      project,
      configuration,
      storageKey
    )

  private def encodeInput(evidence: SbtClasspathInputEvidence): Json =
    Json.obj(
      "coverageVersion" -> Json.fromString(evidence.coverageVersion),
      "fileCount" -> Json.fromLong(evidence.fileCount),
      "totalBytes" -> Json.fromLong(evidence.totalBytes),
      "sha256" -> Json.fromString(evidence.sha256),
      "projectRootPresent" -> Json.fromBoolean(evidence.projectRootPresent)
    )

  private def decodeInput(
      json: Json
  ): Either[SbtClasspathCacheFailure, SbtClasspathInputEvidence] =
    for
      value <- requiredObject(json, "inputEvidence")
      _ <- exactFields(value, InputFields, "inputEvidence")
      coverage <- requiredString(value, "coverageVersion")
      _ <-
        if coverage == "conventional-inputs.v1" then Right(())
        else Left(invalid(s"unsupported input evidence coverage '$coverage'"))
      fileCount <- requiredLong(value, "fileCount")
      totalBytes <- requiredLong(value, "totalBytes")
      digest <- requiredString(value, "sha256")
      _ <- validateSha256(digest, "input evidence digest")
      projectRootPresent <- requiredBoolean(value, "projectRootPresent")
      _ <-
        if fileCount >= 0L && fileCount <= SbtClasspathCacheBounds.MaxInputFiles then Right(())
        else Left(invalid("input evidence file count is outside supported bounds"))
      _ <-
        if totalBytes >= 0L && totalBytes <= SbtClasspathCacheBounds.MaxTotalInputBytes then
          Right(())
        else Left(invalid("input evidence bytes are outside supported bounds"))
    yield SbtClasspathInputEvidence(
      coverage,
      fileCount,
      totalBytes,
      digest,
      projectRootPresent
    )

  private def encodeEntry(evidence: SbtClasspathEntryEvidence): Json =
    Json.obj(
      "path" -> Json.fromString(evidence.path),
      "kind" -> Json.fromString(SbtClasspathEntryKind.value(evidence.kind)),
      "fileCount" -> Json.fromLong(evidence.fileCount),
      "totalBytes" -> Json.fromLong(evidence.totalBytes),
      "sha256" -> Json.fromString(evidence.sha256)
    )

  private def decodeEntry(
      json: Json
  ): Either[SbtClasspathCacheFailure, SbtClasspathEntryEvidence] =
    for
      value <- requiredObject(json, "entry")
      _ <- exactFields(value, EntryFields, "entry")
      pathText <- requiredString(value, "path")
      _ <-
        if pathText.getBytes(StandardCharsets.UTF_8).length <=
            SbtClasspathCacheBounds.MaxStoredPathBytes
        then Right(())
        else Left(invalid("stored entry path exceeds supported bounds"))
      path <- Try(Path.of(pathText)).toEither.left.map(_ => invalid("stored entry path is invalid"))
      normalized = path.toAbsolutePath.normalize()
      _ <-
        if path.isAbsolute && path == normalized then Right(())
        else Left(invalid("stored entry path is not absolute and normalized"))
      kindText <- requiredString(value, "kind")
      kind <- SbtClasspathEntryKind.parse(kindText).left.map(message => invalid(message))
      fileCount <- requiredLong(value, "fileCount")
      totalBytes <- requiredLong(value, "totalBytes")
      digest <- requiredString(value, "sha256")
      _ <- validateSha256(digest, "entry evidence digest")
      _ <-
        if fileCount >= 0L && fileCount <= SbtClasspathCacheBounds.MaxClassDirectoryFiles then
          Right(())
        else Left(invalid("entry evidence file count is outside supported bounds"))
      _ <-
        if totalBytes >= 0L &&
            totalBytes <= math.max(
              SbtClasspathCacheBounds.MaxTotalClassDirectoryBytes,
              SbtClasspathCacheBounds.MaxJarBytes
            )
        then Right(())
        else Left(invalid("entry evidence bytes are outside supported bounds"))
      _ <-
        kind match
          case SbtClasspathEntryKind.Jar
              if fileCount != 1L || totalBytes > SbtClasspathCacheBounds.MaxJarBytes =>
            Left(invalid("JAR entry evidence is outside supported bounds"))
          case SbtClasspathEntryKind.Directory
              if fileCount > SbtClasspathCacheBounds.MaxClassDirectoryFiles ||
                totalBytes > SbtClasspathCacheBounds.MaxTotalClassDirectoryBytes =>
            Left(invalid("class-directory entry evidence is outside supported bounds"))
          case _ =>
            Right(())
    yield SbtClasspathEntryEvidence(pathText, kind, fileCount, totalBytes, digest)

  private def validateAggregateEntryBounds(
      entries: List[SbtClasspathEntryEvidence]
  ): Either[SbtClasspathCacheFailure, Unit] =
    val jarBytes = entries
      .filter(_.kind == SbtClasspathEntryKind.Jar)
      .foldLeft(BigInt(0))((total, entry) => total + entry.totalBytes)
    val directoryEntries =
      entries.filter(_.kind == SbtClasspathEntryKind.Directory)
    val directoryFiles =
      directoryEntries.foldLeft(BigInt(0))((total, entry) => total + entry.fileCount)
    val directoryBytes =
      directoryEntries.foldLeft(BigInt(0))((total, entry) => total + entry.totalBytes)
    if jarBytes > BigInt(SbtClasspathCacheBounds.MaxTotalJarBytes) then
      Left(invalid("total JAR entry evidence exceeds supported bounds"))
    else if directoryFiles > BigInt(SbtClasspathCacheBounds.MaxClassDirectoryFiles) then
      Left(invalid("total class-directory file evidence exceeds supported bounds"))
    else if directoryBytes > BigInt(SbtClasspathCacheBounds.MaxTotalClassDirectoryBytes) then
      Left(invalid("total class-directory byte evidence exceeds supported bounds"))
    else Right(())

  private def decodeUtf8(
      bytes: Array[Byte]
  ): Either[SbtClasspathCacheFailure, String] =
    Try(
      StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes))
        .toString
    ).toEither.left.map(_ => invalid("cache record is not valid UTF-8"))

  private def exactFields(
      value: JsonObject,
      expected: Set[String],
      label: String
  ): Either[SbtClasspathCacheFailure, Unit] =
    val actual = value.keys.toSet
    if actual == expected then Right(())
    else Left(invalid(s"$label fields do not exactly match the supported format"))

  private def requiredObject(
      json: Json,
      label: String
  ): Either[SbtClasspathCacheFailure, JsonObject] =
    json.asObject.toRight(invalid(s"$label must be an object"))

  private def requiredField(
      value: JsonObject,
      name: String
  ): Either[SbtClasspathCacheFailure, Json] =
    value(name).toRight(invalid(s"missing required field '$name'"))

  private def requiredString(
      value: JsonObject,
      name: String
  ): Either[SbtClasspathCacheFailure, String] =
    requiredField(value, name).flatMap(
      _.asString.filter(_.nonEmpty).toRight(invalid(s"field '$name' must be a nonempty string"))
    )

  private def requiredLong(
      value: JsonObject,
      name: String
  ): Either[SbtClasspathCacheFailure, Long] =
    requiredField(value, name).flatMap(
      _.asNumber.flatMap(_.toLong).toRight(invalid(s"field '$name' must be an integer"))
    )

  private def requiredInt(
      value: JsonObject,
      name: String
  ): Either[SbtClasspathCacheFailure, Int] =
    requiredLong(value, name).flatMap { number =>
      if number >= Int.MinValue && number <= Int.MaxValue then Right(number.toInt)
      else Left(invalid(s"field '$name' is outside integer bounds"))
    }

  private def requiredBoolean(
      value: JsonObject,
      name: String
  ): Either[SbtClasspathCacheFailure, Boolean] =
    requiredField(value, name).flatMap(
      _.asBoolean.toRight(invalid(s"field '$name' must be a boolean"))
    )

  private def validateSha256(
      value: String,
      label: String
  ): Either[SbtClasspathCacheFailure, Unit] =
    value match
      case Sha256Pattern() => Right(())
      case _               => Left(invalid(s"$label is not a lowercase SHA-256 value"))

  private def invalid(message: String): SbtClasspathCacheFailure =
    SbtClasspathCacheFailure.Invalid(s"Invalid sbt classpath cache record: $message")
