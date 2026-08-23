package semantic.harness.sbt_runner

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SbtClasspathMaterializerSuite extends munit.FunSuite:
  test("materializer persists only transient converter JARs for classpath and receipt consumers"):
    val root = Files.createTempDirectory("sbt-classpath-materializer-")
    val transientRoot = Files.createDirectory(root.resolve("transient"))
    val stableRoot = Files.createDirectory(root.resolve("stable"))
    val classes = Files.createDirectory(root.resolve("classes"))
    val transientJar = archive(transientRoot.resolve("sha256-cas-object"))
    val expectedBytes = Files.readAllBytes(transientJar).toList
    val externalJar = Files.createFile(root.resolve("ordinary.jar"))
    val materializer = SbtClasspathMaterializer.local(stableRoot)
    val projectId = project("app")
    try
      val classpath = SbtClasspathResult(
        projectId,
        SbtClasspathConfiguration.Compile,
        List(
          SbtClasspathEntry(classes, SbtClasspathEntryKind.Directory),
          SbtClasspathEntry(transientJar, SbtClasspathEntryKind.Jar),
          SbtClasspathEntry(externalJar, SbtClasspathEntryKind.Jar)
        )
      )
      val receipt = SbtTastyCompileReceipt(
        projectId,
        SbtClasspathConfiguration.Compile,
        SbtTastyCompileStatus.Succeeded,
        Some("3.7.4"),
        Some(classes),
        sourceIncluded = true,
        targetJavaContext = None,
        dependencyClasspath = List(transientJar, externalJar)
      )

      val persistedClasspath = materializer.materialize(classpath, transientRoot)
      val persistedReceipt = materializer.materialize(receipt, transientRoot)

      assert(persistedClasspath.isRight, clue(persistedClasspath))
      assert(persistedReceipt.isRight, clue(persistedReceipt))
      val classpathValue = persistedClasspath.fold(message => fail(message), identity)
      val receiptValue = persistedReceipt.fold(message => fail(message), identity)
      val copied = classpathValue.entries(1).path
      assert(copied.startsWith(stableRoot.toAbsolutePath.normalize()), clue(copied))
      assert(copied.getFileName.toString.endsWith(".jar"), clue(copied))
      assertEquals(classpathValue.entries(0).path, classes)
      assertEquals(classpathValue.entries(2).path, externalJar)
      assertEquals(receiptValue.dependencyClasspath, List(copied, externalJar))

      Files.delete(transientJar)
      assert(Files.isReadable(copied))
      assertEquals(Files.readAllBytes(copied).toList, expectedBytes)
    finally deleteRecursively(root)

  test("materializer rejects a transient symbolic-link entry"):
    val root = Files.createTempDirectory("sbt-classpath-materializer-link-")
    val transientRoot = Files.createDirectory(root.resolve("transient"))
    val stableRoot = Files.createDirectory(root.resolve("stable"))
    val outside = archive(root.resolve("outside"))
    val link = transientRoot.resolve("linked")
    Files.createSymbolicLink(link, outside)
    val value = SbtClasspathResult(
      project("app"),
      SbtClasspathConfiguration.Compile,
      List(SbtClasspathEntry(link, SbtClasspathEntryKind.Jar))
    )
    try
      assert(SbtClasspathMaterializer.local(stableRoot).materialize(value, transientRoot).isLeft)
    finally deleteRecursively(root)

  private def archive(path: Path): Path =
    val stream = ZipOutputStream(Files.newOutputStream(path))
    try
      stream.putNextEntry(ZipEntry("example/Main.class"))
      stream.write(Array[Byte](1, 2, 3))
      stream.closeEntry()
    finally stream.close()
    path

  private def project(value: String): SbtProjectId =
    SbtProjectId.parse(value).fold(message => fail(message), identity)

  private def deleteRecursively(root: Path): Unit =
    if Files.exists(root) then
      val paths = Files.walk(root)
      try paths.sorted(Comparator.reverseOrder()).forEach(path => Files.deleteIfExists(path))
      finally paths.close()
