"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import {
  Activity,
  ArrowRight,
  Cpu,
  Database,
  Network,
  ServerCog,
  ShieldCheck,
  SlidersHorizontal,
  Sparkles,
  TriangleAlert,
  Zap,
} from "lucide-react";
import { platformFetch } from "@/lib/api/client";
import { formatDate, humanize } from "@/lib/utils";
import { PageHeader } from "@/components/layout/page-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

type Dashboard = {
  checkedAt: string;
  counts: Record<string, number>;
};
type Health = {
  status: string;
  checkedAt?: string;
  services?: Record<string, { status: string; latencyMs?: number }>;
};

const stats = [
  { key: "agentsOnline", totalKey: "agentsTotal", label: "在线 Agent", href: "/agents", icon: Network, color: "text-emerald-600", background: "bg-emerald-50" },
  { key: "rulesActive", totalKey: "rulesTotal", label: "有效规则", href: "/rules", icon: SlidersHorizontal, color: "text-indigo-600", background: "bg-indigo-50" },
  { key: "rolloutsRunning", label: "运行中发布", href: "/rollouts", icon: Zap, color: "text-amber-600", background: "bg-amber-50" },
];

const workflow = [
  { title: "选择目标方法", detail: "从在线 Agent 上报的类与方法中选择故障注入目标。", href: "/agents", icon: Network },
  { title: "创建注入规则", detail: "编写 Mock 或异常脚本，先做服务端校验和试运行。", href: "/rules/new", icon: SlidersHorizontal },
  { title: "发布到目标环境", detail: "把规则下发到在线实例，必要时一键卸载恢复原始字节码。", href: "/rollouts", icon: Zap },
];

export function OverviewDashboard() {
  const dashboard = useQuery({ queryKey: ["overview", "dashboard"], queryFn: () => platformFetch<Dashboard>("dashboard/overview"), refetchInterval: 20_000 });
  const health = useQuery({ queryKey: ["overview", "health"], queryFn: () => platformFetch<Health>("control/health"), refetchInterval: 20_000 });
  const counts = dashboard.data?.counts ?? {};

  return (
    <>
      <PageHeader
        eyebrow="Fault Injection"
        title="故障注入控制台"
        description="先聚焦最小闭环：接入应用与 Agent、创建规则、发布规则、卸载恢复。"
        actions={<><span className="hidden text-xs text-slate-400 sm:inline">更新于 {formatDate(dashboard.data?.checkedAt)}</span><Button asChild><Link href="/rules/new"><Sparkles />创建规则</Link></Button></>}
      />

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
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

      <div className="mt-5 grid gap-5 xl:grid-cols-[1.1fr_0.9fr]">
        <Card>
          <CardHeader>
            <div className="flex items-center justify-between gap-3">
              <div>
                <CardTitle>故障注入工作流</CardTitle>
                <CardDescription>按最短路径完成一次规则创建、发布和恢复。</CardDescription>
              </div>
              <Badge variant={health.data?.status === "UP" ? "success" : "danger"}><Activity className="mr-1 size-3" />{health.data?.status ?? "检查中"}</Badge>
            </div>
          </CardHeader>
          <CardContent className="space-y-3">
            {workflow.map((item, index) => (
              <Link key={item.title} href={item.href} className="flex items-center gap-4 rounded-xl border border-slate-100 p-4 transition hover:border-indigo-200 hover:bg-indigo-50/40">
                <div className="flex size-9 shrink-0 items-center justify-center rounded-lg bg-indigo-50 text-indigo-600">
                  <item.icon className="size-4" />
                </div>
                <div className="min-w-0 flex-1">
                  <p className="text-sm font-semibold text-slate-900">{index + 1}. {item.title}</p>
                  <p className="mt-1 text-sm text-slate-500">{item.detail}</p>
                </div>
                <ArrowRight className="size-4 text-slate-400" />
              </Link>
            ))}
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

      <Card className="mt-5 bg-slate-950 text-white">
        <CardHeader>
          <div className="mb-2 flex size-10 items-center justify-center rounded-xl bg-indigo-500"><ShieldCheck className="size-5" /></div>
          <CardTitle className="text-white">当前关注项</CardTitle>
          <CardDescription className="text-slate-400">只展示故障注入闭环相关的运行信号。</CardDescription>
        </CardHeader>
        <CardContent className="grid gap-3 md:grid-cols-3">
          {[
            { icon: TriangleAlert, title: `${Math.max(0, (counts.agentsTotal ?? 0) - (counts.agentsOnline ?? 0))} 个 Agent 未在线`, detail: "检查心跳、版本与实例绑定", href: "/agents" },
            { icon: SlidersHorizontal, title: `${Math.max(0, (counts.rulesTotal ?? 0) - (counts.rulesActive ?? 0))} 条规则未生效`, detail: "确认规则状态、目标方法和版本", href: "/rules" },
            { icon: Zap, title: `${counts.rolloutsRunning ?? 0} 个发布运行中`, detail: "查看实例执行结果，必要时卸载恢复", href: "/rollouts" },
          ].map((item) => (
            <Link key={item.title} href={item.href} className="flex items-center gap-3 rounded-xl border border-white/10 bg-white/5 p-3 transition hover:bg-white/10">
              <item.icon className="size-4 text-indigo-300" />
              <div className="min-w-0 flex-1"><p className="text-sm font-medium">{item.title}</p><p className="mt-0.5 truncate text-xs text-slate-400">{item.detail}</p></div>
              <ArrowRight className="size-4 text-slate-500" />
            </Link>
          ))}
        </CardContent>
      </Card>
    </>
  );
}
