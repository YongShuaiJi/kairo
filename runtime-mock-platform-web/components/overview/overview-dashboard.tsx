"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import {
  Activity,
  ArrowRight,
  BookOpenCheck,
  CheckCircle2,
  Cpu,
  Database,
  Network,
  Radio,
  ServerCog,
  ShieldCheck,
  SlidersHorizontal,
  Sparkles,
  TriangleAlert,
  Zap,
} from "lucide-react";
import { platformFetch } from "@/lib/api/client";
import type { PlatformRecord } from "@/lib/api/types";
import { formatDate, humanize } from "@/lib/utils";
import { PageHeader } from "@/components/layout/page-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

type Dashboard = {
  checkedAt: string;
  counts: Record<string, number>;
  auditTrends: Array<{ label: string; value: number }>;
  recentAudits: PlatformRecord[];
};
type Health = {
  status: string;
  checkedAt?: string;
  services?: Record<string, { status: string; latencyMs?: number }>;
  outboxPendingCount?: number;
};

const stats = [
  { key: "agentsOnline", totalKey: "agentsTotal", label: "在线 Agent", href: "/agents", icon: Network, color: "text-emerald-600", background: "bg-emerald-50" },
  { key: "rulesActive", totalKey: "rulesTotal", label: "有效规则", href: "/rules", icon: SlidersHorizontal, color: "text-indigo-600", background: "bg-indigo-50" },
  { key: "rolloutsRunning", label: "运行中发布", href: "/rollouts", icon: Zap, color: "text-amber-600", background: "bg-amber-50" },
  { key: "approvalsPending", label: "待审批", href: "/approvals", icon: BookOpenCheck, color: "text-sky-600", background: "bg-sky-50" },
];

