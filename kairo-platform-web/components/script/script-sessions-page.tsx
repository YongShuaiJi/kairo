"use client";

import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AlertTriangle, FlaskConical, Play, RefreshCw, Rocket, Trash2, CheckCircle2 } from "lucide-react";
import { toast } from "sonner";
import { listResource } from "@/lib/api/client";
import {
  compileScript,
  createScriptSession,
  effectiveTier,
  getScriptPolicy,
  getScriptSession,
  getScriptSessionEvents,
  listScriptSessions,
  promoteScriptSession,
  revertScriptSession,
  TIER_LABELS,
  updateScriptPolicy,
  validateScriptSession,
  applyScriptSession,
  type CapabilityProfile,
  type CreateScriptSessionRequest,
  type ScriptCompilationResult,
  type ScriptDiagnostic,
  type ScriptSessionDetail,
  type ScriptSessionEvent,
  type ScriptSessionStatus,
  type ScriptPolicy,
} from "@/lib/api/script";
import { recordValue as valueOf } from "@/lib/api/record";
import { formatDate, humanize } from "@/lib/utils";
import { PageHeader } from "@/components/layout/page-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { EmptyState } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import { NumberInput } from "@/components/ui/number-input";
import { SegmentedControl } from "@/components/ui/segmented-control";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableContainer, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { Textarea } from "@/components/ui/textarea";

const TIERS: CapabilityProfile[] = ["SAFE", "EXTENDED", "UNRESTRICTED"];

function statusVariant(status: ScriptSessionStatus) {
  switch (status) {
    case "APPLIED":
      return "success" as const;
    case "CREATED":
    case "VALIDATED":
      return "info" as const;
    case "EXPIRED":
    case "REVERTED":
      return "neutral" as const;
    case "FAILED":
      return "danger" as const;
  }
}

function tierVariant(profile: CapabilityProfile) {
  switch (profile) {
    case "SAFE":
      return "success" as const;
    case "EXTENDED":
      return "warning" as const;
    case "UNRESTRICTED":
      return "danger" as const;
  }
}

function statusLabel(status: ScriptSessionStatus) {
  switch (status) {
    case "CREATED":
      return "已创建";
    case "VALIDATED":
      return "已校验";
    case "APPLIED":
      return "已生效";
    case "EXPIRED":
      return "已过期";
    case "REVERTED":
      return "已撤销";
    case "FAILED":
      return "失败";
  }
}

function remainingTtl(session: ScriptSessionDetail): string {
  if (session.status === "EXPIRED" || session.status === "REVERTED") return "-";
  const remaining = session.expiresAt - Date.now();
  if (remaining <= 0) return "已到期";
  if (remaining < 60_000) return `${Math.ceil(remaining / 1000)} 秒`;
  return `${Math.floor(remaining / 60_000)} 分 ${Math.ceil((remaining % 60_000) / 1000)} 秒`;
}

function targetLabel(session: ScriptSessionDetail) {
  return `${session.target.className}#${session.target.methodName}`;
}

function diagnosticVariant(severity: ScriptDiagnostic["severity"]) {
  switch (severity) {
    case "ERROR":
      return "danger" as const;
    case "WARNING":
      return "warning" as const;
    case "INFO":
      return "info" as const;
  }
}

