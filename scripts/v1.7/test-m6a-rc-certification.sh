#!/usr/bin/env bash
#
# scripts/v1.7/test-m6a-rc-certification.sh
#
# V1.7 M6-A focused self-test for the RC certification facility. Covers argument validation,
# schemas, fail-closed behavior, buildId mixing, truncated/short soak, reduced cycle count,
# missing compatibility rows, dev/dirty evidence, unsupported environment, rollback failure,
# corrupted backup hash, stale artifact, missing defect inventory, P0/P1 present, path
# traversal, and cleanup ownership (via the upgrade-rehearsal validation surface).
#
# Synthetic fixtures use the all-zeros buildId sentinel (0000...0000) so they are
# unmistakably fixture evidence and can NEVER be accepted by the authoritative RC path:
# promote-rc-gates.sh refuses the sentinel, and the CI workflow only runs real resolved SHAs.
# The real authoritative RC run is NOT_RUN in this session.
#
# No __pycache__ is left in the worktree (PYTHONDONTWRITEBYTECODE=1 + trap). Only writes
# under an owned ignored target/v1.7 directory. Exits non-zero with a count on any failure.

set -euo pipefail

export PYTHONDONTWRITEBYTECODE=1

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
UPGRADE_RUN="${SCRIPT_DIR}/run-upgrade-rehearsal.sh"
AGG="${SCRIPT_DIR}/aggregate-rc-certification.sh"
PROMOTE="${SCRIPT_DIR}/promote-rc-gates.sh"
LIB="${SCRIPT_DIR}/lib/rc_certlib.py"
WORKFLOW="$REPO_ROOT/.github/workflows/rc-certification.yml"
TEST_TMP="$REPO_ROOT/target/v1.7/m6a-test-$$"
mkdir -p "$TEST_TMP"
cleanup() {
  rm -rf "$TEST_TMP" "${SCRIPT_DIR}/lib/__pycache__" 2>/dev/null || true
}
trap cleanup EXIT

PASS=0
FAIL=0
FAILED_NAMES=()

ok()   { PASS=$((PASS + 1)); printf '  PASS  %s\n' "$1"; }
bad()  { FAIL=$((FAIL + 1)); FAILED_NAMES+=("$1"); printf '  FAIL  %s\n' "$1" >&2; }

# assert_cmd_fail <label> <expected-substr> <cmd...>: command must exit non-zero and stderr
# must contain expected-substr.
assert_cmd_fail() {
  local label="$1" substr="$2"; shift 2
  local err rc
  err="$("$@" 2>&1 >/dev/null)" && rc=0 || rc=$?
  if [ "$rc" -eq 0 ]; then bad "$label (expected failure, got exit 0)"; return; fi
  if ! grep -Fq -- "$substr" <<<"$err"; then
    printf '    stderr: %s\n' "$err" >&2
    bad "$label (stderr missing: $substr)"; return
  fi
  ok "$label"
}

# assert_cmd_ok <label> <cmd...>: command must exit 0.
assert_cmd_ok() {
  local label="$1"; shift
  if "$@" >/dev/null 2>&1; then ok "$label"; else bad "$label (exit non-zero)"; fi
}

echo "=== A. syntax + py_compile ==="
bash -n "$UPGRADE_RUN" && ok "bash -n run-upgrade-rehearsal.sh" || bad "bash -n run-upgrade-rehearsal.sh"
bash -n "$AGG"         && ok "bash -n aggregate-rc-certification.sh" || bad "bash -n aggregate-rc-certification.sh"
bash -n "$PROMOTE"     && ok "bash -n promote-rc-gates.sh" || bad "bash -n promote-rc-gates.sh"
python3 -m py_compile "$LIB" 2>/dev/null && ok "py_compile rc_certlib" || bad "py_compile rc_certlib"
rm -rf "${SCRIPT_DIR}/lib/__pycache__" 2>/dev/null || true

