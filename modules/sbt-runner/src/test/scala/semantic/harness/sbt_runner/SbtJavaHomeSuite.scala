package semantic.harness.sbt_runner

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.EnumSet
import java.util.HashMap
import scala.concurrent.duration.DurationInt

class SbtJavaHomeSuite extends munit.FunSuite:
  test("absolute Java home is canonicalized and produces deterministic private identity"):
    withFakeHome("task166-java-home", "fake-java 25") { home =>
      val first = validator.validate(home)
      val second = validator.validate(home.resolve("."))

      assertEquals(first.map(_.canonicalHome), Right(home.toRealPath()))
      assertEquals(second.map(_.canonicalHome), Right(home.toRealPath()))
      assertEquals(first.map(_.sbtJavaHomeDigest), second.map(_.sbtJavaHomeDigest))
      assertEquals(
        first.map(_.sbtJavaRuntimeFingerprint),
        second.map(_.sbtJavaRuntimeFingerprint)
      )
      assert(first.exists(_.sbtJavaHomeDigest.matches("[0-9a-f]{64}")))
      assert(first.exists(_.sbtJavaRuntimeFingerprint.matches("[0-9a-f]{64}")))
    }

  test("relative missing and non-directory Java homes fail before probing"):
    val regular = Files.createTempFile("task166-java-home-file", ".txt")
    try
      val relative = validator.validate(Path.of("relative-java-home"))
      val missing = validator.validate(regular.resolveSibling("missing-java-home"))
      val nonDirectory = validator.validate(regular)

      assert(relative.left.exists(_.isInstanceOf[SbtJavaHomeFailure.Validation]))
      assert(missing.left.exists(_.isInstanceOf[SbtJavaHomeFailure.Validation]))
      assert(nonDirectory.left.exists(_.isInstanceOf[SbtJavaHomeFailure.Validation]))
      List(relative, missing, nonDirectory).foreach(result =>
        assert(!result.swap.exists(failure =>
          SbtJavaHomeFailure.message(failure).contains(regular.toString)
        ))
      )
    finally Files.deleteIfExists(regular)

  test("top-level SDKMAN-style symlink resolves to the canonical Java home"):
    withFakeHome("task166-java-real", "fake-java 25") { home =>
      val alias = home.resolveSibling(s"${home.getFileName}-current")
      try
        Files.createSymbolicLink(alias, home)
        assertEquals(validator.validate(alias).map(_.canonicalHome), Right(home.toRealPath()))
      finally Files.deleteIfExists(alias)
    }

  test("launcher must be executable and its real path must remain inside the home"):
    withFakeHome("task166-java-contained", "fake-java 25") { home =>
      val launcher = home.resolve("bin/java")
      makeNonExecutable(launcher)
      assert(validator.validate(home).left.exists {
        case SbtJavaHomeFailure.Validation(message) => message.contains("executable")
        case _                                      => false
      })
    }

    val home = Files.createTempDirectory("task166-java-escape")
    val bin = Files.createDirectory(home.resolve("bin"))
    val outside = Files.createTempFile("task166-java-outside", ".sh")
    try
      writeExecutable(outside, "#!/bin/sh\nprintf 'fake-java 25\\n' >&2\n")
      Files.createSymbolicLink(bin.resolve("java"), outside)
      assert(validator.validate(home).left.exists {
        case SbtJavaHomeFailure.Validation(message) => message.contains("contained")
        case _                                      => false
      })
    finally
      Files.deleteIfExists(bin.resolve("java"))
      Files.deleteIfExists(bin)
      Files.deleteIfExists(home)
      Files.deleteIfExists(outside)

  test("fixed Java version probe failure and timeout remain typed and path-private"):
    withFakeHome("task166-java-failure", "probe-secret", exitCode = 9) { home =>
      val result = validator.validate(home)
      assert(result.left.exists(_.isInstanceOf[SbtJavaHomeFailure.Probe]))
      assert(!result.swap.exists(failure =>
        SbtJavaHomeFailure.message(failure).contains("probe-secret")
      ))
      assert(!result.swap.exists(failure =>
        SbtJavaHomeFailure.message(failure).contains(home.toString)
      ))
    }

    withFakeHome("task166-java-timeout", "late", delaySeconds = 2) { home =>
      val started = System.nanoTime()
      val result = SbtJavaHomeValidator.bounded(100.millis).validate(home)
      val elapsedMillis = (System.nanoTime() - started) / 1000000L
      assert(result.left.exists {
        case SbtJavaHomeFailure.Probe(message) => message.contains("timed out")
        case _                                 => false
      })
      assert(elapsedMillis < 1000L, clue(elapsedMillis))
    }

  test("child overlay sets JAVA_HOME and prefixes PATH while preserving unrelated state"):
    withFakeHome("task166-java-environment", "fake-java 25") { home =>
      val selected = validator.validate(home).fold(failure => fail(SbtJavaHomeFailure.message(failure)), identity)
      val environment = HashMap[String, String]()
      environment.put("PATH", "/usr/local/bin:/usr/bin")
      environment.put("UNRELATED_TOKEN", "retained")
      val parentJavaHome = sys.env.get("JAVA_HOME")
      val parentPath = sys.env.get("PATH")

      SbtJavaEnvironment.configure(environment, selected)

      assertEquals(environment.get("JAVA_HOME"), selected.canonicalHome.toString)
      assertEquals(
        environment.get("PATH"),
        s"${selected.binDirectory}:/usr/local/bin:/usr/bin"
      )
      assertEquals(environment.get("UNRELATED_TOKEN"), "retained")
      assertEquals(sys.env.get("JAVA_HOME"), parentJavaHome)
      assertEquals(sys.env.get("PATH"), parentPath)
    }

  test("child overlay uses only the selected bin when inherited PATH is absent or empty"):
    withFakeHome("task166-java-empty-path", "fake-java 25") { home =>
      val selected = validator.validate(home).fold(failure => fail(SbtJavaHomeFailure.message(failure)), identity)
      val absent = HashMap[String, String]()
      val empty = HashMap[String, String]()
      empty.put("PATH", "")

      SbtJavaEnvironment.configure(absent, selected)
      SbtJavaEnvironment.configure(empty, selected)

      assertEquals(absent.get("PATH"), selected.binDirectory.toString)
      assertEquals(empty.get("PATH"), selected.binDirectory.toString)
    }

  private val validator = SbtJavaHomeValidator.bounded(2.seconds)

  private def withFakeHome(
      prefix: String,
      versionText: String,
      exitCode: Int = 0,
      delaySeconds: Int = 0
  )(body: Path => Unit): Unit =
    val home = Files.createTempDirectory(prefix)
    val bin = Files.createDirectory(home.resolve("bin"))
    val launcher = bin.resolve("java")
    val delay = if delaySeconds == 0 then "" else s"sleep $delaySeconds\n"
    writeExecutable(
      launcher,
      s"#!/bin/sh\n${delay}printf '%s\\n' '$versionText' >&2\nexit $exitCode\n"
    )
    Files.writeString(home.resolve("release"), "JAVA_VERSION=\"25\"\n", StandardCharsets.UTF_8)
    try body(home)
    finally
      Files.deleteIfExists(home.resolve("release"))
      Files.deleteIfExists(launcher)
      Files.deleteIfExists(bin)
      Files.deleteIfExists(home)

  private def writeExecutable(path: Path, contents: String): Unit =
    Files.writeString(path, contents, StandardCharsets.UTF_8)
    Files.setPosixFilePermissions(
      path,
      EnumSet.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE
      )
    )

  private def makeNonExecutable(path: Path): Unit =
    Files.setPosixFilePermissions(
      path,
      EnumSet.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE
      )
    )
