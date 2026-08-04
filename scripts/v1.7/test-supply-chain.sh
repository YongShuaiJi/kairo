#!/usr/bin/env bash
#
# scripts/v1.7/test-supply-chain.sh
#
# V1.7 M5-C focused deterministic test harness for the supply-chain gate surface.
#
# It exercises: bash syntax of run-supply-chain.sh / verify-supply-chain.sh; the --help and
# invalid-argument contracts of both commands (bash level); py_compile of both libraries; the full
# supplychainlib self-test (CycloneDX validation, 8-artifact mapping, exception validation,
# vulnerability + license decisions, manifest stage promotion, offline verification incl. tamper /
# missing-evidence / path-escape / symlink-escape / no-network cases); and a CLI end-to-end run of
# verify-supply-chain.sh against a fixture M5-C release directory (pass) and a tampered manifest (fail).
#
# No __pycache__ is left in the worktree (PYTHONDONTWRITEBYTECODE=1 + a trap). Exits non-zero with a
# count on any failure.
#
set -euo pipefail

export PYTHONDONTWRITEBYTECODE=1

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
LIB="${SCRIPT_DIR}/lib/supplychainlib.py"
RLIB="${SCRIPT_DIR}/lib/releaselib.py"
RUN="${SCRIPT_DIR}/run-supply-chain.sh"
VERIFY="${SCRIPT_DIR}/verify-supply-chain.sh"
TEST_TMP="$(mktemp -d -t kairo-supply-test.XXXXXX)"
cleanup() {
  rm -rf "$TEST_TMP" "${SCRIPT_DIR}/lib/__pycache__" 2>/dev/null || true
}
trap cleanup EXIT

PASS=0
FAIL=0
FAILED_NAMES=()

ok()   { PASS=$((PASS + 1)); printf '  PASS  %s\n' "$1"; }
bad()  { FAIL=$((FAIL + 1)); FAILED_NAMES+=("$1"); printf '  FAIL  %s\n' "$1" >&2; }

# assert_rc <label> <expected-rc> <cmd...>: command must exit with expected-rc.
assert_rc() {
  local label="$1" expected="$2"; shift 2
  "$@" >/dev/null 2>&1 && local rc=0 || local rc=$?
  if [ "$rc" -eq "$expected" ]; then ok "$label"; else bad "$label (expected exit $expected, got $rc)"; fi
}

# assert_fail_substr <label> <substr> <cmd...>: command must exit non-zero and stderr contains substr.
assert_fail_substr() {
  local label="$1" substr="$2"; shift 2
  local err rc
  err="$("$@" 2>&1 >/dev/null)" && rc=0 || rc=$?
  if [ "$rc" -eq 0 ]; then bad "$label (expected failure, got exit 0)"; return; fi
  if grep -Fq "$substr" <<<"$err"; then ok "$label"; else
    printf '    stderr: %s\n' "$err" >&2; bad "$label (stderr missing: $substr)"
  fi
}

echo "=== A. syntax + compile ==="
bash -n "$RUN" && ok "bash -n run-supply-chain.sh" || bad "bash -n run-supply-chain.sh"
bash -n "$VERIFY" && ok "bash -n verify-supply-chain.sh" || bad "bash -n verify-supply-chain.sh"
python3 -m py_compile "$LIB" 2>/dev/null && ok "py_compile supplychainlib" || bad "py_compile supplychainlib"
python3 -m py_compile "$RLIB" 2>/dev/null && ok "py_compile releaselib" || bad "py_compile releaselib"
rm -rf "${SCRIPT_DIR}/lib/__pycache__" 2>/dev/null || true

