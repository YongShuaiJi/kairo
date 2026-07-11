"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Camera, Eye, GitCompareArrows, Layers, RefreshCw, Search, ShieldCheck } from "lucide-react";
import { toast } from "sonner";
import {
  captureBytecode,
  fetchBytecodeBytes,
  fetchBytecodeDiff,
  fetchBytecodeTransformations,
  previewBytecode,
  type BytecodeDiffResult,
  type CaptureResponse,
  type PreviewResponse,
} from "@/lib/api/bytecode";
import type { PlatformError, SessionUser } from "@/lib/api/types";
import { decodeClassId } from "@/lib/bytecode/class-id";
import { defaultDiffSelection, deriveSnapshots } from "@/lib/bytecode/snapshots";
import { parseSelector, selectorLabel } from "@/lib/bytecode/labels";
import { Button } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { Input } from "@/components/ui/input";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Skeleton } from "@/components/ui/skeleton";
import { SnapshotMetadataCard } from "./snapshot-metadata-card";
import { BytecodeDiffView } from "./bytecode-diff-view";
import { TransformationHistory } from "./transformation-history";

function errorMessage(error: unknown, fallback: string): string {
  if (error instanceof Error) {
    const payload = (error as Error & { payload?: PlatformError }).payload;
    if (payload?.message) return payload.message;
    return error.message;
  }
  return fallback;
}

