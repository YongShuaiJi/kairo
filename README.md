# Kairo

Java runtime method mock and fault-injection MVP based on Java Instrumentation, Byte Buddy Advice, and precompiled Groovy scripts.

## Modules

- `kairo-bootstrap-api`: bootstrap-safe bridge API used by instrumented business methods.
- `kairo-api`: public domain model and script API.
- `kairo-object`: JSON conversion, property path access, object and throwable creation.
- `kairo-groovy`: save-time Groovy compilation, script class cache, and runtime script base class.
- `kairo-core`: immutable rule sets, atomic rule registry, dispatcher, validation, sampling, hit limits, fail-open, and reentry guard.
- `kairo-agent-core`: Byte Buddy transformer manager and value/void method advice.
- `kairo-agent-server`: local JDK `HttpServer` API, embedded local console, runtime lifecycle, and optional platform command polling.
- `kairo-agent-core-modern`: shaded modern core assembly loaded by the thin bootstrap agent on JDK 17/21.
- `kairo-agent-bootstrap`: thin `premain` and `agentmain` entrypoints that reflectively load an isolated core jar.
- `kairo-attach-cli`: dynamic attach command implemented through reflective JDK Attach API access.
- `kairo-ops`: local emergency operations CLI.
- `kairo-sidecar`: attach executor and runtime helper boundary used by the demo attach flow.
- `kairo-platform-server`: Spring Boot 3 / Java 21 platform image backed by PostgreSQL and Redis.
- `kairo-platform-web`: independent Next.js / React 19 central management UI with TypeScript,
  Tailwind CSS, shadcn/ui, Lucide icons, Monaco Editor, and Kairo domain components.
- `kairo-demo`: Spring Boot-compatible demo domain and `OrderService`.
- `kairo-integration-tests`: JVM integration tests using dynamic Byte Buddy attachment.

## Build And Test

```bash
mvn test
mvn -DskipTests package
```

The main runnable artifacts are generated at:

```text
kairo-agent-bootstrap/target/kairo-agent-bootstrap.jar
kairo-agent-core-modern/target/kairo-agent-core-modern.jar
kairo-attach-cli/target/kairo-attach.jar
kairo-ops/target/kairo-ops.jar
kairo-platform-server/target/kairo-platform-server-0.1.0-SNAPSHOT.jar
```

## Premain

```bash
java \
  -javaagent:kairo-agent-bootstrap/target/kairo-agent-bootstrap.jar=coreJar=kairo-agent-core-modern/target/kairo-agent-core-modern.jar,bootstrapJar=kairo-bootstrap-api/target/kairo-bootstrap-api-0.1.0-SNAPSHOT.jar,host=127.0.0.1,port=18080,token=dev \
  -jar your-application.jar
```

To let the Agent pull platform commands, add `platformUrl` and `platformAgentId`:

```bash
java \
  -javaagent:kairo-agent-bootstrap/target/kairo-agent-bootstrap.jar=coreJar=kairo-agent-core-modern/target/kairo-agent-core-modern.jar,bootstrapJar=kairo-bootstrap-api/target/kairo-bootstrap-api-0.1.0-SNAPSHOT.jar,host=127.0.0.1,port=18080,token=dev,platformUrl=http://127.0.0.1:18280,platformAgentId=agent-1 \
  -jar your-application.jar
```

## Agentmain

The agent jar declares `Agent-Class`, so it can be attached with the packaged CLI:

```bash
java -jar kairo-attach-cli/target/kairo-attach.jar \
  --pid <pid> \
  --agent kairo-agent-bootstrap/target/kairo-agent-bootstrap.jar \
  --core-jar kairo-agent-core-modern/target/kairo-agent-core-modern.jar \
  --bootstrap-jar kairo-bootstrap-api/target/kairo-bootstrap-api-0.1.0-SNAPSHOT.jar \
  --port 18080 \
  --token dev
```

It can also be loaded through JDK tooling:

```bash
jcmd <pid> JVMTI.agent_load kairo-agent-bootstrap/target/kairo-agent-bootstrap.jar "attach=true,coreJar=kairo-agent-core-modern/target/kairo-agent-core-modern.jar,bootstrapJar=kairo-bootstrap-api/target/kairo-bootstrap-api-0.1.0-SNAPSHOT.jar,host=127.0.0.1,port=18080,token=dev"
```

For local development, integration tests use:

```java
Instrumentation instrumentation = ByteBuddyAgent.install();
AgentRuntime runtime = new AgentRuntime(instrumentation);
runtime.start();
```

## Agent HTTP API

All JSON endpoints except `/health` and `/v1/health` require `X-Agent-Token: <token>` or
`Authorization: Bearer <token>`. The loopback-only HTML console is public, but it must submit the
Agent token before it can call an API.

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
java -jar kairo-ops/target/kairo-ops.jar status \
  --url http://127.0.0.1:18080 \
  --token dev

java -jar kairo-ops/target/kairo-ops.jar disable-rule \
  --rule-id <ruleId> \
  --url http://127.0.0.1:18080 \
  --token dev

java -jar kairo-ops/target/kairo-ops.jar reset-all \
  --reason "break glass event INC-123" \
  --url http://127.0.0.1:18080 \
  --token dev
