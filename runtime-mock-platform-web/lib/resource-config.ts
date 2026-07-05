import type { LucideIcon } from "lucide-react";
import {
  Box,
  Network,
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
  defaultValue?: string | (() => string);
  type?: "text" | "number" | "date-time" | "textarea" | "json" | "select" | "resource" | "target" | "hidden";
  options?: string[];
  source?:
    | "applications"
    | "rollout-applications"
    | "rollout-environments"
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
const rolloutScope = (form: Record<string, string>) => {
  const [applicationId = "", environmentId = ""] = (form.applicationEnvironment ?? "").split("|");
  return { applicationId, environmentId };
};
export const resourceConfigs: Record<string, ResourceConfig> = {
  applications: {
    key: "applications",
    eyebrow: "Runtime",
    title: "应用实例",
    singular: "实例",
    description: "按应用名称与环境聚合运行实例，查看实例状态、心跳和 Agent 注入能力。",
    icon: Box,
    endpoint: "instances",
    columns: [
      { key: "nickname", label: "实例昵称" },
      { key: "javaVersion", label: "Java 版本" },
      { key: "status", label: "状态", kind: "status" },
      { key: "agentStatus", label: "Agent状态", kind: "status" },
      { key: "loadMode", label: "Agent加载方式" },
      { key: "lastSeenAt", label: "最近心跳", kind: "date" },
    ],
    readOnly: true,
  },
  agents: {
    key: "agents",
    eyebrow: "Runtime",
    title: "Agent 诊断",
    singular: "Agent",
    description: "诊断 Agent 在线状态、版本、心跳、监听地址与实例归属。",
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
      { key: "applicationName", label: "应用" },
      { key: "environmentName", label: "环境" },
      { key: "targetMethod", label: "目标方法", kind: "mono" },
      { key: "versionCount", label: "版本数" },
      { key: "enabledVersionCount", label: "启用版本" },
      { key: "disabledVersionCount", label: "停用版本" },
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
      { key: "planType", label: "类型" },
      { key: "status", label: "状态", kind: "status" },
      { key: "terminalSource", label: "终止来源" },
      { key: "updatedAt", label: "更新时间", kind: "date" },
    ],
    form: {
      capability: "ROLLOUT_MANAGE",
      fields: [
        resource("environmentKey", "环境", "rollout-environments"),
        resource("applicationEnvironment", "应用", "rollout-applications", ["environmentKey"]),
        { key: "resourceType", label: "资源类型", defaultValue: "rule", type: "hidden" },
        resource("resourceId", "规则", "rules", ["environmentKey", "applicationEnvironment"]),
        resource("resourceVersion", "规则版本", "rule-versions", ["resourceId"]),
        { key: "targetMode", label: "目标范围", defaultValue: "ALL_ACTIVE_INSTANCES", type: "hidden" },
        select("automaticUnload", "失败时自动卸载", ["true", "false"], "true"),
      ],
      buildPayload: (form) => ({
        ...rolloutScope(form),
        planType: "RULE_ROLLOUT",
        resourceType: form.resourceType,
        resourceId: form.resourceId,
        resourceVersion: Number(form.resourceVersion),
        strategy: {
          targetMode: form.targetMode,
          automaticUnload: form.automaticUnload === "true",
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
          { key: "instanceNickname", label: "实例快照" },
          { key: "applicationName", label: "应用" },
          { key: "environmentName", label: "环境" },
          { key: "javaVersion", label: "Java", kind: "mono" },
          { key: "loadMode", label: "加载方式" },
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
};
