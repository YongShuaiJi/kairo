#!/usr/bin/env bash
#
# scripts/v1.7/run-leak-check.sh
#
# V1.7 M2-C ClassLoader/Groovy leak-check harness runner (§9.3 / §9.5). Runs real
# create/enhance/invoke/unload/close lifecycle cycles distributed across the fixed
# five-scenario leak-surface matrix (now six: a Byte Buddy generated target class was
# added per the §9.3 coverage requirement), observes resource trends across stable windows,
# and writes target/<output>/leak-result.json.
#
# Fixed interface (§9.5):
#   ./scripts/v1.7/run-leak-check.sh --cycles 500 --output target/v1.7
#
# A cycle is one real lifecycle on one real unloadable business ClassLoader: compile
# (reused) / load / enhance via the real Byte Buddy / AgentRuntime path + a Groovy
# script rule (exercising the weak-reference compile cache and generation holder),
# invoke and verify the enhanced behaviour, unload the rule, close the loader, and
# track it with a weak reference. After all cycles the agent is closed and a bounded GC
# drains the ClassLoader reference queue; the harness reports residual loaders, the
# heap/Metaspace/thread/FD trends, and the bounded cache sizes against the documented
# §9.3 budgets.
#
# Exit codes (preserved exactly from the harness):
#   0  all gates passed and schema valid
#   1  usage / validation error (incl. dirty PR tree)
#   2  build failed
#   3  harness unusable
#   4  gate or lifecycle failure (firstFailure recorded in the result)
#   5  result-write / aggregation error
#   6  schema-validation failure
#
# The runner NEVER switches or dirties the active V1.7 worktree. It builds the harness
# in place (HEAD), runs it in a fresh JVM with fixed JVM args, preserves the exact
# harness exit code, and cleans only this runner's prior leak-check output. It never
# modifies budgets or the acceptance manifest.

set -euo pipefail
# Fail-fast is on (`-e`). The ONLY intentional non-fatal command is the harness
# invocation: it returns 4/5/6 on gate/write/schema failure, and we must capture that
# status explicitly and exit with the EXACT code (see the `set +e` block around the
# harness below) rather than abort early. Every other failing command (build /
# classpath) aborts immediately so it can never silently continue.

CYCLES=""
OUTPUT=""
JVM_ARGS="${KAIRO_LEAKCHECK_JVM_ARGS:--Xms512m -Xmx512m -XX:+AlwaysPreTouch}"
ALLOW_DIRTY="false"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA_BIN="${JAVA_BIN:-java}"
MVN="${MVN:-mvn}"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HARNESS_MAIN="com.example.kairo.perf.leak.LeakCheckHarness"
MODULES=(kairo-bootstrap-api kairo-api kairo-groovy kairo-core kairo-agent-core
         kairo-agent-server kairo-agent-core-modern kairo-agent-bootstrap
         kairo-attach-cli kairo-ops kairo-platform-server kairo-sdk kairo-cli
         kairo-mcp kairo-demo kairo-integration-tests)

usage() {
  cat <<'EOF'
Usage: run-leak-check.sh --cycles <N> --output <dir> [--jvm-args <args>]
                         [--allow-dirty] [--help]

Required:
  --cycles       total cycles to distribute across the scenario matrix (>= 6, so
                 every scenario runs at least once, including the Byte Buddy
                 generated class; NOT per-scenario repetitions)
  --output       output directory for leak-result.json (e.g. target/v1.7)

Optional:
  --jvm-args     fixed JVM args for the harness JVM (default: -Xms512m -Xmx512m -XX:+AlwaysPreTouch)
  --allow-dirty  DEVELOPMENT ONLY - allow a dirty working tree (records mode=dev).
                 Never use for PR evidence; the fixed PR command refuses a dirty tree.
  --help         show this help

Behavior:
  - Builds the harness in place at HEAD (kairo-integration-tests -am test-compile);
    the active V1.7 worktree is never switched or dirtied.
  - Resolves and records the current 40-hex HEAD commit as the build ID.
  - PR evidence (default) refuses a dirty tracked/untracked working tree; use
    --allow-dirty only for local development (mode=dev).
  - Cleans only this runner's prior leak-result.json before running.
  - Runs the harness in a fresh JVM with fixed JVM args; the exact harness exit
    code is preserved.
  - The harness writes target/<output>/leak-result.json even on failure
    (best effort) and self-validates its schema.
  - Observes: heap, Metaspace, threads, file descriptors (where supported), live
    ClassLoader weak references after bounded GC, loaded-class count, and the
    rule/snapshot/journal/instrumentation caches.
  - Never modifies budgets or the acceptance manifest.
  - The 10,000-cycle and two-hour soak gates belong to RC/M2-D, NOT this runner.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --cycles) CYCLES="$2"; shift 2 ;;
    --output) OUTPUT="$2"; shift 2 ;;
    --jvm-args) JVM_ARGS="$2"; shift 2 ;;
    --allow-dirty) ALLOW_DIRTY="true"; shift ;;
    --help|-h) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage; exit 1 ;;
  esac
