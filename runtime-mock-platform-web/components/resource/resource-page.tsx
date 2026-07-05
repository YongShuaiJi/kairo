"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { Fragment, type ReactNode } from "react";
import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { CalendarClock, ChevronDown, ChevronLeft, ChevronRight, Copy, Pencil, Plus, RefreshCw, RotateCcw, Search, X } from "lucide-react";
import { toast } from "sonner";
import { platformFetch } from "@/lib/api/client";
import type { PlatformRecord, SessionUser } from "@/lib/api/types";
import { resourceConfigs, type ResourceColumn, type ResourceField, type ResourceForm } from "@/lib/resource-config";
import { actionLabel, fieldLabel, formatBytes, formatDate, humanize, shortId } from "@/lib/utils";
import { PageHeader } from "@/components/layout/page-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { EmptyState } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import { DateTimePicker } from "@/components/ui/date-time-picker";
import { NumberInput } from "@/components/ui/number-input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Textarea } from "@/components/ui/textarea";

type PagedResult = { items: PlatformRecord[]; page: number; size: number; total: number };
const VISIBLE_ENVIRONMENTS = new Set(["dev", "sit", "uat"]);
type AgentLifecycleAction = "attach" | "deactivate" | "reload";
type RuleDetailData = {
  rule: PlatformRecord;
  versions: PlatformRecord[];
  targets: PlatformRecord[];
  capabilities: PlatformRecord[];
};

function valueOf(record: PlatformRecord, key: string) {
  const snake = key.replace(/[A-Z]/g, (letter) => `_${letter.toLowerCase()}`);
  return record[key] ?? record[snake];
}

function statusVariant(status: string) {
  const value = status.toUpperCase();
  if (["UP", "ONLINE", "ACTIVE", "VALID", "ENABLED", "READY", "RUNNING", "SUCCESS", "SUCCEEDED", "COMPLETED", "APPROVED", "PUBLISHED", "AVAILABLE", "HEALTHY", "MATCHED", "ACKED"].includes(value)) return "success" as const;
  if (["PENDING", "BUILDING", "RETRYING", "PARTIAL", "MEDIUM", "QUEUED", "STOPPING", "UNLOADING"].includes(value)) return "warning" as const;
  if (["FAILED", "OFFLINE", "REJECTED", "ERROR", "HIGH", "DIFF", "CANCELLED"].includes(value)) return "danger" as const;
  if (["DRAFT", "PAUSED", "ARCHIVED", "LOW", "UNLOADED", "ABANDONED", "DISABLED", "INVALID", "STOPPED"].includes(value)) return "neutral" as const;
  return "info" as const;
}

function environmentName(record: PlatformRecord) {
  const value = String(valueOf(record, "environmentName") ?? valueOf(record, "type") ?? valueOf(record, "name") ?? "");
  const normalized = value.toLowerCase();
  return VISIBLE_ENVIRONMENTS.has(normalized) ? normalized : value.toLowerCase();
}

type ApplicationInstanceGroup = {
  key: string;
  applicationName: string;
  environment: string;
  instances: PlatformRecord[];
};

function groupApplicationInstances(rows: PlatformRecord[]) {
  const groups = new Map<string, ApplicationInstanceGroup>();
  rows.forEach((record) => {
    const applicationName = String(valueOf(record, "applicationName") ?? "未上报应用");
    const environment = environmentName(record) || "未分配";
    const key = `${applicationName}\u0000${environment}`;
    const existing = groups.get(key);
    if (existing) {
      existing.instances.push(record);
    } else {
      groups.set(key, { key, applicationName, environment, instances: [record] });
    }
  });
  return Array.from(groups.values());
}

function detailTitle(configKey: string, detail: PlatformRecord | null | undefined) {
  if (!detail) return "详情";
  if (configKey === "settings") {
    return String(valueOf(detail, "subjectId") ?? valueOf(detail, "username") ?? "访问 Token");
  }
  if (configKey === "applications") {
    const app = String(valueOf(detail, "applicationName") ?? "");
    const env = environmentName(detail);
    return [app, env].filter(Boolean).join(" / ") || String(valueOf(detail, "id") ?? "实例详情");
  }
  return String(valueOf(detail, "name") ?? valueOf(detail, "applicationName") ?? valueOf(detail, "id") ?? "详情");
}

function shouldShowDetailField(key: string, detail: PlatformRecord | null | undefined, activeEndpoint?: string) {
  if (key === "allowed_actions") return false;
  if (activeEndpoint === "tokens") {
    const snake = key.replace(/[A-Z]/g, (letter) => `_${letter.toLowerCase()}`);
    return ["subject_id", "expires_at"].includes(snake);
  }
  if (!detail) return true;
  const snake = key.replace(/[A-Z]/g, (letter) => `_${letter.toLowerCase()}`);
  if (activeEndpoint === "tokens" && ["display_name", "subject_type", "token_status"].includes(snake)) return false;
  if (snake === "application_name" && valueOf(detail, "applicationId")) return false;
  if (snake === "environment_name" && valueOf(detail, "environmentId")) return false;
  return true;
}

function detailEntries(detail: PlatformRecord | null | undefined, activeEndpoint?: string) {
  if (!detail) return [];
  if (activeEndpoint === "tokens") {
    return [
      ["subject_id", valueOf(detail, "subjectId")],
      ["expires_at", valueOf(detail, "expiresAt")],
    ] as Array<[string, unknown]>;
  }
  return Object.entries(detail).filter(([key]) => shouldShowDetailField(key, detail, activeEndpoint));
}

function detailValue(key: string, value: unknown, detail: PlatformRecord | null | undefined) {
  const snake = key.replace(/[A-Z]/g, (letter) => `_${letter.toLowerCase()}`);
  if (detail && snake === "application_id") return valueOf(detail, "applicationName") ?? value;
  if (detail && snake === "environment_id") return environmentName(detail) || value;
  if (snake === "environment_name") return String(value ?? "").toLowerCase();
  return value;
}

function detailLabel(activeEndpoint: string, key: string) {
  const snake = key.replace(/[A-Z]/g, (letter) => `_${letter.toLowerCase()}`);
  if (activeEndpoint === "tokens" && snake === "subject_id") return "用户名";
  if (activeEndpoint === "tokens" && snake === "expires_at") return "过期时间";
  return fieldLabel(key);
}

function detailText(activeEndpoint: string, key: string, value: unknown) {
  const snake = key.replace(/[A-Z]/g, (letter) => `_${letter.toLowerCase()}`);
  if (activeEndpoint === "tokens" && snake === "expires_at" && !value) return "长期";
  if (key.toLowerCase().endsWith("_at") || key.toLowerCase().endsWith("at")) return formatDate(value);
  return humanize(value);
}

function versionBadgeStatus(record: PlatformRecord | undefined, columnKey: string, value: unknown) {
  if (!record) return null;
  if (columnKey === "onlineVersion") return value ? "ONLINE" : null;
  if (columnKey === "latestVersion") return valueOf(record, "latestVersionStatus");
  return null;
}

function cellTitle(value: unknown) {
  if (value === null || value === undefined || value === "") return undefined;
  if (typeof value === "object") return JSON.stringify(value);
  return String(value);
}

const TARGET_METHOD_DISPLAY_LIMIT = 96;

function compactLongTargetMethod(value: unknown) {
  const text = String(value ?? "");
  if (text.length <= TARGET_METHOD_DISPLAY_LIMIT) return text;
  const headLength = 68;
  const tailLength = TARGET_METHOD_DISPLAY_LIMIT - headLength - 1;
  return `${text.slice(0, headLength)}…${text.slice(-tailLength)}`;
}

function Cell({ value, column, record }: { value: unknown; column: ResourceColumn; record?: PlatformRecord }) {
  if ((value === null || value === undefined || value === "") && column.kind === "date" && ["expiresAt", "expires_at"].includes(column.key)) {
    return <span className="whitespace-nowrap text-slate-500">长期</span>;
  }
  if (value === null || value === undefined || value === "") return <span className="text-slate-300">—</span>;
  if (column.key === "onlineVersion" || column.key === "latestVersion") {
    const status = versionBadgeStatus(record, column.key, value);
    return (
      <span className="inline-flex items-center gap-2 whitespace-nowrap">
        <span className="font-mono text-xs text-slate-600">v{String(value)}</span>
        {status ? <Badge variant={statusVariant(String(status))}>{column.key === "onlineVersion" ? "在线" : humanize(status)}</Badge> : null}
      </span>
    );
  }
  if (["environmentName", "environment_name", "type"].includes(column.key)) {
    const environment = String(value).toLowerCase();
    if (VISIBLE_ENVIRONMENTS.has(environment)) return <span className="whitespace-nowrap">{environment}</span>;
  }
  if (column.key === "loadMode" || column.key === "load_mode") return <span className="whitespace-nowrap">{loadMode(value)}</span>;
  if (column.kind === "status") return <Badge variant={statusVariant(String(value))}>{humanize(String(value))}</Badge>;
  if (column.kind === "date") return <span className="whitespace-nowrap text-slate-500">{formatDate(String(value))}</span>;
  if (column.kind === "bytes") return <span>{formatBytes(Number(value))}</span>;
  if (column.kind === "progress") {
    const progress = Number(value);
    return <div className="flex min-w-28 items-center gap-2"><div className="h-1.5 flex-1 overflow-hidden rounded-full bg-slate-100"><div className="h-full rounded-full bg-indigo-500" style={{ width: `${Math.min(100, progress)}%` }} /></div><span className="w-8 text-right text-xs text-slate-500">{progress}%</span></div>;
  }
  if (column.key === "targetMethod" || column.key === "target_method") {
    const text = String(value);
    return (
      <span className="inline-block max-w-full truncate whitespace-nowrap font-mono text-xs text-slate-600" title={text}>
        {compactLongTargetMethod(text)}
      </span>
    );
  }
  const rendered = Array.isArray(value)
    ? value.map((item) => humanize(item)).join(", ")
    : typeof value === "object"
      ? humanize(value)
      : column.kind === "mono"
        ? String(value)
        : humanize(value);
  if (column.kind === "mono") {
    return (
      <span className="inline-block max-w-full truncate whitespace-nowrap font-mono text-xs text-slate-600" title={String(value)}>
        {shortId(value)}
      </span>
    );
  }
  return <span className="inline-block max-w-full truncate whitespace-nowrap">{rendered}</span>;
}

