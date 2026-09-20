# Reviewer test plan

This plan is prepared for a future review after an installable public source is
published and independently validated. It must not be represented as runnable
from the current public repository path while the distribution blocker remains.

1. Install `semantic-scala` `0.1.0-alpha.3` from the reviewed
   `claude-community` entry on Linux x86_64 with compatible GNU libc and system
   zlib.
2. Open a disposable directory containing one Scala file:

   ```scala
   object Fixture:
     def value: Option[Int] = Some(1)
   ```

3. Invoke `/semantic-scala:semantic-scala` and request a read-only effect
   summary for `Fixture.scala`.
4. Confirm the plugin-local stdio MCP server connects and invokes
   `semantic_effect_summary` exactly once.
5. Confirm adapter `ok: true`, schema
   `semantic-scala.effect-summary.v1`, method `value`, and declared return type
   `Option[Int]`.
6. Confirm the fixture tree is unchanged, then uninstall the plugin.

The reviewer need not run compilation or tests for this read-only acceptance
case. Build-backed tools can execute project build or test code and should be
used only with explicit approval in a disposable project.