echo "=== A2. RC workflow freeze + evidence wiring ==="
if python3 - "$WORKFLOW" <<'PY'
import re, sys
text = open(sys.argv[1], encoding="utf-8").read()
uses = re.findall(r"uses:\s+([^\s]+/[^@\s]+)@([^\s#]+)", text)
assert uses and all(re.fullmatch(r"[0-9a-f]{40}", revision) for _, revision in uses)
assert "inputs.candidate-sha" not in text
assert "inputs['candidate-sha']" in text
for required in (
    "run-m1-acceptance.sh", "run-upgrade-rehearsal.sh", "--cycles 10000",
    "--duration PT2H", "compatibility-result.json", "defect-inventory.json",
    "recovery-result.json", "state-cycle-result.json", "soak-result.json",
    "rc-certification-result.json",
):
    assert required in text, required
PY
then
  ok "RC workflow pins actions, freezes input SHA, and carries all six evidence files"
else
  bad "RC workflow freeze/evidence wiring"
fi

echo "=== B. run-upgrade-rehearsal.sh fail-closed argument validation ==="
assert_cmd_fail "missing --to" "--to is required" \
  "$UPGRADE_RUN" --from V1.6.0
assert_cmd_fail "unsupported database" "only 'postgresql16' is supported" \
  "$UPGRADE_RUN" --from V1.6.0 --to HEAD --database mysql8 --output target/v1.7/upgrade
assert_cmd_fail "unresolved --to ref" "could not resolve --to" \
  "$UPGRADE_RUN" --from V1.6.0 --to nope-xyz-123 --database postgresql16 --output target/v1.7/upgrade
assert_cmd_fail "dirty tree without --allow-dirty" "evidence refuses a dirty working tree" \
  "$UPGRADE_RUN" --from V1.6.0 --to HEAD --database postgresql16 --output target/v1.7/upgrade
assert_cmd_fail "from==to same commit" "resolve to the same commit" \
  "$UPGRADE_RUN" --from V1.6.0 --to V1.6.0 --database postgresql16 --output target/v1.7/upgrade
assert_cmd_fail "output outside target root" "relative path under target" \
  "$UPGRADE_RUN" --from V1.6.0 --to HEAD --database postgresql16 --output /tmp/not-target

echo "=== B2. cleanup ownership: rehearsal cleanup removes ONLY its own prefixed resources ==="
# The cleanup function must only rm/network-rm/volume-rm the RUN_PREFIX-prefixed vars,
# never hardcoded or unprefixed names that could touch unrelated Docker resources.
cleanup_cmds="$(grep -nE 'docker (rm|network rm|volume rm|worktree remove)' "$UPGRADE_RUN" || true)"
if printf '%s\n' "$cleanup_cmds" | grep -Eq 'docker rm -f "\$PG_CONTAINER"|docker rm -f "\$REDIS_CONTAINER"|docker network rm "\$DOCKER_NET"|docker volume rm "\$(PG|REDIS)_VOLUME"|worktree remove --force "\$V16_WORKTREE"'; then
  ok "cleanup references only prefixed resource vars"
else
  bad "cleanup ownership (expected only \$PG_CONTAINER/\$REDIS_CONTAINER/\$DOCKER_NET/\$*_VOLUME/\$V16_WORKTREE)"
fi
# No unprefixed compose container/network/volume names targeted for removal (would risk
# touching unrelated resources like the dev docker-compose stack: kairo-postgres/redis/platform).
if printf '%s\n' "$cleanup_cmds" | grep -Eq 'rm (-f )?("?kairo-(postgres|redis|platform)(-data)?"?)'; then
  bad "cleanup targets unprefixed compose resource names (ownership risk)"
else
  ok "cleanup does not touch unprefixed compose resources"
fi

echo "=== C. RC aggregator validator matrix (python fixtures) ==="
PY_RESULT="$TEST_TMP/python-result.txt"
set +e
python3 - "${TEST_TMP#$REPO_ROOT/}" "$REPO_ROOT" "$LIB" >"$PY_RESULT" <<'PY'
import json, os, subprocess, sys, copy
TEST_TMP, REPO_ROOT, LIB = sys.argv[1], sys.argv[2], sys.argv[3]
os.chdir(REPO_ROOT)
PASS=0; FAIL=0
def ok(n): global PASS; PASS+=1; print("  PASS  "+n, file=sys.stderr)
def bad(n,d=""): global FAIL; FAIL+=1; print("  FAIL  "+n+" :: "+str(d), file=sys.stderr)

