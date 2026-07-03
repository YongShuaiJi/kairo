import type { PlatformRecord, ScriptTestResult, ScriptValidationResult } from "@/lib/api/types";

const stamp = "2026-06-18T09:30:00+08:00";

const datasets: Record<string, PlatformRecord[]> = {
  agents: [
    { id: "agt-01", name: "runtime-mock-demo", application: "runtime-mock-demo", environment: "sit", status: "ONLINE", version: "0.1.0", host: "demo", lastHeartbeatAt: stamp },
    { id: "agt-02", name: "demo-attach-executor", application: "runtime-mock-demo", environment: "sit", status: "ONLINE", version: "0.1.0", host: "demo-attach-executor", lastHeartbeatAt: stamp },
  ],
  instances: [
    { id: "ins-01", nickname: "runtime-mock-demo", application: "runtime-mock-demo", environment: "sit", host: "demo", status: "ACTIVE", agentStatus: "ONLINE", loadMode: "attach", javaVersion: "21.0.11", lastSeenAt: stamp },
  ],
  rules: [
    { id: "rule-01", name: "sit 下单接口故障注入试用规则", applicationName: "runtime-mock-demo", environmentName: "sit", targetMethod: "com.example.demo.OrderService#createOrder", versionCount: 3, enabledVersionCount: 2, disabledVersionCount: 1, onlineVersion: 1, latestVersion: 3, latestVersionStatus: "ENABLED", status: "ENABLED", updatedAt: stamp },
  ],
  "rule-versions": [
    { id: "rv-03", ruleId: "rule-01", version: 3, status: "ENABLED", riskLevel: "LOW", executionPhase: "BEFORE", scriptSummary: "return mock.throwException(...)" },
    { id: "rv-02", ruleId: "rule-01", version: 2, status: "DISABLED", riskLevel: "LOW", executionPhase: "BEFORE", autoDeleteAt: "2026-07-18T09:30:00+08:00", scriptSummary: "return mock.returnValue(...)" },
    { id: "rv-01", ruleId: "rule-01", version: 1, status: "ENABLED", riskLevel: "LOW", executionPhase: "BEFORE", scriptSummary: "return mock.proceed()" },
  ],
  "operation-plans": [
    { id: "op-01", resourceId: "rule-01", resourceVersion: 1, planType: "RULE_ROLLOUT", status: "SUCCEEDED", updatedAt: stamp },
    { id: "op-02", resourceId: "rule-01", resourceVersion: 2, planType: "RULE_ROLLOUT", status: "UNLOADED", updatedAt: "2026-06-18T08:32:00+08:00" },
  ],
  "rollout-executions": [
    { id: "rex-01", operationPlanId: "op-01", instanceNickname: "runtime-mock-demo", applicationName: "runtime-mock-demo", environmentName: "sit", javaVersion: "21.0.11", loadMode: "attach", commandId: "cmd-01", status: "SUCCEEDED", createdAt: stamp },
  ],
  "rollback-executions": [
    { id: "unload-01", operationPlanId: "op-02", rollbackType: "RESET_CLASS", status: "SUCCEEDED", reason: "规则版本停用自动卸载", finishedAt: "2026-06-18T08:33:00+08:00" },
  ],
  audits: [
    { id: "audit-01", actor: "平台管理员", action: "RULE_VERSION_ENABLED", resource: "sit 下单接口故障注入试用规则 v1", result: "SUCCESS", correlationId: "req-8cf2d1", createdAt: stamp },
    { id: "audit-02", actor: "系统", action: "RULE_VERSION_DISABLED", resource: "sit 下单接口故障注入试用规则 v2", result: "SUCCESS", correlationId: "req-1fd920", createdAt: "2026-06-18T08:32:00+08:00" },
  ],
  "auth/tokens": [
    { id: "token-01", subjectId: "runtime-mock-ci", roles: ["ADMIN"], status: "VALID", expiresAt: "2026-09-18T00:00:00+08:00" },
  ],
  tokens: [
    { id: "token-01", subjectId: "system", subjectType: "USER", status: "VALID", expiresAt: "2026-09-18T00:00:00+08:00" },
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
  const latestVersion = Number(rule.latestVersion ?? rule.currentVersion ?? 1);
  return {
    rule,
    versions: [
      {
        id: `${id}:${latestVersion}`,
        rule_id: id,
        version: latestVersion,
        script_json: JSON.stringify({
          phase: "BEFORE",
          script: "if (ctx.arguments()[0] == 'demo') {\n  return mock.returnValue('mocked')\n}\nreturn mock.proceed()",
        }),
        matcher_json: JSON.stringify({ sampleRate: 1 }),
        governance_json: JSON.stringify({ maxHits: 1000 }),
        status: String(rule.latestVersionStatus ?? "ENABLED"),
        risk_level: "LOW",
        script_hash: "demo-script-hash",
        created_by: "平台管理员",
        created_at: stamp,
      },
      {
        id: `${id}:${Math.max(1, latestVersion - 1)}`,
        rule_id: id,
        version: Math.max(1, latestVersion - 1),
        script_json: JSON.stringify({ phase: "BEFORE", script: "return mock.proceed()" }),
        matcher_json: JSON.stringify({ sampleRate: 1 }),
        governance_json: JSON.stringify({ maxHits: 500 }),
        status: "ENABLED",
        risk_level: "LOW",
        script_hash: "demo-previous-script-hash",
        created_by: "研发工程师",
        created_at: "2026-06-16T15:22:00+08:00",
      },
    ],
    targets: [{
      rule_version_id: `${id}:${latestVersion}`,
      protocol: "JAVA_METHOD",
      class_name: "com.example.DemoService",
      method_name: "execute",
      matcher_json: JSON.stringify({
        classId: "com.example.DemoService",
        classLoaderId: "loader-1",
        descriptor: "(Ljava/lang/String;)Ljava/lang/String;",
      }),
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
      instancesTotal: datasets.instances?.length ?? 0,
      injectableInstancesOnline: datasets.instances?.filter((item) => ["ACTIVE", "ONLINE"].includes(String(item.status))).length ?? 0,
      rulesTotal: datasets.rules?.length ?? 0,
      rulesActive: datasets.rules?.filter((item) => item.status === "ENABLED").length ?? 0,
      rolloutsRunning: datasets["operation-plans"]?.filter((item) => item.status === "RUNNING").length ?? 0,
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
      redis: { status: "UP", latencyMs: 3 },
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
  const defaultStatus = resource === "operation-plans" ? "DRAFT" : "ENABLED";
  return {
    id: `${resource.slice(0, 4)}-${crypto.randomUUID().slice(0, 8)}`,
    ...body,
    status: body.status ?? defaultStatus,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    demo: true,
  };
}
