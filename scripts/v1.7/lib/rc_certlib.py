#!/usr/bin/env python3
"""
scripts/v1.7/lib/rc_certlib.py

V1.7 M6-A (roadmap §13.2.1): the RC certification aggregator / verifier library.

Pure, dependency-free Python (stdlib only - matches releaselib.py / supplychainlib.py).
Consumes the frozen evidence files produced by the M1/M2/M3/M6-A runners and binds
them to a single immutable 40-hex candidate buildId, then writes (or verifies) the
single target/v1.7/rc-certification-result.json.

Evidence consumed:
  --recovery       recovery-result.json            (run-m1-acceptance.sh)
  --upgrade        upgrade-rehearsal-result.json   (run-upgrade-rehearsal.sh)
  --compatibility  compatibility-result.json       (aggregate-compatibility.sh)
  --state-cycle    state-cycle-result.json          (run-state-cycle.sh)
  --soak           soak-result.json                 (run-soak.sh)
  --defects        v1.7-defect-inventory.json       (maintainer-owned)

Fail-closed (status=FAILED, exit 4) when ANY of:
  - a result is missing / unparseable / malformed / schema-violating;
  - any sub-result's buildId != the frozen candidate (--build-id);
  - any sub-result is mode=dev, workingTreeDirty=true, NOT_RUN/SKIPPED/EXPERIMENTAL,
    or (for soak) a shortened / non-completed / accelerated-clock run;
  - state-cycle cycles.requested != 10000 or cycles.failed != 0 or overall != PASSED;
  - soak duration.requested != PT2H or duration.completed != true or
    duration.completedSeconds < 7200 (real wall-clock >= 2h) or overall != PASSED;
  - the compatibility matrix is not all C01-C10 PASSED with no duplicate/missing rows
    (delegated to the existing verify-compatibility.sh / CompatibilityVerifierMain, and
    re-checked here for overall=PASSED + single buildId + Linux environment);
  - the upgrade rehearsal is not authoritative (Linux/JDK21/PG16/Redis7) + PASSED;
  - the defect inventory is missing, malformed, not "authoritative", or has any open P0/P1
    (P2 must have owner + workaround + targetVersion);
  - any evidence path is absolute, contains '..', or resolves outside --evidence-root.

The library NEVER fabricates evidence, NEVER rewrites historical gate evidence, and NEVER
modifies the acceptance manifest. Promotion is a separate explicit command (promote-rc-gates).
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from datetime import datetime, timezone

HEX40 = re.compile(r"^[0-9a-f]{40}$")
SCHEMA_VERSION = "1.0"
REQUIRED_CYCLES = 10000
MIN_SOAK_SECONDS = 7200  # PT2H
V16_BASELINE_COMMIT = "113823b41981a2d8fb5473a772ae2d2938d9582e"
SUPPORTED_DB = "postgresql16"
APPROVED_STATUSES = {"PASSED", "FAILED", "SKIPPED", "NOT_RUN", "EXPERIMENTAL"}
REJECT_STATUSES = {"NOT_RUN", "SKIPPED", "EXPERIMENTAL"}
SEVERITIES = {"P0", "P1", "P2", "P3"}
BLOCKING_SEVERITIES = {"P0", "P1"}


class CertError(Exception):
    """A fail-closed certification error."""


# --------------------------------------------------------------------------- #
# Small JSON helpers
# --------------------------------------------------------------------------- #
def _load_json(path):
    if not os.path.isfile(path):
        raise CertError(f"evidence file not found: {path}")
    try:
        with open(path, "r", encoding="utf-8") as f:
            return json.load(f)
    except json.JSONDecodeError as e:
        raise CertError(f"unparseable JSON in {path}: {e}")


def _sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def _require(node, key, ctx):
    if not isinstance(node, dict) or key not in node:
        raise CertError(f"{ctx}: missing required field '{key}'")
    return node[key]


def _text(node, key, ctx):
    v = _require(node, key, ctx)
    if not isinstance(v, str) or not v:
        raise CertError(f"{ctx}: field '{key}' must be a non-blank string")
    return v


def _expect_build_id(node, expected, ctx):
    bid = _text(node, "buildId", ctx)
    if not HEX40.match(bid):
        raise CertError(f"{ctx}: buildId must be 40-hex (got: {bid})")
    if expected is not None and bid != expected:
        raise CertError(f"{ctx}: buildId {bid} != frozen candidate {expected}")
    return bid


def _expect_mode_clean(node, ctx):
    mode = node.get("mode")
    if mode != "pr":
        raise CertError(f"{ctx}: mode must be 'pr' (clean) for RC evidence (got: {mode!r}); dev/dirty evidence is rejected")
    if node.get("workingTreeDirty") is not False:
        raise CertError(f"{ctx}: workingTreeDirty must be explicitly false for RC evidence")


def _expect_linux_jdk21(node, ctx):
    env = _require(node, "environment", ctx)
    if not isinstance(env, dict):
        raise CertError(f"{ctx}: environment must be an object")
    os_name = env.get("osName") or env.get("os") or ""
    jdk = env.get("jdkVersion") or env.get("javaMajor") or env.get("java") or ""
    if not str(os_name).lower().startswith("linux"):
        raise CertError(f"{ctx}: environment.osName must be Linux for authoritative RC evidence (got: {os_name!r})")
    jdk_text = str(jdk)
    if not (jdk_text.startswith("21") or re.search(r'version[ =\"]+21(?:\.|\")', jdk_text)):
        raise CertError(f"{ctx}: environment.jdkVersion must be 21.x for authoritative RC evidence (got: {jdk!r})")
    return env


# --------------------------------------------------------------------------- #
# Evidence path safety: reject absolute / '..' / outside-root paths.
# --------------------------------------------------------------------------- #
def _safe_rel(path, evidence_root):
    root_real = os.path.realpath(evidence_root)
    if os.path.isabs(path):
        raise CertError(f"evidence path must be relative: {path}")
    # Reject literal '..' path components (path-traversal signal), even though the
    # under-root realpath check below would also catch escapes.
    if ".." in path.replace("\\", "/").split("/"):
        raise CertError(f"evidence path must not contain '..' (path traversal): {path}")
    full = os.path.realpath(path)
    if full != root_real and not full.startswith(root_real + os.sep):
        raise CertError(f"evidence path resolves outside the approved evidence root: {path}")
    return os.path.relpath(full, root_real)


# --------------------------------------------------------------------------- #
# Per-evidence validators
# --------------------------------------------------------------------------- #
def validate_recovery(node, expected_build_id):
    ctx = "recovery"
    if node.get("schemaVersion") != "1.0":
        raise CertError(f"{ctx}: schemaVersion must be 1.0")
    _expect_build_id(node, expected_build_id, ctx)
    _expect_mode_clean(node, ctx)
    _expect_linux_jdk21(node, ctx)
    if node.get("release") != "V1.7.0" or node.get("milestone") != "M1":
        raise CertError(f"{ctx}: expected release=V1.7.0 and milestone=M1")
    if node.get("gates", {}).get("PR", {}).get("status") != "PASSED":
        raise CertError(f"{ctx}: gates.PR.status must be PASSED")
    expected = {"M1-A", "M1-B", "M1-C", "M1-D", "M1-E", "M1-F", "M1-G", "CLOSED-LOOP"}
    scenarios = node.get("scenarios", [])
    actual = {s.get("id") for s in scenarios}
    if actual != expected or len(scenarios) != len(expected):
        raise CertError(f"{ctx}: scenario catalog must be exactly {sorted(expected)} (got: {sorted(str(x) for x in actual)})")
    failed = [s.get("id") for s in scenarios if s.get("status") != "PASSED"]
    if failed:
        raise CertError(f"{ctx}: scenarios not PASSED: {failed}")
    outcomes = node.get("runnerOutcomes", [])
    expected_steps = {
        "focused-tests", "reactor-test", "package", "compose-verify",
        "web-lint", "web-typecheck", "web-test", "web-build",
    }
    actual_steps = {o.get("step") for o in outcomes}
    if (actual_steps != expected_steps or len(outcomes) != len(expected_steps)
            or any(o.get("exitCode") != 0 for o in outcomes)):
        raise CertError(f"{ctx}: runner outcomes must contain every M1 acceptance step exactly once with exitCode=0")
    checks = node.get("overallChecks", [])
    check_steps = {c.get("step") for c in checks}
    if (check_steps != expected_steps or len(checks) != len(expected_steps)
            or any(c.get("passed") is not True or c.get("status") != "PASSED" for c in checks)):
        raise CertError(f"{ctx}: overallChecks must prove every M1 acceptance step PASSED")
    return {"scenarioCount": len(scenarios), "startedAt": None, "endedAt": node.get("generatedAt")}


def validate_upgrade(node, expected_build_id):
    ctx = "upgrade-rehearsal"
    if node.get("schemaVersion") != "1.0":
        raise CertError(f"{ctx}: schemaVersion must be 1.0")
    _expect_build_id(node, expected_build_id, ctx)
    if node.get("toCommit") != expected_build_id:
        raise CertError(f"{ctx}: toCommit must equal the frozen candidate")
    if node.get("fromCommit") != V16_BASELINE_COMMIT:
        raise CertError(f"{ctx}: fromCommit must equal the frozen V1.6.0 baseline {V16_BASELINE_COMMIT}")
    if node.get("facility") != "kairo-upgrade-rehearsal":
        raise CertError(f"{ctx}: unexpected facility {node.get('facility')!r}")
    _expect_mode_clean(node, ctx)
    status = _text(node, "status", ctx)
    if status != "PASSED":
        raise CertError(f"{ctx}: status must be PASSED (got: {status})")
    if node.get("authoritative") is not True:
        raise CertError(f"{ctx}: authoritative must be true (Linux/JDK21/PG16/Redis7) for RC evidence")
    _expect_linux_jdk21(node, ctx)
    if node.get("database") != SUPPORTED_DB:
        raise CertError(f"{ctx}: database must be {SUPPORTED_DB} (got: {node.get('database')!r})")
    env = _require(node, "environment", ctx)
    pg = _require(env, "postgresql", ctx)
    rd = _require(env, "redis", ctx)
    if not str(pg.get("version", "")).lower().startswith("postgresql 16"):
        raise CertError(f"{ctx}: PostgreSQL 16 required (got: {pg.get('version')!r})")
    if not str(rd.get("version", "")).startswith("7."):
        raise CertError(f"{ctx}: Redis 7 required (got: {rd.get('version')!r})")
    for name, item in (("PostgreSQL", pg), ("Redis", rd)):
        digest = str(item.get("digest", ""))
        if not (re.search(r"@sha256:[0-9a-f]{64}(?:,|$)", digest)
                or re.fullmatch(r"sha256:[0-9a-f]{64}", digest)):
            raise CertError(f"{ctx}: {name} immutable image digest/id is required")
        if str(item.get("image", "")).endswith(":latest"):
            raise CertError(f"{ctx}: {name} latest image tag is forbidden")
    if node.get("rollbackGuard", {}).get("result") != "REJECTED":
        raise CertError(f"{ctx}: rollbackGuard.result must be REJECTED")
    if node.get("rollbackRestore", {}).get("result") != "PASSED":
        raise CertError(f"{ctx}: rollbackRestore.result must be PASSED")
    if node.get("persistedState", {}).get("survived") is not True:
        raise CertError(f"{ctx}: persistedState.survived must be true")
    backup = node.get("backup", {})
    bsha = backup.get("sha256")
    if not isinstance(bsha, str) or not re.fullmatch(r"[0-9a-f]{64}", bsha):
        raise CertError(f"{ctx}: backup.sha256 must be 64-hex (got: {bsha!r})")
    if int(backup.get("size", 0)) <= 0:
        raise CertError(f"{ctx}: backup.size must be > 0")
    scenarios = node.get("scenarios", [])
    expected_scenarios = {
        "start-postgres", "start-redis", "build-v16", "build-candidate", "migrate-v16",
        "seed-state", "backup", "migrate-candidate", "rollback-guard", "rollback-restore",
        "redis-semantics",
    }
    actual_scenarios = {s.get("name") for s in scenarios}
    if actual_scenarios != expected_scenarios or len(scenarios) != len(expected_scenarios):
        raise CertError(f"{ctx}: scenario catalog is incomplete or contains duplicates")
    failed = [s.get("name") for s in scenarios if s.get("status") != "PASSED"]
    if failed:
        raise CertError(f"{ctx}: scenarios not PASSED: {failed}")
    migrations = node.get("migrationVersions", {})
    if str(migrations.get("before")) != "41" or str(migrations.get("expectedBefore")) != "41":
        raise CertError(f"{ctx}: V1.6.0 migration baseline must be V41")
    if not migrations.get("expectedLatest") or migrations.get("after") != migrations.get("expectedLatest"):
        raise CertError(f"{ctx}: candidate must reach migrationVersions.expectedLatest")
    if migrations.get("before") == migrations.get("after"):
        raise CertError(f"{ctx}: upgrade did not advance the schema version")
    guard = node.get("rollbackGuard", {})
    if (guard.get("method") != "SCHEMA_VERSION_PREFLIGHT"
            or str(guard.get("applicationVersion")) != "41"
            or str(guard.get("databaseVersion")) != str(migrations.get("after"))):
        raise CertError(f"{ctx}: rollback guard must reject V1.6/V41 against the upgraded database head")
    if str(node.get("rollbackRestore", {}).get("restoredVersion")) != "41":
        raise CertError(f"{ctx}: rollback restore must return the database to V41")
    if node.get("cleanup", {}).get("result") != "PASSED":
        raise CertError(f"{ctx}: cleanup.result must be PASSED")
    redis = node.get("redis", {})
    if redis.get("result") != "PASSED" or redis.get("namespaceVerified") is not True or redis.get("postgresIsSourceOfTruth") is not True:
        raise CertError(f"{ctx}: Redis namespace semantics and PostgreSQL source-of-truth checks must pass")
    return {
        "fromCommit": node.get("fromCommit"),
        "toCommit": node.get("toCommit"),
        "migrationVersions": migrations,
        "backupSha256": node.get("backup", {}).get("sha256"),
        "startedAt": node.get("startedAt"),
        "endedAt": node.get("endedAt"),
    }


def validate_state_cycle(node, expected_build_id):
    ctx = "state-cycle"
    _require(node, "schemaVersion", ctx)
    _expect_build_id(node, expected_build_id, ctx)
    _expect_mode_clean(node, ctx)
    _expect_linux_jdk21(node, ctx)
    if node.get("overall") != "PASSED":
        raise CertError(f"{ctx}: overall must be PASSED (got: {node.get('overall')!r})")
    cycles = _require(node, "cycles", ctx)
    if int(cycles.get("requested", -1)) != REQUIRED_CYCLES:
        raise CertError(f"{ctx}: cycles.requested must be {REQUIRED_CYCLES} (got: {cycles.get('requested')})")
    if int(cycles.get("failed", -1)) != 0:
        raise CertError(f"{ctx}: cycles.failed must be 0 (got: {cycles.get('failed')})")
    if int(cycles.get("completed", -1)) < REQUIRED_CYCLES:
        raise CertError(f"{ctx}: cycles.completed must be >= {REQUIRED_CYCLES} (got: {cycles.get('completed')})")
    if node.get("firstFailure") is not None:
        raise CertError(f"{ctx}: firstFailure must be null for PASSED evidence")
    if int(cycles.get("completed", -1)) != REQUIRED_CYCLES:
        raise CertError(f"{ctx}: cycles.completed must equal {REQUIRED_CYCLES} (got: {cycles.get('completed')})")
    return {"cycles": cycles, "startedAt": node.get("startedAt"), "endedAt": node.get("endedAt")}


def validate_soak(node, expected_build_id):
    ctx = "soak"
    _require(node, "schemaVersion", ctx)
    _expect_build_id(node, expected_build_id, ctx)
    _expect_mode_clean(node, ctx)
    _expect_linux_jdk21(node, ctx)
    if node.get("overall") != "PASSED":
        raise CertError(f"{ctx}: overall must be PASSED (got: {node.get('overall')!r})")
    dur = _require(node, "duration", ctx)
    if dur.get("requested") != "PT2H":
        raise CertError(f"{ctx}: duration.requested must be PT2H (got: {dur.get('requested')!r})")
    if dur.get("completed") is not True:
        raise CertError(f"{ctx}: duration.completed must be true (full duration reached)")
    if float(dur.get("completedSeconds", -1)) < MIN_SOAK_SECONDS:
        raise CertError(f"{ctx}: duration.completedSeconds must be >= {MIN_SOAK_SECONDS} (real wall-clock PT2H; got: {dur.get('completedSeconds')})")
    try:
        started = datetime.fromisoformat(_text(node, "startedAt", ctx).replace("Z", "+00:00"))
        ended = datetime.fromisoformat(_text(node, "endedAt", ctx).replace("Z", "+00:00"))
    except ValueError as exc:
        raise CertError(f"{ctx}: startedAt/endedAt must be ISO-8601 timestamps ({exc})")
    if (ended - started).total_seconds() < MIN_SOAK_SECONDS:
        raise CertError(f"{ctx}: timestamp interval must prove at least {MIN_SOAK_SECONDS}s of real wall-clock soak")
    # Defensive: reject any explicit accelerated-clock marker. The accelerated clock is
    # test-only (unreachable via run-soak.sh), but if a result ever carries an acceleration
    # flag it cannot be authoritative RC evidence.
    for k in ("acceleratedClock", "clock"):
        if k in node and node[k] in (True, "accelerated", "AcceleratedClock"):
            raise CertError(f"{ctx}: {k}={node[k]!r} indicates accelerated-clock evidence (rejected)")
    if node.get("firstFailure") is not None:
        raise CertError(f"{ctx}: firstFailure must be null for PASSED evidence")
    warmup = _require(node, "measurementWarmup", ctx)
    if warmup.get("strategy") != "bounded-adaptive-metaspace-plateau":
        raise CertError(f"{ctx}: measurementWarmup.strategy must prove bounded adaptive plateau")
    if warmup.get("steadyStateEstablished") is not True:
        raise CertError(f"{ctx}: measurementWarmup.steadyStateEstablished must be true")
    outstanding = warmup.get("eligibleLifecycleLoadersOutstanding")
    allowed_outstanding = warmup.get("allowedOutstandingLifecycleLoaders")
    if not isinstance(outstanding, int) or not isinstance(allowed_outstanding, int) \
            or outstanding < 0 or allowed_outstanding < 0 or outstanding > allowed_outstanding:
        raise CertError(f"{ctx}: lifecycle ClassLoader reclamation was not proven")
    grace = warmup.get("latestCohortGraceLoaders")
    sample_every = warmup.get("sampleEveryBatches")
    if not isinstance(grace, int) or not isinstance(sample_every, int) \
            or grace < 0 or grace > sample_every:
        raise CertError(f"{ctx}: lifecycle ClassLoader grace exceeds one sample cohort")
    observed_growth = warmup.get("observedWindowMetaspaceGrowthPct")
    allowed_growth = warmup.get("maxWindowMetaspaceGrowthPct")
    if not isinstance(observed_growth, (int, float)) or not isinstance(allowed_growth, (int, float)) \
            or observed_growth < 0 or allowed_growth < 0 or observed_growth > allowed_growth:
        raise CertError(f"{ctx}: warm-up Metaspace plateau was not proven")
    return {"duration": dur, "startedAt": node.get("startedAt"), "endedAt": node.get("endedAt")}


def validate_compatibility(node, expected_build_id):
    ctx = "compatibility"
    _require(node, "schemaVersion", ctx)
    _require(node, "catalogVersion", ctx)
    bid = _expect_build_id(node, expected_build_id, ctx)
    if node.get("overall") != "PASSED":
        raise CertError(f"{ctx}: overall must be PASSED (got: {node.get('overall')!r})")
    rows = node.get("rows", [])
    if not isinstance(rows, list) or not rows:
        raise CertError(f"{ctx}: rows must be a non-empty array")
    seen = {}
    for r in rows:
        sid = r.get("scenario")
        st = r.get("status")
        if sid in seen:
            raise CertError(f"{ctx}: duplicate row for scenario {sid}")
        seen[sid] = st
        rbid = r.get("buildId")
        if rbid != bid:
            raise CertError(f"{ctx}: row {sid} buildId {rbid} != aggregate {bid}")
    expected_rows = {f"C{n:02d}" for n in range(1, 11)}
    if set(seen) != expected_rows or len(rows) != len(expected_rows):
        raise CertError(f"{ctx}: scenario catalog must be exactly C01-C10")
    for sid in sorted(expected_rows):
        if sid not in seen:
            raise CertError(f"{ctx}: missing required row {sid}")
        st = seen[sid]
        if st != "PASSED":
            raise CertError(f"{ctx}: final RC row {sid} must be PASSED (got: {st})")
    return {"rows": list(seen.items()), "catalogVersion": node.get("catalogVersion"),
            "startedAt": None, "endedAt": node.get("generatedAt")}


def validate_defects(node, expected_build_id):
    ctx = "defect-inventory"
    _require(node, "schemaVersion", ctx)
    if node.get("status") != "authoritative":
        raise CertError(f"{ctx}: status must be 'authoritative' for RC certification (got: {node.get('status')!r}); a template/dev inventory is rejected")
    inv_bid = node.get("buildId")
    if inv_bid != expected_build_id:
        raise CertError(f"{ctx}: buildId {inv_bid!r} != candidate {expected_build_id}")
    defects = node.get("defects", [])
    if not isinstance(defects, list):
        raise CertError(f"{ctx}: defects must be an array")
    open_blocking = []
    for d in defects:
        did = d.get("id", "?")
        sev = d.get("severity")
        if sev not in SEVERITIES:
            raise CertError(f"{ctx}: defect {did} has invalid severity {sev!r}")
        status = d.get("status", "open")
        if status not in {"open", "resolved", "closed"}:
            raise CertError(f"{ctx}: defect {did} has invalid status {status!r}")
        if status == "open" and sev in BLOCKING_SEVERITIES:
            open_blocking.append(f"{did} ({sev})")
        if status == "open" and sev == "P2":
            for req in ("owner", "workaround", "targetVersion"):
                if not d.get(req):
                    raise CertError(f"{ctx}: open P2 defect {did} must have {req}")
    if open_blocking:
        raise CertError(f"{ctx}: open P0/P1 defects block RC certification: {open_blocking}")
    return {"defectCount": len(defects), "openBlocking": open_blocking}


# --------------------------------------------------------------------------- #
# Aggregate
# --------------------------------------------------------------------------- #
def aggregate(build_id, recovery_p, upgrade_p, compat_p, state_p, soak_p, defects_p, output, evidence_root):
    errors = []
    evidence = []  # exactly six [{name, path, sha256}] entries

    if not HEX40.match(build_id or ""):
        errors.append(f"--build-id must be a 40-hex commit (got: {build_id!r})")

    # Validate every evidence file exists and is bound to the same buildId.
    specs = [
        ("recovery", recovery_p, validate_recovery),
        ("upgrade-rehearsal", upgrade_p, validate_upgrade),
        ("compatibility", compat_p, validate_compatibility),
        ("state-cycle", state_p, validate_state_cycle),
        ("soak", soak_p, validate_soak),
    ]
    summaries = {}
    for name, path, fn in specs:
        if not path:
            errors.append(f"--{name.replace('-', '_')} evidence path is required")
            continue
        try:
            rel = _safe_rel(path, evidence_root)
            node = _load_json(path)
            summaries[name] = fn(node, build_id)
        except CertError as e:
            errors.append(str(e))
            continue
        # Record hash + relative path (path-safety checked).
        try:
            evidence.append({"name": name, "path": rel, "sha256": _sha256(path)})
        except CertError as e:
            errors.append(str(e))

    # Defect inventory.
    if not defects_p:
        errors.append("--defects inventory path is required (absent inventory is NOT zero defects)")
    else:
        try:
            rel = _safe_rel(defects_p, evidence_root)
            dnode = _load_json(defects_p)
            summaries["defects"] = validate_defects(dnode, build_id)
            evidence.append({"name": "defect-inventory", "path": rel,
                             "sha256": _sha256(defects_p)})
        except CertError as e:
            errors.append(str(e))

    status = "PASSED" if not errors else "FAILED"
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    root_real = os.path.realpath(evidence_root)
    output_real = os.path.realpath(output)
    if os.path.dirname(output_real) != root_real:
        raise CertError("--output must be directly inside --evidence-root so verification is portable")
    times = [s.get(k) for s in summaries.values() if isinstance(s, dict) for k in ("startedAt", "endedAt") if s.get(k)]
    result = {
        "schemaVersion": SCHEMA_VERSION,
        "facility": "kairo-rc-certification",
        "status": status,
        "buildId": build_id,
        "startedAt": min(times) if times else now,
        "endedAt": max(times) if times else now,
        "evidence": evidence,
        "summary": summaries,
        "requirements": {
            "recovery": "M1-A..M1-G + CLOSED-LOOP PASSED on the frozen Linux/JDK21 candidate",
            "upgradeRehearsal": "postgresql16 Linux/JDK21/Redis7 backup/restore/rollback PASSED",
            "compatibilityMatrix": "C01-C10 PASSED",
            "stateCycle": f"{REQUIRED_CYCLES} real cycles PASSED",
            "soak": "real wall-clock PT2H PASSED",
            "defects": "zero open P0/P1 (P2 have owner+workaround+targetVersion)",
        },
        "aggregatedAt": now,
        "failureReasons": errors,
        "limitations": [
            "This aggregate binds every sub-result to one 40-hex candidate buildId; mixed-commit evidence is rejected.",
            "RC PASSED requires the candidate be frozen and run on Linux/JDK21/PostgreSQL16/Redis7; dev/dirty/shortened/accelerated evidence is rejected.",
            "This aggregator does NOT promote acceptance-manifest gates; use promote-rc-gates.sh after this aggregate validates.",
        ],
    }
    os.makedirs(os.path.dirname(os.path.abspath(output)), exist_ok=True)
    tmp = output + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2, sort_keys=True)
    os.replace(tmp, output)
    return result, errors


def verify(result_path):
    """Offline verification of a previously written rc-certification-result.json."""
    node = _load_json(result_path)
    ctx = "rc-certification-result"
    if node.get("facility") != "kairo-rc-certification":
        raise CertError(f"{ctx}: unexpected facility {node.get('facility')!r}")
    bid = _text(node, "buildId", ctx)
    if not HEX40.match(bid):
        raise CertError(f"{ctx}: buildId must be 40-hex (got: {bid})")
    status = _text(node, "status", ctx)
    if status != "PASSED":
        raise CertError(f"{ctx}: status must be PASSED (got: {status})")
    evidence = _require(node, "evidence", ctx)
    expected_names = {"recovery", "upgrade-rehearsal", "compatibility", "state-cycle", "soak", "defect-inventory"}
    if not isinstance(evidence, list) or {e.get("name") for e in evidence} != expected_names or len(evidence) != len(expected_names):
        raise CertError(f"{ctx}: evidence must list exactly recovery/upgrade/compatibility/state-cycle/soak/defects")
    base = os.path.dirname(os.path.realpath(result_path))
    validators = {
        "recovery": validate_recovery,
        "upgrade-rehearsal": validate_upgrade,
        "compatibility": validate_compatibility,
        "state-cycle": validate_state_cycle,
        "soak": validate_soak,
        "defect-inventory": validate_defects,
    }
    for e in evidence:
        p = e.get("path", "")
        if os.path.isabs(p) or ".." in p.split(os.sep):
            raise CertError(f"{ctx}: evidence path unsafe: {p}")
        if not e.get("sha256") or not re.match(r"^[0-9a-f]{64}$", e["sha256"]):
            raise CertError(f"{ctx}: evidence {e.get('name')} has invalid sha256")
        full = os.path.realpath(os.path.join(base, p))
        if full != base and not full.startswith(base + os.sep):
            raise CertError(f"{ctx}: evidence path escapes result directory: {p}")
        if not os.path.isfile(full):
            raise CertError(f"{ctx}: evidence file missing: {p}")
        if _sha256(full) != e["sha256"]:
            raise CertError(f"{ctx}: evidence hash mismatch: {p}")
        validators[e["name"]](_load_json(full), bid)
    fr = node.get("failureReasons", [])
    if fr:
        raise CertError(f"{ctx}: failureReasons must be empty (got: {fr})")
    return node


# --------------------------------------------------------------------------- #
# Promotion (promote-rc-gates.sh). Explicit, transactional, fail-closed.
# Runs ONLY after the rc-certification-result validates as PASSED. Resolves the
# buildId to a real git commit, refuses a dirty worktree / fixture buildId, and updates
# ONLY the applicable M6-A RC gates in v1.7-acceptance-manifest.json, keeping every PR /
# RELEASE gate and every historical fact intact. Cannot promote from a dev/dirty/fixture run.
# --------------------------------------------------------------------------- #
# buildId all-zeros is the focused-test fixture sentinel; git rev-parse rejects it, but
# we refuse it explicitly so the failure mode is unmistakable.
FIXTURE_BUILD_ID = "0" * 40

# The RC gates M6-A is authorized to promote (roadmap §13.2.1): recovery,
# upgrade rehearsal, the 10,000-cycle performance/lifecycle gate, final compatibility
# matrix, and the real 2h soak. They are promoted together from one aggregate so the
# manifest cannot mix candidate commits.
PROMOTABLE_RC_GATES = [
    ("V17-RECOVERY", "RC"),
    ("V17-UPGRADE", "RC"),
    ("V17-PERF", "RC"),
    ("V17-COMPAT", "RC"),
    ("V17-SOAK", "RC"),
]


def _git(repo_root, *args):
    return subprocess.run(["git", "-C", repo_root, *args],
                          check=True, capture_output=True, text=True).stdout.strip()


def promote(result_path, manifest_path, repo_root, dry_run=False):
    ctx = "promote-rc-gates"
    # 1. The RC certification result must be PASSED (re-verifies buildId + evidence).
    rc = verify(result_path)
    bid = rc["buildId"]

    # 2. buildId must resolve to a REAL git commit (refuses the fixture sentinel and
    #    any non-commit / unresolved id).
    if bid == FIXTURE_BUILD_ID:
        raise CertError(f"{ctx}: buildId is the fixture sentinel {bid}; cannot promote fixture evidence")
    try:
        resolved = _git(repo_root, "rev-parse", f"{bid}^{{commit}}")
    except subprocess.CalledProcessError:
        raise CertError(f"{ctx}: buildId {bid} does not resolve to a real git commit")
    if resolved != bid:
        raise CertError(f"{ctx}: buildId {bid} resolved to {resolved} (not a commit)")

    # 3. Refuse a dirty worktree: promotion must be from a clean, frozen candidate commit.
    dirty = _git(repo_root, "status", "--porcelain")
    if dirty:
        raise CertError(f"{ctx}: working tree is dirty; promotion requires a clean frozen candidate commit")

    # 4. The manifest must exist and be the v2 schema.
    mctx = "acceptance-manifest"
    manifest = _load_json(manifest_path)
    if manifest.get("schemaVersion") != "2.0":
        raise CertError(f"{mctx}: schemaVersion must be 2.0 (got: {manifest.get('schemaVersion')!r})")
    reqs = manifest.get("requirements", [])
    by_id = {r.get("id"): r for r in reqs}

    # 5. Update ONLY the applicable RC gates. Preserve every PR/RELEASE gate and every
    #    other requirement / historical fact untouched.
    evidence = rc.get("evidence", [])
    # Evidence paths in the aggregate are portable paths relative to target/v1.7,
    # while the acceptance manifest is rooted at the repository. Keep the latter
    # unambiguous so a consumer never resolves (for example) upgrade/foo.json from
    # the repository root by accident.
    evidence_paths = [os.path.join("target", "v1.7", e["path"]) for e in evidence]
    changed = []
    for rid, gate in PROMOTABLE_RC_GATES:
        r = by_id.get(rid)
        if r is None:
            raise CertError(f"{mctx}: requirement {rid} not found; refusing to promote")
        gates = r.setdefault("gates", {})
        old = gates.get(gate, {})
        if old.get("status") == "PASSED" and old.get("buildId") == bid:
            continue  # idempotent: already promoted for this exact candidate
        new = {
            "status": "PASSED",
            "buildId": bid,
            "environment": {"os": "Linux", "java": "21", "database": "postgresql16", "redis": "7"},
            "commands": ["scripts/v1.7/aggregate-rc-certification.sh ..."],
            "reports": ["target/v1.7/rc-certification-result.json"] + evidence_paths,
            "startedAt": rc.get("startedAt"),
            "endedAt": rc.get("endedAt"),
            "limitations": [
                f"M6-A RC certified on candidate commit {bid}; RELEASE remains NOT_RUN pending M6-B",
                "Promotion is transactional and fail-closed; historical PR/RELEASE facts are preserved",
            ],
        }
        gates[gate] = new
        changed.append(f"{rid}.{gate}")

    if not changed:
        print(f"promote: no changes (all applicable RC gates already PASSED for {bid})")
        return {"changed": [], "buildId": bid}

    # The manifest index itself must identify the frozen candidate represented by
    # the promoted RC evidence. This is release metadata, not a claim that the
    # subsequent metadata-only promotion commit was exercised by the RC jobs.
    manifest["buildId"] = bid
    manifest["generatedAt"] = rc.get("endedAt") or rc.get("aggregatedAt")

    # 6. Transactional write: backup -> temp -> atomic rename.
    if dry_run:
        print(f"promote (dry-run): would update {changed} for buildId {bid}")
        return {"changed": changed, "buildId": bid}
    backup_dir = os.path.join(repo_root, "target", "v1.7", "manifest-backups")
    os.makedirs(backup_dir, exist_ok=True)
    backup = os.path.join(backup_dir, f"v1.7-acceptance-manifest.pre-rc-{bid}.json")
    with open(manifest_path, "r", encoding="utf-8") as f:
        original = f.read()
    with open(backup, "w", encoding="utf-8") as f:
        f.write(original)
    tmp = manifest_path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, sort_keys=False)
        f.write("\n")
    os.replace(tmp, manifest_path)
    print(f"promote: updated {changed} for buildId {bid}; backup at {backup}")
    return {"changed": changed, "buildId": bid}


# --------------------------------------------------------------------------- #
# CLI
# --------------------------------------------------------------------------- #
def _cmd_aggregate(args):
    try:
        result, errors = aggregate(
        args.build_id, args.recovery, args.upgrade, args.compatibility, args.state_cycle,
        args.soak, args.defects, args.output, args.evidence_root)
    except CertError as e:
        print(f"RC certification FAILED: {e}", file=sys.stderr)
        return 4
    if errors:
        print(f"RC certification FAILED: {len(errors)} reason(s):", file=sys.stderr)
        for e in errors:
            print(f"  - {e}", file=sys.stderr)
        return 4
    print(f"RC certification PASSED: {args.output}")
    return 0


def _cmd_verify(args):
    try:
        verify(args.result)
    except CertError as e:
        print(f"verify FAILED: {e}", file=sys.stderr)
        return 4
    print(f"verify PASSED: {args.result}")
    return 0


def _cmd_promote(args):
    try:
        promote(args.result, args.manifest, args.repo_root, dry_run=args.dry_run)
    except CertError as e:
        print(f"promote FAILED: {e}", file=sys.stderr)
        return 4
    return 0


def main(argv=None):
    p = argparse.ArgumentParser(prog="rc_certlib.py", description=__doc__.splitlines()[1])
    sub = p.add_subparsers(dest="cmd", required=True)

    a = sub.add_parser("aggregate", help="aggregate + validate sub-results into rc-certification-result.json")
    a.add_argument("--build-id", required=True)
    a.add_argument("--recovery", required=True)
    a.add_argument("--upgrade", required=True)
    a.add_argument("--compatibility", required=True)
    a.add_argument("--state-cycle", required=True)
    a.add_argument("--soak", required=True)
    a.add_argument("--defects", required=True)
    a.add_argument("--output", required=True)
    a.add_argument("--evidence-root", required=True, help="approved root for evidence paths")
    a.set_defaults(func=_cmd_aggregate)

    v = sub.add_parser("verify", help="offline-verify an existing rc-certification-result.json")
    v.add_argument("result", help="path to rc-certification-result.json")
    v.set_defaults(func=_cmd_verify)

    pr = sub.add_parser("promote", help="transactionally promote applicable RC gates after RC certification PASSED")
    pr.add_argument("--result", required=True, help="rc-certification-result.json (must be PASSED)")
    pr.add_argument("--manifest", required=True, help="v1.7-acceptance-manifest.json")
    pr.add_argument("--repo-root", required=True, help="repository root (for git resolution + dirty check)")
    pr.add_argument("--dry-run", action="store_true", help="print changes without writing")
    pr.set_defaults(func=_cmd_promote)

    args = p.parse_args(argv)
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