const RULE_COLUMN_WIDTHS: Record<string, string> = {
  name: "w-[300px] min-w-[300px] max-w-[300px]",
  applicationName: "w-[240px] min-w-[240px] max-w-[240px]",
  environmentName: "w-[100px] min-w-[100px] max-w-[100px]",
  targetMethod: "w-[680px] min-w-[680px] max-w-[680px]",
  versionCount: "w-[96px] min-w-[96px] max-w-[96px]",
  enabledVersionCount: "w-[112px] min-w-[112px] max-w-[112px]",
  disabledVersionCount: "w-[112px] min-w-[112px] max-w-[112px]",
};

const RESOURCE_COLUMN_WIDTHS: Record<string, string> = {
  id: "w-[190px] min-w-[190px] max-w-[190px]",
  resourceId: "w-[190px] min-w-[190px] max-w-[190px]",
  resourceVersion: "w-[90px] min-w-[90px] max-w-[90px]",
  operationPlanId: "w-[190px] min-w-[190px] max-w-[190px]",
  commandId: "w-[190px] min-w-[190px] max-w-[190px]",
  instanceId: "w-[190px] min-w-[190px] max-w-[190px]",
  agentId: "w-[190px] min-w-[190px] max-w-[190px]",
  instanceNickname: "w-[190px] min-w-[190px] max-w-[190px]",
  applicationName: "w-[180px] min-w-[180px] max-w-[180px]",
  environmentName: "w-[100px] min-w-[100px] max-w-[100px]",
  javaVersion: "w-[100px] min-w-[100px] max-w-[100px]",
  loadMode: "w-[180px] min-w-[180px] max-w-[180px]",
  planType: "w-[140px] min-w-[140px] max-w-[140px]",
  rollbackType: "w-[200px] min-w-[200px] max-w-[200px]",
  reason: "w-[320px] min-w-[320px] max-w-[320px]",
  status: "w-[130px] min-w-[130px] max-w-[130px]",
  terminalSource: "w-[180px] min-w-[180px] max-w-[180px]",
  updatedAt: "w-[150px] min-w-[150px] max-w-[150px]",
  finishedAt: "w-[150px] min-w-[150px] max-w-[150px]",
};

function resourceTableClass(configKey: string) {
  if (configKey === "rules") return "w-full min-w-[1720px] table-fixed text-left text-sm";
  if (configKey === "rollouts") return "w-max min-w-[1040px] table-fixed text-left text-sm";
  return "w-full min-w-[1080px] table-fixed text-left text-sm";
}

function resourceHeaderCellClass(configKey: string, columnKey: string) {
  const width = configKey === "rules" ? RULE_COLUMN_WIDTHS[columnKey] ?? "" : RESOURCE_COLUMN_WIDTHS[columnKey] ?? "";
  const alignment = configKey === "rules" && !["name", "applicationName", "targetMethod"].includes(columnKey) ? "text-center" : "";
  return `whitespace-nowrap px-4 py-3 ${width} ${alignment}`;
}

function resourceBodyCellClass(configKey: string, columnKey: string) {
  if (configKey !== "rules") {
    const width = RESOURCE_COLUMN_WIDTHS[columnKey] ?? "w-[180px] min-w-[180px] max-w-[180px]";
    return `whitespace-nowrap px-4 py-3.5 ${width}`;
  }
  const width = RULE_COLUMN_WIDTHS[columnKey] ?? "";
  const alignment = !["name", "applicationName", "targetMethod"].includes(columnKey) ? "text-center" : "";
  return `whitespace-nowrap px-4 py-3.5 ${width} ${alignment}`;
}

function resourceCellContentClass(configKey: string, columnKey: string) {
  if (configKey !== "rules") return "flex min-w-0 max-w-full items-center overflow-hidden whitespace-nowrap";
  const alignment = !["name", "applicationName", "targetMethod"].includes(columnKey) ? "mx-auto justify-center" : "";
  return `flex min-w-0 max-w-full items-center overflow-hidden whitespace-nowrap ${alignment}`;
}

function javaRuntime(value: unknown) {
  const text = String(value ?? "").trim();
  if (!text) return "—";
  return text.toLowerCase().startsWith("java-") ? `Java ${text.slice(5)}` : humanize(text);
}

function loadMode(value: unknown) {
  const text = String(value ?? "").trim().toLowerCase();
  if (text === "premain") return "启动时加载（premain）";
  if (text === "attach") return "运行时加载（attach）";
  return humanize(value);
}

function instanceOnlineJudgement(detail: PlatformRecord) {
  const expiresAt = valueOf(detail, "leaseExpiresAt");
  if (!expiresAt) return "暂无租约信息";
  const leaseExpiresAt = new Date(String(expiresAt));
  if (Number.isNaN(leaseExpiresAt.getTime())) return String(expiresAt);
  return leaseExpiresAt.getTime() >= Date.now()
    ? `心跳正常，租约有效至 ${formatDate(expiresAt)}`
    : `心跳已过期，最后租约到 ${formatDate(expiresAt)}`;
}

function capabilities(value: unknown) {
  const capabilityText: Record<string, string> = {
    BYTECODE_TRANSFORM: "字节码修改",
    DISCOVER_TARGETS: "目标发现",
    APPLY_RULE: "规则下发",
    RESET_CLASS: "恢复目标类",
    RESET_ALL: "恢复全部字节码",
    RECORD_INVOCATIONS: "调用录制",
  };
  if (!value) return [];
  const parsed = typeof value === "string" ? (() => {
    try {
      return JSON.parse(value) as unknown;
    } catch {
      return value.split(",").map((item) => item.trim()).filter(Boolean);
    }
  })() : value;
  return Array.isArray(parsed) ? parsed.map((item) => capabilityText[String(item)] ?? humanize(item)) : [];
}

function agentOf(detail: PlatformRecord) {
  const agent = valueOf(detail, "agent");
  return agent && typeof agent === "object" && !Array.isArray(agent)
    ? agent as PlatformRecord
    : null;
}

function listenAddress(agent: PlatformRecord | null) {
  if (!agent) return "—";
  const host = String(valueOf(agent, "listenHost") ?? "");
  const port = String(valueOf(agent, "listenPort") ?? "");
  return host && port ? `${host}:${port}` : host || port || "—";
}

function DetailRow({
  label,
  children,
  mono = false,
}: {
  label: string;
  children: ReactNode;
  mono?: boolean;
}) {
  return (
    <div className="theme-muted-panel grid grid-cols-[130px_1fr] gap-4 rounded-lg border px-3 py-2.5 text-sm">
      <span className="text-[color:var(--muted)]">{label}</span>
      <span className={`break-all whitespace-pre-wrap text-[color:var(--foreground)] ${mono ? "font-mono text-xs" : ""}`}>{children}</span>
    </div>
  );
}

function DetailSection({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="space-y-3">
      <h3 className="text-xs font-semibold uppercase tracking-[0.14em] text-slate-400">{title}</h3>
      <div className="grid gap-3">{children}</div>
    </section>
  );
}

function TokenMetric({
  label,
  value,
  mono = false,
}: {
  label: string;
  value: ReactNode;
  mono?: boolean;
}) {
  return (
    <div className="rounded-lg border border-[color:var(--border)] bg-[var(--surface-subtle)] px-3 py-3">
      <p className="text-xs font-medium text-[color:var(--muted)]">{label}</p>
      <div className={`mt-1 break-all text-sm font-semibold text-[color:var(--foreground)] ${mono ? "font-mono text-xs" : ""}`}>{value}</div>
    </div>
  );
}

function TokenDetail({ detail }: { detail: PlatformRecord }) {
  const username = String(valueOf(detail, "subjectId") ?? "—");
  const status = String(valueOf(detail, "status") ?? "");
  const subjectType = String(valueOf(detail, "subjectType") ?? "USER");
  const tokenId = String(valueOf(detail, "id") ?? "—");
  const expiresAt = valueOf(detail, "expiresAt");
  const createdBy = valueOf(detail, "createdBy");
  const revokedAt = valueOf(detail, "revokedAt");

  return (
    <div className="space-y-5">
      <section className="theme-panel rounded-xl border p-4">
        <div className="flex items-start justify-between gap-4">
          <div className="min-w-0">
            <p className="text-xs font-medium text-[color:var(--muted)]">Token 持有人</p>
            <p className="mt-1 truncate text-2xl font-semibold text-[color:var(--foreground)]">{username}</p>
          </div>
          {status ? <Badge variant={statusVariant(status)}>{humanize(status)}</Badge> : null}
        </div>
        <div className="mt-4 grid gap-3 sm:grid-cols-2">
          <TokenMetric label="过期时间" value={expiresAt ? formatDate(expiresAt) : "长期有效"} />
          <TokenMetric label="最后使用" value={formatDate(valueOf(detail, "lastUsedAt"))} />
          <TokenMetric label="签发时间" value={formatDate(valueOf(detail, "createdAt"))} />
          <TokenMetric label="签发人" value={humanize(createdBy)} />
        </div>
      </section>

      <DetailSection title="审计信息">
        <DetailRow label="Token ID" mono>{tokenId}</DetailRow>
        <DetailRow label="主体类型">{humanize(subjectType)}</DetailRow>
        {revokedAt ? <DetailRow label="撤销时间">{formatDate(revokedAt)}</DetailRow> : null}
      </DetailSection>
    </div>
  );
}

