#!/usr/bin/env bash
#
# scripts/v1.7/run-supply-chain.sh
#
# V1.7 M5-C (roadmap §12.3 / lts-policy §9) supply-chain gate runner. A bounded, auditable,
# post-assembly command that consumes an already-assembled M5-B release directory and ATTACHES
# supply-chain evidence to its manifest WITHOUT rebuilding or changing the eight-artifact §12.2
# inventory or the SHA256SUMS contract.
#
# It generates the two CycloneDX JSON SBOMs (Maven reactor + Platform Web), normalizes them for
# reproducibility, runs the single Grype 0.116.1 vulnerability scan over the final SBOMs, applies the
# third-party license policy and the exact-match expiring exception sets, and promotes the M5-B
# manifest to M5-C (supplyChain section + per-artifact SBOM references + evidence hashes/counts).
#
# Fail-closed: missing/malformed evidence, stale/unusable Grype DB, nonzero Grype error (other than the
# documented findings code with valid JSON), any Critical/High finding without an exact unexpired
# exception, or any denied/unknown/missing third-party license without an exact unexpired exception.
#
# Signature (cosign keyless) and provenance are NOT touched: signature stays SKIPPED, provenance stays
# NOT_AVAILABLE (both owned by M5-D/M6).
#
# Usage:
#   ./scripts/v1.7/run-supply-chain.sh --release-dir <completed-M5-B-release-dir>
#   GRYPE_BIN=/path/to/grype ./scripts/v1.7/run-supply-chain.sh --release-dir <dir>
#
set -euo pipefail

export PYTHONDONTWRITEBYTECODE=1

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
LIB="${SCRIPT_DIR}/lib/supplychainlib.py"
RLIB="${SCRIPT_DIR}/lib/releaselib.py"
SUPPLY_CONFIG="${REPO_ROOT}/config/v1.7/supply-chain"

usage() {
  cat <<'EOF'
Usage: run-supply-chain.sh --release-dir <completed-M5-B-release-dir>

  --release-dir   an already-assembled M5-B release directory (produced by build-release.sh).
                  Must contain release-manifest.json, SHA256SUMS and the eight §12.2 artifacts.
                  Evidence is written under <release-dir>/sbom, /reports and /supply-chain-config;
                  the manifest is promoted in place (M5-B -> M5-C).

Environment:
  GRYPE_BIN       optional path to a grype 0.116.1 binary (default: grype on PATH).

Tools required: mvn, java, node, npm, python3, grype 0.116.1 (with a current DB).
EOF
}

RELEASE_DIR=""
while [ $# -gt 0 ]; do
  case "$1" in
    --release-dir)
      [ $# -ge 2 ] && [ -n "$2" ] || { echo "error: --release-dir requires a value" >&2; exit 2; }
      RELEASE_DIR="$2"; shift 2 ;;
    -h|--help)     usage; exit 0 ;;
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
[ -f "$RELEASE_DIR/SHA256SUMS" ] || fail "SHA256SUMS not found in release dir: $RELEASE_DIR"

# --- 1. the manifest must be a valid M5-B local RC (the gate consumes an assembled release) ----
echo "==> validating M5-B release manifest"
python3 "$RLIB" validate-manifest "$MANIFEST" >/dev/null \
  || fail "release manifest is not a valid M5-B manifest; run build-release.sh first"

# --- 2. repo HEAD must equal the release gitCommit (the SBOM is generated from the exact source) --
cd "$REPO_ROOT"
HEAD_COMMIT="$(git rev-parse HEAD)"
MANIFEST_COMMIT="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["gitCommit"])' "$MANIFEST")"
[ "$HEAD_COMMIT" = "$MANIFEST_COMMIT" ] \
  || fail "repo HEAD ($HEAD_COMMIT) != release gitCommit ($MANIFEST_COMMIT); checkout the release commit first (the SBOM must reflect the exact release source)"

VERSION="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$MANIFEST")"
SOURCE_DATE_EPOCH="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["sourceDateEpoch"])' "$MANIFEST")"
NORM_TS="$(python3 -c 'import datetime as d; print(d.datetime.fromtimestamp(int(__import__("sys").argv[1]),tz=d.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"))' "$SOURCE_DATE_EPOCH")"

