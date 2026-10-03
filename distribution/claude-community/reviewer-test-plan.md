# Reviewer test plan

> **Superseded:** do not use this thin-plugin plan for resubmission. The
> reviewer rejected its post-install executable download. The locally
> qualified embedded-MCPB candidate also must not be published: it exceeds
> the current 50 MiB GitHub-archive and 5 MiB per-file Directory limits.

This plan is prepared for human review of the thin candidate published at
`DmytroMitin/semantic-scala-claude-plugin:main` and qualified at commit
`05d4f0de35a02916504bf29156bc42270cf77a23`. One review submission later entered In review and is now in Needs changes,
but this plan is not evidence of an accepted or public directory listing. The
portal security scan sent qualified version `05d4f0d` to content-policy review
because shipped code could not be cleared automatically. Versions history also
retains the earlier `fc3a6c8` detection event.

1. Confirm public `main` still resolves to the qualified commit, or record any
   newer reviewed commit, and that the reviewed tree contains the deterministic
   thin candidate: 6 files, 40,205 unpacked bytes, a 21,188-byte largest file,
   and content SHA-256
   `6496b2e82e1aa2bb698e3effade8e35f120899046381778f32b39c9032e84473`.
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
passing package-size validation alone is not directory acceptance. The Task-254
cold proof retained one client input rejection before semantic execution; the
corrected cold call and the single warm call both succeeded. A portal reviewer
should evaluate the published source itself and need not reproduce that client
orchestration error.
