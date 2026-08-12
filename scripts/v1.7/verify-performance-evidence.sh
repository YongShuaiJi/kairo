#!/usr/bin/env bash
#
# scripts/v1.7/verify-performance-evidence.sh
#
# V1.7 M2 evidence verifier (§9.5). Validates the schema, build ID, budgets and exit status
# of the M2 evidence artifacts already produced in an evidence directory, WITHOUT re-running
# any harness or test. Deep per-field validation is the responsibility of each harness's own
# Java validator (invoked at write time); this verifier does only a lightweight, schema-aware
# structural check so it does not duplicate those validators.
#
# Fixed interface (§9.5):
#   ./scripts/v1.7/verify-performance-evidence.sh target/v1.7
#
# Per present artifact:
#   - benchmark-result.json : schemaVersion, summary.failedScenarios==0 (exit status)
#   - state-cycle-result.json / leak-result.json / soak-result.json :
#       schemaVersion == "1.0", buildId is a 40-hex commit id, overall == "PASSED"
#   - soak-result.json additionally : the fixed cadence (PT1M/PT5M/PT30M) is recorded, the
#       budgets object is present, and timeSeries.rawPath points to an existing raw file
#       (a sibling of the result, in-repo/local).
#
# Exit codes:
#   0  all present evidence is structurally valid and none is FAILED
#   1  usage error, missing directory, no evidence found, or a present artifact is
#      structurally invalid / FAILED
#
# The verifier never re-runs a harness, never modifies budgets or the acceptance manifest,
# and never alters acceptance statuses.

set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "usage: $0 <evidence-dir> (e.g. target/v1.7)" >&2
  exit 1
fi

EVIDENCE_DIR="$1"
if [[ ! -d "$EVIDENCE_DIR" ]]; then
  echo "error: evidence directory not found: $EVIDENCE_DIR" >&2
  exit 1
fi

# python3 is the repo's evidence tooling runtime (see generate-recovery-evidence.py).
if ! command -v python3 >/dev/null 2>&1; then
  echo "error: python3 is required to validate evidence JSON" >&2
  exit 1
fi

# Known M2 evidence files, each tagged with its lightweight check kind:
#   standard  -> schemaVersion + 40-hex buildId + overall==PASSED
#   benchmark -> schemaVersion + summary.failedScenarios==0 (exit status)
#   soak      -> standard + fixed cadence + budgets + timeSeries.rawPath exists
KNOWN=(
  "benchmark-result.json:benchmark"
  "state-cycle-result.json:standard"
  "leak-result.json:standard"
  "soak-result.json:soak"
)

python3 - "$EVIDENCE_DIR" "${KNOWN[@]}" <<'PY'
import json, os, re, sys

evidence_dir = sys.argv[1]
known = sys.argv[2:]
hex40 = re.compile(r"^[0-9a-f]{40}$")
errors = []
present = 0


def check_standard(label, root, errors):
    sv = root.get("schemaVersion")
    if sv != "1.0":
        errors.append(f"{label}: schemaVersion must be '1.0' (got {sv!r})")
    build_id = root.get("buildId")
    if not isinstance(build_id, str) or not hex40.match(build_id):
        errors.append(f"{label}: buildId must be a 40-hex lowercase commit id (got {build_id!r})")
    overall = root.get("overall")
    if overall not in ("PASSED", "FAILED"):
        errors.append(f"{label}: overall must be PASSED or FAILED (got {overall!r})")
    elif overall != "PASSED":
        ff = root.get("firstFailure")
        detail = "" if not ff else f" (firstFailure.phase={ff.get('phase')!r})"
        errors.append(f"{label}: overall is FAILED{detail}")


