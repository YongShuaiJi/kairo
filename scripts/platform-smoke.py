#!/usr/bin/env python3
import json
import os
import time
import urllib.error
import urllib.request


BASE_URL = os.environ.get("RUNTIME_MOCK_API", "http://127.0.0.1:18280/api/v1").rstrip("/")
TOKEN = os.environ.get("RUNTIME_MOCK_TOKEN", "runtime-mock-dev-admin-token-change-me")
ACTOR = os.environ.get("RUNTIME_MOCK_ACTOR", "system")
RUN_ID = str(int(time.time()))


def request(method, path, body=None):
    data = None if body is None else json.dumps(body).encode("utf-8")
    headers = {
        "Content-Type": "application/json",
        "X-Actor": ACTOR,
        "X-Correlation-Id": f"smoke-{RUN_ID}",
        "Idempotency-Key": f"smoke-{RUN_ID}-{method}-{path}".replace("/", "-"),
    }
    if TOKEN:
        headers["Authorization"] = f"Bearer {TOKEN}"

    req = urllib.request.Request(
        f"{BASE_URL}{path}",
        data=data,
        method=method,
        headers=headers,
    )
    try:
        with urllib.request.urlopen(req, timeout=10) as resp:
            payload = resp.read().decode("utf-8")
            return json.loads(payload) if payload else {}
    except urllib.error.HTTPError as exc:
        payload = exc.read().decode("utf-8")
        raise RuntimeError(f"{method} {path} failed: {exc.code} {payload}") from exc


def get(path):
    return request("GET", path)


def post(path, body):
    return request("POST", path, body)


def expect(value, expected, label):
    if value != expected:
        raise AssertionError(f"{label}: expected {expected!r}, got {value!r}")


def command_payload(command):
    payload = command.get("payload") or command.get("payload_json") or {}
    if isinstance(payload, str):
        return json.loads(payload)
    return payload


def main():
    print("[1/7] health")
    expect(get("/control/health")["status"], "UP", "platform health")

    suffix = RUN_ID[-6:]
    print("[2/7] register runtime agent")
    registration = post("/agent-registrations/self", {
        "projectName": "runtime-mock",
        "applicationName": f"smoke-demo-{suffix}",
        "environmentName": "sit",
        "hostname": "smoke-host",
        "processId": suffix,
        "processStartId": f"smoke-host:{suffix}:{RUN_ID}",
        "jvmStartedAtEpochMillis": int(RUN_ID) * 1000,
        "runtime": "java-21",
        "javaVersion": "21.0.11",
        "loadMode": "smoke",
        "agentVersion": "0.1.0",
        "bootstrapVersion": "embedded",
        "listenHost": "127.0.0.1",
        "listenPort": 18080,
        "capabilities": ["DISCOVER_TARGETS", "APPLY_RULE", "RESET_CLASS"],
        "labels": {"smoke": suffix},
        "reason": "smoke register runtime agent",
    })
    application_id = registration["applicationId"]
    environment_id = registration["environmentId"]
    agent_id = registration["agentId"]
    post(f"/agents/{agent_id}/heartbeat", {
        "status": "ACTIVE",
        "metrics": {"smoke": True},
        "reason": "smoke heartbeat",
    })

    print("[3/7] create rule and operation plan")
    rule = post("/rules", {
        "applicationId": application_id,
        "environmentId": environment_id,
        "name": f"smoke rule {suffix}",
        "riskLevel": "LOW",
        "matcher": {},
        "script": {"phase": "BEFORE", "script": "return mock.proceed()"},
        "governance": {},
        "targets": [{
            "protocol": "JAVA_METHOD",
            "className": "com.example.demo.OrderService",
            "methodName": "createOrder",
            "matcher": {"classId": "com.example.demo.OrderService"},
        }],
        "capabilities": ["EARLY_RETURN"],
        "reason": "smoke create rule",
    })
    rule_id = rule["id"]
    operation = post("/operation-plans", {
        "applicationId": application_id,
        "environmentId": environment_id,
        "planType": "RULE_ROLLOUT",
        "resourceType": "rule",
        "resourceId": rule_id,
        "resourceVersion": 1,
        "strategy": {"targetMode": "ALL_ACTIVE_INSTANCES", "automaticUnload": True},
        "reason": "smoke create rollout",
    })
    operation_id = operation["id"]

    print("[4/7] transition operation to running")
    token = post("/fencing-tokens", {
        "resourceType": "operation_plan",
        "resourceId": operation_id,
        "purpose": "smoke start rollout",
        "ttlSeconds": 300,
        "reason": "smoke start rollout",
    })["token"]
    post(f"/operation-plans/{operation_id}/transition", {
        "expectedStatus": "DRAFT",
        "expectedVersion": 1,
        "targetStatus": "RUNNING",
        "fencingToken": token,
        "reason": "smoke start rollout",
    })

    print("[5/7] run scheduler once")
    post("/control/schedulers/run-once", {"reason": "smoke scheduler"})

    print("[6/7] poll and ack agent command")
    command = None
    for _ in range(5):
        candidate = post(f"/agents/{agent_id}/commands/next", {"leaseSeconds": 60})
        if candidate.get("status") == "NO_COMMAND":
            post("/control/schedulers/run-once", {"reason": "smoke scheduler retry"})
            continue
        payload = command_payload(candidate)
        if payload.get("operationPlanId") == operation_id:
            command = candidate
            break
        post(f"/agent-commands/{candidate['id']}/ack", {
            "status": "ACKED",
            "result": {"ignoredBySmoke": True},
            "reason": "smoke ignored stale command",
        })
    if command is None:
        raise AssertionError(f"no command found for operation {operation_id}")
    command_type = command.get("command_type") or command.get("commandType")
    expect(command_type, "APPLY_RULE", f"agent command type from {command}")
    post(f"/agent-commands/{command['id']}/ack", {
        "status": "ACKED",
        "result": {"appliedRuleIds": [f"{rule_id}:1"]},
        "reason": "smoke command ack",
    })

    print("[7/7] verify execution")
    plans = get("/operation-plans")
    plan = next(item for item in plans if item["id"] == operation_id)
    expect(plan["status"], "SUCCEEDED", "operation plan status")
    print("smoke passed")


if __name__ == "__main__":
    main()
