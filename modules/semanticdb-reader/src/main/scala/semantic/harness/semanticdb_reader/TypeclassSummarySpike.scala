package semantic.harness.semanticdb_reader

import java.nio.file.Files
import java.nio.file.Path
import scala.meta.internal.semanticdb.ApplyTree
import scala.meta.internal.semanticdb.ClassSignature
import scala.meta.internal.semanticdb.IdTree
import scala.meta.internal.semanticdb.MethodSignature
import scala.meta.internal.semanticdb.SelectTree
import scala.meta.internal.semanticdb.Signature
import scala.meta.internal.semanticdb.SingleType
import scala.meta.internal.semanticdb.SymbolInformation
import scala.meta.internal.semanticdb.SymbolOccurrence
import scala.meta.internal.semanticdb.Synthetic
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.internal.semanticdb.TextDocuments
import scala.meta.internal.semanticdb.Tree
import scala.meta.internal.semanticdb.Type
import scala.meta.internal.semanticdb.TypeApplyTree
import scala.meta.internal.semanticdb.TypeRef
import scala.meta.internal.semanticdb.ValueSignature

enum TypeclassSummarySpikeStatus:
  case EvidenceFound
  case NoEvidence
  case Truncated

enum ContextDeclarationStyle:
  case Given
  case Implicit

enum ContextIdentityKind:
  case GlobalSymbol
  case SourceLocal
  case ContextParameter

final case class SelectedContextEvidence(
  displayName: String,
  identityKind: ContextIdentityKind,
  semanticdbSymbol: Option[String],
  declarationStyle: ContextDeclarationStyle,
  definitionRange: Option[SemanticRange],
  contextTypeSymbols: List[String]
)

final case class TypeclassUseSiteEvidence(
  range: SemanticRange,
  selectedContexts: List[SelectedContextEvidence],
  referencedSymbols: List[String]
)

final case class TypeclassSummarySpikeReport(
  status: TypeclassSummarySpikeStatus,
  useSites: List[TypeclassUseSiteEvidence],
  totalSyntheticCount: Int,
  scannedSyntheticCount: Int,
  truncated: Boolean,
  warnings: List[String]
)