FIX="0"*40  # unmistakable fixture sentinel buildId
ROOT=os.path.join(TEST_TMP,"evidence"); os.makedirs(ROOT,exist_ok=True)
V16="113823b41981a2d8fb5473a772ae2d2938d9582e"
SHA64="a"*64

base_recovery={
  "schemaVersion":"1.0","release":"V1.7.0","milestone":"M1","buildId":FIX,
  "mode":"pr","workingTreeDirty":False,"generatedAt":"2026-08-05T00:00:00Z",
  "environment":{"os":"Linux 6.8 amd64","java":"openjdk version \"21.0.11\"","node":"v20.19.0","npm":"10.8.0"},
  "runnerOutcomes":[{"step":step,"exitCode":0} for step in
    ("focused-tests","reactor-test","package","compose-verify","web-lint","web-typecheck","web-test","web-build")],
  "scenarios":[{"id":sid,"status":"PASSED"} for sid in
    ("M1-A","M1-B","M1-C","M1-D","M1-E","M1-F","M1-G","CLOSED-LOOP")],
  "overallChecks":[{"step":step,"passed":True,"status":"PASSED"} for step in
    ("focused-tests","reactor-test","package","compose-verify","web-lint","web-typecheck","web-test","web-build")],
  "gates":{"PR":{"status":"PASSED"},"RC":{"status":"NOT_RUN"},"RELEASE":{"status":"NOT_RUN"}}
}
base_upgrade={
  "schemaVersion":"1.0","facility":"kairo-upgrade-rehearsal","mode":"pr","authoritative":True,
  "workingTreeDirty":False,"status":"PASSED","buildId":FIX,"fromRef":"V1.6.0","fromCommit":V16,"toRef":"HEAD","toCommit":FIX,
  "database":"postgresql16","environment":{"os":"Linux","osName":"Linux","java":"21.0.11","javaMajor":21,
    "postgresql":{"version":"PostgreSQL 16.4","image":"postgres:16.4-alpine","digest":"sha256:"+"b"*64,"hostPort":32768},
    "redis":{"version":"7.4.10","image":"redis:7.4.10-alpine","digest":"sha256:"+"c"*64,"hostPort":32769}},
  "command":"./scripts/v1.7/run-upgrade-rehearsal.sh --from V1.6.0 --to HEAD --database postgresql16 --output target/v1.7/upgrade",
  "startedAt":"2026-08-05T00:00:00Z","endedAt":"2026-08-05T00:20:00Z",
  "scenarios":[{"name":name,"status":"PASSED","evidence":"x"} for name in
    ("start-postgres","start-redis","build-v16","build-candidate","migrate-v16","seed-state",
     "backup","migrate-candidate","rollback-guard","rollback-restore","redis-semantics")],
  "migrationVersions":{"before":"41","expectedBefore":"41","after":"43","expectedLatest":"43"},
  "backup":{"file":"v16-backup.sql","sha256":SHA64,"size":1234},
  "persistedState":{"before":{"fencingToken":{"count":1,"checksum":"x"}},"after":{"fencingToken":{"count":1,"checksum":"x"}},"survived":True},
  "rollbackGuard":{"result":"REJECTED","method":"SCHEMA_VERSION_PREFLIGHT","applicationVersion":41,"databaseVersion":43},
  "rollbackRestore":{"result":"PASSED","restoredVersion":41},
  "redis":{"result":"PASSED","connection":True,"namespace":"kairo:fencing:","namespaceVerified":True,"postgresIsSourceOfTruth":True},
  "cleanup":{"result":"PASSED"},"reports":[],"limitations":[],"failureReasons":[]
}
base_compat={
  "schemaVersion":"1.0","catalogVersion":"2024.01","generatedAt":"2026-08-05T00:00:00Z",
  "command":"aggregate-compatibility.sh","overall":"PASSED","buildId":FIX,
  "rows":[{"scenario":f"C{n:02d}","status":"PASSED","buildId":FIX} for n in range(1,11)]
}
base_cycle={
  "schemaVersion":"1.0","generatedAt":"2026-08-05T00:00:00Z","startedAt":"x","endedAt":"x",
  "buildId":FIX,"command":"run-state-cycle.sh","mode":"pr","workingTreeDirty":False,"jvmArgs":["-Xms512m"],
  "environment":{"jdkVersion":"21.0.11","osName":"Linux","osArch":"amd64","availableProcessors":4,"javaHome":"/j"},
  "cycles":{"requested":10000,"completed":10000,"failed":0},"scenarios":[],"concurrentConflict":None,
  "firstFailure":None,"overall":"PASSED"
}
base_soak={
  "schemaVersion":"1.0","generatedAt":"2026-08-05T02:00:02Z","startedAt":"2026-08-05T00:00:00Z","endedAt":"2026-08-05T02:00:02Z",
  "buildId":FIX,"command":"run-soak.sh","mode":"pr","workingTreeDirty":False,"jvmArgs":["-Xms1g"],
  "environment":{"jdkVersion":"21.0.11","osName":"Linux","osArch":"amd64","availableProcessors":4,"javaHome":"/j","pid":1},
  "duration":{"requested":"PT2H","requestedSeconds":7200,"completedSeconds":7202.0,"completedIso":"PT2H2S","completed":True},
  "measurementWarmup":{"strategy":"bounded-adaptive-metaspace-plateau","steadyStateEstablished":True,
    "observedWindowMetaspaceGrowthPct":1.0,"maxWindowMetaspaceGrowthPct":2.0,
    "eligibleLifecycleLoadersOutstanding":1,"allowedOutstandingLifecycleLoaders":2,
    "latestCohortGraceLoaders":32,"sampleEveryBatches":32},
  "overall":"PASSED","firstFailure":None,"timeSeries":{"rawPath":"soak-timeseries.jsonl","format":"jsonl","count":120}
}
base_defects={"schemaVersion":"1.0","status":"authoritative","buildId":FIX,"generatedAt":"x","defects":[]}

