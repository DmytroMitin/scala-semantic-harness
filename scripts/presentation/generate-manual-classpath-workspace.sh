#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
WORKSPACE="$REPO_ROOT/target/manualcp-presentation-classpath-workspace"

cleanup() {
  rm -rf "$WORKSPACE"
}

generate() {
  cleanup

  mkdir -p \
    "$WORKSPACE/project" \
    "$WORKSPACE/api/src/main/scala/manualcp/api" \
    "$WORKSPACE/app/src/main/scala/manualcp/app" \
    "$WORKSPACE/app/src/test/scala/manualcp/app"

  cat >"$WORKSPACE/project/build.properties" <<'EOF'
sbt.version=1.9.8
EOF

  cat >"$WORKSPACE/build.sbt" <<'EOF'
ThisBuild / scalaVersion := "3.3.3"
ThisBuild / organization := "manualcp.fixture"
ThisBuild / version := "0.1.0"

lazy val exportCompileClasspath = taskKey[File]("Export the app Compile fullClasspath")
lazy val exportTestClasspath = taskKey[File]("Export the app Test fullClasspath")

lazy val api = (project in file("api"))

lazy val app = (project in file("app"))
  .dependsOn(api)
  .settings(
    exportCompileClasspath := {
      val entries = (Compile / fullClasspath).value
        .map(_.data.toPath.toAbsolutePath.normalize())
        .distinct
      val output = baseDirectory.value.getParentFile / "app-compile.classpath"
      IO.writeLines(output, entries.map(_.toString))
      streams.value.log.info(s"wrote ${entries.size} Compile classpath entries")
      output
    },
    exportTestClasspath := {
      val entries = (Test / fullClasspath).value
        .map(_.data.toPath.toAbsolutePath.normalize())
        .distinct
      val output = baseDirectory.value.getParentFile / "app-test.classpath"
      IO.writeLines(output, entries.map(_.toString))
      streams.value.log.info(s"wrote ${entries.size} Test classpath entries")
      output
    }
  )
EOF

  cat >"$WORKSPACE/api/src/main/scala/manualcp/api/DomainId.scala" <<'EOF'
package manualcp.api

final case class DomainId(value: String)
EOF

  cat >"$WORKSPACE/app/src/main/scala/manualcp/app/AppService.scala" <<'EOF'
package manualcp.app

import manualcp.api.DomainId

object AppService:
  val domain = DomainId("manualcp")
  val rendered = Option(domain).map(_.value)
EOF

  cat >"$WORKSPACE/app/src/test/scala/manualcp/app/TestSupport.scala" <<'EOF'
package manualcp.app

final case class TestToken(value: Int)
EOF

  cat >"$WORKSPACE/app/src/test/scala/manualcp/app/TestConsumer.scala" <<'EOF'
package manualcp.app

object TestConsumer:
  val token = TestToken(67)
  val rendered = Option(token).map(_.value)
EOF

  (
    cd "$WORKSPACE"
    sbt -batch \
      "api/compile" \
      "app/compile" \
      "app/Test/compile" \
      "app/exportCompileClasspath" \
      "app/exportTestClasspath"
  )

  test -f "$WORKSPACE/api/target/scala-3.3.3/classes/manualcp/api/DomainId.class"
  test -f "$WORKSPACE/app/target/scala-3.3.3/classes/manualcp/app/AppService.class"
  test -f "$WORKSPACE/app/target/scala-3.3.3/test-classes/manualcp/app/TestToken.class"
  test -f "$WORKSPACE/app/src/main/scala/manualcp/app/AppService.scala"
  test -f "$WORKSPACE/app/src/test/scala/manualcp/app/TestConsumer.scala"
  test -s "$WORKSPACE/app-compile.classpath"
  test -s "$WORKSPACE/app-test.classpath"
}

case "${1:-}" in
  generate)
    generate
    ;;
  cleanup)
    cleanup
    ;;
  *)
    echo "Usage: $0 generate|cleanup" >&2
    exit 2
    ;;
esac
