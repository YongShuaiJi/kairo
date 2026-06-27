"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import * as DropdownMenu from "@radix-ui/react-dropdown-menu";
import {
  Box,
  ChevronDown,
  CircleGauge,
  Command,
  FlaskConical,
  Menu,
  Network,
  ScrollText,
  Search,
  Settings,
  ShieldCheck,
  SlidersHorizontal,
  X,
  Zap,
} from "lucide-react";
import { cn } from "@/lib/utils";
import { platformFetch } from "@/lib/api/client";
import type { SessionUser } from "@/lib/api/types";
import { Badge } from "@/components/ui/badge";
import { RuntimeMockIcon } from "@/components/brand/runtime-mock-icon";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";

const navigation = [
  {
    label: "工作空间",
    items: [
      { href: "/overview", label: "运行总览", icon: CircleGauge },
      { href: "/applications", label: "应用与环境", icon: Box },
      { href: "/agents", label: "Agent 管理", icon: Network },
    ],
  },
  {
    label: "故障注入",
    items: [
      { href: "/rules", label: "规则中心", icon: SlidersHorizontal },
      { href: "/rollouts", label: "发布管理", icon: Zap },
    ],
  },
  {
    label: "系统",
    items: [
      { href: "/settings", label: "平台设置", icon: Settings, capability: "ADMIN" },
    ],
  },
];

