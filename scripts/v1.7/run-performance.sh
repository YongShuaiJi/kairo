#!/usr/bin/env bash
#
# scripts/v1.7/run-performance.sh
#
# V1.7 M2-A performance comparison harness. Runs the deterministic benchmark
# scenarios (see com.example.kairo.perf.ScenarioCatalog) against a baseline git
# ref and a candidate git ref on the SAME machine with the SAME JDK/JVM args and
# the SAME harness, then aggregates raw samples, validates the schema and checks
# the tracked budget, writing target/<out>/benchmark-result.json.
#
# Fixed interface (§9.5):
#   ./scripts/v1.7/run-performance.sh --mode pr --baseline V1.6.0 --candidate HEAD --output target/v1.7
#
# Exit codes:
#   0  budget passed and schema valid
#   1  usage / validation error
#   2  baseline build failed
#   3  candidate build failed (or candidate harness unusable, e.g. --list fails
#      or returns zero scenarios — the candidate ref lacks the M2-A harness)
#   4  harness fork failed
#   5  reporter/aggregation error
#   6  schema validation failure
#   7  budget failure
#
# The runner NEVER switches or dirties the active V1.7 worktree. The baseline (and
# any non-HEAD candidate) is built in a disposable, bounded-temporary git worktree
# that is unregistered and removed on exit. The budget file is read only — never
# rewritten or relaxed.

set -euo pipefail
# Fail-fast is on (`-e`). The ONLY intentional non-fatal command is the reporter
# invocation: it returns 5/6/7 on harness/schema/budget failure, and we must
# capture that status explicitly and exit with the EXACT code (see the `set +e`
# block around the reporter below) rather than abort early. Every other failing
# command (worktree-add / build / classpath / fork) aborts immediately so it can
# never silently continue.

MODE=""
BASELINE=""
CANDIDATE=""
OUTPUT=""
JVM_ARGS="${KAIRO_PERF_JVM_ARGS:--Xms512m -Xmx512m -XX:+AlwaysPreTouch}"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA_BIN="${JAVA_BIN:-java}"
MVN="${MVN:-mvn}"

# PR mode: 5 forks (real process isolation). Smoke mode: 1 fork, fewer iterations.
PR_FORKS=5
PR_WARMUP=5
PR_MEASURE=20
SMOKE_FORKS=1
SMOKE_WARMUP=2
SMOKE_MEASURE=5

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
BUDGET_FILE="$REPO_ROOT/v1.7-performance-budget.json"
HARNESS_MAIN="com.example.kairo.perf.HarnessMain"
REPORTER_MAIN="com.example.kairo.perf.PerformanceReporter"
MODULES=(kairo-bootstrap-api kairo-api kairo-groovy kairo-core kairo-agent-core
         kairo-agent-server kairo-agent-core-modern kairo-agent-bootstrap
         kairo-attach-cli kairo-ops kairo-platform-server kairo-sdk kairo-cli
         kairo-mcp kairo-demo kairo-integration-tests)

# Disposable worktrees created during this run. Tracked so the EXIT trap can
# unregister them BEFORE deleting the bounded temp root (correct order matters:
# `git worktree remove` refuses a path that no longer exists).
declare -a CREATED_WORKTREES=()
TMPDIR_BASE=""

