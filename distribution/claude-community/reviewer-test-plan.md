# Reviewer test plan

This plan is prepared for human review of the verified public source. It is not
evidence of a submitted or accepted `claude-community` listing.

1. Install `semantic-scala` `0.1.0-alpha.3` on Linux x86_64 with compatible GNU
   libc and system zlib from the reviewed `claude-community` entry. Before the
   listing exists, reviewers can reproduce the source gate with a disposable
   marketplace `github` source for
   `DmytroMitin/semantic-scala-claude-plugin`, pinned to commit
   `c05aac9f38e7755a51f511078ff555a587f97ccf`, using the repository root.
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
