# Coursier application source

This directory is the maintained source for the future `semantic-scala` and
`semantic-scala-mcp` Coursier applications. The descriptors deliberately name
the `_3` implementation artifacts and require an exact released version. They
do not assert that the current development snapshot exists on Maven Central.

Generate a production-shaped directory channel after choosing an externally
published exact version:

```text
python3 scripts/distribution/coursier-channel.py generate \
  --version <exact-version> \
  --repository central \
  --output <empty-output-directory>
```

For the disposable local proof, pass the URI of its isolated Maven repository
instead of `central`; templates are never edited by the proof. The supported
runtime baseline is JDK 21 and Coursier is required for this installation
route. Target-workspace sbt is separate: build-oracle commands such as
`compile`, `errors`, and `test` require it, while installation and every
read-only semantic operation do not inherently require target-workspace sbt.

These Maven coordinates are application implementation artifacts, not a
supported embeddable-library API or a binary-compatibility promise.
