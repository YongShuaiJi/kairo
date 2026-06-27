"use client";

import { useState } from "react";
import { useRouter } from "next/navigation";
import { ArrowRight, Boxes, CheckCircle2, Eye, EyeOff, ShieldCheck, Sparkles } from "lucide-react";
import { RuntimeMockIcon } from "@/components/brand/runtime-mock-icon";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

export default function LoginPage() {
  const router = useRouter();
  const [token, setToken] = useState("");
  const [visible, setVisible] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setLoading(true);
    setError("");
    try {
      const response = await fetch("/api/auth/session", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ token }),
      });
      const payload = (await response.json()) as { message?: string };
      if (!response.ok) throw new Error(payload.message ?? "登录失败");
      router.replace("/overview");
      router.refresh();
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : "登录失败");
    } finally {
      setLoading(false);
    }
  }

  return (
    <main className="grid min-h-screen lg:grid-cols-[1.15fr_0.85fr]">
      <section className="relative hidden overflow-hidden bg-slate-950 p-12 text-white lg:flex lg:flex-col lg:justify-between">
        <div className="absolute inset-0 soft-grid opacity-20" />
        <div className="absolute -right-24 top-20 size-96 rounded-full bg-indigo-500/20 blur-3xl" />
        <div className="absolute bottom-0 left-0 size-80 rounded-full bg-sky-500/10 blur-3xl" />
        <div className="relative flex items-center gap-3">
          <RuntimeMockIcon className="size-11" />
          <div>
            <div className="font-semibold tracking-tight">Runtime Mock</div>
            <div className="text-xs text-slate-400">Control Platform</div>
          </div>
        </div>
        <div className="relative max-w-2xl">
          <div className="mb-6 inline-flex items-center gap-2 rounded-full border border-indigo-400/20 bg-indigo-400/10 px-3 py-1 text-sm text-indigo-200">
            <Sparkles className="size-4" />
            先把故障注入做简单、做稳定
          </div>
          <h1 className="text-5xl font-semibold leading-[1.1] tracking-tight">
            从规则编辑到故障注入，
            <span className="block text-indigo-300">在一个工作台里完成。</span>
          </h1>
          <p className="mt-6 max-w-xl text-lg leading-8 text-slate-300">
            管理 Java 运行时 Mock 与故障注入：选择目标方法、创建规则、发布到在线 Agent，并在需要时卸载恢复。
          </p>
          <div className="mt-9 grid max-w-xl grid-cols-3 gap-4">
            {[
              { label: "故障注入闭环", icon: Boxes },
              { label: "安全会话", icon: ShieldCheck },
              { label: "发布可恢复", icon: CheckCircle2 },
            ].map(({ label, icon: Icon }) => (
              <div key={label} className="rounded-xl border border-white/10 bg-white/5 p-4 backdrop-blur">
                <Icon className="mb-3 size-5 text-indigo-300" />
                <div className="text-sm text-slate-200">{label}</div>
              </div>
            ))}
          </div>
        </div>
        <p className="relative text-xs text-slate-500">Runtime Mock Platform · Independent Web Console</p>
      </section>

      <section className="flex items-center justify-center px-6 py-12">
        <div className="w-full max-w-md">
          <div className="mb-10 flex items-center gap-3 lg:hidden">
            <RuntimeMockIcon className="size-10" />
            <span className="font-semibold">Runtime Mock</span>
          </div>
          <div className="mb-8">
            <p className="mb-2 text-sm font-medium text-indigo-600">欢迎回来</p>
            <h2 className="text-3xl font-semibold tracking-tight text-slate-950">登录控制平台</h2>
            <p className="mt-3 text-sm leading-6 text-slate-500">
              使用 Platform API Token 建立服务端会话。Token 仅提交给本应用服务端，不会写入浏览器存储。
            </p>
          </div>
          <form onSubmit={submit} className="space-y-5">
            <label className="block">
              <span className="mb-2 block text-sm font-medium text-slate-700">Platform Token</span>
              <div className="relative">
                <Input
                  autoFocus
                  required
                  autoComplete="current-password"
                  className="h-12 pr-11 font-mono"
                  type={visible ? "text" : "password"}
                  placeholder="粘贴访问 Token"
                  value={token}
                  onChange={(event) => setToken(event.target.value)}
                />
                <button type="button" onClick={() => setVisible((value) => !value)} className="absolute right-3 top-3 text-slate-400 hover:text-slate-700" aria-label={visible ? "隐藏 Token" : "显示 Token"}>
                  {visible ? <EyeOff className="size-5" /> : <Eye className="size-5" />}
                </button>
              </div>
            </label>
            {error ? <div role="alert" className="rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">{error}</div> : null}
            <Button type="submit" size="lg" className="w-full" disabled={loading}>
              {loading ? "正在验证…" : "进入平台"}
              {!loading ? <ArrowRight /> : null}
            </Button>
          </form>
          <div className="mt-7 rounded-xl border border-slate-200 bg-slate-50 p-4 text-xs leading-5 text-slate-500">
            Demo 环境可使用 <code className="rounded bg-white px-1.5 py-0.5 font-mono text-indigo-700">runtime-mock-demo</code>。真实环境请使用管理员签发的 Token。
          </div>
        </div>
      </section>
    </main>
  );
}