function EditableNickname({
  record,
  nicknamePending,
  onSubmit,
}: {
  record: PlatformRecord;
  nicknamePending: boolean;
  onSubmit: (record: PlatformRecord, nickname: string) => void;
}) {
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState(String(valueOf(record, "nickname") ?? ""));

  useEffect(() => {
    if (!editing) {
      setValue(String(valueOf(record, "nickname") ?? ""));
    }
  }, [editing, record]);

  function submit() {
    const next = value.trim();
    if (!next) {
      toast.error("昵称不能为空");
      setValue(String(valueOf(record, "nickname") ?? ""));
      return;
    }
    if (next === String(valueOf(record, "nickname") ?? "")) {
      setEditing(false);
      return;
    }
    onSubmit(record, next);
    setEditing(false);
  }

  if (editing) {
    return (
      <Input
        autoFocus
        value={value}
        onChange={(event) => setValue(event.target.value)}
        onBlur={submit}
        onKeyDown={(event) => {
          if (event.key === "Enter") {
            event.currentTarget.blur();
          }
          if (event.key === "Escape") {
            setValue(String(valueOf(record, "nickname") ?? ""));
            setEditing(false);
            event.currentTarget.blur();
          }
        }}
        disabled={nicknamePending}
        aria-label="实例昵称"
        className="h-9 max-w-72 font-medium"
      />
    );
  }
  return (
    <button
      type="button"
      onPointerDown={(event) => {
        event.preventDefault();
        event.stopPropagation();
        setEditing(true);
      }}
      onClick={(event) => {
        event.stopPropagation();
        setEditing(true);
      }}
      className="group/nickname inline-flex max-w-72 items-center gap-2 rounded-md px-2 py-1 text-left font-medium text-[color:var(--foreground)] transition hover:bg-[var(--surface-muted)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--focus-border)]"
      aria-label="编辑实例昵称"
      title="编辑昵称"
    >
      <span className="truncate">{String(valueOf(record, "nickname") ?? "—")}</span>
      <Pencil className="size-3.5 shrink-0 text-[color:var(--muted)] opacity-0 transition group-hover/nickname:opacity-100 group-focus-visible/nickname:opacity-100" />
    </button>
  );
}

function ApplicationDetail({
  detail,
  nicknamePending,
  onSubmitNickname,
}: {
  detail: PlatformRecord;
  nicknamePending: boolean;
  onSubmitNickname: (record: PlatformRecord, nickname: string) => void;
}) {
  const agent = agentOf(detail);
  const capabilityItems = capabilities(valueOf(agent ?? detail, "capabilitiesJson"));
  return (
    <div className="space-y-6">
      <DetailSection title="核心信息">
        <DetailRow label="实例 ID" mono>{String(valueOf(detail, "id") ?? "—")}</DetailRow>
        <DetailRow label="应用">{humanize(valueOf(detail, "applicationName"))}</DetailRow>
        <DetailRow label="环境">{environmentName(detail) || "—"}</DetailRow>
        <DetailRow label="昵称">
          <EditableNickname
            record={detail}
            nicknamePending={nicknamePending}
            onSubmit={onSubmitNickname}
          />
        </DetailRow>
        <DetailRow label="状态"><Badge variant={statusVariant(String(valueOf(detail, "status") ?? ""))}>{humanize(valueOf(detail, "status"))}</Badge></DetailRow>
        <DetailRow label="最近心跳">{formatDate(valueOf(detail, "lastSeenAt"))}</DetailRow>
      </DetailSection>

      <DetailSection title="运行信息">
        <DetailRow label="Java 版本">{javaRuntime(valueOf(detail, "runtime") ?? valueOf(detail, "javaVersion"))}</DetailRow>
        <DetailRow label="JVM 启动时间">{formatDate(valueOf(detail, "jvmStartedAt"))}</DetailRow>
        <DetailRow label="环境分配状态">{humanize(valueOf(detail, "registrationStatus"))}</DetailRow>
        <DetailRow label="注册时间">{formatDate(valueOf(detail, "createdAt"))}</DetailRow>
      </DetailSection>

      <DetailSection title="Agent 信息">
        {agent ? (
          <>
            <DetailRow label="Agent ID" mono>{String(valueOf(agent, "id") ?? "—")}</DetailRow>
            <DetailRow label="Agent 状态"><Badge variant={statusVariant(String(valueOf(agent, "status") ?? ""))}>{humanize(valueOf(agent, "status"))}</Badge></DetailRow>
            <DetailRow label="Agent 版本">{humanize(valueOf(agent, "agentVersion"))}</DetailRow>
            <DetailRow label="加载方式">{loadMode(valueOf(detail, "loadMode"))}</DetailRow>
            <DetailRow label="最后心跳">{formatDate(valueOf(agent, "lastHeartbeatAt"))}</DetailRow>
            <DetailRow label="监听地址" mono>{listenAddress(agent)}</DetailRow>
          </>
        ) : (
          <DetailRow label="Agent 状态"><Badge variant="warning">未注册</Badge></DetailRow>
        )}
      </DetailSection>

      <details className="theme-panel group rounded-xl border">
        <summary className="cursor-pointer list-none px-3 py-3 text-sm font-semibold text-[color:var(--foreground)]">
          <span className="inline-flex items-center gap-2">
            能力与诊断
            <span className="text-xs font-normal text-slate-400 group-open:hidden">展开</span>
            <span className="hidden text-xs font-normal text-slate-400 group-open:inline">收起</span>
          </span>
        </summary>
        <div className="grid gap-3 border-t p-3">
          <DetailRow label="在线判定">{instanceOnlineJudgement(detail)}</DetailRow>
          <DetailRow label="可注入能力">
            {capabilityItems.length ? (
              <span className="flex flex-wrap gap-1.5">
                {capabilityItems.map((item) => <Badge key={String(item)} variant="info">{item}</Badge>)}
              </span>
            ) : "—"}
          </DetailRow>
        </div>
      </details>
    </div>
  );
}

function JsonBlock({ value }: { value: unknown }) {
  const text = (() => {
    if (value === null || value === undefined || value === "") return "—";
    if (typeof value !== "string") return humanize(value);
    try {
      return JSON.stringify(JSON.parse(value), null, 2);
    } catch {
      return value;
    }
  })();
  return (
    <pre className="max-h-72 overflow-auto rounded-lg border bg-[var(--surface-subtle)] p-3 font-mono text-xs leading-5 text-[color:var(--foreground)]">
      {text}
    </pre>
  );
}

function RuleDetail({
  detail,
  ruleDetail,
}: {
  detail: PlatformRecord;
  ruleDetail: RuleDetailData | undefined;
}) {
  const rule = ruleDetail?.rule ?? detail;
  const versions = ruleDetail?.versions ?? [];
  const targets = ruleDetail?.targets ?? [];
  const capabilitiesList = ruleDetail?.capabilities ?? [];
  const onlineVersion = valueOf(rule, "onlineVersion") ?? valueOf(detail, "onlineVersion");
  const latestVersion = valueOf(rule, "latestVersion") ?? valueOf(detail, "latestVersion");

  function versionTargets(version: PlatformRecord) {
    const versionId = String(valueOf(version, "id") ?? "");
    return targets.filter((target) => String(valueOf(target, "ruleVersionId") ?? "") === versionId);
  }

  function versionCapabilities(version: PlatformRecord) {
    const versionId = String(valueOf(version, "id") ?? "");
    return capabilitiesList.filter((capability) => String(valueOf(capability, "ruleVersionId") ?? "") === versionId);
  }

  return (
    <div className="space-y-6">
      <DetailSection title="规则信息">
        <DetailRow label="规则 ID" mono>{String(valueOf(rule, "id") ?? "—")}</DetailRow>
        <DetailRow label="应用">{humanize(valueOf(rule, "applicationName") ?? valueOf(rule, "applicationId"))}</DetailRow>
        <DetailRow label="环境">{humanize(valueOf(rule, "environmentName") ?? valueOf(rule, "environmentId"))}</DetailRow>
        <DetailRow label="状态"><Badge variant={statusVariant(String(valueOf(rule, "status") ?? ""))}>{humanize(valueOf(rule, "status"))}</Badge></DetailRow>
        <DetailRow label="在线版本">{onlineVersion ? <span className="inline-flex items-center gap-2"><span className="font-mono text-xs">v{String(onlineVersion)}</span><Badge variant="success">在线</Badge></span> : "—"}</DetailRow>
        <DetailRow label="最新版本">{latestVersion ? <span className="font-mono text-xs">v{String(latestVersion)}</span> : "—"}</DetailRow>
        <DetailRow label="更新时间">{formatDate(valueOf(rule, "updatedAt"))}</DetailRow>
      </DetailSection>

      <DetailSection title="版本记录">
        {versions.length ? versions.map((version) => {
          const versionNumber = valueOf(version, "version");
          const isOnline = String(versionNumber) === String(onlineVersion);
          const status = String(valueOf(version, "status") ?? "");
          const versionTargetRows = versionTargets(version);
          const versionCapabilityRows = versionCapabilities(version);
          return (
            <details key={String(valueOf(version, "id") ?? versionNumber)} className="theme-panel group rounded-lg border">
              <summary className="grid cursor-pointer list-none grid-cols-[1fr_auto] gap-3 px-3 py-3 text-sm">
                <span className="flex min-w-0 flex-wrap items-center gap-2">
                  <span className="font-mono font-semibold text-[color:var(--foreground)]">v{String(versionNumber)}</span>
                  <Badge variant={statusVariant(status)}>{humanize(status)}</Badge>
                  {isOnline ? <Badge variant="success">当前在线</Badge> : null}
                  {String(versionNumber) === String(latestVersion) ? <Badge variant="info">最新</Badge> : null}
                  <span className="text-xs text-[color:var(--muted)]">{formatDate(valueOf(version, "createdAt"))}</span>
                </span>
                <span className="text-xs text-[color:var(--muted)] group-open:hidden">查看</span>
                <span className="hidden text-xs text-[color:var(--muted)] group-open:inline">收起</span>
              </summary>
              <div className="space-y-4 border-t p-3">
                <div className="grid gap-3">
                  <DetailRow label="版本 ID" mono>{String(valueOf(version, "id") ?? "—")}</DetailRow>
                  <DetailRow label="创建人">{humanize(valueOf(version, "createdBy"))}</DetailRow>
                  <DetailRow label="风险等级"><Badge variant={statusVariant(String(valueOf(version, "riskLevel") ?? ""))}>{humanize(valueOf(version, "riskLevel"))}</Badge></DetailRow>
                  <DetailRow label="脚本摘要" mono>{String(valueOf(version, "scriptHash") ?? "—")}</DetailRow>
                </div>
                <div className="space-y-2">
                  <p className="text-xs font-semibold text-[color:var(--muted)]">目标方法</p>
                  {versionTargetRows.length ? (
                    <div className="grid gap-2">
                      {versionTargetRows.map((target, index) => (
                        <div key={String(valueOf(target, "id") ?? index)} className="rounded-lg border bg-[var(--surface-subtle)] px-3 py-2 font-mono text-xs">
                          {String(valueOf(target, "className") ?? "—")}#{String(valueOf(target, "methodName") ?? "—")}
                        </div>
                      ))}
                    </div>
                  ) : <div className="text-sm text-[color:var(--muted)]">—</div>}
                </div>
                <div className="space-y-2">
                  <p className="text-xs font-semibold text-[color:var(--muted)]">能力</p>
                  {versionCapabilityRows.length ? (
                    <div className="flex flex-wrap gap-1.5">
                      {versionCapabilityRows.map((capability, index) => <Badge key={String(valueOf(capability, "id") ?? index)} variant="info">{humanize(valueOf(capability, "capability"))}</Badge>)}
                    </div>
                  ) : <div className="text-sm text-[color:var(--muted)]">—</div>}
                </div>
                <div className="space-y-2">
                  <p className="text-xs font-semibold text-[color:var(--muted)]">匹配配置</p>
                  <JsonBlock value={valueOf(version, "matcherJson")} />
                </div>
                <div className="space-y-2">
                  <p className="text-xs font-semibold text-[color:var(--muted)]">脚本配置</p>
                  <JsonBlock value={valueOf(version, "scriptJson")} />
                </div>
                <div className="space-y-2">
                  <p className="text-xs font-semibold text-[color:var(--muted)]">治理配置</p>
                  <JsonBlock value={valueOf(version, "governanceJson")} />
                </div>
              </div>
            </details>
          );
        }) : (
          <div className="rounded-lg border px-3 py-5 text-center text-sm text-[color:var(--muted)]">暂无版本记录</div>
        )}
      </DetailSection>
    </div>
  );
}