SBOM_OUT="$RELEASE_DIR/sbom"
REPORTS_OUT="$RELEASE_DIR/reports"
CFG_OUT="$RELEASE_DIR/supply-chain-config"
mkdir -p "$SBOM_OUT" "$REPORTS_OUT" "$CFG_OUT"

need_cmd() { command -v "$1" >/dev/null 2>&1 || fail "required tool not found: $1"; }
need_cmd mvn; need_cmd java; need_cmd node; need_cmd npm; need_cmd python3

# --- 3. Maven reactor CycloneDX SBOM (cyclonedx-maven-plugin 2.9.3) -------------------------
# makeAggregateBom is a reporting goal: it resolves the reactor dependency tree and emits one
# aggregate BOM at the reactor root target/bom.json. It does NOT rebuild or change artifacts.
echo "==> generating Maven reactor CycloneDX SBOM (cyclonedx-maven-plugin 2.9.3)"
mvn -B -ntp -Drevision="$VERSION" -DskipTests \
    org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeAggregateBom \
    -DschemaVersion=1.6 -DoutputFormat=json -DoutputName=bom >/dev/null
MAVEN_RAW_BOM="$REPO_ROOT/target/bom.json"
[ -f "$MAVEN_RAW_BOM" ] || fail "makeAggregateBom did not produce $MAVEN_RAW_BOM"
MAVEN_SBOM_REL="sbom/kairo-maven-reactor.cdx.json"
MAVEN_SBOM_ABS="$SBOM_OUT/kairo-maven-reactor.cdx.json"
python3 "$LIB" normalize-sbom --input "$MAVEN_RAW_BOM" --output "$MAVEN_SBOM_ABS" --timestamp "$NORM_TS" >/dev/null \
  || fail "Maven SBOM validation/normalization failed"
echo "    maven sbom: $MAVEN_SBOM_REL"

# --- 4. Platform Web CycloneDX SBOM (@cyclonedx/cyclonedx-npm 6.0.0) -------------------------
# cyclonedx-npm reads package-lock.json + node_modules; --omit dev restricts to production deps so
# the SBOM matches the shipped web image (dev tooling like cyclonedx-npm itself is excluded).
# ajv 8 is pinned at the Web root so cyclonedx-npm performs its own official schema validation rather
# than bypassing it. --output-reproducible suppresses volatile serialNumber/timestamp; normalize-sbom
# is the transparent second pass that fixes metadata.timestamp to SOURCE_DATE_EPOCH.
echo "==> generating Platform Web CycloneDX SBOM (@cyclonedx/cyclonedx-npm 6.0.0)"
( cd "$REPO_ROOT/kairo-platform-web" && npm ci >/dev/null 2>&1 )
WEB_RAW_BOM="$REPO_ROOT/kairo-platform-web/.cdx-web.json"
( cd "$REPO_ROOT/kairo-platform-web" && npx --no-install cyclonedx-npm \
    --omit dev --output-reproducible --sv 1.6 \
    --output-format json --output-file "$WEB_RAW_BOM" >/dev/null ) \
  || fail "cyclonedx-npm failed (is @cyclonedx/cyclonedx-npm 6.0.0 pinned in package.json/package-lock.json?)"
[ -f "$WEB_RAW_BOM" ] || fail "cyclonedx-npm did not produce output"
WEB_SBOM_REL="sbom/kairo-platform-web.cdx.json"
WEB_SBOM_ABS="$SBOM_OUT/kairo-platform-web.cdx.json"
python3 "$LIB" normalize-sbom --input "$WEB_RAW_BOM" --output "$WEB_SBOM_ABS" --timestamp "$NORM_TS" >/dev/null \
  || fail "Web SBOM validation/normalization failed"
rm -f "$WEB_RAW_BOM"
echo "    web sbom: $WEB_SBOM_REL"

# --- 5. copy the pinned policy/exception/repository-license files into the release ------------
for f in vulnerability-exceptions.json license-policy.json license-exceptions.json repository-license.json; do
  [ -f "$SUPPLY_CONFIG/$f" ] || fail "supply-chain config file missing: $SUPPLY_CONFIG/$f"
  cp "$SUPPLY_CONFIG/$f" "$CFG_OUT/$f"
