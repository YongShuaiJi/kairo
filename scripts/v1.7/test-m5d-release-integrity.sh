#!/usr/bin/env bash
#
# scripts/v1.7/test-m5d-release-integrity.sh
#
# V1.7 M5-D focused deterministic test harness for the release-integrity surface.
#
# Exercises: bash syntax of verify-reproducible.sh / generate-provenance.sh / verify-release.sh; the
# --help and invalid-argument contracts (bash level); py_compile of reprolib (+ releaselib +
# supplychainlib); the full reprolib self-test (reproducibility happy/tamper/mismatch/dirty/
# missing-image-identity/malformed-result/fabricated-PASSED/over-broad-normalization/path-escape,
# provenance subject-mismatch/stale/local-misrepresented, signature require-false-true/wrong-issuer/
# wrong-identity/tampered-bundle/wrong-subject/malformed, and the final gate happy/M5-C/tampered); and
# CLI end-to-end runs of verify-reproducible.sh (PASSED + tampered FAILED) and verify-release.sh
# (--require-signature false PASSED + true SKIPPED FAILED) against fixture M5-D release directories.
#
# No __pycache__ is left in the worktree (PYTHONDONTWRITEBYTECODE=1 + a trap). Exits non-zero with a
# count on any failure.
#
set -euo pipefail

export PYTHONDONTWRITEBYTECODE=1

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
LIB="${SCRIPT_DIR}/lib/reprolib.py"
RLIB="${SCRIPT_DIR}/lib/releaselib.py"
SCLIB="${SCRIPT_DIR}/lib/supplychainlib.py"
VREPRO="${SCRIPT_DIR}/verify-reproducible.sh"
VRELEASE="${SCRIPT_DIR}/verify-release.sh"
GENPROV="${SCRIPT_DIR}/generate-provenance.sh"
TEST_TMP="$(mktemp -d -t kairo-m5d-test.XXXXXX)"
cleanup() {
  rm -rf "$TEST_TMP" "${SCRIPT_DIR}/lib/__pycache__" 2>/dev/null || true
}
trap cleanup EXIT

PASS=0
FAIL=0
FAILED_NAMES=()

ok()   { PASS=$((PASS + 1)); printf '  PASS  %s\n' "$1"; }
bad()  { FAIL=$((FAIL + 1)); FAILED_NAMES+=("$1"); printf '  FAIL  %s\n' "$1" >&2; }

assert_rc() {
  local label="$1" expected="$2"; shift 2
  "$@" >/dev/null 2>&1 && local rc=0 || local rc=$?
  if [ "$rc" -eq "$expected" ]; then ok "$label"; else bad "$label (expected exit $expected, got $rc)"; fi
}

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
for s in "$VREPRO" "$VRELEASE" "$GENPROV"; do
  bash -n "$s" && ok "bash -n $(basename "$s")" || bad "bash -n $(basename "$s")"
done
python3 -m py_compile "$LIB" 2>/dev/null && ok "py_compile reprolib" || bad "py_compile reprolib"
python3 -m py_compile "$RLIB" 2>/dev/null && ok "py_compile releaselib" || bad "py_compile releaselib"
python3 -m py_compile "$SCLIB" 2>/dev/null && ok "py_compile supplychainlib" || bad "py_compile supplychainlib"
rm -rf "${SCRIPT_DIR}/lib/__pycache__" 2>/dev/null || true

echo "=== B. --help + invalid-argument contracts (bash level) ==="
assert_rc "verify-reproducible --help exits 0" 0 "$VREPRO" --help
assert_rc "verify-release --help exits 0" 0 "$VRELEASE" --help
assert_rc "generate-provenance --help exits 0" 0 "$GENPROV" --help
assert_fail_substr "verify-reproducible missing args" "requires <release-a>" "$VREPRO"
assert_fail_substr "verify-release missing --manifest" "manifest is required" "$VRELEASE"
assert_fail_substr "verify-release missing --require-signature" "require-signature is required" "$VRELEASE" --manifest /nope
assert_fail_substr "verify-release bad require-signature" "must be true or false" "$VRELEASE" --manifest /nope --require-signature maybe
assert_fail_substr "generate-provenance missing --release-dir" "release-dir is required" "$GENPROV"
assert_fail_substr "verify-reproducible unknown arg" "unknown argument" "$VREPRO" a b --bogus
assert_fail_substr "verify-release nonexistent manifest" "manifest not found" "$VRELEASE" --manifest /no/such.json --require-signature false

