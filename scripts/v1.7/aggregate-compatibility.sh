#!/usr/bin/env bash
#
# scripts/v1.7/aggregate-compatibility.sh
#
# V1.7 M3-A compatibility aggregator (section 10.3 / 10.4.1). Consumes the row-evidence
# JSON files produced by run-compatibility.sh, validates and aggregates them, and writes
# the single compatibility-result.json. It consumes ROW JSON ONLY: it never executes
# scenarios, never reads the workflow or acceptance manifest, and performs no network
# downloads.
#
# Fixed interface (section 10.3):
#   ./scripts/v1.7/aggregate-compatibility.sh \
#     --input target/v1.7/compatibility-rows \
#     --output target/v1.7/compatibility-result.json
#
# Fail-closed (overall=FAILED, exit 4) when any of:
#   - a row file is missing/unparseable/malformed;
#   - a C01-C10 row is missing, duplicated, or an unknown scenario appears;
#   - rows disagree on the build id (not a single candidate commit);
#   - a row's embedded catalog block does not match the frozen catalog;
#   - a row claims PASSED without a real independent child PID / full assertions;
#   - any formal row (C01-C08, C10) is FAILED / SKIPPED / NOT_RUN.
# C09 (experimental) is accepted only as PASSED or EXPERIMENTAL; FAILED,
# SKIPPED, and NOT_RUN fail the matrix closed.
#
# Exit codes (preserved exactly from the aggregator):
#   0  aggregate valid and all formal rows PASSED (overall=PASSED)
#   1  usage / validation error
#   2  build failed
#   3  aggregator unusable (e.g. --input is not a directory)
#   4  aggregate has failures (overall=FAILED) - fail-closed
#   5  result-write error
#   6  schema-validation failure (self-validation of the produced result)
#
# The runner NEVER switches or dirties the active V1.7 worktree. It builds the
# aggregator in place (HEAD), runs it in a fresh JVM, preserves the exact exit code,
# and cleans only this runner's prior result file.

set -euo pipefail
# Fail-fast is on (`-e`). The ONLY intentional non-fatal command is the aggregator
# invocation: it returns 3/4/5/6 on unusable/failed/write/schema failure, and we
# capture that status explicitly and exit with the EXACT code (see the `set +e`
# block below) rather than abort early.

INPUT=""
OUTPUT=""
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA_BIN="${JAVA_BIN:-java}"
MVN="${MVN:-mvn}"
ORIGINAL_ARGS=("$@")

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
AGGREGATOR_MAIN="com.example.kairo.compatmatrix.CompatibilityAggregatorMain"
MODULES=(kairo-bootstrap-api kairo-api kairo-groovy kairo-core kairo-agent-core
         kairo-agent-server kairo-agent-core-modern kairo-agent-bootstrap
         kairo-attach-cli kairo-ops kairo-platform-server kairo-sdk kairo-cli
         kairo-mcp kairo-demo kairo-integration-tests)

usage() {
  cat <<'EOF'
Usage: aggregate-compatibility.sh --input <dir> --output <result.json> [--help]

Required:
  --input    directory containing the row-evidence JSON files (one per scenario)
  --output   output path for the single compatibility-result.json

Optional:
  --help     show this help

Behavior:
  - Builds the aggregator in place at HEAD (kairo-integration-tests -am test-compile);
    the active V1.7 worktree is never switched or dirtied.
  - Reads every *.json file in --input (sorted). Does not execute scenarios.
  - Rejects missing/duplicate/unknown/malformed rows, build-id mismatch, fake PASSED
    evidence, and any formal row not PASSED (overall=FAILED, exit 4).
  - Writes the single compatibility-result.json and self-validates its schema.
  - Cleans only this runner's prior result file before running.
  - Never modifies the workflow, the acceptance manifest, or support conclusions.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --input) INPUT="$2"; shift 2 ;;
    --output) OUTPUT="$2"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage; exit 1 ;;
  esac
done

if [[ -z "$INPUT" || -z "$OUTPUT" ]]; then
  echo "error: --input and --output are required" >&2
  usage
  exit 1
fi

# Contamination guard: clear ONLY this runner's prior result file, then recreate parent.
rm -f "$OUTPUT"
PARENT_DIR="$(dirname "$OUTPUT")"
mkdir -p "$PARENT_DIR"

printf -v EXACT_CMD '%q ' "$0" "${ORIGINAL_ARGS[@]}"
EXACT_CMD="${EXACT_CMD% }"

echo "==> input=$INPUT output=$OUTPUT"

# -----------------------------------------------------------------------------
# Build the aggregator in place at HEAD. Single side; no worktree, no dirty.
# Generate its classpath in the same reactor invocation after upstream modules
# are packaged; a fresh CI Maven repository cannot independently resolve the
# child POMs whose parent uses the CI-friendly ${revision} placeholder.
# -----------------------------------------------------------------------------
EXTDEPS_FILE="$(mktemp -t kairo-compatagg-deps-XXXXXX)"
echo "==> building aggregator at $REPO_ROOT"
if ! (cd "$REPO_ROOT" && $MVN -B -ntp -pl kairo-integration-tests -am \
      package -DskipTests dependency:build-classpath \
      -Dmdep.outputFile="$EXTDEPS_FILE" -DincludeScope=test -q); then
  echo "error: aggregator build failed" >&2
  rm -f "$EXTDEPS_FILE"
  exit 2
fi

# -----------------------------------------------------------------------------
# Assemble the classpath: aggregator test-classes + reactor target/classes + ext deps.
# -----------------------------------------------------------------------------
reactor_classes() {
  local out=""
  for m in "${MODULES[@]}"; do
    local d="$REPO_ROOT/$m/target/classes"
    if [[ -d "$d" ]]; then out="$out:$d"; fi
  done
  echo "${out#:}"
}
EXTDEPS="$(cat "$EXTDEPS_FILE")"
rm -f "$EXTDEPS_FILE"
CP="$REPO_ROOT/kairo-integration-tests/target/test-classes:$(reactor_classes):$EXTDEPS"

echo "==> running aggregator"
# The aggregator returns 3/4/5/6 on unusable/failed/write/schema failure. Temporarily
# disable `-e` so those codes are captured (not turned into an early abort), then
# exit with the EXACT code. The result file is always written (best effort).
AGG_STATUS=0
set +e
"$JAVA_BIN" -cp "$CP" "$AGGREGATOR_MAIN" \
  --input "$INPUT" \
  --output "$OUTPUT" \
  --command "$EXACT_CMD"
AGG_STATUS=$?
set -e

echo "==> done (aggregator exit=$AGG_STATUS). result: $OUTPUT"
exit "$AGG_STATUS"