def write(name,obj):
    p=os.path.join(ROOT,name); json.dump(obj,open(p,"w")); return p

def run_agg(rec=base_recovery, upg=base_upgrade, comp=base_compat, cyc=base_cycle, soak=base_soak, defe=base_defects,
            rec_path=None, upg_path=None, comp_path=None, cyc_path=None, soak_path=None, defe_path=None,
            build_id=FIX, evidence_root=ROOT, output=None):
    rc=rec_path or write("recovery.json",rec)
    up=upg_path or write("upgrade.json",upg)
    cp=comp_path or write("compat.json",comp)
    sc=cyc_path or write("cycle.json",cyc)
    sk=soak_path or write("soak.json",soak)
    df=defe_path or write("defects.json",defe)
    out=output or os.path.join(ROOT,"rc-certification-result.json")
    r=subprocess.run(["./scripts/v1.7/aggregate-rc-certification.sh",
        "--build-id",build_id,"--recovery",rc,"--upgrade",up,"--compatibility",cp,"--state-cycle",sc,
        "--soak",sk,"--defects",df,"--output",out,"--evidence-root",evidence_root],
        capture_output=True,text=True)
    return r

def expect_fail(label, substr, **kw):
    r=run_agg(**kw)
    if r.returncode==0: bad(label,"expected failure, got exit 0"); return
    if substr not in r.stderr: bad(label,f"stderr missing '{substr}': {r.stderr.strip()}"); return
    ok(label)

def expect_ok(label, **kw):
    r=run_agg(**kw)
    if r.returncode!=0: bad(label,f"exit {r.returncode}: {r.stderr.strip()}"); return
    ok(label)

# --- positive: all-valid fixture (buildId=sentinel) -> aggregator PASSED ---
expect_ok("positive: all-valid fixture aggregate PASSED")

# --- buildId mixing ---
u=copy.deepcopy(base_upgrade); u["buildId"]="1"*40
expect_fail("buildId mixing (upgrade)", "buildId", upg=u)
r=copy.deepcopy(base_recovery); r["buildId"]="1"*40
expect_fail("buildId mixing (recovery)", "buildId", rec=r)
r=copy.deepcopy(base_recovery); r["runnerOutcomes"]=r["runnerOutcomes"][:-1]
expect_fail("truncated recovery runner outcomes", "every M1 acceptance step exactly once", rec=r)