def check_benchmark(label, root, errors):
    sv = root.get("schemaVersion")
    if sv != "1.0":
        errors.append(f"{label}: schemaVersion must be '1.0' (got {sv!r})")
    summary = root.get("summary")
    if not isinstance(summary, dict):
        errors.append(f"{label}: summary object is required (exit status)")
        return
    failed = summary.get("failedScenarios")
    if not isinstance(failed, int) or failed != 0:
        errors.append(f"{label}: summary.failedScenarios must be 0 (got {failed!r})")
    missing = summary.get("missingScenarios")
    if not isinstance(missing, list) or missing:
        errors.append(f"{label}: summary.missingScenarios must be an empty list (got {missing!r})")
    builds = root.get("builds")
    if not isinstance(builds, dict):
        errors.append(f"{label}: builds object is required")
    else:
        for side in ("baseline", "candidate"):
            build_id = (builds.get(side) or {}).get("resolvedBuildId")
            if not isinstance(build_id, str) or not hex40.match(build_id):
                errors.append(f"{label}: builds.{side}.resolvedBuildId must be 40-hex")


def resolve_raw(evidence_dir, raw):
    # The harness records either a repo-root-relative path or an absolute local path. Never fall
    # back to basename matching: that could validate a different file with the same name.
    candidates = [raw] if os.path.isabs(raw) else [raw, os.path.join(evidence_dir, raw)]
    for candidate in candidates:
        if os.path.isfile(candidate):
            return os.path.realpath(candidate)
    return None


