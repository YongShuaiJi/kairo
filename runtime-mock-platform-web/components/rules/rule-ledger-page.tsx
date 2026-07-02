"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Pencil, Plus, Power, PowerOff } from "lucide-react";
import { toast } from "sonner";
import { platformFetch } from "@/lib/api/client";
import type { PlatformRecord } from "@/lib/api/types";
import { formatDate, humanize } from "@/lib/utils";
import { PageHeader } from "@/components/layout/page-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";

type RuleDetailData = {
  rule: PlatformRecord;
  versions: PlatformRecord[];
  targets: PlatformRecord[];
  capabilities: PlatformRecord[];
};

type InvokePhase = "BEFORE" | "RETURN" | "THROWS";

function valueOf(record: PlatformRecord | undefined, key: string) {
  if (!record) return undefined;
  const snake = key.replace(/[A-Z]/g, (letter) => `_${letter.toLowerCase()}`);
  return record[key] ?? record[snake];
}

function statusVariant(status: string) {
  const value = status.toUpperCase();
  if (["ACTIVE", "ONLINE", "PUBLISHED", "APPROVED", "SUCCEEDED"].includes(value)) return "success" as const;
  if (["DRAFT", "UNLOADING"].includes(value)) return "warning" as const;
  if (["FAILED", "REJECTED", "CANCELLED"].includes(value)) return "danger" as const;
  return "neutral" as const;
}

function firstTarget(detail: RuleDetailData | undefined, version: PlatformRecord) {
  const versionId = String(valueOf(version, "id") ?? "");
  return detail?.targets.find((target) => String(valueOf(target, "ruleVersionId") ?? "") === versionId);
}

function targetLabel(target: PlatformRecord | undefined) {
  if (!target) return "—";
  return `${String(valueOf(target, "className") ?? "—")}#${String(valueOf(target, "methodName") ?? "—")}`;
}

function phaseLabel(phase: InvokePhase) {
  return phase === "BEFORE" ? "调用前" : phase === "RETURN" ? "正常返回后" : "抛出异常时";
}

function executionPhase(version: PlatformRecord): InvokePhase | undefined {
  const scriptJson = valueOf(version, "scriptJson");
  if (!scriptJson) return undefined;
  if (typeof scriptJson === "object" && scriptJson !== null && "phase" in scriptJson) {
    const phase = String((scriptJson as { phase?: unknown }).phase);
    return phase === "BEFORE" || phase === "RETURN" || phase === "THROWS" ? phase : undefined;
  }
  if (typeof scriptJson !== "string") return undefined;
  try {
    const parsed = JSON.parse(scriptJson) as unknown;
    if (typeof parsed !== "object" || parsed === null || !("phase" in parsed)) return undefined;
    const phase = String((parsed as { phase?: unknown }).phase);
    return phase === "BEFORE" || phase === "RETURN" || phase === "THROWS" ? phase : undefined;
  } catch {
    return undefined;
  }
}

function autoDeleteCountdown(version: PlatformRecord | undefined) {
  if (String(valueOf(version, "status") ?? "").toUpperCase() !== "DISABLED") return "—";
  const raw = valueOf(version, "autoDeleteAt");
  if (!raw) return "—";
  const deadline = new Date(String(raw)).getTime();
  if (Number.isNaN(deadline)) return "—";
  const remaining = deadline - Date.now();
  if (remaining <= 0) return "等待自动删除";
  const days = Math.floor(remaining / 86_400_000);
  const hours = Math.ceil((remaining % 86_400_000) / 3_600_000);
  return days > 0 ? `${days} 天 ${hours} 小时` : `${hours} 小时`;
}

function versionCountLabel(value: unknown) {
  const count = Number(value ?? 0);
  return Number.isFinite(count) ? String(count) : "0";
}

function displayRaw(value: unknown) {
  if (value === null || value === undefined || value === "") return "—";
  return String(value);
}

function versionLifecycleStatus(versionNumber: number, status: string, onlineVersion: string) {
  return String(versionNumber) === onlineVersion ? "ONLINE" : status;
}

