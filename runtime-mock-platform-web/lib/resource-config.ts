import type { LucideIcon } from "lucide-react";
import {
  BookOpenCheck,
  Box,
  Database,
  FileClock,
  Layers3,
  Network,
  PlayCircle,
  Radio,
  Settings,
  SlidersHorizontal,
  Zap,
} from "lucide-react";
import type { PlatformRecord } from "@/lib/api/types";

export type ResourceColumn = {
  key: string;
  label: string;
  kind?: "status" | "date" | "bytes" | "progress" | "mono";
};

export type ResourceField = {
  key: string;
  label: string;
  placeholder?: string;
  required?: boolean;
  defaultValue?: string;
  type?: "text" | "number" | "textarea" | "json" | "select";
  options?: string[];
};

export type ResourceForm = {
  fields: ResourceField[];
  capability: string;
  createLabel?: string;
  buildEndpoint?: (form: Record<string, string>) => string;
  buildPayload?: (form: Record<string, string>) => PlatformRecord;
};

export type ResourceTab = {
  label: string;
  endpoint: string;
  columns: ResourceColumn[];
  form?: ResourceForm;
};

export type ResourceConfig = {
  key: string;
  eyebrow: string;
  title: string;
  singular: string;
  description: string;
  icon: LucideIcon;
  endpoint: string;
  columns: ResourceColumn[];
  form?: ResourceForm;
  tabs?: ResourceTab[];
  createLabel?: string;
  createHref?: string;
  readOnly?: boolean;
};

const text = (key: string, label: string, defaultValue = "", required = true): ResourceField => ({
  key, label, defaultValue, required,
});
const number = (key: string, label: string, defaultValue: string): ResourceField => ({
  key, label, defaultValue, required: true, type: "number",
});
const json = (key: string, label: string, defaultValue = "{}"): ResourceField => ({
  key, label, defaultValue, type: "json",
});
const select = (key: string, label: string, options: string[], defaultValue = options[0]): ResourceField => ({
  key, label, options, defaultValue, required: true, type: "select",
});
const parseJson = (value: string, fallback: unknown = {}) => value.trim() ? JSON.parse(value) : fallback;
const base = (form: Record<string, string>) => ({
  applicationId: form.applicationId || "app-default",
  environmentId: form.environmentId || "env-dev",
});

