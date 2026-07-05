"use client";

import { useEffect, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Copy, KeyRound, Save } from "lucide-react";
import { toast } from "sonner";
import type { SessionUser } from "@/lib/api/types";
import { cn } from "@/lib/utils";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Skeleton } from "@/components/ui/skeleton";

type TokenResult = SessionUser & {
  token: string;
};

type AccountSelfPanelProps = {
  user: SessionUser | null;
  onUserChange: (user: SessionUser) => void;
  className?: string;
};

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

export function AccountSelfPanel({ user, onUserChange, className }: AccountSelfPanelProps) {
  const queryClient = useQueryClient();
  const [username, setUsername] = useState("");
  const [issuedToken, setIssuedToken] = useState<string | null>(null);

  useEffect(() => {
    if (user?.subject) {
      setUsername(user.subject);
    }
  }, [user?.subject]);

  async function refreshSharedQueries() {
    await queryClient.invalidateQueries({ queryKey: ["session"] });
    await queryClient.invalidateQueries({ queryKey: ["auth-users"] });
  }

  const updateProfileMutation = useMutation({
    mutationFn: () => sessionRequest<SessionUser>("/api/auth/session", {
      method: "PATCH",
      body: JSON.stringify({ username: username.trim(), displayName: username.trim() }),
    }),
    onSuccess: async (result) => {
      toast.success("用户名已更新");
      setUsername(result.subject);
      onUserChange(result);
      await refreshSharedQueries();
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "用户名更新失败"),
  });

  const replaceSelfTokenMutation = useMutation({
    mutationFn: () => sessionRequest<TokenResult>("/api/auth/session/token", {
      method: "POST",
      body: JSON.stringify({}),
    }),
    onSuccess: async (result) => {
      toast.success("我的 Token 已更换，旧 Token 已失效");
      setIssuedToken(result.token);
      onUserChange(result);
      await refreshSharedQueries();
    },
    onError: (error) => toast.error(error instanceof Error ? error.message : "Token 更换失败"),
  });

  return (
    <div className={cn("space-y-4", className)} onKeyDown={(event) => event.stopPropagation()}>
      {!user ? (
        <div className="space-y-3">
          <Skeleton className="h-10 w-full" />
          <Skeleton className="h-10 w-full" />
        </div>
      ) : (
        <>
          <div className="space-y-2">
            <label className="text-xs font-medium text-[color:var(--foreground)]" htmlFor="account-menu-username">用户名</label>
            <div className="flex gap-2">
              <Input
                id="account-menu-username"
                value={username}
                onChange={(event) => setUsername(event.target.value)}
                placeholder="输入用户名"
              />
              <Button
                type="button"
                onClick={() => updateProfileMutation.mutate()}
                disabled={!username.trim() || username.trim() === user.subject || updateProfileMutation.isPending}
              >
                <Save />
                保存
              </Button>
            </div>
          </div>

          <div className="space-y-2 border-t pt-4">
            <p className="text-xs leading-5 text-[color:var(--muted)]">
              更换后旧 Token 会立即失效；Token 续期只能由超级管理员在用户管理中操作。
            </p>
            <Button
              type="button"
              className="w-full"
              onClick={() => replaceSelfTokenMutation.mutate()}
              disabled={replaceSelfTokenMutation.isPending}
            >
              <KeyRound />
              {replaceSelfTokenMutation.isPending ? "正在更换…" : "更换我的 Token"}
            </Button>
          </div>
        </>
      )}

      <Dialog open={Boolean(issuedToken)} onOpenChange={(open) => !open && setIssuedToken(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>新的个人 Token</DialogTitle>
            <DialogDescription>这是唯一一次显示明文 Token，请立即复制并保存。</DialogDescription>
          </DialogHeader>
          <div className="break-all rounded-lg border border-amber-200 bg-amber-50 p-4 font-mono text-xs text-amber-950">
            {issuedToken}
          </div>
          <DialogFooter>
            <Button onClick={async () => {
              await navigator.clipboard.writeText(issuedToken ?? "");
              toast.success("Token 已复制");
            }}>
              <Copy />
              复制 Token
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  );
}
