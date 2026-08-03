#!/usr/bin/env bash
#
# scripts/v1.7/test-release-assembly.sh
#
# V1.7 M5-B focused integration / self-test for the release assembly surface.
#
# It exercises the ACTUAL command surface and failure modes of build-release.sh (fast paths that
# fail before Maven) and the deterministic assembly / inventory / manifest / secret / SHA-256 /
# Docker-metadata logic of releaselib via synthetic fixtures. Synthetic fixtures are clearly
# labeled as fixtures and are NOT presented as a real release; the real RC build is a separate step
# (build-release.sh into target/v1.7/release-smoke).
#
# No __pycache__ is left in the worktree: PYTHONDONTWRITEBYTECODE=1 is exported and a trap removes
# any stray cache. The script only writes under owned mktemp directories and exits non-zero with a
# count on any failure.
#
set -euo pipefail

export PYTHONDONTWRITEBYTECODE=1

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
LIB="${SCRIPT_DIR}/lib/releaselib.py"
BUILD="${SCRIPT_DIR}/build-release.sh"
TEST_TMP="$(mktemp -d -t kairo-release-test.XXXXXX)"
cleanup() {
  rm -rf "$TEST_TMP" "${SCRIPT_DIR}/lib/__pycache__" 2>/dev/null || true
}
trap cleanup EXIT

PASS=0
FAIL=0
FAILED_NAMES=()

ok()   { PASS=$((PASS + 1)); printf '  PASS  %s\n' "$1"; }
bad()  { FAIL=$((FAIL + 1)); FAILED_NAMES+=("$1"); printf '  FAIL  %s\n' "$1" >&2; }

# assert_cmd_fail <label> <expected-substr> <cmd...>: command must exit non-zero and stderr must
# contain expected-substr.
assert_cmd_fail() {
  local label="$1" substr="$2"; shift 2
  local err rc
  err="$("$@" 2>&1 >/dev/null)" && rc=0 || rc=$?
  if [ "$rc" -eq 0 ]; then bad "$label (expected failure, got exit 0)"; return; fi
  if ! grep -Fq "$substr" <<<"$err"; then
    printf '    stderr: %s\n' "$err" >&2
    bad "$label (stderr missing: $substr)"; return
  fi
  ok "$label"
}

echo "=== A. syntax + self-test ==="
bash -n "$BUILD" && ok "bash -n build-release.sh" || bad "bash -n build-release.sh"
if python3 -m py_compile "$LIB" 2>/dev/null; then ok "py_compile releaselib"; else bad "py_compile releaselib"; fi
# py_compile always writes a .pyc under __pycache__; remove it so the worktree stays clean.
rm -rf "${SCRIPT_DIR}/lib/__pycache__" 2>/dev/null || true
SELFTEST_JSON="$TEST_TMP/releaselib-selftest.json"
SELFTEST_ERR="$TEST_TMP/releaselib-selftest.err"
if python3 "$LIB" self-test >"$SELFTEST_JSON" 2>"$SELFTEST_ERR"; then
  ok "releaselib self-test ($(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["passed"])' "$SELFTEST_JSON")/$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["total"])' "$SELFTEST_JSON"))"
else
  bad "releaselib self-test"; cat "$SELFTEST_ERR" >&2
fi

ATTACH_EXAMPLE="$SCRIPT_DIR/lib/release/bundle/examples/attach-launch.sh"
if grep -Fq 'KAIRO_AGENT_TOKEN' "$ATTACH_EXAMPLE" \
   && grep -Fq 'KAIRO_AGENT_HOST:-127.0.0.1' "$ATTACH_EXAMPLE" \
   && grep -Fq 'KAIRO_AGENT_PORT:-18080' "$ATTACH_EXAMPLE" \
   && ! grep -Fq 'KAIRO_ATTACH_PLATFORM_TOKEN' "$ATTACH_EXAMPLE"; then
  ok "attach example uses the Agent loopback contract"
else
  bad "attach example confuses Agent loopback and Platform credentials"
fi

echo "=== B. build-release.sh failure modes (fast, before Maven) ==="
assert_cmd_fail "invalid version SNAPSHOT" "must not contain -SNAPSHOT" \
  "$BUILD" --version 1.7.0-SNAPSHOT --output target/v1.7/x
assert_cmd_fail "invalid version 1.6.0" "must match 1.7.0" \
  "$BUILD" --version 1.6.0 --output target/v1.7/x
assert_cmd_fail "invalid version rc.0" "must match 1.7.0" \
  "$BUILD" --version 1.7.0-rc.0 --output target/v1.7/x