echo "=== C. reprolib self-test (focused deterministic assertions) ==="
SELFTEST_JSON="$TEST_TMP/selftest.json"
SELFTEST_ERR="$TEST_TMP/selftest.err"
if python3 "$LIB" self-test >"$SELFTEST_JSON" 2>"$SELFTEST_ERR"; then
  ok "self-test ($(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["passed"])' "$SELFTEST_JSON")/$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["total"])' "$SELFTEST_JSON") passed)"
else
  bad "self-test"; cat "$SELFTEST_ERR" >&2
fi

echo "=== D. CLI end-to-end: verify-reproducible.sh on two fixture releases ==="
# Build a pair of identical M5-D fixture releases for comparison.
PAIR_TMP="$TEST_TMP/pair"
python3 - "$LIB" "$PAIR_TMP" <<'PY'
import os, sys, importlib.util
spec = importlib.util.spec_from_file_location("reprolib", sys.argv[1])
rpl = importlib.util.module_from_spec(spec); spec.loader.exec_module(rpl)
rel, m = rpl._m5d_release(sys.argv[2], sig_status='SKIPPED')
print(rel)
PY
REL_A="$PAIR_TMP/release"
REL_B="$PAIR_TMP/release-b"
# valid pair: verify-reproducible exits 0
if "$VREPRO" "$REL_A" "$REL_B" --out "$PAIR_TMP/repro.json" >/dev/null 2>&1; then
  ok "verify-reproducible: identical releases PASSED (CLI)"
else
  bad "verify-reproducible: identical releases PASSED (CLI)"
fi
# tamper a file artifact in B (same name, different content) -> verify-reproducible exits 2
ART="$(python3 -c 'import json,sys; print(next(a["path"] for a in json.load(open(sys.argv[1]))["artifacts"] if a["type"]=="jar"))' "$REL_A/release-manifest.json")"
echo "tampered-content" > "$REL_B/$ART"
# fix B manifest sha + SHA256SUMS so the comparison sees a content diff, not a manifest diff
python3 - "$REL_B/release-manifest.json" "$REL_B/$ART" "$REL_B/SHA256SUMS" <<'PY'
import json, sys, hashlib, os
mp, art_path, sums_path = sys.argv[1], sys.argv[2], sys.argv[3]
m = json.load(open(mp))
h = hashlib.sha256(open(art_path,'rb').read()).hexdigest()
sz = os.path.getsize(art_path)
for a in m['artifacts']:
    if a.get('path') == os.path.basename(art_path):
        a['sha256'] = h; a['size'] = sz
json.dump(m, open(mp,'w'), indent=2, sort_keys=True); open(mp,'a').write('\n')
# rebuild SHA256SUMS
fa = sorted([a for a in m['artifacts'] if a.get('type') in ('jar','tar.gz')], key=lambda a:a['name'])
with open(sums_path,'w') as f:
    for a in fa:
        f.write("%s  %s\n" % (a['sha256'], a['name']))
PY
if "$VREPRO" "$REL_A" "$REL_B" --out "$PAIR_TMP/repro-tamper.json" >/dev/null 2>&1; then
  bad "verify-reproducible: tampered release should FAILED (CLI)"
else
  ok "verify-reproducible: tampered release FAILED (CLI, exit 2)"
fi
# the tampered result must honestly record FAILED + a bit-identical failure reason
if grep -q '"status": "FAILED"' "$PAIR_TMP/repro-tamper.json" 2>/dev/null \
   && grep -q 'not bit-identical' "$PAIR_TMP/repro-tamper.json" 2>/dev/null; then
  ok "verify-reproducible: tampered result records FAILED + bit-identical reason"
