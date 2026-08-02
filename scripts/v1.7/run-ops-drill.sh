#!/usr/bin/env bash
#
# scripts/v1.7/run-ops-drill.sh
#
# V1.7 M4-D (roadmap §11.5) controlled operations drill runner. Produces
# target/v1.7/ops-result.json from REAL exit codes and Surefire XML structural validation - never
# from console keyword matching. It exercises a fixed, monotonic, fail-closed sequence:
#
#   1. verify-runbooks            ./scripts/v1.7/verify-runbooks.sh  (link + AUTOMATED-SAFE blocks)
#   2. m4-focused-tests           the fixed §11.5 focused M4 command  (Health/Metrics/SupportBundle)
#   3. ops-emergency-boundary     the isolated kairo-ops loopback test (OpsEmergencyBoundaryTest)
#   4. git-diff-check             git diff --check
#
# Step 3 proves the documented kairo-ops emergency command path, the X-Agent-Token header, the
# fixed {reason,eventId}/{classId,reason,eventId} body, the local audit line, and the
# disable->verify->enable recovery loop against an in-process loopback stub Agent - never a real
# Agent and never external infrastructure.
#
# The drill writes JSON atomically and returns non-zero for: missing reports, malformed XML/JSON,
# failed or skipped mandatory steps, duplicate step ids, build-id mismatch, or command failure.
# It does NOT claim an RC PostgreSQL/Redis outage or a real emergency mutation. PR drill evidence
# may be PASSED; V17-OPS.RC remains NOT_RUN.
#
# Usage:
#   ./scripts/v1.7/run-ops-drill.sh [--output target/v1.7/ops-result.json] [--help]
#
# Exit codes:
#   0  all mandatory steps PASSED, build id stable, JSON written + validated
#   1  usage error
#   2  a mandatory step failed/was skipped, build mismatch, missing/malformed report, or JSON failure

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
DEFAULT_OUTPUT="target/v1.7/ops-result.json"
OUTPUT=""
SCHEMA_VERSION="1.0"