export function OverviewDashboard() {
  const dashboard = useQuery({ queryKey: ["overview", "dashboard"], queryFn: () => platformFetch<Dashboard>("dashboard/overview"), refetchInterval: 20_000 });
  const health = useQuery({ queryKey: ["overview", "health"], queryFn: () => platformFetch<Health>("control/health"), refetchInterval: 20_000 });
  const counts = dashboard.data?.counts ?? {};
  const maxTrend = Math.max(1, ...(dashboard.data?.auditTrends ?? []).map((item) => Number(item.value)));

  return (
    <>
      <PageHeader
        eyebrow="Control Center"
        title="运行总览"
        description="所有指标来自 Platform 聚合接口，不在浏览器下载全量资源后拼接统计。"
        actions={<><span className="hidden text-xs text-slate-400 sm:inline">更新于 {formatDate(dashboard.data?.checkedAt)}</span><Button asChild><Link href="/rules/new"><Sparkles />创建规则</Link></Button></>}
      />

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        {stats.map((item) => (
          <Card key={item.key} className="overflow-hidden">
            <CardContent className="p-5">
              <div className="flex items-start justify-between">
                <div>
                  <p className="text-sm text-slate-500">{item.label}</p>
                  {dashboard.isLoading ? <Skeleton className="mt-3 h-9 w-16" /> : <p className="mt-2 text-3xl font-semibold tracking-tight text-slate-950">{counts[item.key] ?? 0}</p>}
                </div>
                <div className={`rounded-xl p-2.5 ${item.background} ${item.color}`}><item.icon className="size-5" /></div>
              </div>
              <div className="mt-4 flex items-center text-xs text-slate-400">
                <span>{item.totalKey ? `总计 ${counts[item.totalKey] ?? 0}` : "实时聚合"}</span>
                <Link href={item.href} className="ml-auto flex items-center gap-1 text-indigo-600 hover:text-indigo-800">查看 <ArrowRight className="size-3" /></Link>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>

      <div className="mt-5 grid gap-5 xl:grid-cols-[1.45fr_0.85fr]">
        <Card>
          <CardHeader className="flex-row items-center justify-between">
            <div><CardTitle>审计结果分布</CardTitle><CardDescription>当前审计链中按结果聚合的真实数据</CardDescription></div>
            <Badge variant={health.data?.status === "UP" ? "success" : "danger"}><Activity className="mr-1 size-3" />{health.data?.status ?? "检查中"}</Badge>
          </CardHeader>
          <CardContent>
            {dashboard.isLoading ? <Skeleton className="h-64 w-full" /> : !(dashboard.data?.auditTrends.length) ? (
              <div className="flex h-64 items-center justify-center rounded-xl border border-dashed text-sm text-slate-400">产生业务操作后，这里会出现审计趋势。</div>
            ) : (
              <div className="flex h-64 items-end gap-6 rounded-xl border border-slate-100 bg-slate-50/70 p-5">
                {dashboard.data.auditTrends.map((item) => (
                  <div key={item.label} className="flex h-full min-w-20 flex-1 flex-col justify-end text-center">
                    <span className="mb-2 text-sm font-semibold text-slate-700">{item.value}</span>
                    <div className="mx-auto w-full max-w-24 rounded-t-md bg-gradient-to-t from-indigo-600 to-indigo-400" style={{ height: `${Math.max(8, Number(item.value) / maxTrend * 80)}%` }} />
                    <span className="mt-2 text-xs text-slate-500">{humanize(item.label)}</span>
                  </div>
                ))}
              </div>
            )}
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>基础服务健康度</CardTitle>
            <CardDescription>来自控制面实时探测</CardDescription>
          </CardHeader>
          <CardContent className="space-y-2">
            {health.isLoading ? [1, 2].map((item) => <Skeleton key={item} className="h-11" />) : health.isError ? (
              <div className="rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-700"><TriangleAlert className="mb-2 size-5" />健康检查暂时不可用</div>
            ) : Object.entries(health.data?.services ?? {}).map(([name, service]) => (
              <div key={name} className="flex items-center gap-3 rounded-lg border border-slate-100 px-3 py-2.5">
                <div className="rounded-lg bg-slate-100 p-1.5 text-slate-500">{name === "postgresql" ? <Database className="size-4" /> : name === "platformApi" ? <ServerCog className="size-4" /> : <Cpu className="size-4" />}</div>
                <span className="text-sm font-medium text-slate-700">{humanize(name)}</span>
                <span className="ml-auto text-xs text-slate-400">{service.latencyMs ?? "—"} ms</span>
                <span className={`size-2 rounded-full ${service.status === "UP" ? "bg-emerald-500" : "bg-red-500"}`} />
              </div>
            ))}
            <p className="pt-2 text-xs text-slate-400">最近检查：{formatDate(health.data?.checkedAt)}</p>
          </CardContent>
        </Card>
      </div>

      <div className="mt-5 grid gap-5 xl:grid-cols-[1.1fr_0.9fr]">
        <Card>
          <CardHeader className="flex-row items-center justify-between">
            <div><CardTitle>最近活动</CardTitle><CardDescription>审计链中最近的高价值操作</CardDescription></div>
            <Button variant="ghost" size="sm" asChild><Link href="/audits">查看全部 <ArrowRight /></Link></Button>
          </CardHeader>
          <CardContent>
            <div className="space-y-1">
              {(dashboard.data?.recentAudits ?? []).map((record, index) => (
                <div key={String(record.id ?? index)} className="flex gap-3 rounded-lg px-2 py-3 hover:bg-slate-50">
                  <div className={`mt-0.5 rounded-full p-2 ${String(record.result).includes("SUCCESS") ? "bg-emerald-50 text-emerald-600" : "bg-amber-50 text-amber-600"}`}>{String(record.result).includes("SUCCESS") ? <CheckCircle2 className="size-4" /> : <TriangleAlert className="size-4" />}</div>
                  <div className="min-w-0 flex-1"><p className="truncate text-sm font-medium text-slate-800">{String(record.resource_id ?? record.action)}</p><p className="mt-0.5 text-xs text-slate-400">{String(record.actor)} · {humanize(String(record.action))}</p></div>
                  <span className="whitespace-nowrap text-xs text-slate-400">{formatDate(String(record.occurred_at))}</span>
                </div>
              ))}
              {!dashboard.isLoading && !(dashboard.data?.recentAudits.length) ? <p className="py-10 text-center text-sm text-slate-400">暂无审计活动</p> : null}
            </div>
          </CardContent>
        </Card>

        <Card className="bg-slate-950 text-white">
          <CardHeader>
            <div className="mb-2 flex size-10 items-center justify-center rounded-xl bg-indigo-500"><ShieldCheck className="size-5" /></div>
            <CardTitle className="text-white">当前关注项</CardTitle>
            <CardDescription className="text-slate-400">根据实时聚合值生成，不使用演示文案</CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            {[
              { icon: TriangleAlert, title: `${Math.max(0, (counts.agentsTotal ?? 0) - (counts.agentsOnline ?? 0))} 个 Agent 未在线`, detail: "检查心跳、版本与实例绑定", href: "/agents" },
              { icon: BookOpenCheck, title: `${counts.approvalsPending ?? 0} 条待审批`, detail: "审批决定会绑定当前资源版本", href: "/approvals" },
              { icon: Radio, title: `${counts.recordingsRunning ?? 0} 个录制会话运行中`, detail: `Worker 已生成 ${counts.workerArtifacts ?? 0} 个加密产物`, href: "/recordings" },
            ].map((item) => (
              <Link key={item.title} href={item.href} className="flex items-center gap-3 rounded-xl border border-white/10 bg-white/5 p-3 transition hover:bg-white/10">
                <item.icon className="size-4 text-indigo-300" />
                <div className="min-w-0 flex-1"><p className="text-sm font-medium">{item.title}</p><p className="mt-0.5 truncate text-xs text-slate-400">{item.detail}</p></div>
                <ArrowRight className="size-4 text-slate-500" />
              </Link>
            ))}
          </CardContent>
        </Card>
      </div>
    </>
  );
}
