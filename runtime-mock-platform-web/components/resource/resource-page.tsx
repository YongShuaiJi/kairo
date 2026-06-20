"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Check, ChevronLeft, ChevronRight, Copy, Plus, RefreshCw, RotateCcw, Search, X } from "lucide-react";
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
import { NumberInput } from "@/components/ui/number-input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Textarea } from "@/components/ui/textarea";

type PagedResult = { items: PlatformRecord[]; page: number; size: number; total: number };

function valueOf(record: PlatformRecord, key: string) {
  const snake = key.replace(/[A-Z]/g, (letter) => `_${letter.toLowerCase()}`);
  return record[key] ?? record[snake];
}

function statusVariant(status: string) {
  const value = status.toUpperCase();
  if (["UP", "ONLINE", "ACTIVE", "READY", "RUNNING", "SUCCESS", "SUCCEEDED", "COMPLETED", "APPROVED", "PUBLISHED", "AVAILABLE", "HEALTHY", "MATCHED", "ACKED"].includes(value)) return "success" as const;
  if (["PENDING", "WAITING_APPROVAL", "SCHEDULED", "BUILDING", "RETRYING", "RECORDING", "PARTIAL", "MEDIUM", "QUEUED"].includes(value)) return "warning" as const;
  if (["FAILED", "OFFLINE", "REJECTED", "ERROR", "HIGH", "DIFF", "CANCELLED"].includes(value)) return "danger" as const;
  if (["DRAFT", "PAUSED", "ARCHIVED", "LOW", "ROLLED_BACK"].includes(value)) return "neutral" as const;
  return "info" as const;
}

function Cell({ value, column }: { value: unknown; column: ResourceColumn }) {
  if (value === null || value === undefined || value === "") return <span className="text-slate-300">—</span>;
  if (column.kind === "status") return <Badge variant={statusVariant(String(value))}>{humanize(String(value))}</Badge>;
  if (column.kind === "date") return <span className="whitespace-nowrap text-slate-500">{formatDate(String(value))}</span>;
  if (column.kind === "bytes") return <span>{formatBytes(Number(value))}</span>;
  if (column.kind === "progress") {
    const progress = Number(value);
    return <div className="flex min-w-28 items-center gap-2"><div className="h-1.5 flex-1 overflow-hidden rounded-full bg-slate-100"><div className="h-full rounded-full bg-indigo-500" style={{ width: `${Math.min(100, progress)}%` }} /></div><span className="w-8 text-right text-xs text-slate-500">{progress}%</span></div>;
  }
  const rendered = Array.isArray(value)
    ? value.map((item) => humanize(item)).join(", ")
    : typeof value === "object"
      ? humanize(value)
      : column.kind === "mono"
        ? String(value)
        : humanize(value);
  return <span className={column.kind === "mono" ? "font-mono text-xs text-slate-600" : ""}>{rendered}</span>;
}

function initialForm(form: ResourceForm | undefined) {
  return Object.fromEntries((form?.fields ?? []).map((field) => [field.key, field.defaultValue ?? ""]));
}

function resourceOptionValue(source: ResourceField["source"], record: PlatformRecord) {
  if (source === "rule-versions") return String(valueOf(record, "version") ?? "");
  return String(valueOf(record, "id") ?? "");
}

