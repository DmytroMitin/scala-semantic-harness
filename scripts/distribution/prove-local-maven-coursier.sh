#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SOURCE="$ROOT"
EVIDENCE_DIR=""
PRIMARIES_ONLY=false
CS="${CS:-$(command -v cs || true)}"
JAVA21_HOME="${JAVA21_HOME:-${JAVA_HOME:-}}"

usage() {
  echo "Usage: prove-local-maven-coursier.sh [--source <clean-source-tree>] [--primaries-only] [--evidence-dir <absent-directory>]" >&2
}

while (($#)); do
  case "$1" in
    --source)
      [[ $# -ge 2 ]] || { usage; exit 2; }
      SOURCE="$2"
      shift 2
      ;;
    --primaries-only)
      PRIMARIES_ONLY=true
      shift
      ;;
    --evidence-dir)
      [[ $# -ge 2 ]] || { usage; exit 2; }
      EVIDENCE_DIR="$2"
      shift 2
      ;;
    *) usage; exit 2 ;;
  esac
done

[[ -n "$CS" && -x "$CS" ]] || { echo "Coursier cs is required" >&2; exit 1; }
[[ -n "$JAVA21_HOME" && -x "$JAVA21_HOME/bin/java" ]] || { echo "JAVA21_HOME must name a JDK 21 home" >&2; exit 1; }
[[ "$("$JAVA21_HOME/bin/java" -version 2>&1 | head -1)" == *'21.'* ]] || { echo "JDK 21 is required" >&2; exit 1; }
[[ -f "$SOURCE/build.sbt" && -f "$SOURCE/scripts/distribution/validate-maven-candidate.py" && \
   -f "$SOURCE/scripts/distribution/project-release-version.py" ]] || {
  echo "source tree is incomplete" >&2
  exit 1
}
VERSION="$(python3 "$SOURCE/scripts/distribution/project-release-version.py" --source "$SOURCE")"
[[ -z "$EVIDENCE_DIR" || ! -e "$EVIDENCE_DIR" ]] || {
  echo "evidence directory must not already exist" >&2
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

PROOF_ROOT="$(mktemp -d -t semantic-scala-release-proof-XXXXXXXX)"
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

cp -a -- "$SOURCE" "$FIRST"
cp -a -- "$SOURCE" "$SECOND"
mkdir -p "$REPOSITORY_ONE" "$REPOSITORY_TWO" "$FIXTURE/src"
cp -- "$FIRST/modules/fp-analyzers/src/test/resources/effect-fixtures/simple/UserRepo.scala" "$FIXTURE/src/UserRepo.scala"

export JAVA_HOME="$JAVA21_HOME"
export PATH="$JAVA21_HOME/bin:$PATH"

publish_candidate() {
  local workspace="$1"
  local repository="$2"
  local include_tests="$3"
  local commands=(
    "set ThisBuild / version := \"$VERSION\""
    "set ThisBuild / publishTo := Some(Resolver.file(\"release-candidate-local\", file(\"$repository\"))(Resolver.mavenStylePatterns))"
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

if [[ "$PRIMARIES_ONLY" == false ]]; then
  GNUPGHOME_PROOF="$PROOF_ROOT/gnupg"
  mkdir -p "$GNUPGHOME_PROOF"
  chmod 700 "$GNUPGHOME_PROOF"
  GNUPGHOME="$GNUPGHOME_PROOF" gpg --batch --pinentry-mode loopback --passphrase '' \
    --quick-generate-key "semantic-scala synthetic local proof" rsa2048 sign 1d >/dev/null 2>&1

  while IFS= read -r -d '' artifact; do
    GNUPGHOME="$GNUPGHOME_PROOF" gpg --batch --yes --armor --detach-sign "$artifact"
    sha256sum "$artifact" >"$artifact.sha256"
    sha512sum "$artifact" >"$artifact.sha512"
    GNUPGHOME="$GNUPGHOME_PROOF" gpg --batch --verify "$artifact.asc" "$artifact" >/dev/null 2>&1
  done < <(
    find "$REPOSITORY_ONE/com/github/dmytromitin" -type f \
      \( -name '*.pom' -o -name '*.jar' \) \
      ! -name '*.asc' ! -name '*.sha256' ! -name '*.sha512' -print0
  )
fi

validator_args=(repository --repository "$REPOSITORY_ONE" --version "$VERSION")
if [[ "$PRIMARIES_ONLY" == true ]]; then
  validator_args+=(--primaries-only)
fi
python3 "$FIRST/scripts/distribution/validate-maven-candidate.py" "${validator_args[@]}" \
  --output "$PROOF_ROOT/repository-report.json"

primary_manifest() {
  local repository="$1"
  printf 'gav\trelative_maven_path\tfile_kind\tbytes\tsha256\n'
  while IFS= read -r -d '' artifact; do
    local relative="${artifact#"$repository/"}"
    local coordinate="${relative#com/github/dmytromitin/}"
    local module="${coordinate%%/*}"
    local remainder="${coordinate#*/}"
    local coordinate_version="${remainder%%/*}"
    local name="${artifact##*/}"
    local kind
    case "$name" in
      *.pom) kind=pom ;;
      *-sources.jar) kind=sources ;;
      *-javadoc.jar) kind=documentation ;;
      *.jar) kind=main ;;
      *) echo "unexpected primary file: $name" >&2; return 1 ;;
    esac
    printf '%s\t%s\t%s\t%s\t%s\n' \
      "com.github.dmytromitin:$module:$coordinate_version" \
      "$relative" "$kind" "$(stat -c %s "$artifact")" \
      "$(sha256sum "$artifact" | cut -d' ' -f1)"
  done < <(
    find "$repository/com/github/dmytromitin" -type f \
      \( -name '*.pom' -o -name '*.jar' \) \
      ! -name '*.asc' ! -name '*.sha256' ! -name '*.sha512' -print0 | sort -z
  )
}
primary_manifest "$REPOSITORY_ONE" >"$PROOF_ROOT/primary-manifest-build-a.tsv"
primary_manifest "$REPOSITORY_TWO" >"$PROOF_ROOT/primary-manifest-build-b.tsv"
cmp "$PROOF_ROOT/primary-manifest-build-a.tsv" "$PROOF_ROOT/primary-manifest-build-b.tsv"

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

