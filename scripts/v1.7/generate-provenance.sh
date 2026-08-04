#!/usr/bin/env bash
#
# scripts/v1.7/generate-provenance.sh
#
# V1.7 M5-D (roadmap §12.4 / §12.5) deterministic provenance generator + manifest promotion
# (M5-C -> M5-D). Generates a deterministic in-toto Statement v0.1 / SLSA-compatible predicate whose
# every subject digest exactly equals the release-manifest content identity (file sha256 / normalized
# immutable image digest), records the exact source commit / version / build interface / locked
# toolchain, and attaches a ``releaseIntegrity`` section to the manifest.
#
# Promotes the manifest from M5-C to an honest M5-D stage only after supply-chain evidence (M5-C,
# already promoted) and the reproducibility result validate. Local/unsigned provenance is explicitly
# labeled ``generator=local-unsigned`` and may never be misrepresented as trusted GitHub/OIDC.
#
# Signature: cosign keyless is rehearsed only in the controlled GitHub Actions OIDC job
# (.github/workflows/release-integrity.yml). Locally (no OIDC) the signature stays SKIPPED. A
# --signature-bundle may be supplied only by that controlled OIDC job; it is never produced here.
#
# It never: publishes, pushes, tags, invents a digest, fabricates a signature, weakens the
# eight-artifact inventory, or claims RELEASE.
#
# Usage:
#   ./scripts/v1.7/generate-provenance.sh --release-dir <dir>
#       [--generator local-unsigned|github-actions-oidc]
#       [--reproducibility-result <result.json>]
#       [--signature-bundle <bundle.json> --identity <id> --issuer <issuer>]
#       [--build-workflow <workflow>]
#
set -euo pipefail

export PYTHONDONTWRITEBYTECODE=1

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
RLIB="${SCRIPT_DIR}/lib/releaselib.py"
LIB="${SCRIPT_DIR}/lib/reprolib.py"

usage() {
  cat <<'EOF'
Usage: generate-provenance.sh --release-dir <dir>
                              [--generator local-unsigned|github-actions-oidc]
                              [--reproducibility-result <result.json>]
                              [--signature-bundle <bundle.json> --identity <id> --issuer <issuer>]
                              [--build-workflow <workflow>]

  --release-dir             an already-assembled, M5-C-promoted release directory.
  --generator               local-unsigned (default; honest local provenance, not trusted OIDC) or
                            github-actions-oidc (controlled CI; requires --signature-bundle).
  --reproducibility-result  path to the reproducibility result JSON (defaults to
                            <release-dir>/reports/reproducibility-result.json). Must be PASSED.
  --signature-bundle        a cosign keyless bundle produced by the controlled OIDC job. Only valid
                            with --generator github-actions-oidc.
  --identity / --issuer     the signing identity/issuer (required with --signature-bundle).
  --build-workflow          the build interface string (defaults to the manifest buildWorkflow).

This command generates provenance and promotes the manifest to M5-D in place. It does not sign.
EOF
}

RELEASE_DIR=""
GENERATOR="local-unsigned"
REPRO_RESULT=""
SIG_BUNDLE=""
IDENTITY=""
ISSUER=""
BUILD_WORKFLOW=""
while [ $# -gt 0 ]; do
  case "$1" in
    --release-dir)             RELEASE_DIR="$2"; shift 2 ;;
    --generator)              GENERATOR="$2"; shift 2 ;;
    --reproducibility-result) REPRO_RESULT="$2"; shift 2 ;;
    --signature-bundle)       SIG_BUNDLE="$2"; shift 2 ;;
    --identity)               IDENTITY="$2"; shift 2 ;;
    --issuer)                 ISSUER="$2"; shift 2 ;;
    --build-workflow)         BUILD_WORKFLOW="$2"; shift 2 ;;
    -h|--help)                usage; exit 0 ;;
    *) echo "error: unknown argument: $1" >&2; usage >&2; exit 2 ;;
  esac
done

fail() { echo "error: $*" >&2; exit 1; }