done
echo "    config: supply-chain-config/ (4 files)"

# --- 6. locate Grype 0.116.1 and verify the DB is current -----------------------------------
GRYPE="${GRYPE_BIN:-grype}"
command -v "$GRYPE" >/dev/null 2>&1 || fail "grype 0.116.1 not found (set GRYPE_BIN or install it); cannot run the vulnerability scan (M5-C is fail-closed)"
GRYPE_VER_OUT="$("$GRYPE" version 2>&1 || true)"
if ! grep -Eq '0\.116\.1' <<<"$GRYPE_VER_OUT"; then
  fail "grype version is not 0.116.1 (pinned); got: $(printf '%s' "$GRYPE_VER_OUT" | head -1)"
fi
echo "==> grype: $(printf '%s' "$GRYPE_VER_OUT" | grep -E '0\.116\.1' | head -1)"
echo "==> grype db update (downloads the vulnerability DB; M5-C is an explicit scan command)"
"$GRYPE" db update >/dev/null 2>&1 || true
DB_STATUS_TMP="$(mktemp -t kairo-grype-db.XXXXXX)"
trap 'rm -f "$DB_STATUS_TMP" 2>/dev/null || true' EXIT
"$GRYPE" db status -o json > "$DB_STATUS_TMP" 2>/dev/null || fail "grype db status failed; cannot record DB metadata"
# Grype 0.116.1 db status JSON: {schemaVersion, from, built, path, valid}. Newer/older builds may use
# `current` instead of `valid`; accept either. Fail closed if the DB is not usable (stale/missing).
read -r DB_CURRENT DB_BUILT DB_VERSION DB_PATH <<PYEOF
$(python3 - "$DB_STATUS_TMP" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
ok = d.get("valid", d.get("current", d.get("status", "")))
ok = "true" if ok in (True, "true", "True", "current", "valid") else "false"
print(ok, d.get("built", "") or "", d.get("schemaVersion", d.get("version", "")) or "", d.get("path", "") or "")
PY
)
PYEOF
[ "$DB_CURRENT" = "true" ] \
  || fail "grype DB is not current/stale/usable (valid=$DB_CURRENT); run '$GRYPE db update' (network) first; M5-C is fail-closed"
echo "    db: valid=$DB_CURRENT built=$DB_BUILT schema=$DB_VERSION"

# --- 7. run Grype over each final SBOM; accept exit 0 or the documented findings code ----------
run_grype() {  # <sbom_rel> <out_rel>
  local sbom_rel="$1" out_rel="$2" out_abs rc
  out_abs="$RELEASE_DIR/$out_rel"
  set +e
  "$GRYPE" "sbom:$RELEASE_DIR/$sbom_rel" -o json --file "$out_abs" >/dev/null 2>"$out_abs.err"
  rc=$?
  set -e
  if [ "$rc" -ne 0 ]; then
    # Accept Grype's documented findings-present exit code (1) ONLY if valid JSON was produced.
    if [ "$rc" -eq 1 ] && python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$out_abs" 2>/dev/null; then
      :
    else
      echo "error: grype scan of $sbom_rel failed (exit $rc)" >&2
      [ -f "$out_abs.err" ] && tail -5 "$out_abs.err" >&2
      rm -f "$out_abs.err"
      fail "grype scan failed; M5-C is fail-closed"
    fi
  fi
  rm -f "$out_abs.err"
  [ -s "$out_abs" ] || fail "grype produced empty output for $sbom_rel"
}

GRYPE_MAVEN_REL="reports/grype-maven.raw.json"
GRYPE_WEB_REL="reports/grype-web.raw.json"
echo "==> grype scan: maven SBOM"
run_grype "$MAVEN_SBOM_REL" "$GRYPE_MAVEN_REL"
echo "==> grype scan: web SBOM"
run_grype "$WEB_SBOM_REL" "$GRYPE_WEB_REL"