# --- dev mode ---
u=copy.deepcopy(base_upgrade); u["mode"]="dev"
expect_fail("dev-mode upgrade", "mode must be 'pr'", upg=u)
s=copy.deepcopy(base_soak); s["mode"]="dev"
expect_fail("dev-mode soak", "mode must be 'pr'", soak=s)

# --- dirty evidence ---
c=copy.deepcopy(base_cycle); c["workingTreeDirty"]=True
expect_fail("dirty state-cycle", "workingTreeDirty must be explicitly false", cyc=c)
r=copy.deepcopy(base_recovery); r["workingTreeDirty"]=True
expect_fail("dirty recovery", "workingTreeDirty must be explicitly false", rec=r)
u=copy.deepcopy(base_upgrade); del u["workingTreeDirty"]
expect_fail("missing upgrade dirty provenance", "workingTreeDirty must be explicitly false", upg=u)

# --- short soak (not completed) ---
s=copy.deepcopy(base_soak); s["duration"]["completed"]=False
expect_fail("short soak (not completed)", "duration.completed must be true", soak=s)
# --- short soak (completedSeconds < 7200) ---
s=copy.deepcopy(base_soak); s["duration"]["completedSeconds"]=3600.0
expect_fail("short soak (completedSeconds < 7200)", "duration.completedSeconds must be", soak=s)
# A claimed completedSeconds value cannot disguise an actually short wall-clock interval.
s=copy.deepcopy(base_soak); s["endedAt"]="2026-08-05T01:00:00Z"
expect_fail("short soak (wall-clock timestamps < 7200)", "timestamp interval must prove", soak=s)
# --- wrong duration.requested ---
s=copy.deepcopy(base_soak); s["duration"]["requested"]="PT1H"
expect_fail("short soak (requested PT1H)", "duration.requested must be PT2H", soak=s)
# --- soak steady-state / disposable-loader proof is mandatory ---
s=copy.deepcopy(base_soak); s["measurementWarmup"]["steadyStateEstablished"]=False
expect_fail("soak without steady-state proof", "steadyStateEstablished must be true", soak=s)
s=copy.deepcopy(base_soak); s["measurementWarmup"]["eligibleLifecycleLoadersOutstanding"]=3
expect_fail("soak with unreclaimed lifecycle loader", "reclamation was not proven", soak=s)
s=copy.deepcopy(base_soak); s["measurementWarmup"]["latestCohortGraceLoaders"]=33
expect_fail("soak with oversized loader grace cohort", "grace exceeds one sample cohort", soak=s)

# --- reduced cycle count ---
c=copy.deepcopy(base_cycle); c["cycles"]["requested"]=5000
expect_fail("reduced cycle count", "cycles.requested must be 10000", cyc=c)
c=copy.deepcopy(base_cycle); c["cycles"]["failed"]=1
expect_fail("non-zero cycle failures", "cycles.failed must be 0", cyc=c)

# --- missing compatibility row ---
comp=copy.deepcopy(base_compat); comp["rows"]=[r for r in comp["rows"] if r["scenario"]!="C05"]
expect_fail("missing compat row C05", "scenario catalog must be exactly C01-C10", comp=comp)
# --- duplicate row ---
comp=copy.deepcopy(base_compat); comp["rows"].append({"scenario":"C01","status":"PASSED","buildId":FIX})
expect_fail("duplicate compat row", "duplicate row for scenario C01", comp=comp)
# --- formal row FAILED ---
comp=copy.deepcopy(base_compat);
for r in comp["rows"]:
    if r["scenario"]=="C03": r["status"]="FAILED"
expect_fail("formal row C03 FAILED", "final RC row C03 must be PASSED", comp=comp)
comp=copy.deepcopy(base_compat)
for row in comp["rows"]:
    if row["scenario"]=="C09": row["status"]="EXPERIMENTAL"
expect_fail("C09 EXPERIMENTAL rejected for final RC", "final RC row C09 must be PASSED", comp=comp)