export function AppShell({ children }: { children: React.ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const workspaceRoute = pathname.startsWith("/rules/");
  const [mobileOpen, setMobileOpen] = useState(false);
  const [commandOpen, setCommandOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [user, setUser] = useState<SessionUser | null>(null);
  const [platformHealthy, setPlatformHealthy] = useState<boolean | null>(null);

  useEffect(() => {
    fetch("/api/auth/session").then(async (response) => {
      if (response.ok) {
        const session = (await response.json()) as SessionUser;
        setUser(session);
        void platformFetch<{ status?: string }>("control/health")
          .then((result) => setPlatformHealthy(result.status === "UP"))
          .catch(() => setPlatformHealthy(false));
      } else {
        router.replace("/login");
      }
    });
  }, [router]);

  useEffect(() => {
    const handler = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        setCommandOpen(true);
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, []);

  const visibleNavigation = useMemo(
    () => navigation.map((group) => ({
      ...group,
      items: group.items.filter((item) => {
        const capability = "capability" in item && typeof item.capability === "string" ? item.capability : undefined;
        return capability === undefined
          || user?.capabilities?.includes("ADMIN")
          || user?.capabilities?.includes(capability);
      }),
    })),
    [user],
  );

  const commandItems = useMemo(
    () =>
      visibleNavigation
        .flatMap((group) => group.items)
        .filter((item) => item.label.toLowerCase().includes(query.toLowerCase())),
    [query, visibleNavigation],
  );

  async function logout() {
    await fetch("/api/auth/session", { method: "DELETE" });
    router.replace("/login");
    router.refresh();
  }

  const sidebar = (
    <div className="flex h-full flex-col">
      <div className="flex h-16 items-center gap-3 px-5">
        <RuntimeMockIcon className="size-10" />
        <div>
          <div className="text-sm font-semibold text-white">Runtime Mock</div>
          <div className="text-[10px] uppercase tracking-[0.18em] text-slate-500">Control Platform</div>
        </div>
      </div>
      <nav className="scrollbar-thin flex-1 space-y-6 overflow-y-auto p-3">
        {visibleNavigation.map((group) => (
          <div key={group.label}>
            <p className="mb-2 px-3 text-[10px] font-semibold uppercase tracking-[0.18em] text-slate-500">{group.label}</p>
            <div className="space-y-1">
              {group.items.map((item) => {
                const active = pathname === item.href || pathname.startsWith(`${item.href}/`);
                return (
                  <Link
                    key={item.href}
                    href={item.href}
                    onClick={() => setMobileOpen(false)}
                    className={cn(
                      "flex items-center gap-3 rounded-lg px-3 py-2.5 text-sm transition-colors",
                      active ? "bg-indigo-500/20 font-medium text-white" : "text-slate-400 hover:bg-white/5 hover:text-slate-100",
                    )}
                  >
                    <item.icon className={cn("size-4.5", active && "text-indigo-300")} />
                    {item.label}
                  </Link>
                );
              })}
            </div>
          </div>
        ))}
      </nav>
      <div className="border-t border-white/10 p-4">
        <div className="rounded-xl border border-white/10 bg-white/5 p-3">
          <div className="flex items-center gap-2 text-xs text-slate-300">
            <ShieldCheck className={cn("size-4", platformHealthy === true ? "text-emerald-400" : platformHealthy === false ? "text-red-400" : "text-slate-500")} />
            {platformHealthy === true ? "控制面连接正常" : platformHealthy === false ? "控制面连接异常" : "正在检查控制面"}
          </div>
          <div className="mt-2 h-1.5 overflow-hidden rounded-full bg-white/10">
            <div className={cn("h-full rounded-full transition-all", platformHealthy === true ? "w-full bg-emerald-400" : platformHealthy === false ? "w-full bg-red-400" : "w-1/2 animate-pulse bg-slate-500")} />
          </div>
        </div>
      </div>
    </div>
  );

  return (
    <div className={cn("min-h-screen", workspaceRoute && "lg:h-screen lg:overflow-hidden")}>
      <aside className="fixed inset-y-0 left-0 z-40 hidden w-64 bg-slate-950 lg:block">{sidebar}</aside>
      {mobileOpen ? (
        <div className="fixed inset-0 z-50 lg:hidden">
          <button className="absolute inset-0 bg-slate-950/50" onClick={() => setMobileOpen(false)} aria-label="关闭导航" />
          <aside className="relative h-full w-72 bg-slate-950 shadow-2xl">
            <button aria-label="关闭导航菜单" className="absolute right-3 top-3 rounded-lg p-2 text-slate-400 hover:bg-white/10" onClick={() => setMobileOpen(false)}><X className="size-5" /></button>
            {sidebar}
          </aside>
        </div>
      ) : null}

      <div className={cn("lg:pl-64", workspaceRoute && "lg:flex lg:h-screen lg:min-h-0 lg:flex-col lg:overflow-hidden")}>
        <header className={cn("sticky top-0 z-30 flex h-16 items-center gap-3 border-b border-slate-200/80 bg-white/85 px-4 backdrop-blur-xl sm:px-6", workspaceRoute && "lg:relative lg:shrink-0")}>
          <Button aria-label="打开导航菜单" variant="ghost" size="icon" className="lg:hidden" onClick={() => setMobileOpen(true)}><Menu /></Button>
          <button onClick={() => setCommandOpen(true)} className="flex h-9 w-full max-w-md items-center gap-2 rounded-lg border border-slate-200 bg-slate-50 px-3 text-left text-sm text-slate-400 hover:border-slate-300 hover:bg-white">
            <Search className="size-4" />
            <span className="flex-1">搜索页面与功能</span>
            <kbd className="hidden rounded border bg-white px-1.5 py-0.5 font-mono text-[10px] text-slate-500 sm:inline-flex">⌘ K</kbd>
          </button>
          <div className="ml-auto flex items-center gap-1">
            {user?.demo ? <Badge variant="warning" className="hidden sm:inline-flex">Demo 模式</Badge> : null}
            <DropdownMenu.Root>
              <DropdownMenu.Trigger asChild>
                <button aria-label="用户菜单" className="ml-1 flex items-center gap-2 rounded-lg p-1.5 hover:bg-slate-100">
                  <span className="flex size-8 items-center justify-center rounded-lg bg-indigo-100 text-xs font-bold text-indigo-700">{user?.displayName?.slice(0, 1) ?? "R"}</span>
                  <span className="hidden text-left sm:block">
                    <span className="block text-xs font-medium text-slate-800">{user?.displayName ?? "平台用户"}</span>
                    <span className="block text-[10px] text-slate-400">{user?.roles?.[0] ?? "未加载"}</span>
                  </span>
                  <ChevronDown className="size-3.5 text-slate-400" />
                </button>
              </DropdownMenu.Trigger>
              <DropdownMenu.Portal>
                <DropdownMenu.Content align="end" className="z-50 min-w-48 rounded-xl border bg-white p-1.5 shadow-xl">
                  {user?.capabilities?.includes("ADMIN") ? <DropdownMenu.Item asChild><Link href="/settings" className="flex cursor-pointer items-center gap-2 rounded-lg px-3 py-2 text-sm outline-none hover:bg-slate-100"><Settings className="size-4" />账户与设置</Link></DropdownMenu.Item> : null}
                  <DropdownMenu.Separator className="my-1 h-px bg-slate-100" />
                  <DropdownMenu.Item onSelect={logout} className="cursor-pointer rounded-lg px-3 py-2 text-sm text-red-600 outline-none hover:bg-red-50">退出登录</DropdownMenu.Item>
                </DropdownMenu.Content>
              </DropdownMenu.Portal>
            </DropdownMenu.Root>
          </div>
        </header>
        {user?.demo ? (
          <div className={cn("border-b border-amber-200 bg-amber-50 px-4 py-2 text-center text-xs text-amber-800", workspaceRoute && "lg:shrink-0")}>
            当前使用演示数据。写操作不会影响真实 Platform API。
          </div>
        ) : null}
        <main data-testid="app-main" className={cn("mx-auto w-full max-w-[1600px] p-4 sm:p-6 lg:p-8", workspaceRoute && "lg:min-h-0 lg:flex-1 lg:overflow-hidden")}>{children}</main>
      </div>

      <Dialog open={commandOpen} onOpenChange={setCommandOpen}>
        <DialogContent className="top-[38%] max-w-xl gap-0 p-0">
          <DialogHeader className="sr-only"><DialogTitle>快速导航</DialogTitle></DialogHeader>
          <div className="flex items-center gap-3 border-b px-4">
            <Command className="size-5 text-indigo-600" />
            <Input value={query} onChange={(event) => setQuery(event.target.value)} autoFocus placeholder="输入页面名称…" className="h-14 border-0 bg-transparent px-0 shadow-none focus:ring-0" />
          </div>
          <div className="max-h-80 overflow-y-auto p-2">
            {commandItems.map((item) => (
              <button key={item.href} onClick={() => { router.push(item.href); setCommandOpen(false); }} className="flex w-full items-center gap-3 rounded-lg px-3 py-2.5 text-left text-sm text-slate-700 hover:bg-slate-100">
                <item.icon className="size-4 text-slate-400" />
                {item.label}
                <span className="ml-auto text-xs text-slate-400">{item.href}</span>
              </button>
            ))}
            {!commandItems.length ? <div className="p-8 text-center text-sm text-slate-400"><FlaskConical className="mx-auto mb-2 size-6" />没有找到匹配功能</div> : null}
          </div>
          <div className="flex items-center gap-4 border-t bg-slate-50 px-4 py-2 text-[10px] text-slate-400"><span>↑↓ 选择</span><span>Enter 打开</span><span>Esc 关闭</span><ScrollText className="ml-auto size-3.5" /></div>
        </DialogContent>
      </Dialog>
    </div>
  );
}
