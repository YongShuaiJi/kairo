#!/usr/bin/env python3
"""
V1.7 M1 dependency-free evidence generator (roadmap §5.2 / §8.8).

Reads ACTUAL Surefire XML reports plus the explicit command outcomes recorded by
scripts/v1.7/run-m1-acceptance.sh (target/v1.7/runner-outcomes.jsonl) and writes
target/v1.7/recovery-result.json covering M1-A..M1-G and the end-to-end closed
loop as separate scenarios, each with its concrete report paths and checks.

Only the locally executed PR evidence may be marked PASSED, and only when the
reports confirm it. RC and RELEASE are always NOT_RUN. Nothing is fabricated: a
missing report means NOT_RUN, a failing report means FAILED.

Stdlib only (json, os, sys, glob, subprocess, xml.etree). No third-party deps.
"""

import argparse
import datetime as _dt
import glob
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

RELEASE = "V1.7.0"
MILESTONE = "M1"

# Each scenario maps a focused work package to the concrete test classes that
# prove it. The CLOSED-LOOP scenario is the single end-to-end test required by
# §8.8. Keep this in sync with the FOCUSED_TESTS list in run-m1-acceptance.sh.
SCENARIOS = [
    {
        "id": "M1-A",
        "title": "command lease, epoch and terminal-state safety",
        "tests": [
            "AgentCommandLeaseIntegrationTest",
            "AgentCommandAckFencingIntegrationTest",
            "PlatformCommandPollerEpochTest",
        ],
    },
    {
        "id": "M1-B",
        "title": "command classification + idempotent startup recovery",
        "tests": [
            "AgentCommandClassificationTest",
            "PlatformRestartRecoveryIntegrationTest",
            "TransientCommandRestartIntegrationTest",
            "CompletedCommandNoReplayIntegrationTest",
        ],
    },
    {
        "id": "M1-C",
        "title": "bounded real runtime snapshot",
        "tests": [
            "RuntimeStateSnapshotTest",
            "PlatformRuntimeStateCommandTest",
            "RuntimeStateSnapshotPersistenceIntegrationTest",
        ],
    },
    {
        "id": "M1-D",
        "title": "registration + reconciliation reapply (no trial revival)",
        "tests": [
            "AgentReconnectReconciliationIntegrationTest",
            "JvmRestartReapplyIntegrationTest",
            "ExpiredTrialDoesNotReviveIntegrationTest",
            "DivergedStateFailClosedIntegrationTest",
        ],
    },
    {
        "id": "M1-E",
        "title": "offline unload compensation across disconnects",
        "tests": [
            "OfflineAgentUnloadCompensationIntegrationTest",
            "AgentGoneIsNotUnloadedIntegrationTest",
            "MultiTargetPartialFailureIntegrationTest",
            "UnloadRetryIdempotencyIntegrationTest",
            "RealJvmDisconnectUnloadIntegrationTest",
        ],
    },
    {
        "id": "M1-F",
        "title": "dependency, emergency and rollback recovery",
        "tests": [
            "DependencyHealthRecoveryIntegrationTest",
            "RedisFencingFailureIntegrationTest",
            "EmergencyOpsWithoutPlatformIntegrationTest",
            "PostEmergencyReconciliationIntegrationTest",
            "ApplicationRollbackGuardIntegrationTest",
        ],
    },
    {
        "id": "M1-G",
        "title": "module and application boundary convergence",
        "tests": [
            "ModuleBoundaryConvergenceTest",
            "ProductEntrypointInventoryTest",
            "ObjectRuntimeCompatibilityTest",
            "AttachExecutorCompatibilityTest",
        ],
    },
    {
        "id": "CLOSED-LOOP",
        "title": "M1 end-to-end closed loop (desired->enhance->restart->reapply->"
                 "disconnect->offline unload->reconnect->precise unload->restored)",
        "tests": [
            "M1ClosedLoopRecoveryIntegrationTest",
        ],
    },
]

