#!/usr/bin/env bash
#
# scripts/v1.7/verify-reproducible.sh
#
# V1.7 M5-D (roadmap §12.4) reproducibility verifier.
#
# Compares two releases built from two clean, isolated checkouts of the same commit with the same
# pinned toolchain and SOURCE_DATE_EPOCH. Fail-closed: dirty builds, different commits/versions/
# toolchains, incomplete inventories, missing evidence, symlink/path escape, malformed JSON, and
# non-bit-identical file artifacts all produce status=FAILED. The six file artifacts + SHA256SUMS
# must be bit-identical (bytes/hashes, not only names). The two OCI images are compared via a
# documented canonical immutable-content structure with an explicit narrow volatile-field allowlist
# (never name-only, never ignoring all metadata).
#
# Emits a machine-readable reproducibility result JSON with status, inputs, compared fields, allowed
# differences and failure reasons. A forged PASSED result cannot satisfy offline verification
# (verify-release.sh re-hashes the result and re-derives releaseA-side hashes from disk).
#
# Usage:
#   ./scripts/v1.7/verify-reproducible.sh <release-a> <release-b> [--out <result.json>]
#
#   <release-a>/<release-b>   directories produced by build-release.sh (each containing
#                            release-manifest.json, SHA256SUMS and the eight §12.2 artifacts).
#   --out                     path to write the machine-readable result (defaults to
#                            <release-a>/reports/reproducibility-result.json).
#
set -euo pipefail

export PYTHONDONTWRITEBYTECODE=1

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB="${SCRIPT_DIR}/lib/reprolib.py"

usage() {
  cat <<'EOF'
Usage: verify-reproducible.sh <release-a> <release-b> [--out <result.json>]

  Compares two releases for reproducibility (roadmap §12.4). Exits 0 iff PASSED, 2 iff the comparison
  ran and honestly FAILED, 1 on usage/IO error.

This command is fully offline: it never builds, runs Grype, or makes a network call.
EOF
}

if [ $# -lt 1 ]; then usage >&2; echo "error: requires <release-a> <release-b>" >&2; exit 1; fi
# allow --help before the two-positional-arg requirement
case "$1" in -h|--help) usage; exit 0 ;; esac
if [ $# -lt 2 ]; then usage >&2; echo "error: requires <release-a> <release-b>" >&2; exit 1; fi
RELEASE_A="$1"; shift
RELEASE_B="$1"; shift
OUT=""
while [ $# -gt 0 ]; do
  case "$1" in
    --out) [ $# -ge 2 ] && [ -n "$2" ] || { echo "error: --out requires a value" >&2; exit 2; }
           OUT="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "error: unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

fail() { echo "error: $*" >&2; exit 1; }

[ -d "$RELEASE_A" ] || fail "release-a is not a directory: $RELEASE_A"
[ -d "$RELEASE_B" ] || fail "release-b is not a directory: $RELEASE_B"
A_MAN="$RELEASE_A/release-manifest.json"
B_MAN="$RELEASE_B/release-manifest.json"
[ -f "$A_MAN" ] || fail "release-a/release-manifest.json not found: $A_MAN"
[ -f "$B_MAN" ] || fail "release-b/release-manifest.json not found: $B_MAN"

if [ -z "$OUT" ]; then
  mkdir -p "$RELEASE_A/reports"
  OUT="$RELEASE_A/reports/reproducibility-result.json"
fi

# compare-releases exits 0 = PASSED, 2 = FAILED (honest comparison outcome), 1 = error.
set +e
python3 "$LIB" compare-releases "$A_MAN" "$B_MAN" --out "$OUT" >/tmp/kairo-repro.out 2>/tmp/kairo-repro.err
RC=$?
set -e
cat /tmp/kairo-repro.out
if [ "$RC" -eq 1 ]; then
  cat /tmp/kairo-repro.err >&2
  fail "reproducibility comparison error (exit 1)"
fi
rm -f /tmp/kairo-repro.out /tmp/kairo-repro.err

STATUS="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["status"])' "$OUT")"
if [ "$STATUS" = "PASSED" ]; then
  echo "==> reproducibility: PASSED ($OUT)"
  exit 0
else
  echo "==> reproducibility: FAILED (honest); see $OUT" >&2
  exit 2
fi
