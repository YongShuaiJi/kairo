#!/usr/bin/env bash
#
# scripts/v1.7/verify-supply-chain.sh
#
# V1.7 M5-C OFFLINE supply-chain verifier. Validates a release manifest and its attached supply-chain
# evidence WITHOUT running Grype, downloading a database, or making any network call. It re-hashes
# every evidence file, validates the CycloneDX structure of each SBOM, re-derives the vulnerability
# and license decisions from the raw Grype JSON + SBOMs + policy/exceptions, and cross-checks them
# against the stored decision reports and the manifest's recorded hashes/counts/status.
#
# Validates: manifest stage/state; exact eight-artifact §12.2 inventory; CycloneDX structure; SBOM
# hashes and artifact->SBOM mappings (maven/web/NOT_APPLICABLE); report hashes/schema; pinned tool
# identities (grype 0.116.1, CycloneDX generators); Critical/High gate; license gate; exception
# schema/expiry/exact-matching/usage; unchanged signature (SKIPPED) / provenance (NOT_AVAILABLE)
# semantics; release-root-relative, canonical, non-symlink-escape path containment.
#
# An M5-B manifest (no supplyChain) validates at its stage (exit 0, no evidence to verify). A
# promoted M5-C manifest that omits or tampers evidence fails closed with stable diagnostics.
#
# Usage:
#   ./scripts/v1.7/verify-supply-chain.sh --manifest <release-dir>/release-manifest.json
#
set -euo pipefail

export PYTHONDONTWRITEBYTECODE=1

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LIB="${SCRIPT_DIR}/lib/supplychainlib.py"

usage() {
  cat <<'EOF'
Usage: verify-supply-chain.sh --manifest <release-dir>/release-manifest.json

  --manifest   path to a release-manifest.json (M5-B or M5-C). The release directory is taken as
               the manifest's parent directory; all evidence paths must be release-root-relative.

This command is fully offline: it never runs Grype, downloads a database, or makes a network call.
EOF
}

MANIFEST=""
while [ $# -gt 0 ]; do
  case "$1" in
    --manifest)
      [ $# -ge 2 ] && [ -n "$2" ] || { echo "error: --manifest requires a value" >&2; exit 2; }
      MANIFEST="$2"; shift 2 ;;
    -h|--help)  usage; exit 0 ;;
    *) echo "error: unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

fail() { echo "error: $*" >&2; exit 1; }

[ -n "$MANIFEST" ] || { usage >&2; fail "--manifest is required"; }
[ -f "$MANIFEST" ] || fail "manifest not found: $MANIFEST"

# The verifier is pure Python (file I/O + JSON + hashing); it performs no network/subprocess calls.
python3 "$LIB" verify-release --manifest "$MANIFEST"