function resourceOptionLabel(source: ResourceField["source"], record: PlatformRecord) {
  const id = String(valueOf(record, "id") ?? "");
  const name = String(valueOf(record, "name") ?? id);
  if (source === "environments") {
    const type = String(valueOf(record, "type") ?? name);
    return `${humanize(type)}（${shortId(id)}）`;
  }
  if (source === "rule-versions") {
    return `版本 ${String(valueOf(record, "version") ?? "—")} · ${humanize(valueOf(record, "status"))}`;
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
    if (field.source === "environments") {
      const applicationField = field.dependsOn?.find((key) => key.toLowerCase().includes("application")) ?? "applicationId";
      return String(valueOf(record, "applicationId") ?? "") === form[applicationField]
        && ["DEV", "SIT", "UAT", "PROD"].includes(String(valueOf(record, "type") ?? "").toUpperCase());
    }
    if (field.source === "rules") {
      return String(valueOf(record, "applicationId") ?? "") === form.applicationId
        && String(valueOf(record, "environmentId") ?? "") === form.environmentId;
    }
    if (field.source === "rule-versions") {
      return String(valueOf(record, "ruleId") ?? "") === form.resourceId;
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

export function ResourcePage({ resourceKey }: { resourceKey: string }) {
  const config = resourceConfigs[resourceKey];
  const queryClient = useQueryClient();
  const [query, setQuery] = useState("");
  const [page, setPage] = useState(0);
  const [activeEndpoint, setActiveEndpoint] = useState(config.endpoint);
  const [selected, setSelected] = useState<PlatformRecord | null>(null);
  const [creating, setCreating] = useState(false);
  const [created, setCreated] = useState<PlatformRecord | null>(null);
  const [assignmentEnvironment, setAssignmentEnvironment] = useState("");
  const [confirmingUnload, setConfirmingUnload] = useState(false);

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
    "recording-sessions": "RECORD_ARGUMENTS",
    "extraction-tasks": "DATA_EXTRACT",
    "replay-plans": "IMPORT_TO_TEST",
    "replay-executions": "REPLAY_EXECUTE",
  };

  const resourceQuery = useQuery({
    queryKey: ["resource", activeEndpoint, page, query],
    queryFn: () => platformFetch<PagedResult>(`query/${activeEndpoint}?page=${page}&size=25&q=${encodeURIComponent(query.trim())}`),
    refetchInterval: activeEndpoint === "agents" ? 15_000 : false,
  });
  const rows = resourceQuery.data?.items ?? [];

  const detailQuery = useQuery({
    queryKey: ["detail", activeEndpoint, selected?.id],
    queryFn: () => platformFetch<PlatformRecord>(`details/${activeEndpoint}/${selected?.id}`),
    enabled: Boolean(selected?.id),
  });
  const detail = detailQuery.data ?? selected;
  const allowedActions = Array.isArray(detail?.allowed_actions) ? detail.allowed_actions.map(String) : [];
  const detailApplicationId = String(valueOf(detail ?? {}, "applicationId") ?? "");
  const detailEnvironmentId = String(valueOf(detail ?? {}, "environmentId") ?? "");

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
      && ["DEV", "SIT", "UAT", "PROD"].includes(String(valueOf(record, "type") ?? "").toUpperCase()),
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

  async function decide(decision: "APPROVED" | "REJECTED") {
    if (!detail?.id) return;
    try {
      await platformFetch(`approvals/${detail.id}/decisions`, {
        method: "POST",
        body: JSON.stringify({ decision, reason: decision === "APPROVED" ? "通过 Web 控制台批准" : "通过 Web 控制台拒绝" }),
        idempotencyKey: crypto.randomUUID(),
      });
      toast.success(decision === "APPROVED" ? "审批已通过" : "审批已拒绝");
      setSelected(null);
      await queryClient.invalidateQueries({ queryKey: ["resource", activeEndpoint] });
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "审批操作失败");
    }
  }

  async function transition(targetStatus: string) {
    if (!detail?.id || !detail.status || detail.version === undefined) return;
    const reason = `通过 Web 控制台执行：${actionLabel(targetStatus)}`;
    const resourceType: Record<string, string> = {
      "operation-plans": "operation_plan",
      "recording-sessions": "recording_session",
      "extraction-tasks": "extraction_task",
      "replay-plans": "replay_plan",
      "replay-executions": "replay_execution",
    };
    const transitionPath: Record<string, string> = {
      "operation-plans": "operation-plans",
      "recording-sessions": "recording-sessions",
      "extraction-tasks": "extraction-tasks",
      "replay-plans": "replay-plans",
      "replay-executions": "replay-executions",
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
    return <Button onClick={() => setCreating(true)}><Plus />{activeForm.createLabel ?? config.createLabel ?? `创建${activeTab?.label ?? config.singular}`}</Button>;
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

  return (
    <>
      <PageHeader
        eyebrow={config.eyebrow}
        title={config.title}
        description={config.description}
        actions={<><Button variant="secondary" onClick={() => resourceQuery.refetch()} disabled={resourceQuery.isFetching}><RefreshCw className={resourceQuery.isFetching ? "animate-spin" : ""} />刷新</Button>{createAction}</>}
      />

      {config.tabs ? (
        <div className="mb-4 flex gap-1 overflow-x-auto rounded-xl border bg-white p-1">
          {config.tabs.map((tab) => (
            <button key={tab.endpoint} onClick={() => setActiveEndpoint(tab.endpoint)} className={`whitespace-nowrap rounded-lg px-3 py-2 text-sm font-medium transition ${activeEndpoint === tab.endpoint ? "bg-slate-900 text-white shadow-sm" : "text-slate-500 hover:bg-slate-100 hover:text-slate-900"}`}>{tab.label}</button>
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
        ) : (
          <div className="scrollbar-thin overflow-x-auto">
            <table className="w-full min-w-[760px] text-left text-sm">
              <thead className="bg-slate-50/80 text-xs font-medium uppercase tracking-wide text-slate-500">
                <tr>{columns.map((column) => <th key={column.key} className="px-4 py-3">{column.label}</th>)}<th className="w-16 px-4 py-3"><span className="sr-only">操作</span></th></tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {rows.map((record, index) => (
                  <tr key={String(record.id ?? index)} onClick={() => setSelected(record)} className="cursor-pointer bg-white transition hover:bg-indigo-50/35">
                    {columns.map((column, columnIndex) => (
                      <td key={column.key} className="max-w-72 px-4 py-3.5">
                        {columnIndex === 0 ? <div className="font-medium text-slate-900"><Cell value={valueOf(record, column.key)} column={column} /></div> : <Cell value={valueOf(record, column.key)} column={column} />}
                      </td>
                    ))}
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
        <DialogContent className="right-0 left-auto top-0 h-screen max-h-screen w-full max-w-xl translate-x-0 translate-y-0 rounded-none p-0 sm:rounded-l-2xl">
          <div className="border-b p-6 pr-14">
            <p className="text-xs font-semibold uppercase tracking-[0.16em] text-indigo-600">{activeTab?.label ?? config.singular}详情</p>
            <DialogTitle className="mt-2 text-xl">{String(detail?.name ?? detail?.subject_id ?? detail?.id ?? "详情")}</DialogTitle>
            <DialogDescription className="mt-1">详情来自独立查询接口；敏感凭据和 Token 不会回显。</DialogDescription>
          </div>
          <div className="scrollbar-thin flex-1 overflow-y-auto p-6">
            {detailQuery.isLoading ? <div className="space-y-3">{[1, 2, 3, 4].map((item) => <Skeleton key={item} className="h-12" />)}</div> : (
              <div className="grid gap-3">
                {detail ? Object.entries(detail).filter(([key]) => key !== "allowed_actions").map(([key, value]) => (
                  <div key={key} className="grid grid-cols-[130px_1fr] gap-4 rounded-lg border border-slate-100 bg-slate-50/60 px-3 py-2.5 text-sm">
                    <span className="text-slate-500">{fieldLabel(key)}</span>
                    <span className={`break-all whitespace-pre-wrap text-slate-800 ${key.toLowerCase().includes("id") ? "font-mono text-xs" : ""}`}>
                      {key.toLowerCase().endsWith("_at") || key.toLowerCase().endsWith("at")
                        ? formatDate(value)
                        : humanize(value)}
                    </span>
                  </div>
                )) : null}
                {config.key === "applications" && detail?.id && !detailEnvironmentId ? (
                  <div className="mt-2 rounded-xl border border-indigo-100 bg-indigo-50/60 p-4">
                    <p className="text-sm font-semibold text-indigo-950">为实例分配环境</p>
                    <p className="mt-1 text-xs leading-5 text-indigo-700">Agent 已完成真实运行时注册，选择 DEV、SIT、UAT 或 PROD 后才会参与目标发现和规则发布。</p>
                    <Select value={assignmentEnvironment} onValueChange={setAssignmentEnvironment} disabled={assignmentOptionsQuery.isLoading}>
                      <SelectTrigger className="mt-3 bg-white" aria-label="分配环境">
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
            )}
          </div>
          <div className="flex flex-wrap items-center gap-2 border-t bg-white p-4">
            <span className="mr-auto font-mono text-xs text-slate-400">{detail?.id ? shortId(String(detail.id)) : ""}</span>
            {config.key === "approvals" && String(detail?.status).toUpperCase() === "WAITING_APPROVAL" && can("APPROVE") ? (
              <><Button variant="secondary" onClick={() => decide("REJECTED")}><X />拒绝</Button><Button onClick={() => decide("APPROVED")}><Check />批准</Button></>
            ) : null}
            {can(transitionCapability[activeEndpoint] ?? "__UNAVAILABLE__")
              ? allowedActions.map((action) => action === "UNLOAD" || action === "UNLOAD_PLAN"
                ? <Button key={action} variant="secondary" onClick={() => setConfirmingUnload(true)}><RotateCcw />{actionLabel(action)}</Button>
                : <Button key={action} variant={action.includes("CANCEL") || action === "FAILED" ? "secondary" : "default"} onClick={() => transition(action)}>{actionLabel(action)}</Button>)
              : null}
            {config.key === "applications" && detail?.id && !detailEnvironmentId ? (
              <Button onClick={() => assignmentMutation.mutate()} disabled={!assignmentEnvironment || assignmentMutation.isPending}>
                {assignmentMutation.isPending ? "正在分配…" : "确认分配环境"}
              </Button>
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
              平台会向该计划所有成功发布实例的在线 Agent 下发卸载命令，清除对应规则并恢复目标类的原始字节码。规则定义和审计记录会保留。
            </DialogDescription>
          </DialogHeader>
          <div className="rounded-xl border border-amber-200 bg-amber-50 p-4 text-sm leading-6 text-amber-900">
            这是运行时变更操作。命令全部得到 Agent 确认后，计划状态才会变为“已回滚”；任一实例失败则标记为“失败”。
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

      <Dialog open={creating} onOpenChange={setCreating}>
        <DialogContent className="max-h-[90vh] overflow-y-auto">
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