# --- 8. vulnerability + license decisions (deterministic, from raw evidence) -----------------
NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
VDEC_REL="reports/vulnerability-decision.json"
LDEC_REL="reports/license-decision.json"
# decide-* exits 0 = PASSED, 2 = FAILED (gate, honest finding -- NOT a runner error; continue to
# promotion so the manifest captures the full evidence + failure reasons), 1 = error (abort).
echo "==> vulnerability decision"
set +e
python3 "$LIB" decide-vulnerabilities \
  --grype-doc "$MAVEN_SBOM_REL=$RELEASE_DIR/$GRYPE_MAVEN_REL" \
  --grype-doc "$WEB_SBOM_REL=$RELEASE_DIR/$GRYPE_WEB_REL" \
  --exceptions "$CFG_OUT/vulnerability-exceptions.json" \
  --policy-rel "supply-chain-config/vulnerability-exceptions.json" \
  --now "$NOW" --out "$RELEASE_DIR/$VDEC_REL"
VRC=$?
set -e
[ "$VRC" -eq 0 ] || [ "$VRC" -eq 2 ] || fail "vulnerability decision error (exit $VRC)"
echo "==> license decision"
set +e
python3 "$LIB" decide-licenses \
  --sbom-doc "$MAVEN_SBOM_REL=$MAVEN_SBOM_ABS" \
  --sbom-doc "$WEB_SBOM_REL=$WEB_SBOM_ABS" \
  --policy "$CFG_OUT/license-policy.json" \
  --exceptions "$CFG_OUT/license-exceptions.json" \
  --policy-rel "supply-chain-config/license-policy.json" \
  --exceptions-rel "supply-chain-config/license-exceptions.json" \
  --repo-rel "supply-chain-config/repository-license.json" \
  --now "$NOW" --out "$RELEASE_DIR/$LDEC_REL"
LRC=$?
set -e
[ "$LRC" -eq 0 ] || [ "$LRC" -eq 2 ] || fail "license decision error (exit $LRC)"

# --- 9. promote the manifest (M5-B -> M5-C) -------------------------------------------------
echo "==> promoting manifest (M5-B -> M5-C)"
SPEC="$RELEASE_DIR/.supply-spec.json"
MAVEN_SBOM_REL="$MAVEN_SBOM_REL" MAVEN_SBOM_ABS="$MAVEN_SBOM_ABS" \
WEB_SBOM_REL="$WEB_SBOM_REL" WEB_SBOM_ABS="$WEB_SBOM_ABS" \
GRYPE_MAVEN_REL="$GRYPE_MAVEN_REL" GRYPE_WEB_REL="$GRYPE_WEB_REL" \
RELEASE_DIR="$RELEASE_DIR" NOW="$NOW" \
DB_CURRENT="$DB_CURRENT" DB_BUILT="$DB_BUILT" DB_VERSION="$DB_VERSION" DB_PATH="$DB_PATH" \
VDEC_REL="$VDEC_REL" LDEC_REL="$LDEC_REL" \
python3 - <<'PY'
import json, os
spec = {
  "generatedAt": os.environ["NOW"],
  "mavenSbom": {"rel": os.environ["MAVEN_SBOM_REL"], "abs": os.environ["MAVEN_SBOM_ABS"]},
  "webSbom": {"rel": os.environ["WEB_SBOM_REL"], "abs": os.environ["WEB_SBOM_ABS"]},
  "grype": {
    "version": "0.116.1",
    "database": {"current": os.environ["DB_CURRENT"] == "true", "valid": os.environ["DB_CURRENT"] == "true",
                 "built": os.environ["DB_BUILT"], "schemaVersion": os.environ["DB_VERSION"],
                 "path": os.environ["DB_PATH"]},
    "scans": [
      {"sbomRel": os.environ["MAVEN_SBOM_REL"], "rawRel": os.environ["GRYPE_MAVEN_REL"],
       "rawAbs": os.path.join(os.environ["RELEASE_DIR"], os.environ["GRYPE_MAVEN_REL"])},
      {"sbomRel": os.environ["WEB_SBOM_REL"], "rawRel": os.environ["GRYPE_WEB_REL"],
       "rawAbs": os.path.join(os.environ["RELEASE_DIR"], os.environ["GRYPE_WEB_REL"])},
    ],
  },
  "vulnDecision": {"rel": os.environ["VDEC_REL"], "abs": os.path.join(os.environ["RELEASE_DIR"], os.environ["VDEC_REL"])},
  "licenseDecision": {"rel": os.environ["LDEC_REL"], "abs": os.path.join(os.environ["RELEASE_DIR"], os.environ["LDEC_REL"])},
  "repositoryLicense": "AGPL-3.0-only",
  "policyFiles": {
    "vulnerabilityExceptions": {"rel": "supply-chain-config/vulnerability-exceptions.json",
        "abs": os.path.join(os.environ["RELEASE_DIR"], "supply-chain-config/vulnerability-exceptions.json")},
    "licensePolicy": {"rel": "supply-chain-config/license-policy.json",
        "abs": os.path.join(os.environ["RELEASE_DIR"], "supply-chain-config/license-policy.json")},
    "licenseExceptions": {"rel": "supply-chain-config/license-exceptions.json",
        "abs": os.path.join(os.environ["RELEASE_DIR"], "supply-chain-config/license-exceptions.json")},
    "repositoryLicense": {"rel": "supply-chain-config/repository-license.json",
        "abs": os.path.join(os.environ["RELEASE_DIR"], "supply-chain-config/repository-license.json")},
  },
}
# promote-manifest computes sha256/size/componentCount/specVersion from the abs paths, so the spec
# only needs rel/abs pairs (+ the raw report sha/size which it also computes from rawAbs).
with open(os.path.join(os.environ["RELEASE_DIR"], ".supply-spec.json"), "w") as f:
    json.dump(spec, f, indent=2)
