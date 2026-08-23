package semantic.harness.sbt_runner

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SbtClasspathProtocolSuite extends munit.FunSuite:
  test("protocol round-trip preserves order, spaces, Unicode, brackets, parentheses, and commas"):
    val root = Files.createTempDirectory("task069 protocol λ")
    val directory = Files.createDirectory(root.resolve("classes [main], (λ)"))
    val jar = Files.createFile(root.resolve("library (test), [λ].JAR"))
    try
      val expected = SbtClasspathResult(
        project("app-2"),
        SbtClasspathConfiguration.Test,
        List(
          SbtClasspathEntry(directory, SbtClasspathEntryKind.Directory),
          SbtClasspathEntry(jar, SbtClasspathEntryKind.Jar)
        )
      )
      val request = SbtClasspathRequest(root, expected.project, expected.configuration)

      assertEquals(SbtClasspathProtocol.parse(SbtClasspathProtocol.render(expected), request), Right(expected))
    finally
      Files.deleteIfExists(jar)
      Files.deleteIfExists(directory)
      Files.deleteIfExists(root)

  test("protocol removes normalized duplicates in first-seen order without sorting or hashing"):
    val root = Files.createTempDirectory("task069-protocol-order")
    val first = Files.createDirectory(root.resolve("z-classes"))
    val second = Files.createDirectory(root.resolve("a-classes"))
    try
      val request = SbtClasspathRequest(root, project("app-2"), SbtClasspathConfiguration.Compile)
      val content = protocol(
        request,
        List(
          SbtClasspathEntry(first, SbtClasspathEntryKind.Directory),
          SbtClasspathEntry(second, SbtClasspathEntryKind.Directory),
          SbtClasspathEntry(first.resolve("."), SbtClasspathEntryKind.Directory)
        )
      )

      assertEquals(
        SbtClasspathProtocol.parse(content, request).map(_.entries.map(_.path)),
        Right(List(first.toAbsolutePath.normalize(), second.toAbsolutePath.normalize()))
      )
    finally
      Files.deleteIfExists(second)
      Files.deleteIfExists(first)
      Files.deleteIfExists(root)

  test("protocol rejects marker, project, configuration, record, kind, and empty-entry failures"):
    val root = Files.createTempDirectory("task069-protocol-invalid")
    val directory = Files.createDirectory(root.resolve("classes"))
    val request = SbtClasspathRequest(root, project("app-2"), SbtClasspathConfiguration.Compile)
    try
      val valid = protocol(
        request,
        List(SbtClasspathEntry(directory, SbtClasspathEntryKind.Directory))
      )
      assert(SbtClasspathProtocol.parse(valid.replace(SbtClasspathProtocol.Format, "wrong.v1"), request).isLeft)
      assert(SbtClasspathProtocol.parse(valid.replace(encoded("app-2"), encoded("api")), request).isLeft)
      assert(SbtClasspathProtocol.parse(valid.replace("\tCompile\n", "\tTest\n"), request).isLeft)
      assert(SbtClasspathProtocol.parse(valid.replace("entry\tDirectory", "broken\tDirectory"), request).isLeft)
      assert(SbtClasspathProtocol.parse(valid.replace("entry\tDirectory", "entry\tZip"), request).isLeft)
      assert(SbtClasspathProtocol.parse(valid.linesIterator.take(3).mkString("", "\n", "\n"), request).isLeft)
    finally
      Files.deleteIfExists(directory)
      Files.deleteIfExists(root)

  test("protocol rejects missing and unsupported entries rather than dropping them"):
    val root = Files.createTempDirectory("task069-protocol-entry-validation")
    val unsupported = Files.createFile(root.resolve("classes.txt"))
    val request = SbtClasspathRequest(root, project("app-2"), SbtClasspathConfiguration.Compile)
    try
      val missing = protocol(
        request,
        List(SbtClasspathEntry(root.resolve("missing"), SbtClasspathEntryKind.Directory))
      )
      val wrongKind = protocol(
        request,
        List(SbtClasspathEntry(unsupported, SbtClasspathEntryKind.Directory))
      )
      assert(SbtClasspathProtocol.parse(missing, request).isLeft)
      assert(SbtClasspathProtocol.parse(wrongKind, request).isLeft)
    finally
      Files.deleteIfExists(unsupported)
      Files.deleteIfExists(root)

  test("protocol accepts a readable extensionless JAR materialized from an sbt virtual reference"):
    val root = Files.createTempDirectory("sbt-classpath-protocol-cas-jar")
    val archive = root.resolve("sha256-content-address")
    val stream = ZipOutputStream(Files.newOutputStream(archive))
    try
      stream.putNextEntry(ZipEntry("example/Main.class"))
      stream.write(Array[Byte](1, 2, 3))
      stream.closeEntry()
    finally stream.close()
    val request = SbtClasspathRequest(root, project("app"), SbtClasspathConfiguration.Compile)
    try
      val content = protocol(
        request,
        List(SbtClasspathEntry(archive, SbtClasspathEntryKind.Jar))
      )
      assertEquals(
        SbtClasspathProtocol.parse(content, request).map(_.entries.map(_.path)),
        Right(List(archive.toAbsolutePath.normalize()))
      )
    finally
      Files.deleteIfExists(archive)
      Files.deleteIfExists(root)

  test("protocol rejects malformed Base64 without interpreting log text"):
    val root = Files.createTempDirectory("task069-protocol-base64")
    val request = SbtClasspathRequest(root, project("app-2"), SbtClasspathConfiguration.Compile)
    try
      val content =
        s"""${SbtClasspathProtocol.Format}
           |project	%%%
           |configuration	Compile
           |entry	Directory	%%%
           |""".stripMargin
      assert(SbtClasspathProtocol.parse(content, request).isLeft)
    finally Files.deleteIfExists(root)

  test("explicit Java classpath protocol uses v2 and requires the exact opaque context token"):
    val root = Files.createTempDirectory("task166-protocol-v2")
    val directory = Files.createDirectory(root.resolve("classes"))
    val selected = SbtClasspathCacheTestSupport.selectedJava(root.resolve("jdk"))
    val request = SbtClasspathRequest(
      root,
      project("app-2"),
      SbtClasspathConfiguration.Compile,
      Some(selected)
    )
    val expected = SbtClasspathResult(
      request.project,
      request.configuration,
      List(SbtClasspathEntry(directory, SbtClasspathEntryKind.Directory)),
      javaContextToken = Some(SbtJavaContext.token(selected))
    )
    try
      val rendered = SbtClasspathProtocol.render(expected)
      assert(rendered.startsWith(SbtClasspathProtocol.FormatV2 + "\n"), clue(rendered))
      assertEquals(SbtClasspathProtocol.parse(rendered, request), Right(expected))
      assert(
        SbtClasspathProtocol
          .parse(rendered.replace(SbtJavaContext.token(selected), "f" * 64), request)
          .isLeft
      )
      assert(SbtClasspathProtocol.parse(rendered, request.copy(targetJava = None)).isLeft)
    finally
      Files.deleteIfExists(directory)
      Files.deleteIfExists(root)

  private def protocol(
      request: SbtClasspathRequest,
      entries: List[SbtClasspathEntry]
  ): String =
    SbtClasspathProtocol.render(
      SbtClasspathResult(request.project, request.configuration, entries)
    )

  private def project(value: String): SbtProjectId =
    SbtProjectId.parse(value).fold(message => fail(message), identity)

  private def encoded(value: String): String =
    Base64.getEncoder.encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))
