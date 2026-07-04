"use client";

import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Copy, KeyRound, RefreshCw, Save, Trash2, UserPlus } from "lucide-react";
import { toast } from "sonner";
import { platformFetch } from "@/lib/api/client";
import type { PlatformRecord, SessionUser } from "@/lib/api/types";
import { formatDate } from "@/lib/utils";
import { PageHeader } from "@/components/layout/page-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { DateTimePicker } from "@/components/ui/date-time-picker";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";

type UserRecord = PlatformRecord & {
  username?: string;
  display_name?: string;
  displayName?: string;
  status?: string;
  super_admin?: boolean;
  superAdmin?: boolean;
  active_token_count?: number;
  activeTokenCount?: number;
};

type TokenResult = SessionUser & {
  token: string;
  tokenId?: string;
  subjectId?: string;
};

function valueOf(record: PlatformRecord | undefined, key: string) {
  if (!record) return undefined;
  const snake = key.replace(/[A-Z]/g, (letter) => `_${letter.toLowerCase()}`);
  return record[key] ?? record[snake];
}

function isoInstant(value: string) {
  if (!value.trim()) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}

async function sessionRequest<T>(path: string, init: RequestInit) {
  const response = await fetch(path, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...init.headers,
    },
  });
  if (!response.ok) {
    const payload = await response.json().catch(() => ({}));
    throw new Error(payload.message ?? "请求失败");
  }
  return response.json() as Promise<T>;
}

function roleLabel(user: UserRecord) {
  return valueOf(user, "superAdmin") ? "超级管理员" : "业务用户";
}

function activeTokenCount(user: UserRecord) {
  return Number(valueOf(user, "activeTokenCount") ?? 0);
}