# Overall §8.8 acceptance checks beyond the per-scenario tests, keyed by the
# step names recorded by the runner. Their pass/fail comes ONLY from the recorded
# command outcomes (never assumed).
OVERALL_STEPS = [
    "focused-tests",
    "reactor-test",
    "package",
    "compose-verify",
    "web-lint",
    "web-typecheck",
    "web-test",
    "web-build",
]


def run(cmd, cwd, use_stderr=False):
    """Run a command, returning trimmed output or 'unknown' on failure.

    java -version (and similar JDK tools) writes its version to stderr, so
    use_stderr=True merges stderr into the captured text for those.
    """
    try:
        out = subprocess.run(
            cmd, cwd=cwd, capture_output=True, text=True, timeout=30,
        )
        if out.returncode != 0:
            return "unknown"
        text = out.stderr if use_stderr else out.stdout
        return text.strip() or "unknown"
    except Exception:
        return "unknown"


def detect_environment(root):
    java_ver = run(["java", "-version"], root, use_stderr=True)
    java_line = java_ver.splitlines()[0] if java_ver != "unknown" else "unknown"
    return {
        "os": run(["uname", "-srm"], root),
        "java": java_line,
        "node": run(["node", "--version"], root),
        "npm": run(["npm", "--version"], root),
    }


def detect_build_id(root):
    return run(["git", "rev-parse", "HEAD"], root)


