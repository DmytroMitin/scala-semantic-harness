#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "$script_dir/../.." && pwd)"
workspace="$repo_root/target/multimodule-semanticdb-artifact-hints-workspace"
scala_version="3.3.3"
sbt_version="1.9.8"

cleanup_workspace() {
  case "$workspace" in
    "$repo_root"/target/multimodule-semanticdb-artifact-hints-workspace) ;;
    *)
      echo "Refusing to clean unexpected path: $workspace" >&2
      exit 1
      ;;
  esac

  if [[ -e "$workspace" ]]; then
    rm -rf -- "$workspace"
  fi
}

write_workspace() {
  mkdir -p \
    "$workspace/project" \
    "$workspace/alpha/src/main/scala/example" \
    "$workspace/alpha/src/test/scala/example" \
    "$workspace/beta/src/main/scala/example" \
    "$workspace/beta/src/test/scala/example"

  cat > "$workspace/project/build.properties" <<EOF
sbt.version=$sbt_version
EOF

  cat > "$workspace/build.sbt" <<EOF
ThisBuild / scalaVersion := "$scala_version"
ThisBuild / version := "0.0.0-multimodule"

lazy val semanticdbSettings = Seq(
  scalacOptions ++= Seq(
    "-Xsemanticdb",
    "-sourceroot",
    baseDirectory.value.getAbsolutePath
  )
)

lazy val root = (project in file("."))
  .aggregate(alpha, beta)
  .settings(publish / skip := true)

lazy val alpha = (project in file("alpha"))
  .settings(semanticdbSettings)

lazy val beta = (project in file("beta"))
  .settings(semanticdbSettings)
EOF

  cat > "$workspace/alpha/src/main/scala/example/Shared.scala" <<'EOF'
package example

object Shared:
  def moduleName: String = "alpha"
EOF

  cat > "$workspace/alpha/src/test/scala/example/SharedSuite.scala" <<'EOF'
package example

object SharedSuite:
  def result: String = "alpha-test:" + Shared.moduleName
EOF

  cat > "$workspace/beta/src/main/scala/example/Shared.scala" <<'EOF'
package example

object Shared:
  def moduleName: String = "beta"
EOF

  cat > "$workspace/beta/src/test/scala/example/SharedSuite.scala" <<'EOF'
package example

object SharedSuite:
  def result: String = "beta-test:" + Shared.moduleName
EOF
}

generate_workspace() {
  cleanup_workspace
  write_workspace

  (
    cd "$workspace"
    sbt -batch \
      "alpha/compile" \
      "alpha/Test/compile" \
      "beta/compile" \
      "beta/Test/compile"
  )

  mapfile -d '' semanticdb_files < <(
    find "$workspace/alpha/target" "$workspace/beta/target" \
      -type f -name '*.semanticdb' -print0 | sort -z
  )

  if [[ "${#semanticdb_files[@]}" -ne 4 ]]; then
    echo "Expected exactly four generated SemanticDB artifacts, found ${#semanticdb_files[@]}" >&2
    exit 1
  fi

  echo "Generated multimodule workspace"
  echo "Workspace: $workspace"
  echo "Scala: $scala_version"
  echo "sbt: $sbt_version"
  echo "SemanticDB artifacts:"
  for file in "${semanticdb_files[@]}"; do
    echo "  ${file#"$workspace"/}"
  done
}

case "${1:-generate}" in
  generate)
    generate_workspace
    ;;
  cleanup)
    cleanup_workspace
    echo "Removed multimodule workspace: $workspace"
    ;;
  *)
    echo "Usage: $0 [generate|cleanup]" >&2
    exit 2
    ;;
esac