export function ScriptSessionsPage() {
  const queryClient = useQueryClient();
  const [applicationId, setApplicationId] = useState<string>("");
  const [createOpen, setCreateOpen] = useState(false);
  const [compileOpen, setCompileOpen] = useState(false);
  const [selectedSessionId, setSelectedSessionId] = useState<string | null>(null);

  const applicationsQuery = useQuery({
    queryKey: ["script-applications"],
    queryFn: async () => {
      const rows = await listResource("instances");
      const ids = new Set<string>();
      rows.forEach((row) => {
        const id = String(valueOf(row, "applicationId") ?? valueOf(row, "application") ?? "").trim();
        if (id) ids.add(id);
      });
      return Array.from(ids).sort();
    },
  });

  const effectiveAppId = applicationId || (applicationsQuery.data?.[0] ?? "");

  const sessionsQuery = useQuery({
    queryKey: ["script-sessions", effectiveAppId],
    queryFn: () => listScriptSessions(effectiveAppId || undefined),
    enabled: applicationsQuery.isFetched,
  });

  const policyQuery = useQuery({
    queryKey: ["script-policy", effectiveAppId],
    queryFn: () => getScriptPolicy(effectiveAppId),
    enabled: Boolean(effectiveAppId),
  });

  const invalidateAll = () => {
    void queryClient.invalidateQueries({ queryKey: ["script-sessions"] });
    void queryClient.invalidateQueries({ queryKey: ["script-policy"] });
  };

  const lifecycleMutation = useMutation({
    mutationFn: async ({ action, id }: { action: "validate" | "apply" | "promote" | "revert"; id: string }) => {
      switch (action) {
        case "validate":
          return validateScriptSession(id);
        case "apply":
          return applyScriptSession(id);
        case "promote":
          return promoteScriptSession(id);
        case "revert":
          return revertScriptSession(id);
      }
    },
    onSuccess: (data, vars) => {
      toast.success(`${statusLabel(data.status)}`);
      void queryClient.invalidateQueries({ queryKey: ["script-sessions"] });
      void queryClient.invalidateQueries({ queryKey: ["script-session", vars.id] });
      void queryClient.invalidateQueries({ queryKey: ["script-session-events", vars.id] });
    },
    onError: (error: unknown) => {
      const message = error instanceof Error ? error.message : "操作失败";
      toast.error(message);
    },
  });

  return (
    <div>
      <PageHeader
        eyebrow="V1.2 脚本能力"
        title="脚本会话"
        description="在真实 JVM 上以 SAFE / EXTENDED / UNRESTRICTED 三档试用脚本，受 TTL 与命中上限约束，可提升为正式规则。"
        actions={
          <>
            <Button variant="outline" onClick={() => setCompileOpen(true)}>
              <FlaskConical className="size-4" />
              编译试运行
            </Button>
            <Button
              onClick={() => setCreateOpen(true)}
              disabled={!effectiveAppId && !sessionsQuery.data?.length}
            >
              <Play className="size-4" />
              新建试用会话
            </Button>
          </>
        }
      />

      <SecurityNotesBanner />

      <PolicyCard
        applicationId={effectiveAppId}
        applications={applicationsQuery.data ?? []}
        applicationsLoading={applicationsQuery.isLoading}
        onApplicationChange={setApplicationId}
        policy={policyQuery.data}
        policyLoading={policyQuery.isLoading}
        onSaved={invalidateAll}
      />

      <Card className="mt-6 p-4">
        <div className="mb-3 flex items-center justify-between">
          <h2 className="text-sm font-semibold text-slate-900">试用会话</h2>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => sessionsQuery.refetch()}
            disabled={sessionsQuery.isFetching}
          >
            <RefreshCw className={`size-4 ${sessionsQuery.isFetching ? "animate-spin" : ""}`} />
            刷新
          </Button>
        </div>
        {sessionsQuery.isLoading ? (
          <Skeleton className="h-40 w-full" />
        ) : sessionsQuery.data && sessionsQuery.data.length > 0 ? (
          <TableContainer>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>会话 / 目标</TableHead>
                  <TableHead>有效档位</TableHead>
                  <TableHead>状态</TableHead>
                  <TableHead>命中 / 上限</TableHead>
                  <TableHead>TTL 剩余</TableHead>
                  <TableHead>到期时间</TableHead>
                  <TableHead>操作</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {sessionsQuery.data.map((session) => (
                  <TableRow key={session.sessionId}>
                    <TableCell>
                      <div className="font-mono text-xs text-slate-900">{session.sessionId}</div>
                      <div className="mt-0.5 max-w-[320px] truncate font-mono text-[11px] text-slate-500" title={targetLabel(session)}>
                        {targetLabel(session)}
                      </div>
                    </TableCell>
                    <TableCell>
                      <Badge variant={tierVariant(session.effectiveProfile)}>{session.effectiveProfile}</Badge>
                      {session.effectiveProfile !== session.requestedProfile ? (
                        <div className="mt-1 text-[10px] text-slate-400">请求 {session.requestedProfile}</div>
                      ) : null}
                    </TableCell>
                    <TableCell>
                      <Badge variant={statusVariant(session.status)}>{statusLabel(session.status)}</Badge>
                    </TableCell>
                    <TableCell className="font-mono text-xs">
                      {session.hitCount} / {session.maxHits}
                    </TableCell>
                    <TableCell className="text-xs">{remainingTtl(session)}</TableCell>
                    <TableCell className="text-xs text-slate-500">{formatDate(session.expiresAt)}</TableCell>
                    <TableCell>
                      <Button variant="ghost" size="sm" className="h-auto p-0" onClick={() => setSelectedSessionId(session.sessionId)}>
                        详情
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </TableContainer>
        ) : (
          <EmptyState
            icon={FlaskConical}
            title="暂无试用会话"
            description={effectiveAppId ? "该应用还没有在真实 JVM 上试用的脚本会话。" : "选择一个应用以查看其试用会话。"}
          />
        )}
      </Card>

      <CreateSessionDialog
        open={createOpen}
        onOpenChange={setCreateOpen}
        defaultApplicationId={effectiveAppId}
        policy={policyQuery.data}
        onCreated={(id) => {
          setCreateOpen(false);
          setSelectedSessionId(id);
          invalidateAll();
        }}
      />

      <CompileConsoleDialog open={compileOpen} onOpenChange={setCompileOpen} />

      <SessionDetailDialog
        sessionId={selectedSessionId}
        onOpenChange={(open) => !open && setSelectedSessionId(null)}
        onLifecycle={(action, id) => lifecycleMutation.mutate({ action, id })}
        pending={lifecycleMutation.isPending}
      />
    </div>
  );
}

function SecurityNotesBanner() {
  return (
    <Card className="mt-6 border-amber-200 bg-amber-50/50 p-4">
      <div className="flex gap-3">
        <AlertTriangle className="size-5 shrink-0 text-amber-600" />
        <div className="space-y-1.5 text-xs leading-5 text-amber-900">
          <p className="font-semibold">安全说明（§3.6 / §2.3）</p>
          <ul className="ml-4 list-disc space-y-1">
            <li>有效档位为 <code className="rounded bg-amber-100 px-1">min(平台上限, 应用上限, 请求档位)</code>，由 Platform 计算并下发，Agent 二次校验。</li>
            <li>试用会话默认单实例、低命中上限、短 TTL；Agent 独立保存截止时间，Platform 断开后仍能自动失效。</li>
            <li><strong>无法安全强杀任意 Java 线程</strong>：超时后取消 Future、熔断规则并阻止后续命中，但 UNRESTRICTED 脚本自建线程或阻塞 native IO 只能通过隔离、告警与规则停用治理。</li>
            <li>提升为正式规则不会扩大权限或作用范围：复用同一档位、策略版本、目标与脚本，仅去掉 TTL 与命中上限。</li>
          </ul>
        </div>
      </div>
    </Card>
  );
}

function PolicyCard({
  applicationId,
  applications,
  applicationsLoading,
  onApplicationChange,
  policy,
  policyLoading,
  onSaved,
}: {
  applicationId: string;
  applications: string[];
  applicationsLoading: boolean;
  onApplicationChange: (id: string) => void;
  policy?: ScriptPolicy;
  policyLoading: boolean;
  onSaved: () => void;
}) {
  const [editing, setEditing] = useState(false);
  const [draftMax, setDraftMax] = useState<CapabilityProfile>("SAFE");
  const [submitting, setSubmitting] = useState(false);

  useMemo(() => {
    if (policy) setDraftMax(policy.applicationMaxProfile);
  }, [policy]);

  async function save() {
    if (!applicationId || !policy) return;
    setSubmitting(true);
    try {
      await updateScriptPolicy(applicationId, {
        allowedMaxProfile: draftMax,
        expectedRevision: policy.hasApplicationPolicy ? policy.revision : undefined,
      });
      toast.success("应用脚本能力上限已更新");
      setEditing(false);
      onSaved();
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "更新失败");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Card className="mt-6 p-4">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div className="min-w-[240px]">
          <label className="mb-1 block text-xs font-medium text-slate-500">应用</label>
          <Select value={applicationId} onValueChange={onApplicationChange} disabled={applicationsLoading}>
            <SelectTrigger>
              <SelectValue placeholder={applicationsLoading ? "加载中..." : "选择应用"} />
            </SelectTrigger>
            <SelectContent>
              {applications.map((id) => (
                <SelectItem key={id} value={id}>
                  {id}
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>
        {policy && !editing ? (
          <Button variant="outline" size="sm" onClick={() => setEditing(true)}>
            调整应用上限
          </Button>
        ) : null}
      </div>

      {applicationId ? (
        policyLoading ? (
          <Skeleton className="mt-4 h-24 w-full" />
        ) : policy ? (
          <div className="mt-4 grid grid-cols-2 gap-3 sm:grid-cols-4">
            <PolicyMetric label="平台上限" profile={policy.platformMaxProfile} />
            <PolicyMetric label="应用上限" profile={policy.applicationMaxProfile} editing={editing} value={draftMax} onChange={setDraftMax} />
            <PolicyMetric label="有效上限" profile={policy.effectiveMaxProfile} />
            <div className="rounded-lg border border-slate-200 bg-slate-50 p-3">
              <div className="text-[10px] font-medium uppercase tracking-wide text-slate-400">策略版本</div>
              <div className="mt-1 font-mono text-sm text-slate-900">rev {policy.revision}</div>
              <div className="mt-0.5 truncate font-mono text-[10px] text-slate-400" title={policy.policyHash}>
                {policy.policyHash?.slice(0, 16)}…
              </div>
            </div>
            {editing ? (
              <div className="col-span-2 flex items-center gap-2 sm:col-span-4">
                <Button size="sm" onClick={save} disabled={submitting}>
                  <CheckCircle2 className="size-4" />
                  保存（乐观锁 rev {policy.revision}）
                </Button>
                <Button size="sm" variant="ghost" onClick={() => { setEditing(false); setDraftMax(policy.applicationMaxProfile); }}>
                  取消
                </Button>
                <span className="text-[11px] text-slate-400">更新后 revision 自增并重算 hash，Agent 编译缓存自动失效。</span>
              </div>
            ) : null}
          </div>
        ) : (
          <EmptyState icon={AlertTriangle} title="无法加载策略" description="请确认应用存在且已配置脚本能力策略。" />
        )
      ) : null}
    </Card>
  );
}

function PolicyMetric({
  label,
  profile,
  editing,
  value,
  onChange,
}: {
  label: string;
  profile: CapabilityProfile;
  editing?: boolean;
  value?: CapabilityProfile;
  onChange?: (profile: CapabilityProfile) => void;
}) {
  return (
    <div className="rounded-lg border border-slate-200 bg-slate-50 p-3">
      <div className="text-[10px] font-medium uppercase tracking-wide text-slate-400">{label}</div>
      {editing && onChange ? (
        <div className="mt-2">
          <SegmentedControl
            value={value ?? profile}
            onValueChange={(v) => onChange(v as CapabilityProfile)}
            items={TIERS.map((tier) => ({ value: tier, label: tier }))}
          />
        </div>
      ) : (
        <div className="mt-1.5">
          <Badge variant={tierVariant(profile)}>{profile}</Badge>
          <div className="mt-1 text-[10px] text-slate-400">{TIER_LABELS[profile]}</div>
        </div>
      )}
    </div>
  );
}

function CreateSessionDialog({
  open,
  onOpenChange,
  defaultApplicationId,
  policy,
  onCreated,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  defaultApplicationId: string;
  policy?: ScriptPolicy;
  onCreated: (sessionId: string) => void;
}) {
  const [agentId, setAgentId] = useState("");
  const [className, setClassName] = useState("");
  const [classLoaderId, setClassLoaderId] = useState("");
  const [methodName, setMethodName] = useState("");
  const [methodDescriptor, setMethodDescriptor] = useState("()V");
  const [script, setScript] = useState("return mock.proceed()");
  const [profile, setProfile] = useState<CapabilityProfile>("SAFE");
  const [ttlMillis, setTtlMillis] = useState(60_000);
  const [maxHits, setMaxHits] = useState(1);
  const [submitting, setSubmitting] = useState(false);

  const platformMax = policy?.platformMaxProfile ?? "UNRESTRICTED";
  const appMax = policy?.applicationMaxProfile ?? "UNRESTRICTED";
  const previewEffective = effectiveTier(platformMax, appMax, profile);

  async function submit() {
    if (!agentId.trim() || !className.trim() || !methodName.trim() || !methodDescriptor.trim() || !script.trim()) {
      toast.error("请完整填写 Agent、目标方法与脚本");
      return;
    }
    const request: CreateScriptSessionRequest = {
      agentId: agentId.trim(),
      target: {
        className: className.trim(),
        classLoaderId: classLoaderId.trim() || undefined,
        methodName: methodName.trim(),
        methodDescriptor: methodDescriptor.trim(),
      },
      script,
      capabilityProfile: profile,
      ttlMillis,
      maxHits,
      applicationId: defaultApplicationId || undefined,
    };
    setSubmitting(true);
    try {
      const result = await createScriptSession(request);
      toast.success(`试用会话已创建：${result.sessionId}`);
      onCreated(result.sessionId);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "创建失败");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>新建试用会话</DialogTitle>
          <DialogDescription>在目标 Agent 的真实 JVM 上试用脚本。有效档位由平台策略收敛。</DialogDescription>
        </DialogHeader>
        <div className="max-h-[60vh] space-y-3 overflow-y-auto pr-1">
          <div className="grid grid-cols-2 gap-3">
            <Field label="Agent ID">
              <Input value={agentId} onChange={(e) => setAgentId(e.target.value)} placeholder="agt-..." />
            </Field>
            <Field label="档位">
              <SegmentedControl
                value={profile}
                onValueChange={(v) => setProfile(v as CapabilityProfile)}
                items={TIERS.map((tier) => ({ value: tier, label: tier }))}
              />
            </Field>
          </div>
          <div className="rounded-md border border-slate-200 bg-slate-50 p-2 text-[11px] text-slate-600">
            请求 {profile} · 平台 {platformMax} · 应用 {appMax} →{" "}
            <Badge variant={tierVariant(previewEffective)}>有效 {previewEffective}</Badge>
          </div>
          <Field label="目标类名">
            <Input value={className} onChange={(e) => setClassName(e.target.value)} placeholder="com.example.demo.OrderService" />
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label="ClassLoader ID（可选）">
              <Input value={classLoaderId} onChange={(e) => setClassLoaderId(e.target.value)} placeholder="留空按类名解析" />
            </Field>
            <Field label="方法名">
              <Input value={methodName} onChange={(e) => setMethodName(e.target.value)} placeholder="calculateScore" />
            </Field>
          </div>
          <Field label="方法描述符">
            <Input value={methodDescriptor} onChange={(e) => setMethodDescriptor(e.target.value)} placeholder="(I)I" className="font-mono text-xs" />
          </Field>
          <Field label="脚本">
            <Textarea
              value={script}
              onChange={(e) => setScript(e.target.value)}
              rows={6}
              className="font-mono text-xs"
              placeholder="return mock.returnValue(42)"
            />
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label="TTL（毫秒）">
              <NumberInput value={String(ttlMillis)} onValueChange={(v) => setTtlMillis(Number(v) || 60_000)} min={1000} step={1000} />
            </Field>
            <Field label="最大命中数">
              <NumberInput value={String(maxHits)} onValueChange={(v) => setMaxHits(Number(v) || 1)} min={1} step={1} />
            </Field>
          </div>
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>取消</Button>
          <Button onClick={submit} disabled={submitting}>
            <Play className="size-4" />
            创建并下发
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function CompileConsoleDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const [agentId, setAgentId] = useState("");
  const [targetClassLoaderId, setTargetClassLoaderId] = useState("bootstrap");
  const [script, setScript] = useState("return mock.proceed()");
  const [profile, setProfile] = useState<CapabilityProfile>("SAFE");
  const [result, setResult] = useState<ScriptCompilationResult | null>(null);
  const [submitting, setSubmitting] = useState(false);

  async function submit() {
    if (!agentId.trim() || !script.trim() || !targetClassLoaderId.trim()) {
      toast.error("请填写 Agent、目标 ClassLoader 与脚本");
      return;
    }
    setSubmitting(true);
    try {
      const res = await compileScript({ agentId: agentId.trim(), script, targetClassLoaderId: targetClassLoaderId.trim(), capabilityProfile: profile });
      setResult(res);
      if (res.successful) toast.success("编译成功");
      else toast.error("编译失败，查看诊断");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "编译失败");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Dialog open={open} onOpenChange={(v) => { onOpenChange(v); if (!v) setResult(null); }}>
      <DialogContent className="max-w-2xl">
        <DialogHeader>
          <DialogTitle>编译试运行</DialogTitle>
          <DialogDescription>在目标 Agent 的 ClassLoader 上编译脚本，返回结构化诊断（不执行、不落地）。</DialogDescription>
        </DialogHeader>
        <div className="space-y-3">
          <div className="grid grid-cols-2 gap-3">
            <Field label="Agent ID">
              <Input value={agentId} onChange={(e) => setAgentId(e.target.value)} placeholder="agt-..." />
            </Field>
            <Field label="档位">
              <SegmentedControl
                value={profile}
                onValueChange={(v) => setProfile(v as CapabilityProfile)}
                items={TIERS.map((tier) => ({ value: tier, label: tier }))}
              />
            </Field>
          </div>
          <Field label="目标 ClassLoader ID">
            <Input value={targetClassLoaderId} onChange={(e) => setTargetClassLoaderId(e.target.value)} className="font-mono text-xs" />
          </Field>
          <Field label="脚本">
            <Textarea value={script} onChange={(e) => setScript(e.target.value)} rows={6} className="font-mono text-xs" />
          </Field>
          {result ? <CompileResult result={result} /> : null}
        </div>
        <DialogFooter>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>关闭</Button>
          <Button onClick={submit} disabled={submitting}>
            <FlaskConical className="size-4" />
            编译
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

function CompileResult({ result }: { result: ScriptCompilationResult }) {
  return (
    <div className="space-y-2 rounded-md border border-slate-200 bg-slate-50 p-3">
      <div className="flex items-center gap-2 text-xs">
        <Badge variant={result.successful ? "success" : "danger"}>{result.successful ? "成功" : "失败"}</Badge>
        <span className="text-slate-500">编译器 {result.compilerVersion}</span>
        <span className="text-slate-400">·</span>
        <span className="text-slate-500">档位 {result.capabilityProfile}</span>
        <span className="text-slate-400">·</span>
        <span className="font-mono text-[11px] text-slate-400">{result.targetClassLoaderId}</span>
      </div>
      {result.diagnostics.length > 0 ? (
        <ul className="space-y-1">
          {result.diagnostics.map((d, i) => (
            <li key={i} className="flex items-start gap-2 text-xs">
              <Badge variant={diagnosticVariant(d.severity)}>{d.code}</Badge>
              <span className="text-slate-700">{d.message}</span>
              {d.suggestion ? <span className="text-slate-400">（{d.suggestion}）</span> : null}
            </li>
          ))}
        </ul>
      ) : (
        <p className="text-xs text-slate-500">无诊断信息。</p>
      )}
    </div>
  );
}

function SessionDetailDialog({
  sessionId,
  onOpenChange,
  onLifecycle,
  pending,
}: {
  sessionId: string | null;
  onOpenChange: (open: boolean) => void;
  onLifecycle: (action: "validate" | "apply" | "promote" | "revert", id: string) => void;
  pending: boolean;
}) {
  const detailQuery = useQuery({
    queryKey: ["script-session", sessionId],
    queryFn: () => getScriptSession(sessionId!),
    enabled: Boolean(sessionId),
  });
  const eventsQuery = useQuery({
    queryKey: ["script-session-events", sessionId],
    queryFn: () => getScriptSessionEvents(sessionId!),
    enabled: Boolean(sessionId),
  });

  const session = detailQuery.data;
  const open = Boolean(sessionId);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-3xl">
        <DialogHeader>
          <DialogTitle>试用会话详情</DialogTitle>
          <DialogDescription>{session ? session.sessionId : "加载中..."}</DialogDescription>
        </DialogHeader>
        {detailQuery.isLoading ? (
          <Skeleton className="h-48 w-full" />
        ) : session ? (
          <div className="max-h-[65vh] space-y-4 overflow-y-auto pr-1">
            <div className="flex flex-wrap items-center gap-2">
              <Badge variant={statusVariant(session.status)}>{statusLabel(session.status)}</Badge>
              <Badge variant={tierVariant(session.effectiveProfile)}>有效 {session.effectiveProfile}</Badge>
              {session.effectiveProfile !== session.requestedProfile ? (
                <span className="text-[11px] text-slate-400">请求 {session.requestedProfile} → 收敛为 {session.effectiveProfile}</span>
              ) : null}
              {session.formalRuleId ? <Badge variant="info">已提升为正式规则</Badge> : null}
            </div>

            <div className="grid grid-cols-2 gap-3 text-xs sm:grid-cols-3">
              <DetailField label="目标" value={targetLabel(session)} mono />
              <DetailField label="ClassLoader" value={session.target.classLoaderId ?? "bootstrap"} mono />
              <DetailField label="方法描述符" value={session.target.methodDescriptor} mono />
              <DetailField label="Agent" value={session.agentId} mono />
              <DetailField label="应用" value={session.applicationId} mono />
              <DetailField label="请求人" value={session.requestedBy} />
              <DetailField label="命中 / 上限" value={`${session.hitCount} / ${session.maxHits}`} mono />
              <DetailField label="TTL" value={`${Math.round(session.ttlMillis / 1000)} 秒`} />
              <DetailField label="剩余" value={remainingTtl(session)} />
              <DetailField label="创建时间" value={formatDate(session.createdAt)} />
              <DetailField label="到期时间" value={formatDate(session.expiresAt)} />
              <DetailField label="生效时间" value={session.appliedAt ? formatDate(session.appliedAt) : "-"} />
              <DetailField label="脚本哈希" value={session.scriptHash?.slice(0, 24) + "…"} mono />
              <DetailField label="策略版本" value={`rev ${session.policyRevision.revision}`} mono />
              <DetailField label="版本" value={`v${session.version}`} mono />
            </div>

            <SessionDiagnostics diagnostics={session.diagnostics} />

            <SessionLifecycleActions session={session} onLifecycle={onLifecycle} pending={pending} />

            <SessionEvents events={eventsQuery.data ?? []} loading={eventsQuery.isLoading} />
          </div>
        ) : (
          <EmptyState icon={AlertTriangle} title="无法加载会话" description="会话可能已被清理。" />
        )}
      </DialogContent>
    </Dialog>
  );
}

function SessionLifecycleActions({
  session,
  onLifecycle,
  pending,
}: {
  session: ScriptSessionDetail;
  onLifecycle: (action: "validate" | "apply" | "promote" | "revert", id: string) => void;
  pending: boolean;
}) {
  const status = session.status;
  const canValidate = status === "CREATED";
  const canApply = status === "VALIDATED";
  const canPromote = status === "VALIDATED" || status === "APPLIED";
  const canRevert = !status || !(status === "EXPIRED" || status === "REVERTED" || status === "FAILED");

  return (
    <div className="flex flex-wrap items-center gap-2 rounded-md border border-slate-200 bg-slate-50 p-3">
      <span className="text-xs font-medium text-slate-600">生命周期</span>
      <Button size="sm" variant="outline" disabled={!canValidate || pending} onClick={() => onLifecycle("validate", session.sessionId)}>
        <CheckCircle2 className="size-4" /> 校验
      </Button>
      <Button size="sm" variant="outline" disabled={!canApply || pending} onClick={() => onLifecycle("apply", session.sessionId)}>
        <Play className="size-4" /> 生效
      </Button>
      <Button size="sm" variant="outline" disabled={!canPromote || pending} onClick={() => onLifecycle("promote", session.sessionId)}>
        <Rocket className="size-4" /> 提升为正式规则
      </Button>
      <Button size="sm" variant="outline" disabled={!canRevert || pending} onClick={() => onLifecycle("revert", session.sessionId)}>
        <Trash2 className="size-4" /> 撤销
      </Button>
      <span className="ml-auto text-[10px] text-slate-400">失败会话不可重试，请新建会话。</span>
    </div>
  );
}

function SessionDiagnostics({ diagnostics }: { diagnostics: ScriptDiagnostic[] }) {
  if (diagnostics.length === 0) return null;
  return (
    <div className="rounded-md border border-red-200 bg-red-50/40 p-3">
      <div className="mb-1.5 text-xs font-semibold text-red-700">诊断</div>
      <ul className="space-y-1">
        {diagnostics.map((d, i) => (
          <li key={i} className="flex items-start gap-2 text-xs">
            <Badge variant={diagnosticVariant(d.severity)}>{d.code}</Badge>
            <span className="text-slate-700">{d.message}</span>
            {d.suggestion ? <span className="text-slate-400">（{d.suggestion}）</span> : null}
          </li>
        ))}
      </ul>
    </div>
  );
}

function SessionEvents({ events, loading }: { events: ScriptSessionEvent[]; loading: boolean }) {
  return (
    <div>
      <div className="mb-2 text-xs font-semibold text-slate-700">事件历史（审计）</div>
      {loading ? (
        <Skeleton className="h-20 w-full" />
      ) : events.length === 0 ? (
        <p className="text-xs text-slate-400">暂无事件。</p>
      ) : (
        <ol className="space-y-1.5 border-l border-slate-200 pl-3">
          {events.map((event) => (
            <li key={event.id} className="text-xs">
              <div className="flex items-center gap-2">
                <span className="font-medium text-slate-700">{humanize(event.toStatus)}</span>
                <span className="text-slate-400">{event.actor}</span>
                <span className="text-slate-400">{formatDate(event.createdAt)}</span>
              </div>
              {event.detail ? <div className="text-[11px] text-slate-500">{event.detail}</div> : null}
            </li>
          ))}
        </ol>
      )}
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <label className="mb-1 block text-xs font-medium text-slate-500">{label}</label>
      {children}
    </div>
  );
}

function DetailField({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div>
      <div className="text-[10px] font-medium uppercase tracking-wide text-slate-400">{label}</div>
      <div className={`mt-0.5 text-slate-900 ${mono ? "font-mono text-[11px]" : "text-xs"}`} title={value}>
        {value || "-"}
      </div>
    </div>
  );
}
