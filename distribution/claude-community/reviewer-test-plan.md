# Reviewer test plan

This plan applies to the skills-only source at public commit
`f28fe7a61ff449c9775d554d5bc9a11e2a9cfda1`. It replaces the rejected
thin-runtime plan. The existing Directory submission has not been resubmitted
and still resolves `05d4f0d`.

1. Confirm the reviewed repository root resolves to commit `f28fe7a6`, tree
   `62d8689c830dbf6460115c76fbd1a2df48fb775b`, and plugin version
   `0.1.0-alpha.3.2`.
2. Confirm the tree has exactly six files, 41,500 bytes, content SHA-256
   `141362f7893bb555f770cde0091737695d7045052363ecf8ec836bd5edd9d40b`,
   and a 21,188-byte largest file.
3. Confirm it declares one skill and zero MCP servers, and contains no runtime,
   executable, bootstrap, download, hook, LSP server, installer, updater,
   `allowed-tools`, or automatic network behavior.
4. Install exact `semantic-scala` CLI `0.1.0-alpha.3` independently. Confirm the
   plugin does not install it and uses only Claude Code's ordinary shell
   permission flow.
5. In a disposable Scala fixture, ask the skill for an `effect-summary`. Confirm
   the CLI result is attributed as CLI evidence and no MCP result is claimed.
6. Ask for a build-backed operation. Confirm Claude requests the ordinary shell
   permission appropriate to executing build/project/plugin/test code and does
   not imply that plugin installation granted that permission.
7. Remove the CLI from the disposable path. Confirm the skill reports the
   missing prerequisite and neither installs a replacement nor falls back to
   `semantic-scala-mcp`.
8. On a surface without local CLI access, confirm the skill does not claim local
   semantic evidence.

The public GitHub archive at the exact commit is 18,708 bytes, has SHA-256
`6c507aa64ae6effe63552c62838685156f24919e62c6aaacfc3a703fcdfa30a5`,
and passes current source limits. Those facts and the successful local/client
qualification do not substitute for portal validation or reviewer approval.