done

# Validate inputs.
if [[ -z "$CYCLES" || -z "$OUTPUT" ]]; then
  echo "error: --cycles and --output are required" >&2
  usage
  exit 1
fi
if ! [[ "$CYCLES" =~ ^[0-9]+$ ]] || [[ "$CYCLES" -le 0 ]]; then
  echo "error: --cycles must be a positive integer (got: $CYCLES)" >&2
  exit 1
fi
if [[ "$CYCLES" -lt 6 ]]; then
  echo "error: --cycles must be >= 6 so every scenario runs at least once (got: $CYCLES)" >&2
  exit 1
fi
read -r -a JVM_ARGV <<< "$JVM_ARGS"

# Resolve the current 40-hex HEAD commit (peeled to the commit object).
HEAD_ID="$(git -C "$REPO_ROOT" rev-parse "HEAD^{commit}")"
if ! [[ "$HEAD_ID" =~ ^[0-9a-f]{40}$ ]]; then
  echo "error: could not resolve a 40-hex HEAD commit (got: $HEAD_ID)" >&2
  exit 1
fi

# Dirty-tree detection. PR evidence (default) refuses a dirty tree; --allow-dirty
# is a clearly-marked development-only escape that records mode=dev.
DIRTY="false"
if [[ -n "$(git -C "$REPO_ROOT" status --porcelain)" ]]; then
  DIRTY="true"
fi
if [[ "$DIRTY" == "true" && "$ALLOW_DIRTY" != "true" ]]; then
  echo "error: PR evidence refuses a dirty working tree." >&2
  echo "       Commit the harness first, or use --allow-dirty (DEVELOPMENT ONLY, mode=dev)." >&2
  exit 1
fi
MODE="pr"
if [[ "$ALLOW_DIRTY" == "true" ]]; then
  MODE="dev"
fi

OUTPUT_DIR="$(cd "$REPO_ROOT" && mkdir -p "$OUTPUT" && cd "$OUTPUT" && pwd)"

# Contamination guard: clear ONLY this runner's prior result, then recreate.
rm -f "$OUTPUT_DIR/leak-result.json"

EXACT_CMD="$(printf '%q' "$0") --cycles $CYCLES --output $OUTPUT"
if [[ "$ALLOW_DIRTY" == "true" ]]; then
  EXACT_CMD="$EXACT_CMD --allow-dirty"
fi

echo "==> cycles=$CYCLES mode=$MODE working-tree-dirty=$DIRTY"
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
EXTDEPS_FILE="$(mktemp -t kairo-leakcheck-deps-XXXXXX)"
if ! (cd "$REPO_ROOT" && $MVN -B -ntp -pl kairo-integration-tests \
      dependency:build-classpath -Dmdep.outputFile="$EXTDEPS_FILE" -q >/dev/null 2>&1); then
  echo "error: dependency:build-classpath failed" >&2
  rm -f "$EXTDEPS_FILE"
  exit 2
fi
EXTDEPS="$(cat "$EXTDEPS_FILE")"
rm -f "$EXTDEPS_FILE"
CP="$REPO_ROOT/kairo-integration-tests/target/test-classes:$(reactor_classes):$EXTDEPS"

echo "==> running leak-check harness"
# The harness returns 4/5/6 on gate/write/schema failure. Temporarily disable
# `-e` so those codes are captured (not turned into an early abort), then exit
# with the EXACT code. The result file is always written (best effort).
HARNESS_STATUS=0
set +e
"$JAVA_BIN" "${JVM_ARGV[@]}" -cp "$CP" "$HARNESS_MAIN" \
  --cycles "$CYCLES" \
  --output "$OUTPUT_DIR" \
  --build-id "$HEAD_ID" \
  --command "$EXACT_CMD" \
  --jvm-args "$JVM_ARGS" \
  --mode "$MODE" \
  --working-tree-dirty "$DIRTY"
HARNESS_STATUS=$?
set -e

echo "==> done (harness exit=$HARNESS_STATUS). result: $OUTPUT_DIR/leak-result.json"
exit "$HARNESS_STATUS"
