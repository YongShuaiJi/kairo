"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Pencil, Plus, Power, PowerOff } from "lucide-react";
import { toast } from "sonner";
import { platformFetch } from "@/lib/api/client";
import { recordValue as valueOf } from "@/lib/api/record";
import type { PlatformRecord } from "@/lib/api/types";
import { formatDate, humanize } from "@/lib/utils";
import { PageHeader } from "@/components/layout/page-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Skeleton } from "@/components/ui/skeleton";
import { Table, TableBody, TableCell, TableContainer, TableHead, TableHeader, TableRow } from "@/components/ui/table";

type RuleDetailData = {
  rule: PlatformRecord;
  versions: PlatformRecord[];
  targets: PlatformRecord[];
  capabilities: PlatformRecord[];
};

type InvokePhase = "BEFORE" | "RETURN" | "THROWS";

function statusVariant(status: string) {
  const value = status.toUpperCase();
  if (["ACTIVE", "ONLINE", "ENABLED", "SUCCEEDED"].includes(value)) return "success" as const;
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

// V1.5 §5: render the modern-JVM target metadata the platform now persists on rule_target
// (proxy type, support level, drift status, loader/framework). All fields are optional: legacy
// targets and offline demo data render no badges, so existing flows are unchanged.
// Lambda/synthetic/bridge/JDK risk surfaces via the support-level badge.
function supportVariant(level: string) {
  const value = level.toUpperCase();
  if (value === "SUPPORTED") return "success" as const;
  if (value === "LIMITED") return "warning" as const;
  if (value === "UNSUPPORTED") return "danger" as const;
  return "neutral" as const; // EXPERIMENTAL
}

function targetMetadataBadges(target: PlatformRecord | undefined) {
  if (!target) return null;
  const proxyType = String(valueOf(target, "proxyType") ?? "");
  const supportLevel = String(valueOf(target, "supportLevel") ?? "");
  const driftStatus = String(valueOf(target, "driftStatus") ?? "");
  const loaderClass = String(valueOf(target, "loaderClass") ?? "");
  const frameworkLoader = String(valueOf(target, "frameworkLoader") ?? "");
  return (
    <div className="mt-1 flex flex-wrap justify-center gap-1">
      {proxyType && proxyType !== "PLAIN" && (
        <Badge variant="info" className="text-[10px]">代理 {proxyType}</Badge>
      )}
      {supportLevel && (
        <Badge variant={supportVariant(supportLevel)} className="text-[10px]">{supportLevel}</Badge>
      )}
      {driftStatus && driftStatus !== "FRESH" && (
        <Badge variant={driftStatus === "DRIFTED" ? "danger" : "warning"} className="text-[10px]">
          {driftStatus}
        </Badge>
      )}
      {frameworkLoader && (
        <Badge variant="neutral" className="text-[10px]">{frameworkLoader}</Badge>
      )}
      {!frameworkLoader && loaderClass && loaderClass !== "bootstrap" && (
        <Badge variant="neutral" className="text-[10px]">{loaderClass}</Badge>
      )}
    </div>
  );
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
      toast.success("规则版本状态已更新");
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
            <TableContainer>
              <Table className="min-w-[1540px] whitespace-nowrap text-center">
                <TableHeader className="theme-muted-panel whitespace-nowrap font-medium tracking-wide">
                  <TableRow className="hover:bg-transparent">
                    <TableHead className="px-6 text-center">规则名称</TableHead>
                    <TableHead className="px-6 text-center">应用</TableHead>
                    <TableHead className="px-6 text-center">环境</TableHead>
                    <TableHead className="px-6 text-center">目标方法</TableHead>
                    <TableHead className="px-6 text-center">版本数</TableHead>
                    <TableHead className="px-6 text-center">在线版本</TableHead>
                    <TableHead className="px-6 text-center">最新版本</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  <TableRow>
                    <TableCell className="px-6 py-4 font-semibold text-[color:var(--foreground)]">{humanize(valueOf(rule, "name"))}</TableCell>
                    <TableCell className="px-6 py-4">{humanize(valueOf(rule, "applicationName") ?? valueOf(rule, "applicationId"))}</TableCell>
                    <TableCell className="px-6 py-4">{displayRaw(valueOf(rule, "environmentName") ?? valueOf(rule, "environmentId"))}</TableCell>
                    <TableCell className="px-6 py-4 font-mono text-xs">
                      <div className="flex flex-col items-center gap-1">
                        <span>{targetLabel(firstTarget(detailQuery.data, versions[0]))}</span>
                        {targetMetadataBadges(firstTarget(detailQuery.data, versions[0]))}
                      </div>
                    </TableCell>
                    <TableCell className="px-6 py-4">
                      <span>{versionCountLabel(valueOf(rule, "versionCount") ?? versions.length)}</span>
                      <span className="ml-2 whitespace-nowrap text-xs text-[color:var(--muted)]">
                        启用 {versionCountLabel(valueOf(rule, "enabledVersionCount"))} / 停用 {versionCountLabel(valueOf(rule, "disabledVersionCount"))}
                      </span>
                    </TableCell>
                    <TableCell className="px-6 py-4">{onlineVersion ? `v${onlineVersion}` : "—"}</TableCell>
                    <TableCell className="px-6 py-4">{latestVersion ? `v${latestVersion}` : "—"}</TableCell>
                  </TableRow>
                </TableBody>
              </Table>
            </TableContainer>
          </Card>

          <Card className="overflow-hidden">
            <div className="flex flex-wrap items-center gap-2 border-b p-4">
              <div>
                <h2 className="text-sm font-semibold text-[color:var(--foreground)]">版本台账</h2>
                <p className="mt-1 text-xs text-[color:var(--muted)]">共 {versions.length} 个版本，按版本号倒序排列。</p>
              </div>
            </div>
            <TableContainer>
              <Table className="min-w-[1420px] whitespace-nowrap text-center">
                <TableHeader className="theme-muted-panel whitespace-nowrap font-medium tracking-wide">
                  <TableRow className="hover:bg-transparent">
                    <TableHead className="px-6 text-center">版本</TableHead>
                    <TableHead className="px-6 text-center">状态</TableHead>
                    <TableHead className="px-6 text-center">执行策略</TableHead>
                    <TableHead className="px-6 text-center">风险</TableHead>
                    <TableHead className="px-6 text-center">30 天自动删除倒计时</TableHead>
                    <TableHead className="px-6 text-center">脚本摘要</TableHead>
                    <TableHead className="px-6 text-center">创建人</TableHead>
                    <TableHead className="px-6 text-center">创建时间</TableHead>
                    <TableHead className="sticky right-0 z-10 w-56 bg-[var(--surface-subtle)] px-6 text-center shadow-[-12px_0_18px_-18px_rgba(15,23,42,0.85)]">操作</TableHead>
                  </TableRow>
                </TableHeader>
                <TableBody>
                  {versions.map((version) => {
                    const versionNumber = Number(valueOf(version, "version") ?? 0);
                    const status = String(valueOf(version, "status") ?? "");
                    const scriptSummary = valueOf(version, "scriptSummary");
                    const phase = executionPhase(version);
                    const disabled = status.toUpperCase() === "DISABLED";
                    return (
                      <TableRow key={String(valueOf(version, "id") ?? versionNumber)} className="theme-row">
                        <TableCell className="px-6 py-4">
                          <div className="flex items-center justify-center gap-2">
                            <span className="font-mono font-semibold">v{versionNumber}</span>
                          </div>
                        </TableCell>
                        <TableCell className="px-6 py-4"><Badge variant={statusVariant(status)}>{humanize(status)}</Badge></TableCell>
                        <TableCell className="px-6 py-4">{phase ? <Badge variant="neutral">{phaseLabel(phase)}</Badge> : "—"}</TableCell>
                        <TableCell className="px-6 py-4"><Badge variant={statusVariant(String(valueOf(version, "riskLevel") ?? ""))}>{humanize(valueOf(version, "riskLevel"))}</Badge></TableCell>
                        <TableCell className="px-6 py-4 whitespace-nowrap text-[color:var(--muted)]">{autoDeleteCountdown(version)}</TableCell>
                        <TableCell className="max-w-80 px-6 py-4 text-center text-[color:var(--muted)]">
                          <span className="block truncate" title={String(scriptSummary ?? "")}>{scriptSummary ? humanize(scriptSummary) : "—"}</span>
                        </TableCell>
                        <TableCell className="px-6 py-4">{humanize(valueOf(version, "createdBy"))}</TableCell>
                        <TableCell className="px-6 py-4 whitespace-nowrap text-[color:var(--muted)]">{formatDate(valueOf(version, "createdAt"))}</TableCell>
                        <TableCell className="sticky right-0 z-10 bg-inherit px-6 py-4 shadow-[-12px_0_18px_-18px_rgba(15,23,42,0.85)]">
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
                        </TableCell>
                      </TableRow>
                    );
                  })}
                </TableBody>
              </Table>
            </TableContainer>
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
