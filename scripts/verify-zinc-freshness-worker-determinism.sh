#!/usr/bin/env bash
set -euo pipefail

repository_root=$(cd "$(dirname "$0")/.." && pwd)
temporary_root=$(mktemp -d)
trap 'rm -rf "$temporary_root"' EXIT

expected_sha256=cffcddbee0795d436664185a0ca0e3206a6542b5d01bd0e487da3da23cbcfd69

build_copy() {
  destination=$1
  (cd "$repository_root" && sbt -batch zincFreshnessWorker/clean zincFreshnessWorker/Compile/packageBin)
  artifact_root="$repository_root/modules/zinc-freshness-worker/target/scala-2.13"
  mapfile -t artifacts < <(
    find "$artifact_root" -maxdepth 1 -type f \
      -name 'semantic-scala-zinc-freshness-worker_2.13-*.jar' | sort
  )
  test "${#artifacts[@]}" -eq 1
  cp "${artifacts[0]}" "$destination"
}

build_copy "$temporary_root/worker-first.jar"
build_copy "$temporary_root/worker-second.jar"
cmp "$temporary_root/worker-first.jar" "$temporary_root/worker-second.jar"

actual_sha256=$(sha256sum "$temporary_root/worker-first.jar" | cut -d' ' -f1)
test "$actual_sha256" = "$expected_sha256"
printf 'ZINC_FRESHNESS_WORKER_DETERMINISM_PASS sha256=%s\n' "$actual_sha256"
