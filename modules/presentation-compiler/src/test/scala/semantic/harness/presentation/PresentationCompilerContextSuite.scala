package semantic.harness.presentation

import java.nio.file.Files
import java.nio.file.Path

class PresentationCompilerContextSuite extends munit.FunSuite:
  test("narrow runtime accepts an existing workspace directory"):
    val result = PresentationCompilerContext.validate(
      PresentationCompilerContext(workspace = Some(Path.of(".")))
    )

    assert(result.exists(_.workspace.exists(_.isAbsolute)))
    assert(result.exists(_.classpath == PresentationCompilerClasspath.NarrowRuntime))

  test("explicit classpath normalizes relative entries and removes duplicates in first-seen order"):
    val first = Path.of("modules/presentation-compiler/target")
    val second = Path.of("modules/cli/target")
    val result = PresentationCompilerContext.validate(
      PresentationCompilerContext.explicit(List(first, second, first))
    )

    val entries = result.fold(message => fail(message), _.classpath match
      case PresentationCompilerClasspath.Explicit(values) => values
      case PresentationCompilerClasspath.NarrowRuntime     => fail("expected explicit classpath")
    )
    assertEquals(entries, List(first.toAbsolutePath.normalize(), second.toAbsolutePath.normalize()))

  test("explicit classpath rejects an empty list"):
    val result = PresentationCompilerContext.validate(PresentationCompilerContext.explicit(Nil))

    assertEquals(result, Left("Explicit classpath must contain at least one entry"))

  test("explicit classpath accepts compiled directories and JAR files"):
    val directory = Files.createTempDirectory("task067-classes")
    val jar = Files.createTempFile("task067-library", ".JAR")
    try
      val result = PresentationCompilerContext.validate(
        PresentationCompilerContext.explicit(List(directory, jar))
      )
      assert(result.isRight)
    finally
      Files.deleteIfExists(jar)
      Files.deleteIfExists(directory)

  test("explicit classpath rejects missing and unsupported regular files"):
    val missing = Path.of("target/task067-missing-entry")
    val unsupported = Files.createTempFile("task067-classpath", ".txt")
    try
      assert(
        PresentationCompilerContext
          .validate(PresentationCompilerContext.explicit(List(missing)))
          .left
          .exists(_.contains("does not exist"))
      )
      assert(
        PresentationCompilerContext
          .validate(PresentationCompilerContext.explicit(List(unsupported)))
          .left
          .exists(_.contains("not a JAR file or directory"))
      )
    finally Files.deleteIfExists(unsupported)

  test("workspace must exist and be a directory"):
    val missing = Path.of("target/task067-missing-workspace")
    val file = Files.createTempFile("task067-workspace", ".txt")
    try
      assert(
        PresentationCompilerContext
          .validate(PresentationCompilerContext(workspace = Some(missing)))
          .left
          .exists(_.contains("Workspace does not exist"))
      )
      assert(
        PresentationCompilerContext
          .validate(PresentationCompilerContext(workspace = Some(file)))
          .left
          .exists(_.contains("Workspace is not a directory"))
      )
    finally Files.deleteIfExists(file)
