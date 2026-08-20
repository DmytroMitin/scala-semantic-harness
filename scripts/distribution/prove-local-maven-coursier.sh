#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SOURCE="$ROOT"
VERSION="0.1.0-alpha.2"
CS="${CS:-$(command -v cs || true)}"
JAVA21_HOME="${JAVA21_HOME:-${JAVA_HOME:-}}"

usage() {
  echo "Usage: prove-local-maven-coursier.sh [--source <clean-source-tree>]" >&2
}

while (($#)); do
  case "$1" in
    --source)
      [[ $# -eq 2 ]] || { usage; exit 2; }
      SOURCE="$2"
      shift 2
      ;;
    *) usage; exit 2 ;;
  esac
done

[[ -n "$CS" && -x "$CS" ]] || { echo "Coursier cs is required" >&2; exit 1; }
[[ -n "$JAVA21_HOME" && -x "$JAVA21_HOME/bin/java" ]] || { echo "JAVA21_HOME must name a JDK 21 home" >&2; exit 1; }
[[ "$("$JAVA21_HOME/bin/java" -version 2>&1 | head -1)" == *'21.'* ]] || { echo "JDK 21 is required" >&2; exit 1; }
[[ -f "$SOURCE/build.sbt" && -f "$SOURCE/scripts/distribution/validate-maven-candidate.py" ]] || {
  echo "source tree is incomplete" >&2
  exit 1
}

if git -C "$SOURCE" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
  [[ -z "$(git -C "$SOURCE" status --porcelain=v1 --untracked-files=all)" ]] || {
    echo "source Git tree must be clean" >&2
    exit 1
  }
else
  [[ -z "$(find "$SOURCE" -type d \( -name target -o -name .bsp -o -name .metals -o -name __pycache__ \) -print -quit)" ]] || {
    echo "source snapshot contains generated build state" >&2
    exit 1
  }
fi

PROOF_ROOT="$(mktemp -d -t semantic-scala-task148-proof-XXXXXXXX)"
cleanup() {
  rm -rf -- "$PROOF_ROOT"
}
trap cleanup EXIT INT TERM

FIRST="$PROOF_ROOT/first"
SECOND="$PROOF_ROOT/second"
REPOSITORY_ONE="$PROOF_ROOT/maven-one"
REPOSITORY_TWO="$PROOF_ROOT/maven-two"
CHANNEL="$PROOF_ROOT/channel"
INSTALL="$PROOF_ROOT/install"
CACHE="$PROOF_ROOT/coursier-cache"
FIXTURE="$PROOF_ROOT/outside-workspace"
GNUPGHOME_TASK148="$PROOF_ROOT/gnupg"

cp -a -- "$SOURCE" "$FIRST"
cp -a -- "$SOURCE" "$SECOND"
mkdir -p "$REPOSITORY_ONE" "$REPOSITORY_TWO" "$FIXTURE/src" "$GNUPGHOME_TASK148"
chmod 700 "$GNUPGHOME_TASK148"
cp -- "$FIRST/modules/fp-analyzers/src/test/resources/effect-fixtures/simple/UserRepo.scala" "$FIXTURE/src/UserRepo.scala"

export JAVA_HOME="$JAVA21_HOME"
export PATH="$JAVA21_HOME/bin:$PATH"

publish_candidate() {
  local workspace="$1"
  local repository="$2"
  local include_tests="$3"
  local commands=(
    "set ThisBuild / version := \"$VERSION\""
    "set ThisBuild / publishTo := Some(Resolver.file(\"task148-local\", file(\"$repository\"))(Resolver.mavenStylePatterns))"
  )
  if [[ "$include_tests" == true ]]; then
    commands+=("test")
  fi
  commands+=(
    "core/publish"
    "sbtRunner/publish"
    "semanticdbReader/publish"
    "presentationCompiler/publish"
    "semanticReconciliation/publish"
    "fpAnalyzers/publish"
    "cli/publish"
    "mcpServer/publish"
  )
  (cd "$workspace" && sbt -batch "${commands[@]}")
}

publish_candidate "$FIRST" "$REPOSITORY_ONE" true
publish_candidate "$SECOND" "$REPOSITORY_TWO" false

GNUPGHOME="$GNUPGHOME_TASK148" gpg --batch --pinentry-mode loopback --passphrase '' \
  --quick-generate-key "semantic-scala Task 148 synthetic local proof" rsa2048 sign 1d >/dev/null 2>&1

while IFS= read -r -d '' artifact; do
  GNUPGHOME="$GNUPGHOME_TASK148" gpg --batch --yes --armor --detach-sign "$artifact"
  sha256sum "$artifact" >"$artifact.sha256"
  sha512sum "$artifact" >"$artifact.sha512"
  GNUPGHOME="$GNUPGHOME_TASK148" gpg --batch --verify "$artifact.asc" "$artifact" >/dev/null 2>&1
done < <(
  find "$REPOSITORY_ONE/com/github/dmytromitin" -type f \
    \( -name '*.pom' -o -name '*.jar' \) \
    ! -name '*.asc' ! -name '*.sha256' ! -name '*.sha512' -print0
)

python3 "$FIRST/scripts/distribution/validate-maven-candidate.py" repository \
  --repository "$REPOSITORY_ONE" --version "$VERSION" --output "$PROOF_ROOT/repository-report.json"

primary_manifest() {
  local repository="$1"
  (
    cd "$repository"
    find com/github/dmytromitin -type f \( -name '*.pom' -o -name '*.jar' \) \
      ! -name '*.asc' ! -name '*.sha256' ! -name '*.sha512' -print0 \
      | sort -z | xargs -0 sha256sum
  )
}
primary_manifest "$REPOSITORY_ONE" >"$PROOF_ROOT/manifest-one.txt"
primary_manifest "$REPOSITORY_TWO" >"$PROOF_ROOT/manifest-two.txt"
cmp "$PROOF_ROOT/manifest-one.txt" "$PROOF_ROOT/manifest-two.txt"

REPOSITORY_URI="file:$REPOSITORY_ONE"
python3 "$FIRST/scripts/distribution/coursier-channel.py" generate \
  --version "$VERSION" --repository "$REPOSITORY_URI" --output "$CHANNEL"

export COURSIER_CACHE="$CACHE"
"$CS" install --default-channels=false --channel "$CHANNEL" --install-dir "$INSTALL" \
  semantic-scala semantic-scala-mcp

SMOKE_ONE="$(python3 "$FIRST/scripts/distribution/smoke-installed.py" \
  --install "$INSTALL" --fixture "$FIXTURE" --version "$VERSION")"

for launcher in "$INSTALL/semantic-scala" "$INSTALL/semantic-scala-mcp"; do
  [[ -x "$launcher" ]] || { echo "missing installed launcher" >&2; exit 1; }
  ! grep -aF -- "$SOURCE" "$launcher" >/dev/null
  ! grep -aF -- "scala-semantic-harness-control" "$launcher" >/dev/null
done

"$CS" update --install-dir "$INSTALL" semantic-scala semantic-scala-mcp
SMOKE_TWO="$(python3 "$FIRST/scripts/distribution/smoke-installed.py" \
  --install "$INSTALL" --fixture "$FIXTURE" --version "$VERSION")"

python3 "$FIRST/scripts/distribution/validate-maven-candidate.py" cache \
  --cache "$CACHE" --output "$PROOF_ROOT/runtime-inventory.json"

"$CS" uninstall --install-dir "$INSTALL" semantic-scala semantic-scala-mcp
[[ ! -e "$INSTALL/semantic-scala" && ! -e "$INSTALL/semantic-scala-mcp" ]] || {
  echo "Coursier uninstall left launchers behind" >&2
  exit 1
}

python3 - "$PROOF_ROOT" "$VERSION" "$CS" "$SMOKE_ONE" "$SMOKE_TWO" <<'PY'
import hashlib
import json
import subprocess
import sys
from pathlib import Path

root, version, cs, first, second = sys.argv[1:]
root = Path(root)
repository = json.loads((root / "repository-report.json").read_text())
inventory = json.loads((root / "runtime-inventory.json").read_text())
manifest_hash = hashlib.sha256((root / "manifest-one.txt").read_bytes()).hexdigest()
channel_hashes = {
    path.name: hashlib.sha256(path.read_bytes()).hexdigest()
    for path in sorted((root / "channel").iterdir())
}
flags = [component["flags"] for component in inventory["components"]]
print(json.dumps({
    "schemaVersion": "semantic-scala.local-distribution-proof.v1",
    "version": version,
    "coursierVersion": subprocess.check_output([cs, "version"], text=True).strip(),
    "moduleCount": repository["moduleCount"],
    "artifactCount": len(repository["artifacts"]),
    "primaryArtifactManifestSha256": manifest_hash,
    "byteIdenticalAcrossTwoCleanBuilds": True,
    "channelSha256": channel_hashes,
    "initialSmoke": json.loads(first),
    "postUpdateSmoke": json.loads(second),
    "runtimeComponentCount": inventory["componentCount"],
    "licenseFlags": {
        "multiple": sum(flag["multipleLicenseMetadata"] for flag in flags),
        "eplFamily": sum(flag["eplFamily"] for flag in flags),
        "missingOrAmbiguous": sum(flag["missingOrAmbiguousLicenseMetadata"] for flag in flags),
        "noticeReview": sum(flag["noticeOrAttributionReview"] for flag in flags),
    },
    "syntheticSigningKeyDeletedByTrap": True,
    "uninstalled": True,
    "externalPublication": False,
}, indent=2, sort_keys=True))
PY