# --- unsupported environment (non-Linux) ---
c=copy.deepcopy(base_cycle); c["environment"]["osName"]="Darwin"
expect_fail("non-Linux state-cycle", "environment.osName must be Linux", cyc=c)
s=copy.deepcopy(base_soak); s["environment"]["jdkVersion"]="17.0.11"
expect_fail("non-JDK21 soak", "environment.jdkVersion must be 21", soak=s)
u=copy.deepcopy(base_upgrade); u["environment"]["os"]="Darwin"; u["environment"]["osName"]="Darwin"
expect_fail("non-Linux upgrade", "environment.osName must be Linux", upg=u)

# --- rollback failure ---
u=copy.deepcopy(base_upgrade); u["rollbackGuard"]["result"]="NOT_REJECTED"
expect_fail("rollback guard not rejected", "rollbackGuard.result must be REJECTED", upg=u)
# --- upgrade not authoritative ---
u=copy.deepcopy(base_upgrade); u["authoritative"]=False
expect_fail("upgrade not authoritative", "authoritative must be true", upg=u)

# --- frozen upgrade lineage / immutable infrastructure identity ---
u=copy.deepcopy(base_upgrade); u["fromCommit"]="3"*40
expect_fail("upgrade wrong V1.6 baseline", "fromCommit must equal", upg=u)
u=copy.deepcopy(base_upgrade); u["toCommit"]="3"*40
expect_fail("upgrade toCommit differs from candidate", "toCommit must equal", upg=u)
u=copy.deepcopy(base_upgrade); u["environment"]["redis"]["digest"]=""
expect_fail("upgrade missing immutable image identity", "immutable image digest/id is required", upg=u)

# --- corrupted backup hash ---
u=copy.deepcopy(base_upgrade); u["backup"]["sha256"]="corrupted"
expect_fail("corrupted backup hash", "backup.sha256 must be 64-hex", upg=u)

# --- missing defect inventory (absent file is NOT silently treated as zero defects) ---
expect_fail("missing defect inventory file", "evidence file not found", defe_path=os.path.join(ROOT,"nope-defects.json"))
# --- defect inventory template (not authoritative) ---
d={"schemaVersion":"1.0","status":"template","buildId":FIX,"defects":[]}
expect_fail("defect inventory template", "status must be 'authoritative'", defe=d)
# --- a wildcard/token is never accepted as a candidate binding ---
d={"schemaVersion":"1.0","status":"authoritative","buildId":"*","defects":[]}
expect_fail("defect inventory wildcard buildId", "!= candidate", defe=d)
d={"schemaVersion":"1.0","status":"authoritative","buildId":"$"+"{CANDIDATE_SHA}","defects":[]}
expect_fail("defect inventory unresolved token", "!= candidate", defe=d)
# --- malformed severity/status fail closed ---
d={"schemaVersion":"1.0","status":"authoritative","buildId":FIX,"defects":[
   {"id":"B-invalid-severity","severity":"critical","status":"open"}]}
expect_fail("defect inventory invalid severity", "invalid severity", defe=d)
d={"schemaVersion":"1.0","status":"authoritative","buildId":FIX,"defects":[
   {"id":"B-invalid-status","severity":"P3","status":"ignored"}]}
expect_fail("defect inventory invalid status", "invalid status", defe=d)
# --- P0/P1 present ---
d={"schemaVersion":"1.0","status":"authoritative","buildId":FIX,"defects":[
   {"id":"B1","severity":"P1","status":"open","title":"x"}]}
expect_fail("open P1 defect", "open P0/P1 defects block", defe=d)
# --- P2 without owner/workaround/target ---
d={"schemaVersion":"1.0","status":"authoritative","buildId":FIX,"defects":[
   {"id":"B2","severity":"P2","status":"open","title":"x"}]}
expect_fail("open P2 without owner", "must have owner", defe=d)
# P2 with full owner is accepted
d={"schemaVersion":"1.0","status":"authoritative","buildId":FIX,"defects":[
   {"id":"B3","severity":"P2","status":"open","owner":"o","workaround":"w","targetVersion":"1.7.1"}]}