echo "=== B. --help + invalid-argument contracts (bash level) ==="
assert_rc "run --help exits 0" 0 "$RUN" --help
assert_rc "verify --help exits 0" 0 "$VERIFY" --help
assert_fail_substr "run unknown arg rejected" "unknown argument" "$RUN" --bogus
assert_fail_substr "verify unknown arg rejected" "unknown argument" "$VERIFY" --bogus
assert_fail_substr "run missing --release-dir" "release-dir is required" "$RUN"
assert_fail_substr "verify missing --manifest" "manifest is required" "$VERIFY"
assert_fail_substr "run --release-dir missing value" "requires a value" "$RUN" --release-dir
assert_fail_substr "verify --manifest missing value" "requires a value" "$VERIFY" --manifest
assert_fail_substr "verify nonexistent manifest" "manifest not found" "$VERIFY" --manifest /no/such.json

echo "=== C. supplychainlib self-test (focused deterministic assertions) ==="
SELFTEST_JSON="$TEST_TMP/selftest.json"
SELFTEST_ERR="$TEST_TMP/selftest.err"
if python3 "$LIB" self-test >"$SELFTEST_JSON" 2>"$SELFTEST_ERR"; then
  ok "self-test ($(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["passed"])' "$SELFTEST_JSON")/$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["total"])' "$SELFTEST_JSON") passed)"
else
  bad "self-test"; cat "$SELFTEST_ERR" >&2
fi

echo "=== D. CLI end-to-end: verify-supply-chain.sh on a fixture M5-C release ==="
FX="$TEST_TMP/fixture-release"
python3 - "$LIB" "$FX" <<'PY'
import os, sys, json, tempfile
sys.path.insert(0, os.path.dirname(sys.argv[1]))
import importlib.util
spec = importlib.util.spec_from_file_location("supplychainlib", sys.argv[1])
scl = importlib.util.module_from_spec(spec); spec.loader.exec_module(scl)
tmp = tempfile.mkdtemp(prefix="m5c-fx-")
rel, promoted = scl._build_release_dir(tmp)
fx = sys.argv[2]
os.symlink(os.path.join(rel, "release-manifest.json"), fx) if False else None
# copy the built release dir to the expected fixture path
import shutil
shutil.copytree(rel, fx)
print(fx)
PY
# valid fixture: verifier exits 0
if "$VERIFY" --manifest "$FX/release-manifest.json" >/dev/null 2>&1; then
  ok "verify: fixture M5-C release PASSES (CLI)"
else
  bad "verify: fixture M5-C release PASSES (CLI)"
fi
# tamper: corrupt a raw grype report hash in the manifest -> verifier exits non-zero
python3 - "$FX/release-manifest.json" <<'PY'
import json, sys
m = json.load(open(sys.argv[1]))
m["supplyChain"]["vulnerabilityScan"]["rawReports"][0]["sha256"] = "0" * 64
json.dump(m, open(sys.argv[1], "w"), indent=2, sort_keys=True)
open(sys.argv[1], "a").write("\n")
PY
if "$VERIFY" --manifest "$FX/release-manifest.json" >/dev/null 2>&1; then
  bad "verify: tampered hash should fail closed (CLI)"
else
  ok "verify: tampered hash fails closed (CLI)"
fi
# an M5-B manifest (no supplyChain) validates at its stage -> exit 0
python3 - "$FX/release-manifest.json" "$RLIB" <<'PY'
import json, sys, os, importlib.util
m = json.load(open(sys.argv[1]))
m.pop("supplyChain", None)
# restore M5-B artifact sbom fields (NOT_AVAILABLE) so validate_manifest passes
for a in m["artifacts"]:
    a["sbom"] = {"status": "NOT_AVAILABLE"}
json.dump(m, open(sys.argv[1], "w"), indent=2, sort_keys=True)
open(sys.argv[1], "a").write("\n")
PY
if "$VERIFY" --manifest "$FX/release-manifest.json" >/dev/null 2>&1; then
  ok "verify: M5-B manifest validates at its stage (CLI)"
else
  bad "verify: M5-B manifest validates at its stage (CLI)"
fi

echo
echo "==> test-supply-chain: PASS=$PASS FAIL=$FAIL"
if [ "$FAIL" -ne 0 ]; then
  printf 'failed: %s\n' "${FAILED_NAMES[@]}" >&2
  exit 1
fi