python3 - "$PROOF_ROOT" "$VERSION" "$CS" "$SMOKE_ONE" "$SMOKE_TWO" "$PRIMARIES_ONLY" >"$PROOF_ROOT/local-proof-summary.json" <<'PY'
import hashlib
import json
import subprocess
import sys
from pathlib import Path

root, version, cs, first, second, primaries_only = sys.argv[1:]
root = Path(root)
repository = json.loads((root / "repository-report.json").read_text())
inventory = json.loads((root / "runtime-inventory.json").read_text())
manifest_hash = hashlib.sha256((root / "primary-manifest-build-a.tsv").read_bytes()).hexdigest()
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
    "primariesOnly": primaries_only == "true",
    "signingPerformed": primaries_only != "true",
    "syntheticSigningKeyDeletedByTrap": primaries_only != "true",
    "uninstalled": True,
    "externalPublication": False,
}, indent=2, sort_keys=True))
PY

if [[ -n "$EVIDENCE_DIR" ]]; then
  mkdir -p "$EVIDENCE_DIR"
  cp "$PROOF_ROOT/primary-manifest-build-a.tsv" "$EVIDENCE_DIR/primary-manifest-build-a.tsv"
  cp "$PROOF_ROOT/primary-manifest-build-b.tsv" "$EVIDENCE_DIR/primary-manifest-build-b.tsv"
  cp "$PROOF_ROOT/repository-report.json" "$EVIDENCE_DIR/maven-candidate-report.json"
  cp "$PROOF_ROOT/runtime-inventory.json" "$EVIDENCE_DIR/runtime-dependency-inventory.json"
  cp "$PROOF_ROOT/local-proof-summary.json" "$EVIDENCE_DIR/local-proof-summary.json"
fi

cat "$PROOF_ROOT/local-proof-summary.json"