assert_cmd_fail "unsafe output outside repo" "must be within the repository root" \
  "$BUILD" --version 1.7.0-rc.1 --output /tmp/kairo-unsafe-out
assert_cmd_fail "output == repo root" "must not be the repository root itself" \
  "$BUILD" --version 1.7.0-rc.1 --output .
# Output path traversal escaping the repo root (realpath resolves to the repo parent).
assert_cmd_fail "output escapes repo" "must be within the repository root" \
  "$BUILD" --version 1.7.0-rc.1 --output target/v1.7/../../..
# Dirty worktree refused without --allow-dirty (the dev worktree is intentionally dirty).
assert_cmd_fail "dirty default refused" "worktree is dirty" \
  "$BUILD" --version 1.7.0-rc.1 --output target/v1.7/x
# Non-empty existing output dir refused (clobber protection); must be within the repo root so the
# containment check passes and the non-empty check is what fires.
CLOBBER_DIR="$REPO_ROOT/target/v1.7/clobber-test"
mkdir -p "$CLOBBER_DIR"
echo "leftover" > "$CLOBBER_DIR/README"
assert_cmd_fail "non-empty output refused" "output directory is not empty" \
  "$BUILD" --version 1.7.0-rc.1 --output "$CLOBBER_DIR"
rm -rf "$CLOBBER_DIR"

echo "=== C. deterministic assembly / inventory / contents / secrets / manifest (fixtures) ==="
set +e
PY_OUT="$TEST_TMP/release-fixtures.out"
PY_ERR="$TEST_TMP/release-fixtures.err"
python3 - "$LIB" >"$PY_OUT" 2>"$PY_ERR" <<'PY'
import os, sys, json, io, tarfile, gzip, tempfile, importlib.util
sys.path.insert(0, os.path.dirname(sys.argv[1]))
# Re-import cleanly each run (do not cache a stale module object).
spec = importlib.util.spec_from_file_location("releaselib", sys.argv[1])
rl = importlib.util.module_from_spec(spec); spec.loader.exec_module(rl)

PASS = FAIL = 0
def ok(name, detail=""): global PASS; PASS += 1; print("  PASS  " + name)
def bad(name, detail=""): global FAIL; FAIL += 1; print("  FAIL  " + name + " :: " + str(detail), file=sys.stderr)

V = "1.7.0-rc.1"
tmp = tempfile.mkdtemp(prefix="kairo-reltest-")

# --- deterministic archive + bundle contents ---
bundle_stage = os.path.join(tmp, "bstage"); top = "kairo-agent-bundle-" + V
os.makedirs(os.path.join(bundle_stage, "lib"))
os.makedirs(os.path.join(bundle_stage, "examples"))
for n, payload in [("kairo-bootstrap-api-" + V + ".jar", b"bootstrap"),
                   ("kairo-agent-bootstrap.jar", b"ab"), ("kairo-agent-core-modern.jar", b"acm"),
                   ("kairo-attach.jar", b"att"), ("kairo-ops.jar", b"ops")]:
    with open(os.path.join(bundle_stage, "lib", n), "wb") as f: f.write(payload)
with open(os.path.join(bundle_stage, "LICENSE"), "w") as f: f.write("AGPL-3.0\n")
with open(os.path.join(bundle_stage, "examples", "attach-list.sh"), "w") as f: f.write("#!/bin/sh\n")
with open(os.path.join(bundle_stage, "examples", "ops-version.sh"), "w") as f: f.write("#!/bin/sh\n")
with open(os.path.join(bundle_stage, "README.md"), "w") as f: f.write("# bundle\n")
t1 = os.path.join(tmp, "b1.tar.gz"); t2 = os.path.join(tmp, "b2.tar.gz")
rl.make_deterministic_tar(bundle_stage, top, t1, 1700000000)
rl.make_deterministic_tar(bundle_stage, top, t2, 1700000000)
b1 = open(t1, "rb").read(); b2 = open(t2, "rb").read()
if b1 == b2: ok("deterministic tar: identical bytes")
else: bad("deterministic tar: bytes differ", "%d vs %d" % (len(b1), len(b2)))
# inspect members
raw = gzip.decompress(b1)
members = []
with tarfile.open(fileobj=io.BytesIO(raw)) as tf:
    for m in tf.getmembers():
        members.append(m)