export function RuleLedgerPage({ ruleId }: { ruleId: string }) {
  const router = useRouter();
  const queryClient = useQueryClient();
  const [confirmDisableVersion, setConfirmDisableVersion] = useState<number | null>(null);

  const detailQuery = useQuery({
    queryKey: ["rule-ledger", ruleId],
    queryFn: () => platformFetch<RuleDetailData>(`rules/${ruleId}/detail`),
  });

  const versions = useMemo(
    () => [...(detailQuery.data?.versions ?? [])].sort((left, right) => Number(valueOf(right, "version") ?? 0) - Number(valueOf(left, "version") ?? 0)),
    [detailQuery.data?.versions],
  );
  const rule = detailQuery.data?.rule;
  const onlineVersion = String(valueOf(rule, "onlineVersion") ?? "");
  const latestVersion = String(valueOf(rule, "latestVersion") ?? "");

  const versionStatusMutation = useMutation({
    mutationFn: ({ version, action }: { version: number; action: "enable" | "disable" }) => platformFetch(`rules/${ruleId}/versions/${version}/${action}`, {
      method: "POST",
      idempotencyKey: crypto.randomUUID(),
    }),
    onSuccess: () => {
      toast.success("规则版本状态已更新，关联发布已自动卸载");
      setConfirmDisableVersion(null);
      void queryClient.invalidateQueries({ queryKey: ["rule-ledger", ruleId] });
      void queryClient.invalidateQueries({ queryKey: ["resource", "rules"] });
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "规则版本状态更新失败"),
  });

  return (
    <>
      <PageHeader
        eyebrow="Mock & Fault"
        title="规则台账"
        description="查看规则聚合信息和各版本脚本、发布状态、停用保留倒计时。"
        actions={(
          <>
            <Button variant="secondary" onClick={() => router.push("/rules")}><ArrowLeft />返回规则中心</Button>
            <Button asChild><Link href={`/rules/${ruleId}/versions/new`}><Plus />创建新版本</Link></Button>
          </>
        )}
      />

      {detailQuery.isLoading ? (
        <div className="space-y-4">
          <Skeleton className="h-36 w-full" />
          <Skeleton className="h-80 w-full" />
        </div>
      ) : detailQuery.isError ? (
        <Card className="p-8 text-center text-sm text-[color:var(--muted)]">规则详情加载失败</Card>
      ) : (
        <div className="space-y-4">
          <Card className="overflow-hidden">
            <div className="scrollbar-thin overflow-x-auto">
              <table className="w-full min-w-[1540px] whitespace-nowrap text-center text-sm">
                <thead className="theme-muted-panel whitespace-nowrap text-xs font-medium uppercase tracking-wide text-[color:var(--muted)]">
                  <tr>
                    <th className="px-6 py-3">规则名称</th>
                    <th className="px-6 py-3">应用</th>
                    <th className="px-6 py-3">环境</th>
                    <th className="px-6 py-3">目标方法</th>
                    <th className="px-6 py-3">版本数</th>
                    <th className="px-6 py-3">在线版本</th>
                    <th className="px-6 py-3">最新版本</th>
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <td className="px-6 py-4 font-semibold text-[color:var(--foreground)]">{humanize(valueOf(rule, "name"))}</td>
                    <td className="px-6 py-4">{humanize(valueOf(rule, "applicationName") ?? valueOf(rule, "applicationId"))}</td>
                    <td className="px-6 py-4">{displayRaw(valueOf(rule, "environmentName") ?? valueOf(rule, "environmentId"))}</td>
                    <td className="px-6 py-4 font-mono text-xs">{targetLabel(firstTarget(detailQuery.data, versions[0]))}</td>
                    <td className="px-6 py-4">
                      <span>{versionCountLabel(valueOf(rule, "versionCount") ?? versions.length)}</span>
                      <span className="ml-2 whitespace-nowrap text-xs text-[color:var(--muted)]">
                        启用 {versionCountLabel(valueOf(rule, "enabledVersionCount"))} / 停用 {versionCountLabel(valueOf(rule, "disabledVersionCount"))}
                      </span>
                    </td>
                    <td className="px-6 py-4">{onlineVersion ? `v${onlineVersion}` : "—"}</td>
                    <td className="px-6 py-4">{latestVersion ? `v${latestVersion}` : "—"}</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </Card>

          <Card className="overflow-hidden">
            <div className="flex flex-wrap items-center gap-2 border-b p-4">
              <div>
                <h2 className="text-sm font-semibold text-[color:var(--foreground)]">版本台账</h2>
                <p className="mt-1 text-xs text-[color:var(--muted)]">共 {versions.length} 个版本，按版本号倒序排列。</p>
              </div>
            </div>
            <div className="scrollbar-thin overflow-x-auto">
              <table className="w-full min-w-[1420px] whitespace-nowrap text-center text-sm">
                <thead className="theme-muted-panel whitespace-nowrap text-xs font-medium uppercase tracking-wide text-[color:var(--muted)]">
                  <tr>
                    <th className="px-6 py-3">版本</th>
                    <th className="px-6 py-3">状态</th>
                    <th className="px-6 py-3">执行策略</th>
                    <th className="px-6 py-3">风险</th>
                    <th className="px-6 py-3">30 天自动删除倒计时</th>
                    <th className="px-6 py-3">脚本摘要</th>
                    <th className="px-6 py-3">创建人</th>
                    <th className="px-6 py-3">创建时间</th>
                    <th className="sticky right-0 z-10 w-56 bg-[var(--surface-subtle)] px-6 py-3 shadow-[-12px_0_18px_-18px_rgba(15,23,42,0.85)]">操作</th>
                  </tr>
                </thead>
                <tbody className="divide-y">
                  {versions.map((version) => {
                    const versionNumber = Number(valueOf(version, "version") ?? 0);
                    const status = String(valueOf(version, "status") ?? "");
                    const lifecycleStatus = versionLifecycleStatus(versionNumber, status, onlineVersion);
                    const scriptSummary = valueOf(version, "scriptSummary");
                    const phase = executionPhase(version);
                    const disabled = status.toUpperCase() === "DISABLED";
                    return (
                      <tr key={String(valueOf(version, "id") ?? versionNumber)} className="theme-row">
                        <td className="px-6 py-4">
                          <div className="flex items-center justify-center gap-2">
                            <span className="font-mono font-semibold">v{versionNumber}</span>
                          </div>
                        </td>
                        <td className="px-6 py-4"><Badge variant={statusVariant(lifecycleStatus)}>{humanize(lifecycleStatus)}</Badge></td>
                        <td className="px-6 py-4">{phase ? <Badge variant="neutral">{phaseLabel(phase)}</Badge> : "—"}</td>
                        <td className="px-6 py-4"><Badge variant={statusVariant(String(valueOf(version, "riskLevel") ?? ""))}>{humanize(valueOf(version, "riskLevel"))}</Badge></td>
                        <td className="px-6 py-4 whitespace-nowrap text-[color:var(--muted)]">{autoDeleteCountdown(version)}</td>
                        <td className="max-w-80 px-6 py-4 text-center text-[color:var(--muted)]">
                          <span className="block truncate" title={String(scriptSummary ?? "")}>{scriptSummary ? humanize(scriptSummary) : "—"}</span>
                        </td>
                        <td className="px-6 py-4">{humanize(valueOf(version, "createdBy"))}</td>
                        <td className="px-6 py-4 whitespace-nowrap text-[color:var(--muted)]">{formatDate(valueOf(version, "createdAt"))}</td>
                        <td className="sticky right-0 z-10 bg-inherit px-6 py-4 shadow-[-12px_0_18px_-18px_rgba(15,23,42,0.85)]">
                          <div className="flex justify-center gap-2">
                            <Button asChild variant="secondary" size="sm"><Link href={`/rules/${ruleId}/versions/${versionNumber}`}><Pencil />修改</Link></Button>
                            {disabled ? (
                              <Button size="sm" onClick={() => versionStatusMutation.mutate({ version: versionNumber, action: "enable" })} disabled={versionStatusMutation.isPending}>
                                <Power />启用
                              </Button>
                            ) : (
                              <Button variant="secondary" size="sm" onClick={() => setConfirmDisableVersion(versionNumber)} disabled={versionStatusMutation.isPending}>
                                <PowerOff />停用
                              </Button>
                            )}
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          </Card>
        </div>
      )}

      <Dialog open={confirmDisableVersion !== null} onOpenChange={(open) => !open && setConfirmDisableVersion(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>停用规则版本 v{confirmDisableVersion}</DialogTitle>
            <DialogDescription>
              停用后会自动卸载该版本关联发布和实例执行，并开始 30 天自动删除倒计时。30 天内重新启用会取消自动删除。
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button variant="secondary" onClick={() => setConfirmDisableVersion(null)}>取消</Button>
            <Button
              onClick={() => confirmDisableVersion && versionStatusMutation.mutate({ version: confirmDisableVersion, action: "disable" })}
              disabled={versionStatusMutation.isPending}
            >
              <PowerOff />确认停用
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