expect_ok("open P2 with owner+workaround+target accepted", defe=d)

# --- path traversal: evidence outside the approved root ---
outside=os.path.join(TEST_TMP,"outside.json"); json.dump(base_upgrade,open(outside,"w"))
expect_fail("evidence path outside root", "outside the approved evidence root", upg_path=outside)
# absolute paths are rejected even when they point under the approved root
expect_fail("absolute path rejected", "must be relative", upg_path=os.path.abspath(os.path.join(ROOT,"upgrade.json")))
# '..' path rejected (create the file at the traversal target so the path-safety check fires)
dotdot_target=os.path.join(TEST_TMP,"traversal-upgrade.json")
json.dump(base_upgrade,open(dotdot_target,"w"))
dotdot=os.path.join(ROOT,"..","traversal-upgrade.json")
expect_fail("path traversal '..'", "must not contain '..'", upg_path=dotdot)

# --- stale artifact (file missing) ---
expect_fail("stale/missing artifact", "evidence file not found", upg_path=os.path.join(ROOT,"nope.json"))

# --- verify a PASSED result ---
r=run_agg(output=os.path.join(ROOT,"rc-ok.json"))
if r.returncode!=0: bad("verify precondition (aggregate PASSED)", r.stderr.strip())
else:
    vr=subprocess.run(["./scripts/v1.7/verify-rc-certification.sh",os.path.join(ROOT,"rc-ok.json")],capture_output=True,text=True)
    if vr.returncode==0: ok("verify PASSED on a valid result")
    else: bad("verify PASSED on a valid result", vr.stderr.strip())
    # verify re-hashes evidence and rejects post-aggregate tampering
    recovery_path=os.path.join(ROOT,"recovery.json")
    original=open(recovery_path).read()
    open(recovery_path,"a").write("\n")
    vt=subprocess.run(["./scripts/v1.7/verify-rc-certification.sh",os.path.join(ROOT,"rc-ok.json")],capture_output=True,text=True)
    if vt.returncode!=0 and "hash mismatch" in vt.stderr: ok("verify rejects tampered evidence hash")
    else: bad("verify rejects tampered evidence hash",vt.stderr.strip())
    open(recovery_path,"w").write(original)

    # promote refuses the fixture buildId after full underlying evidence validation
    manifest=os.path.join(ROOT,"manifest.json")
    reqs=[]
    for rid in ("V17-RECOVERY","V17-UPGRADE","V17-PERF","V17-COMPAT","V17-SOAK"):
        reqs.append({"id":rid,"gates":{"PR":{"status":"PASSED"},"RC":{"status":"NOT_RUN"},"RELEASE":{"status":"NOT_RUN"}}})
    json.dump({"schemaVersion":"2.0","release":"V1.7.0","buildId":FIX,"requirements":reqs},open(manifest,"w"))
    pr=subprocess.run(["./scripts/v1.7/promote-rc-gates.sh","--result",os.path.join(ROOT,"rc-ok.json"),
        "--manifest",manifest,"--dry-run"],capture_output=True,text=True)
    if pr.returncode!=0 and "fixture sentinel" in pr.stderr: ok("promote refuses fixture buildId")
    else: bad("promote refuses fixture buildId",f"rc={pr.returncode} stderr={pr.stderr.strip()}")

    # Exercise the successful promotion transform without claiming that the synthetic
    # SHA is a real repository commit: _git is narrowly stubbed in-process. This checks
    # manifest identity, repository-rooted report paths, gate scope, and PR/RELEASE
    # preservation independently of the CLI's real-commit/clean-tree guards above.
    REAL="2"*40
    rr=copy.deepcopy(base_recovery); rr["buildId"]=REAL
    uu=copy.deepcopy(base_upgrade); uu["buildId"]=REAL; uu["toCommit"]=REAL
    cc=copy.deepcopy(base_compat); cc["buildId"]=REAL
    for row in cc["rows"]: row["buildId"]=REAL
    cy=copy.deepcopy(base_cycle); cy["buildId"]=REAL
    ss=copy.deepcopy(base_soak); ss["buildId"]=REAL
    dd=copy.deepcopy(base_defects); dd["buildId"]=REAL
    real_result=os.path.join(ROOT,"rc-real.json")
    real_agg=run_agg(rec=rr,upg=uu,comp=cc,cyc=cy,soak=ss,defe=dd,
                     build_id=REAL,output=real_result)
    if real_agg.returncode!=0:
        bad("promotion transform precondition",real_agg.stderr.strip())
    else:
        import importlib.util
        spec=importlib.util.spec_from_file_location("rc_certlib_under_test",LIB)
        certlib=importlib.util.module_from_spec(spec); spec.loader.exec_module(certlib)
        certlib._git=lambda root,*args: REAL if args and args[0]=="rev-parse" else ""
        promote_manifest=os.path.join(ROOT,"promote-manifest.json")
        preqs=[]
        for rid in ("V17-RECOVERY","V17-UPGRADE","V17-PERF","V17-COMPAT","V17-SOAK"):
            preqs.append({"id":rid,"gates":{"PR":{"status":"PASSED","marker":"keep-pr"},
                "RC":{"status":"NOT_RUN"},"RELEASE":{"status":"NOT_RUN","marker":"keep-release"}}})
        json.dump({"schemaVersion":"2.0","release":"V1.7.0","buildId":"1"*40,
                   "generatedAt":"old","requirements":preqs},open(promote_manifest,"w"))
        certlib.promote(real_result,promote_manifest,TEST_TMP)
        promoted=json.load(open(promote_manifest))
        gates=[r["gates"] for r in promoted["requirements"]]
        reports=[p for g in gates for p in g["RC"]["reports"]]
        if promoted["buildId"]==REAL and promoted["generatedAt"]==json.load(open(real_result))["endedAt"]:
            ok("promotion binds top-level manifest identity to candidate")
        else: bad("promotion binds top-level manifest identity to candidate")
        if all(g["RC"]["status"]=="PASSED" for g in gates) and all(
                g["PR"].get("marker")=="keep-pr" and g["RELEASE"].get("marker")=="keep-release" for g in gates):
            ok("promotion updates only authorized RC gates")
        else: bad("promotion updates only authorized RC gates")
        if reports and all(p.startswith("target/v1.7/") for p in reports):
            ok("promotion records repository-rooted evidence paths")
        else: bad("promotion records repository-rooted evidence paths",reports)

    # verify rejects a FAILED result
    rf=run_agg(upg=copy.deepcopy({**base_upgrade,"status":"FAILED"}),output=os.path.join(ROOT,"rc-fail.json"))
    vrf=subprocess.run(["./scripts/v1.7/verify-rc-certification.sh",os.path.join(ROOT,"rc-fail.json")],capture_output=True,text=True)
    if vrf.returncode!=0: ok("verify FAILED on a failed result")
    else: bad("verify FAILED on a failed result","expected non-zero")