usage() {
  cat <<'EOF'
Usage: run-performance.sh --mode <pr|smoke> --baseline <git-ref> --candidate <git-ref> --output <dir>
                     [--jvm-args <args>] [--help]

Required:
  --mode       pr (>=5 forks, full measurement) or smoke (1 fork, short — development only)
  --baseline   git ref to build as baseline (e.g. V1.6.0; annotated tags are peeled to commits)
  --candidate  git ref to build as candidate (e.g. HEAD)
  --output     output directory (e.g. target/v1.7)

Optional:
  --jvm-args   fixed JVM args for every fork (default: -Xms512m -Xmx512m -XX:+AlwaysPreTouch)
  --help       show this help

Behavior:
  - Baseline (and any non-HEAD candidate) is built in a disposable git worktree
    under a bounded temp dir; the active V1.7 worktree is never switched or dirtied.
  - The SAME harness (built from the candidate) drives both builds; only the kairo
    implementation classes differ.
  - Refs are peeled with `^{commit}` so annotated tags record the actual checkout
    commit (40-hex), not the tag object.
  - In PR mode, a dirty working tree with --candidate HEAD fails BEFORE building
    (uncommitted code must never be bound to HEAD PR evidence). Smoke may run dirty.
  - At the start of each run, this runner's <output>/perf/raw/{baseline,candidate}
    and old benchmark-result.json are cleared, so prior runs cannot contaminate
    evidence.
  - Resolved build IDs, exact build/harness commands and classpaths, and environment
    are recorded in the result.
  - The tracked v1.7-performance-budget.json is READ ONLY.
  - Exit non-zero on harness/schema/budget failure.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --mode) MODE="$2"; shift 2 ;;
    --baseline) BASELINE="$2"; shift 2 ;;
    --candidate) CANDIDATE="$2"; shift 2 ;;
    --output) OUTPUT="$2"; shift 2 ;;
    --jvm-args) JVM_ARGS="$2"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage; exit 1 ;;
  esac
done

# Validate inputs.
if [[ -z "$MODE" || -z "$BASELINE" || -z "$CANDIDATE" || -z "$OUTPUT" ]]; then
  echo "error: --mode, --baseline, --candidate and --output are all required" >&2
  usage
  exit 1
fi
if [[ "$MODE" != "pr" && "$MODE" != "smoke" ]]; then
  echo "error: --mode must be 'pr' or 'smoke' (got: $MODE)" >&2
  exit 1
fi
read -r -a JVM_ARGV <<< "$JVM_ARGS"
if [[ ! -f "$BUDGET_FILE" ]]; then
  echo "error: budget file not found: $BUDGET_FILE" >&2
  exit 1
fi
# Verify refs exist (resolve to any object first).
if ! git -C "$REPO_ROOT" rev-parse --verify "$BASELINE^{commit}" >/dev/null 2>&1 \
   && ! git -C "$REPO_ROOT" rev-parse --verify "$BASELINE" >/dev/null 2>&1; then
  echo "error: baseline git ref not found: $BASELINE" >&2
  exit 1
fi
if ! git -C "$REPO_ROOT" rev-parse --verify "$CANDIDATE^{commit}" >/dev/null 2>&1 \
   && ! git -C "$REPO_ROOT" rev-parse --verify "$CANDIDATE" >/dev/null 2>&1; then
  echo "error: candidate git ref not found: $CANDIDATE" >&2
  exit 1
fi

if [[ "$MODE" == "pr" ]]; then
  FORKS=$PR_FORKS; WARMUP=$PR_WARMUP; MEASURE=$PR_MEASURE
else
  FORKS=$SMOKE_FORKS; WARMUP=$SMOKE_WARMUP; MEASURE=$SMOKE_MEASURE
fi

# Resolve build IDs by PEELING to the commit object (^{commit}). For an annotated
# tag like V1.6.0, `git rev-parse V1.6.0` returns the tag object, not the commit;
# `^{commit}` dereferences it to the actual checkout commit (40-hex).
BASE_ID="$(git -C "$REPO_ROOT" rev-parse "$BASELINE^{commit}")"
CAND_ID="$(git -C "$REPO_ROOT" rev-parse "$CANDIDATE^{commit}")"

# Candidate dirty-tree detection (only meaningful when candidate == active HEAD).
CAND_DIRTY="false"
CAND_IS_HEAD="false"
ACTIVE_HEAD_ID="$(git -C "$REPO_ROOT" rev-parse "HEAD^{commit}")"
if [[ "$CAND_ID" == "$ACTIVE_HEAD_ID" ]]; then
  CAND_IS_HEAD="true"
  if [[ -n "$(git -C "$REPO_ROOT" status --porcelain)" ]]; then
    CAND_DIRTY="true"
  fi
fi

# PR guard: never bind uncommitted code to HEAD PR evidence.
if [[ "$MODE" == "pr" && "$CAND_IS_HEAD" == "true" && "$CAND_DIRTY" == "true" ]]; then
  echo "error: PR mode refuses a dirty working tree with --candidate HEAD." >&2
  echo "       Commit the harness first, or use --mode smoke for development." >&2
  exit 1