export const resourceConfigs: Record<string, ResourceConfig> = {
  applications: {
    key: "applications",
    eyebrow: "Topology",
    title: "应用与环境",
    singular: "实例",
    description: "注册并查看接入 Runtime Mock 的应用实例、环境和运行状态。",
    icon: Box,
    endpoint: "instances",
    columns: [
      { key: "applicationId", label: "应用", kind: "mono" },
      { key: "environmentId", label: "环境", kind: "mono" },
      { key: "hostname", label: "主机" },
      { key: "runtime", label: "运行时" },
      { key: "status", label: "状态", kind: "status" },
      { key: "updatedAt", label: "更新时间", kind: "date" },
    ],
    form: {
      capability: "INSTANCE_MANAGE",
      fields: [
        text("applicationId", "应用 ID", "app-default"),
        text("environmentId", "环境 ID", "env-dev"),
        text("hostname", "主机名"),
        text("processId", "进程 ID", "unknown"),
        text("runtime", "运行时", "java-21"),
        json("labels", "标签 JSON", "{}"),
      ],
      buildPayload: (form) => ({ ...base(form), hostname: form.hostname, processId: form.processId, runtime: form.runtime, labels: parseJson(form.labels), reason: "Web 注册实例" }),
    },
  },
  agents: {
    key: "agents",
    eyebrow: "Runtime",
    title: "Agent 管理",
    singular: "Agent",
    description: "观察 Agent 在线状态、版本、心跳、监听地址与实例归属。",
    icon: Network,
    endpoint: "agents",
    columns: [
      { key: "id", label: "Agent", kind: "mono" },
      { key: "instanceId", label: "实例", kind: "mono" },
      { key: "listenHost", label: "监听地址", kind: "mono" },
      { key: "agentVersion", label: "版本", kind: "mono" },
      { key: "status", label: "状态", kind: "status" },
      { key: "lastHeartbeatAt", label: "最后心跳", kind: "date" },
    ],
    readOnly: true,
  },
  rules: {
    key: "rules",
    eyebrow: "Mock & Fault",
    title: "规则中心",
    singular: "规则",
    description: "创建、服务端校验、受控试运行并版本化管理 Runtime Mock 规则。",
    icon: SlidersHorizontal,
    endpoint: "rules",
    columns: [
      { key: "name", label: "规则名称" },
      { key: "applicationId", label: "应用", kind: "mono" },
      { key: "environmentId", label: "环境", kind: "mono" },
      { key: "latestVersion", label: "最新版本", kind: "mono" },
      { key: "status", label: "状态", kind: "status" },
      { key: "updatedAt", label: "更新时间", kind: "date" },
    ],
    createLabel: "创建规则",
    createHref: "/rules/new",
  },
  rollouts: {
    key: "rollouts",
    eyebrow: "Delivery",
    title: "发布与灰度",
    singular: "发布计划",
    description: "通过计划、批次、实例执行和 Agent 命令管理可回滚的规则发布。",
    icon: Zap,
    endpoint: "operation-plans",
    columns: [
      { key: "id", label: "计划 ID", kind: "mono" },
      { key: "resourceId", label: "规则", kind: "mono" },
      { key: "resourceVersion", label: "版本", kind: "mono" },
      { key: "planType", label: "类型" },
      { key: "status", label: "状态", kind: "status" },
      { key: "updatedAt", label: "更新时间", kind: "date" },
    ],
    form: {
      capability: "ROLLOUT_MANAGE",
      fields: [
        text("applicationId", "应用 ID", "app-default"),
        text("environmentId", "环境 ID", "env-dev"),
        select("resourceType", "资源类型", ["rule"]),
        text("resourceId", "规则 ID"),
        number("resourceVersion", "规则版本", "1"),
        json("strategy", "发布策略 JSON", '{"mode":"canary","observeSeconds":60}'),
        json("rollout", "批次与回滚 JSON", '{"mode":"SEQUENTIAL","batchPolicy":{"batchSize":1},"rollbackPolicy":{"automatic":true}}'),
      ],
      buildPayload: (form) => ({ ...base(form), planType: "RULE_ROLLOUT", resourceType: form.resourceType, resourceId: form.resourceId, resourceVersion: Number(form.resourceVersion), strategy: parseJson(form.strategy), rollout: parseJson(form.rollout), reason: "Web 创建发布计划" }),
    },
    tabs: [
      { label: "发布计划", endpoint: "operation-plans", columns: [] },
      {
        label: "灰度批次",
        endpoint: "rollout-batches",
        columns: [
          { key: "id", label: "批次", kind: "mono" },
          { key: "operationPlanId", label: "计划", kind: "mono" },
          { key: "batchOrder", label: "顺序" },
          { key: "status", label: "状态", kind: "status" },
          { key: "updatedAt", label: "更新时间", kind: "date" },
        ],
        form: {
          capability: "ROLLOUT_MANAGE",
          createLabel: "创建灰度批次",
          fields: [text("operationPlanId", "发布计划 ID"), number("batchOrder", "批次顺序", "1"), json("targetSelector", "目标选择器 JSON", '{"labels":{}}')],
          buildEndpoint: (form) => `operation-plans/${form.operationPlanId}/batches`,
          buildPayload: (form) => ({ batchOrder: Number(form.batchOrder), targetSelector: parseJson(form.targetSelector), reason: "Web 创建灰度批次" }),
        },
      },
      {
        label: "实例执行",
        endpoint: "rollout-executions",
        columns: [
          { key: "id", label: "执行 ID", kind: "mono" },
          { key: "rolloutBatchId", label: "批次", kind: "mono" },
          { key: "instanceId", label: "实例", kind: "mono" },
          { key: "commandId", label: "命令", kind: "mono" },
          { key: "status", label: "状态", kind: "status" },
        ],
        form: {
          capability: "ROLLOUT_MANAGE",
          createLabel: "创建实例执行",
          fields: [text("rolloutBatchId", "灰度批次 ID"), text("instanceId", "实例 ID"), text("expectedAgentVersion", "预期 Agent 版本", "unknown"), number("expectedRuleVersion", "预期规则版本", "1")],
          buildEndpoint: (form) => `rollout-batches/${form.rolloutBatchId}/executions`,
          buildPayload: (form) => ({ instanceId: form.instanceId, expectedAgentVersion: form.expectedAgentVersion, expectedRuleVersion: Number(form.expectedRuleVersion), reason: "Web 创建实例执行" }),
        },
      },
    ],
  },
  recordings: {
    key: "recordings",
    eyebrow: "Capture",
    title: "录制管理",
    singular: "录制会话",
    description: "创建受配额和脱敏约束的录制规则与会话，并推进其完整生命周期。",
    icon: Radio,
    endpoint: "recording-sessions",
    columns: [
      { key: "id", label: "会话", kind: "mono" },
      { key: "applicationId", label: "应用", kind: "mono" },
      { key: "environmentId", label: "环境", kind: "mono" },
      { key: "maxEvents", label: "最大事件数" },
      { key: "ttlSeconds", label: "有效期(s)" },
      { key: "status", label: "状态", kind: "status" },
      { key: "updatedAt", label: "更新时间", kind: "date" },
    ],
    form: {
      capability: "RECORD_ARGUMENTS",
      fields: [
        text("applicationId", "应用 ID", "app-default"),
        text("environmentId", "环境 ID", "env-dev"),
        number("maxEvents", "最大事件数", "10000"),
        number("ttlSeconds", "有效期（秒）", "900"),
        json("target", "录制目标 JSON", '{"protocol":"JAVA_METHOD","className":"","methodName":"","methodDescriptor":""}'),
        json("quota", "配额 JSON", '{"maxBytes":1073741824}'),
      ],
      buildPayload: (form) => ({ ...base(form), maxEvents: Number(form.maxEvents), ttlSeconds: Number(form.ttlSeconds), target: parseJson(form.target), quota: parseJson(form.quota), reason: "Web 创建录制会话" }),
    },
    tabs: [
      { label: "录制会话", endpoint: "recording-sessions", columns: [] },
      {
        label: "录制规则",
        endpoint: "recording-rules",
        columns: [
          { key: "name", label: "规则" },
          { key: "applicationId", label: "应用", kind: "mono" },
          { key: "latestVersion", label: "版本" },
          { key: "status", label: "状态", kind: "status" },
          { key: "updatedAt", label: "更新时间", kind: "date" },
        ],
        form: {
          capability: "RECORD_ARGUMENTS",
          createLabel: "创建录制规则",
          fields: [text("name", "规则名称"), text("applicationId", "应用 ID", "app-default"), text("environmentId", "环境 ID", "env-dev"), json("target", "目标 JSON", '{"className":"","methodName":""}'), json("sampling", "采样 JSON", '{"rate":0.001}'), json("quota", "配额 JSON", '{"maxEvents":10000,"maxBytes":1073741824}')],
          buildPayload: (form) => ({ name: form.name, ...base(form), protocol: "JAVA_METHOD", target: parseJson(form.target), sampling: parseJson(form.sampling), quota: parseJson(form.quota), reason: "Web 创建录制规则" }),
        },
      },
      {
        label: "录制批次",
        endpoint: "recording-batches",
        columns: [
          { key: "id", label: "批次", kind: "mono" },
          { key: "recordingSessionId", label: "录制会话", kind: "mono" },
          { key: "eventCount", label: "事件数" },
          { key: "bytesCount", label: "数据量", kind: "bytes" },
          { key: "status", label: "状态", kind: "status" },
          { key: "sealedAt", label: "封存时间", kind: "date" },
        ],
      },
      {
        label: "事件索引",
        endpoint: "recording-events",
        columns: [
          { key: "id", label: "事件", kind: "mono" },
          { key: "recordingSessionId", label: "录制会话", kind: "mono" },
          { key: "traceId", label: "Trace", kind: "mono" },
          { key: "protocol", label: "协议" },
          { key: "eventTime", label: "事件时间", kind: "date" },
        ],
      },
    ],
  },
  datasets: {
    key: "datasets",
    eyebrow: "Data",
    title: "数据集与数据源",
    singular: "数据集版本",
    description: "管理脱敏、哈希校验和版本不可变的数据集，以及受控数据源注册。",
    icon: Database,
    endpoint: "datasets",
    columns: [
      { key: "datasetId", label: "数据集", kind: "mono" },
      { key: "version", label: "版本" },
      { key: "sourceSessionId", label: "来源会话", kind: "mono" },
      { key: "retentionPolicy", label: "保留策略" },
      { key: "createdBy", label: "创建者" },
      { key: "createdAt", label: "创建时间", kind: "date" },
    ],
    form: {
      capability: "IMPORT_TO_TEST",
      fields: [text("name", "数据集名称"), text("datasetId", "数据集 ID"), text("sourceSessionId", "已完成录制会话 ID"), text("schemaHash", "Schema 哈希（留空自动生成）", "", false), text("manifestHash", "Manifest 哈希（留空自动生成）", "", false), text("maskingHash", "脱敏策略哈希（留空使用平台策略）", "", false), text("applicationId", "应用 ID", "app-default"), text("environmentId", "环境 ID", "env-dev")],
      buildPayload: (form) => ({ name: form.name, datasetId: form.datasetId, sourceSessionId: form.sourceSessionId, ...(form.schemaHash ? { schemaHash: form.schemaHash } : {}), ...(form.manifestHash ? { manifestHash: form.manifestHash } : {}), ...(form.maskingHash ? { maskingHash: form.maskingHash } : {}), ...base(form), retentionPolicy: "P30D", reason: "Web 创建数据集版本" }),
    },
    tabs: [
      { label: "数据集版本", endpoint: "datasets", columns: [] },
      {
        label: "数据源",
        endpoint: "datasources",
        columns: [
          { key: "name", label: "数据源" },
          { key: "datasourceType", label: "类型" },
          { key: "applicationId", label: "应用", kind: "mono" },
          { key: "status", label: "状态", kind: "status" },
          { key: "updatedAt", label: "更新时间", kind: "date" },
        ],
        form: {
          capability: "DATA_EXTRACT",
          createLabel: "注册数据源",
          fields: [text("name", "数据源名称"), select("datasourceType", "数据源类型", ["POSTGRESQL", "MYSQL", "TEST_FIXTURE"]), text("applicationId", "应用 ID", "app-default"), text("environmentId", "环境 ID", "env-dev"), json("config", "连接配置 JSON", "{}"), text("secretRef", "凭据引用", "", false)],
          buildPayload: (form) => ({ name: form.name, datasourceType: form.datasourceType, ...base(form), config: parseJson(form.config), ...(form.secretRef ? { credential: { provider: "LOCAL_ENV", secretRef: form.secretRef } } : {}), reason: "Web 注册数据源" }),
        },
      },
    ],
  },
  extractions: {
    key: "extractions",
    eyebrow: "Pipeline",
    title: "数据提取",
    singular: "提取任务",
    description: "通过数据源、模板、任务和 Worker 产物构建可回放的数据集。",
    icon: Layers3,
    endpoint: "extraction-tasks",
    columns: [
      { key: "id", label: "任务", kind: "mono" },
      { key: "templateId", label: "模板", kind: "mono" },
      { key: "templateVersion", label: "模板版本" },
      { key: "datasetId", label: "数据集", kind: "mono" },
      { key: "status", label: "状态", kind: "status" },
      { key: "updatedAt", label: "更新时间", kind: "date" },
    ],
    form: {
      capability: "DATA_EXTRACT",
      fields: [text("templateId", "模板 ID"), number("templateVersion", "模板版本", "1"), text("datasetId", "输出数据集 ID", "", false), json("parameters", "参数 JSON", "{}"), json("quota", "配额 JSON", '{"maxRows":10000,"maxBytes":104857600,"timeoutSeconds":5}')],
      buildPayload: (form) => ({ templateId: form.templateId, templateVersion: Number(form.templateVersion), ...(form.datasetId ? { datasetId: form.datasetId } : {}), parameters: parseJson(form.parameters), quota: parseJson(form.quota), reason: "Web 创建提取任务" }),
    },
    tabs: [
      { label: "任务", endpoint: "extraction-tasks", columns: [] },
      {
        label: "模板",
        endpoint: "extraction-templates",
        columns: [{ key: "name", label: "模板" }, { key: "datasourceId", label: "数据源", kind: "mono" }, { key: "latestVersion", label: "版本" }, { key: "status", label: "状态", kind: "status" }, { key: "updatedAt", label: "更新时间", kind: "date" }],
        form: {
          capability: "DATA_EXTRACT",
          createLabel: "创建提取模板",
          fields: [text("name", "模板名称"), text("datasourceId", "数据源 ID"), text("rootTable", "根表"), json("template", "模板 JSON", '{"columns":[]}'), json("quota", "配额 JSON", '{"maxRows":10000,"timeoutSeconds":5}')],
          buildPayload: (form) => ({ name: form.name, datasourceId: form.datasourceId, rootTable: form.rootTable, template: parseJson(form.template), quota: parseJson(form.quota), reason: "Web 创建提取模板" }),
        },
      },
      { label: "执行", endpoint: "extraction-executions", columns: [{ key: "id", label: "执行 ID", kind: "mono" }, { key: "extractionTaskId", label: "任务", kind: "mono" }, { key: "status", label: "状态", kind: "status" }, { key: "startedAt", label: "开始", kind: "date" }, { key: "finishedAt", label: "结束", kind: "date" }] },
      { label: "结果", endpoint: "extraction-results", columns: [{ key: "id", label: "结果 ID", kind: "mono" }, { key: "extractionTaskId", label: "任务", kind: "mono" }, { key: "datasetVersionId", label: "数据集版本", kind: "mono" }, { key: "rowCount", label: "行数" }, { key: "bytesCount", label: "大小", kind: "bytes" }, { key: "createdAt", label: "时间", kind: "date" }] },
    ],
  },
  replays: {
    key: "replays",
    eyebrow: "Replay",
    title: "流量回放",
    singular: "回放计划",
    description: "创建回放计划和执行，查看批次、调用结果及自动比较差异。",
    icon: PlayCircle,
    endpoint: "replay-plans",
    columns: [
      { key: "id", label: "计划", kind: "mono" },
      { key: "datasetId", label: "数据集", kind: "mono" },
      { key: "datasetVersion", label: "版本" },
      { key: "targetApplication", label: "目标应用" },
      { key: "targetEnvironment", label: "目标环境" },
      { key: "status", label: "状态", kind: "status" },
    ],
    form: {
      capability: "IMPORT_TO_TEST",
      fields: [text("datasetId", "数据集 ID"), number("datasetVersion", "数据集版本", "1"), text("targetApplication", "目标应用"), text("targetEnvironment", "目标环境", "test"), text("sideEffectPolicyHash", "副作用策略哈希"), text("comparisonPolicyHash", "比较策略哈希"), json("executionPolicy", "执行策略 JSON", '{"qps":1,"concurrency":1}')],
      buildPayload: (form) => ({ datasetId: form.datasetId, datasetVersion: Number(form.datasetVersion), targetApplication: form.targetApplication, targetEnvironment: form.targetEnvironment, sideEffectPolicyHash: form.sideEffectPolicyHash, comparisonPolicyHash: form.comparisonPolicyHash, executionPolicy: parseJson(form.executionPolicy), reason: "Web 创建回放计划" }),
    },
    tabs: [
      { label: "计划", endpoint: "replay-plans", columns: [] },
      {
        label: "执行",
        endpoint: "replay-executions",
        columns: [{ key: "id", label: "执行 ID", kind: "mono" }, { key: "replayPlanId", label: "计划", kind: "mono" }, { key: "status", label: "状态", kind: "status" }, { key: "updatedAt", label: "更新时间", kind: "date" }],
        form: {
          capability: "REPLAY_EXECUTE",
          createLabel: "创建回放执行",
          fields: [text("replayPlanId", "回放计划 ID"), json("executorConfig", "执行配置 JSON", '{"qps":1,"concurrency":1}')],
          buildPayload: (form) => ({ replayPlanId: form.replayPlanId, executorConfig: parseJson(form.executorConfig), reason: "Web 创建回放执行" }),
        },
      },
      { label: "批次", endpoint: "replay-batches", columns: [{ key: "id", label: "批次 ID", kind: "mono" }, { key: "replayExecutionId", label: "执行", kind: "mono" }, { key: "batchOrder", label: "顺序" }, { key: "status", label: "状态", kind: "status" }] },
      { label: "调用结果", endpoint: "replay-invocation-results", columns: [{ key: "id", label: "调用", kind: "mono" }, { key: "replayBatchId", label: "批次", kind: "mono" }, { key: "durationMillis", label: "耗时(ms)" }, { key: "status", label: "状态", kind: "status" }, { key: "createdAt", label: "时间", kind: "date" }] },
      { label: "差异", endpoint: "comparison-results", columns: [{ key: "replayInvocationResultId", label: "调用", kind: "mono" }, { key: "status", label: "状态", kind: "status" }, { key: "diffJson", label: "差异详情" }, { key: "createdAt", label: "时间", kind: "date" }] },
    ],
  },
  approvals: {
    key: "approvals",
    eyebrow: "Governance",
    title: "审批中心",
    singular: "审批",
    description: "处理高风险操作申请，决策与资源版本、操作者和审计记录绑定。",
    icon: BookOpenCheck,
    endpoint: "approvals",
    columns: [
      { key: "subjectType", label: "事项类型" },
      { key: "subjectId", label: "事项 ID", kind: "mono" },
      { key: "subjectVersion", label: "版本" },
      { key: "requester", label: "申请人" },
      { key: "status", label: "状态", kind: "status" },
      { key: "createdAt", label: "申请时间", kind: "date" },
    ],
    form: {
      capability: "APPROVE",
      createLabel: "发起审批",
      fields: [
        select("subjectType", "事项类型", ["OPERATION_PLAN", "RECORDING_SESSION", "REPLAY_PLAN", "REPLAY_EXECUTION", "EXTRACTION_TASK", "RULE", "RECORDING_RULE", "DATASET_VERSION"]),
        text("subjectId", "事项 ID"),
        number("subjectVersion", "事项版本", "1"),
        text("reason", "申请原因"),
        text("approvers", "审批人（逗号分隔）", "reviewer"),
      ],
      buildPayload: (form) => ({ subjectType: form.subjectType, subjectId: form.subjectId, subjectVersion: Number(form.subjectVersion), reason: form.reason, approvers: form.approvers.split(",").map((item) => item.trim()).filter(Boolean) }),
    },
  },
  audits: {
    key: "audits",
    eyebrow: "Observability",
    title: "审计与事件",
    singular: "审计记录",
    description: "追踪操作者、资源版本、结果、关联请求、Outbox 和加密 Worker 产物。",
    icon: FileClock,
    endpoint: "audits",
    columns: [
      { key: "actor", label: "操作者" },
      { key: "action", label: "动作", kind: "mono" },
      { key: "resourceType", label: "资源类型" },
      { key: "resourceId", label: "资源 ID", kind: "mono" },
      { key: "result", label: "结果", kind: "status" },
      { key: "occurredAt", label: "时间", kind: "date" },
    ],
    readOnly: true,
    tabs: [
      { label: "审计记录", endpoint: "audits", columns: [] },
      { label: "Outbox 事件", endpoint: "outbox", columns: [{ key: "id", label: "事件 ID", kind: "mono" }, { key: "aggregateType", label: "聚合" }, { key: "eventType", label: "事件" }, { key: "attempts", label: "尝试次数" }, { key: "status", label: "状态", kind: "status" }, { key: "createdAt", label: "时间", kind: "date" }] },
      { label: "Worker 产物", endpoint: "worker-artifacts", columns: [{ key: "id", label: "产物 ID", kind: "mono" }, { key: "workerType", label: "Worker" }, { key: "artifactType", label: "类型" }, { key: "objectUri", label: "对象 URI", kind: "mono" }, { key: "bytesCount", label: "大小", kind: "bytes" }, { key: "createdAt", label: "时间", kind: "date" }] },
    ],
  },
  settings: {
    key: "settings",
    eyebrow: "Administration",
    title: "平台设置",
    singular: "访问 Token",
    description: "管理本地身份 Token；明文 Token 只在签发成功时显示一次。",
    icon: Settings,
    endpoint: "tokens",
    columns: [
      { key: "displayName", label: "Token 名称" },
      { key: "subjectType", label: "主体类型" },
      { key: "subjectId", label: "主体" },
      { key: "status", label: "状态", kind: "status" },
      { key: "expiresAt", label: "过期时间", kind: "date" },
      { key: "lastUsedAt", label: "最后使用", kind: "date" },
    ],
    form: {
      capability: "ADMIN",
      createLabel: "签发 Token",
      fields: [text("displayName", "Token 名称"), select("subjectType", "主体类型", ["USER", "AGENT"], "USER"), text("subjectId", "主体 ID", "system"), number("ttlSeconds", "有效期（秒）", "86400")],
      buildEndpoint: () => "auth/tokens",
      buildPayload: (form) => ({ displayName: form.displayName, subjectType: form.subjectType, subjectId: form.subjectId, ttlSeconds: Number(form.ttlSeconds) }),
    },
    createLabel: "签发 Token",
  },
};
