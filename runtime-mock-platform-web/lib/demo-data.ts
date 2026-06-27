import type { PlatformRecord, ScriptTestResult, ScriptValidationResult } from "@/lib/api/types";

const stamp = "2026-06-18T09:30:00+08:00";

const datasets: Record<string, PlatformRecord[]> = {
  agents: [
    { id: "agt-01", name: "order-service-01", application: "order-service", environment: "prod", status: "ONLINE", version: "1.8.2", host: "10.24.8.11", lastHeartbeatAt: stamp },
    { id: "agt-02", name: "payment-service-02", application: "payment-service", environment: "staging", status: "ONLINE", version: "1.8.2", host: "10.24.9.22", lastHeartbeatAt: stamp },
    { id: "agt-03", name: "inventory-service-01", application: "inventory-service", environment: "prod", status: "DEGRADED", version: "1.7.9", host: "10.24.6.41", lastHeartbeatAt: "2026-06-18T09:24:00+08:00" },
    { id: "agt-04", name: "gateway-local", application: "api-gateway", environment: "dev", status: "OFFLINE", version: "1.7.7", host: "127.0.0.1", lastHeartbeatAt: "2026-06-17T20:10:00+08:00" },
  ],
  instances: [
    { id: "ins-01", application: "order-service", environment: "prod", host: "10.24.8.11", status: "RUNNING", registeredAt: stamp },
    { id: "ins-02", application: "payment-service", environment: "staging", host: "10.24.9.22", status: "RUNNING", registeredAt: stamp },
  ],
  rules: [
    { id: "rule-01", name: "订单查询延迟注入", applicationId: "order-service", environmentId: "staging", status: "ACTIVE", currentVersion: 7, updatedAt: stamp },
    { id: "rule-02", name: "支付超时模拟", applicationId: "payment-service", environmentId: "prod", status: "DRAFT", currentVersion: 3, updatedAt: "2026-06-18T08:12:00+08:00" },
    { id: "rule-03", name: "库存返回值替换", applicationId: "inventory-service", environmentId: "dev", status: "PAUSED", currentVersion: 12, updatedAt: "2026-06-17T18:40:00+08:00" },
  ],
  "rule-versions": [
    { id: "rv-07", ruleId: "rule-01", version: 7, status: "PUBLISHED", author: "平台管理员", createdAt: stamp },
    { id: "rv-06", ruleId: "rule-01", version: 6, status: "ARCHIVED", author: "研发工程师", createdAt: "2026-06-16T15:22:00+08:00" },
  ],
  "operation-plans": [
    { id: "plan-01", name: "订单服务规则发布", ruleId: "rule-01", environment: "staging", status: "RUNNING", progress: 68, updatedAt: stamp },
    { id: "plan-02", name: "支付服务全量撤回", ruleId: "rule-02", environment: "prod", status: "PENDING_APPROVAL", progress: 0, updatedAt: "2026-06-18T08:32:00+08:00" },
    { id: "plan-03", name: "库存规则发布", ruleId: "rule-03", environment: "prod", status: "COMPLETED", progress: 100, updatedAt: "2026-06-17T16:20:00+08:00" },
  ],
  "rollout-executions": [
    { id: "rex-01", operationPlanId: "plan-01", agentId: "agt-01", status: "SUCCESS", durationMs: 182, createdAt: stamp },
    { id: "rex-02", operationPlanId: "plan-01", agentId: "agt-03", status: "RETRYING", durationMs: 4200, createdAt: stamp },
  ],
  "recording-sessions": [
    { id: "rec-01", name: "支付回调基线采集", application: "payment-service", environment: "staging", status: "RECORDING", sampleCount: 1284, updatedAt: stamp },
    { id: "rec-02", name: "订单查询脱敏样本", application: "order-service", environment: "prod", status: "COMPLETED", sampleCount: 8421, updatedAt: "2026-06-17T21:12:00+08:00" },
  ],
  "recording-rules": [
    { id: "rr-01", name: "订单查询采集规则", application: "order-service", status: "ACTIVE", sampleRate: "10%", updatedAt: stamp },
  ],
  datasets: [
    { id: "ds-01", name: "订单查询基线 2026-06", source: "rec-02", version: 5, objectCount: 8421, sizeBytes: 152043520, status: "READY", updatedAt: stamp },
    { id: "ds-02", name: "支付异常样本", source: "rec-01", version: 2, objectCount: 1284, sizeBytes: 27472691, status: "BUILDING", updatedAt: stamp },
  ],
  datasources: [
    { id: "source-01", name: "Staging PostgreSQL", type: "POSTGRESQL", endpoint: "postgres.internal:5432", status: "HEALTHY", updatedAt: stamp },
    { id: "source-02", name: "MinIO Recording Bucket", type: "S3", endpoint: "minio:9000", status: "HEALTHY", updatedAt: stamp },
  ],
  "extraction-tasks": [
    { id: "ext-01", name: "订单脱敏字段提取", template: "订单查询模板", datasource: "Staging PostgreSQL", status: "RUNNING", progress: 74, updatedAt: stamp },
    { id: "ext-02", name: "支付错误码提取", template: "支付异常模板", datasource: "MinIO Recording Bucket", status: "COMPLETED", progress: 100, updatedAt: "2026-06-17T13:10:00+08:00" },
  ],
  "extraction-templates": [
    { id: "tpl-01", name: "订单查询模板", sourceType: "POSTGRESQL", fields: 12, status: "ACTIVE", updatedAt: stamp },
    { id: "tpl-02", name: "支付异常模板", sourceType: "S3", fields: 8, status: "ACTIVE", updatedAt: stamp },
  ],
  "extraction-executions": [
    { id: "exe-01", taskId: "ext-01", status: "RUNNING", processed: 7400, total: 10000, startedAt: stamp },
  ],
  "extraction-results": [
    { id: "result-01", executionId: "exe-01", datasetId: "ds-01", objectCount: 7400, status: "PARTIAL", updatedAt: stamp },
  ],
  "replay-plans": [
    { id: "replay-01", name: "订单接口回归", dataset: "订单查询基线 2026-06", target: "order-service/staging", status: "READY", updatedAt: stamp },
    { id: "replay-02", name: "支付超时复现", dataset: "支付异常样本", target: "payment-service/dev", status: "RUNNING", updatedAt: stamp },
  ],
  "replay-executions": [
    { id: "rpe-01", planId: "replay-02", status: "RUNNING", progress: 43, successRate: "97.8%", updatedAt: stamp },
  ],
  "replay-batches": [
    { id: "rpb-01", executionId: "rpe-01", sequence: 1, status: "COMPLETED", invocationCount: 500, updatedAt: stamp },
    { id: "rpb-02", executionId: "rpe-01", sequence: 2, status: "RUNNING", invocationCount: 500, updatedAt: stamp },
  ],
  "replay-invocation-results": [
    { id: "invoke-01", batchId: "rpb-02", operation: "PaymentFacade.pay", status: "MATCHED", durationMs: 42, createdAt: stamp },
    { id: "invoke-02", batchId: "rpb-02", operation: "PaymentFacade.query", status: "DIFF", durationMs: 68, createdAt: stamp },
  ],
  "comparison-results": [
    { id: "cmp-01", invocationId: "invoke-02", status: "DIFF", path: "$.data.status", expected: "SUCCESS", actual: "PROCESSING", createdAt: stamp },
  ],
  approvals: [
    { id: "approval-01", subject: "支付服务全量撤回", type: "ROLLOUT", requester: "研发工程师", status: "PENDING", risk: "HIGH", createdAt: stamp },
    { id: "approval-02", subject: "生产录制会话延期", type: "RECORDING", requester: "平台管理员", status: "APPROVED", risk: "MEDIUM", createdAt: "2026-06-17T11:20:00+08:00" },
  ],
  audits: [
    { id: "audit-01", actor: "平台管理员", action: "RULE_VERSION_PUBLISHED", resource: "订单查询延迟注入 v7", result: "SUCCESS", correlationId: "req-8cf2d1", createdAt: stamp },
    { id: "audit-02", actor: "研发工程师", action: "ROLLOUT_REQUESTED", resource: "支付服务全量撤回", result: "PENDING_APPROVAL", correlationId: "req-1fd920", createdAt: "2026-06-18T08:32:00+08:00" },
    { id: "audit-03", actor: "系统", action: "AGENT_HEARTBEAT_MISSED", resource: "inventory-service-01", result: "WARNING", correlationId: "req-b6d21a", createdAt: "2026-06-18T09:24:00+08:00" },
  ],
  outbox: [
    { id: "evt-01", aggregateType: "ROLLOUT", eventType: "BATCH_STARTED", status: "PUBLISHED", attempts: 1, createdAt: stamp },
  ],
  "worker-artifacts": [
    { id: "artifact-01", type: "EXTRACTION_RESULT", storageKey: "extractions/2026/06/result-01.json", sizeBytes: 5242880, status: "AVAILABLE", createdAt: stamp },
  ],
  "auth/tokens": [
    { id: "token-01", name: "CI Smoke Token", subject: "runtime-mock-ci", roles: ["ADMIN"], status: "ACTIVE", expiresAt: "2026-09-18T00:00:00+08:00" },
    { id: "token-02", name: "Read Only Console", subject: "runtime-mock-viewer", roles: ["VIEWER"], status: "ACTIVE", expiresAt: "2026-07-18T00:00:00+08:00" },
  ],
  tokens: [
    { id: "token-01", displayName: "CI Smoke Token", subjectType: "USER", subjectId: "system", status: "ACTIVE", expiresAt: "2026-09-18T00:00:00+08:00" },
    { id: "token-02", displayName: "Read Only Console", subjectType: "USER", subjectId: "reviewer", status: "ACTIVE", expiresAt: "2026-07-18T00:00:00+08:00" },
  ],
};

