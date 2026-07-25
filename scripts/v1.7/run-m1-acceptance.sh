#!/usr/bin/env bash
#
# V1.7 M1 PR acceptance runner (roadmap §5.2 / §8.8).
#
# Runs, in order, the M1 focused recovery tests, the full Maven reactor test,
# package verification, the Compose/attach verify-only check, and the Web
# lint/typecheck/test/build. It fails fast: the first failing command stops the
# run. It NEVER fabricates evidence — every step's real exit code is recorded to
# target/v1.7/runner-outcomes.jsonl, and the dependency-free evidence generator
# is invoked (on success or on first failure) to write
# target/v1.7/recovery-result.json strictly from those outcomes and the actual
# Surefire XML reports.
#
# This runner only produces PR evidence; RC and RELEASE remain NOT_RUN and are
# certified independently. It does not modify v1.7-acceptance-manifest.json.
#
# Usage:
#   scripts/v1.7/run-m1-acceptance.sh            # full PR acceptance
#   scripts/v1.7/run-m1-acceptance.sh --skip-web # skip the Web phase
#   scripts/v1.7/run-m1-acceptance.sh --only focused-tests,reactor-test
#
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

OUT_DIR="$ROOT_DIR/target/v1.7"
OUTCOMES="$OUT_DIR/runner-outcomes.jsonl"
GENERATOR="$ROOT_DIR/scripts/v1.7/generate-recovery-evidence.py"

# M1-A .. M1-G focused recovery tests + the M1 end-to-end closed-loop test.
FOCUSED_TESTS="AgentCommandLeaseIntegrationTest,\
AgentCommandAckFencingIntegrationTest,\
PlatformCommandPollerEpochTest,\
AgentCommandClassificationTest,\
PlatformRestartRecoveryIntegrationTest,\
TransientCommandRestartIntegrationTest,\
CompletedCommandNoReplayIntegrationTest,\
RuntimeStateSnapshotTest,\
PlatformRuntimeStateCommandTest,\
RuntimeStateSnapshotPersistenceIntegrationTest,\
AgentReconnectReconciliationIntegrationTest,\
JvmRestartReapplyIntegrationTest,\
ExpiredTrialDoesNotReviveIntegrationTest,\
DivergedStateFailClosedIntegrationTest,\
OfflineAgentUnloadCompensationIntegrationTest,\
AgentGoneIsNotUnloadedIntegrationTest,\
MultiTargetPartialFailureIntegrationTest,\
UnloadRetryIdempotencyIntegrationTest,\
RealJvmDisconnectUnloadIntegrationTest,\
DependencyHealthRecoveryIntegrationTest,\
RedisFencingFailureIntegrationTest,\
EmergencyOpsWithoutPlatformIntegrationTest,\
PostEmergencyReconciliationIntegrationTest,\
ApplicationRollbackGuardIntegrationTest,\
ModuleBoundaryConvergenceTest,\
ProductEntrypointInventoryTest,\
ObjectRuntimeCompatibilityTest,\
AttachExecutorCompatibilityTest,\
M1ClosedLoopRecoveryIntegrationTest"

WEB_DIR="$ROOT_DIR/kairo-platform-web"

mkdir -p "$OUT_DIR"
: > "$OUTCOMES"

iso_now() { date -u +%Y-%m-%dT%H:%M:%SZ; }

# record <step> <command> <exitCode> <startedAt> <endedAt>
record() {
  # The focused-test step runs Maven clean, which removes the repository-level
  # target directory after this runner initializes it. Recreate the evidence
  # directory immediately before every append so the real outcome is never lost.
  mkdir -p "$OUT_DIR"
  python3 - "$1" "$2" "$3" "$4" "$5" >> "$OUTCOMES" <<'PY'
import json, sys
print(json.dumps({
    "step": sys.argv[1],
    "command": sys.argv[2],
    "exitCode": int(sys.argv[3]),
    "startedAt": sys.argv[4],
    "endedAt": sys.argv[5],
}))
PY
}

# run_step <step-name> <command...>: runs the command, records the real outcome,
# and on non-zero exit invokes the evidence generator and exits with that code.
run_step() {
  local name="$1"; shift
  local cmd_str="$*"
  local started ended code
  started="$(iso_now)"
  set +e
  ( "$@" )
  code=$?
  set -e
  ended="$(iso_now)"
  record "$name" "$cmd_str" "$code" "$started" "$ended"
  if [ "$code" -ne 0 ]; then
    echo ">> M1 acceptance FAILED at step '$name' (exit $code). Recording evidence and stopping." >&2
    set +e
    generate_evidence
    local evidence_code=$?
    set -e
    if [ "$evidence_code" -ne 0 ]; then
      echo ">> evidence generation also failed (exit $evidence_code)." >&2
    fi
    exit "$code"
  fi
}

generate_evidence() {
  if [ ! -x "$GENERATOR" ]; then
    echo ">> evidence generator not found or not executable: $GENERATOR" >&2
    return 1
  fi
  python3 "$GENERATOR" --root "$ROOT_DIR" --output "$OUT_DIR/recovery-result.json"
}

SKIP_WEB=0
ONLY=""
while [ $# -gt 0 ]; do
  case "$1" in
    --skip-web) SKIP_WEB=1; shift;;
    --only) ONLY="$2"; shift 2;;
    -h|--help)
      sed -n '2,30p' "$0"; exit 0;;
    *) echo "unknown arg: $1" >&2; exit 2;;
  esac
done

want() { [ -z "$ONLY" ] || case ",$ONLY," in *,"$1",*) return 0;; *) return 1;; esac; }

echo ">> V1.7 M1 PR acceptance — evidence dir: $OUT_DIR"

if want focused-tests; then
  echo ">> [1/6] M1 focused recovery tests (M1-A..M1-G + closed loop)"
  run_step focused-tests \
    mvn -B -ntp clean test \
      -Dtest="$FOCUSED_TESTS" \
      -Dsurefire.failIfNoSpecifiedTests=false
fi

if want reactor-test; then
  echo ">> [2/6] full Maven reactor test"
  run_step reactor-test mvn -B -ntp test
fi

if want package; then
  echo ">> [3/6] package verification"
  run_step package mvn -B -ntp -DskipTests package
fi

if want compose-verify; then
  echo ">> [4/6] Compose / attach verify-only"
  run_step compose-verify "$ROOT_DIR/scripts/up-demo-attach.sh" --verify-only
fi

if [ "$SKIP_WEB" -eq 0 ]; then
  if want web-lint; then
    echo ">> [5/6] Web lint"
    run_step web-lint bash -lc "cd '$WEB_DIR' && npm run lint"
  fi
  if want web-typecheck; then
    echo ">> [5/6] Web typecheck"
    run_step web-typecheck bash -lc "cd '$WEB_DIR' && npm run typecheck"
  fi
  if want web-test; then
    echo ">> [5/6] Web test"
    run_step web-test bash -lc "cd '$WEB_DIR' && npm run test"
  fi
  if want web-build; then
    echo ">> [6/6] Web build"
    run_step web-build bash -lc "cd '$WEB_DIR' && npm run build"
  fi
fi

echo ">> all requested M1 acceptance steps passed; generating evidence."
generate_evidence
echo ">> done: $OUT_DIR/recovery-result.json"