print(f"PASS={PASS} FAIL={FAIL}")
sys.exit(1 if FAIL else 0)
PY
PY_RC=$?
set -e
PYLINE="$(tail -n 1 "$PY_RESULT")"
# Merge the python block's counts into the bash totals (last stdout line: PASS=N FAIL=M).
if [[ "$PYLINE" == PASS=*FAIL=* ]]; then
  PYPASS="${PYLINE#PASS=}"; PYPASS="${PYPASS%% FAIL=*}"; PYFAIL="${PYLINE##*FAIL=}"
  PASS=$((PASS + PYPASS)); FAIL=$((FAIL + PYFAIL))
else
  bad "python fixture block (no PASS= line)"
fi
if [ "$PY_RC" -ne 0 ] && [ "$PYLINE" != "PASS=${PYPASS:-0} FAIL=${PYFAIL:-0}" ]; then
  bad "python fixture block (exit $PY_RC)"
fi

echo ""
echo "=============================================="
echo "M6-A focused tests: PASS=$PASS FAIL=$FAIL"
if [ "$FAIL" -ne 0 ]; then
  printf 'Failed:\n' >&2
  for n in "${FAILED_NAMES[@]}"; do printf '  - %s\n' "$n" >&2; done
  exit 1
fi
echo "All M6-A focused tests passed."
exit 0