else
  bad "verify-reproducible: tampered result missing FAILED/bit-identical reason"
fi

echo "=== E. CLI end-to-end: verify-release.sh on a fixture M5-D release ==="
# Build a fresh M5-D fixture release for the gate tests.
GATE_TMP="$TEST_TMP/gate"
python3 - "$LIB" "$GATE_TMP" <<'PY'
import os, sys, importlib.util
spec = importlib.util.spec_from_file_location("reprolib", sys.argv[1])
rpl = importlib.util.module_from_spec(spec); spec.loader.exec_module(rpl)
print(rpl._m5d_release(sys.argv[2], sig_status='SKIPPED')[0])
PY
GATE_REL="$GATE_TMP/release"
# require-signature false: gate PASSES (V17-SUPPLY.PR=PASSED, RELEASE=NOT_RUN)
if "$VRELEASE" --manifest "$GATE_REL/release-manifest.json" --require-signature false >/dev/null 2>&1; then
  ok "verify-release: M5-D + require-signature=false PASSES (CLI)"
else
  bad "verify-release: M5-D + require-signature=false PASSES (CLI)"
fi

echo "=== F. CLI end-to-end: generate-provenance M5-C -> M5-D promotion ==="
PROMOTE_TMP="$TEST_TMP/promote"
python3 - "$LIB" "$PROMOTE_TMP" <<'PY'
import json, os, sys, importlib.util
spec = importlib.util.spec_from_file_location("reprolib", sys.argv[1])
rpl = importlib.util.module_from_spec(spec); spec.loader.exec_module(rpl)
rel, _ = rpl._m5d_release(sys.argv[2], sig_status='SKIPPED')
mp = os.path.join(rel, 'release-manifest.json')
m = json.load(open(mp))
m.pop('releaseIntegrity', None)
for a in m['artifacts']:
    a['signature'] = {'status': 'SKIPPED'}
    a['provenance'] = {'status': 'NOT_AVAILABLE'}
rpl._write_json(mp, m)
print(rel)
PY
PROMOTE_REL="$PROMOTE_TMP/release"
if "$GENPROV" --release-dir "$PROMOTE_REL" >/dev/null 2>&1 \
   && [ "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["releaseIntegrity"]["overallStatus"])' "$PROMOTE_REL/release-manifest.json")" = "PASSED" ]; then
  ok "generate-provenance loads PASSED result and promotes M5-C -> M5-D"
else
  bad "generate-provenance M5-C -> M5-D promotion"
fi
# require-signature true with SKIPPED signature -> FAIL
if "$VRELEASE" --manifest "$GATE_REL/release-manifest.json" --require-signature true >/dev/null 2>&1; then
  bad "verify-release: require-signature=true + SKIPPED should FAIL (CLI)"
else
  ok "verify-release: require-signature=true + SKIPPED FAILED (CLI)"
fi
# a tampered artifact hash -> FAIL
ART2="$(python3 -c 'import json,sys; print(next(a["path"] for a in json.load(open(sys.argv[1]))["artifacts"] if a["type"]=="jar"))' "$GATE_REL/release-manifest.json")"
echo "tampered" > "$GATE_REL/$ART2"
if "$VRELEASE" --manifest "$GATE_REL/release-manifest.json" --require-signature false >/dev/null 2>&1; then
  bad "verify-release: tampered artifact should FAIL (CLI)"
else
  ok "verify-release: tampered artifact FAILED (CLI)"
fi

# Final: no __pycache__ left in the worktree.
if find "$SCRIPT_DIR/lib" -name '__pycache__' -type d 2>/dev/null | grep -q .; then
  bad "__pycache__ left in worktree"
  find "$SCRIPT_DIR/lib" -name '__pycache__' -type d -exec rm -rf {} + 2>/dev/null || true
else
  ok "no __pycache__ in worktree"
fi

echo
echo "==> test-m5d-release-integrity: PASS=$PASS FAIL=$FAIL"
if [ "$FAIL" -ne 0 ]; then
  printf 'failed: %s\n' "${FAILED_NAMES[@]}" >&2
  exit 1
fi
