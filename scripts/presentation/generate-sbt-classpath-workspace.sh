#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
WORKSPACE="$REPO_ROOT/target/sbtcp-sbt-classpath-workspace"

cleanup() {
  rm -rf "$WORKSPACE"
}

generate() {
  cleanup

  mkdir -p \
    "$WORKSPACE/project" \
    "$WORKSPACE/src/main/scala/sbtcp/root" \
    "$WORKSPACE/api/src/main/scala/sbtcp/api" \
    "$WORKSPACE/jar-api/src/main/scala/sbtcp/jarapi" \
    "$WORKSPACE/app/src/main/scala/sbtcp/app" \
    "$WORKSPACE/app/src/test/scala/sbtcp/app"

  cat >"$WORKSPACE/project/build.properties" <<'EOF'
sbt.version=1.9.8
EOF

  cat >"$WORKSPACE/build.sbt" <<'EOF'
ThisBuild / scalaVersion := "3.3.3"
ThisBuild / organization := "sbtcp.fixture"
ThisBuild / version := "0.1.0"

lazy val root = (project in file("."))
  .aggregate(api, jarApi, app)
  .settings(publish / skip := true)

lazy val api = (project in file("api"))

lazy val jarApi = (project in file("jar-api"))
  .settings(Compile / exportJars := true)

lazy val app = Project(id = "app-2", base = file("app"))
  .dependsOn(api, jarApi)
EOF

  cat >"$WORKSPACE/api/src/main/scala/sbtcp/api/DomainId.scala" <<'EOF'
package sbtcp.api

final case class DomainId(value: String)
EOF

  cat >"$WORKSPACE/src/main/scala/sbtcp/root/RootMarker.scala" <<'EOF'
package sbtcp.root

object RootMarker
EOF

  cat >"$WORKSPACE/jar-api/src/main/scala/sbtcp/jarapi/BuildTag.scala" <<'EOF'
package sbtcp.jarapi

final case class BuildTag(value: String)
EOF

  cat >"$WORKSPACE/app/src/main/scala/sbtcp/app/AppService.scala" <<'EOF'
package sbtcp.app

import sbtcp.api.DomainId
import sbtcp.jarapi.BuildTag

object AppService:
  val domain = DomainId("sbtcp")
  val tag = BuildTag("ordinary-export-jars")
  val rendered = Option(domain).map(_.value)
EOF

  cat >"$WORKSPACE/app/src/test/scala/sbtcp/app/TestSupport.scala" <<'EOF'
package sbtcp.app

final case class TestToken(value: Int)
EOF

  cat >"$WORKSPACE/app/src/test/scala/sbtcp/app/TestConsumer.scala" <<'EOF'
package sbtcp.app

object TestConsumer:
  val token = TestToken(69)
  val rendered = Option(token).map(_.value)
EOF

  test -f "$WORKSPACE/build.sbt"
  test -f "$WORKSPACE/project/build.properties"
  test -f "$WORKSPACE/src/main/scala/sbtcp/root/RootMarker.scala"
  test -f "$WORKSPACE/api/src/main/scala/sbtcp/api/DomainId.scala"
  test -f "$WORKSPACE/app/src/main/scala/sbtcp/app/AppService.scala"
  test -f "$WORKSPACE/app/src/test/scala/sbtcp/app/TestConsumer.scala"
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
