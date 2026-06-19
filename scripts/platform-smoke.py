#!/usr/bin/env python3
"""Dependency-free end-to-end smoke test for the Runtime Mock product stack."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from typing import Any


BASE_URL = os.environ.get("RUNTIME_MOCK_PLATFORM_URL", "http://127.0.0.1:18280")
ACCESS_TOKEN = os.environ.get(
    "RUNTIME_MOCK_ACCESS_TOKEN", "runtime-mock-dev-admin-token-change-me"
)
SMOKE_ID = os.environ.get("RUNTIME_MOCK_SMOKE_ID", str(int(time.time())))
CORRELATION_ID = f"full-product-smoke-{SMOKE_ID}"


def fail(message: str) -> None:
    raise AssertionError(message)


def check(condition: bool, message: str) -> None:
    if not condition:
        fail(message)


def api(
    method: str,
    path: str,
    body: dict[str, Any] | None = None,
    token: str = ACCESS_TOKEN,
) -> Any:
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(
        f"{BASE_URL}{path}",
        data=data,
        method=method,
        headers={
            "Authorization": f"Bearer {token}",
            "X-Correlation-Id": CORRELATION_ID,
            **({"Content-Type": "application/json"} if data is not None else {}),
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            raw = response.read()
    except urllib.error.HTTPError as error:
        detail = error.read().decode(errors="replace")
        raise RuntimeError(
            f"{method} {path} failed with HTTP {error.code}: {detail}"
        ) from error
    except urllib.error.URLError as error:
        raise RuntimeError(f"{method} {path} failed: {error}") from error
    if not raw:
        return None
    return json.loads(raw)


def get(path: str, token: str = ACCESS_TOKEN) -> Any:
    return api("GET", path, token=token)


def post(
    path: str, body: dict[str, Any], token: str = ACCESS_TOKEN
) -> Any:
    return api("POST", path, body, token)


def issue_fencing_token(resource_type: str, resource_id: str) -> str:
    response = post(
        "/api/v1/fencing-tokens",
        {
            "resourceType": resource_type,
            "resourceId": resource_id,
            "purpose": "full-product-smoke",
            "ttlSeconds": 300,
            "reason": "full product smoke",
        },
    )
    return response["token"]


def transition(
    endpoint: str,
    resource_type: str,
    resource_id: str,
    expected_status: str,
    expected_version: int,
    target_status: str,
) -> Any:
    return post(
        f"/api/v1/{endpoint}/{resource_id}/transition",
        {
            "expectedStatus": expected_status,
            "expectedVersion": expected_version,
            "targetStatus": target_status,
            "reason": f"full product smoke: {target_status}",
            "fencingToken": issue_fencing_token(resource_type, resource_id),
        },
    )


def approve_subject(
    subject_type: str,
    subject_id: str,
    subject_version: int,
    approval_id: str,
    reviewer_token: str,
) -> None:
    post(
        "/api/v1/approvals",
        {
            "id": approval_id,
            "subjectType": subject_type,
            "subjectId": subject_id,
            "subjectVersion": subject_version,
            "approvers": ["reviewer"],
            "reason": "full product smoke approval",
        },
    )
    post(
        f"/api/v1/approvals/{approval_id}/decisions",
        {"decision": "APPROVED", "reason": "approved by full product smoke"},
        reviewer_token,
    )


def resource_item(resource: str, resource_id: str) -> dict[str, Any]:
    response = get(f"/api/v1/query/{resource}?page=0&size=100&q={resource_id}")
    for item in response["items"]:
        if item.get("id") == resource_id:
            return item
    fail(f"{resource}/{resource_id} was not found")


def wait_for_status(
    resource: str, resource_id: str, expected: str, attempts: int = 60
) -> dict[str, Any]:
    current: dict[str, Any] | None = None
    for _ in range(attempts):
        try:
            current = resource_item(resource, resource_id)
        except AssertionError:
            current = None
        if current and current.get("status") == expected:
            return current
        time.sleep(1)
    fail(
        f"Timed out waiting for {resource}/{resource_id} status={expected}; "
        f"current={current}"
    )


def wait_for_outbox() -> None:
    for _ in range(60):
        response = get("/api/v1/query/outbox?page=0&size=200")
        if all(item.get("status") == "PUBLISHED" for item in response["items"]):
            return
        time.sleep(1)
    fail("Outbox events did not all reach PUBLISHED")


def run(command: list[str]) -> str:
    completed = subprocess.run(
        command, check=True, capture_output=True, text=True
    )
    return completed.stdout


def main() -> None:
    instance_id = f"smoke-instance-{SMOKE_ID}"
    agent_id = f"smoke-agent-{SMOKE_ID}"
    rule_id = f"smoke-rule-{SMOKE_ID}"
    operation_id = f"smoke-operation-{SMOKE_ID}"
    batch_id = f"smoke-rollout-batch-{SMOKE_ID}"
    recording_id = f"smoke-recording-{SMOKE_ID}"
    recording_dataset_id = f"smoke-recording-dataset-{SMOKE_ID}"
    datasource_id = f"smoke-datasource-{SMOKE_ID}"
    template_id = f"smoke-template-{SMOKE_ID}"
    extraction_id = f"smoke-extraction-{SMOKE_ID}"
    extracted_dataset_id = f"smoke-extracted-dataset-{SMOKE_ID}"
    replay_plan_id = f"smoke-replay-plan-{SMOKE_ID}"
    replay_execution_id = f"smoke-replay-execution-{SMOKE_ID}"

    print("[1/9] Health, identity and script workbench", flush=True)
    check(get("/api/v1/control/health")["status"] == "UP", "Platform is not UP")
    identity = get("/api/v1/auth/me")
    check(
        identity["subject"] == "system" and "ADMIN" in identity["capabilities"],
        "Bootstrap identity is not an administrator",
    )
    validation = post(
        "/api/v1/scripts/validate",
        {"script": 'return mock.returnValue([status: 200, source: "smoke"])'},
    )
    check(validation["valid"] is True, "Groovy validation failed")
    execution = post(
        "/api/v1/scripts/test",
        {
            "script": 'log.info("smoke"); return mock.returnValue("ok")',
            "input": {
                "phase": "RETURN",
                "args": [],
                "result": "original",
            },
        },
    )
    check(
        execution["status"] == "SUCCESS"
        and execution["decision"] == "RETURN"
        and execution["output"] == "ok",
        "Groovy test execution failed",
    )

    print("[2/9] Instance, agent and scoped agent authentication", flush=True)
    post(
        "/api/v1/instances",
        {
            "id": instance_id,
            "applicationId": "app-default",
            "environmentId": "env-dev",
            "hostname": "smoke-host",
            "processId": "1",
            "runtime": "java-21",
            "labels": {"tier": "smoke", "smokeId": SMOKE_ID},
            "reason": "full product smoke",
        },
    )
    agent_response = post(
        "/api/v1/agents",
        {
            "id": agent_id,
            "instanceId": instance_id,
            "status": "ACTIVE",
            "agentVersion": "0.1.0",
            "bootstrapVersion": "0.1.0",
            "listenHost": "127.0.0.1",
            "listenPort": 18080,
            "capabilities": ["JAVA_METHOD"],
            "reason": "full product smoke",
        },
    )
    check("token_hash" not in agent_response, "Agent token hash leaked")
    reviewer_token = post(
        "/api/v1/auth/tokens",
        {
            "subjectType": "USER",
            "subjectId": "reviewer",
            "displayName": "Smoke reviewer",
            "ttlSeconds": 3600,
        },
    )["token"]
    agent_token = post(
        "/api/v1/auth/tokens",
        {
            "subjectType": "AGENT",
            "subjectId": agent_id,
            "displayName": "Smoke agent",
            "ttlSeconds": 3600,
        },
    )["token"]
    check(
        get("/api/v1/auth/me", agent_token)["subjectType"] == "AGENT",
        "Agent identity was not resolved",
    )
    post(
        f"/api/v1/agents/{agent_id}/heartbeat",
        {"status": "ACTIVE", "metrics": {"smoke": True}},
        agent_token,
    )
    try:
        get("/api/v1/instances", agent_token)
    except RuntimeError as error:
        check("HTTP 403" in str(error), "Agent management access failed unexpectedly")
    else:
        fail("Agent token unexpectedly accessed management API")

    print("[3/9] Governed rule rollout through approval and agent command", flush=True)
    post(
        "/api/v1/rules",
        {
            "id": rule_id,
            "applicationId": "app-default",
            "environmentId": "env-dev",
            "name": "Full product smoke rule",
            "status": "ACTIVE",
            "versionStatus": "ACTIVE",
            "riskLevel": "LOW",
            "script": {
                "phase": "BEFORE",
                "script": "return mock.proceed()",
            },
            "targets": [
                {
                    "protocol": "JAVA_METHOD",
                    "className": "example.Smoke",
                    "methodName": "ping",
                    "matcher": {"descriptor": "()Ljava/lang/String;"},
                }
            ],
            "capabilities": ["JAVA_METHOD"],
            "reason": "full product smoke",
        },
    )
    post(
        "/api/v1/operation-plans",
        {
            "id": operation_id,
            "applicationId": "app-default",
            "environmentId": "env-dev",
            "planType": "RULE_ROLLOUT",
            "resourceType": "rule",
            "resourceId": rule_id,
            "resourceVersion": 1,
            "strategy": {"mode": "smoke"},
            "rollout": {
                "mode": "SEQUENTIAL",
                "batchPolicy": {"batchSize": 1},
                "rollbackPolicy": {"automatic": False},
            },
            "reason": "full product smoke",
        },
    )
    transition(
        "operation-plans",
        "operation_plan",
        operation_id,
        "DRAFT",
        1,
        "WAITING_APPROVAL",
    )
    approve_subject(
        "OPERATION_PLAN",
        operation_id,
        2,
        f"smoke-approval-operation-{SMOKE_ID}",
        reviewer_token,
    )
    transition(
        "operation-plans",
        "operation_plan",
        operation_id,
        "WAITING_APPROVAL",
        2,
        "APPROVED",
    )
    post(
        f"/api/v1/operation-plans/{operation_id}/batches",
        {
            "id": batch_id,
            "batchOrder": 1,
            "targetSelector": {"labels": {"smokeId": SMOKE_ID}},
            "reason": "full product smoke",
        },
    )
    transition(
        "operation-plans",
        "operation_plan",
        operation_id,
        "APPROVED",
        3,
        "RUNNING",
    )
    command: dict[str, Any] | None = None
    for _ in range(60):
        candidate = post(
            f"/api/v1/agents/{agent_id}/commands/next",
            {"leaseSeconds": 30},
            agent_token,
        )
        if candidate and candidate.get("id"):
            command = candidate
            break
        time.sleep(1)
    check(command is not None, "Rollout command was not dispatched")
    check(
        command["command_type"] == "APPLY_RULE"
        and command["payload"]["rule"]["enabled"] is True,
        "Rollout command payload is invalid",
    )
    post(
        f"/api/v1/agent-commands/{command['id']}/ack",
        {
            "status": "ACKED",
            "result": {"applied": True},
            "reason": "full product smoke ack",
        },
        agent_token,
    )
    wait_for_status("operation-plans", operation_id, "SUCCEEDED")

    print("[4/9] Governed recording lifecycle and recording dataset", flush=True)
    post(
        "/api/v1/recording-sessions",
        {
            "id": recording_id,
            "applicationId": "app-default",
            "environmentId": "env-dev",
            "maxEvents": 100,
            "ttlSeconds": 300,
            "target": {
                "protocol": "JAVA_METHOD",
                "className": "example.Smoke",
                "methodName": "ping",
                "methodDescriptor": "()Ljava/lang/String;",
                "agentIds": [agent_id],
            },
            "quota": {"maxBytes": 1048576},
            "reason": "full product smoke",
        },
    )
    transition(
        "recording-sessions",
        "recording_session",
        recording_id,
        "DRAFT",
        1,
        "WAITING_APPROVAL",
    )
    approve_subject(
        "RECORDING_SESSION",
        recording_id,
        2,
        f"smoke-approval-recording-{SMOKE_ID}",
        reviewer_token,
    )
    transition(
        "recording-sessions",
        "recording_session",
        recording_id,
        "WAITING_APPROVAL",
        2,
        "APPROVED",
    )
    transition(
        "recording-sessions",
        "recording_session",
        recording_id,
        "APPROVED",
        3,
        "RECORDING",
    )
    recording_batch = post(
        f"/api/v1/recording-sessions/{recording_id}/events",
        {
            "batchId": f"smoke-recording-batch-{SMOKE_ID}",
            "events": [
                {
                    "id": f"smoke-event-1-{SMOKE_ID}",
                    "traceId": "smoke-trace",
                    "spanId": "span-1",
                    "protocol": "JAVA_METHOD",
                    "eventTime": "2026-06-19T00:00:00Z",
                    "metadata": {
                        "className": "example.Smoke",
                        "methodName": "ping",
                    },
                    "arguments": {
                        "message": "hello",
                        "password": "must-be-redacted",
                    },
                },
                {
                    "id": f"smoke-event-2-{SMOKE_ID}",
                    "traceId": "smoke-trace",
                    "spanId": "span-2",
                    "protocol": "JAVA_METHOD",
                    "eventTime": "2026-06-19T00:00:01Z",
                    "metadata": {
                        "className": "example.Smoke",
                        "methodName": "ping",
                        "authorization": "must-be-redacted",
                    },
                    "result": {"value": "pong"},
                },
            ],
        },
        agent_token,
    )
    check(
        recording_batch["status"] == "SEALED"
        and recording_batch["event_count"] == 2,
        "Recording batch was not sealed",
    )
    transition(
        "recording-sessions",
        "recording_session",
        recording_id,
        "RECORDING",
        4,
        "COMPLETED",
    )
    recording_dataset = post(
        "/api/v1/datasets",
        {
            "datasetId": recording_dataset_id,
            "sourceSessionId": recording_id,
            "name": "Smoke recording dataset",
            "applicationId": "app-default",
            "environmentId": "env-dev",
            "reason": "full product smoke",
        },
    )
    object_references = json.loads(recording_dataset["object_references_json"])
    check(
        recording_dataset["source_type"] == "RECORDING_SESSION"
        and len(object_references) == 1
        and "s3://runtime-mock/recording/" in recording_dataset["object_references_json"],
        "Recording dataset lineage is invalid",
    )
    recording_events = get(
        f"/api/v1/query/recording-events?page=0&size=100&q={recording_id}"
    )
    check(
        recording_events["total"] == 2
        and all(
            item["recording_session_id"] == recording_id
            for item in recording_events["items"]
        ),
        "Recording event index is invalid",
    )

    print("[5/9] Extraction worker, immutable dataset lineage and MinIO artifact", flush=True)
    datasource = post(
        "/api/v1/datasources",
        {
            "id": datasource_id,
            "applicationId": "app-default",
            "environmentId": "env-dev",
            "datasourceType": "TEST_FIXTURE",
            "name": "Smoke fixture",
            "config": {
                "sampleRows": [
                    {"id": 1, "name": "alpha"},
                    {"id": 2, "name": "beta"},
                ]
            },
            "reason": "full product smoke",
        },
    )
    check("config_json" not in datasource, "Datasource credentials leaked")
    post(
        "/api/v1/extraction-templates",
        {
            "id": template_id,
            "datasourceId": datasource_id,
            "name": "Smoke extraction template",
            "status": "ACTIVE",
            "versionStatus": "ACTIVE",
            "rootTable": "fixture.orders",
            "template": {"columns": ["id", "name"]},
            "quota": {"maxRows": 100, "timeoutSeconds": 5},
            "reason": "full product smoke",
        },
    )
    post(
        "/api/v1/extraction-tasks",
        {
            "id": extraction_id,
            "templateId": template_id,
            "templateVersion": 1,
            "datasetId": extracted_dataset_id,
            "parameters": {},
            "quota": {
                "maxRows": 100,
                "maxBytes": 1048576,
                "timeoutSeconds": 5,
            },
            "reason": "full product smoke",
        },
    )
    transition(
        "extraction-tasks",
        "extraction_task",
        extraction_id,
        "DRAFT",
        1,
        "QUEUED",
    )
    wait_for_status("extraction-tasks", extraction_id, "SUCCEEDED")
    extracted_dataset = resource_item("datasets", f"{extracted_dataset_id}:1")
    check(
        extracted_dataset["source_type"] == "EXTRACTION_TASK"
        and extracted_dataset["source_ref"] == extraction_id,
        "Extraction dataset lineage is invalid",
    )
    extraction_results = get(
        f"/api/v1/query/extraction-results?page=0&size=100&q={extraction_id}"
    )
    check(
        any(
            item["dataset_version_id"] == f"{extracted_dataset_id}:1"
            and item["row_count"] == 2
            for item in extraction_results["items"]
        ),
        "Extraction result was not persisted",
    )

    print("[6/9] Governed replay plan and replay worker", flush=True)
    post(
        "/api/v1/replay-plans",
        {
            "id": replay_plan_id,
            "datasetId": extracted_dataset_id,
            "datasetVersion": 1,
            "targetEnvironment": "env-dev",
            "targetApplication": "app-default",
            "versionStatus": "ACTIVE",
            "sideEffectPolicyHash": "side-effect-smoke",
            "comparisonPolicyHash": "comparison-smoke",
            "sideEffectPolicy": {"mode": "BLOCK"},
            "comparisonPolicy": {"mode": "STRICT"},
            "executionPolicy": {"qps": 1, "concurrency": 1},
            "targets": [{"targetType": "SYNTHETIC", "name": "smoke"}],
            "reason": "full product smoke",
        },
    )
    transition(
        "replay-plans",
        "replay_plan",
        replay_plan_id,
        "DRAFT",
        1,
        "WAITING_APPROVAL",
    )
    approve_subject(
        "REPLAY_PLAN",
        replay_plan_id,
        2,
        f"smoke-approval-replay-{SMOKE_ID}",
        reviewer_token,
    )
    transition(
        "replay-plans",
        "replay_plan",
        replay_plan_id,
        "WAITING_APPROVAL",
        2,
        "APPROVED",
    )
    transition(
        "replay-plans",
        "replay_plan",
        replay_plan_id,
        "APPROVED",
        3,
        "RUNNING",
    )
    post(
        "/api/v1/replay-executions",
        {
            "id": replay_execution_id,
            "replayPlanId": replay_plan_id,
            "executorConfig": {"qps": 1, "concurrency": 1},
            "reason": "full product smoke",
        },
    )
    wait_for_status("replay-executions", replay_execution_id, "SUCCEEDED")
    transition(
        "replay-plans",
        "replay_plan",
        replay_plan_id,
        "RUNNING",
        4,
        "SUCCEEDED",
    )
    replay_batches = get(
        f"/api/v1/query/replay-batches?page=0&size=100&q={replay_execution_id}"
    )
    check(
        any(item["status"] == "SUCCEEDED" for item in replay_batches["items"]),
        "Replay batch did not succeed",
    )
    comparison_results = get(
        "/api/v1/query/comparison-results?page=0&size=100"
    )
    check(
        any(
            item["status"] == "MATCHED"
            for item in comparison_results["items"]
        ),
        "Replay comparison did not match",
    )

    print("[7/9] Web-facing query, details, dashboard and redaction", flush=True)
    check(
        get(f"/api/v1/details/rules/{rule_id}")["id"] == rule_id,
        "Generic detail API failed",
    )
    check(
        len(get(f"/api/v1/rules/{rule_id}/detail")["versions"]) >= 1,
        "Rule detail history is missing",
    )
    overview = get("/api/v1/dashboard/overview")
    check(
        overview["counts"]["agentsTotal"] >= 1
        and overview["counts"]["rulesTotal"] >= 1
        and overview["counts"]["workerArtifacts"] >= 2,
        "Dashboard aggregation is incomplete",
    )
    agents = get(f"/api/v1/query/agents?page=0&size=100&q={agent_id}")
    datasources = get(
        f"/api/v1/query/datasources?page=0&size=100&q={datasource_id}"
    )
    tokens = get("/api/v1/query/tokens?page=0&size=100")
    fencing_tokens = get("/api/v1/fencing-tokens")
    check("token_hash" not in json.dumps(agents), "Agent token hash leaked")
    check(
        "config_json" not in json.dumps(datasources),
        "Datasource configuration leaked",
    )
    check("token_hash" not in json.dumps(tokens), "Auth token hash leaked")
    check(
        '"token"' not in json.dumps(fencing_tokens),
        "Fencing token secret leaked",
    )

    print("[8/9] Kafka outbox and infrastructure verification", flush=True)
    wait_for_outbox()
    if subprocess.call(
        ["sh", "-c", "command -v docker >/dev/null 2>&1"]
    ) == 0:
        check(
            "PONG" in run(
                ["docker", "exec", "runtime-mock-redis", "redis-cli", "ping"]
            ),
            "Redis is unavailable",
        )
        check(
            "runtime-mock.platform." in run(
                [
                    "docker",
                    "exec",
                    "runtime-mock-kafka",
                    "rpk",
                    "topic",
                    "list",
                ]
            ),
            "Kafka platform topics are missing",
        )
        minio_user = run(
            [
                "docker",
                "exec",
                "runtime-mock-minio",
                "printenv",
                "MINIO_ROOT_USER",
            ]
        ).strip()
        minio_password = run(
            [
                "docker",
                "exec",
                "runtime-mock-minio",
                "printenv",
                "MINIO_ROOT_PASSWORD",
            ]
        ).strip()
        run(
            [
                "docker",
                "exec",
                "runtime-mock-minio",
                "mc",
                "alias",
                "set",
                "local",
                "http://localhost:9000",
                minio_user,
                minio_password,
            ]
        )
        minio_objects = run(
            [
                "docker",
                "exec",
                "runtime-mock-minio",
                "mc",
                "find",
                "local/runtime-mock",
            ]
        )
        check(minio_objects.strip() != "", "MinIO contains no product artifacts")
        published = run(
            [
                "docker",
                "exec",
                "runtime-mock-postgres",
                "psql",
                "-U",
                "runtime_mock",
                "-d",
                "runtime_mock",
                "-Atc",
                "select count(*) from outbox_event where status = 'PUBLISHED'",
            ]
        ).strip()
        check(int(published) > 0, "No outbox event reached PUBLISHED")

        recording_uri = object_references[0]["objectUri"]
        object_path = recording_uri.removeprefix("s3://runtime-mock/")
        encrypted_payload = subprocess.run(
            [
                "docker",
                "exec",
                "runtime-mock-minio",
                "mc",
                "cat",
                f"local/runtime-mock/{object_path}",
            ],
            check=True,
            capture_output=True,
        ).stdout
        check(
            b"must-be-redacted" not in encrypted_payload
            and f"smoke-event-1-{SMOKE_ID}".encode() not in encrypted_payload,
            "Recording object was not encrypted at rest",
        )

    print("[9/9] Full product smoke passed", flush=True)
    print(
        json.dumps(
            {
                "status": "PASSED",
                "smokeId": SMOKE_ID,
                "instanceId": instance_id,
                "agentId": agent_id,
                "ruleId": rule_id,
                "operationPlanId": operation_id,
                "recordingSessionId": recording_id,
                "extractedDatasetId": extracted_dataset_id,
                "replayExecutionId": replay_execution_id,
            },
            ensure_ascii=False,
        )
    )


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"platform-smoke failed: {error}", file=sys.stderr)
        raise
