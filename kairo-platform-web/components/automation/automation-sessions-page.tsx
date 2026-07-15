"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Bot, RefreshCw, RotateCcw } from "lucide-react";
import { toast } from "sonner";
import {
  createAutomationSession,
  listAutomationSessions,
  revertAutomationSession,
} from "@/lib/api/automation";
import { platformErrorMessage } from "@/lib/api/error";
import { formatDate } from "@/lib/utils";
import { PageHeader } from "@/components/layout/page-header";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import type { AutomationSession } from "@/lib/api/types";

const STATUS_VARIANT: Record<string, "default" | "success" | "warning" | "danger" | "neutral" | "info"> = {
  CREATED: "neutral",
  ACTIVE: "info",
  COMPLETED: "success",
  EXPIRED: "warning",
  REVERTED: "neutral",
  FAILED: "danger",
};

export function AutomationSessionsPage() {
  const queryClient = useQueryClient();
  const [caller, setCaller] = useState("ai-bot");
  const [source, setSource] = useState("web");
  const [applicationId, setApplicationId] = useState("app-default");
  const [environmentId, setEnvironmentId] = useState("env-default");
  const [profile, setProfile] = useState("SAFE");

  const sessionsQuery = useQuery({
    queryKey: ["automation-sessions"],
    queryFn: () => listAutomationSessions(),
  });

  const createMutation = useMutation({
    mutationFn: () =>
      createAutomationSession({
        caller,
        source,
        applicationId,
        environmentId,
        requestedCapabilityProfile: profile,
        ttlMillis: 600000,
      }),
    onSuccess: () => {
      toast.success("AI 自动化会话已创建");
      queryClient.invalidateQueries({ queryKey: ["automation-sessions"] });
    },
    onError: (error) => toast.error(platformErrorMessage(error) || "创建失败"),
  });

  const revertMutation = useMutation({
    mutationFn: (id: string) => revertAutomationSession(id),
    onSuccess: () => {
      toast.success("已一键撤销");
      queryClient.invalidateQueries({ queryKey: ["automation-sessions"] });
    },
    onError: (error) => toast.error(platformErrorMessage(error) || "撤销失败"),
  });

  const sessions: AutomationSession[] = sessionsQuery.data ?? [];

  return (
    <div className="space-y-6">
      <PageHeader
        eyebrow="V1.6 AI First"
        title="AI 自动化会话"
        description="AI/自动化调用的顶层边界：带 TTL、可观察、可撤销。会话只缩小 Token 权限，不扩大。"
      />

      <Card className="p-4">
        <div className="grid grid-cols-1 gap-3 md:grid-cols-5">
          <Input placeholder="调用方" value={caller} onChange={(e) => setCaller(e.target.value)} />
          <Input placeholder="来源" value={source} onChange={(e) => setSource(e.target.value)} />
          <Input placeholder="应用" value={applicationId} onChange={(e) => setApplicationId(e.target.value)} />
          <Input placeholder="环境" value={environmentId} onChange={(e) => setEnvironmentId(e.target.value)} />
          <Input placeholder="档位" value={profile} onChange={(e) => setProfile(e.target.value)} />
        </div>
        <div className="mt-3 flex justify-end">
          <Button onClick={() => createMutation.mutate()} disabled={createMutation.isPending}>
            <Bot className="size-4" /> 创建会话
          </Button>
        </div>
      </Card>

      <div className="flex items-center justify-between">
        <h2 className="text-sm font-semibold text-slate-700">会话列表</h2>
        <Button
          variant="ghost"
          size="sm"
          onClick={() => sessionsQuery.refetch()}
          disabled={sessionsQuery.isFetching}
        >
          <RefreshCw className="size-4" /> 刷新
        </Button>
      </div>

      {sessions.length === 0 ? (
        <EmptyState icon={Bot} title="暂无 AI 自动化会话" description="创建一个会话开始受控试用。" />
      ) : (
        <div className="space-y-2">
          {sessions.map((s) => (
            <Card key={s.sessionId} className="flex flex-wrap items-center gap-3 p-3">
              <Badge variant={STATUS_VARIANT[s.status] ?? "secondary"}>{s.status}</Badge>
              <span className="font-mono text-xs text-slate-700">{s.sessionId}</span>
              <span className="text-xs text-slate-500">{s.caller} · {s.source}</span>
              <span className="text-xs text-slate-500">档位 {s.maxCapabilityProfile}</span>
              <span className="text-xs text-slate-400">创建 {formatDate(new Date(s.createdAt))}</span>
              <div className="ml-auto">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={revertMutation.isPending || s.status === "REVERTED" || s.status === "EXPIRED"}
                  onClick={() => revertMutation.mutate(s.sessionId)}
                >
                  <RotateCcw className="size-4" /> 一键撤销
                </Button>
              </div>
            </Card>
          ))}
        </div>
      )}
    </div>
  );
}
