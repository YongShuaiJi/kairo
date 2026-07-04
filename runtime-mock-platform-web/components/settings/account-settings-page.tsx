"use client";

import { useMemo, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { CalendarClock, Copy, KeyRound, RefreshCw, Settings, Trash2, UserPlus } from "lucide-react";
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
import { EmptyState } from "@/components/ui/empty-state";
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

function roleLabel(user: UserRecord) {
  return valueOf(user, "superAdmin") ? "超级管理员" : "业务用户";
}

function activeTokenCount(user: UserRecord) {
  return Number(valueOf(user, "activeTokenCount") ?? 0);
}

export function AccountSettingsPage() {
  const [newUsername, setNewUsername] = useState("");
  const [newUserExpiresAt, setNewUserExpiresAt] = useState("");
  const [issuedToken, setIssuedToken] = useState<{ title: string; token: string } | null>(null);
  const [renewTarget, setRenewTarget] = useState<UserRecord | null>(null);
  const [renewUserExpiresAt, setRenewUserExpiresAt] = useState("");
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

  const usersQuery = useQuery({
    queryKey: ["auth-users"],
    queryFn: () => platformFetch<UserRecord[]>("auth/users"),
    enabled: superAdmin,
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

  const renewUserTokenMutation = useMutation({
    mutationFn: (target: string) => platformFetch<PlatformRecord>(`auth/users/${encodeURIComponent(target)}/tokens/renew`, {
      method: "POST",
      body: JSON.stringify({ expiresAt: isoInstant(renewUserExpiresAt) }),
      idempotencyKey: crypto.randomUUID(),
    }),
    onSuccess: async (_result, target) => {
      toast.success(`用户 ${target} 的 Token 已续期`);
      setRenewTarget(null);
      setRenewUserExpiresAt("");
      await usersQuery.refetch();
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "续期用户 Token 失败"),
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
        title="用户管理"
        description="当前账户和个人 Token 已移到右上角用户菜单；超级管理员可在这里创建用户、续期用户 Token、强制更换 Token、删除用户。"
        actions={superAdmin ? (
          <Button variant="secondary" onClick={() => usersQuery.refetch()} disabled={usersQuery.isFetching}>
            <RefreshCw className={usersQuery.isFetching ? "animate-spin" : ""} />
            刷新用户
          </Button>
        ) : null}
      />

      {sessionQuery.isLoading ? (
        <Card className="space-y-3 p-5">
          <Skeleton className="h-12 w-full" />
          <Skeleton className="h-12 w-full" />
          <Skeleton className="h-12 w-full" />
        </Card>
      ) : superAdmin ? (
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
              <table className="w-full min-w-[910px] table-fixed text-left text-sm">
                <thead className="theme-muted-panel text-xs font-medium uppercase tracking-wide text-[color:var(--muted)]">
                  <tr>
                    <th className="w-[190px] px-4 py-3">用户名</th>
                    <th className="w-[130px] px-4 py-3">权限</th>
                    <th className="w-[100px] px-4 py-3">有效 Token</th>
                    <th className="w-[150px] px-4 py-3">创建时间</th>
                    <th className="w-[340px] px-4 py-3 text-right">操作</th>
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
                            {!isSelf && activeTokenCount(user) > 0 ? (
                              <Button
                                variant="secondary"
                                size="sm"
                                onClick={() => {
                                  setRenewTarget(user);
                                  setRenewUserExpiresAt("");
                                }}
                                disabled={renewUserTokenMutation.isPending}
                              >
                                <CalendarClock />
                                续期
                              </Button>
                            ) : null}
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
      ) : (
        <Card>
          <EmptyState
            icon={Settings}
            title="用户管理仅超级管理员可用"
            description="当前账户资料和个人 Token 请在右上角用户菜单中维护。"
          />
        </Card>
      )}

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

      <Dialog open={Boolean(renewTarget)} onOpenChange={(open) => {
        if (!open) {
          setRenewTarget(null);
          setRenewUserExpiresAt("");
        }
      }}>
        <DialogContent
          className="max-w-md overflow-visible"
          onInteractOutside={(event) => event.preventDefault()}
        >
          <DialogHeader>
            <DialogTitle>续期用户 Token</DialogTitle>
            <DialogDescription>只更新该用户当前有效 Token 的过期时间，不会生成新的明文 Token。</DialogDescription>
          </DialogHeader>
          <form onSubmit={(event) => {
            event.preventDefault();
            const target = String(valueOf(renewTarget ?? {}, "username") ?? "");
            if (target) renewUserTokenMutation.mutate(target);
          }} className="space-y-4">
            <div className="rounded-lg border bg-[var(--surface-subtle)] p-3 text-sm">
              用户 <span className="font-semibold text-[color:var(--foreground)]">{String(valueOf(renewTarget ?? {}, "username") ?? "")}</span>
            </div>
            <div className="space-y-2">
              <span className="text-sm font-medium text-[color:var(--foreground)]">过期时间</span>
              <DateTimePicker
                value={renewUserExpiresAt}
                onChange={setRenewUserExpiresAt}
                required={false}
              />
            </div>
            <DialogFooter>
              <Button
                type="button"
                variant="secondary"
                onClick={() => {
                  setRenewTarget(null);
                  setRenewUserExpiresAt("");
                }}
                disabled={renewUserTokenMutation.isPending}
              >
                取消
              </Button>
              <Button type="submit" disabled={renewUserTokenMutation.isPending}>
                <CalendarClock />
                {renewUserTokenMutation.isPending ? "正在续期…" : "确认续期"}
              </Button>
            </DialogFooter>
          </form>
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
