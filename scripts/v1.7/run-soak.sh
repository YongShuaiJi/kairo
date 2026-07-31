#!/usr/bin/env bash
#
# scripts/v1.7/run-soak.sh
#
# V1.7 M2-D long-running stability / soak harness runner (§9.4 / §9.5). Drives a real
# AgentRuntime under sustained load on a fixed cadence - a per-minute time-series summary,
# continuous real enhanced-target invocations, a 5-minute enhance/update/partial-unload/
# full-unload batch, and a 30-minute Agent/Platform disconnect/recovery - and writes
# target/<output>/soak-result.json plus a raw per-minute time-series file.
#
# Fixed interface (§9.5):
#   ./scripts/v1.7/run-soak.sh --duration PT2H --output target/v1.7      # RC
#   ./scripts/v1.7/run-soak.sh --duration P7D --output target/v1.7      # RELEASE (M6)
#
# Exit codes (preserved exactly from the harness):
#   0  soak completed the full duration with no stability/lifecycle failure and schema valid
#   1  usage / validation error (incl. dirty PR tree)
#   2  build failed
#   3  harness unusable
#   4  stability / lifecycle failure (firstFailure recorded in the result)
#   5  result-write / aggregation error
#   6  schema-validation failure
#
# The runner NEVER switches or dirties the active V1.7 worktree. It builds the harness in
# place (HEAD), runs it in a fresh JVM with fixed JVM args, preserves the exact harness exit
# code, and cleans only this runner's prior soak output. It never modifies budgets or the
# acceptance manifest. The harness uses a REAL Java/Maven harness and a REAL AgentRuntime
# lifecycle; it never uses a shell sleep to fake a lifecycle, never inflates a timeout, and
# never continues on error.

set -euo pipefail
# Fail-fast is on (`-e`). The ONLY intentional non-fatal command is the harness invocation: it
# returns 4/5/6 on stability/write/schema failure, and we must capture that status explicitly
# and exit with the EXACT code (see the `set +e` block around the harness below) rather than
# abort early. Every other failing command (build / classpath) aborts immediately so it can
# never silently continue.

DURATION=""
OUTPUT=""
JVM_ARGS="${KAIRO_SOAK_JVM_ARGS:--Xms1g -Xmx1g -XX:+AlwaysPreTouch}"
ALLOW_DIRTY="false"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA_BIN="${JAVA_BIN:-java}"
MVN="${MVN:-mvn}"
ORIGINAL_ARGS=("$@")

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HARNESS_MAIN="com.example.kairo.perf.soak.SoakHarness"
MODULES=(kairo-bootstrap-api kairo-api kairo-groovy kairo-core kairo-agent-core
         kairo-agent-server kairo-agent-core-modern kairo-agent-bootstrap
         kairo-attach-cli kairo-ops kairo-platform-server kairo-sdk kairo-cli
         kairo-mcp kairo-demo kairo-integration-tests)

usage() {
  cat <<'EOF'
Usage: run-soak.sh --duration <ISO-8601> --output <dir> [--jvm-args <args>]
                   [--allow-dirty] [--help]

Required:
  --duration    requested soak duration, ISO-8601 (e.g. PT2H for RC, P7D for RELEASE)
  --output      output directory for soak-result.json + soak-timeseries.jsonl (e.g. target/v1.7)

Optional:
  --jvm-args    fixed JVM args for the harness JVM (default: -Xms1g -Xmx1g -XX:+AlwaysPreTouch)
  --allow-dirty DEVELOPMENT ONLY - allow a dirty working tree (records mode=dev).
                Never use for PR/RC/RELEASE evidence; the fixed command refuses a dirty tree.
  --help        show this help

Behavior:
  - Builds the harness in place at HEAD (kairo-integration-tests -am test-compile);
    the active V1.7 worktree is never switched or dirtied.
  - Resolves and records the current 40-hex HEAD commit as the build ID.
  - PR evidence (default) refuses a dirty tracked/untracked working tree; use
    --allow-dirty only for local development (mode=dev).
  - Cleans only this runner's prior soak-result.json + soak-timeseries.jsonl before running.
  - Runs the harness in a fresh JVM with fixed JVM args; the exact harness exit code is
    preserved. The harness uses the production wall clock (real time) so the 1m/5m/30m
    cadence fires at genuine wall-clock intervals.
  - The harness writes target/<output>/soak-result.json even on failure (best effort) and
    self-validates its schema.
  - Observes (per minute): heap, Metaspace, threads, file descriptors (where supported),
    loaded-class count, and the agent's real bounded caches (rule/snapshot/journal/
    instrumentation), plus cumulative invocation/batch/disconnect counters and per-window
    breach/drift flags.
  - Never modifies budgets or the acceptance manifest.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --duration) DURATION="$2"; shift 2 ;;
    --output) OUTPUT="$2"; shift 2 ;;
    --jvm-args) JVM_ARGS="$2"; shift 2 ;;
    --allow-dirty) ALLOW_DIRTY="true"; shift ;;
    --help|-h) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage; exit 1 ;;
  esac
done