export function AccountSettingsPage() {
  const queryClient = useQueryClient();
  const [username, setUsername] = useState("");
  const [selfExpiresAt, setSelfExpiresAt] = useState("");
  const [newUsername, setNewUsername] = useState("");
  const [newUserExpiresAt, setNewUserExpiresAt] = useState("");
  const [issuedToken, setIssuedToken] = useState<{ title: string; token: string } | null>(null);
  const [deleteTarget, setDeleteTarget] = useState<UserRecord | null>(null);

  const sessionQuery = useQuery({
    queryKey: ["session"],
    queryFn: async () => {
      const response = await fetch("/api/auth/session");
      if (!response.ok) throw new Error("会话已失效");
      return response.json() as Promise<SessionUser>;
    },
  });
  const session = sessionQuery.data;
  const superAdmin = Boolean(session?.capabilities?.includes("ADMIN"));

  useEffect(() => {
    if (session?.subject) {
      setUsername(session.subject);
    }
  }, [session?.subject]);

  const usersQuery = useQuery({
    queryKey: ["auth-users"],
    queryFn: () => platformFetch<UserRecord[]>("auth/users"),
    enabled: superAdmin,
  });

  const updateProfileMutation = useMutation({
    mutationFn: () => sessionRequest<SessionUser>("/api/auth/session", {
      method: "PATCH",
      body: JSON.stringify({ username: username.trim(), displayName: username.trim() }),
    }),
    onSuccess: async (result) => {
      toast.success("用户名已更新");
      setUsername(result.subject);
      await queryClient.invalidateQueries({ queryKey: ["session"] });
      await sessionQuery.refetch();
      if (superAdmin) await usersQuery.refetch();
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "用户名更新失败"),
  });

  const replaceSelfTokenMutation = useMutation({
    mutationFn: () => sessionRequest<TokenResult>("/api/auth/session/token", {
      method: "POST",
      body: JSON.stringify({ expiresAt: isoInstant(selfExpiresAt) }),
    }),
    onSuccess: async (result) => {
      toast.success("我的 Token 已更换，旧 Token 已失效");
      setSelfExpiresAt("");
      setIssuedToken({ title: "新的个人 Token", token: result.token });
      await queryClient.invalidateQueries({ queryKey: ["session"] });
      await sessionQuery.refetch();
      if (superAdmin) await usersQuery.refetch();
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "Token 更换失败"),
  });

  const createUserMutation = useMutation({
    mutationFn: () => platformFetch<TokenResult>("auth/tokens", {
      method: "POST",
      body: JSON.stringify({
        username: newUsername.trim(),
        displayName: newUsername.trim(),
        expiresAt: isoInstant(newUserExpiresAt),
      }),
      idempotencyKey: crypto.randomUUID(),
    }),
    onSuccess: async (result) => {
      toast.success("用户已创建，Token 已签发");
      setNewUsername("");
      setNewUserExpiresAt("");
      setIssuedToken({ title: `用户 ${String(result.subjectId ?? result.subject ?? "")} 的 Token`, token: result.token });
      await usersQuery.refetch();
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "创建用户失败"),
  });

  const replaceUserTokenMutation = useMutation({
    mutationFn: (target: string) => platformFetch<TokenResult>(`auth/users/${encodeURIComponent(target)}/token/replace`, {
      method: "POST",
      body: JSON.stringify({}),
      idempotencyKey: crypto.randomUUID(),
    }),
    onSuccess: async (result, target) => {
      toast.success("用户 Token 已更换，旧 Token 已失效");
      setIssuedToken({ title: `用户 ${target} 的新 Token`, token: result.token });
      await usersQuery.refetch();
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "更换用户 Token 失败"),
  });

  const deleteUserMutation = useMutation({
    mutationFn: (target: string) => platformFetch<void>(`auth/users/${encodeURIComponent(target)}`, {
      method: "DELETE",
      idempotencyKey: crypto.randomUUID(),
    }),
    onSuccess: async () => {
      toast.success("用户已删除，相关 Token 已清理");
      setDeleteTarget(null);
      await usersQuery.refetch();
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "删除用户失败"),
  });

  const sortedUsers = useMemo(() => usersQuery.data ?? [], [usersQuery.data]);

  return (
    <>
      <PageHeader
        eyebrow="Account"
        title="账户与设置"
        description="管理当前账户和访问 Token；超级管理员可创建用户、强制更换 Token、删除用户。"
        actions={superAdmin ? (
          <Button variant="secondary" onClick={() => usersQuery.refetch()} disabled={usersQuery.isFetching}>
            <RefreshCw className={usersQuery.isFetching ? "animate-spin" : ""} />
            刷新用户
          </Button>
        ) : null}
      />

      <div className="grid gap-5 xl:grid-cols-[minmax(0,420px)_1fr]">
        <Card className="p-5">
          <div className="flex items-start justify-between gap-3">
            <div>
              <p className="text-sm font-semibold text-[color:var(--foreground)]">我的账户</p>
              <p className="mt-1 text-sm leading-6 text-[color:var(--muted)]">所有用户都可以修改自己的用户名，并更换自己的 Token。</p>
            </div>
            {session ? <Badge variant={superAdmin ? "success" : "info"}>{superAdmin ? "超级管理员" : "业务用户"}</Badge> : null}
          </div>

          {sessionQuery.isLoading ? (
            <div className="mt-5 space-y-3">
              <Skeleton className="h-10 w-full" />
              <Skeleton className="h-10 w-full" />
            </div>
          ) : (
            <div className="mt-5 space-y-5">
              <div className="space-y-2">
                <label className="text-sm font-medium text-[color:var(--foreground)]" htmlFor="account-username">用户名</label>
                <div className="flex gap-2">
                  <Input
                    id="account-username"
                    value={username}
                    onChange={(event) => setUsername(event.target.value)}
                    placeholder="输入用户名"
                  />
                  <Button
                    onClick={() => updateProfileMutation.mutate()}
                    disabled={!username.trim() || username.trim() === session?.subject || updateProfileMutation.isPending}
                  >
                    <Save />
                    保存
                  </Button>
                </div>
              </div>

              <div className="space-y-2 border-t pt-5">
                <label className="text-sm font-medium text-[color:var(--foreground)]">新 Token 过期时间</label>
                <DateTimePicker value={selfExpiresAt} onChange={setSelfExpiresAt} required={false} />
                <Button
                  className="w-full"
                  onClick={() => replaceSelfTokenMutation.mutate()}
                  disabled={replaceSelfTokenMutation.isPending}
                >
                  <KeyRound />
                  {replaceSelfTokenMutation.isPending ? "正在更换…" : "更换我的 Token"}
                </Button>
              </div>
            </div>
          )}
        </Card>

        {superAdmin ? (
          <Card className="overflow-hidden">
            <div className="space-y-4 border-b p-5">
              <div>
                <p className="text-sm font-semibold text-[color:var(--foreground)]">用户管理</p>
                <p className="mt-1 text-sm leading-6 text-[color:var(--muted)]">创建用户会同时签发首次 Token；普通用户只有业务操作权限，不能管理用户。</p>
              </div>
              <div className="grid gap-2 md:grid-cols-[minmax(180px,1fr)_220px_auto]">
                <Input
                  value={newUsername}
                  onChange={(event) => setNewUsername(event.target.value)}
                  placeholder="新用户名"
                  aria-label="新用户名"
                />
                <DateTimePicker value={newUserExpiresAt} onChange={setNewUserExpiresAt} required={false} />
                <Button
                  onClick={() => createUserMutation.mutate()}
                  disabled={!newUsername.trim() || createUserMutation.isPending}
                >
                  <UserPlus />
                  创建并签发
                </Button>
              </div>
            </div>

            {usersQuery.isLoading ? (
              <div className="space-y-3 p-5">
                {[1, 2, 3].map((item) => <Skeleton key={item} className="h-12 w-full" />)}
              </div>
            ) : usersQuery.isError ? (
              <div className="p-8 text-center text-sm text-red-600">
                {usersQuery.error instanceof Error ? usersQuery.error.message : "用户列表加载失败"}
              </div>
            ) : (
              <div className="scrollbar-thin overflow-x-auto">
                <table className="w-full min-w-[860px] table-fixed text-left text-sm">
                  <thead className="theme-muted-panel text-xs font-medium uppercase tracking-wide text-[color:var(--muted)]">
                    <tr>
                      <th className="w-[220px] px-4 py-3">用户名</th>
                      <th className="w-[150px] px-4 py-3">权限</th>
                      <th className="w-[120px] px-4 py-3">有效 Token</th>
                      <th className="w-[180px] px-4 py-3">创建时间</th>
                      <th className="w-[220px] px-4 py-3 text-right">操作</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y">
                    {sortedUsers.map((user) => {
                      const target = String(valueOf(user, "username") ?? "");
                      const isSelf = target === session?.subject;
                      const isSuperAdmin = Boolean(valueOf(user, "superAdmin"));
                      return (
                        <tr key={String(valueOf(user, "id") ?? target)} className="theme-row">
                          <td className="px-4 py-3.5">
                            <div className="min-w-0">
                              <p className="truncate font-medium text-[color:var(--foreground)]">{target}</p>
                              <p className="truncate text-xs text-[color:var(--muted)]">{String(valueOf(user, "displayName") ?? valueOf(user, "display_name") ?? target)}</p>
                            </div>
                          </td>
                          <td className="px-4 py-3.5">
                            <Badge variant={isSuperAdmin ? "success" : "info"}>{roleLabel(user)}</Badge>
                          </td>
                          <td className="px-4 py-3.5 text-[color:var(--foreground)]">{activeTokenCount(user)}</td>
                          <td className="px-4 py-3.5 text-[color:var(--muted)]">{formatDate(valueOf(user, "createdAt"))}</td>
                          <td className="px-4 py-3.5">
                            <div className="flex justify-end gap-2">
                              {!isSelf ? (
                                <Button
                                  variant="secondary"
                                  size="sm"
                                  onClick={() => replaceUserTokenMutation.mutate(target)}
                                  disabled={replaceUserTokenMutation.isPending}
                                >
                                  <KeyRound />
                                  更换 Token
                                </Button>
                              ) : null}
                              {!isSuperAdmin ? (
                                <Button
                                  variant="secondary"
                                  size="sm"
                                  onClick={() => setDeleteTarget(user)}
                                  disabled={deleteUserMutation.isPending}
                                >
                                  <Trash2 />
                                  删除
                                </Button>
                              ) : null}
                            </div>
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </Card>
        ) : null}
      </div>

      <Dialog open={Boolean(issuedToken)} onOpenChange={(open) => !open && setIssuedToken(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{issuedToken?.title ?? "Token 已生成"}</DialogTitle>
            <DialogDescription>这是唯一一次显示明文 Token，请立即复制并交给对应用户保存。</DialogDescription>
          </DialogHeader>
          <div className="break-all rounded-lg border border-amber-200 bg-amber-50 p-4 font-mono text-xs text-amber-950">
            {issuedToken?.token}
          </div>
          <DialogFooter>
            <Button onClick={async () => {
              await navigator.clipboard.writeText(issuedToken?.token ?? "");
              toast.success("Token 已复制");
            }}>
              <Copy />
              复制 Token
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog open={Boolean(deleteTarget)} onOpenChange={(open) => !open && setDeleteTarget(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>删除用户</DialogTitle>
            <DialogDescription>删除后会清理该用户的所有访问 Token，用户将无法继续登录。</DialogDescription>
          </DialogHeader>
          <div className="rounded-lg border bg-[var(--surface-subtle)] p-4 text-sm">
            确认删除用户 <span className="font-semibold text-[color:var(--foreground)]">{String(valueOf(deleteTarget ?? {}, "username") ?? "")}</span>？
          </div>
          <DialogFooter>
            <Button variant="secondary" onClick={() => setDeleteTarget(null)} disabled={deleteUserMutation.isPending}>取消</Button>
            <Button
              onClick={() => deleteUserMutation.mutate(String(valueOf(deleteTarget ?? {}, "username") ?? ""))}
              disabled={deleteUserMutation.isPending}
            >
              <Trash2 />
              {deleteUserMutation.isPending ? "正在删除…" : "确认删除"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </>
  );
}
