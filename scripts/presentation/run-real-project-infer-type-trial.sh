#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STAGE_DIR="$REPO_ROOT/modules/cli/target/stage"
STAGE_LIB="$STAGE_DIR/lib"
CLI="$STAGE_DIR/bin/semantic-scala"

usage() {
  cat >&2 <<'EOF'
Usage:
  run-real-project-infer-type-trial.sh metrics <file> <marker> <delta>
  run-real-project-infer-type-trial.sh query <file> <marker> <delta> full
  run-real-project-infer-type-trial.sh query <file> <marker> <delta> narrow
  run-real-project-infer-type-trial.sh query <file> <marker> <delta> omit-class <class-file>

The staged CLI and the immediate children of target/stage/lib are evidence
inputs. Classpath entries are ordered by their numeric stage index and passed
as one repeated --classpath argument per entry.
EOF
  exit 2
}

[[ $# -ge 4 ]] || usage
operation="$1"
source_file="$2"
marker="$3"
delta="$4"
mode="${5:-full}"
omitted_class="${6:-}"

[[ -x "$CLI" && -d "$STAGE_LIB" ]] || {
  echo "Run 'sbt cli/stage' before this evidence helper." >&2
  exit 1
}

if [[ "$source_file" != /* ]]; then
  source_file="$REPO_ROOT/$source_file"
fi
[[ -f "$source_file" ]] || {
  echo "Source file does not exist: $source_file" >&2
  exit 1
}

mapfile -t staged_records < <(
  while IFS= read -r -d '' entry; do
    base="${entry##*/}"
    case "$base" in
      classes-*) index="${base#classes-}" ;;
      [0-9]*-*) index="${base%%-*}" ;;
      *)
        echo "Unexpected staged classpath entry: $base" >&2
        exit 1
        ;;
    esac
    [[ "$index" =~ ^[0-9]+$ ]] || {
      echo "Invalid staged classpath index: $base" >&2
      exit 1
    }
    printf '%010d\t%s\n' "$index" "$entry"
  done < <(find "$STAGE_LIB" -mindepth 1 -maxdepth 1 -print0) |
    sort -n -k1,1
)

classpath=()
omitted_entry=""
for record in "${staged_records[@]}"; do
  entry="${record#*$'\t'}"
  omit=false
  if [[ "$mode" == "omit-class" ]]; then
    [[ -n "$omitted_class" ]] || usage
    if [[ -d "$entry" && -f "$entry/$omitted_class" ]]; then
      omit=true
    elif [[ -f "$entry" ]] && jar tf "$entry" | grep -Fx -- "$omitted_class" >/dev/null; then
      omit=true
    fi
  fi
  if [[ "$omit" == true ]]; then
    [[ -z "$omitted_entry" ]] || {
      echo "Class file occurs in more than one staged entry: $omitted_class" >&2
      exit 1
    }
    omitted_entry="$entry"
  else
    classpath+=("$entry")
  fi
done

case "$mode" in
  full) ;;
  narrow) classpath=() ;;
  omit-class)
    [[ -n "$omitted_entry" ]] || {
      echo "Class file not found in staged entries: $omitted_class" >&2
      exit 1
    }
    ;;
  *) usage ;;
esac

read -r line column < <(
  python3 - "$source_file" "$marker" "$delta" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1]).read_text()
marker = sys.argv[2]
delta = int(sys.argv[3])
first = source.find(marker)
if first < 0:
    raise SystemExit(f"Marker not found: {marker}")
if source.find(marker, first + 1) >= 0:
    raise SystemExit(f"Marker is not unique: {marker}")
offset = first + delta
if offset < 0 or offset > len(source):
    raise SystemExit(f"Marker delta is outside the source: {delta}")
line_start = source.rfind("\n", 0, offset) + 1
line = source.count("\n", 0, line_start) + 1
column = len(source[line_start:offset].encode("utf-16-le")) // 2 + 1
print(line, column)
PY
)

command=(
  "$CLI" infer-type
  --file "$source_file"
  --line "$line"
  --col "$column"
  --workspace "$REPO_ROOT"
)
for entry in "${classpath[@]}"; do
  command+=(--classpath "$entry")
done
command+=(--json)

case "$operation" in
  metrics)
    [[ "$mode" == "full" ]] || usage
    directories=0
    jars=0
    path_characters=0
    for entry in "${classpath[@]}"; do
      if [[ -d "$entry" ]]; then
        ((directories += 1))
      else
        ((jars += 1))
      fi
      ((path_characters += ${#entry}))
    done
    command_characters=0
    for argument in "${command[@]}"; do
      ((command_characters += ${#argument} + 1))
    done
    printf '{"entryCount":%d,"directoryCount":%d,"jarCount":%d,"totalPathCharacters":%d,"approximateCommandCharacters":%d}\n' \
      "${#classpath[@]}" "$directories" "$jars" "$path_characters" "$command_characters"
    ;;
  query)
    "${command[@]}"
    ;;
  *) usage ;;
esac