# Validate inputs.
if [[ -z "$DURATION" || -z "$OUTPUT" ]]; then
  echo "error: --duration and --output are required" >&2
  usage
  exit 1
fi
# Light ISO-8601 duration shape check. The harness parser (java.time.Duration.parse) is the
# authority for semantic validity; this only catches obvious typos (non-P inputs) before the
# build. It accepts the documented PT2H / P7D / PT35M forms and rejects garbage like "2hours".
if ! [[ "$DURATION" =~ ^P[0-9YMWDHST]+$ ]] || ! [[ "$DURATION" =~ [0-9] ]]; then
  echo "error: --duration must be an ISO-8601 duration (e.g. PT2H, P7D, PT35M; got: $DURATION)" >&2
  exit 1
fi
read -r -a JVM_ARGV <<< "$JVM_ARGS"

# Resolve the current 40-hex HEAD commit (peeled to the commit object).
HEAD_ID="$(git -C "$REPO_ROOT" rev-parse "HEAD^{commit}")"
if ! [[ "$HEAD_ID" =~ ^[0-9a-f]{40}$ ]]; then
  echo "error: could not resolve a 40-hex HEAD commit (got: $HEAD_ID)" >&2
  exit 1
fi

# Dirty-tree detection. PR/RC/RELEASE evidence (default) refuses a dirty tree; --allow-dirty
# is a clearly-marked development-only escape that records mode=dev.
DIRTY="false"
if [[ -n "$(git -C "$REPO_ROOT" status --porcelain)" ]]; then
  DIRTY="true"
fi
if [[ "$DIRTY" == "true" && "$ALLOW_DIRTY" != "true" ]]; then
  echo "error: evidence refuses a dirty working tree." >&2
  echo "       Commit the harness first, or use --allow-dirty (DEVELOPMENT ONLY, mode=dev)." >&2
  exit 1
fi
MODE="pr"
if [[ "$ALLOW_DIRTY" == "true" ]]; then
  MODE="dev"
fi

OUTPUT_DIR="$(cd "$REPO_ROOT" && mkdir -p "$OUTPUT" && cd "$OUTPUT" && pwd)"

# Contamination guard: clear ONLY this runner's prior result + raw time-series, then recreate.
rm -f "$OUTPUT_DIR/soak-result.json" "$OUTPUT_DIR/soak-timeseries.jsonl"

printf -v EXACT_CMD '%q ' "$0" "${ORIGINAL_ARGS[@]}"
EXACT_CMD="${EXACT_CMD% }"

echo "==> duration=$DURATION mode=$MODE working-tree-dirty=$DIRTY"
echo "==> head=$HEAD_ID"
echo "==> jvm-args: $JVM_ARGS"

# -----------------------------------------------------------------------------
# Build the harness in place at HEAD. Single side; no worktree, no dirty.
# -----------------------------------------------------------------------------
echo "==> building harness at $REPO_ROOT"
if ! (cd "$REPO_ROOT" && $MVN -B -ntp -pl kairo-integration-tests -am test-compile -q); then
  echo "error: harness build failed" >&2
  exit 2
fi

# -----------------------------------------------------------------------------
# Assemble the classpath: harness test-classes + reactor target/classes + ext deps.
# -----------------------------------------------------------------------------
reactor_classes() {
  local out=""
  for m in "${MODULES[@]}"; do
    local d="$REPO_ROOT/$m/target/classes"
    if [[ -d "$d" ]]; then out="$out:$d"; fi
  done
  echo "${out#:}"
}
EXTDEPS_FILE="$(mktemp -t kairo-soak-deps-XXXXXX)"
if ! (cd "$REPO_ROOT" && $MVN -B -ntp -pl kairo-integration-tests \
      dependency:build-classpath -Dmdep.outputFile="$EXTDEPS_FILE" -q >/dev/null 2>&1); then
  echo "error: dependency:build-classpath failed" >&2
  rm -f "$EXTDEPS_FILE"
  exit 2
fi
EXTDEPS="$(cat "$EXTDEPS_FILE")"
rm -f "$EXTDEPS_FILE"
CP="$REPO_ROOT/kairo-integration-tests/target/test-classes:$(reactor_classes):$EXTDEPS"

echo "==> running soak harness (duration=$DURATION)"
# The harness returns 4/5/6 on stability/write/schema failure. Temporarily disable `-e` so
# those codes are captured (not turned into an early abort), then exit with the EXACT code.
# The result file + raw time-series are always written (best effort).
HARNESS_STATUS=0
set +e
"$JAVA_BIN" "${JVM_ARGV[@]}" -cp "$CP" "$HARNESS_MAIN" \
  --duration "$DURATION" \
  --output "$OUTPUT_DIR" \
  --build-id "$HEAD_ID" \
  --command "$EXACT_CMD" \
  --jvm-args "$JVM_ARGS" \
  --mode "$MODE" \
  --working-tree-dirty "$DIRTY"
HARNESS_STATUS=$?
set -e

echo "==> done (harness exit=$HARNESS_STATUS). result: $OUTPUT_DIR/soak-result.json"
exit "$HARNESS_STATUS"
