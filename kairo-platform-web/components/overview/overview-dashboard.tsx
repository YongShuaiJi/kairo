"use client";

import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import {
  ArrowRight,
  Network,
  SlidersHorizontal,
  Sparkles,
  Zap,
} from "lucide-react";
import { platformFetch } from "@/lib/api/client";
import { formatDate } from "@/lib/utils";
import { PageHeader } from "@/components/layout/page-header";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Skeleton } from "@/components/ui/skeleton";

type Dashboard = {
  checkedAt: string;
  counts: Record<string, number>;
};

const stats = [
  { key: "injectableInstancesOnline", totalKey: "instancesTotal", label: "可注入实例", href: "/applications", icon: Network, color: "text-emerald-600", background: "bg-emerald-50" },
  { key: "rulesActive", totalKey: "rulesTotal", label: "有效规则", href: "/rules", icon: SlidersHorizontal, color: "text-indigo-600", background: "bg-indigo-50" },
  { key: "rolloutsRunning", label: "运行中发布", href: "/rollouts", icon: Zap, color: "text-amber-600", background: "bg-amber-50" },
];

const workflow = [
  { title: "选择目标方法", detail: "从在线实例中选择故障注入目标，必要时先动态加载 Agent。", href: "/applications", icon: Network },
  { title: "创建注入规则", detail: "编写 Mock 或异常脚本，先做服务端校验和试运行。", href: "/rules/new", icon: SlidersHorizontal },
  { title: "发布到目标环境", detail: "把规则下发到在线实例，必要时一键卸载恢复原始字节码。", href: "/rollouts", icon: Zap },
];

export function OverviewDashboard() {
  const dashboard = useQuery({ queryKey: ["overview", "dashboard"], queryFn: () => platformFetch<Dashboard>("dashboard/overview"), refetchInterval: 20_000 });
  const counts = dashboard.data?.counts ?? {};

  return (
    <>
      <PageHeader
        eyebrow="Fault Injection"
        title="故障注入控制台"
        description="先聚焦最小闭环：接入应用与 Agent、创建规则、发布规则、卸载恢复。"
        actions={<><span className="hidden text-xs text-slate-400 sm:inline">更新于 {formatDate(dashboard.data?.checkedAt)}</span><Button asChild><Link href="/rules/new"><Sparkles />创建规则</Link></Button></>}
      />

      <div className="grid items-stretch gap-5 xl:grid-cols-[0.44fr_0.56fr]">
        <div className="grid gap-4 sm:grid-cols-3 xl:grid-cols-1">
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

        <Card className="flex h-full flex-col">
          <CardHeader className="pb-3">
            <div>
              <CardTitle className="text-xl">故障注入工作流</CardTitle>
              <CardDescription className="mt-2 text-base">按最短路径完成一次规则创建、发布和恢复。</CardDescription>
            </div>
          </CardHeader>
          <CardContent className="flex flex-1 flex-col justify-center gap-4">
            {workflow.map((item, index) => (
              <Link key={item.title} href={item.href} className="flex min-h-28 items-center gap-5 rounded-xl border border-slate-100 p-6 transition hover:border-indigo-200 hover:bg-indigo-50/40">
                <div className="flex size-12 shrink-0 items-center justify-center rounded-xl bg-indigo-50 text-indigo-600">
                  <item.icon className="size-5" />
                </div>
                <div className="min-w-0 flex-1">
                  <p className="text-base font-semibold text-slate-900">{index + 1}. {item.title}</p>
                  <p className="mt-2 text-base text-slate-500">{item.detail}</p>
                </div>
                <ArrowRight className="size-5 text-slate-400" />
              </Link>
            ))}
          </CardContent>
        </Card>
      </div>

    </>
  );
}