function RolloutExecutionDetail({ detail }: { detail: PlatformRecord }) {
  const status = String(valueOf(detail, "status") ?? "");
  const errorMessage = valueOf(detail, "errorMessage");

  return (
    <div className="space-y-6">
      <DetailSection title="执行信息">
        <DetailRow label="执行 ID" mono>{String(valueOf(detail, "id") ?? "—")}</DetailRow>
        <DetailRow label="发布计划" mono>{String(valueOf(detail, "operationPlanId") ?? "—")}</DetailRow>
        <DetailRow label="状态"><Badge variant={statusVariant(status)}>{humanize(status)}</Badge></DetailRow>
        <DetailRow label="规则版本">{humanize(valueOf(detail, "expectedRuleVersion"))}</DetailRow>
        <DetailRow label="期望 Agent">{humanize(valueOf(detail, "expectedAgentVersion"))}</DetailRow>
        <DetailRow label="Agent 命令" mono>{String(valueOf(detail, "commandId") ?? "—")}</DetailRow>
        <DetailRow label="开始时间">{formatDate(valueOf(detail, "startedAt"))}</DetailRow>
        <DetailRow label="完成时间">{formatDate(valueOf(detail, "finishedAt"))}</DetailRow>
        {errorMessage ? <DetailRow label="异常信息" mono>{String(errorMessage)}</DetailRow> : null}
      </DetailSection>

      <details className="theme-panel group rounded-xl border">
        <summary className="flex cursor-pointer list-none items-center justify-between gap-3 px-3 py-3 text-sm font-semibold text-[color:var(--foreground)]">
          <span>命中实例快照</span>
          <ChevronDown className="size-4 shrink-0 text-[color:var(--muted)] transition group-open:rotate-180" />
        </summary>
        <div className="grid gap-3 border-t p-3">
          <DetailRow label="实例快照">{humanize(valueOf(detail, "instanceNickname"))}</DetailRow>
          <DetailRow label="实例 ID" mono>{String(valueOf(detail, "instanceId") ?? "—")}</DetailRow>
          <DetailRow label="应用">{humanize(valueOf(detail, "applicationName"))}</DetailRow>
          <DetailRow label="环境">{environmentName(detail) || "—"}</DetailRow>
          <DetailRow label="Java 版本">{javaRuntime(valueOf(detail, "javaVersion"))}</DetailRow>
          <DetailRow label="Agent 版本">{humanize(valueOf(detail, "agentVersion"))}</DetailRow>
          <DetailRow label="加载方式">{loadMode(valueOf(detail, "loadMode"))}</DetailRow>
          <DetailRow label="进程启动标识" mono>{String(valueOf(detail, "processStartId") ?? "—")}</DetailRow>
          <DetailRow label="实例最后心跳">{formatDate(valueOf(detail, "instanceLastSeenAt"))}</DetailRow>
          <DetailRow label="Attach 执行器" mono>{String(valueOf(detail, "attachExecutorId") ?? "—")}</DetailRow>
        </div>
      </details>
    </div>
  );
}

function initialForm(form: ResourceForm | undefined) {
  return Object.fromEntries((form?.fields ?? []).map((field) => [
    field.key,
    typeof field.defaultValue === "function" ? field.defaultValue() : field.defaultValue ?? "",
  ]));
}