object TypeclassSummarySpike:
  val MaxInputBytes: Long = 4L * 1024L * 1024L
  val MaxSynthetics: Int = 1024
  val MaxUseSites: Int = 256
  val MaxSelectedContextsPerSite: Int = 8
  val MaxReferencedSymbolsPerSite: Int = 16
  val MaxSymbolLength: Int = 1024
  val MaxDisplayNameLength: Int = 256

  private val CommonWarnings = List(
    "Only compiler-selected context arguments backed by source-owned GIVEN or IMPLICIT SemanticDB symbol information are reported.",
    "The spike does not enumerate every in-scope instance or claim why another candidate was rejected.",
    "Missing and ambiguous instances remain compiler-diagnostic questions."
  )

  private final case class ExtractedUseSite(
    evidence: TypeclassUseSiteEvidence,
    truncated: Boolean
  )

  def summarize(path: Path): Either[String, TypeclassSummarySpikeReport] =
    if !Files.exists(path) then Left("SemanticDB file does not exist")
    else if !Files.isRegularFile(path) then Left("SemanticDB path is not a regular file")
    else if path.getFileName == null || !path.getFileName.toString.endsWith(".semanticdb") then
      Left("SemanticDB path must point to a .semanticdb file")
    else
      try
        if Files.size(path) > MaxInputBytes then
          Left(s"SemanticDB file exceeds the $MaxInputBytes byte spike limit")
        else
          val documents = TextDocuments.parseFrom(Files.readAllBytes(path))
          documents.documents.toList match
            case document :: Nil => Right(summarize(document))
            case Nil => Left("SemanticDB file contains no documents")
            case values => Left(s"SemanticDB spike requires exactly one document, found ${values.size}")
      catch
        case _: Exception =>
          Left("Unable to read SemanticDB file: malformed or unsupported SemanticDB payload")

  def summarize(document: TextDocument): TypeclassSummarySpikeReport =
    val totalSyntheticCount = document.synthetics.size
    val scanned = document.synthetics.take(MaxSynthetics).toList
    val extracted = scanned.flatMap(synthetic => summarizeSynthetic(document, synthetic))
    val useSites = extracted.take(MaxUseSites).map(_.evidence)
    val truncated =
      totalSyntheticCount > MaxSynthetics ||
        extracted.size > MaxUseSites ||
        extracted.exists(_.truncated)
    val status =
      if truncated then TypeclassSummarySpikeStatus.Truncated
      else if useSites.nonEmpty then TypeclassSummarySpikeStatus.EvidenceFound
      else TypeclassSummarySpikeStatus.NoEvidence

    TypeclassSummarySpikeReport(
      status = status,
      useSites = useSites,
      totalSyntheticCount = totalSyntheticCount,
      scannedSyntheticCount = scanned.size,
      truncated = truncated,
      warnings = CommonWarnings
    )

  private def summarizeSynthetic(
      document: TextDocument,
      synthetic: Synthetic
  ): Option[ExtractedUseSite] =
    synthetic.range.flatMap { range =>
      val allSelected =
        appliedArgumentSymbols(synthetic.tree)
          .distinct
          .flatMap(symbol => selectedContext(document, symbol))

      Option.when(allSelected.nonEmpty) {
        val semanticRange = toRange(range)
        val allReferences = referencesWithin(document, semanticRange)
        ExtractedUseSite(
          evidence = TypeclassUseSiteEvidence(
            range = semanticRange,
            selectedContexts = allSelected.take(MaxSelectedContextsPerSite),
            referencedSymbols = allReferences.take(MaxReferencedSymbolsPerSite)
          ),
          truncated =
            allSelected.size > MaxSelectedContextsPerSite ||
              allReferences.size > MaxReferencedSymbolsPerSite
        )
      }
    }

  private def selectedContext(
      document: TextDocument,
      selectedSymbol: String
  ): Option[SelectedContextEvidence] =
    contextInformation(document, selectedSymbol).flatMap { info =>
      declarationStyle(info).flatMap { style =>
        Option
          .when(info.displayName.nonEmpty && info.displayName.length <= MaxDisplayNameLength) {
            val identityKind =
              if info.kind == SymbolInformation.Kind.PARAMETER then ContextIdentityKind.ContextParameter
              else if info.symbol.startsWith("local") then ContextIdentityKind.SourceLocal
              else ContextIdentityKind.GlobalSymbol
            SelectedContextEvidence(
              displayName =
                if identityKind == ContextIdentityKind.ContextParameter &&
                    info.displayName.startsWith("evidence$")
                then "context-bound parameter"
                else info.displayName,
              identityKind = identityKind,
              semanticdbSymbol =
                Option.when(identityKind == ContextIdentityKind.GlobalSymbol && stableSymbol(info.symbol))(info.symbol),
              declarationStyle = style,
              definitionRange = definitionRange(document, info.symbol),
              contextTypeSymbols = contextTypeSymbols(info.signature)
                .filter(stableSymbol)
                .filterNot(ignoredContextType)
                .distinct
                .sorted
            )
          }
      }
    }

  private def contextInformation(
      document: TextDocument,
      selectedSymbol: String
  ): Option[SymbolInformation] =
    document.symbols.find(_.symbol == selectedSymbol).orElse {
      document.symbols.find {
        case info if hasContextProperty(info) =>
          info.signature match
            case signature: ClassSignature =>
              signature.self match
                case value: SingleType => value.symbol == selectedSymbol
                case _ => false
            case _ => false
        case _ => false
      }
    }

  private def declarationStyle(info: SymbolInformation): Option[ContextDeclarationStyle] =
    if hasProperty(info, SymbolInformation.Property.GIVEN) then Some(ContextDeclarationStyle.Given)
    else if hasProperty(info, SymbolInformation.Property.IMPLICIT) then Some(ContextDeclarationStyle.Implicit)
    else None

  private def hasContextProperty(info: SymbolInformation): Boolean =
    declarationStyle(info).nonEmpty

  private def hasProperty(
      info: SymbolInformation,
      property: SymbolInformation.Property
  ): Boolean =
    (info.properties & property.value) != 0

  private def appliedArgumentSymbols(tree: Tree): List[String] =
    tree match
      case value: ApplyTree =>
        value.arguments.toList.flatMap(treeSymbols) ++ appliedArgumentSymbols(value.function)
      case value: TypeApplyTree =>
        appliedArgumentSymbols(value.function)
      case value: SelectTree =>
        appliedArgumentSymbols(value.qualifier)
      case _ => Nil

  private def treeSymbols(tree: Tree): List[String] =
    tree match
      case value: IdTree =>
        Option.when(stableInputSymbol(value.symbol))(value.symbol).toList
      case value: SelectTree =>
        treeSymbols(value.qualifier) ++ value.id.toList.map(_.symbol).filter(stableInputSymbol)
      case value: ApplyTree =>
        treeSymbols(value.function) ++ value.arguments.toList.flatMap(treeSymbols)
      case value: TypeApplyTree =>
        treeSymbols(value.function)
      case _ => Nil

  private def referencesWithin(document: TextDocument, site: SemanticRange): List[String] =
    document.occurrences.toList
      .filter(_.role == SymbolOccurrence.Role.REFERENCE)
      .flatMap { occurrence =>
        occurrence.range
          .map(toRange)
          .filter(range => contains(site, range))
          .map(_ => occurrence.symbol)
      }
      .filter(stableSymbol)
      .distinct

  private def definitionRange(document: TextDocument, symbol: String): Option[SemanticRange] =
    document.occurrences
      .find(occurrence =>
        occurrence.symbol == symbol &&
          occurrence.role == SymbolOccurrence.Role.DEFINITION
      )
      .flatMap(_.range)
      .map(toRange)

  private def contextTypeSymbols(signature: Signature): List[String] =
    signature match
      case value: ClassSignature =>
        value.parents.toList.flatMap(typeSymbols)
      case value: ValueSignature =>
        typeSymbols(value.tpe)
      case value: MethodSignature =>
        typeSymbols(value.returnType)
      case _ => Nil

  private def typeSymbols(value: Type): List[String] =
    value match
      case ref: TypeRef =>
        List(ref.symbol)
      case single: SingleType =>
        List(single.symbol)
      case _ => Nil

  private def contains(outer: SemanticRange, inner: SemanticRange): Boolean =
    positionAtOrBefore(outer.startLine, outer.startCharacter, inner.startLine, inner.startCharacter) &&
      positionAtOrBefore(inner.endLine, inner.endCharacter, outer.endLine, outer.endCharacter)

  private def positionAtOrBefore(
      leftLine: Int,
      leftCharacter: Int,
      rightLine: Int,
      rightCharacter: Int
  ): Boolean =
    leftLine < rightLine || (leftLine == rightLine && leftCharacter <= rightCharacter)

  private def stableInputSymbol(symbol: String): Boolean =
    symbol.nonEmpty && symbol.length <= MaxSymbolLength

  private def stableSymbol(symbol: String): Boolean =
    stableInputSymbol(symbol) && !symbol.startsWith("local")

  private def ignoredContextType(symbol: String): Boolean =
    Set("java/lang/Object#", "scala/Any#", "scala/AnyRef#", "scala/Nothing#").contains(symbol)

  private def toRange(value: scala.meta.internal.semanticdb.Range): SemanticRange =
    SemanticRange(
      startLine = value.startLine,
      startCharacter = value.startCharacter,
      endLine = value.endLine,
      endCharacter = value.endCharacter
    )
