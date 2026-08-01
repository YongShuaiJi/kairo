#!/usr/bin/env bash
#
# scripts/v1.7/run-compatibility.sh
#
# V1.7 M3-A compatibility row-evidence runner (section 10.3 / 10.4.1). Produces one
# row-evidence JSON file for a fixed C01-C10 scenario. The row runner is contract /
# dispatch foundation only: it validates the environment and dispatches to a scenario
# implementation. Because real fixtures land in M3-B through M3-E, in M3-A every formal
# scenario fails closed with truthful NOT_RUN evidence (exit 4) and the experimental
# C09 emits truthful EXPERIMENTAL evidence (exit 0). It NEVER fabricates PASSED.
#
# Fixed interface (section 10.3):
#   ./scripts/v1.7/run-compatibility.sh \
#     --scenario C01 --output target/v1.7/compatibility-rows/C01.json
#
# Exit codes (preserved exactly from the row runner):
#   0  row produced non-blocking truthful evidence (PASSED or EXPERIMENTAL)
#   1  usage / validation error (incl. dirty PR tree)
#   2  build failed
#   3  row runner unusable
#   4  blocking non-passed evidence (FAILED / SKIPPED / NOT_RUN) - fail-closed
#   5  row-write error
#   6  schema-validation failure
#
# The runner NEVER switches or dirties the active V1.7 worktree. It builds the runner
# in place (HEAD), runs it in a fresh JVM, preserves the exact exit code, and cleans
# only this runner's prior row file. It never modifies the workflow, the acceptance
# manifest, or the support conclusions, and performs no network downloads.

set -euo pipefail
# Fail-fast is on (`-e`). The ONLY intentional non-fatal command is the runner
# invocation: it returns 3/4/5/6 on unusable/blocked/write/schema failure, and we
# capture that status explicitly and exit with the EXACT code (see the `set +e`
# block below) rather than abort early. Every other failing command (build /
# classpath) aborts immediately so it can never silently continue.

SCENARIO=""
OUTPUT=""
ALLOW_DIRTY="false"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA_BIN="${JAVA_BIN:-java}"
MVN="${MVN:-mvn}"
ORIGINAL_ARGS=("$@")

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
RUNNER_MAIN="com.example.kairo.compatmatrix.CompatibilityRowRunner"
MODULES=(kairo-bootstrap-api kairo-api kairo-groovy kairo-core kairo-agent-core
         kairo-agent-server kairo-agent-core-modern kairo-agent-bootstrap
         kairo-attach-cli kairo-ops kairo-platform-server kairo-sdk kairo-cli
         kairo-mcp kairo-demo kairo-integration-tests)

usage() {
  cat <<'EOF'
Usage: run-compatibility.sh --scenario <C01-C10> --output <row.json>
                            [--allow-dirty] [--help]

Required:
  --scenario    one of C01..C10 (the frozen M3-A catalog; section 10.1)
  --output      output file path for the row-evidence JSON
                (e.g. target/v1.7/compatibility-rows/C01.json)

Optional:
  --allow-dirty DEVELOPMENT ONLY - allow a dirty working tree (records mode=dev).
                Never use for PR/RC/RELEASE evidence; the fixed command refuses a
                dirty tree.
  --help        show this help

Behavior:
  - Builds the runner in place at HEAD (kairo-integration-tests -am test-compile);
    the active V1.7 worktree is never switched or dirtied.
  - Resolves and records the current 40-hex HEAD commit as the build ID.
  - PR evidence (default) refuses a dirty tracked/untracked working tree; use
    --allow-dirty only for local development (mode=dev).
  - Cleans only this runner's prior output file before running.
  - Runs the runner in a fresh JVM; the exact exit code is preserved.
  - In M3-A: formal scenarios (C01-C08, C10) emit NOT_RUN (exit 4); C09 emits
    EXPERIMENTAL (exit 0). No scenario is marked PASSED.
  - Never modifies the workflow, the acceptance manifest, or support conclusions.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --scenario) SCENARIO="$2"; shift 2 ;;
    --output) OUTPUT="$2"; shift 2 ;;
    --allow-dirty) ALLOW_DIRTY="true"; shift ;;
    --help|-h) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage; exit 1 ;;
  esac
done