function normalize(path: string) {
  return path.replace(/^\/+|\/+$/g, "").split("?")[0];
}

export function demoList(path: string): PlatformRecord[] | undefined {
  return datasets[normalize(path)];
}

export function demoTargets(query = "", applicationId = "", environmentId = "") {
  const targets = [
    { class_name: "com.example.order.OrderQueryService", method_name: "queryOrder", protocol: "JAVA_METHOD", version_count: 3, application_id: "order-service", environment_id: "prod" },
    { class_name: "com.example.payment.PaymentFacade", method_name: "pay", protocol: "JAVA_METHOD", version_count: 2, application_id: "payment-service", environment_id: "staging" },
  ];
  const needle = query.trim().toLowerCase();
  return targets
    .filter((target) => !applicationId || target.application_id === applicationId)
    .filter((target) => !environmentId || target.environment_id === environmentId)
    .filter((target) => !needle || target.class_name.toLowerCase().includes(needle) || target.method_name.toLowerCase().includes(needle))
    .map((target) => ({
      class_name: target.class_name,
      method_name: target.method_name,
      protocol: target.protocol,
      version_count: target.version_count,
    }));
}

export function demoPage(path: string, page = 0, size = 25, query = "") {
  const rows = demoList(path) ?? [];
  const needle = query.trim().toLowerCase();
  const filtered = needle
    ? rows.filter((row) => JSON.stringify(row).toLowerCase().includes(needle))
    : rows;
  return {
    items: filtered.slice(page * size, (page + 1) * size),
    page,
    size,
    total: filtered.length,
  };
}