fi

OUTPUT_DIR="$(cd "$REPO_ROOT" && mkdir -p "$OUTPUT" && cd "$OUTPUT" && pwd)"
RAW_BASE="$OUTPUT_DIR/perf/raw/baseline"
RAW_CAND="$OUTPUT_DIR/perf/raw/candidate"

# Contamination guard: clear ONLY this runner's raw dirs and the old result at the
# start of every run, then recreate. A prior 5-fork run followed by smoke must not
# leave fork2..5 raw files.
rm -rf "$RAW_BASE" "$RAW_CAND" "$OUTPUT_DIR/benchmark-result.json"
mkdir -p "$RAW_BASE" "$RAW_CAND"

TMPDIR_BASE="$(mktemp -d -t kairo-perf-XXXXXX)"

cleanup() {
  # Unregister every disposable worktree BEFORE deleting the temp root. Order
  # matters: `git worktree remove` needs the path to still exist.
  for wt in "${CREATED_WORKTREES[@]}"; do
    git -C "$REPO_ROOT" worktree remove --force "$wt" >/dev/null 2>&1 || true
  done
  rm -rf "$TMPDIR_BASE"
}
trap cleanup EXIT

echo "==> mode=$MODE forks=$FORKS warmup=$WARMUP measure=$MEASURE"
echo "==> baseline=$BASELINE -> commit $BASE_ID"
echo "==> candidate=$CANDIDATE -> commit $CAND_ID (is-head=$CAND_IS_HEAD, dirty=$CAND_DIRTY)"
echo "==> jvm-args: $JVM_ARGS"

# -----------------------------------------------------------------------------
# Build a classpath: harness test-classes (always from candidate) + a build's
# reactor target/classes + that build's external dependency classpath.
# -----------------------------------------------------------------------------
shell_join() {
  local result="" arg quoted
  for arg in "$@"; do
    printf -v quoted '%q' "$arg"
    result+="${result:+ }$quoted"
  done
  printf '%s' "$result"
}

reactor_classes() {
  local wt="$1" out=""
  for m in "${MODULES[@]}"; do
    local d="$wt/$m/target/classes"
    if [[ -d "$d" ]]; then out="$out:$d"; fi
  done
  echo "${out#:}"
}

build_extdeps() {
  # Writes the external dependency classpath to stdout for the given worktree.
  local wt="$1" outfile="$2"
  "$MVN" -B -ntp -f "$wt/pom.xml" -pl kairo-integration-tests \
    dependency:build-classpath -Dmdep.outputFile="$outfile" -q >/dev/null 2>&1 || {
    echo "error: dependency:build-classpath failed for $wt" >&2
    return 1
  }
  cat "$outfile"
}

# -----------------------------------------------------------------------------
# Build the candidate. HEAD -> active worktree (in-place); otherwise a disposable
# worktree checked out at the peeled commit.
# -----------------------------------------------------------------------------
CAND_WT="$REPO_ROOT"
if [[ "$CAND_IS_HEAD" != "true" ]]; then
  CAND_WT="$TMPDIR_BASE/candidate"
  mkdir -p "$CAND_WT"
  if ! git -C "$REPO_ROOT" worktree add --detach "$CAND_WT" "$CAND_ID" >/dev/null; then
    echo "error: candidate worktree add failed at $CAND_ID" >&2
    exit 3
  fi
  CREATED_WORKTREES+=("$CAND_WT")
fi
CAND_BUILD_CMD="cd $(shell_join "$CAND_WT") && $(shell_join "$MVN" -B -ntp -pl kairo-integration-tests -am test-compile -q)"

echo "==> building candidate at $CAND_WT"
if ! (cd "$CAND_WT" && $MVN -B -ntp -pl kairo-integration-tests -am test-compile -q); then
  echo "error: candidate build failed" >&2
  exit 3
fi
CAND_EXT="$(build_extdeps "$CAND_WT" "$TMPDIR_BASE/cand-deps.txt")" || {
  echo "error: candidate dependency classpath build failed" >&2
  exit 3
}
CAND_CP="$CAND_WT/kairo-integration-tests/target/test-classes:$(reactor_classes "$CAND_WT"):$CAND_EXT"

