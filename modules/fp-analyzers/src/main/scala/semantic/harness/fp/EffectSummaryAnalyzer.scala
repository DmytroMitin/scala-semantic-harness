package semantic.harness.fp

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import scala.util.matching.Regex

object EffectSummaryAnalyzer:
  def summarize(path: Path): Either[String, EffectSummaryReport] =
    if !Files.exists(path) then Left(s"Source file does not exist: $path")
    else if !Files.isRegularFile(path) then Left(s"Source path is not a regular file: $path")
    else
      try
        val source = Files.readString(path, StandardCharsets.UTF_8)
        Right(summarizeSource(path.toString, source))
      catch
        case exception: Exception =>
          Left(s"Unable to read source file: ${exception.getMessage}")

  def summarizeSource(sourceName: String, source: String): EffectSummaryReport =
    val packageName = extractPackageName(source)

    val (_, methods) =
      source.linesIterator.zipWithIndex.foldLeft((List.empty[OwnerContext], List.empty[EffectMethodSummary])) {
        case ((owners, methods), (line, lineIndex)) =>
          val currentOwners = popClosedOwners(owners, line)
          val method = parseMethodLine(line, lineIndex, currentOwners.headOption, packageName, Some(sourceName))
          val nextOwners = parseOwnerLine(line) match
            case Some(owner) => owner :: currentOwners
            case None => currentOwners

          (nextOwners, method.toList ::: methods)
      }

    EffectSummaryReport(
      source = sourceName,
      methods = methods.reverse
    )

  private[fp] def classify(returnType: String): (String, String, List[String]) =
    val normalized = returnType.trim
    val outer = outerTypeName(normalized)

    outer match
      case None =>
        ("unknown", "unknown", List("Unable to identify the outer return type."))
      case Some(name) if name == "Option" =>
        ("option", "declared", List("Return type encodes optional absence with Option."))
      case Some(name) if name == "Either" =>
        ("either", "declared", List("Return type encodes typed failure with Either."))
      case Some(name) if name == "Future" =>
        ("future", "declared", List("Return type encodes asynchronous execution with Future."))
      case Some(name) if name == "IO" =>
        ("io", "declared", List("Return type is syntactically recognized as IO."))
      case Some(name) if Set("ZIO", "Task", "UIO").contains(name) =>
        ("zio", "declared", List(s"Return type is syntactically recognized as $name."))
      case Some(name) if isGenericEffectName(name) && hasTypeArguments(normalized) =>
        ("generic-effect", "declared", List(s"Outer return type is an abstract unary effect $name[_]."))
      case Some(name) if isPlainTypeName(name) =>
        ("plain", "declared", Nil)
      case Some(_) if hasTypeArguments(normalized) =>
        ("unknown", "unknown", List("Outer parameterized return type is not recognized by v0."))
      case Some(_) =>
        ("plain", "declared", Nil)

  private val DefPattern: Regex =
    """^\s*(?:override\s+|private\s+|protected\s+|final\s+|inline\s+|transparent\s+)*def\s+([A-Za-z_][A-Za-z0-9_]*)\b(.*)$""".r

  private val OwnerPattern: Regex =
    """^\s*(?:(?:private|protected|final|abstract|sealed|open)\s+)*(object|class|trait|enum)\s+([A-Za-z_][A-Za-z0-9_]*)\b.*(?::|\{)\s*(?://.*)?$""".r

  private val PackagePattern: Regex =
    """^\s*package\s+([A-Za-z_][A-Za-z0-9_]*(?:\.[A-Za-z_][A-Za-z0-9_]*)*)\s*:?\s*(?://.*)?$""".r

  private final case class OwnerContext(name: String, kind: String, indent: Int)

  private def parseMethodLine(
    line: String,
    lineIndex: Int,
    owner: Option[OwnerContext],
    packageName: Option[String],
    sourceFile: Option[String]
  ): Option[EffectMethodSummary] =
    line match
      case DefPattern(name, rest) =>
        val startCharacter = line.indexOf("def")
        val range = EffectSourceRange(lineIndex, startCharacter.max(0), lineIndex, line.length)
        val declared = declaredReturnType(rest)
        val ownerName = owner.map(_.name)
        val qualifiedName = ownerName.map(owner => s"$owner.$name")
        val enclosingKind = owner.map(_.kind)
        val packageQualifiedName =
          for
            packageName <- packageName
            qualifiedName <- qualifiedName
          yield s"$packageName.$qualifiedName"
        declared match
          case Some(returnType) =>
            val (category, confidence, notes) = classify(returnType)
            Some(
              EffectMethodSummary(
                name = name,
                range = Some(range),
                declaredReturnType = Some(returnType),
                inferredReturnType = None,
                effectCategory = category,
                confidence = confidence,
                notes = notes,
                ownerName = ownerName,
                qualifiedName = qualifiedName,
                enclosingKind = enclosingKind,
                packageName = packageName,
                packageQualifiedName = packageQualifiedName,
                sourceFile = sourceFile
              )
            )
          case None =>
            Some(
              EffectMethodSummary(
                name = name,
                range = Some(range),
                declaredReturnType = None,
                inferredReturnType = None,
                effectCategory = "unknown",
                confidence = "unknown",
                notes = List("No declared return type was available; v0 does not infer return types."),
                ownerName = ownerName,
                qualifiedName = qualifiedName,
                enclosingKind = enclosingKind,
                packageName = packageName,
                packageQualifiedName = packageQualifiedName,
                sourceFile = sourceFile
              )
            )
      case _ =>
        None

  private def popClosedOwners(owners: List[OwnerContext], line: String): List[OwnerContext] =
    if line.trim.isEmpty || line.trim.startsWith("//") then owners
    else
      val lineIndent = indentation(line)
      owners.dropWhile(owner => lineIndent <= owner.indent)

  private def parseOwnerLine(line: String): Option[OwnerContext] =
    line match
      case OwnerPattern(kind, name) =>
        Some(OwnerContext(name = name, kind = kind, indent = indentation(line)))
      case _ =>
        None

  private def extractPackageName(source: String): Option[String] =
    source.linesIterator.collectFirst {
      case PackagePattern(name) => name
    }

  private def indentation(line: String): Int =
    line.segmentLength(ch => ch == ' ' || ch == '\t')

  private def declaredReturnType(rest: String): Option[String] =
    val colon = findReturnColon(rest)
    colon.flatMap { index =>
      val afterColon = rest.drop(index + 1)
      val end = topLevelReturnTypeEnd(afterColon)
      val returnType = afterColon.take(end).trim
      Option.when(returnType.nonEmpty)(returnType)
    }

  private def findReturnColon(text: String): Option[Int] =
    var round = 0
    var square = 0
    var curly = 0
    var index = 0
    var result: Option[Int] = None

    while index < text.length && result.isEmpty do
      text.charAt(index) match
        case '(' => round += 1
        case ')' => round = (round - 1).max(0)
        case '[' => square += 1
        case ']' => square = (square - 1).max(0)
        case '{' => curly += 1
        case '}' => curly = (curly - 1).max(0)
        case ':' if round == 0 && square == 0 && curly == 0 =>
          result = Some(index)
        case _ => ()
      index += 1

    result

  private def topLevelReturnTypeEnd(text: String): Int =
    var round = 0
    var square = 0
    var curly = 0
    var index = 0
    var done = false

    while index < text.length && !done do
      text.charAt(index) match
        case '(' => round += 1
        case ')' => round = (round - 1).max(0)
        case '[' => square += 1
        case ']' => square = (square - 1).max(0)
        case '{' => curly += 1
        case '}' => curly = (curly - 1).max(0)
        case '=' if round == 0 && square == 0 && curly == 0 =>
          done = true
        case _ => ()
      if !done then index += 1

    index

  private def outerTypeName(returnType: String): Option[String] =
    val trimmed = returnType.trim
    val withoutQualifier = trimmed.takeWhile(ch => ch != '[' && ch != '(' && !ch.isWhitespace)
    val name = withoutQualifier.split('.').lastOption.getOrElse("").trim
    Option.when(name.matches("[A-Za-z_][A-Za-z0-9_]*"))(name)

  private def hasTypeArguments(returnType: String): Boolean =
    returnType.contains("[") && returnType.contains("]")

  private def isGenericEffectName(name: String): Boolean =
    name.matches("[A-Z][A-Za-z0-9_]*") && name.length <= 2 && !isPlainTypeName(name)

  private def isPlainTypeName(name: String): Boolean =
    Set(
      "String",
      "Int",
      "Long",
      "Double",
      "Float",
      "Boolean",
      "Unit",
      "Char",
      "Byte",
      "Short",
      "BigInt",
      "BigDecimal"
    ).contains(name)