names = [m.name for m in members]
if names == sorted(names): ok("tar entries sorted")
else: bad("tar entries not sorted", names[:5])
forbidden = ["kairo-demo", "kairo-sidecar", "node_modules", ".env", "original-", "SNAPSHOT", "dependency-reduced"]
hits = [n for n in names if any(f in n for f in forbidden)]
if not hits: ok("bundle tar: no forbidden names")
else: bad("bundle tar: forbidden names", hits)
expected_lib = {"kairo-bootstrap-api-" + V + ".jar", "kairo-agent-bootstrap.jar",
                "kairo-agent-core-modern.jar", "kairo-attach.jar", "kairo-ops.jar"}
got_lib = {n.split("/")[-1] for n in names if n.startswith(top + "/lib/")}
if got_lib == expected_lib: ok("bundle tar: exact lib contents")
else: bad("bundle tar: lib mismatch", "%s vs %s" % (got_lib, expected_lib))
# normalized metadata
bad_meta = [(m.name, m.uid, m.gid, m.uname, m.gname, m.mtime, oct(m.mode))
            for m in members if m.uid != 0 or m.gid != 0 or m.uname or m.gname or m.mtime != 1700000000]
if not bad_meta: ok("tar entries: uid/gid/uname/gname/mtime normalized")
else: bad("tar entries: metadata not normalized", bad_meta[:2])
# path traversal rejection
trav_stage = os.path.join(tmp, "trav"); os.makedirs(os.path.join(trav_stage, "sub"))
with open(os.path.join(trav_stage, "sub", "x"), "wb") as f: f.write(b"x")
try:
    rl.make_deterministic_tar(trav_stage, "..", os.path.join(tmp, "trav.tar.gz"), 1)
    bad("make-tar: top-dir '..' not rejected")
except ValueError: ok("make-tar: path-traversal top-dir rejected")

# --- compose archive contents + secret scan ---
compose_stage = os.path.join(tmp, "cstage"); ctop = "kairo-compose-" + V
os.makedirs(compose_stage)
compose_yaml = "services:\n  platform:\n    image: kairo-platform-server:" + V + "\n    environment:\n" \
               "      KAIRO_BOOTSTRAP_TOKEN: ${KAIRO_BOOTSTRAP_TOKEN:?set the bootstrap token}\n" \
               "      KAIRO_DB_PASSWORD: ${KAIRO_DB_PASSWORD:?set the db password}\n"
with open(os.path.join(compose_stage, "docker-compose.yml"), "w") as f: f.write(compose_yaml)
with open(os.path.join(compose_stage, "kairo.env.template"), "w") as f:
    f.write("KAIRO_BOOTSTRAP_TOKEN=__SET_TOKEN__\nKAIRO_DB_PASSWORD=__SET_PW__\n")
with open(os.path.join(compose_stage, "UPGRADE.md"), "w") as f: f.write("# upgrade\n")
with open(os.path.join(compose_stage, "README.md"), "w") as f: f.write("# compose\n")
ctar = os.path.join(tmp, "compose.tar.gz")
rl.make_deterministic_tar(compose_stage, ctop, ctar, 1700000000)
craw = gzip.decompress(open(ctar, "rb").read())
cnames = []
with tarfile.open(fileobj=io.BytesIO(craw)) as tf: cnames = [m.name for m in tf.getmembers()]
need = {ctop + "/docker-compose.yml", ctop + "/kairo.env.template", ctop + "/UPGRADE.md", ctop + "/README.md"}
if need.issubset(set(cnames)): ok("compose tar: contains all expected files")
else: bad("compose tar: missing files", need - set(cnames))
if "__KAIRO_VERSION__" not in craw.decode(): ok("compose: version substituted (no placeholder)")
else: bad("compose: __KAIRO_VERSION__ placeholder remains")
# secret scan of compose content must be clean (placeholders are not secrets)
if not rl.scan_secrets([os.path.join(compose_stage, "docker-compose.yml"),
                        os.path.join(compose_stage, "kairo.env.template")]):
    ok("compose: secret scan clean")
else: bad("compose: secret scan flagged", rl.scan_secrets([os.path.join(compose_stage, "docker-compose.yml")]))
# a planted dev token must be detected
hot = os.path.join(tmp, "hot.env")
with open(hot, "w") as f: f.write("KAIRO_BOOTSTRAP_TOKEN=kairo-dev-admin-token-change-me\n")
if rl.scan_secrets([hot]): ok("secret scan: dev token detected")
else: bad("secret scan: dev token not detected")