def check_soak(label, root, evidence_dir, errors):
    check_standard(label, root, errors)
    warmup = root.get("measurementWarmup")
    if not isinstance(warmup, dict):
        errors.append(f"{label}: measurementWarmup object is required")
    else:
        if warmup.get("strategy") != "bounded-adaptive-metaspace-plateau":
            errors.append(f"{label}: measurementWarmup.strategy must be bounded-adaptive-metaspace-plateau")
        for key in ("enhanceUnloadBatch", "disconnectRecovery", "resourceSample",
                    "excludedFromDurationAndCycles", "steadyStateEstablished"):
            if warmup.get(key) is not True:
                errors.append(f"{label}: measurementWarmup.{key} must be true")
        batches = warmup.get("batchesRun")
        minimum = warmup.get("minimumLifecycleBatches")
        maximum = warmup.get("maximumLifecycleBatches")
        if not all(isinstance(value, int) for value in (batches, minimum, maximum)) \
                or not minimum <= batches <= maximum:
            errors.append(f"{label}: measurementWarmup.batchesRun must be inside its bounded range")
        observed = warmup.get("observedWindowMetaspaceGrowthPct")
        allowed = warmup.get("maxWindowMetaspaceGrowthPct")
        if not all(isinstance(value, (int, float)) for value in (observed, allowed)) \
                or observed > allowed:
            errors.append(f"{label}: measurementWarmup Metaspace plateau is not proven")
        outstanding = warmup.get("eligibleLifecycleLoadersOutstanding")
        allowed_outstanding = warmup.get("allowedOutstandingLifecycleLoaders")
        if not all(isinstance(value, int) for value in (outstanding, allowed_outstanding)) \
                or outstanding > allowed_outstanding:
            errors.append(f"{label}: measurementWarmup lifecycle ClassLoader reclamation is not proven")
        grace = warmup.get("latestCohortGraceLoaders")
        sample_every = warmup.get("sampleEveryBatches")
        if not all(isinstance(value, int) for value in (grace, sample_every)) or grace > sample_every:
            errors.append(f"{label}: measurementWarmup ClassLoader grace exceeds one sample cohort")
    cadence = root.get("cadence")
    if not isinstance(cadence, dict):
        errors.append(f"{label}: cadence object is required but missing")
    else:
        for k, want in (("summaryInterval", "PT1M"), ("batchInterval", "PT5M"),
                        ("disconnectInterval", "PT30M")):
            if cadence.get(k) != want:
                errors.append(f"{label}: cadence.{k} must be {want!r} (got {cadence.get(k)!r})")
    budgets = root.get("budgets")
    if not isinstance(budgets, dict) or not budgets:
        errors.append(f"{label}: budgets object is required but missing/empty")
    else:
        expected_budgets = {
            "maxHeapGrowthPct": 15,
            "maxMetaspaceGrowthPct": 10,
            "maxThreadDelta": 2,
            "maxFdDelta": 5,
            "driftThresholdSeconds": 300,
            "sustainedBreachWindowSeconds": 300,
        }
        for key, want in expected_budgets.items():
            if budgets.get(key) != want:
                errors.append(f"{label}: budgets.{key} must be {want} (got {budgets.get(key)!r})")
    ts = root.get("timeSeries")
    if not isinstance(ts, dict) or not ts.get("rawPath"):
        errors.append(f"{label}: timeSeries.rawPath is required")
    else:
        raw = ts["rawPath"]
        resolved_raw = resolve_raw(evidence_dir, raw)
        if resolved_raw is None:
            errors.append(f"{label}: timeSeries.rawPath does not exist: {raw}")
        if not isinstance(ts.get("count"), int) or ts["count"] < 1:
            errors.append(f"{label}: timeSeries.count must be >= 1")
        elif resolved_raw is not None:
            raw_count = 0
            try:
                with open(resolved_raw, "r", encoding="utf-8") as raw_file:
                    for line_number, line in enumerate(raw_file, 1):
                        if not line.strip():
                            errors.append(f"{label}: raw time-series line {line_number} is blank")
                            continue
                        value = json.loads(line)
                        if not isinstance(value, dict):
                            errors.append(f"{label}: raw time-series line {line_number} is not an object")
                        raw_count += 1
            except Exception as exc:  # noqa: BLE001
                errors.append(f"{label}: raw time-series is not valid JSONL ({exc})")
            if raw_count != ts["count"]:
                errors.append(f"{label}: raw line count {raw_count} != timeSeries.count {ts['count']}")

    duration = root.get("duration")
    cycles = root.get("cycles")
    if not isinstance(duration, dict):
        errors.append(f"{label}: duration object is required")
    elif not isinstance(cycles, dict):
        errors.append(f"{label}: cycles object is required")
    else:
        requested = duration.get("requestedSeconds")
        completed = duration.get("completedSeconds")
        if not isinstance(requested, (int, float)) or requested < 60:
            errors.append(f"{label}: duration.requestedSeconds must be >= 60")
        elif root.get("overall") == "PASSED":
            if duration.get("completed") is not True or not isinstance(completed, (int, float)) \
                    or completed < requested:
                errors.append(f"{label}: passing duration is incomplete")
            minimums = {
                "summaries": int(requested // 60),
                "enhanceUnloadBatches": int(requested // 300),
                "disconnectRecoveries": int(requested // 1800),
            }
            for key, want in minimums.items():
                got = cycles.get(key)
                if not isinstance(got, int) or got < want:
                    errors.append(f"{label}: cycles.{key} must be >= {want} (got {got!r})")
            if isinstance(ts, dict) and isinstance(ts.get("count"), int) \
                    and ts["count"] < minimums["summaries"]:
                errors.append(f"{label}: timeSeries.count is too small for requested duration")


for spec in known:
    fname, kind = spec.split(":")
    path = os.path.join(evidence_dir, fname)
    if not os.path.isfile(path):
        continue
    present += 1
    label = fname
    try:
        with open(path, "r", encoding="utf-8") as fh:
            root = json.load(fh)
    except Exception as e:  # noqa: BLE001
        errors.append(f"{label}: not valid JSON ({e})")
        continue
    if not isinstance(root, dict):
        errors.append(f"{label}: root is not a JSON object")
        continue
    if kind == "benchmark":
        check_benchmark(label, root, errors)
    elif kind == "soak":
        check_soak(label, root, evidence_dir, errors)
    else:
        check_standard(label, root, errors)

if present == 0:
    errors.append("no M2 evidence files found in " + evidence_dir)

if errors:
    print("EVIDENCE VALIDATION FAILED:", file=sys.stderr)
    for e in errors:
        print("  - " + e, file=sys.stderr)
    sys.exit(1)
print(f"verified {present} M2 evidence artifact(s) in {evidence_dir}: all structurally valid, none FAILED")
sys.exit(0)
PY
VERIFY_STATUS=$?
exit "$VERIFY_STATUS"
