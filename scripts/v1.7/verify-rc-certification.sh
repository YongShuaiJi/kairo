#!/usr/bin/env bash
#
# scripts/v1.7/verify-rc-certification.sh
#
# V1.7 M6-A: offline verifier for a previously written rc-certification-result.json.
# Confirms the result is PASSED, bound to a 40-hex buildId, lists all 6 evidence files,
# re-hashes and re-validates them, and has no failureReasons. Performs no network
# downloads and never modifies the acceptance manifest.
#
# Fixed interface:
#   ./scripts/v1.7/verify-rc-certification.sh target/v1.7/rc-certification-result.json
#
# Exit codes:
#   0  result is valid and PASSED
#   1  usage error / file not found
#   4  result is FAILED or malformed (fail-closed)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LIB="$REPO_ROOT/scripts/v1.7/lib/rc_certlib.py"

usage() {
  cat <<'EOF'
Usage: verify-rc-certification.sh <rc-certification-result.json> [--help]

Behavior:
  - Confirms facility, 40-hex buildId, status=PASSED, all 6 evidence entries with valid
    sha256 and safe relative paths, empty failureReasons, and re-validates every sub-result.
EOF
}

[ $# -ge 1 ] || { usage; exit 1; }
case "$1" in
  --help|-h) usage; exit 0 ;;
esac
RESULT="$1"
[ -f "$RESULT" ] || { echo "error: result file not found: $RESULT" >&2; exit 1; }

python3 "$LIB" verify "$RESULT"