# --- exact inventory + forbidden artifact rejection ---
inv = rl.expected_inventory(V)
if len(inv) == 8 and not rl.validate_inventory(inv, V): ok("inventory: exact 8 §12.2 entries")
else: bad("inventory: wrong", rl.validate_inventory(inv, V))
extra = inv + [{"name": "kairo-demo-" + V + ".jar", "type": "jar"}]
if rl.validate_inventory(extra, V): ok("inventory: kairo-demo rejected")  # errors present = rejected
else: bad("inventory: kairo-demo not rejected")
if rl.validate_inventory(inv[:-1], V): ok("inventory: missing artifact detected")  # errors present = detected
else: bad("inventory: missing artifact not detected")

# --- SHA256SUMS ---
fa = os.path.join(tmp, "a.jar"); fb = os.path.join(tmp, "b.tar.gz")
open(fa, "wb").write(b"aaaa"); open(fb, "wb").write(b"bbbb")
sums = os.path.join(tmp, "SHA256SUMS")
written = rl.write_sha256sums([("b.tar.gz", fb), ("a.jar", fa)], sums)
if written == ["a.jar", "b.tar.gz"]: ok("SHA256SUMS: lexical order")
else: bad("SHA256SUMS: order", written)
lines = open(sums).read().splitlines()
ok_sums = all(len(l.split("  ", 1)) == 2 and rl.SHA256_RE.match(l.split("  ", 1)[0]) for l in lines)
if ok_sums and len(lines) == 2: ok("SHA256SUMS: format + count")
else: bad("SHA256SUMS: format", lines)
try:
    rl.write_sha256sums([("SHA256SUMS", sums)], sums + ".x"); bad("SHA256SUMS: self-inclusion not rejected")
except ValueError: ok("SHA256SUMS: self-inclusion rejected")

# --- manifest validation / honesty ---
def synth_spec(tmpdir, allow_dirty=False, dirty_files=None):
    f = os.path.join(tmpdir, "k.jar"); open(f, "wb").write(b"j")
    return {
        "version": V, "gitCommit": "a" * 40,
        "buildWorkflow": "./scripts/v1.7/build-release.sh --version " + V,
        "buildStartedAt": "2026-08-03T00:00:00Z", "buildEndedAt": "2026-08-03T00:01:00Z",
        "sourceDateEpoch": 1700000000, "allowDirty": allow_dirty,
        "dirtyFiles": dirty_files or [], "toolchain": {"mvn": "3.9.16", "java": "21.0.11", "os": "linux"},
        "files": [
            {"name": "kairo-agent-bundle-" + V + ".tar.gz", "type": "tar.gz", "path": f},
            {"name": "kairo-platform-server-" + V + ".jar", "type": "jar", "path": f},
            {"name": "kairo-cli-" + V + ".jar", "type": "jar", "path": f},
            {"name": "kairo-mcp-" + V + ".jar", "type": "jar", "path": f},
            {"name": "kairo-sdk-" + V + ".jar", "type": "jar", "path": f},
            {"name": "kairo-compose-" + V + ".tar.gz", "type": "tar.gz", "path": f},
        ],
        "images": [
            {"name": "kairo-platform-server:" + V, "inspect": [{
                "Id": "sha256:srv", "RepoTags": ["kairo-platform-server:" + V], "RepoDigests": [],
                "Size": 10, "Created": "2026-01-01T00:00:00Z", "Architecture": "amd64", "Os": "linux",
                "RootFS": {"type": "layers", "Layers": ["sha256:l1"]}}]},
            {"name": "kairo-platform-web:" + V, "inspect": [{
                "Id": "sha256:web", "RepoTags": ["kairo-platform-web:" + V],
                "RepoDigests": ["kairo-platform-web@sha256:real"], "Size": 20,
                "Created": "2026-01-01T00:00:00Z", "Architecture": "amd64", "Os": "linux",
                "RootFS": {"type": "layers", "Layers": ["sha256:l2"]}}]},
        ],
    }
m = rl.build_manifest(synth_spec(tmp))
if not rl.validate_manifest(m): ok("manifest: build_manifest produces valid manifest")
else: bad("manifest: invalid", rl.validate_manifest(m))
if len(m["artifacts"]) == 8: ok("manifest: 8 artifacts")
else: bad("manifest: artifact count", len(m["artifacts"]))
# web image preserves truthful non-empty repoDigests + manifestDigest NOT_AVAILABLE
web = [a for a in m["artifacts"] if a["name"].startswith("kairo-platform-web:")][0]
if web["image"]["repoDigests"] == ["kairo-platform-web@sha256:real"] and web["image"]["manifestDigest"] == "NOT_AVAILABLE":
    ok("manifest: non-empty repoDigests preserved + manifestDigest NOT_AVAILABLE")
