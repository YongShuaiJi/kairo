#!/usr/bin/env bash
#
# scripts/v1.7/promote-rc-gates.sh
#
# V1.7 M6-A: explicit, transactional, fail-closed promotion of the applicable RC gates
# in v1.7-acceptance-manifest.json AFTER aggregate-rc-certification.sh has produced a
# PASSED rc-certification-result.json for a frozen candidate commit.
#
# Fixed interface:
#   ./scripts/v1.7/promote-rc-gates.sh \
#     --result target/v1.7/rc-certification-result.json \
#     --manifest v1.7-acceptance-manifest.json [--dry-run]
#
# Safety:
#   - Refuses a non-PASSED / malformed rc-certification-result.
#   - Resolves the buildId to a REAL git commit (refuses the fixture sentinel + unresolved ids).
#   - Refuses a dirty working tree (promotion is from a clean, frozen candidate commit only).
#   - Updates ONLY the M6-A RC gates (V17-RECOVERY/UPGRADE/PERF/COMPAT/SOAK.RC). Every PR /
#     RELEASE gate and every historical fact is preserved.
#   - Idempotent: re-running for the same buildId is a no-op.
#   - Transactional: backs up the manifest, writes a temp, atomically renames.
#
# Exit codes:
#   0  promoted (or dry-run / idempotent no-op)
#   1  usage error
#   4  promotion refused (fail-closed)
#
# During the M6-A implementation session this command is NOT run.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LIB="$REPO_ROOT/scripts/v1.7/lib/rc_certlib.py"

usage() {
  cat <<'EOF'
Usage: promote-rc-gates.sh --result <rc-certification-result.json>
       --manifest <v1.7-acceptance-manifest.json> [--dry-run] [--help]

Behavior:
  - Verifies the rc-certification-result is PASSED.
  - Resolves buildId to a real git commit; refuses fixture/dev/dirty evidence.
  - Updates only V17-RECOVERY.RC, V17-UPGRADE.RC, V17-PERF.RC, V17-COMPAT.RC and V17-SOAK.RC.
  - Preserves every PR/RELEASE gate and historical fact; transactional with .bak backup.
  - --dry-run prints the would-be changes without writing.
EOF
}

RESULT=""
MANIFEST=""
DRY_RUN=""

while [ $# -gt 0 ]; do
  case "$1" in
    --result) RESULT="$2"; shift 2 ;;
    --manifest) MANIFEST="$2"; shift 2 ;;
    --dry-run) DRY_RUN="--dry-run"; shift ;;
    --help|-h) usage; exit 0 ;;
    *) echo "unknown argument: $1" >&2; usage; exit 1 ;;
  esac
done

[ -n "$RESULT" ]   || { echo "error: --result is required" >&2; exit 1; }
[ -n "$MANIFEST" ] || { echo "error: --manifest is required" >&2; exit 1; }

python3 "$LIB" promote --result "$RESULT" --manifest "$MANIFEST" --repo-root "$REPO_ROOT" ${DRY_RUN:+"$DRY_RUN"}
