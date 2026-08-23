package semantic.harness.sbt_runner

private[sbt_runner] object SbtInjectedClasspathMaterialization:
  val Settings: String =
    """def semanticScalaInternalClasspathFile(
      |    value: Any,
      |    converter: xsbti.FileConverter
      |): java.io.File = {
      |  value match {
      |    case attributed: Attributed[_] =>
      |      semanticScalaInternalClasspathFile(attributed.data, converter)
      |    case file: java.io.File =>
      |      file.getCanonicalFile
      |    case reference: xsbti.VirtualFileRef =>
      |      converter.toPath(reference).toFile.getCanonicalFile
      |    case _ =>
      |      sys.error("unsupported sbt classpath entry representation")
      |  }
      |}
      |""".stripMargin