else: bad("manifest: docker meta", web["image"])

def tamper(mutate, label, should_reject=True):
    spec = synth_spec(tmp)
    m = rl.build_manifest(spec)
    mutate(m)
    errs = rl.validate_manifest(m)
    if should_reject and errs: ok("manifest honesty: " + label)
    elif not should_reject and not errs: ok("manifest honesty: " + label + " (accepted)")
    else: bad("manifest honesty: " + label, errs[:2] if errs else "accepted unexpectedly")

import copy
def set_sig(v): return lambda m: m["artifacts"][0].__setitem__("signature", {"status": v})
tamper(set_sig("SIGNED"), "fabricated SIGNED signature rejected")
def set_ver(v): return lambda m: m.__setitem__("version", v)
tamper(set_ver("1.7.0-SNAPSHOT"), "SNAPSHOT version rejected")
def set_ev(f, s): return lambda m: m.__setitem__(f, {"status": s})
tamper(set_ev("compatibilityEvidence", "PASSED"), "RC evidence PASSED rejected")
tamper(set_ev("soakEvidence", "PENDING"), "PENDING evidence accepted", should_reject=False)
def set_date(f, v): return lambda m: m.__setitem__(f, v)
tamper(set_date("ltsStart", "2026-01-01"), "pre-M6 support date rejected")
tamper(set_date("sourceTag", "V1.7.0"), "pre-M6 source tag rejected")
def add_demo(m): m["artifacts"].append({"name": "kairo-demo-" + V + ".jar", "type": "jar",
    "sha256": "0" * 64, "size": 1, "sbom": {"status": "NOT_AVAILABLE"},
    "signature": {"status": "SKIPPED"}, "provenance": {"status": "NOT_AVAILABLE"}})
tamper(add_demo, "kairo-demo artifact rejected")
def set_rd_bad(m):
    for a in m["artifacts"]:
        if a["type"] == "docker-image": a["image"]["repoDigests"] = "not-a-list"
tamper(set_rd_bad, "non-list repoDigests rejected")
# non-empty repoDigests (a real registry digest) must be ACCEPTED, not required empty
def set_rd_nonempty(m):
    for a in m["artifacts"]:
        if a["type"] == "docker-image": a["image"]["repoDigests"] = ["kairo@sha256:deadbeef"]
tamper(set_rd_nonempty, "truthful non-empty repoDigests accepted", should_reject=False)
# allowDirty must be disclosed
spec_d = synth_spec(tmp, allow_dirty=True, dirty_files=["x"])
md = rl.build_manifest(spec_d)
if not rl.validate_manifest(md) and any("dirty" in k.lower() for k in md["knownLimitations"]):
    ok("manifest: allowDirty disclosed in knownLimitations")
else: bad("manifest: allowDirty disclosure", rl.validate_manifest(md))

print("PASS=%d FAIL=%d" % (PASS, FAIL))
sys.exit(1 if FAIL else 0)
PY
PYRES=$?
set -e
if [ "$PYRES" -eq 0 ]; then
  ok "fixture assertions (python block)"
else
  bad "fixture assertions (python block)"
  cat "$PY_ERR" >&2
fi
# Merge the python block's own counts into the bash totals (last stdout line: PASS=N FAIL=M).
PYLINE="$(tail -1 "$PY_OUT" 2>/dev/null || true)"
if [[ "$PYLINE" == PASS=*FAIL=* ]]; then
  PYPASS="${PYLINE#PASS=}"; PYPASS="${PYPASS%% FAIL=*}"; PYFAIL="${PYLINE##*FAIL=}"
  PASS=$((PASS + PYPASS)); FAIL=$((FAIL + PYFAIL))
fi

# Final: no __pycache__ left in the worktree.
if find "$SCRIPT_DIR/lib" -name '__pycache__' -type d 2>/dev/null | grep -q .; then
  bad "__pycache__ left in worktree"
  find "$SCRIPT_DIR/lib" -name '__pycache__' -type d -exec rm -rf {} + 2>/dev/null || true
else
  ok "no __pycache__ in worktree"
fi

echo
echo "==> test-release-assembly: PASS=$PASS FAIL=$FAIL"
if [ "$FAIL" -ne 0 ]; then
  printf 'failed: %s\n' "${FAILED_NAMES[@]}" >&2
  exit 1
fi
