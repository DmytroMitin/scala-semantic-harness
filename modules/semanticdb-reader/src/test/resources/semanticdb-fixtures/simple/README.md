# Simple SemanticDB Fixture

This fixture is a tiny checked-in SemanticDB payload for the reader tests.
Normal `sbt test` reads `Main.scala.semanticdb` directly and does not
regenerate it.

The source is:

```scala
package example

object Main:
  val answer: Int = 42
  def add(x: Int, y: Int): Int = x + y
```

The `.semanticdb` file contains a single `TextDocument` with URI `Main.scala`,
one canonical symbol `example/Main.`, and one definition occurrence with a
range. It is intentionally minimal so tests stay deterministic and avoid
dynamic SemanticDB generation, directory discovery, Metals, BSP, and TASTy.

Real generated SemanticDB commonly lives under paths such as
`META-INF/semanticdb/.../*.semanticdb`. The reader tests use explicit fixture
file paths only.
