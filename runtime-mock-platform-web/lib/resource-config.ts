import type { LucideIcon } from "lucide-react";
import {
  Box,
  Network,
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
  type?: "text" | "number" | "textarea" | "json" | "select" | "resource" | "target" | "hidden";
  options?: string[];
  source?:
    | "applications"
    | "environments"
    | "rules"
    | "rule-versions";
  dependsOn?: string[];
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
const select = (key: string, label: string, options: string[], defaultValue = options[0]): ResourceField => ({
  key, label, options, defaultValue, required: true, type: "select",
});
const resource = (
  key: string,
  label: string,
  source: NonNullable<ResourceField["source"]>,
  dependsOn: string[] = [],
): ResourceField => ({
  key, label, source, dependsOn, required: true, type: "resource",
});
const base = (form: Record<string, string>) => ({
  applicationId: form.applicationId,
  environmentId: form.environmentId,
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
      { key: "projectName", label: "项目" },
      { key: "applicationName", label: "应用" },
      { key: "environmentName", label: "环境" },
      { key: "hostname", label: "主机" },
      { key: "runtime", label: "运行时" },
      { key: "status", label: "状态", kind: "status" },
      { key: "updatedAt", label: "更新时间", kind: "date" },
    ],
    readOnly: true,
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
    title: "发布管理",
    singular: "发布计划",
    description: "将规则发布到目标环境的在线实例，并支持一键卸载、恢复原始字节码。",
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
        resource("applicationId", "应用", "applications"),
        resource("environmentId", "环境", "environments", ["applicationId"]),
        { key: "resourceType", label: "资源类型", defaultValue: "rule", type: "hidden" },
        resource("resourceId", "规则", "rules", ["applicationId", "environmentId"]),
        resource("resourceVersion", "规则版本", "rule-versions", ["resourceId"]),
        { key: "targetMode", label: "目标范围", defaultValue: "ALL_ACTIVE_INSTANCES", type: "hidden" },
        select("automaticRollback", "失败时自动回滚", ["true", "false"], "true"),
      ],
      buildPayload: (form) => ({
        ...base(form),
        planType: "RULE_ROLLOUT",
        resourceType: form.resourceType,
        resourceId: form.resourceId,
        resourceVersion: Number(form.resourceVersion),
        strategy: {
          targetMode: form.targetMode,
          automaticRollback: form.automaticRollback === "true",
        },
        reason: "Web 创建发布计划",
      }),
    },
    tabs: [
      { label: "发布计划", endpoint: "operation-plans", columns: [] },
      {
        label: "实例执行",
        endpoint: "rollout-executions",
        columns: [
          { key: "id", label: "执行 ID", kind: "mono" },
          { key: "operationPlanId", label: "发布计划", kind: "mono" },
          { key: "instanceId", label: "实例", kind: "mono" },
          { key: "commandId", label: "命令", kind: "mono" },
          { key: "status", label: "状态", kind: "status" },
        ],
      },
      {
        label: "卸载记录",
        endpoint: "rollback-executions",
        columns: [
          { key: "id", label: "卸载执行 ID", kind: "mono" },
          { key: "operationPlanId", label: "发布计划", kind: "mono" },
          { key: "rollbackType", label: "卸载方式" },
          { key: "status", label: "状态", kind: "status" },
          { key: "reason", label: "操作原因" },
          { key: "finishedAt", label: "完成时间", kind: "date" },
        ],
      },
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