# The scenario list comes from the harness itself (single source of truth). Run
# --list status-checked: a non-HEAD candidate that predates the M2-A harness
# (e.g. V1.6.0) has no HarnessMain in its test-classes, so --list fails and the
# list is empty. In that case fail clearly with the candidate/harness failure
# code — NEVER dereference SCENARIOS[0] when the list is empty (unbound var).
SCENARIOS=()
LIST_STATUS=0
LIST_OUT="$("$JAVA_BIN" -cp "$CAND_CP" "$HARNESS_MAIN" --list 2>/dev/null)" || LIST_STATUS=$?
if [[ "$LIST_STATUS" -ne 0 ]]; then
  echo "error: candidate harness --list failed (exit $LIST_STATUS);" >&2
  echo "       the candidate ref ($CANDIDATE @ $CAND_ID) does not contain a usable M2-A harness" >&2
  exit 3
fi
while IFS= read -r line; do
  [[ -n "$line" ]] && SCENARIOS+=("$line")
done <<< "$LIST_OUT"
if [[ "${#SCENARIOS[@]}" -eq 0 ]]; then
  echo "error: candidate harness --list returned zero scenarios;" >&2
  echo "       the candidate ref ($CANDIDATE @ $CAND_ID) does not contain a usable M2-A harness" >&2
  exit 3
fi

# -----------------------------------------------------------------------------
# Build the baseline in a disposable worktree at the peeled baseline commit.
# -----------------------------------------------------------------------------
BASE_WT="$TMPDIR_BASE/baseline"
mkdir -p "$BASE_WT"
if ! git -C "$REPO_ROOT" worktree add --detach "$BASE_WT" "$BASE_ID" >/dev/null; then
  echo "error: baseline worktree add failed at $BASE_ID" >&2
  exit 2
fi
CREATED_WORKTREES+=("$BASE_WT")
BASE_BUILD_CMD="$(shell_join "$MVN" -B -ntp -f "$BASE_WT/pom.xml" -pl kairo-integration-tests -am test-compile -q)"
echo "==> building baseline ($BASELINE @ $BASE_ID) at $BASE_WT"
if ! $MVN -B -ntp -f "$BASE_WT/pom.xml" -pl kairo-integration-tests -am test-compile -q; then
  echo "error: baseline build failed" >&2
  exit 2
fi
BASE_EXT="$(build_extdeps "$BASE_WT" "$TMPDIR_BASE/base-deps.txt")" || {
  echo "error: baseline dependency classpath build failed" >&2
  exit 2
}
# Baseline CP uses the candidate's harness test-classes + baseline impl classes.
BASE_CP="$CAND_WT/kairo-integration-tests/target/test-classes:$(reactor_classes "$BASE_WT"):$BASE_EXT"

# Exact harness command (no placeholders). Record the literal invocation actually
# used for one concrete fork (scenario[0], fork 1) with the real classpath, so the
# evidence shows the exact java/args/main/flags. The per-scenario/per-fork values
# vary, but the command SHAPE, JVM args, main class, classpath and flags are fixed
# across all forks of a side — this one is representative and contains no <...>.
FIRST_SCENARIO="${SCENARIOS[0]}"
BASE_HARNESS_CMD="$(shell_join "$JAVA_BIN" "${JVM_ARGV[@]}" -cp "$BASE_CP" "$HARNESS_MAIN" --scenario "$FIRST_SCENARIO" --warmup "$WARMUP" --measure "$MEASURE" --fork 1 --out "$RAW_BASE/${FIRST_SCENARIO}-fork1.json" --build-id "$BASE_ID" --build-label baseline)"
CAND_HARNESS_CMD="$(shell_join "$JAVA_BIN" "${JVM_ARGV[@]}" -cp "$CAND_CP" "$HARNESS_MAIN" --scenario "$FIRST_SCENARIO" --warmup "$WARMUP" --measure "$MEASURE" --fork 1 --out "$RAW_CAND/${FIRST_SCENARIO}-fork1.json" --build-id "$CAND_ID" --build-label candidate)"