export function demoDetail(path: string, id: string): PlatformRecord | undefined {
  const record = demoList(path)?.find((item) => String(item.id) === id);
  return record ? { ...record, allowed_actions: [] } : undefined;
}

export function demoRuleDetail(id: string) {
  const rule = demoDetail("rules", id);
  if (!rule) return undefined;
  return {
    rule,
    versions: [{
      id: `${id}-version-1`,
      rule_id: id,
      version: Number(rule.currentVersion ?? 1),
      script_json: JSON.stringify({
        phase: "BEFORE",
        script: "if (ctx.arguments()[0] == 'demo') {\n  return mock.returnValue('mocked')\n}\nreturn mock.proceed()",
      }),
      status: "DRAFT",
      risk_level: "LOW",
    }],
    targets: [{
      rule_version_id: `${id}-version-1`,
      protocol: "JAVA_METHOD",
      class_name: "com.example.DemoService",
      method_name: "execute",
    }],
    capabilities: [],
  };
}

export function demoDashboard() {
  const audits = datasets.audits ?? [];
  return {
    checkedAt: stamp,
    counts: {
      agentsTotal: datasets.agents?.length ?? 0,
      agentsOnline: datasets.agents?.filter((item) => item.status === "ONLINE").length ?? 0,
      rulesTotal: datasets.rules?.length ?? 0,
      rulesActive: datasets.rules?.filter((item) => item.status === "ACTIVE").length ?? 0,
      rolloutsRunning: datasets["operation-plans"]?.filter((item) => item.status === "RUNNING").length ?? 0,
      approvalsPending: datasets.approvals?.filter((item) => ["WAITING_APPROVAL", "PENDING"].includes(String(item.status))).length ?? 0,
      recordingsRunning: datasets["recording-sessions"]?.filter((item) => item.status === "RECORDING").length ?? 0,
      workerArtifacts: datasets["worker-artifacts"]?.length ?? 0,
    },
    auditTrends: [
      { label: "SUCCESS", value: audits.filter((item) => item.result === "SUCCESS").length },
      { label: "WARNING", value: audits.filter((item) => item.result === "WARNING").length },
      { label: "PENDING", value: audits.filter((item) => String(item.result).includes("PENDING")).length },
    ],
    recentAudits: audits.map((item) => ({
      ...item,
      resource_id: item.resource,
      occurred_at: item.createdAt,
    })),
  };
}

