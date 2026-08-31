# Changelog

Notable public changes will be recorded in this file.

The project is an experimental alpha. Source availability alone does not imply
a supported binary, package-manager, marketplace, or client-installation
release.

## Unreleased

- Add an explicit target-aware point-evidence v5 opt-in that can use only
  already-present same-axis internal Compile dependency outputs from a bounded
  settings-only receipt. The default target route remains v4, the no-target
  route remains v2, missing outputs are never built, and MCP remains exactly
  eight tools.
- Resume mutable `main` development at `0.1.0-alpha.3-SNAPSHOT` after the
  alpha-2 release. This does not publish or promise an alpha-3 artifact,
  channel, tag, or GitHub Release.

## 0.1.0-alpha.2

- Publish the exact eight application implementation modules under final group
  `com.github.dmytromitin` on Maven Central with POM, main, sources,
  documentation, signature, and checksum files; retain explicit rejection of
  the provisional group and the reviewed Apache-2.0 path for JNA 5.14.0.
- Add and independently qualify the exact-version project-owned Coursier URL
  route under JDK 21 for CLI and generic stdio MCP installation, runtime,
  update, and uninstall.
- Publish lightweight Git tag and non-draft GitHub prerelease
  `0.1.0-alpha.2` at the exact source identity that reproduced all 32 public
  Maven Central primary files byte-for-byte. The Release contains normal
  GitHub-generated source archives and zero uploaded project assets.

## 0.1.0-alpha.1

This is the first experimental, source-only alpha. It does not publish
project-built binaries, package-manager coordinates, containers, marketplace
artifacts, or a generated Agent Plugin bundle.

- Add structured compile, test, and compiler-diagnostic commands.
- Add bounded SemanticDB discovery, coverage, symbol, usage, and source lookup
  evidence.
- Add Presentation Compiler point/type evidence, dynamic/static symbol
  reconciliation, and coherent `point-evidence` composition.
- Add conservative syntax-first effect summaries.
- Provide exactly eight CLI-backed MCP tools while keeping additional
  inspection and utility commands CLI-only.
- Provide the canonical semantic-scala skill, thin client wrappers, and a
  deterministic, relocatable Agent Plugin package source/validator without
  claiming client adoption or publishing the generated bundle.
- Document the JDK 21 source-build and first-use route, the capability-specific
  Scala 2/Scala 3 boundary, and the clean-history Apache-2.0 public source
  publication.