# -----------------------------------------------------------------------------
# Run forks: baseline then candidate, same JVM args, same harness.
# -----------------------------------------------------------------------------
run_forks() {
  local label="$1" cp="$2" build_id="$3" raw_dir="$4"
  echo "==> running $label ($FORKS forks x ${#SCENARIOS[@]} scenarios)"
  for scenario in "${SCENARIOS[@]}"; do
    for ((fork=1; fork<=FORKS; fork++)); do
      local out="$raw_dir/$scenario-fork$fork.json"
      if ! "$JAVA_BIN" "${JVM_ARGV[@]}" -cp "$cp" "$HARNESS_MAIN" \
          --scenario "$scenario" --warmup "$WARMUP" --measure "$MEASURE" \
          --fork "$fork" --out "$out" \
          --build-id "$build_id" --build-label "$label"; then
        echo "error: harness fork failed: $label scenario=$scenario fork=$fork" >&2
        exit 4
      fi
    done
  done
}

run_forks "baseline" "$BASE_CP" "$BASE_ID" "$RAW_BASE"
run_forks "candidate" "$CAND_CP" "$CAND_ID" "$RAW_CAND"

# -----------------------------------------------------------------------------
# Verify exact raw-file counts: every scenario must have exactly FORKS valid fork
# files on each side. Stale/missing files fail evidence.
# -----------------------------------------------------------------------------
expected_per_side=$(( ${#SCENARIOS[@]} * FORKS ))
base_count=$(find "$RAW_BASE" -name '*.json' | wc -l | tr -d ' ')
cand_count=$(find "$RAW_CAND" -name '*.json' | wc -l | tr -d ' ')
if [[ "$base_count" -ne "$expected_per_side" ]]; then
  echo "error: baseline raw file count=$base_count != expected $expected_per_side (${#SCENARIOS[@]} scenarios x $FORKS forks)" >&2
  exit 5
fi
if [[ "$cand_count" -ne "$expected_per_side" ]]; then
  echo "error: candidate raw file count=$cand_count != expected $expected_per_side (${#SCENARIOS[@]} scenarios x $FORKS forks)" >&2
  exit 5
fi
echo "==> raw files: baseline=$base_count candidate=$cand_count (expected $expected_per_side each)"

# -----------------------------------------------------------------------------
# Aggregate, validate, budget-check, write benchmark-result.json.
# Capture reporter status explicitly (no `set -e`), then exit with that exact code.
# -----------------------------------------------------------------------------
META_JSON=$(cat <<EOF
{"mainClass":"$HARNESS_MAIN","forks":$FORKS,"warmupIterations":$WARMUP,"measurementIterations":$MEASURE,"candidateWorkingTreeDirty":$CAND_DIRTY,"candidateIsHead":$CAND_IS_HEAD}
EOF
)

echo "==> aggregating and checking budget"
# The reporter returns 5/6/7 on harness/schema/budget failure. Temporarily
# disable `-e` so those codes are captured (not turned into an early abort),
# then re-enable. The final result line is always printed.
REPORTER_STATUS=0
set +e
"$JAVA_BIN" -cp "$CAND_CP" "$REPORTER_MAIN" \
  --mode "$MODE" \
  --budget "$BUDGET_FILE" \
  --baseline-raw "$RAW_BASE" --baseline-build-id "$BASE_ID" \
  --baseline-label "$BASELINE" --baseline-source-ref "$BASELINE" \
  --baseline-build-command "$BASE_BUILD_CMD" \
  --baseline-harness-command "$BASE_HARNESS_CMD" \
  --baseline-classpath "$BASE_CP" \
  --candidate-raw "$RAW_CAND" --candidate-build-id "$CAND_ID" \
  --candidate-label "$CANDIDATE" --candidate-source-ref "$CANDIDATE" \
  --candidate-build-command "$CAND_BUILD_CMD" \
  --candidate-harness-command "$CAND_HARNESS_CMD" \
  --candidate-classpath "$CAND_CP" \
  --jvm-args "$JVM_ARGS" \
  --harness-meta "$META_JSON" \
  --output "$OUTPUT_DIR/benchmark-result.json"
REPORTER_STATUS=$?
set -e

echo "==> done (reporter exit=$REPORTER_STATUS). result: $OUTPUT_DIR/benchmark-result.json"
exit "$REPORTER_STATUS"