export function demoHealth() {
  return {
    status: "UP",
    mode: "DEMO",
    services: {
      platformApi: { status: "UP", latencyMs: 24 },
      postgresql: { status: "UP", latencyMs: 7 },
      kafka: { status: "UP", latencyMs: 12 },
      redis: { status: "UP", latencyMs: 3 },
      minio: { status: "UP", latencyMs: 18 },
    },
    checkedAt: stamp,
  };
}

export function demoValidate(body: PlatformRecord): ScriptValidationResult {
  const script = String(body.script ?? "");
  const diagnostics = [];
  if (!script.trim()) {
    diagnostics.push({ severity: "error" as const, code: "SCRIPT_EMPTY", message: "脚本不能为空", line: 1, column: 1 });
  }
  if (script.includes("System.exit")) {
    diagnostics.push({ severity: "error" as const, code: "FORBIDDEN_API", message: "禁止调用 System.exit", line: 1, column: Math.max(1, script.indexOf("System.exit") + 1) });
  }
  if (script.includes("sleep(")) {
    diagnostics.push({ severity: "warning" as const, code: "BLOCKING_CALL", message: "阻塞调用可能占用业务线程", line: 1, column: Math.max(1, script.indexOf("sleep(") + 1) });
  }
  return { valid: !diagnostics.some((item) => item.severity === "error"), diagnostics, compileTimeMs: 31, policy: "runtime-mock-safe-groovy-v1" };
}

export function demoTest(body: PlatformRecord): ScriptTestResult {
  const script = String(body.script ?? "");
  if (script.includes("throw ")) {
    return {
      status: "FAILED",
      durationMs: 18,
      exception: { type: "java.lang.IllegalStateException", message: "演示脚本主动抛出异常" },
      logs: ["沙箱初始化完成", "捕获到脚本异常，未影响真实业务进程"],
    };
  }
  return {
    status: "SUCCESS",
    durationMs: 24,
    output: { code: 200, message: "mocked by Runtime Mock", data: { orderId: "RM-20260618-001" } },
    logs: ["编译缓存命中", "沙箱执行完成", "返回值已通过序列化检查"],
    diff: { before: null, after: { message: "mocked by Runtime Mock" } },
  };
}

export function demoMutation(path: string, body: PlatformRecord) {
  const resource = normalize(path).split("/")[0] || "resource";
  return {
    id: `${resource.slice(0, 4)}-${crypto.randomUUID().slice(0, 8)}`,
    ...body,
    status: body.status ?? "DRAFT",
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    demo: true,
  };
}
