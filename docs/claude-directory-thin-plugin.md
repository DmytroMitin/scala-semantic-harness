# Claude directory thin plugin

> **Reviewer-rejected for Claude Directory publication.** The existing
> submission is in Needs changes because this plugin downloads and executes
> server code after installation. Retain this page only as historical evidence;
> do not resubmit the thin architecture unchanged. The locally qualified,
> no-download alternative is documented in
> [claude-directory-embedded-plugin.md](claude-directory-embedded-plugin.md),
> but it is also blocked by current Directory size limits.

The repository includes a deterministic thin Claude Code plugin candidate for
`semantic-scala` `0.1.0-alpha.3`. The exact six-file wrapper is published at the
root of `DmytroMitin/semantic-scala-claude-plugin:main` and qualified at commit
`05d4f0de35a02916504bf29156bc42270cf77a23`. It has been submitted for
directory review, but it is not a verified public directory listing.

## Package contract

The candidate contains the canonical `semantic-scala` skill, a Claude plugin
manifest, a package-relative local MCP configuration, and a Python bootstrap.
It contains no Java runtime or semantic-scala application JARs. The generated
package has 6 files, 40,205 unpacked bytes, a largest file of 21,188 bytes, and
a 14,843-byte ZIP archive. Its content SHA-256 is
`6496b2e82e1aa2bb698e3effade8e35f120899046381778f32b39c9032e84473`.

The host must provide Python 3.11 or newer. No host Java is required after the
bootstrap has installed the verified runtime. The downloaded runtime remains
Linux x86_64 only and requires compatible GNU libc and system zlib.

## First start and cache

On the first MCP start, the bootstrap downloads exactly:

<https://github.com/DmytroMitin/scala-semantic-harness/releases/download/0.1.0-alpha.3/semantic-scala-0.1.0-alpha.3-linux-x86_64.mcpb>

The expected size is 285,603,142 bytes and the expected SHA-256 is
`f5e5dbeb8ebfb8d0495dd3201bce7319ac72e19f6110ecec354843a1f978583d`.
The URL, size, and digest are fixed in the bootstrap and cannot be redirected
through an environment variable. Redirects are accepted only when the target
remains HTTPS. The bootstrap streams the download with a
size bound, verifies the digest before extraction, rejects unsafe ZIP paths,
duplicate or conflicting entries, links, and special files, verifies the
runtime manifest and entrypoints, and installs through an owner-only temporary
directory and atomic rename. Concurrent starts are serialized by a lock.

The installed runtime is stored below
`$XDG_CACHE_HOME/semantic-scala/runtime/0.1.0-alpha.3/<sha256>` when
`XDG_CACHE_HOME` is an absolute path, or below
`~/.cache/semantic-scala/runtime/0.1.0-alpha.3/<sha256>` otherwise. A successful
install does not retain the downloaded MCPB. Warm starts validate the cache
marker and required entrypoints, do not re-download or re-hash the complete
runtime, and start the cached MCP executable. Removing that versioned
cache directory forces a new verified download on the next start.

## Qualification status

Two deterministic builds were identical. Claude Code `2.1.283` passed strict
plugin and disposable-marketplace validation. A real cold installed-client
treatment from the final public source downloaded
and verified the fixed MCPB once and connected the plugin-local exact-eight MCP
server. Its first tool invocation used an absolute `file` value and was rejected
before semantic execution; one corrected read-only `semantic_effect_summary`
call then succeeded with `Fixture.value: Option[Int]`. A second client session
reused the unchanged cache while GitHub-bound fetch traffic was forced to fail;
the plugin server connected in 697 ms and exactly one read-only call succeeded
without changing the cache marker or fixture. No MCPB or partial download was
retained. Strict validation also passed for both exact-commit and `main` source
fixtures.

The candidate is far below the current documented directory limits: fewer
than 10,000 entries, archive smaller than 50 MiB, unpacked content smaller than
256 MiB, and every file smaller than 5 MiB. It also stays below the current
review-hold heuristics of 512 files and 256 KiB for a non-image/font file.

This establishes public-source technical readiness only. Published evaluator
guidance can place local launcher/download chains on a human-review hold. On
2026-09-28 the existing exact draft was revalidated successfully at `main @
05d4f0d`, with seven checks and one missing-icon warning. The owner corrected
the personal-data answer to `Reads only`, approved all four compliance
acknowledgements, supplied the private contact field, and authorized exactly
one `Submit for review` action. The security scan completed and sent qualified version `05d4f0d` to
content-policy review because shipped code could not be cleared automatically.
That historical In review status was later superseded by Needs changes; no public listing exists. Versions history retains
the earlier `fc3a6c8` detection event; no duplicate submission or corrective
portal action was attempted. The public [semantic-scala Privacy Policy](../PRIVACY.md)
remains exposed from `main`, and the private contact value is not retained here.
The historical full self-contained plugin remains preserved at parent commit
`c05aac9f38e7755a51f511078ff555a587f97ccf`.

Build and validate locally with:

```text
python3 -m unittest scripts.tests.test_package_claude_directory_plugin
python3 scripts/package-claude-directory-plugin.py assemble --output target/claude-directory-plugin/semantic-scala
python3 scripts/package-claude-directory-plugin.py validate --plugin-root target/claude-directory-plugin/semantic-scala
```