def now_iso():
    # Use timezone-aware UTC; equivalent to date -u on the runner.
    return _dt.datetime.now(_dt.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def collect_surefire(root):
    """Map test-class-name -> aggregated {tests,failures,errors,skipped,reports[]}."""
    by_class = {}
    pattern = os.path.join(root, "**", "target", "surefire-reports", "TEST-*.xml")
    for path in glob.glob(pattern, recursive=True):
        base = os.path.basename(path)
        if not base.startswith("TEST-") or not base.endswith(".xml"):
            continue
        class_name = base[len("TEST-"):-len(".xml")]
        # Surefire names reports with the fully-qualified class name; scenarios
        # reference the simple name, so key by the trailing segment.
        simple = class_name.rsplit(".", 1)[-1]
        try:
            tree = ET.parse(path)
        except ET.ParseError:
            continue
        suite = tree.getroot()
        agg = by_class.setdefault(simple, {
            "tests": 0, "failures": 0, "errors": 0, "skipped": 0, "reports": [],
        })
        agg["tests"] += int(suite.get("tests", "0"))
        agg["failures"] += int(suite.get("failures", "0"))
        agg["errors"] += int(suite.get("errors", "0"))
        agg["skipped"] += int(suite.get("skipped", "0"))
        agg["reports"].append(os.path.relpath(path, root))
    return by_class


def class_result(name, surefire):
    entry = surefire.get(name)
    if not entry:
        return {"status": "NOT_RUN", "tests": 0, "failures": 0, "errors": 0,
                "skipped": 0, "reports": []}
    status = "PASSED" if (entry["failures"] == 0 and entry["errors"] == 0) else "FAILED"
    return {
        "status": status,
        "tests": entry["tests"],
        "failures": entry["failures"],
        "errors": entry["errors"],
        "skipped": entry["skipped"],
        "reports": entry["reports"],
    }


def scenario_status(class_results):
    statuses = [c["status"] for c in class_results]
    if not statuses:
        return "NOT_RUN"
    if any(s == "FAILED" for s in statuses):
        return "FAILED"
    if any(s == "NOT_RUN" for s in statuses):
        return "NOT_RUN"
    return "PASSED"


def read_outcomes(root):
    path = os.path.join(root, "target", "v1.7", "runner-outcomes.jsonl")
    outcomes = []
    if not os.path.exists(path):
        return outcomes
    with open(path, "r", encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if not line:
                continue
            try:
                outcomes.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    return outcomes


def build_overall_checks(outcomes):
    by_step = {o.get("step"): o for o in outcomes}
    checks = []
    for step in OVERALL_STEPS:
        o = by_step.get(step)
        if o is None:
            checks.append({
                "step": step, "passed": False, "status": "NOT_RUN",
                "detail": "not executed by runner", "command": None,
            })
        else:
            code = o.get("exitCode")
            passed = isinstance(code, int) and code == 0
            checks.append({
                "step": step, "passed": passed,
                "status": "PASSED" if passed else "FAILED",
                "detail": "exitCode=%s" % code,
                "command": o.get("command"),
                "startedAt": o.get("startedAt"),
                "endedAt": o.get("endedAt"),
            })
    return checks


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--root", default=os.getcwd())
    ap.add_argument("--output", default=None)
    args = ap.parse_args()
    root = os.path.abspath(args.root)
    if args.output is None:
        args.output = os.path.join(root, "target", "v1.7", "recovery-result.json")

    surefire = collect_surefire(root)
    outcomes = read_outcomes(root)

    scenarios = []
    for sc in SCENARIOS:
        class_results = [class_result(t, surefire) for t in sc["tests"]]
        status = scenario_status(class_results)
        present = sum(1 for c in class_results if c["status"] != "NOT_RUN")
        checks = [{
            "name": "all-test-classes-executed",
            "passed": present == len(class_results),
            "detail": "%d/%d test classes have Surefire reports" % (present, len(class_results)),
        }, {
            "name": "no-failures-or-errors",
            "passed": all(c["failures"] == 0 and c["errors"] == 0 for c in class_results),
            "detail": "failures=%d errors=%d across executed classes" % (
                sum(c["failures"] for c in class_results),
                sum(c["errors"] for c in class_results),
            ),
        }]
        scenarios.append({
            "id": sc["id"],
            "title": sc["title"],
            "gate": "PR",
            "status": status,
            "tests": [{"className": t, **cr} for t, cr in zip(sc["tests"], class_results)],
            "checks": checks,
        })

    overall_checks = build_overall_checks(outcomes)
    all_scenarios_passed = all(s["status"] == "PASSED" for s in scenarios)
    any_scenario_failed = any(s["status"] == "FAILED" for s in scenarios)
    all_overall_passed = all(c["passed"] for c in overall_checks)
    any_overall_failed = any(c["status"] == "FAILED" for c in overall_checks)

    if all_scenarios_passed and all_overall_passed:
        pr_status = "PASSED"
    elif any_scenario_failed or any_overall_failed:
        pr_status = "FAILED"
    else:
        pr_status = "NOT_RUN"

    result = {
        "schemaVersion": "1.0",
        "release": RELEASE,
        "milestone": MILESTONE,
        "buildId": detect_build_id(root),
        "generatedAt": now_iso(),
        "environment": detect_environment(root),
        "runnerOutcomes": outcomes,
        "scenarios": scenarios,
        "overallChecks": overall_checks,
        "gates": {
            "PR": {
                "status": pr_status,
                "limitations": [
                    "PR evidence is derived solely from local Surefire reports and the "
                    "recorded runner command outcomes; it is not a release certification.",
                    "RC and RELEASE recovery evidence is produced by separate RC/RELEASE "
                    "acceptance runs and is intentionally NOT_RUN here.",
                ],
            },
            "RC": {"status": "NOT_RUN"},
            "RELEASE": {"status": "NOT_RUN"},
        },
    }

    os.makedirs(os.path.dirname(args.output), exist_ok=True)
    with open(args.output, "w", encoding="utf-8") as fh:
        json.dump(result, fh, indent=2)
        fh.write("\n")
    print(">> wrote %s" % args.output)
    # Exit non-zero if any evidence is FAILED, so a CI caller can detect it.
    if pr_status == "FAILED":
        sys.exit(1)


if __name__ == "__main__":
    main()
