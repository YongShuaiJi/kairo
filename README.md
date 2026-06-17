# Runtime Mock

Java runtime method mock and fault-injection MVP based on Java Instrumentation, Byte Buddy Advice, and precompiled Groovy scripts.

## Modules

- `runtime-mock-bootstrap-api`: bootstrap-safe bridge API used by instrumented business methods.
- `runtime-mock-api`: public domain model and script API.
- `runtime-mock-object`: JSON conversion, property path access, object and throwable creation.
- `runtime-mock-groovy`: save-time Groovy compilation, script class cache, and runtime script base class.
- `runtime-mock-core`: immutable rule sets, atomic rule registry, dispatcher, validation, sampling, hit limits, fail-open, and reentry guard.
- `runtime-mock-agent-core`: Byte Buddy transformer manager and value/void method advice.
- `runtime-mock-agent-server`: local JDK `HttpServer` API for health, JVM info, class/method discovery, script compile, rules, metrics, events, reset, and optional platform command polling.
- `runtime-mock-agent-core-modern`: shaded modern core assembly loaded by the thin bootstrap agent on JDK 17/21.
- `runtime-mock-agent-bootstrap`: thin `premain` and `agentmain` entrypoints that reflectively load an isolated core jar.
- `runtime-mock-attach-cli`: dynamic attach command implemented through reflective JDK Attach API access.
- `runtime-mock-ops`: local emergency operations CLI.
- `runtime-mock-sidecar`: production recording safety primitives, including masking/tokenization and encrypted WAL.
- `runtime-mock-web`: static operations console assets.
- `runtime-mock-control-server`: lightweight control server that hosts the web console, proxies Agent API calls, and exposes early `/api/v1` control-plane resources.
- `runtime-mock-platform-server`: Spring Boot 3 / Java 21 production control plane with PostgreSQL/Flyway, Redis fencing, RBAC, approval, audit hash chain, outbox/Kafka publishing, agent command queue, rollout executor, extraction worker, and replay worker.
- `runtime-mock-demo`: Spring Boot-compatible demo domain and `OrderService`.
- `runtime-mock-integration-tests`: JVM integration tests using dynamic Byte Buddy attachment.

## Build And Test

```bash
mvn test
mvn -DskipTests package
```

The main runnable artifacts are generated at:

```text
runtime-mock-agent-bootstrap/target/runtime-mock-agent-bootstrap.jar
runtime-mock-agent-core-modern/target/runtime-mock-agent-core-modern.jar
runtime-mock-attach-cli/target/runtime-mock-attach.jar
runtime-mock-ops/target/runtime-mock-ops.jar
runtime-mock-sidecar/target/runtime-mock-sidecar-0.1.0-SNAPSHOT.jar
runtime-mock-control-server/target/runtime-mock-control-server.jar
runtime-mock-platform-server/target/runtime-mock-platform-server-0.1.0-SNAPSHOT.jar
```

## Premain

```bash
java \
  -javaagent:runtime-mock-agent-bootstrap/target/runtime-mock-agent-bootstrap.jar=coreJar=runtime-mock-agent-core-modern/target/runtime-mock-agent-core-modern.jar,bootstrapJar=runtime-mock-bootstrap-api/target/runtime-mock-bootstrap-api-0.1.0-SNAPSHOT.jar,host=127.0.0.1,port=18080,token=dev \
  -jar your-application.jar
```

To let the Agent pull platform commands, add `platformUrl` and `platformAgentId`:

```bash
java \
  -javaagent:runtime-mock-agent-bootstrap/target/runtime-mock-agent-bootstrap.jar=coreJar=runtime-mock-agent-core-modern/target/runtime-mock-agent-core-modern.jar,bootstrapJar=runtime-mock-bootstrap-api/target/runtime-mock-bootstrap-api-0.1.0-SNAPSHOT.jar,host=127.0.0.1,port=18080,token=dev,platformUrl=http://127.0.0.1:18280,platformAgentId=agent-1 \
  -jar your-application.jar
```

## Agentmain

The agent jar declares `Agent-Class`, so it can be attached with the packaged CLI:

```bash
java -jar runtime-mock-attach-cli/target/runtime-mock-attach.jar \
  --pid <pid> \
  --agent runtime-mock-agent-bootstrap/target/runtime-mock-agent-bootstrap.jar \
  --core-jar runtime-mock-agent-core-modern/target/runtime-mock-agent-core-modern.jar \
  --bootstrap-jar runtime-mock-bootstrap-api/target/runtime-mock-bootstrap-api-0.1.0-SNAPSHOT.jar \
  --port 18080 \
  --token dev
```

It can also be loaded through JDK tooling:

```bash
jcmd <pid> JVMTI.agent_load runtime-mock-agent-bootstrap/target/runtime-mock-agent-bootstrap.jar "attach=true,coreJar=runtime-mock-agent-core-modern/target/runtime-mock-agent-core-modern.jar,bootstrapJar=runtime-mock-bootstrap-api/target/runtime-mock-bootstrap-api-0.1.0-SNAPSHOT.jar,host=127.0.0.1,port=18080,token=dev"
```

For local development, integration tests use:

```java
Instrumentation instrumentation = ByteBuddyAgent.install();
AgentRuntime runtime = new AgentRuntime(instrumentation);
runtime.start();
```

## Agent HTTP API

All endpoints except `/health` and `/v1/health` require `X-Agent-Token: <token>` or `Authorization: Bearer <token>`.

```text
GET  /health
GET  /v1/health
GET  /v1/status
GET  /jvm
GET  /v1/classes?keyword=OrderService
GET  /v1/classes/{classId}/methods
POST /v1/scripts/compile
GET  /v1/rules
POST /v1/rules
PUT  /v1/rules/{ruleId}
POST /v1/rules/{ruleId}/enable
POST /v1/rules/{ruleId}/disable
DELETE /v1/rules/{ruleId}
POST /v1/agent/disable-all
POST /v1/agent/enable-all
POST /v1/agent/reset-class
POST /v1/agent/reset-all
POST /v1/agent/shutdown
GET  /events
GET  /v1/events
GET  /v1/metrics
```

The old non-`/v1` paths remain available for MVP compatibility.

## Local Ops CLI

```bash
java -jar runtime-mock-ops/target/runtime-mock-ops.jar status \
  --url http://127.0.0.1:18080 \
  --token dev

java -jar runtime-mock-ops/target/runtime-mock-ops.jar disable-rule \
  --rule-id <ruleId> \
  --url http://127.0.0.1:18080 \
  --token dev

java -jar runtime-mock-ops/target/runtime-mock-ops.jar reset-all \
  --reason "break glass event INC-123" \
  --url http://127.0.0.1:18080 \
  --token dev
```

## Control Console

```bash
java -jar runtime-mock-control-server/target/runtime-mock-control-server.jar \
  --port 18180 \
  --agent http://127.0.0.1:18080 \
  --token dev
```

Open:

```text
http://127.0.0.1:18180/
```

## Local demo target

For a quick end-to-end fault-injection exercise, start the bundled Spring Boot demo with the Agent on port `18080` and the demo app on port `18090`:

```bash
java \
  -javaagent:/Users/jiyongshuai/code/runtime-mock/runtime-mock-agent-bootstrap/target/runtime-mock-agent-bootstrap.jar=coreJar=/Users/jiyongshuai/code/runtime-mock/runtime-mock-agent-core-modern/target/runtime-mock-agent-core-modern.jar,bootstrapJar=/Users/jiyongshuai/code/runtime-mock/runtime-mock-bootstrap-api/target/runtime-mock-bootstrap-api-0.1.0-SNAPSHOT.jar,host=127.0.0.1,port=18080,token=dev \
  -jar runtime-mock-demo/target/runtime-mock-demo-0.1.0-SNAPSHOT.jar \
  --server.port=18090
```

Verify the baseline behavior:

```bash
curl -X POST http://127.0.0.1:18090/demo/orders \
  -H 'Content-Type: application/json' \
  -d '{"userId":"u1","amount":12.34}'
```

Then open the console, connect to `http://127.0.0.1:18080` with token `dev`, search `OrderService`, select a method such as `createOrder`, and publish a fault script.

Implemented local control-plane endpoints:

```text
GET  /api/v1/control/health
GET  /api/v1/audits
GET  /api/v1/recording-sessions
POST /api/v1/recording-sessions
POST /api/v1/recording-sessions/{id}/transition
GET  /api/v1/datasets
POST /api/v1/datasets
GET  /api/v1/replay-plans
POST /api/v1/replay-plans
POST /api/v1/replay-plans/{id}/transition
```

Other `/api/*` paths still proxy to the local Agent API for console compatibility.

## Production Platform Server

The production control-plane module is the authoritative API for PostgreSQL-backed platform state:

```bash
mvn -pl runtime-mock-platform-server -am test
mvn -pl runtime-mock-platform-server -am -DskipTests package
java -jar runtime-mock-platform-server/target/runtime-mock-platform-server-0.1.0-SNAPSHOT.jar
```

Docker-assisted local platform:

```bash
./scripts/platform-up.sh
./scripts/platform-smoke.sh
./scripts/platform-down.sh
```

Primary platform endpoints:

```text
GET  /api/v1/control/health
POST /api/v1/control/schedulers/run-once
GET  /api/v1/fencing-tokens
POST /api/v1/fencing-tokens
GET  /api/v1/instances
POST /api/v1/instances
GET  /api/v1/sidecars
POST /api/v1/sidecars
GET  /api/v1/agents
POST /api/v1/agents
POST /api/v1/agents/{id}/heartbeat
GET  /api/v1/agent-commands
POST /api/v1/agents/{id}/commands
POST /api/v1/agents/{id}/commands/next
POST /api/v1/agent-commands/{id}/ack
GET  /api/v1/rules
POST /api/v1/rules
POST /api/v1/rules/{id}/versions
GET  /api/v1/operation-plans
POST /api/v1/operation-plans
POST /api/v1/operation-plans/{id}/transition
POST /api/v1/operation-plans/{id}/batches
POST /api/v1/rollout-batches/{id}/executions
GET  /api/v1/recording-rules
POST /api/v1/recording-rules
POST /api/v1/recording-rules/{id}/versions
GET  /api/v1/recording-sessions
POST /api/v1/recording-sessions
POST /api/v1/recording-sessions/{id}/transition
GET  /api/v1/datasets
POST /api/v1/datasets
GET  /api/v1/datasources
POST /api/v1/datasources
GET  /api/v1/extraction-templates
POST /api/v1/extraction-templates
GET  /api/v1/extraction-tasks
POST /api/v1/extraction-tasks
POST /api/v1/extraction-tasks/{id}/transition
GET  /api/v1/extraction-executions
GET  /api/v1/extraction-results
GET  /api/v1/replay-plans
POST /api/v1/replay-plans
POST /api/v1/replay-plans/{id}/transition
GET  /api/v1/replay-executions
POST /api/v1/replay-executions
POST /api/v1/replay-executions/{id}/transition
GET  /api/v1/replay-batches
GET  /api/v1/replay-invocation-results
GET  /api/v1/comparison-results
GET  /api/v1/approvals
POST /api/v1/approvals
POST /api/v1/approvals/{id}/decisions
GET  /api/v1/audits
GET  /api/v1/outbox
GET  /api/v1/worker-artifacts
```

Supporting documents:

```text
docs/api/platform-openapi.yaml
docs/api/permission-matrix.md
docs/api/error-codes.md
docs/ops/platform-docker.md
```

## Current MVP Behavior

- Supports BEFORE, RETURN, and THROWS Groovy rules.
- Supports argument replacement, early return, early throw, return replacement, return-to-throw, throw replacement, and throw-to-return.
- Supports instance, static, void, primitive return/argument, and overloaded methods.
- Rule updates replace immutable rule sets atomically.
- Rule removal unregisters the method and triggers retransformation.
- Rule disable/enable updates active instrumentation when the method crosses zero active rules.
- Agent HTTP API supports class/method search, script compile, rule publish/update/enable/disable/delete, metrics, events, disable-all, and reset-all.
- Control console supports JVM status, class/method browser, Groovy editor, templates, rule management, audit events, and hit/error statistics.
- Runtime execution is fail-open by default.
- Groovy scripts are compiled on publish/save, never on the hot path.
- Bootstrap and bridge jars are compiled as Java 8 bytecode; the modern core jar targets JDK 17/21.
- Groovy save-time security uses `SecureASTCustomizer`, receiver/import/method/property restrictions, source limits, and loop/method/class/package bans.
- The Spring Boot platform server persists control-plane state with PostgreSQL/Flyway and covers assets, agents, commands, rules, rollouts, recording, datasets, extraction, replay, approvals, Redis-backed fencing, audit hash chain, and outbox events.
- Rollout execution can enqueue idempotent Agent commands, Agents can poll and ack commands, and ack results advance rollout execution/batch/operation state.
- Extraction and replay workers can run in-process from the platform scheduler and create durable result rows plus local object-store artifacts.
- Sidecar core supports sensitive payload masking/tokenization and AES-GCM encrypted WAL records.

## Current Limits

- The agent now uses a thin Java 8 bootstrap jar and a shaded modern core jar. The legacy JDK 8/11 core packaging is still pending.
- Groovy security is an AST-level first pass, not a complete sandbox with bounded iteration counters, runtime timeouts, or automatic LOCKED workflow.
- Dynamic attach can emit JDK warnings on modern Java; prefer `-javaagent` for stable environments.
- The legacy `runtime-mock-control-server` remains for the static console and local Agent proxy path; production state lives in `runtime-mock-platform-server`.
- OIDC/JWT validation is not fully wired yet; current platform RBAC uses seeded local users and role bindings through `X-Actor`.
- Sidecar currently provides data-safety primitives only; gRPC mTLS streaming, queue ingestion, token rotation, and object storage upload workers are still pending.
- Extraction and Replay workers are implemented for platform-managed execution, sample-row/JDBC extraction, synthetic/HTTP replay, and result artifacts; remaining production hardening includes SQL plan review, MySQL driver packaging, distributed worker scaling, richer comparison policies, and cleanup execution.