PY
python3 "$LIB" promote-manifest --manifest "$MANIFEST" --spec "$SPEC" --out "$MANIFEST" \
  || fail "manifest promotion failed"
python3 "$RLIB" validate-manifest "$MANIFEST" >/dev/null \
  || fail "promoted manifest failed validation"
rm -f "$SPEC"

# --- 10. summary ---------------------------------------------------------------------------
echo "==> supply-chain gate complete"
python3 - "$MANIFEST" <<'PY'
import json, sys
m = json.load(open(sys.argv[1]))
sc = m["supplyChain"]
vs = sc["vulnerabilityScan"]; lp = sc["licensePolicy"]
print("    stage            : %s" % sc["stage"])
print("    maven sbom       : %s (%d components)" % (sc["sboms"]["maven"]["path"], sc["mavenSbomComponentCount"]))
print("    web sbom         : %s (%d components)" % (sc["sboms"]["web"]["path"], sc["webSbomComponentCount"]))
print("    vuln status      : %s  (Critical=%d High=%d Medium=%d Low=%d Negligible=%d Unknown=%d; blocked=%d exceptionAllowed=%d reported=%d)"
      % (vs["overallStatus"], vs["countsBySeverity"]["Critical"], vs["countsBySeverity"]["High"],
         vs["countsBySeverity"]["Medium"], vs["countsBySeverity"]["Low"], vs["countsBySeverity"]["Negligible"],
         vs["countsBySeverity"]["Unknown"], vs["countsByDecision"]["blocked"],
         vs["countsByDecision"]["exceptionAllowed"], vs["countsByDecision"]["reported"]))
print("    license status   : %s  (allowed=%d denied=%d review=%d unknown=%d missing=%d exceptionAllowed=%d)"
      % (lp["overallStatus"], lp["countsByDecision"]["allowed"], lp["countsByDecision"]["denied"],
         lp["countsByDecision"]["review"], lp["countsByDecision"]["unknown"], lp["countsByDecision"]["missing"],
         lp["countsByDecision"]["exceptionAllowed"]))
print("    overall          : %s" % sc["overallStatus"])
if sc["failureReasons"]:
    print("    failure reasons:")
    for r in sc["failureReasons"][:20]:
        print("      - %s" % r)
PY
if [ "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["supplyChain"]["overallStatus"])' "$MANIFEST")" != "PASSED" ]; then
  echo "==> supply-chain gate FAILED (honestly); see failure reasons above" >&2
  exit 3
fi
echo "==> done"