[ -n "$RELEASE_DIR" ] || { usage >&2; fail "--release-dir is required"; }
if [[ "$RELEASE_DIR" != /* ]]; then RELEASE_DIR="$REPO_ROOT/$RELEASE_DIR"; fi
RELEASE_DIR="$(python3 -c 'import os,sys; print(os.path.realpath(os.path.normpath(sys.argv[1])))' "$RELEASE_DIR")"
[ -d "$RELEASE_DIR" ] || fail "release dir does not exist: $RELEASE_DIR"
MANIFEST="$RELEASE_DIR/release-manifest.json"
[ -f "$MANIFEST" ] || fail "release-manifest.json not found: $MANIFEST"

case "$GENERATOR" in
  local-unsigned|github-actions-oidc) ;;
  *) fail "--generator must be local-unsigned or github-actions-oidc" ;;
esac

# --- 1. the manifest must be a valid, M5-C-promoted release --------------------------------
echo "==> validating M5-C release manifest"
python3 "$RLIB" validate-manifest "$MANIFEST" >/dev/null \
  || fail "release manifest is not valid; run build-release.sh + run-supply-chain.sh first"
STAGE="$(python3 -c 'import json,sys; m=json.load(open(sys.argv[1])); s=m.get("supplyChain",{}); print(s.get("stage",""))' "$MANIFEST")"
[ "$STAGE" = "M5-C" ] \
  || fail "manifest is not M5-C-promoted (supplyChain.stage=$STAGE); run run-supply-chain.sh first"

# --- 2. reproducibility result must be PASSED ----------------------------------------------
if [ -z "$REPRO_RESULT" ]; then
  REPRO_RESULT="$RELEASE_DIR/reports/reproducibility-result.json"
fi
[ -f "$REPRO_RESULT" ] || fail "reproducibility result not found: $REPRO_RESULT (run verify-reproducible.sh first)"
REPRO_STATUS="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("status",""))' "$REPRO_RESULT")"
[ "$REPRO_STATUS" = "PASSED" ] \
  || fail "reproducibility result is not PASSED (status=$REPRO_STATUS); M5-D promotion requires a PASSED comparison"

# --- 3. signature: SKIPPED locally; SIGNED only with a controlled OIDC bundle ---------------
SIG_STATUS="SKIPPED"
SIG_REASON="local/PR build; cosign keyless requires GitHub Actions OIDC (controlled CI); RELEASE requires signature at M6"
SIG_BUNDLE_REL=""
if [ -n "$SIG_BUNDLE" ]; then
  [ "$GENERATOR" = "github-actions-oidc" ] \
    || fail "--signature-bundle requires --generator github-actions-oidc (cosign keyless is CI-only)"
  [ -n "$IDENTITY" ] && [ -n "$ISSUER" ] \
    || fail "--signature-bundle requires --identity and --issuer"
  if [[ "$SIG_BUNDLE" != /* ]]; then SIG_BUNDLE="$RELEASE_DIR/$SIG_BUNDLE"; fi
  [ -f "$SIG_BUNDLE" ] || fail "signature bundle not found: $SIG_BUNDLE"
  mkdir -p "$RELEASE_DIR/signatures"
  SIG_DEST="$RELEASE_DIR/signatures/release-manifest.sigstore.json"
  # The controlled CI writes the bundle directly under the release directory. Do not fail by
  # attempting to copy a file onto itself; external bundle paths are still copied into containment.
  if [ "$(python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' "$SIG_BUNDLE")" != \
       "$(python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' "$SIG_DEST")" ]; then
    cp "$SIG_BUNDLE" "$SIG_DEST"
  fi
  SIG_BUNDLE_REL="signatures/release-manifest.sigstore.json"
  SIG_STATUS="SIGNED"
fi

# --- 4. build-workflow default -------------------------------------------------------------
if [ -z "$BUILD_WORKFLOW" ]; then
  BUILD_WORKFLOW="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("buildWorkflow",""))' "$MANIFEST")"
fi
[ -n "$BUILD_WORKFLOW" ] || fail "could not determine buildWorkflow"

NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# --- 5. generate the in-toto statement (deterministic) -------------------------------------
echo "==> generating in-toto/SLSA provenance statement (generator=$GENERATOR)"
python3 "$LIB" build-provenance --manifest "$MANIFEST" --generator "$GENERATOR" \
  --build-workflow "$BUILD_WORKFLOW" \
  --out "$RELEASE_DIR/provenance/kairo-release.intoto.json"

# --- 6. build the promotion spec and promote (M5-C -> M5-D) --------------------------------
echo "==> promoting manifest (M5-C -> M5-D)"
REPRO_REL="reports/reproducibility-result.json"
case "$REPRO_RESULT" in
  "$RELEASE_DIR"/*) REPRO_REL="${REPRO_RESULT#$RELEASE_DIR/}" ;;
  *) # result outside the release dir: copy it in so it is content-addressed evidence
     mkdir -p "$RELEASE_DIR/reports"
     cp "$REPRO_RESULT" "$RELEASE_DIR/$REPRO_REL" ;;
esac

SPEC="$(mktemp -t kairo-m5d-spec.XXXXXX)"
trap 'rm -f "$SPEC" 2>/dev/null || true' EXIT
RELEASE_DIR="$RELEASE_DIR" GENERATOR="$GENERATOR" BUILD_WORKFLOW="$BUILD_WORKFLOW" NOW="$NOW" \
REPRO_REL="$REPRO_REL" REPRO_RESULT="$RELEASE_DIR/$REPRO_REL" \
SIG_STATUS="$SIG_STATUS" SIG_REASON="$SIG_REASON" SIG_BUNDLE_REL="$SIG_BUNDLE_REL" \
IDENTITY="$IDENTITY" ISSUER="$ISSUER" SPEC="$SPEC" \
python3 - <<'PY'
import json, os
spec = {
  "generatedAt": os.environ["NOW"],
  "generator": os.environ["GENERATOR"],
  "buildWorkflow": os.environ["BUILD_WORKFLOW"],
  "releaseRoot": os.environ["RELEASE_DIR"],
  "reproducibility": {
    "resultRel": os.environ["REPRO_REL"],
    "resultAbs": os.environ["REPRO_RESULT"],
    "result": json.load(open(os.environ["REPRO_RESULT"])),
  },
  "signature": {
    "status": os.environ["SIG_STATUS"],
    "reason": os.environ["SIG_REASON"],
    "bundleRel": os.environ["SIG_BUNDLE_REL"] or None,
    "bundleAbs": (os.path.join(os.environ["RELEASE_DIR"], os.environ["SIG_BUNDLE_REL"])
                  if os.environ["SIG_BUNDLE_REL"] else None),
    "identity": os.environ["IDENTITY"] or None,
    "issuer": os.environ["ISSUER"] or None,
  },
}
with open(os.environ["SPEC"], "w") as f:
    json.dump(spec, f, indent=2)
PY

python3 "$LIB" promote-m5d --manifest "$MANIFEST" --spec "$SPEC" --out "$MANIFEST" \
  || fail "M5-D promotion failed"
python3 "$RLIB" validate-manifest "$MANIFEST" >/dev/null \
  || fail "promoted M5-D manifest failed validation"

# --- 7. summary ---------------------------------------------------------------------------
echo "==> M5-D promotion complete"
python3 - "$MANIFEST" <<'PY'
import json, sys
m = json.load(open(sys.argv[1]))
ri = m["releaseIntegrity"]
print("    stage            : %s" % ri["stage"])
print("    reproducibility  : %s" % ri["reproducibility"]["status"])
print("    provenance       : %s (generator=%s, trustedOIDC=%s)" % (
    ri["provenance"]["status"], ri["provenance"]["generator"], ri["provenance"]["trustedOIDC"]))
print("    signature        : %s" % ri["signature"]["status"])
print("    overall          : %s" % ri["overallStatus"])
PY
echo "==> done"
