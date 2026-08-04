#!/usr/bin/env bash
#
# scripts/v1.7/verify-release.sh
#
# V1.7 M5-D (roadmap §12.6) final release-integrity verifier and gate.
#
# Integrates -- without duplicating inconsistently -- the M5-B inventory/hash validation, the M5-C
# SBOM/vulnerability/license validation, the M5-D reproducibility/provenance validation, and the
# optional signature validation, against a single release manifest.
#
#   --manifest <release-manifest.json>  an M5-D-promoted release manifest (with releaseIntegrity).
#   --require-signature <true|false>    true: cosign keyless signature required (controlled CI);
#                                       false: local/PR; signature may be SKIPPED.
#
# V17-SUPPLY.PR becomes PASSED only when every applicable local/PR check is independently derivable
# and passes. V17-SUPPLY.RELEASE stays NOT_RUN until M6 (Engineering Complete is not Released).
#
# Fail-closed: missing/malformed/tampered/stale evidence, a fabricated PASSED reproducibility result,
# provenance subject mismatch, an over-broad OCI normalization, path traversal/symlink escape, and
# (with --require-signature true) missing/skipped/malformed/untrusted-identity/wrong-issuer/
# wrong-subject/tampered signature bundle evidence.
#
# Usage:
#   ./scripts/v1.7/verify-release.sh --manifest <release-manifest.json> --require-signature false
#   ./scripts/v1.7/verify-release.sh --manifest <release-manifest.json> --require-signature true
#
set -euo pipefail

export PYTHONDONTWRITEBYTECODE=1

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB="${SCRIPT_DIR}/lib/reprolib.py"

usage() {
  cat <<'EOF'
Usage: verify-release.sh --manifest <release-manifest.json> --require-signature <true|false>

  --manifest           path to a release-manifest.json (M5-D-promoted for the full gate).
  --require-signature  true: cosign keyless signature required (controlled CI); false: local/PR
                       (signature may be SKIPPED).

Environment:
  KAIRO_EXPECTED_SIGNING_IDENTITY  optional expected signing identity (cosign --certificate-identity).
  COSIGN_BIN                       optional cosign binary for cryptographic signature verification.

This command is fully offline: it never builds, runs Grype, or makes a network call. Cryptographic
signature verification uses cosign (COSIGN_BIN) when available; absent cosign, --require-signature
true fails closed.
EOF
}

MANIFEST=""
REQUIRE_SIG=""
while [ $# -gt 0 ]; do
  case "$1" in
    --manifest)
      [ $# -ge 2 ] && [ -n "$2" ] || { echo "error: --manifest requires a value" >&2; exit 2; }
      MANIFEST="$2"; shift 2 ;;
    --require-signature)
      [ $# -ge 2 ] && [ -n "$2" ] || { echo "error: --require-signature requires a value" >&2; exit 2; }
      case "$2" in
        true|false) REQUIRE_SIG="$2"; shift 2 ;;
        *) echo "error: --require-signature must be true or false" >&2; exit 2 ;;
      esac ;;
    -h|--help)  usage; exit 0 ;;
    *) echo "error: unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

fail() { echo "error: $*" >&2; exit 1; }

[ -n "$MANIFEST" ] || { usage >&2; fail "--manifest is required"; }
[ -n "$REQUIRE_SIG" ] || { usage >&2; fail "--require-signature is required"; }
[ -f "$MANIFEST" ] || fail "manifest not found: $MANIFEST"

# The verifier is pure Python (file I/O + JSON + hashing); it performs no network/subprocess calls
# (cosign, when present, is invoked only for --require-signature true SIGNED bundle crypto).
python3 "$LIB" verify-release --manifest "$MANIFEST" --require-signature "$REQUIRE_SIG"