function isoInstant(value: string) {
  if (!value.trim()) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

function splitApplicationEnvironment(value: string | undefined) {
  const [applicationId = "", environmentId = ""] = String(value ?? "").split("|");
  return { applicationId, environmentId };
}

function resourceOptionValue(source: ResourceField["source"], record: PlatformRecord) {
  if (source === "rule-versions") return String(valueOf(record, "version") ?? "");
  if (source === "rollout-applications") {
    const applicationId = String(valueOf(record, "id") ?? "");
    const environmentId = String(valueOf(record, "environmentId") ?? "");
    return applicationId && environmentId ? `${applicationId}|${environmentId}` : "";
  }
  return String(valueOf(record, "id") ?? "");
}

function resourceOptionLabel(source: ResourceField["source"], record: PlatformRecord) {
  const id = String(valueOf(record, "id") ?? "");
  const name = String(valueOf(record, "name") ?? id);
  if (source === "applications" || source === "rollout-applications") {
    return name;
  }
  if (source === "rollout-environments") {
    return environmentName(record);
  }
  if (source === "environments") {
    return environmentName(record);
  }
  if (source === "rule-versions") {
    return `版本 ${String(valueOf(record, "version") ?? "—")}`;
  }
  if (source === "rules") {
    return name;
  }
  return `${name}（${shortId(id)}）`;
}

function ResourceSelectField({
  field,
  form,
  onValueChange,
}: {
  field: ResourceField;
  form: Record<string, string>;
  onValueChange: (value: string) => void;
}) {
  const dependenciesReady = (field.dependsOn ?? []).every((key) => Boolean(form[key]));
  const optionsQuery = useQuery({
    queryKey: ["resource-field-options", field.source, ...((field.dependsOn ?? []).map((key) => form[key] ?? ""))],
    queryFn: () => platformFetch<PagedResult>(`query/${field.source}?page=0&size=200&q=`),
    enabled: Boolean(field.source && dependenciesReady),
  });
  const options = (optionsQuery.data?.items ?? []).filter((record) => {
    if (field.source === "rollout-applications") {
      return String(valueOf(record, "environmentKey") ?? "").toLowerCase() === String(form.environmentKey ?? "").toLowerCase();
    }
    if (field.source === "environments") {
      const applicationField = field.dependsOn?.find((key) => key.toLowerCase().includes("application")) ?? "applicationId";
      return String(valueOf(record, "applicationId") ?? "") === form[applicationField]
        && VISIBLE_ENVIRONMENTS.has(String(valueOf(record, "type") ?? valueOf(record, "name") ?? "").toLowerCase());
    }
    if (field.source === "rules") {
      const rolloutScope = splitApplicationEnvironment(form.applicationEnvironment);
      const applicationId = rolloutScope.applicationId || form.applicationId;
      const environmentId = rolloutScope.environmentId || form.environmentId;
      return String(valueOf(record, "applicationId") ?? "") === applicationId
        && String(valueOf(record, "environmentId") ?? "") === environmentId
        && String(valueOf(record, "status") ?? "").toUpperCase() === "ENABLED"
        && Number(valueOf(record, "enabledVersionCount") ?? 0) > 0;
    }
    if (field.source === "rule-versions") {
      return String(valueOf(record, "ruleId") ?? "") === form.resourceId
        && String(valueOf(record, "status") ?? "").toUpperCase() === "ENABLED";
    }
    return true;
  });
  const dependencyLabel = (field.dependsOn ?? []).map((key) => fieldLabel(key)).join("、");

  return (
    <Select value={form[field.key] ?? ""} onValueChange={onValueChange} disabled={!dependenciesReady || optionsQuery.isLoading}>
      <SelectTrigger aria-label={field.label}>
        <SelectValue placeholder={
          !dependenciesReady
            ? `请先选择${dependencyLabel}`
            : optionsQuery.isLoading
              ? `正在加载${field.label}…`
              : `请选择${field.label}`
        } />
      </SelectTrigger>
      <SelectContent>
        {options.map((record, index) => {
          const value = resourceOptionValue(field.source, record);
          return value ? (
            <SelectItem key={`${String(record.id ?? index)}-${value}`} value={value}>
              {resourceOptionLabel(field.source, record)}
            </SelectItem>
          ) : null;
        })}
        {!optionsQuery.isLoading && dependenciesReady && !options.length ? (
          <div className="px-3 py-5 text-center text-xs text-slate-400">暂无可选择的{field.label}</div>
        ) : null}
      </SelectContent>
    </Select>
  );
}

function TargetSelectField({
  field,
  form,
  onValueChange,
}: {
  field: ResourceField;
  form: Record<string, string>;
  onValueChange: (value: string) => void;
}) {
  const [query, setQuery] = useState("");
  const applicationId = form.applicationId ?? "";
  const environmentId = form.environmentId ?? "";
  const dependenciesReady = Boolean(applicationId && environmentId);
  const targetsQuery = useQuery({
    queryKey: ["target-field-options", applicationId, environmentId, query],
    queryFn: () => {
      const search = new URLSearchParams({ q: query.trim(), applicationId, environmentId });
      return platformFetch<PlatformRecord[]>(`targets/search?${search.toString()}`);
    },
    enabled: dependenciesReady,
  });
  const selectedTarget = (() => {
    try {
      return JSON.parse(form[field.key] ?? "{}") as PlatformRecord;
    } catch {
      return {};
    }
  })();
  const selectedLabel = selectedTarget.className && selectedTarget.methodName
    ? `${String(selectedTarget.className)}#${String(selectedTarget.methodName)}${String(selectedTarget.methodDescriptor ?? selectedTarget.descriptor ?? "")}`
    : "";

  return (
    <div className="space-y-2">
      <div className="relative">
        <Search className="absolute left-3 top-2.5 size-4 text-slate-400" />
        <Input
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          disabled={!dependenciesReady}
          placeholder={dependenciesReady ? "搜索已加载的类或方法…" : "请先选择应用和环境"}
          className="pl-9"
        />
      </div>
      <Select
        value={form[field.key] ?? ""}
        onValueChange={onValueChange}
        disabled={!dependenciesReady || targetsQuery.isLoading}
      >
        <SelectTrigger aria-label={field.label}>
          <SelectValue placeholder={
            !dependenciesReady
              ? "请先选择应用和环境"
              : targetsQuery.isLoading
                ? "正在从 Agent 发现目标方法…"
                : "请选择目标方法"
          }>
            {selectedLabel || undefined}
          </SelectValue>
        </SelectTrigger>
        <SelectContent>
          {(targetsQuery.data ?? []).map((record, index) => {
            const descriptor = String(valueOf(record, "descriptor") ?? valueOf(record, "methodDescriptor") ?? "");
            const target = {
              protocol: String(valueOf(record, "protocol") ?? "JAVA_METHOD"),
              classId: String(valueOf(record, "classId") ?? valueOf(record, "className") ?? ""),
              className: String(valueOf(record, "className") ?? ""),
              classLoaderId: String(valueOf(record, "classLoaderId") ?? ""),
              methodName: String(valueOf(record, "methodName") ?? ""),
              methodDescriptor: descriptor,
              descriptor,
            };
            const value = JSON.stringify(target);
            return target.className && target.methodName ? (
              <SelectItem key={`${target.classId}-${target.methodName}-${descriptor}-${index}`} value={value}>
                {target.className}#{target.methodName}{descriptor}
              </SelectItem>
            ) : null;
          })}
          {!targetsQuery.isLoading && dependenciesReady && !(targetsQuery.data ?? []).length ? (
            <div className="px-3 py-5 text-center text-xs leading-5 text-slate-400">
              没有发现已加载的方法。请确认目标应用 Agent 在线且实例已分配环境。
            </div>
          ) : null}
        </SelectContent>
      </Select>
    </div>
  );
}

export function ResourcePage({ resourceKey }: { resourceKey: string }) {
  const config = resourceConfigs[resourceKey];
  const router = useRouter();
  const queryClient = useQueryClient();
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(0);
  const [activeEndpoint, setActiveEndpoint] = useState(config.endpoint);
  const [selected, setSelected] = useState<PlatformRecord | null>(null);
  const [creating, setCreating] = useState(false);
  const [created, setCreated] = useState<PlatformRecord | null>(null);
  const [renewingToken, setRenewingToken] = useState<PlatformRecord | null>(null);
  const [renewExpiresAt, setRenewExpiresAt] = useState("");
  const [assignmentEnvironment, setAssignmentEnvironment] = useState("");
  const [confirmingUnload, setConfirmingUnload] = useState(false);
  const [editingNicknameId, setEditingNicknameId] = useState("");
  const [editingNicknameValue, setEditingNicknameValue] = useState("");
  const [collapsedApplicationGroups, setCollapsedApplicationGroups] = useState<Set<string>>(() => new Set());

  const activeTab = config.tabs?.find((tab) => tab.endpoint === activeEndpoint);
  const columns = activeTab?.columns.length ? activeTab.columns : config.columns;
  const activeForm = activeTab?.form ?? (activeEndpoint === config.endpoint ? config.form : undefined);
  const [form, setForm] = useState<Record<string, string>>(() => initialForm(activeForm));

  useEffect(() => {
    setPage(0);
    setForm(initialForm(activeForm));
  }, [activeEndpoint, activeForm]);

  const session = useQuery({
    queryKey: ["session"],
    queryFn: async () => {
      const response = await fetch("/api/auth/session");
      if (!response.ok) throw new Error("会话已失效");
      return response.json() as Promise<SessionUser>;
    },
  });
  const can = (capability: string) =>
    Boolean(session.data?.capabilities?.includes("ADMIN") || session.data?.capabilities?.includes(capability));
  const transitionCapability: Record<string, string> = {
    "operation-plans": "ROLLOUT_MANAGE",
    "rollout-executions": "ROLLOUT_MANAGE",
  };

  const resourceQuery = useQuery({
    queryKey: ["resource", activeEndpoint, page, query],
    queryFn: () => platformFetch<PagedResult>(`query/${activeEndpoint}?page=${page}&size=25&q=${encodeURIComponent(query.trim())}`),
    refetchInterval: activeEndpoint === "agents" ? 15_000 : false,
  });
  const rows = resourceQuery.data?.items ?? [];
  const applicationGroups = config.key === "applications" ? groupApplicationInstances(rows) : [];

  const detailQuery = useQuery({
    queryKey: ["detail", activeEndpoint, selected?.id],
    queryFn: () => platformFetch<PlatformRecord>(`details/${activeEndpoint}/${selected?.id}`),
    enabled: Boolean(selected?.id),
  });
  const ruleDetailQuery = useQuery({
    queryKey: ["rule-detail", selected?.id],
    queryFn: () => platformFetch<RuleDetailData>(`rules/${selected?.id}/detail`),
    enabled: config.key === "rules" && Boolean(selected?.id),
  });
  const detail = detailQuery.data ?? selected;
  const allowedActions = Array.isArray(detail?.allowed_actions) ? detail.allowed_actions.map(String) : [];
  const detailApplicationId = String(valueOf(detail ?? {}, "applicationId") ?? "");
  const detailEnvironmentId = String(valueOf(detail ?? {}, "environmentId") ?? "");
  const detailAgent = detail ? agentOf(detail) : null;
  const detailAgentStatus = String(valueOf(detailAgent ?? {}, "status") ?? "").toUpperCase();
  const detailAgentCanDeactivate = Boolean(detailAgent?.id && ["ACTIVE", "ONLINE"].includes(detailAgentStatus));

  useEffect(() => {
    setAssignmentEnvironment("");
  }, [selected?.id]);

  const assignmentOptionsQuery = useQuery({
    queryKey: ["instance-environment-options", detailApplicationId],
    queryFn: () => platformFetch<PagedResult>("query/environments?page=0&size=200&q="),
    enabled: config.key === "applications" && Boolean(selected?.id && detailApplicationId && !detailEnvironmentId),
  });
  const assignmentOptions = (assignmentOptionsQuery.data?.items ?? []).filter((record) =>
    String(valueOf(record, "applicationId") ?? "") === detailApplicationId
      && VISIBLE_ENVIRONMENTS.has(String(valueOf(record, "type") ?? valueOf(record, "name") ?? "").toLowerCase()),
  );

  const assignmentMutation = useMutation({
    mutationFn: () => platformFetch(`instances/${detail?.id}/environment`, {
      method: "POST",
      body: JSON.stringify({ environmentId: assignmentEnvironment }),
      idempotencyKey: crypto.randomUUID(),
    }),
    onSuccess: async () => {
      toast.success("环境分配成功");
      setSelected(null);
      await queryClient.invalidateQueries({ queryKey: ["resource", activeEndpoint] });
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "环境分配失败"),
  });

  const nicknameMutation = useMutation({
    mutationFn: ({ id, nickname }: { id: string; nickname: string }) => platformFetch<PlatformRecord>(`instances/${id}/nickname`, {
      method: "PATCH",
      body: JSON.stringify({ nickname }),
      idempotencyKey: crypto.randomUUID(),
    }),
    onSuccess: async () => {
      toast.success("昵称已更新");
      setEditingNicknameId("");
      setEditingNicknameValue("");
      await queryClient.invalidateQueries({ queryKey: ["resource", activeEndpoint] });
      await queryClient.invalidateQueries({ queryKey: ["detail", activeEndpoint] });
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "昵称更新失败"),
  });

  const agentLifecycleMutation = useMutation({
    mutationFn: ({ id, action }: { id: string; action: AgentLifecycleAction }) => platformFetch<PlatformRecord>(`instances/${id}/agent/${action}`, {
      method: "POST",
      body: JSON.stringify({ reason: `通过 Web 控制台执行 Agent ${action}` }),
      idempotencyKey: crypto.randomUUID(),
    }),
    onSuccess: async (_result, variables) => {
      const message: Record<AgentLifecycleAction, string> = {
        attach: "Attach 已执行，等待 Agent 注册心跳",
        deactivate: "停用命令已下发，Agent 将恢复字节码并停止服务",
        reload: "重新加载已执行，等待 Agent 注册心跳",
      };
      toast.success(message[variables.action]);
      await queryClient.invalidateQueries({ queryKey: ["resource", activeEndpoint] });
      await queryClient.invalidateQueries({ queryKey: ["detail", activeEndpoint] });
      await queryClient.invalidateQueries({ queryKey: ["rule-detail"] });
      void detailQuery.refetch();
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "Agent 操作失败"),
  });

  const createMutation = useMutation({
    mutationFn: async () => {
      if (!activeForm) throw new Error("当前视图不支持创建");
      const endpoint = activeForm.buildEndpoint?.(form) ?? activeEndpoint;
      const payload = activeForm.buildPayload?.(form) ?? form;
      return platformFetch<PlatformRecord>(endpoint, {
        method: "POST",
        body: JSON.stringify(payload),
        idempotencyKey: crypto.randomUUID(),
      });
    },
    onSuccess: (result) => {
      toast.success(`${activeTab?.label ?? config.singular}已创建`);
      setCreating(false);
      setCreated(result);
      setForm(initialForm(activeForm));
      void queryClient.invalidateQueries({ queryKey: ["resource", activeEndpoint] });
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "创建失败"),
  });

  const renewTokenMutation = useMutation({
    mutationFn: () => {
      const id = String(valueOf(renewingToken ?? {}, "id") ?? "");
      if (!id) throw new Error("未找到需要续期的 Token");
      return platformFetch<PlatformRecord>(`auth/tokens/${encodeURIComponent(id)}/renew`, {
        method: "POST",
        body: JSON.stringify({ expiresAt: isoInstant(renewExpiresAt) }),
        idempotencyKey: crypto.randomUUID(),
      });
    },
    onSuccess: async () => {
      toast.success("Token 已续期");
      setRenewingToken(null);
      setRenewExpiresAt("");
      await queryClient.invalidateQueries({ queryKey: ["resource", activeEndpoint] });
      await queryClient.invalidateQueries({ queryKey: ["detail", activeEndpoint] });
      void detailQuery.refetch();
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "Token 续期失败"),
  });

  async function transition(targetStatus: string) {
    if (!detail?.id || !detail.status || detail.version === undefined) return;
    const reason = `通过 Web 控制台执行：${actionLabel(targetStatus)}`;
    const resourceType: Record<string, string> = {
      "operation-plans": "operation_plan",
    };
    const transitionPath: Record<string, string> = {
      "operation-plans": "operation-plans",
    };
    try {
      const token = await platformFetch<{ token: string }>("fencing-tokens", {
        method: "POST",
        body: JSON.stringify({ resourceType: resourceType[activeEndpoint], resourceId: detail.id, purpose: reason, ttlSeconds: 300, reason }),
        idempotencyKey: crypto.randomUUID(),
      });
      await platformFetch(`${transitionPath[activeEndpoint]}/${detail.id}/transition`, {
        method: "POST",
        body: JSON.stringify({ expectedStatus: detail.status, expectedVersion: detail.version, targetStatus, fencingToken: token.token, reason }),
        idempotencyKey: crypto.randomUUID(),
      });
      toast.success(`状态已更新为 ${humanize(targetStatus)}`);
      setSelected(null);
      await queryClient.invalidateQueries({ queryKey: ["resource", activeEndpoint] });
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "状态更新失败");
    }
  }

  const unloadMutation = useMutation({
    mutationFn: async () => {
      const operationPlanId = activeEndpoint === "operation-plans"
        ? String(detail?.id ?? "")
        : String(valueOf(detail ?? {}, "operationPlanId") ?? "");
      if (!operationPlanId) throw new Error("未找到实例执行所属的发布计划");
      const operationPlan = activeEndpoint === "operation-plans"
        ? detail
        : await platformFetch<PlatformRecord>(`details/operation-plans/${operationPlanId}`);
      if (!operationPlan?.status || operationPlan.version === undefined) {
        throw new Error("发布计划状态不完整，请刷新后重试");
      }
      const reason = "通过 Web 控制台卸载规则并恢复原始字节码";
      const token = await platformFetch<{ token: string }>("fencing-tokens", {
        method: "POST",
        body: JSON.stringify({
          resourceType: "operation_plan",
          resourceId: operationPlanId,
          purpose: reason,
          ttlSeconds: 300,
          reason,
        }),
        idempotencyKey: crypto.randomUUID(),
      });
      return platformFetch(`operation-plans/${operationPlanId}/unload`, {
        method: "POST",
        body: JSON.stringify({
          expectedStatus: operationPlan.status,
          expectedVersion: operationPlan.version,
          fencingToken: token.token,
          reason,
        }),
        idempotencyKey: crypto.randomUUID(),
      });
    },
    onSuccess: async () => {
      toast.success("卸载命令已发送，等待 Agent 恢复原始字节码");
      setConfirmingUnload(false);
      setSelected(null);
      await queryClient.invalidateQueries({ queryKey: ["resource"] });
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "规则卸载失败"),
  });

  const createAction = (() => {
    if (config.createHref) {
      return can("RULE_MANAGE") ? <Button asChild><Link href={config.createHref}><Plus />{config.createLabel ?? `创建${config.singular}`}</Link></Button> : null;
    }
    if (!activeForm || !can(activeForm.capability)) return null;
    return <Button onClick={() => {
      setForm(initialForm(activeForm));
      setCreating(true);
    }}><Plus />{activeForm.createLabel ?? config.createLabel ?? `创建${activeTab?.label ?? config.singular}`}</Button>;
  })();

  function changeFormField(key: string, value: string) {
    setForm((current) => {
      const next = { ...current, [key]: value };
      const cleared = new Set([key]);
      let changed = true;
      while (changed) {
        changed = false;
        for (const field of activeForm?.fields ?? []) {
          if ((field.dependsOn ?? []).some((dependency) => cleared.has(dependency)) && next[field.key]) {
            next[field.key] = "";
            cleared.add(field.key);
            changed = true;
          }
        }
      }
      return next;
    });
  }

  function startEditingNickname(record: PlatformRecord) {
    setEditingNicknameId(String(record.id ?? ""));
    setEditingNicknameValue(String(valueOf(record, "nickname") ?? ""));
  }

  function submitNickname(record: PlatformRecord, nickname?: string) {
    const id = String(record.id ?? "");
    const original = String(valueOf(record, "nickname") ?? "");
    const next = (nickname ?? editingNicknameValue).trim();
    if (!id) return;
    if (!nickname && editingNicknameId !== id) return;
    if (!next) {
      toast.error("昵称不能为空");
      setEditingNicknameValue(original);
      return;
    }
    if (next === original) {
      setEditingNicknameId("");
      setEditingNicknameValue("");
      return;
    }
    nicknameMutation.mutate({ id, nickname: next });
  }

  function runAgentLifecycle(action: AgentLifecycleAction) {
    const id = String(detail?.id ?? "");
    if (!id) return;
    agentLifecycleMutation.mutate({ id, action });
  }

  function openTokenRenewal(record: PlatformRecord) {
    setRenewingToken(record);
    setRenewExpiresAt("");
  }

  return (
    <>
      <PageHeader
        eyebrow={config.eyebrow}
        title={config.title}
        description={config.description}
        actions={<><Button variant="secondary" onClick={() => resourceQuery.refetch()} disabled={resourceQuery.isFetching}><RefreshCw className={resourceQuery.isFetching ? "animate-spin" : ""} />刷新</Button>{createAction}</>}
      />

      {config.tabs ? (
        <div className="theme-panel scrollbar-thin mb-4 flex gap-1 overflow-x-auto rounded-xl border p-1">
          {config.tabs.map((tab) => (
            <button key={tab.endpoint} onClick={() => setActiveEndpoint(tab.endpoint)} className={`whitespace-nowrap rounded-lg px-3 py-2 text-sm font-medium transition ${activeEndpoint === tab.endpoint ? "bg-[var(--primary)] text-white shadow-sm" : "text-[color:var(--muted)] hover:bg-[var(--surface-muted)] hover:text-[color:var(--foreground)]"}`}>{tab.label}</button>
          ))}
        </div>
      ) : null}

      <Card className="overflow-hidden">
        <div className="flex flex-col gap-3 border-b p-4 sm:flex-row sm:items-center">
          <div className="relative max-w-md flex-1">
            <Search className="absolute left-3 top-2.5 size-4 text-slate-400" />
            <Input value={query} onChange={(event) => { setQuery(event.target.value); setPage(0); }} placeholder={`搜索${activeTab?.label ?? config.title}…`} className="pl-9" />
          </div>
          <div className="text-xs text-slate-400 sm:ml-auto">共 {resourceQuery.data?.total ?? 0} 条</div>
        </div>

        {resourceQuery.isLoading ? (
          <div className="space-y-3 p-5">{[1, 2, 3, 4].map((item) => <Skeleton key={item} className="h-12 w-full" />)}</div>
        ) : resourceQuery.isError ? (
          <div className="flex min-h-72 flex-col items-center justify-center p-8 text-center">
            <div className="mb-3 rounded-full bg-red-50 p-3 text-red-600"><X className="size-5" /></div>
            <h3 className="font-semibold text-slate-900">加载失败</h3>
            <p className="mt-1 text-sm text-slate-500">{resourceQuery.error instanceof Error ? resourceQuery.error.message : "无法读取数据"}</p>
            <Button className="mt-4" variant="secondary" onClick={() => resourceQuery.refetch()}>重新加载</Button>
          </div>
        ) : !rows.length ? (
          <EmptyState icon={config.icon} title={`暂无${activeTab?.label ?? config.singular}`} description={query ? "没有找到匹配的数据，请调整搜索条件。" : "当前还没有数据，可以从右上角的主操作开始。"} />
        ) : config.key === "applications" ? (
          <div className="scrollbar-thin overflow-x-auto">
            <table className="w-full min-w-[760px] text-left text-sm">
              <thead className="theme-muted-panel text-xs font-medium uppercase tracking-wide text-[color:var(--muted)]">
                <tr>
                  <th className="px-4 py-3">应用名称</th>
                  <th className="px-4 py-3">环境</th>
                  <th className="w-16 px-4 py-3"><span className="sr-only">展开</span></th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {applicationGroups.map((group) => {
                  const expanded = !collapsedApplicationGroups.has(group.key);
                  return (
                    <Fragment key={group.key}>
                      <tr
                        key={group.key}
                        className="theme-row cursor-pointer transition"
                        onClick={() => {
                          setCollapsedApplicationGroups((current) => {
                            const next = new Set(current);
                            if (next.has(group.key)) {
                              next.delete(group.key);
                            } else {
                              next.add(group.key);
                            }
                            return next;
                          });
                        }}
                      >
                        <td className="px-4 py-4">
                          <div className="flex items-center gap-3">
                            <ChevronDown className={`size-4 text-slate-400 transition ${expanded ? "rotate-0" : "-rotate-90"}`} />
                            <span className="font-semibold text-[color:var(--foreground)]">{group.applicationName}</span>
                          </div>
                        </td>
                        <td className="px-4 py-4 text-[color:var(--foreground)]">{group.environment}</td>
                        <td className="px-4 py-4 text-right text-xs text-slate-400" />
                      </tr>
                      {expanded ? (
                        <tr key={`${group.key}-instances`} className="bg-[var(--surface-subtle)]">
                          <td colSpan={3} className="px-4 pb-4 pt-0">
                            <div className="theme-panel scrollbar-thin overflow-x-auto rounded-xl border">
                              <table className="w-full min-w-[760px] text-left text-sm">
                                <thead className="theme-muted-panel text-xs font-medium uppercase tracking-wide text-[color:var(--muted)]">
                                  <tr>
                                    {columns.map((column) => <th key={column.key} className="px-4 py-3">{column.label}</th>)}
                                    <th className="w-16 px-4 py-3"><span className="sr-only">操作</span></th>
                                  </tr>
                                </thead>
                                <tbody className="divide-y">
                                  {group.instances.map((record, index) => (
                                    <tr key={String(record.id ?? `${group.key}-${index}`)} onClick={() => setSelected(record)} className="theme-row cursor-pointer transition">
                                      {columns.map((column, columnIndex) => (
                                        <td key={column.key} className="max-w-72 px-4 py-3.5">
                                          {column.key === "nickname" ? editingNicknameId === String(record.id ?? "") ? (
                                            <Input
                                              autoFocus
                                              value={editingNicknameValue}
                                              onClick={(event) => event.stopPropagation()}
                                              onChange={(event) => setEditingNicknameValue(event.target.value)}
                                              onBlur={() => submitNickname(record)}
                                              onKeyDown={(event) => {
                                                if (event.key === "Enter") {
                                                  event.currentTarget.blur();
                                                }
                                                if (event.key === "Escape") {
                                                  setEditingNicknameId("");
                                                  setEditingNicknameValue("");
                                                  event.currentTarget.blur();
                                                }
                                              }}
                                              disabled={nicknameMutation.isPending}
                                              aria-label="实例昵称"
                                              className="h-9 max-w-64 font-medium"
                                            />
                                          ) : (
                                            <button
                                              type="button"
                                              onClick={(event) => {
                                                event.stopPropagation();
                                                startEditingNickname(record);
                                              }}
                                              className="group/nickname inline-flex max-w-64 items-center gap-2 rounded-md px-2 py-1.5 text-left font-medium text-[color:var(--foreground)] transition hover:bg-[var(--surface-muted)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--focus-border)]"
                                              aria-label="编辑实例昵称"
                                              title="编辑昵称"
                                            >
                                              <span className="truncate">{String(valueOf(record, "nickname") ?? "—")}</span>
                                              <Pencil className="size-3.5 shrink-0 text-[color:var(--muted)] opacity-0 transition group-hover/nickname:opacity-100 group-focus-visible/nickname:opacity-100" />
                                            </button>
                                          ) : columnIndex === 0 ? <div className="font-medium text-[color:var(--foreground)]"><Cell value={valueOf(record, column.key)} column={column} record={record} /></div> : <Cell value={valueOf(record, column.key)} column={column} record={record} />}
                                        </td>
                                      ))}
                                      <td className="px-4 py-3.5 text-right"><Button variant="ghost" size="icon" aria-label="查看详情"><ChevronRight /></Button></td>
                                    </tr>
                                  ))}
                                </tbody>
                              </table>
                            </div>
                          </td>
                        </tr>
                      ) : null}
                    </Fragment>
                  );
                })}
              </tbody>
            </table>
          </div>
        ) : (
          <div className="scrollbar-thin overflow-x-auto">
            <table className={resourceTableClass(config.key)}>
              <thead className="theme-muted-panel text-xs font-medium uppercase tracking-wide text-[color:var(--muted)]">
                <tr>
                  {columns.map((column) => <th key={column.key} className={resourceHeaderCellClass(config.key, column.key)}>{column.label}</th>)}
                  <th className="w-16 whitespace-nowrap px-4 py-3"><span className="sr-only">操作</span></th>
                </tr>
              </thead>
              <tbody className="divide-y">
                {rows.map((record, index) => (
                  <tr
                    key={String(record.id ?? index)}
                    onClick={() => {
                      if (config.key === "rules" && record.id) {
                        router.push(`/rules/${record.id}`);
                        return;
                      }
                      setSelected(record);
                    }}
                    className="theme-row cursor-pointer transition"
                  >
                    {columns.map((column, columnIndex) => {
                      const cellValue = valueOf(record, column.key);
                      const content = config.key === "applications" && column.key === "nickname" ? editingNicknameId === String(record.id ?? "") ? (
                        <Input
                          autoFocus
                          value={editingNicknameValue}
                          onClick={(event) => event.stopPropagation()}
                          onChange={(event) => setEditingNicknameValue(event.target.value)}
                          onBlur={() => submitNickname(record)}
                          onKeyDown={(event) => {
                            if (event.key === "Enter") {
                              event.currentTarget.blur();
                            }
                            if (event.key === "Escape") {
                              setEditingNicknameId("");
                              setEditingNicknameValue("");
                              event.currentTarget.blur();
                            }
                          }}
                          disabled={nicknameMutation.isPending}
                          aria-label="实例昵称"
                          className="h-9 max-w-64 font-medium"
                        />
                      ) : (
                        <button
                          type="button"
                          onClick={(event) => {
                            event.stopPropagation();
                            startEditingNickname(record);
                          }}
                          className="group/nickname inline-flex max-w-64 items-center gap-2 rounded-md px-2 py-1.5 text-left font-medium text-[color:var(--foreground)] transition hover:bg-[var(--surface-muted)] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--focus-border)]"
                          aria-label="编辑实例昵称"
                          title="编辑昵称"
                        >
                          <span className="truncate">{String(valueOf(record, "nickname") ?? "—")}</span>
                          <Pencil className="size-3.5 shrink-0 text-[color:var(--muted)] opacity-0 transition group-hover/nickname:opacity-100 group-focus-visible/nickname:opacity-100" />
                        </button>
                      ) : columnIndex === 0 ? (
                        <div className="font-medium text-[color:var(--foreground)]"><Cell value={cellValue} column={column} record={record} /></div>
                      ) : (
                        <Cell value={cellValue} column={column} record={record} />
                      );
                      return (
                        <td key={column.key} className={resourceBodyCellClass(config.key, column.key)} title={cellTitle(cellValue)}>
                          <div className={resourceCellContentClass(config.key, column.key)}>{content}</div>
                        </td>
                      );
                    })}
                    <td className="px-4 py-3.5 text-right"><Button variant="ghost" size="icon" aria-label="查看详情"><ChevronRight /></Button></td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {(resourceQuery.data?.total ?? 0) > 25 ? (
          <div className="flex items-center justify-end gap-2 border-t p-3">
            <Button variant="secondary" size="sm" disabled={page === 0} onClick={() => setPage((value) => Math.max(0, value - 1))}><ChevronLeft />上一页</Button>
            <span className="text-xs text-slate-500">第 {page + 1} 页</span>
            <Button variant="secondary" size="sm" disabled={(page + 1) * 25 >= (resourceQuery.data?.total ?? 0)} onClick={() => setPage((value) => value + 1)}>下一页<ChevronRight /></Button>
          </div>
        ) : null}
      </Card>

      <Dialog open={Boolean(selected)} onOpenChange={(open) => !open && setSelected(null)}>
        <DialogContent className="right-0 left-auto top-0 flex h-screen max-h-screen w-full max-w-xl translate-x-0 translate-y-0 flex-col gap-0 overflow-hidden rounded-none p-0 sm:rounded-l-2xl">
          <div className="border-b p-6 pr-14">
            <p className="text-xs font-semibold uppercase tracking-[0.16em] text-indigo-600">{activeTab?.label ?? config.singular}详情</p>
            <DialogTitle className="mt-2 text-xl">{detailTitle(config.key, detail)}</DialogTitle>
            <DialogDescription className="mt-1">详情来自独立查询接口；敏感凭据和 Token 不会回显。</DialogDescription>
          </div>
          <div className="scrollbar-thin flex-1 overflow-y-auto p-6">
            {detailQuery.isLoading || ruleDetailQuery.isLoading ? <div className="space-y-3">{[1, 2, 3, 4].map((item) => <Skeleton key={item} className="h-12" />)}</div> : config.key === "applications" && detail ? (
              <div className="space-y-4">
                <ApplicationDetail
                  detail={detail}
                  nicknamePending={nicknameMutation.isPending}
                  onSubmitNickname={submitNickname}
                />
                {detail.id && !detailEnvironmentId ? (
                  <div className="rounded-xl border border-[color:var(--border)] bg-[var(--primary-soft)] p-4">
                    <p className="text-sm font-semibold text-indigo-950">为实例分配环境</p>
                    <p className="mt-1 text-xs leading-5 text-indigo-700">Agent 已完成真实运行时注册，选择 dev、sit 或 uat 后才会参与目标发现和规则发布。</p>
                    <Select value={assignmentEnvironment} onValueChange={setAssignmentEnvironment} disabled={assignmentOptionsQuery.isLoading}>
                      <SelectTrigger className="mt-3" aria-label="分配环境">
                        <SelectValue placeholder={assignmentOptionsQuery.isLoading ? "正在加载环境…" : "请选择环境"} />
                      </SelectTrigger>
                      <SelectContent>
                        {assignmentOptions.map((record, index) => {
                          const value = String(valueOf(record, "id") ?? "");
                          return value ? <SelectItem key={String(record.id ?? index)} value={value}>{resourceOptionLabel("environments", record)}</SelectItem> : null;
                        })}
                      </SelectContent>
                    </Select>
                  </div>
                ) : null}
              </div>
            ) : config.key === "rules" && detail ? (
              <RuleDetail detail={detail} ruleDetail={ruleDetailQuery.data} />
            ) : activeEndpoint === "rollout-executions" && detail ? (
              <RolloutExecutionDetail detail={detail} />
            ) : activeEndpoint === "tokens" && detail ? (
              <TokenDetail detail={detail} />
            ) : (
              <div className="grid gap-3">
                {detailEntries(detail, activeEndpoint).map(([key, value]) => {
                  const renderedValue = detailValue(key, value, detail);
                  const isRawId = key.toLowerCase() === "id" || String(renderedValue).match(/^[a-z]+-[0-9a-f-]{24,}$/);
                  const label = detailLabel(activeEndpoint, key);
                  const renderedText = detailText(activeEndpoint, key, renderedValue);
                  return (
                  <div key={key} className="theme-muted-panel grid grid-cols-[96px_minmax(0,1fr)] gap-2 rounded-lg border px-3 py-2.5 text-sm">
                    <span className="text-[color:var(--muted)]">{label}</span>
                    <span className={`break-all whitespace-pre-wrap text-[color:var(--foreground)] ${isRawId ? "font-mono text-xs" : ""}`}>
                      {renderedText}
                    </span>
                  </div>
                );})}
              </div>
            )}
          </div>
          <div className="theme-panel flex flex-wrap items-center gap-2 border-t p-4">
            <span className="mr-auto font-mono text-xs text-slate-400">{activeEndpoint !== "tokens" && detail?.id ? shortId(String(detail.id)) : ""}</span>
            {can(transitionCapability[activeEndpoint] ?? "__UNAVAILABLE__")
              ? allowedActions.map((action) => action === "UNLOAD" || action === "UNLOAD_PLAN"
                ? <Button key={action} variant="secondary" onClick={() => setConfirmingUnload(true)}><RotateCcw />{actionLabel(action)}</Button>
                : <Button key={action} variant={action.includes("CANCEL") || action === "FAILED" ? "secondary" : "default"} onClick={() => transition(action)}>{actionLabel(action)}</Button>)
              : null}
            {activeEndpoint === "tokens" && detail?.id && can("ADMIN") ? (
              <Button variant="secondary" onClick={() => openTokenRenewal(detail)}>
                <CalendarClock />
                续期
              </Button>
            ) : null}
            {config.key === "applications" && detail?.id && !detailEnvironmentId ? (
              <Button onClick={() => assignmentMutation.mutate()} disabled={!assignmentEnvironment || assignmentMutation.isPending}>
                {assignmentMutation.isPending ? "正在分配…" : "确认分配环境"}
              </Button>
            ) : null}
            {config.key === "applications" && detail?.id && can("AGENT_MANAGE") ? (
              <>
                <Button
                  variant="secondary"
                  onClick={() => runAgentLifecycle("attach")}
                  disabled={agentLifecycleMutation.isPending}
                >
                  {agentLifecycleMutation.isPending ? "执行中…" : "加载 Agent"}
                </Button>
                <Button
                  variant="secondary"
                  onClick={() => runAgentLifecycle("deactivate")}
                  disabled={!detailAgentCanDeactivate || agentLifecycleMutation.isPending}
                >
                  停用 Agent
                </Button>
                <Button
                  onClick={() => runAgentLifecycle("reload")}
                  disabled={agentLifecycleMutation.isPending}
                >
                  <RefreshCw />
                  重新加载
                </Button>
              </>
            ) : null}
            {config.key === "rules" && detail?.id ? <Button asChild><Link href={`/rules/${detail.id}`}>创建新版本</Link></Button> : null}
          </div>
        </DialogContent>
      </Dialog>

      <Dialog open={confirmingUnload} onOpenChange={setConfirmingUnload}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>确认卸载该发布计划？</DialogTitle>
            <DialogDescription>
              平台会向该计划所有成功发布实例的在线 Agent 下发卸载命令，清除对应规则并恢复目标类的原始字节码。规则定义会保留，方便后续重新发布。
            </DialogDescription>
          </DialogHeader>
          <div className="rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm leading-6 text-amber-900">
            这是运行时变更操作。命令全部得到 Agent 确认后，计划状态才会变为“已卸载”；任一实例失败则标记为“失败”。
          </div>
          <DialogFooter>
            <Button variant="secondary" onClick={() => setConfirmingUnload(false)} disabled={unloadMutation.isPending}>暂不卸载</Button>
            <Button onClick={() => unloadMutation.mutate()} disabled={unloadMutation.isPending}>
              <RotateCcw />
              {unloadMutation.isPending ? "正在下发…" : "确认卸载并恢复字节码"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={Boolean(renewingToken)} onOpenChange={(open) => {
        if (!open) {
          setRenewingToken(null);
          setRenewExpiresAt("");
        }
      }}>
        <DialogContent
          className="max-w-md overflow-visible"
          onInteractOutside={(event) => event.preventDefault()}
        >
          <DialogHeader>
            <DialogTitle>续期 Token</DialogTitle>
            <DialogDescription>只更新该 Token 的过期时间，不会重新展示明文 Token；不选时间即长期有效。</DialogDescription>
          </DialogHeader>
          <form onSubmit={(event) => { event.preventDefault(); renewTokenMutation.mutate(); }} className="space-y-4">
            <div className="block">
              <span className="mb-1.5 block text-sm font-medium text-slate-700">过期时间</span>
              <DateTimePicker
                aria-label="过期时间"
                value={renewExpiresAt}
                onChange={setRenewExpiresAt}
                inline
              />
            </div>
            <DialogFooter>
              <Button
                type="button"
                variant="secondary"
                onClick={() => {
                  setRenewingToken(null);
                  setRenewExpiresAt("");
                }}
                disabled={renewTokenMutation.isPending}
              >
                取消
              </Button>
              <Button type="submit" disabled={renewTokenMutation.isPending}>
                {renewTokenMutation.isPending ? "正在续期…" : "确认续期"}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <Dialog open={creating} onOpenChange={setCreating}>
        <DialogContent
          className="max-h-[90vh] overflow-visible"
          onInteractOutside={(event) => {
            const target = event.target;
            if (target instanceof HTMLElement && target.closest("[data-runtime-mock-date-picker]")) {
              return;
            }
            event.preventDefault();
          }}
        >
          <DialogHeader>
            <DialogTitle>{activeForm?.createLabel ?? config.createLabel ?? `创建${activeTab?.label ?? config.singular}`}</DialogTitle>
            <DialogDescription>字段与 Platform API DTO 完全一致；JSON 字段会在提交前解析。</DialogDescription>
          </DialogHeader>
          <form onSubmit={(event) => { event.preventDefault(); createMutation.mutate(); }} className="space-y-4">
            {(activeForm?.fields ?? []).map((field) => (
              field.type === "hidden" ? (
                <input key={field.key} type="hidden" name={field.key} value={form[field.key] ?? ""} />
              ) : <div key={field.key} className="block">
                <span className="mb-1.5 block text-sm font-medium text-slate-700">{field.label}{field.required ? <span className="text-red-500"> *</span> : null}</span>
                {field.type === "json" || field.type === "textarea" ? (
                  <Textarea aria-label={field.label} required={field.required} value={form[field.key] ?? ""} onChange={(event) => changeFormField(field.key, event.target.value)} placeholder={field.placeholder} className={field.type === "json" ? "min-h-24 font-mono text-xs" : ""} />
                ) : field.type === "resource" ? (
                  <ResourceSelectField field={field} form={form} onValueChange={(value) => changeFormField(field.key, value)} />
                ) : field.type === "target" ? (
                  <TargetSelectField field={field} form={form} onValueChange={(value) => changeFormField(field.key, value)} />
                ) : field.type === "select" ? (
                  <Select value={form[field.key] ?? ""} onValueChange={(value) => changeFormField(field.key, value)}>
                    <SelectTrigger aria-label={field.label}>
                      <SelectValue placeholder={field.placeholder ?? `请选择${field.label}`} />
                    </SelectTrigger>
                    <SelectContent>
                      {field.options?.map((option) => <SelectItem key={option} value={option}>{humanize(option)}</SelectItem>)}
                    </SelectContent>
                  </Select>
                ) : field.type === "number" ? (
                  <NumberInput
                    aria-label={field.label}
                    required={field.required}
                    min={0}
                    value={form[field.key] ?? ""}
                    onValueChange={(value) => changeFormField(field.key, value)}
                    placeholder={field.placeholder}
                  />
                ) : field.type === "date-time" ? (
                  <DateTimePicker
                    aria-label={field.label}
                    required={field.required}
                    value={form[field.key] ?? ""}
                    onChange={(value) => changeFormField(field.key, value)}
                  />
                ) : (
                  <Input aria-label={field.label} type="text" required={field.required} value={form[field.key] ?? ""} onChange={(event) => changeFormField(field.key, event.target.value)} placeholder={field.placeholder} />
                )}
              </div>
            ))}
            <DialogFooter>
              <Button type="button" variant="secondary" onClick={() => setCreating(false)}>取消</Button>
              <Button type="submit" disabled={createMutation.isPending}>{createMutation.isPending ? "正在提交…" : "确认创建"}</Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      <Dialog open={Boolean(created?.token)} onOpenChange={(open) => !open && setCreated(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Token 已签发</DialogTitle>
            <DialogDescription>这是唯一一次显示明文 Token，请立即复制并安全保存。</DialogDescription>
          </DialogHeader>
          <div className="rounded-lg border border-amber-200 bg-amber-50 p-4 font-mono text-xs break-all text-amber-950">{String(created?.token ?? "")}</div>
          <DialogFooter><Button onClick={async () => { await navigator.clipboard.writeText(String(created?.token ?? "")); toast.success("Token 已复制"); }}><Copy />复制 Token</Button></DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