export function BytecodeInspector({ agentId }: { agentId: string }) {
  const router = useRouter();
  const searchParams = useSearchParams();
  const queryClient = useQueryClient();

  const activeClassId = searchParams.get("classId") ?? "";
  const [classIdInput, setClassIdInput] = useState(activeClassId);
  const [previewResult, setPreviewResult] = useState<PreviewResponse | undefined>(undefined);
  const [captureResult, setCaptureResult] = useState<CaptureResponse | undefined>(undefined);
  const [fetchedInputSize, setFetchedInputSize] = useState<number | undefined>(undefined);
  const [diffResult, setDiffResult] = useState<BytecodeDiffResult | undefined>(undefined);
  const [diffError, setDiffError] = useState<string | undefined>(undefined);
  const [fromLabel, setFromLabel] = useState("");
  const [toLabel, setToLabel] = useState("");
  const [selectionInitialized, setSelectionInitialized] = useState(false);

  useEffect(() => {
    setClassIdInput(activeClassId);
  }, [activeClassId]);

  // Reset all per-class derived state when the committed classId changes.
  useEffect(() => {
    setPreviewResult(undefined);
    setCaptureResult(undefined);
    setFetchedInputSize(undefined);
    setDiffResult(undefined);
    setDiffError(undefined);
    setSelectionInitialized(false);
  }, [activeClassId]);

  const sessionQuery = useQuery({
    queryKey: ["session"],
    queryFn: async () => {
      const response = await fetch("/api/auth/session");
      if (!response.ok) throw new Error("会话已失效");
      return response.json() as Promise<SessionUser>;
    },
  });
  const canManage = Boolean(
    sessionQuery.data?.capabilities?.includes("ADMIN")
      || sessionQuery.data?.capabilities?.includes("AGENT_MANAGE"),
  );

  const transformationsQuery = useQuery({
    queryKey: ["bytecode-transformations", agentId, activeClassId],
    queryFn: () => fetchBytecodeTransformations(agentId, activeClassId),
    enabled: Boolean(activeClassId),
  });

  const derived = useMemo(
    () => deriveSnapshots(transformationsQuery.data, previewResult, captureResult, fetchedInputSize),
    [transformationsQuery.data, previewResult, captureResult, fetchedInputSize],
  );

  const identity = transformationsQuery.data?.classIdentity ?? decodeClassId(activeClassId);

  // Initialize diff selectors once the snapshot set is known for this classId.
  useEffect(() => {
    if (selectionInitialized) return;
    if (derived.selectors.length < 1) return;
    const def = defaultDiffSelection(derived.selectors);
    setFromLabel(def.from ? selectorLabel(def.from.kind, def.from.revision) : "");
    setToLabel(def.to ? selectorLabel(def.to.kind, def.to.revision) : "");
    setSelectionInitialized(true);
  }, [derived.selectors, selectionInitialized]);

  const captureMutation = useMutation({
    mutationFn: () => captureBytecode(agentId, activeClassId),
    onSuccess: (result) => {
      setCaptureResult(result);
      toast.success("已重新读取 JVM 实际运行字节码");
    },
    onError: (error) => toast.error(errorMessage(error, "采集失败")),
  });

  const previewMutation = useMutation({
    mutationFn: async () => {
      const inputRevision = transformationsQuery.data?.currentRevision.value ?? 0;
      // Prefer the transform INPUT. Before the first transform there is no INPUT@0;
      // after an explicit capture, APPLIED@0 is the honest preview baseline.
      const inputKind = derived.cards.input ? "INPUT" : captureResult?.captured ? "APPLIED" : null;
      if (!inputKind) throw new Error("尚无可预览快照，请先采集 JVM 实际运行字节码");
      const { bytes } = await fetchBytecodeBytes(agentId, activeClassId, inputKind, inputRevision);
      const preview = await previewBytecode(agentId, activeClassId, bytes);
      return { preview, inputSize: bytes.byteLength };
    },
    onSuccess: ({ preview, inputSize }) => {
      setPreviewResult(preview);
      setFetchedInputSize(inputSize);
      toast.success("只读预览完成，未修改 JVM");
    },
    onError: (error) => toast.error(errorMessage(error, "预览失败")),
  });

  const diffMutation = useMutation({
    mutationFn: async () => {
      const from = parseSelector(fromLabel);
      const to = parseSelector(toLabel);
      if (!from || !to) throw new Error("请选择两个有效的 KIND@revision 快照");
      if (fromLabel === toLabel) throw new Error("请选择两个不同的快照进行对比");
      return fetchBytecodeDiff(agentId, activeClassId, from, to);
    },
    onSuccess: (result) => {
      setDiffResult(result);
      setDiffError(undefined);
    },
    onError: (error) => {
      setDiffResult(undefined);
      setDiffError(errorMessage(error, "对比失败"));
    },
  });

  function commitClassId() {
    const next = classIdInput.trim();
    if (!next) {
      toast.error("请输入 classId");
      return;
    }
    if (next === activeClassId) return;
    const params = new URLSearchParams(searchParams.toString());
    params.set("classId", next);
    router.replace(`/agents/${agentId}/bytecode?${params.toString()}`, { scroll: false });
    void queryClient.invalidateQueries({ queryKey: ["bytecode-transformations", agentId] });
  }

  const selectorOptions = derived.selectors.map((s) => ({
    value: selectorLabel(s.kind, s.revision),
    label: selectorLabel(s.kind, s.revision),
  }));

  return (
    <>
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <Button variant="secondary" size="sm" asChild>
          <Link href="/agents"><ArrowLeft />返回 Agent 诊断</Link>
        </Button>
        <div className="flex items-center gap-2 text-xs text-[color:var(--muted)]">
          <ShieldCheck className="size-3.5 text-emerald-500" />
          同源 BFF 代理，浏览器不接触 Agent 地址或 Token
        </div>
      </div>

      <Card className="mb-4 p-4">
        <div className="flex flex-col gap-3 lg:flex-row lg:items-end">
          <div className="flex-1">
            <label htmlFor="bytecode-class-id" className="mb-1.5 block text-sm font-medium text-slate-700">
              目标类 classId
              <span className="ml-2 text-xs font-normal text-[color:var(--muted)]">base64url(classLoaderId|binaryClassName)</span>
            </label>
            <Input
              id="bytecode-class-id"
              value={classIdInput}
              onChange={(event) => setClassIdInput(event.target.value)}
              onKeyDown={(event) => { if (event.key === "Enter") commitClassId(); }}
              placeholder="粘贴 classId，例如 b2RkLWxvYWRlcnxjb20vZXhhbXBsZS9Gb28"
              className="font-mono text-xs"
            />
            {activeClassId ? (
              <p className="mt-1.5 break-all text-xs text-[color:var(--muted)]" data-testid="class-id-decoded">
                {identity ? `${identity.binaryClassName} @ ${identity.classLoaderId}` : "(classId 无法解码，仍可尝试请求)"}
              </p>
            ) : null}
          </div>
          <Button onClick={commitClassId} disabled={!classIdInput.trim()}>
            <Search />加载
          </Button>
        </div>
        <div className="mt-3 rounded-lg border border-sky-200 bg-sky-50 px-3 py-2 text-xs leading-5 text-sky-800" data-testid="read-only-notice">
          本页为只读诊断视图。<strong>预览（PLANNED）</strong>仅按规则离线推算计划字节码，<strong>只读预览，未修改 JVM</strong>；<strong>APPLIED</strong>来自转换完成后从 JVM 重新读取的实际运行字节码。classLoaderId 区分同名类的不同加载器。
        </div>
      </Card>

      {!activeClassId ? (
        <EmptyState
          icon={Layers}
          title="请输入目标类 classId"
          description="classId 由 Agent 对 classLoaderId|binaryClassName 做 base64url 编码生成，可在 Agent 诊断或目标方法选择器中获取。"
        />
      ) : transformationsQuery.isLoading ? (
        <div className="space-y-4">
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">{[0, 1, 2].map((i) => <Skeleton key={i} className="h-44 w-full" />)}</div>
          <Skeleton className="h-64 w-full" />
        </div>
      ) : transformationsQuery.isError ? (
        <Card className="flex flex-col items-center justify-center p-8 text-center" data-testid="transformations-error">
          <div className="mb-3 rounded-full bg-red-50 p-3 text-red-600"><RefreshCw className="size-5" /></div>
          <h3 className="font-semibold text-slate-900">转换历史加载失败</h3>
          <p className="mt-1 max-w-md text-sm text-slate-500">{errorMessage(transformationsQuery.error, "无法读取转换历史")}</p>
          <Button className="mt-4" variant="secondary" onClick={() => transformationsQuery.refetch()}>重新加载</Button>
        </Card>
      ) : (
        <div className="space-y-4">
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            <SnapshotMetadataCard
              kind="INPUT"
              meta={derived.cards.input}
              identity={identity}
              pending={previewMutation.isPending}
            />
            <SnapshotMetadataCard
              kind="PLANNED"
              meta={derived.cards.planned}
              identity={identity}
              pending={previewMutation.isPending}
              action={(
                <Button
                  size="sm"
                  variant="secondary"
                  onClick={() => previewMutation.mutate()}
                  disabled={!canManage || previewMutation.isPending}
                  title={canManage ? "拉取 INPUT 字节码并离线推算计划字节码（不修改 JVM）" : "需要 AGENT_MANAGE 权限"}
                >
                  <Eye />{previewMutation.isPending ? "预览中…" : "只读预览"}
                </Button>
              )}
            />
            <SnapshotMetadataCard
              kind="APPLIED"
              meta={derived.cards.applied}
              identity={identity}
              pending={captureMutation.isPending}
              action={(
                <Button
                  size="sm"
                  onClick={() => captureMutation.mutate()}
                  disabled={!canManage || captureMutation.isPending}
                  title={canManage ? "重新从 JVM 读取实际运行字节码" : "需要 AGENT_MANAGE 权限"}
                >
                  <Camera />{captureMutation.isPending ? "采集中…" : "采集"}
                </Button>
              )}
            />
          </div>

          {transformationsQuery.data ? <TransformationHistory data={transformationsQuery.data} /> : null}

          <Card className="p-4">
            <div className="mb-3 flex flex-wrap items-center gap-2">
              <GitCompareArrows className="size-4 text-[color:var(--muted)]" />
              <h2 className="text-sm font-semibold text-[color:var(--foreground)]">字节码 Diff</h2>
              <span className="text-xs text-[color:var(--muted)]">选择两个 KIND@revision 快照进行结构化对比</span>
            </div>
            <div className="grid gap-3 sm:grid-cols-[1fr_1fr_auto] sm:items-end">
              <div>
                <label className="mb-1.5 block text-xs text-[color:var(--muted)]">起点（from）</label>
                <Select value={fromLabel} onValueChange={setFromLabel}>
                  <SelectTrigger aria-label="起点快照"><SelectValue placeholder="选择 KIND@revision" /></SelectTrigger>
                  <SelectContent>
                    {selectorOptions.map((option) => <SelectItem key={`from-${option.value}`} value={option.value}>{option.label}</SelectItem>)}
                  </SelectContent>
                </Select>
              </div>
              <div>
                <label className="mb-1.5 block text-xs text-[color:var(--muted)]">终点（to）</label>
                <Select value={toLabel} onValueChange={setToLabel}>
                  <SelectTrigger aria-label="终点快照"><SelectValue placeholder="选择 KIND@revision" /></SelectTrigger>
                  <SelectContent>
                    {selectorOptions.map((option) => <SelectItem key={`to-${option.value}`} value={option.value}>{option.label}</SelectItem>)}
                  </SelectContent>
                </Select>
              </div>
              <Button onClick={() => diffMutation.mutate()} disabled={diffMutation.isPending || !fromLabel || !toLabel || fromLabel === toLabel}>
                <GitCompareArrows />{diffMutation.isPending ? "对比中…" : "对比"}
              </Button>
            </div>
            {fromLabel && toLabel && fromLabel === toLabel ? (
              <p className="mt-2 text-xs text-amber-700">起点与终点相同，请选择两个不同的快照。</p>
            ) : null}
          </Card>

          <BytecodeDiffView
            result={diffResult}
            loading={diffMutation.isPending}
            error={diffError}
            fromLabel={fromLabel}
            toLabel={toLabel}
          />
        </div>
      )}
    </>
  );
}
