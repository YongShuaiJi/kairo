#!/usr/bin/env bash
set -euo pipefail

BASE_URL="${RUNTIME_MOCK_PLATFORM_URL:-http://127.0.0.1:18280}"
ACTOR="${RUNTIME_MOCK_ACTOR:-system}"
SMOKE_ID="${RUNTIME_MOCK_SMOKE_ID:-smoke-$(date +%s)}"
CORRELATION_ID="smoke-${SMOKE_ID}"

pretty_json() {
  if command -v jq >/dev/null 2>&1; then
    jq .
  else
    cat
    echo
  fi
}

post_json() {
  local path="$1"
  local body="$2"
  curl -fsS -X POST "$BASE_URL$path" \
    -H "Content-Type: application/json" \
    -H "X-Actor: $ACTOR" \
    -H "X-Correlation-Id: $CORRELATION_ID" \
    -d "$body"
}

issue_token() {
  local resource_type="$1"
  local resource_id="$2"
  local purpose="$3"
  post_json "/api/v1/fencing-tokens" "{
    \"resourceType\": \"$resource_type\",
    \"resourceId\": \"$resource_id\",
    \"purpose\": \"$purpose\",
    \"ttlSeconds\": 300,
    \"reason\": \"$purpose\"
  }" | jq -r '.token'
}

transition_operation() {
  local operation_id="$1"
  local expected_status="$2"
  local expected_version="$3"
  local target_status="$4"
  local token
  token="$(issue_token operation_plan "$operation_id" "move to $target_status")"
  post_json "/api/v1/operation-plans/$operation_id/transition" "{
    \"expectedStatus\": \"$expected_status\",
    \"expectedVersion\": $expected_version,
    \"targetStatus\": \"$target_status\",
    \"reason\": \"move to $target_status\",
    \"fencingToken\": \"$token\"
  }" >/dev/null
}

curl -fsS "$BASE_URL/api/v1/control/health" | pretty_json

INSTANCE_ID="smoke-instance-${SMOKE_ID}"
AGENT_ID="smoke-agent-${SMOKE_ID}"
RULE_ID="smoke-rule-${SMOKE_ID}"
OPERATION_ID="smoke-operation-${SMOKE_ID}"
BATCH_ID="smoke-batch-${SMOKE_ID}"

post_json "/api/v1/instances" "{
  \"id\": \"$INSTANCE_ID\",
  \"applicationId\": \"app-default\",
  \"environmentId\": \"env-dev\",
  \"hostname\": \"smoke-host\",
  \"processId\": \"1\",
  \"runtime\": \"java-21\",
  \"labels\": {\"tier\": \"smoke\", \"smokeId\": \"$SMOKE_ID\"},
  \"reason\": \"smoke test\"
}" | pretty_json

post_json "/api/v1/agents" "{
  \"id\": \"$AGENT_ID\",
  \"instanceId\": \"$INSTANCE_ID\",
  \"status\": \"ACTIVE\",
  \"agentVersion\": \"0.1.0\",
  \"bootstrapVersion\": \"0.1.0\",
  \"listenHost\": \"127.0.0.1\",
  \"listenPort\": 18080,
  \"tokenHash\": \"smoke-token-hash\",
  \"capabilities\": [\"JAVA_METHOD\"],
  \"reason\": \"smoke test\"
}" | pretty_json

post_json "/api/v1/rules" "{
  \"id\": \"$RULE_ID\",
  \"applicationId\": \"app-default\",
  \"environmentId\": \"env-dev\",
  \"name\": \"Smoke rule $SMOKE_ID\",
  \"riskLevel\": \"LOW\",
  \"script\": {\"phase\": \"BEFORE\", \"script\": \"return mock.proceed(args)\"},
  \"targets\": [{
    \"protocol\": \"JAVA_METHOD\",
    \"className\": \"example.Smoke\",
    \"methodName\": \"ping\",
    \"matcher\": {\"descriptor\": \"()Ljava/lang/String;\"}
  }],
  \"reason\": \"smoke test\"
}" | pretty_json

if command -v jq >/dev/null 2>&1; then
  post_json "/api/v1/operation-plans" "{
    \"id\": \"$OPERATION_ID\",
    \"applicationId\": \"app-default\",
    \"environmentId\": \"env-dev\",
    \"planType\": \"RULE_ROLLOUT\",
    \"resourceType\": \"rule\",
    \"resourceId\": \"$RULE_ID\",
    \"resourceVersion\": 1,
    \"strategy\": {\"mode\": \"smoke\"},
    \"rollout\": {\"mode\": \"SEQUENTIAL\", \"batchPolicy\": {\"batchSize\": 1}},
    \"reason\": \"smoke test\"
  }" | pretty_json

  transition_operation "$OPERATION_ID" DRAFT 1 WAITING_APPROVAL
  transition_operation "$OPERATION_ID" WAITING_APPROVAL 2 APPROVED
  transition_operation "$OPERATION_ID" APPROVED 3 RUNNING

  post_json "/api/v1/operation-plans/$OPERATION_ID/batches" "{
    \"id\": \"$BATCH_ID\",
    \"batchOrder\": 1,
    \"targetSelector\": {\"labels\": {\"smokeId\": \"$SMOKE_ID\"}},
    \"reason\": \"smoke test\"
  }" | pretty_json

  post_json "/api/v1/control/schedulers/run-once" "{}" | pretty_json

  COMMAND_ID="$(post_json "/api/v1/agents/$AGENT_ID/commands/next" "{\"leaseSeconds\": 30}" | jq -r '.id')"
  if [[ "$COMMAND_ID" != "null" && -n "$COMMAND_ID" ]]; then
    curl -fsS -X POST "$BASE_URL/api/v1/agent-commands/$COMMAND_ID/ack" \
      -H "Content-Type: application/json" \
      -H "X-Actor: $AGENT_ID" \
      -H "X-Identity-Source: agent" \
      -H "X-Correlation-Id: $CORRELATION_ID" \
      -d '{"status":"ACKED","result":{"smoke":true},"reason":"smoke ack"}' | pretty_json
  fi
else
  echo "jq not found; skipped rollout command smoke"
fi

curl -fsS "$BASE_URL/api/v1/agent-commands" | pretty_json
curl -fsS "$BASE_URL/api/v1/fencing-tokens" | pretty_json
curl -fsS "$BASE_URL/api/v1/audits" | pretty_json