usage() {
  cat <<'EOF'
Usage: run-ops-drill.sh [--output <path>] [--help]

  --output   Output path for the drill result JSON
             (default: target/v1.7/ops-result.json, relative to the repository root).
  --help     Show this help.

Runs a fixed, monotonic, fail-closed sequence:
  1. verify-runbooks.sh            link + AUTOMATED-SAFE block verification
  2. §11.5 focused M4 tests         *Health*Test,*Metrics*Test,*SupportBundle*Test
  3. kairo-ops emergency boundary  OpsEmergencyBoundaryTest (isolated loopback)
  4. git diff --check

Surefire reports are validated structurally (tests > 0, failures == 0, errors == 0). The result
JSON is written atomically. PR evidence may be PASSED; RC remains NOT_RUN (no real outage or
emergency mutation is performed).

Exit codes: 0 ok | 1 usage | 2 step/validation/build/JSON failure.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --output)
      [[ $# -lt 2 ]] && { echo "error: --output requires a value" >&2; exit 1; }
      OUTPUT="$2"; shift 2 ;;
    *) echo "error: unknown argument: $1" >&2; usage; exit 1 ;;
  esac
done
[[ -z "$OUTPUT" ]] && OUTPUT="$DEFAULT_OUTPUT"

# Resolve OUTPUT relative to the repository root. Evidence writes are confined to target/v1.7;
# accepting an arbitrary path would turn an operations test helper into a file-overwrite primitive.
if [[ "$OUTPUT" != /* ]]; then
  OUTPUT="$REPO_ROOT/$OUTPUT"
fi
OUTPUT="$(python3 - "$OUTPUT" <<'PY'
import os, sys
print(os.path.realpath(sys.argv[1]))
PY
)"
ALLOWED_OUTPUT_ROOT="$REPO_ROOT/target/v1.7"
case "$OUTPUT" in
  "$ALLOWED_OUTPUT_ROOT"/*) ;;
  *) echo "error: --output must be below target/v1.7" >&2; exit 1 ;;
esac
OUT_DIR="$(dirname "$OUTPUT")"; mkdir -p "$OUT_DIR"

JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/java}"
JAVA_BIN="${JAVA_BIN:-java}"
MVN="${MVN:-mvn}"

# --- Build identity + environment (captured up front). ------------------------------------
BUILD_ID_START="$(git -C "$REPO_ROOT" rev-parse 'HEAD^{commit}')"
if ! [[ "$BUILD_ID_START" =~ ^[0-9a-f]{40}$ ]]; then
  echo "error: could not resolve a 40-hex HEAD commit (got: $BUILD_ID_START)" >&2
  exit 1
fi
DIRTY="false"
if [[ -n "$(git -C "$REPO_ROOT" status --porcelain)" ]]; then
  DIRTY="true"
fi
workspace_fingerprint() {
  REPO_ROOT="$REPO_ROOT" python3 <<'PY'
import hashlib, os, subprocess
root = os.environ["REPO_ROOT"]
h = hashlib.sha256()
h.update(subprocess.check_output(["git", "-C", root, "diff", "--binary", "HEAD", "--", "."]))
raw = subprocess.check_output(["git", "-C", root, "ls-files", "--others", "--exclude-standard", "-z"])
for rel_b in sorted(p for p in raw.split(b"\0") if p):
    rel = rel_b.decode("utf-8", "surrogateescape")
    h.update(b"UNTRACKED\0" + rel_b + b"\0")
    path = os.path.join(root, rel)
    if os.path.isfile(path) and not os.path.islink(path):
        with open(path, "rb") as fh:
            for chunk in iter(lambda: fh.read(1024 * 1024), b""):
                h.update(chunk)
print(h.hexdigest())
PY
}
WORKSPACE_HASH_START="$(workspace_fingerprint)"
STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
OS_NAME="$(uname -s)"
OS_ARCH="$(uname -m)"
BASH_VER="${BASH_VERSION:-unknown}"
JAVA_VER="$("$JAVA_BIN" -version 2>&1 | head -1 | sed 's/^/Java: /; s/"/ /g' || echo 'Java: unknown')"
MVN_VER="$("$MVN" -v 2>/dev/null | head -1 || echo 'Maven: unknown')"

# --- Step results accumulator (TSV: id \t command \t exitCode \t status \t reports \t sf*). --
STEPS_TSV="$(mktemp -t kairo-opsdrill-steps-XXXXXX)"
LIMITATIONS="$(mktemp -t kairo-opsdrill-limits-XXXXXX)"
SCRATCH="$(mktemp -d -t kairo-opsdrill-XXXXXX)"
trap 'rm -f "$STEPS_TSV" "$LIMITATIONS"; rm -rf "$SCRATCH" 2>/dev/null || true' EXIT

cat >> "$LIMITATIONS" <<'EOF'
PR drill evidence only. No RC PostgreSQL/Redis outage or real emergency mutation was performed; V17-OPS.RC remains NOT_RUN.
The kairo-ops emergency boundary is exercised against an in-process loopback stub Agent (OpsEmergencyBoundaryTest), not a real Agent or external infrastructure.
EOF

# Fixed commands (recorded verbatim in the result).
CMD_VERIFY="./scripts/v1.7/verify-runbooks.sh"
CMD_M4="mvn -B -ntp -pl kairo-platform-server,kairo-cli,kairo-ops -am test -Dtest='*Health*Test,*Metrics*Test,*SupportBundle*Test' -Dsurefire.failIfNoSpecifiedTests=false"
CMD_BOUNDARY="mvn -B -ntp -pl kairo-ops -am test -Dtest='OpsEmergencyBoundaryTest' -Dsurefire.failIfNoSpecifiedTests=false"
CMD_GITDIFF="git diff --check"

# All mandatory step ids (used for duplicate-id and completeness checks).
STEP_IDS=(verify-runbooks m4-focused-tests ops-emergency-boundary git-diff-check)

# --- Surefire structural validation (fresh reports only; globs + aggregates TEST-*.xml). -----
# Arguments: module dirs, report patterns, minimum mtime_ns, required relative report paths.
# Prints:
#   OK<TAB>tests<TAB>failures<TAB>errors<TAB>skipped<TAB>files<TAB>file1;file2;...
#   ERR<TAB>message
validate_surefire() {
  REPO_ROOT="$REPO_ROOT" SUREFIRE_DIRS="$1" SUREFIRE_PATTERNS="$2" \
    SUREFIRE_MIN_NS="$3" SUREFIRE_REQUIRED="$4" python3 <<'PY'
import os, glob, sys
try:
    import xml.etree.ElementTree as ET
except Exception as e:
    print("ERR\tcannot import xml: %s" % e)
    sys.exit(0)
dirs = [d for d in os.environ.get("SUREFIRE_DIRS", "").split(",") if d]
pats = [p for p in os.environ.get("SUREFIRE_PATTERNS", "").split(",") if p]
files = []
try:
    minimum_ns = int(os.environ["SUREFIRE_MIN_NS"])
except (KeyError, ValueError):
    print("ERR\tinvalid minimum report timestamp")
    sys.exit(0)
for d in dirs:
    base = os.path.join(os.environ.get("REPO_ROOT", "."), d, "target", "surefire-reports")
    for p in pats:
        files.extend(sorted(glob.glob(os.path.join(base, p))))
# De-duplicate while preserving order.
seen = set(); uniq = []
for f in files:
    if f not in seen:
        seen.add(f); uniq.append(f)
files = [f for f in uniq if os.stat(f).st_mtime_ns >= minimum_ns]
if not files:
    print("ERR\tno fresh Surefire reports matched this command (dirs=%s patterns=%s)" % (",".join(dirs), ",".join(pats)))
    sys.exit(0)
relative = {os.path.relpath(f, os.environ.get("REPO_ROOT", ".")) for f in files}
required = {r for r in os.environ.get("SUREFIRE_REQUIRED", "").split(";") if r}
missing = sorted(required - relative)
if missing:
    print("ERR\tmissing fresh required report(s): %s" % ",".join(missing))
    sys.exit(0)
tests = failures = errors = skipped = 0
for f in files:
    try:
        root = ET.parse(f).getroot()
    except Exception as e:
        print("ERR\tmalformed XML in %s: %s" % (f, e))
        sys.exit(0)
    def attr(name):
        try:
            return int(root.get(name) or 0)
        except ValueError:
            print("ERR\tnon-integer %s= in %s" % (name, f))
            return None
    t = attr("tests"); fa = attr("failures"); er = attr("errors"); sk = attr("skipped")
    if None in (t, fa, er, sk):
        sys.exit(0)
    tests += t; failures += fa; errors += er; skipped += sk
names = [os.path.relpath(f, os.environ.get("REPO_ROOT", ".")) for f in files]
if tests <= 0:
    print("ERR\taggregate tests=%d (expected > 0) across %d file(s)" % (tests, len(files)))
elif failures != 0 or errors != 0:
    print("ERR\taggregate failures=%d errors=%d (expected 0) across %d file(s)" % (failures, errors, len(files)))
else:
    print("OK\t%d\t%d\t%d\t%d\t%d\t%s" % (tests, failures, errors, skipped, len(files), ";".join(names)))
PY
}

# Run a step: execute the command (set -e safe), capture exit code, append a TSV row.
# append_step <id> <command> <exitCode> <status> <reports> <sf...> [detail]
append_step() {
  local id="$1" cmd="$2" code="$3" status="$4" reports="$5"
  local sf_tests="${6:--1}" sf_fail="${7:-0}" sf_err="${8:-0}" sf_skip="${9:-0}" sf_files="${10:-0}"
  local detail="${11:-}"; detail="${detail//$'\t'/ }"; detail="${detail//$'\n'/ }"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$id" "$cmd" "$code" "$status" "$reports" "$sf_tests" "$sf_fail" "$sf_err" "$sf_skip" "$sf_files" "$detail" >> "$STEPS_TSV"
}

echo "==> ops drill: build=$BUILD_ID_START dirty=$DIRTY os=$OS_NAME/$OS_ARCH"
echo "==> started=$STARTED_AT"

OVERALL="PASSED"
ABORTED="false"
now_ns() {
  python3 <<'PY'
import time
print(time.time_ns())
PY
}

# --- Step 1: verify-runbooks. ---------------------------------------------------------------
echo "==> [1/4] verify-runbooks"
code=0
( cd "$REPO_ROOT" && bash -c "$CMD_VERIFY" ) >$SCRATCH/step1.out 2>&1 || code=$?
if [[ "$code" -eq 0 ]]; then
  append_step "verify-runbooks" "$CMD_VERIFY" 0 "PASSED" "docs/ops/v1.7-lts-runbook.md" -1 0 0 0 0
else
  append_step "verify-runbooks" "$CMD_VERIFY" "$code" "FAILED" "" -1 0 0 0 0
  OVERALL="FAILED"; ABORTED="true"
fi

# --- Step 2: §11.5 focused M4 tests + Surefire validation. ----------------------------------
if [[ "$ABORTED" == "false" ]]; then
  echo "==> [2/4] m4-focused-tests"
  step_started_ns="$(now_ns)"
  code=0
  ( cd "$REPO_ROOT" && bash -c "$CMD_M4" ) >$SCRATCH/step2.out 2>&1 || code=$?
  sf=""
  if [[ "$code" -eq 0 ]]; then
    required_reports="kairo-api/target/surefire-reports/TEST-com.example.kairo.api.support.SupportBundleWriterTest.xml;kairo-platform-server/target/surefire-reports/TEST-com.example.kairo.platform.command.BusinessMetricsTest.xml;kairo-platform-server/target/surefire-reports/TEST-com.example.kairo.platform.health.DependencyHealthRecoveryIntegrationTest.xml;kairo-platform-server/target/surefire-reports/TEST-com.example.kairo.platform.health.HealthGroupRecoveryTest.xml;kairo-platform-server/target/surefire-reports/TEST-com.example.kairo.platform.health.RedisHealthRecoveryIntegrationTest.xml;kairo-platform-server/target/surefire-reports/TEST-com.example.kairo.platform.metrics.KairoMetricsCatalogTest.xml;kairo-cli/target/surefire-reports/TEST-com.example.kairo.cli.KairoCliSupportBundleTest.xml;kairo-ops/target/surefire-reports/TEST-com.example.kairo.ops.OpsSupportBundleTest.xml"
    sf="$(validate_surefire "kairo-api,kairo-platform-server,kairo-cli,kairo-ops" "TEST-*Health*Test.xml,TEST-*Metrics*Test.xml,TEST-*SupportBundle*Test.xml" "$step_started_ns" "$required_reports")"
  fi
  if [[ "$code" -eq 0 && "$sf" == OK* ]]; then
    IFS=$'\t' read -r _ t f e s fl files <<< "$sf"
    append_step "m4-focused-tests" "$CMD_M4" 0 "PASSED" "$files" "$t" "$f" "$e" "$s" "$fl"
  else
    evidence_code="$code"; [[ "$evidence_code" -ne 0 ]] || evidence_code=2
    reason="mvn exit $code"
    [[ "$sf" == ERR* ]] && reason="${sf#ERR$'\t'}; mvn exit $code"
    append_step "m4-focused-tests" "$CMD_M4" "$evidence_code" "FAILED" "" -1 0 0 0 0 "$reason"
    OVERALL="FAILED"; ABORTED="true"
  fi
fi

# --- Step 3: kairo-ops emergency boundary (isolated loopback) + Surefire validation. --------
if [[ "$ABORTED" == "false" ]]; then
  echo "==> [3/4] ops-emergency-boundary"
  step_started_ns="$(now_ns)"
  code=0
  ( cd "$REPO_ROOT" && bash -c "$CMD_BOUNDARY" ) >$SCRATCH/step3.out 2>&1 || code=$?
  sf=""
  if [[ "$code" -eq 0 ]]; then
    sf="$(validate_surefire "kairo-ops" "TEST-*OpsEmergencyBoundaryTest.xml" "$step_started_ns" "kairo-ops/target/surefire-reports/TEST-com.example.kairo.ops.OpsEmergencyBoundaryTest.xml")"
  fi
  if [[ "$code" -eq 0 && "$sf" == OK* ]]; then
    IFS=$'\t' read -r _ t f e s fl files <<< "$sf"
    append_step "ops-emergency-boundary" "$CMD_BOUNDARY" 0 "PASSED" "$files" "$t" "$f" "$e" "$s" "$fl"
  else
    evidence_code="$code"; [[ "$evidence_code" -ne 0 ]] || evidence_code=2
    reason="mvn exit $code"
    [[ "$sf" == ERR* ]] && reason="${sf#ERR$'\t'}; mvn exit $code"
    append_step "ops-emergency-boundary" "$CMD_BOUNDARY" "$evidence_code" "FAILED" "" -1 0 0 0 0 "$reason"
    OVERALL="FAILED"; ABORTED="true"
  fi
fi

# --- Step 4: git diff --check. -------------------------------------------------------------
if [[ "$ABORTED" == "false" ]]; then
  echo "==> [4/4] git-diff-check"
  code=0
  ( cd "$REPO_ROOT" && git diff --check ) >$SCRATCH/step4.out 2>&1 || code=$?
  if [[ "$code" -eq 0 ]]; then
    append_step "git-diff-check" "$CMD_GITDIFF" 0 "PASSED" "" -1 0 0 0 0
  else
    append_step "git-diff-check" "$CMD_GITDIFF" "$code" "FAILED" "" -1 0 0 0 0
    OVERALL="FAILED"; ABORTED="true"
  fi
fi

# Mark any not-yet-recorded mandatory steps as SKIPPED (fail-closed: never silently drop a step).
recorded_ids="$(cut -f1 "$STEPS_TSV" 2>/dev/null || true)"
for sid in "${STEP_IDS[@]}"; do
  if ! grep -qx "$sid" <<< "$recorded_ids"; then
    append_step "$sid" "(not executed)" 2 "SKIPPED" "" -1 0 0 0 0 "earlier mandatory step failed"
    OVERALL="FAILED"
  fi
done

# --- Build-id stability check (HEAD must not change during the drill). ---------------------
ENDED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
BUILD_ID_END="$(git -C "$REPO_ROOT" rev-parse 'HEAD^{commit}')"
BUILD_MISMATCH="false"
if [[ "$BUILD_ID_START" != "$BUILD_ID_END" ]]; then
  BUILD_MISMATCH="true"
  OVERALL="FAILED"
fi
WORKSPACE_HASH_END="$(workspace_fingerprint)"
WORKSPACE_MISMATCH="false"
if [[ "$WORKSPACE_HASH_START" != "$WORKSPACE_HASH_END" ]]; then
  WORKSPACE_MISMATCH="true"
  OVERALL="FAILED"
fi

# --- Assemble + validate + atomically write the result JSON. --------------------------------
set +e
REPO_ROOT="$REPO_ROOT" \
DRILL_SCHEMA="$SCHEMA_VERSION" \
DRILL_BUILDID="$BUILD_ID_START" \
DRILL_MODE="pr" \
DRILL_DIRTY="$DIRTY" \
DRILL_OS="$OS_NAME" \
DRILL_ARCH="$OS_ARCH" \
DRILL_JAVA="$JAVA_VER" \
DRILL_BASH="$BASH_VER" \
DRILL_MVN="$MVN_VER" \
DRILL_START="$STARTED_AT" \
DRILL_END="$ENDED_AT" \
DRILL_BUILD_MISMATCH="$BUILD_MISMATCH" \
DRILL_WORKSPACE_HASH_START="$WORKSPACE_HASH_START" \
DRILL_WORKSPACE_HASH_END="$WORKSPACE_HASH_END" \
DRILL_WORKSPACE_MISMATCH="$WORKSPACE_MISMATCH" \
DRILL_OUTPUT="$OUTPUT" \
python3 - "$STEPS_TSV" "$LIMITATIONS" <<'PY'
import json, os, sys, tempfile

steps_tsv = sys.argv[1]
limitations_file = sys.argv[2]
repo = os.environ["REPO_ROOT"]

def parse_steps(path):
    rows = []
    if not os.path.isfile(path):
        return rows
    with open(path, encoding='utf-8') as fh:
        for line in fh:
            line = line.rstrip('\n')
            if not line:
                continue
            f = line.split('\t')
            # id, command, exitCode, status, reports, sf_tests, sf_fail, sf_err, sf_skip, sf_files, detail
            while len(f) < 11:
                f.append('')
            sid, cmd, code, status, reports, sft, sff, sfe, sfs, sfl, detail = f[:11]
            step = {
                "id": sid,
                "command": cmd,
                "exitCode": int(code) if code.lstrip('-').isdigit() else None,
                "status": status,
                "reports": [r for r in reports.split(';') if r],
            }
            if sft.lstrip('-').isdigit() and int(sft) >= 0:
                step["surefire"] = {
                    "tests": int(sft), "failures": int(sff), "errors": int(sfe),
                    "skipped": int(sfs), "files": int(sfl),
                }
            if detail:
                step["detail"] = detail
            rows.append(step)
    return rows

steps = parse_steps(steps_tsv)

# Duplicate step-id check.
seen = set()
for st in steps:
    if st["id"] in seen:
        print("error: duplicate step id: %s" % st["id"], file=sys.stderr)
        sys.exit(2)
    seen.add(st["id"])

# Completeness check: every mandatory id present exactly once.
mandatory = ["verify-runbooks", "m4-focused-tests", "ops-emergency-boundary", "git-diff-check"]
present = [st["id"] for st in steps]
for m in mandatory:
    if present.count(m) != 1:
        print("error: mandatory step missing or duplicated: %s" % m, file=sys.stderr)
        sys.exit(2)

overall = "PASSED" if (all(st["status"] == "PASSED" for st in steps)
                       and os.environ["DRILL_BUILD_MISMATCH"] == "false"
                       and os.environ["DRILL_WORKSPACE_MISMATCH"] == "false") else "FAILED"

with open(limitations_file, encoding='utf-8') as fh:
    limitations = [l.rstrip('\n') for l in fh if l.strip()]

result = {
    "schemaVersion": os.environ["DRILL_SCHEMA"],
    "buildId": os.environ["DRILL_BUILDID"],
    "mode": os.environ["DRILL_MODE"],
    "dirty": os.environ["DRILL_DIRTY"] == "true",
    "buildMismatch": os.environ["DRILL_BUILD_MISMATCH"] == "true",
    "workspaceHashStart": os.environ["DRILL_WORKSPACE_HASH_START"],
    "workspaceHashEnd": os.environ["DRILL_WORKSPACE_HASH_END"],
    "workspaceMismatch": os.environ["DRILL_WORKSPACE_MISMATCH"] == "true",
    "environment": {
        "os": os.environ["DRILL_OS"],
        "arch": os.environ["DRILL_ARCH"],
        "java": os.environ["DRILL_JAVA"],
        "bash": os.environ["DRILL_BASH"],
        "mvn": os.environ["DRILL_MVN"],
    },
    "startedAt": os.environ["DRILL_START"],
    "endedAt": os.environ["DRILL_END"],
    "steps": steps,
    "overallStatus": overall,
    "limitations": limitations,
}

payload = json.dumps(result, indent=2, ensure_ascii=False) + "\n"
out = os.environ["DRILL_OUTPUT"]
# Atomic write: temp file in the same dir, then rename.
tmpfd, tmppath = tempfile.mkstemp(prefix=".ops-result-", dir=os.path.dirname(out))
try:
    with os.fdopen(tmpfd, 'w', encoding='utf-8') as fh:
        fh.write(payload)
    os.replace(tmppath, out)
except Exception:
    try:
        os.unlink(tmppath)
    except OSError:
        pass
    raise

# Re-read and re-validate the written JSON (catch a truncated/atomic-write failure).
try:
    with open(out, encoding='utf-8') as fh:
        json.load(fh)
except Exception as e:
    print("error: written result JSON is malformed: %s" % e, file=sys.stderr)
    sys.exit(2)

print("==> wrote %s (overall=%s)" % (out, overall))
sys.exit(0 if overall == "PASSED" else 2)
PY
DRILL_EXIT=$?
set -e

if [[ "$DRILL_EXIT" -ne 0 ]]; then
  echo "error: ops drill failed (overall=$OVERALL, buildMismatch=$BUILD_MISMATCH, workspaceMismatch=$WORKSPACE_MISMATCH)" >&2
  exit 2
fi

echo "==> ops drill PASSED: $OUTPUT"
exit 0