if [[ -z "$SCENARIO" || -z "$OUTPUT" ]]; then
  echo "error: --scenario and --output are required" >&2
  usage
  exit 1
fi
if ! [[ "$SCENARIO" =~ ^C(0[1-9]|10)$ ]]; then
  echo "error: --scenario must be one of C01..C10 (got: $SCENARIO)" >&2
  exit 1
fi

# Resolve the current 40-hex HEAD commit (peeled to the commit object).
HEAD_ID="$(git -C "$REPO_ROOT" rev-parse "HEAD^{commit}")"
if ! [[ "$HEAD_ID" =~ ^[0-9a-f]{40}$ ]]; then
  echo "error: could not resolve a 40-hex HEAD commit (got: $HEAD_ID)" >&2
  exit 1
fi

# Dirty-tree detection. PR/RC/RELEASE evidence (default) refuses a dirty tree;
# --allow-dirty is a clearly-marked development-only escape that records mode=dev.
DIRTY="false"
if [[ -n "$(git -C "$REPO_ROOT" status --porcelain)" ]]; then
  DIRTY="true"
fi
if [[ "$DIRTY" == "true" && "$ALLOW_DIRTY" != "true" ]]; then
  echo "error: evidence refuses a dirty working tree." >&2
  echo "       Commit the runner first, or use --allow-dirty (DEVELOPMENT ONLY, mode=dev)." >&2
  exit 1
fi
MODE="pr"
if [[ "$ALLOW_DIRTY" == "true" ]]; then
  MODE="dev"
fi

# Contamination guard: clear ONLY this runner's prior output file, then recreate parent.
rm -f "$OUTPUT"
PARENT_DIR="$(dirname "$OUTPUT")"
mkdir -p "$PARENT_DIR"

printf -v EXACT_CMD '%q ' "$0" "${ORIGINAL_ARGS[@]}"
EXACT_CMD="${EXACT_CMD% }"

echo "==> scenario=$SCENARIO mode=$MODE working-tree-dirty=$DIRTY"
echo "==> head=$HEAD_ID"

# -----------------------------------------------------------------------------
# Build the runner in place at HEAD. Single side; no worktree, no dirty.
# -----------------------------------------------------------------------------
echo "==> building runner at $REPO_ROOT"
if ! (cd "$REPO_ROOT" && $MVN -B -ntp -pl kairo-integration-tests -am test-compile -q); then
  echo "error: runner build failed" >&2
  exit 2
fi

# -----------------------------------------------------------------------------
# Assemble the classpath: runner test-classes + reactor target/classes + ext deps.
# -----------------------------------------------------------------------------
reactor_classes() {
  local out=""
  for m in "${MODULES[@]}"; do
    local d="$REPO_ROOT/$m/target/classes"
    if [[ -d "$d" ]]; then out="$out:$d"; fi
  done
  echo "${out#:}"
}
EXTDEPS_FILE="$(mktemp -t kairo-compat-deps-XXXXXX)"
if ! (cd "$REPO_ROOT" && $MVN -B -ntp -pl kairo-integration-tests \
      dependency:build-classpath -Dmdep.outputFile="$EXTDEPS_FILE" -q >/dev/null 2>&1); then
  echo "error: dependency:build-classpath failed" >&2
  rm -f "$EXTDEPS_FILE"
  exit 2
fi
EXTDEPS="$(cat "$EXTDEPS_FILE")"
rm -f "$EXTDEPS_FILE"
CP="$REPO_ROOT/kairo-integration-tests/target/test-classes:$(reactor_classes):$EXTDEPS"

echo "==> running row runner (scenario=$SCENARIO)"
# The runner returns 3/4/5/6 on unusable/blocked/write/schema failure. Temporarily
# disable `-e` so those codes are captured (not turned into an early abort), then
# exit with the EXACT code. The row file is always written (best effort).
RUNNER_STATUS=0
set +e
"$JAVA_BIN" -cp "$CP" "$RUNNER_MAIN" \
  --scenario "$SCENARIO" \
  --output "$OUTPUT" \
  --build-id "$HEAD_ID" \
  --command "$EXACT_CMD" \
  --mode "$MODE" \
  --working-tree-dirty "$DIRTY"
RUNNER_STATUS=$?
set -e

echo "==> done (runner exit=$RUNNER_STATUS). row: $OUTPUT"
exit "$RUNNER_STATUS"
