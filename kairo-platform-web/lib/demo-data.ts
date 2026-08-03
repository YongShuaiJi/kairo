import type { PlatformRecord, ScriptTestResult, ScriptValidationResult } from "@/lib/api/types";
import type {
  BytecodeDiffResult,
  BytecodeSnapshotKind,
  CaptureResponse,
  PreviewResponse,
  TransformationsResponse,
} from "@/lib/api/bytecode";
import { decodeClassId } from "@/lib/bytecode/class-id";

const stamp = "2026-06-18T09:30:00+08:00";

const datasets: Record<string, PlatformRecord[]> = {
  agents: [
    { id: "agt-01", name: "kairo-demo", application: "kairo-demo", environment: "sit", status: "ONLINE", version: "1.7.0", host: "demo", lastHeartbeatAt: stamp },
    { id: "agt-02", name: "kairo-attach-executor", application: "kairo-demo", environment: "sit", status: "ONLINE", version: "1.7.0", host: "attach-executor", lastHeartbeatAt: stamp },
  ],
  instances: [
    { id: "ins-01", nickname: "kairo-demo", application: "kairo-demo", environment: "sit", host: "demo", status: "ACTIVE", agentStatus: "ONLINE", loadMode: "attach", javaVersion: "21.0.11", lastSeenAt: stamp },
  ],
  rules: [
    { id: "rule-01", name: "sit 下单接口故障注入试用规则", applicationName: "kairo-demo", environmentName: "sit", targetMethod: "com.example.demo.OrderService#createOrder", versionCount: 3, enabledVersionCount: 2, disabledVersionCount: 1, onlineVersion: 1, latestVersion: 3, latestVersionStatus: "ENABLED", status: "ENABLED", updatedAt: stamp },
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
    { id: "rex-01", operationPlanId: "op-01", instanceNickname: "kairo-demo", applicationName: "kairo-demo", environmentName: "sit", javaVersion: "21.0.11", loadMode: "attach", commandId: "cmd-01", status: "SUCCEEDED", createdAt: stamp },
  ],
  "rollback-executions": [
    { id: "unload-01", operationPlanId: "op-02", rollbackType: "RESET_CLASS", status: "SUCCEEDED", reason: "规则版本停用自动卸载", finishedAt: "2026-06-18T08:33:00+08:00" },
  ],
  audits: [
    { id: "audit-01", actor: "平台管理员", action: "RULE_VERSION_ENABLED", resource: "sit 下单接口故障注入试用规则 v1", result: "SUCCESS", correlationId: "req-8cf2d1", createdAt: stamp },
    { id: "audit-02", actor: "系统", action: "RULE_VERSION_DISABLED", resource: "sit 下单接口故障注入试用规则 v2", result: "SUCCESS", correlationId: "req-1fd920", createdAt: "2026-06-18T08:32:00+08:00" },
  ],
  "auth/tokens": [
    { id: "token-01", subjectId: "kairo-ci", roles: ["ADMIN"], status: "VALID", expiresAt: "2026-09-18T00:00:00+08:00" },
  ],
  "auth/users": [
    { id: "user-system", username: "demo-admin", displayName: "演示管理员", status: "ACTIVE", superAdmin: true, activeTokenCount: 1, createdAt: stamp },
    { id: "user-operator", username: "demo-operator", displayName: "演示业务用户", status: "ACTIVE", superAdmin: false, activeTokenCount: 1, createdAt: stamp },
  ],
  tokens: [
    { id: "token-01", subjectId: "system", subjectType: "USER", status: "VALID", expiresAt: "2026-09-18T00:00:00+08:00" },
  ],
  "script-sessions": [
    {
      sessionId: "ss-01",
      agentId: "agt-01",
      applicationId: "kairo-demo",
      target: { className: "com.example.demo.OrderService", classLoaderId: "loader-01", methodName: "createOrder", methodDescriptor: "(Lcom/example/demo/CreateOrderRequest;)Lcom/example/demo/Order;" },
      scriptHash: "demo-hash-ss-01",
      requestedProfile: "EXTENDED",
      effectiveProfile: "SAFE",
      platformMaxProfile: "UNRESTRICTED",
      applicationMaxProfile: "SAFE",
      policyRevision: { revision: 2, hash: "demo-policy-hash" },
      ttlMillis: 60000,
      maxHits: 10,
      status: "APPLIED",
      hitCount: 3,
      version: 3,
      requestedBy: "demo-admin",
      formalRuleId: null,
      createdAt: Date.now() - 120_000,
      expiresAt: Date.now() + 30_000,
      appliedAt: Date.now() - 90_000,
      revertedAt: null,
      updatedAt: Date.now() - 5_000,
      diagnostics: [],
    },
    {
      sessionId: "ss-02",
      agentId: "agt-01",
      applicationId: "kairo-demo",
      target: { className: "com.example.demo.OrderService", classLoaderId: "loader-01", methodName: "calculateScore", methodDescriptor: "(I)I" },
      scriptHash: "demo-hash-ss-02",
      requestedProfile: "SAFE",
      effectiveProfile: "SAFE",
      platformMaxProfile: "UNRESTRICTED",
      applicationMaxProfile: "SAFE",
      policyRevision: { revision: 2, hash: "demo-policy-hash" },
      ttlMillis: 60000,
      maxHits: 5,
      status: "EXPIRED",
      hitCount: 5,
      version: 4,
      requestedBy: "demo-operator",
      formalRuleId: null,
      createdAt: Date.now() - 300_000,
      expiresAt: Date.now() - 240_000,
      appliedAt: Date.now() - 290_000,
      revertedAt: Date.now() - 240_000,
      updatedAt: Date.now() - 240_000,
      diagnostics: [],
    },
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

/**
 * V1.5 §4.1/§5: demo ClassLoader tree for the Web class selector when no live agent is online.
 * Mirrors the shape of the agent's LIST_LOADERS response: a bootstrap loader, the system/loader
 * chain and a Spring Boot embedded-Tomcat loader, with a parent→children tree.
 */
export function demoLoaders() {
  const bootstrap = { loaderId: "bootstrap", loaderClassName: "bootstrap", parentLoaderId: null };
  const system = { loaderId: "system-loader-id", loaderClassName: "jdk.internal.loader.ClassLoaders$AppClassLoader", parentLoaderId: "bootstrap" };
  const springBoot = { loaderId: "spring-boot-loader-id", loaderClassName: "org.springframework.boot.loader.launch.LaunchedURLClassLoader", parentLoaderId: "system-loader-id", frameworkLoader: "Spring Boot (LaunchedURLClassLoader)" };
  const tomcat = { loaderId: "tomcat-loader-id", loaderClassName: "org.springframework.boot.web.embedded.tomcat.TomcatEmbeddedWebappClassLoader", parentLoaderId: "spring-boot-loader-id", frameworkLoader: "Spring Boot embedded Tomcat" };
  const loaders = [bootstrap, system, springBoot, tomcat];
  return {
    loaders,
    tree: {
      bootstrap: [system],
      "system-loader-id": [springBoot],
      "spring-boot-loader-id": [tomcat],
    },
    count: loaders.length,
    bootstrapLoaderId: "bootstrap",
    agentAvailable: false,
  };
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

// -------------------------------------------------------- V1.2 script sessions

export function demoScriptSessions(applicationId?: string): PlatformRecord[] {
  const rows = datasets["script-sessions"] ?? [];
  return applicationId ? rows.filter((row) => row.applicationId === applicationId) : rows;
}

export function demoScriptSession(id: string): PlatformRecord | undefined {
  return (datasets["script-sessions"] ?? []).find((row) => String(row.sessionId) === id);
}

export function demoScriptSessionEvents(id: string): PlatformRecord[] {
  const session = demoScriptSession(id);
  if (!session) return [];
  const status = String(session.status);
  const events: PlatformRecord[] = [
    { id: `${id}-ev-1`, sessionId: id, action: "script.session.create", fromStatus: null, toStatus: "CREATED", actor: String(session.requestedBy), detail: "Created session profile=SAFE ttl=60000ms", createdAt: new Date(Number(session.createdAt)).toISOString() },
  ];
  if (status === "VALIDATED" || status === "APPLIED" || status === "EXPIRED" || status === "REVERTED") {
    events.push({ id: `${id}-ev-2`, sessionId: id, action: "script.session.validate", fromStatus: "CREATED", toStatus: "VALIDATED", actor: String(session.requestedBy), detail: "Validated script", createdAt: new Date(Number(session.createdAt) + 5_000).toISOString() });
  }
  if (status === "APPLIED" || status === "EXPIRED" || status === "REVERTED") {
    events.push({ id: `${id}-ev-3`, sessionId: id, action: "script.session.apply", fromStatus: "VALIDATED", toStatus: "APPLIED", actor: String(session.requestedBy), detail: "Applied trial rule", createdAt: new Date(Number(session.appliedAt ?? session.createdAt)).toISOString() });
  }
  if (status === "EXPIRED") {
    events.push({ id: `${id}-ev-4`, sessionId: id, action: "script.session.expire", fromStatus: "APPLIED", toStatus: "EXPIRED", actor: "ttl-cleanup", detail: "Session expired (TTL elapsed)", createdAt: new Date(Number(session.expiresAt)).toISOString() });
  }
  if (status === "REVERTED") {
    events.push({ id: `${id}-ev-4`, sessionId: id, action: "script.session.revert", fromStatus: "APPLIED", toStatus: "REVERTED", actor: String(session.requestedBy), detail: "Reverted session", createdAt: new Date(Number(session.revertedAt ?? session.updatedAt)).toISOString() });
  }
  return events;
}

export function demoScriptPolicy(applicationId: string): PlatformRecord {
  return {
    applicationId,
    platformMaxProfile: "UNRESTRICTED",
    applicationMaxProfile: "SAFE",
    effectiveMaxProfile: "SAFE",
    hasApplicationPolicy: true,
    revision: 2,
    policyHash: "demo-policy-hash-abcdef",
    modifiedBy: "demo-admin",
    updatedAt: stamp,
  };
}

export function demoScriptCompile(body: PlatformRecord): PlatformRecord {
  const script = String(body.script ?? "");
  const profile = String(body.capabilityProfile ?? "SAFE");
  const targetClassLoaderId = String(body.targetClassLoaderId ?? "bootstrap");
  const forbidden = script.includes("java.io.File") || script.includes("System.exit") || script.includes("Runtime.getRuntime");
  const syntaxError = script.includes("return mock.proceed(") && !script.includes(")");
  if (forbidden) {
    return {
      successful: false,
      scriptHash: "demo-compile-hash",
      capabilityProfile: profile,
      policyRevision: { revision: 2, hash: "demo-policy-hash" },
      compilerVersion: "groovy-4.0.24",
      targetClassLoaderId,
      diagnostics: [{ phase: "COMPILATION", severity: "ERROR", line: 1, column: 1, code: "FORBIDDEN_SCRIPT", message: "脚本使用了 SAFE 档位禁止的 API", targetClassLoaderId, suggestion: "改用 EXTENDED/UNRESTRICTED 档位或移除敏感调用" }],
    };
  }
  if (syntaxError) {
    return {
      successful: false,
      scriptHash: "demo-compile-hash",
      capabilityProfile: profile,
      policyRevision: { revision: 2, hash: "demo-policy-hash" },
      compilerVersion: "groovy-4.0.24",
      targetClassLoaderId,
      diagnostics: [{ phase: "COMPILATION", severity: "ERROR", line: 1, column: 19, code: "SCRIPT_COMPILE_ERROR", message: "意外的脚本结束，缺少 ')'", targetClassLoaderId, suggestion: "补全括号后重试" }],
    };
  }
  return {
    successful: true,
    scriptHash: "demo-compile-hash",
    capabilityProfile: profile,
    policyRevision: { revision: 2, hash: "demo-policy-hash" },
    compilerVersion: "groovy-4.0.24",
    targetClassLoaderId,
    diagnostics: [],
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
  return { valid: !diagnostics.some((item) => item.severity === "error"), diagnostics, compileTimeMs: 31, policy: "kairo-safe-groovy-v1" };
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
    output: { code: 200, message: "mocked by Kairo", data: { orderId: "RM-20260618-001" } },
    logs: ["编译缓存命中", "沙箱执行完成", "返回值已通过序列化检查"],
    diff: { before: null, after: { message: "mocked by Kairo" } },
  };
}

/**
 * V1.6 §5.3 demo-mode canonical rule preview. Mirrors the server
 * {@code POST /api/v1/rules/preview} so the workbench is fully exercisable offline:
 * the platform (here the demo) owns the business defaults and returns the canonical
 * payload + preview token/revision + impact/risk/revert + script validation.
 */
export function demoRulePreview(body: PlatformRecord) {
  const phase = String(body.executionPhase ?? "BEFORE");
  const script = String(body.script ?? "");
  const source = script.toLowerCase();
  const riskLevel = source.includes("system.exit") || source.includes("runtime.exec")
    ? "HIGH"
    : phase === "THROWS" || source.includes("throwexception") ? "MEDIUM" : "LOW";
  const className = String(body.className ?? "com.example.Service");
  const methodName = String(body.methodName ?? "query");
  const payload = {
    name: String(body.name ?? "demo-rule"),
    applicationId: String(body.applicationId ?? "app-default"),
    environmentId: String(body.environmentId ?? "env-dev"),
    status: "ENABLED",
    versionStatus: "ENABLED",
    riskLevel,
    script: { phase, script },
    matcher: { phase },
    targets: [{
      protocol: "JAVA_METHOD",
      className,
      methodName,
      matcher: {
        classId: String(body.classId ?? ""),
        classLoaderId: String(body.classLoaderId ?? "bootstrap"),
        descriptor: String(body.methodDescriptor ?? "()V"),
      },
    }],
    capabilities: ["RETURN_VALUE", "THROW_EXCEPTION"],
    reason: body.reason ?? undefined,
  };
  return {
    payload,
    previewToken: `rule-prev-demo-${crypto.randomUUID().slice(0, 12)}`,
    revision: Date.now(),
    riskLevel,
    impact: {
      affectedResources: [{ resourceType: "rule-target", resourceId: String(body.classId ?? `${className}#${methodName}`) }],
      scope: `app:${payload.applicationId}`,
      blastRadius: "single-instance",
      reversible: true,
      estimatedAffectedInstances: 1,
    },
    validation: demoValidate({ script } as PlatformRecord),
    revert: {
      strategy: "DISABLE_RULE_VERSION",
      description: "停用规则版本后，系统在保留期结束后自动删除；已发布的版本可通过发布管理卸载立即生效回滚。",
      steps: [
        "POST /api/v1/rules/{id}/versions/{version}/disable",
        "POST /api/v1/operation-plans/{id}/unload (已发布版本的立即回滚)",
        "保留期结束后自动删除（当前为 30 天）",
      ],
    },
  };
}

export function demoMutation(path: string, body: PlatformRecord) {
  const normalized = normalize(path);
  if (normalized === "auth/tokens" || normalized.endsWith("/token/replace")) {
    const username = String(body.username ?? normalized.split("/")[2] ?? "demo-user");
    return {
      id: `token-${crypto.randomUUID().slice(0, 8)}`,
      token: `demo-token-${crypto.randomUUID()}`,
      subjectType: "USER",
      subjectId: username,
      displayName: String(body.displayName ?? username),
      status: "VALID",
      expiresAt: body.expiresAt ?? null,
      demo: true,
    };
  }
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

// ---- V1.1 bytecode diagnostics (demo) ----
// Mirrors the five platform-proxied agent routes under
// /api/v1/agents/{agentId}/classes/{classId}/... so the page is fully exercisable
// in demo mode. The browser still goes through the same-origin BFF; no agent URL or
// X-Agent-Token is ever exposed. Decompilation is honestly UNAVAILABLE (the agent
// does not expose source on the wire in this phase), so the UI shows the notice and
// retains the structured bytecode diff.

const BYTECODE_DEMO_MILLIS = Date.parse(stamp);
const BYTECODE_DEMO_IDENTITY = {
  binaryClassName: "com.example.demo.OrderService",
  classLoaderId: "app-loader-7f3a",
};

function demoIdentity(classId: string) {
  const decoded = decodeClassId(classId);
  if (decoded) return decoded;
  return BYTECODE_DEMO_IDENTITY;
}

const UNAVAILABLE_DECOMPILATION = {
  status: "UNAVAILABLE" as const,
  decompilerName: "UnavailableBytecodeDecompiler",
  sourceCode: null,
  diagnostics: ["Agent 未配置反编译器，无法产出反编译源码"],
  durationMillis: 0,
};

export function demoBytecodeTransformations(agentId: string, classId: string): TransformationsResponse {
  const identity = demoIdentity(classId);
  return {
    classIdentity: identity,
    currentRevision: { value: 2 },
    count: 2,
    history: [
      {
        classIdentity: identity,
        revision: { value: 2 },
        status: "SUCCEEDED",
        inputHash: "sha256-input-r2-9c4f",
        outputHash: "sha256-applied-r2-2b71",
        diagnostics: [],
        attemptedAtMillis: BYTECODE_DEMO_MILLIS,
        durationMillis: 47,
      },
      {
        classIdentity: identity,
        revision: { value: 1 },
        status: "SUCCEEDED",
        inputHash: "sha256-input-r1-aa01",
        outputHash: "sha256-applied-r1-10fe",
        diagnostics: [],
        attemptedAtMillis: BYTECODE_DEMO_MILLIS - 3_600_000,
        durationMillis: 33,
      },
    ],
  };
}

/** Deterministic fake {@code .class} bytes (CAFEBABE magic + padding) for demo only. */
export function demoBytecodeBytes(kind: BytecodeSnapshotKind, revision: number): Uint8Array {
  const base = [0xca, 0xfe, 0xba, 0xbe, 0x00, 0x00, 0x00, 0x34];
  const length = Math.max(16, 248 + revision * 8 + (kind === "APPLIED" ? 12 : 0));
  const bytes = new Uint8Array(length);
  for (let i = 0; i < base.length && i < length; i += 1) bytes[i] = base[i];
  for (let i = base.length; i < length; i += 1) bytes[i] = (i * 31 + revision) & 0xff;
  return bytes;
}

export function demoBytecodePreview(agentId: string, classId: string): PreviewResponse {
  const identity = demoIdentity(classId);
  return {
    classIdentity: identity,
    revision: { value: 2 },
    inputHash: "sha256-input-r2-9c4f",
    plannedHash: "sha256-planned-r2-5d8e",
    plannedSizeBytes: 264,
    targetMethodCount: 2,
    adviceTypes: ["MethodDelegation", "MemberSubstitution"],
    diagnostics: [],
    changed: true,
    decompilation: UNAVAILABLE_DECOMPILATION,
  };
}

export function demoBytecodeCapture(agentId: string, classId: string): CaptureResponse {
  const identity = demoIdentity(classId);
  return {
    classIdentity: identity,
    revision: { value: 2 },
    appliedHash: "sha256-applied-r2-2b71",
    sizeBytes: 272,
    diagnostics: [],
    capturedAtMillis: Date.now(),
    captured: true,
    decompilation: UNAVAILABLE_DECOMPILATION,
  };
}

export function demoBytecodeDiff(
  classId: string,
  fromKind: BytecodeSnapshotKind,
  fromRevision: number,
  toKind: BytecodeSnapshotKind,
  toRevision: number,
): BytecodeDiffResult {
  const identity = demoIdentity(classId);
  return {
    classIdentity: identity,
    fromRevision: { value: fromRevision },
    toRevision: { value: toRevision },
    fromKind,
    toKind,
    fromHash: `sha256-${fromKind.toLowerCase()}-r${fromRevision}`,
    toHash: `sha256-${toKind.toLowerCase()}-r${toRevision}`,
    identical: false,
    normalized: true,
    methodDiffs: [
      {
        methodName: "createOrder",
        methodDescriptor: "(Ljava/lang/String;)Lcom/example/demo/Order;",
        changeType: "MODIFIED",
        instructionDiffs: [
          "- 12: invokevirtual #24 (MockRule.apply)",
          "+ 12: invokevirtual #28 (MockRule.applyReturnValue)",
          "+ 15: areturn",
        ],
        attributeDiffs: [],
      },
      {
        methodName: "cancelOrder",
        methodDescriptor: "(Ljava/lang/String;)V",
        changeType: "ADDED",
        instructionDiffs: ["+ 0: aload_1", "+ 1: invokestatic #30 (Audit.record)"],
        attributeDiffs: [],
      },
    ],
    structuralDiffs: [],
    summary: "1 个方法被修改，1 个方法新增",
    fromDecompilation: UNAVAILABLE_DECOMPILATION,
    toDecompilation: UNAVAILABLE_DECOMPILATION,
  };
}
