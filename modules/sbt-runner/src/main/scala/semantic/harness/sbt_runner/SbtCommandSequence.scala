package semantic.harness.sbt_runner

private[sbt_runner] enum SbtFixedTask(
    val selectedTask: String,
    val rootTask: Option[String]
):
  case Compile extends SbtFixedTask("Compile / compile", Some("compile"))
  case Test extends SbtFixedTask("Test / test", Some("test"))
  case StructuredTest
      extends SbtFixedTask(SbtTestResultSource.Task, Some(SbtTestResultSource.Task))
  case CompileClasspath
      extends SbtFixedTask("semanticScalaInternalExportCompileClasspath", None)
  case TestClasspath
      extends SbtFixedTask("semanticScalaInternalExportTestClasspath", None)
  case TastyCompileReceipt
      extends SbtFixedTask("semanticScalaInternalTastyCompileReceipt", None)

private[sbt_runner] object SbtCommandSequence:
  def build(project: Option[SbtProjectId], task: SbtFixedTask): String =
    project match
      case Some(projectId) => selected(projectId, task)
      case None =>
        task.rootTask.getOrElse(
          throw IllegalArgumentException("selected sbt task requires a project")
        )

  def selected(project: SbtProjectId, task: SbtFixedTask): String =
    s"project ${project.value}; ${task.selectedTask}"
