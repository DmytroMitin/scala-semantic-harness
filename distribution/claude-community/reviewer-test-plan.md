# Reviewer test plan

This plan is prepared for later human review of the thin candidate after it is
published and requalified from an exact public source. It is not evidence of a
submitted or accepted directory listing.

1. Confirm the reviewed public branch, tag, or commit contains the deterministic
   thin candidate: 6 files, 39,938 unpacked bytes, a 21,188-byte largest file,
   and content SHA-256
   `ab0d5feaed7b4b9e6313e61116b5b3fc86d2cc5bc114b127a6290de5e29e8b8f`.
2. On Linux x86_64 with compatible GNU libc, system zlib, Python 3.11 or newer,
   and no host Java requirement, install `semantic-scala` `0.1.0-alpha.3` from
   that exact reviewed source into a disposable Claude configuration.
3. Confirm the first MCP start downloads only the fixed Alpha-3 MCPB, requires
   exactly 285,603,142 bytes, verifies SHA-256
   `f5e5dbeb8ebfb8d0495dd3201bce7319ac72e19f6110ecec354843a1f978583d`,
   installs an owner-only cache, and retains no MCPB or partial directory.
4. Open a disposable directory containing one Scala file:

   ```scala
   object Fixture:
     def value: Option[Int] = Some(1)
   ```

5. Invoke `/semantic-scala:semantic-scala` and request a read-only effect
   summary for `Fixture.scala`.
6. Confirm the plugin-local stdio MCP server exposes exactly eight tools and
   invokes
   `semantic_effect_summary` exactly once.
7. Confirm adapter `ok: true`, schema
   `semantic-scala.effect-summary.v1`, method `value`, and declared return type
   `Option[Int]`.
8. Start a second Claude session with the same cache and the fixed download URL
   made unreachable. Confirm the MCP server connects, the same read-only call
   succeeds once, no download path is required, and the cache marker is
   unchanged.
9. Confirm the fixture tree is unchanged, uninstall the plugin, and remove the
   disposable cache.

The reviewer need not run compilation or tests for this read-only acceptance
case. Build-backed tools can execute project build or test code and should be
used only with explicit approval in a disposable project. The reviewer should
also scrutinize the fixed-download/execute bootstrap as a supply-chain boundary;
passing package-size validation alone is not directory acceptance.
