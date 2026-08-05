#!/usr/bin/env bash
#
# scripts/v1.7/aggregate-rc-certification.sh
#
# V1.7 M6-A (roadmap §13.2.1): the RC certification aggregator. Consumes the frozen
# evidence produced by the M1/M2/M3/M6-A runners + the maintainer-owned defect inventory,
# binds every sub-result to one immutable 40-hex candidate buildId, and writes the single
# target/v1.7/rc-certification-result.json.
#
# Fixed interface (roadmap §13.2):
#   ./scripts/v1.7/aggregate-rc-certification.sh \
#     --build-id <40-hex> \
#     --recovery target/v1.7/recovery-result.json \
#     --upgrade target/v1.7/upgrade/upgrade-rehearsal-result.json \
#     --compatibility target/v1.7/compatibility-result.json \
#     --state-cycle target/v1.7/state-cycle-result.json \
#     --soak target/v1.7/rc-soak/soak-result.json \
#     --defects v1.7-defect-inventory.json \
#     --output target/v1.7/rc-certification-result.json \
#     --evidence-root target/v1.7
#
# Exit codes:
#   0  aggregate valid: every sub-result is PASSED, bound to one buildId, zero open P0/P1
#   1  usage / validation error
#   4  aggregate FAILED (fail-closed): missing/malformed/dev/dirty/shortened/accelerated/
#      mixed-commit evidence, missing defect inventory, open P0/P1, or path-traversal
#
# The aggregator NEVER fabricates evidence, NEVER rewrites historical gate evidence, and
# NEVER modifies the acceptance manifest (promotion is the separate promote-rc-gates.sh).
# It performs no network downloads and never dirties the active V1.7 worktree.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LIB="$REPO_ROOT/scripts/v1.7/lib/rc_certlib.py"

usage() {
  cat <<'EOF'
Usage: aggregate-rc-certification.sh --build-id <40-hex>
       --recovery <recovery-result.json>
       --upgrade <upgrade-rehearsal-result.json>
       --compatibility <compatibility-result.json>
       --state-cycle <state-cycle-result.json>
       --soak <soak-result.json>
       --defects <v1.7-defect-inventory.json>
       --output <rc-certification-result.json>
       --evidence-root <approved-root> [--help]

Required:
  --build-id       the frozen 40-hex candidate commit every sub-result must bind to.
  --recovery        recovery-result.json (run-m1-acceptance.sh on the candidate)
  --upgrade         upgrade-rehearsal-result.json (run-upgrade-rehearsal.sh)
  --compatibility   compatibility-result.json (aggregate-compatibility.sh)
  --state-cycle     state-cycle-result.json (run-state-cycle.sh --cycles 10000)
  --soak            soak-result.json (run-soak.sh --duration PT2H)
  --defects         v1.7-defect-inventory.json (maintainer-owned; status=authoritative)
  --output          output path for rc-certification-result.json
  --evidence-root   approved root for evidence paths (rejects absolute/.. /outside paths)

Behavior:
  - Validates each sub-result's schema, buildId (== --build-id), mode (pr, clean tree),
    Linux/JDK21 environment, and PASSED status.
  - Requires state-cycle cycles.requested == 10000 (cycles.failed == 0) and soak
    duration.requested == PT2H with duration.completed == true (real wall-clock >= 2h).
  - Requires all final C01-C10 compatibility rows PASSED, including C09.
  - Requires the upgrade rehearsal authoritative + PASSED (Linux/JDK21/PG16/Redis7).
  - Requires the defect inventory present + status=authoritative + zero open P0/P1.
  - Records each evidence file's sha256 + relative path; rejects path traversal / outside-root.
  - Writes rc-certification-result.json (status=PASSED only if every check passes).
  - NEVER modifies the acceptance manifest; use promote-rc-gates.sh after this validates.
EOF
}

BUILD_ID=""
RECOVERY=""
UPGRADE=""
COMPATIBILITY=""
STATE_CYCLE=""
SOAK=""
DEFECTS=""
OUTPUT=""
EVIDENCE_ROOT=""

while [ $# -gt 0 ]; do
  case "$1" in
    --build-id) BUILD_ID="$2"; shift 2 ;;
    --recovery) RECOVERY="$2"; shift 2 ;;
    --upgrade) UPGRADE="$2"; shift 2 ;;
    --compatibility) COMPATIBILITY="$2"; shift 2 ;;
    --state-cycle) STATE_CYCLE="$2"; shift 2 ;;
    --soak) SOAK="$2"; shift 2 ;;
    --defects) DEFECTS="$2"; shift 2 ;;
    --output) OUTPUT="$2"; shift 2 ;;
    --evidence-root) EVIDENCE_ROOT="$2"; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage; exit 1 ;;
  esac
done

[ -n "$BUILD_ID" ]       || { echo "error: --build-id is required" >&2; exit 1; }
[ -n "$RECOVERY" ]       || { echo "error: --recovery is required" >&2; exit 1; }
[ -n "$UPGRADE" ]        || { echo "error: --upgrade is required" >&2; exit 1; }
[ -n "$COMPATIBILITY" ]  || { echo "error: --compatibility is required" >&2; exit 1; }
[ -n "$STATE_CYCLE" ]    || { echo "error: --state-cycle is required" >&2; exit 1; }
[ -n "$SOAK" ]           || { echo "error: --soak is required" >&2; exit 1; }
[ -n "$DEFECTS" ]        || { echo "error: --defects is required" >&2; exit 1; }
[ -n "$OUTPUT" ]         || { echo "error: --output is required" >&2; exit 1; }
[ -n "$EVIDENCE_ROOT" ]  || { echo "error: --evidence-root is required" >&2; exit 1; }

python3 "$LIB" aggregate \
  --build-id "$BUILD_ID" \
  --recovery "$RECOVERY" \
  --upgrade "$UPGRADE" \
  --compatibility "$COMPATIBILITY" \
  --state-cycle "$STATE_CYCLE" \
  --soak "$SOAK" \
  --defects "$DEFECTS" \
  --output "$OUTPUT" \
  --evidence-root "$EVIDENCE_ROOT"