```

## Local Agent Console

Open:

```text
http://127.0.0.1:18080/
```

The console is served directly by `kairo-agent-server`; there is no additional proxy or
in-memory control-plane process.

## Local demo target

For a quick end-to-end fault-injection exercise, start the bundled Spring Boot demo with the Agent on port `18080` and the demo app on port `18090`:

```bash
java \
  -javaagent:/Users/jiyongshuai/code/kairo/kairo-agent-bootstrap/target/kairo-agent-bootstrap.jar=coreJar=/Users/jiyongshuai/code/kairo/kairo-agent-core-modern/target/kairo-agent-core-modern.jar,bootstrapJar=/Users/jiyongshuai/code/kairo/kairo-bootstrap-api/target/kairo-bootstrap-api-0.1.0-SNAPSHOT.jar,host=127.0.0.1,port=18080,token=dev,platformUrl=http://127.0.0.1:18280,platformToken=kairo-dev-admin-token-change-me,platformProjectName=kairo,platformApplicationName=kairo-demo \
  -jar kairo-demo/target/kairo-demo-0.1.0-SNAPSHOT-exec.jar \
  --server.port=18090
```

`platformProjectName` 和 `platformApplicationName` 是平台展示及资源归属使用的真实业务名称。
Platform 会按“项目名 + 应用名”复用或创建应用，并生成独立内部 ID；不要再把
`app-default` 作为新接入应用的标识。

Verify the baseline behavior:

```bash
curl -X POST http://127.0.0.1:18090/demo/orders \
  -H 'Content-Type: application/json' \
  -d '{"userId":"u1","amount":12.34}'
```

Then open the console, enter token `dev`, search `OrderService`, select a method such as
`createOrder`, and publish a fault script. Persistent control-plane resources are available only
from `kairo-platform-server`.

## Production Platform Server

The production control-plane module is the authoritative API for PostgreSQL-backed platform state:

```bash
mvn -pl kairo-platform-server -am test
mvn -pl kairo-platform-server -am -DskipTests package
java -jar kairo-platform-server/target/kairo-platform-server-0.1.0-SNAPSHOT.jar
```

Docker-assisted local platform:

```bash
./scripts/platform-up.sh
./scripts/platform-smoke.sh
./scripts/platform-down.sh
```

The central Web console is available at:

```text
http://127.0.0.1:18380/
```

Use the Compose development token `kairo-dev-admin-token-change-me`. The Web process is an
independent Next.js deployment and connects to Platform API through its same-origin BFF.

The default architecture deliberately does not require Kubernetes, Keycloak, Vault, Kafka, MinIO, or a cloud KMS.
Compose runs a Platform API process with the V1 scheduler enabled. Authentication
uses revocable opaque Bearer Tokens whose hashes are stored in PostgreSQL.

Design and requirements:

```text
docs/architecture/simplified-platform-architecture.md
docs/architecture/kairo-platform-web-design.md
docs/architecture/module-boundary-governance.md
docs/requirements/kairo-product-requirements.md
```

Primary platform endpoints:

```text
GET  /api/v1/control/health
POST /api/v1/control/schedulers/run-once
GET  /api/v1/auth/me
GET  /api/v1/auth/tokens
POST /api/v1/auth/tokens
DELETE /api/v1/auth/tokens/{id}
POST /api/v1/scripts/validate
POST /api/v1/scripts/test
GET  /api/v1/dashboard/overview
GET  /api/v1/query/{resource}
GET  /api/v1/details/{resource}/{id}
GET  /api/v1/targets/search
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
GET  /api/v1/rules/{id}/detail
GET  /api/v1/operation-plans
POST /api/v1/operation-plans
POST /api/v1/operation-plans/{id}/transition
POST /api/v1/operation-plans/{id}/unload
GET  /api/v1/rollout-executions
```

Supporting documents:

```text
docs/README.md
docs/developer/platform-technical-guide.md
docs/user-guide/platform-complete-user-guide.md
docs/user-guide/rule-script-authoring-guide.md
docs/api/platform-openapi.yaml
docs/api/permission-matrix.md
docs/api/error-codes.md
docs/ops/production-readiness-checklist.md
docs/ops/platform-docker.md
docs/copyright/runtime-mock-software-copyright-application.md
```

## Current Product Behavior

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
- Groovy runtime execution is bounded; repeated timeout/error conditions lock unsafe rules while business calls remain fail-open.
- The Spring Boot platform server persists control-plane state with PostgreSQL/Flyway and covers applications, instances, agents, commands, rules, rule versions, rollouts, unload execution, Redis-backed fencing, and audit hash chains.
- Rollout execution can enqueue idempotent Agent commands; Agents poll and ack commands, and ack results advance rollout execution and operation-plan state.
- Rule versions are immutable once created; disabling a version starts the 30-day retention countdown and automatically unloads affected runtime bytecode when needed.
- The independent Next.js Web console provides authenticated dashboards, application instances, rule ledgers, rollout management, and a Monaco rule workbench backed by real Platform APIs.

## Current Limits

- The agent now uses a thin Java 8 bootstrap jar and a shaded modern core jar. The legacy JDK 8/11 core packaging is still pending.
- Groovy security combines AST restrictions and bounded runtime execution, but it is not process-level isolation. Only trusted administrators should be allowed to author production rules.
- Dynamic attach can emit JDK warnings on modern Java; prefer `-javaagent` for stable environments.
- The former local `kairo-control-server` and single-consumer `kairo-web` modules were removed. The Agent serves its local console directly, and all persistent control-plane state lives in `kairo-platform-server`.
- Platform authentication uses revocable opaque user/Agent Bearer Tokens. OIDC remains an optional future identity-provider adapter.
- `kairo-sidecar` is used by the local attach demo flow; it is not a separate production storage or replay subsystem.
- Recording, dataset extraction, replay, approval workflow, outbox publishing, Kafka, and MinIO have been removed from the active product surface.
- Kubernetes, enterprise SSO, performance certification, and multi-region operation are optional future integrations rather than dependencies of the current product.
