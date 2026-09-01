package semantic.harness.sbt_runner

import scala.collection.mutable

enum SbtInternalDependencyRole:
  case Direct
  case Transitive

enum SbtCompileDependencyMapping:
  case DefaultCompileToCompile
  case ExplicitCompileToCompile
  case ExcludedNoCompileToCompile
  case UnsupportedOrAmbiguous

  def admitted: Boolean = this match
    case SbtCompileDependencyMapping.DefaultCompileToCompile |
        SbtCompileDependencyMapping.ExplicitCompileToCompile => true
    case _ => false

object SbtCompileDependencyMapping:
  private val SimpleName = "[a-z][a-z0-9_-]*".r

  def classify(value: Option[String]): SbtCompileDependencyMapping = value match
    case None => SbtCompileDependencyMapping.DefaultCompileToCompile
    case Some(raw) =>
      val normalized = raw.trim.toLowerCase(java.util.Locale.ROOT)
      if normalized == "compile" then SbtCompileDependencyMapping.ExplicitCompileToCompile
      else if normalized.isEmpty then SbtCompileDependencyMapping.UnsupportedOrAmbiguous
      else
        val clauses = normalized.split(";", -1).toList
        val parsed = clauses.foldLeft[Option[List[(String, String)]]](Some(Nil)) {
          case (Some(values), clause) =>
            clause.split("->", -1).toList match
              case left :: right :: Nil
                  if SimpleName.matches(left) && SimpleName.matches(right) =>
                Some(values :+ (left -> right))
              case _ => None
          case (None, _) => None
        }
        parsed match
          case Some(values) if values.exists(_ == ("compile" -> "compile")) =>
            SbtCompileDependencyMapping.ExplicitCompileToCompile
          case Some(_) => SbtCompileDependencyMapping.ExcludedNoCompileToCompile
          case None if !normalized.contains(";") && SimpleName.matches(normalized) =>
            SbtCompileDependencyMapping.ExcludedNoCompileToCompile
          case None => SbtCompileDependencyMapping.UnsupportedOrAmbiguous

  def parse(value: String): Either[String, SbtCompileDependencyMapping] =
    values.find(_.toString == value).toRight(s"invalid Compile dependency mapping status: $value")

final case class SbtInternalDependencyEdge(
    project: String,
    configuration: Option[String] = None,
    sameBuild: Boolean = true
)

final case class SbtInternalDependencyNode(
    project: String,
    dependencies: List[SbtInternalDependencyEdge],
    aggregates: List[String] = Nil
)

final case class SbtAdmittedInternalDependency(
    project: String,
    role: SbtInternalDependencyRole,
    mapping: SbtCompileDependencyMapping
)

final case class SbtExcludedInternalDependency(
    project: String,
    role: SbtInternalDependencyRole,
    mapping: SbtCompileDependencyMapping
)

final case class SbtInternalDependencyCycle(from: String, to: String)

final case class SbtInternalDependencyGraphResult(
    admitted: List[SbtAdmittedInternalDependency],
    excluded: List[SbtExcludedInternalDependency],
    cycles: List[SbtInternalDependencyCycle]
)

object SbtInternalDependencyGraph:
  val MaxDependencyProjects = 128

  def resolve(
      selected: String,
      nodes: Map[String, SbtInternalDependencyNode]
  ): SbtInternalDependencyGraphResult =
    val admitted = mutable.ListBuffer.empty[SbtAdmittedInternalDependency]
    val excluded = mutable.ListBuffer.empty[SbtExcludedInternalDependency]
    val cycles = mutable.ListBuffer.empty[SbtInternalDependencyCycle]
    val visited = mutable.LinkedHashSet(selected)
    val active = mutable.LinkedHashSet(selected)
    val selectedEdges = nodes.get(selected).toList.flatMap(_.dependencies)
    val direct = selectedEdges.iterator
      .filter(edge => edge.sameBuild && SbtCompileDependencyMapping.classify(edge.configuration).admitted)
      .map(_.project)
      .toSet

    def visit(from: String, edge: SbtInternalDependencyEdge): Unit =
      val role =
        if from == selected || direct.contains(edge.project) then SbtInternalDependencyRole.Direct
        else SbtInternalDependencyRole.Transitive
      val mapping =
        if edge.sameBuild then SbtCompileDependencyMapping.classify(edge.configuration)
        else SbtCompileDependencyMapping.UnsupportedOrAmbiguous
      if !mapping.admitted then
        excluded += SbtExcludedInternalDependency(edge.project, role, mapping)
      else if active.contains(edge.project) then
        cycles += SbtInternalDependencyCycle(from, edge.project)
      else if !visited.contains(edge.project) && admitted.size < MaxDependencyProjects then
        visited += edge.project
        active += edge.project
        admitted += SbtAdmittedInternalDependency(edge.project, role, mapping)
        nodes.get(edge.project).toList.flatMap(_.dependencies).foreach(visit(edge.project, _))
        active -= edge.project

    selectedEdges.foreach(visit(selected, _))
    SbtInternalDependencyGraphResult(admitted.toList, excluded.toList, cycles.toList)

enum SbtInternalDependencyExclusionReason:
  case NoCompileToCompileMapping
  case UnsupportedOrAmbiguousMapping
  case ForeignBuildProjectRef
  case ScalaAxisMismatch
  case ProjectSettingsUnavailable
  case ClassDirectorySettingUnavailable
  case CycleToSelectedOrActiveProject
  case DependencyBoundExceeded

object SbtInternalDependencyExclusionReason:
  def parse(value: String): Either[String, SbtInternalDependencyExclusionReason] =
    values.find(_.toString == value).toRight(s"invalid internal dependency exclusion reason: $value")

final case class SbtInternalDependencyReceipt(
    projectRef: String,
    project: SbtProjectId,
    role: SbtInternalDependencyRole,
    compileMapping: SbtCompileDependencyMapping,
    requestedScalaVersion: Option[SbtScalaVersion],
    effectiveScalaVersion: SbtScalaVersion,
    configuration: SbtClasspathConfiguration,
    classDirectory: java.nio.file.Path,
    classDirectoryPresent: Boolean,
    compileAnalysisFile: Option[java.nio.file.Path] = None,
    sourceLayout: Option[SbtInternalSourceLayoutReceipt] = None
)

final case class SbtInternalSourceLayoutReceipt(
    sourceDirectories: List[java.nio.file.Path],
    unmanagedSourceDirectories: List[java.nio.file.Path],
    managedSourceDirectories: List[java.nio.file.Path],
    sourceGeneratorCount: Int
)

object SbtInternalSourceLayoutReceipt:
  val MaxSourceDirectories = 128
  val MaxSourceGenerators = 128

final case class SbtInternalDependencyExclusion(
    projectRef: String,
    project: SbtProjectId,
    role: SbtInternalDependencyRole,
    compileMapping: SbtCompileDependencyMapping,
    reason: SbtInternalDependencyExclusionReason,
    effectiveScalaVersion: Option[SbtScalaVersion]
)
