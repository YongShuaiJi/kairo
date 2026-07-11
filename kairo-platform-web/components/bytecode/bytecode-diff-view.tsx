"use client";

import { useState } from "react";
import { FileCode2, GitCompareArrows, ShieldAlert } from "lucide-react";
import type { BytecodeDiffResult } from "@/lib/api/bytecode";
import {
  changeTypeBadgeVariant,
  CHANGE_TYPE_LABEL,
  decompilationAvailable,
  decompilationReason,
  instructionLineKind,
  summarizeDiff,
  toMethodDiffViews,
} from "@/lib/bytecode/diff";
import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import { SegmentedControl } from "@/components/ui/segmented-control";

type DiffTab = "structured" | "source";

/**
 * Structured bytecode diff between two {@code KIND@revision} snapshots. The structured
 * method/instruction diff is authoritative and always retained. The source decompilation
 * tab is honest: when the agent did not return decompiled source (the current contract),
 * it shows a clear notice and the structured bytecode diff remains available - source is
 * never fabricated.
 */
export function BytecodeDiffView({
  result,
  loading,
  error,
  fromLabel,
  toLabel,
}: {
  result?: BytecodeDiffResult | null;
  loading?: boolean;
  error?: string | null;
  fromLabel?: string;
  toLabel?: string;
}) {
  const [tab, setTab] = useState<DiffTab>("structured");

  if (loading) {
    return (
      <Card className="p-5">
        <div className="mb-4 h-5 w-48 animate-pulse rounded bg-[var(--surface-strong)]" />
        <div className="space-y-2">{[1, 2, 3].map((i) => <div key={i} className="h-4 w-full animate-pulse rounded bg-[var(--surface-strong)]" />)}</div>
      </Card>
    );
  }
  if (error) {
    return (
      <Card className="flex flex-col items-center justify-center p-8 text-center" data-testid="diff-error">
        <div className="mb-3 rounded-full bg-red-50 p-3 text-red-600"><ShieldAlert className="size-5" /></div>
        <h3 className="font-semibold text-slate-900">Diff 加载失败</h3>
        <p className="mt-1 max-w-md text-sm text-slate-500">{error}</p>
      </Card>
    );
  }
  if (!result) {
    return (
      <Card className="flex flex-col items-center justify-center p-8 text-center text-sm text-[color:var(--muted)]" data-testid="diff-empty">
        <GitCompareArrows className="mb-2 size-6 text-slate-400" />
        选择两个 KIND@revision 快照后点击「对比」查看结构化字节码 Diff。
      </Card>
    );
  }

  const summary = summarizeDiff(result);
  const methods = toMethodDiffViews(result);
  return (
    <Card className="overflow-hidden" data-testid="bytecode-diff-view">
      <div className="border-b p-4">
        <div className="flex flex-wrap items-center gap-2">
          <Badge variant="neutral" className="font-mono">{fromLabel ?? `${result.fromKind}@${result.fromRevision.value}`}</Badge>
          <GitCompareArrows className="size-4 text-[color:var(--muted)]" />
          <Badge variant="neutral" className="font-mono">{toLabel ?? `${result.toKind}@${result.toRevision.value}`}</Badge>
          {summary?.identical ? <Badge variant="success">完全相同</Badge> : null}
          {result.normalized ? <Badge variant="info">已归一化</Badge> : null}
        </div>
        {result.summary ? <p className="mt-2 text-xs text-[color:var(--muted)]">{result.summary}</p> : null}
      </div>

      <div className="flex flex-wrap items-center gap-2 border-b p-3">
        <SegmentedControl
          value={tab}
          onValueChange={setTab}
          items={[
            { value: "structured" as const, label: "结构化 Diff", icon: GitCompareArrows },
            { value: "source" as const, label: "源码反编译", icon: FileCode2 },
          ]}
          aria-label="Diff 视图"
        />
        <span className="ml-auto text-xs text-[color:var(--muted)]">
          {summary ? `方法 ${summary.methodCount} · 结构 ${summary.structuralCount}` : ""}
        </span>
      </div>

      {tab === "structured" ? (
        <StructuredDiff result={result} methods={methods} />
      ) : (
        <SourceDecompilation
          result={result}
          methods={methods}
          fromLabel={fromLabel}
          toLabel={toLabel}
        />
      )}
    </Card>
  );
}

function StructuredDiff({
  result,
  methods,
}: {
  result: BytecodeDiffResult;
  methods: ReturnType<typeof toMethodDiffViews>;
}) {
  if (result.identical && methods.length === 0 && result.structuralDiffs.length === 0) {
    return (
      <div className="p-6 text-center text-sm text-[color:var(--muted)]" data-testid="diff-identical">
        两个快照归一化后字节码完全相同，无差异。
      </div>
    );
  }
  return (
    <div className="space-y-4 p-4" data-testid="bytecode-diff-methods">
      {result.structuralDiffs.length > 0 ? (
        <section>
          <h4 className="mb-2 text-xs font-semibold uppercase tracking-[0.14em] text-slate-400">结构差异</h4>
          <ul className="space-y-1">
            {result.structuralDiffs.map((line, index) => (
              <li key={index} className="rounded-md border bg-[var(--surface-subtle)] px-3 py-1.5 font-mono text-xs">{line}</li>
            ))}
          </ul>
        </section>
      ) : null}
      <section>
        <h4 className="mb-2 text-xs font-semibold uppercase tracking-[0.14em] text-slate-400">方法 / 指令差异</h4>
        {methods.length > 0 ? (
          <div className="space-y-3">
            {methods.map((method) => (
              <div key={method.key} className="rounded-lg border">
                <div className="flex flex-wrap items-center gap-2 border-b bg-[var(--surface-subtle)] px-3 py-2">
                  <Badge variant={changeTypeBadgeVariant(method.changeType)}>{CHANGE_TYPE_LABEL[method.changeType]}</Badge>
                  <code className="font-mono text-xs text-[color:var(--foreground)]">{method.signature}</code>
                </div>
                {method.attributeDiffs.length > 0 ? (
                  <div className="space-y-1 border-b px-3 py-2">
                    {method.attributeDiffs.map((line, index) => (
                      <p key={index} className="font-mono text-xs text-[color:var(--muted)]">{line}</p>
                    ))}
                  </div>
                ) : null}
                <div className="overflow-x-auto">
                  <table className="w-full min-w-[420px] font-mono text-xs">
                    <tbody>
                      {method.instructionDiffs.map((line, index) => {
                        const kind = instructionLineKind(line);
                        return (
                          <tr key={index} className={instructionRowClass(kind)}>
                            <td className="whitespace-pre px-3 py-1" data-testid="instruction-line">{line}</td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </div>
            ))}
          </div>
        ) : (
          <p className="text-sm text-[color:var(--muted)]">没有方法级指令差异。</p>
        )}
      </section>
    </div>
  );
}

function SourceDecompilation({ result, methods, fromLabel, toLabel }: {
  result: BytecodeDiffResult;
  methods: ReturnType<typeof toMethodDiffViews>;
  fromLabel?: string;
  toLabel?: string;
}) {
  const before = result.fromDecompilation;
  const after = result.toDecompilation;
  const beforeAvailable = decompilationAvailable(before);
  const afterAvailable = decompilationAvailable(after);
  if (beforeAvailable || afterAvailable) {
    return (
      <div className="p-4" data-testid="decompilation-source">
        <p className="mb-2 text-xs text-[color:var(--muted)]">
          近似源码仅用于阅读，以结构化字节码 Diff 为准；局部变量名和表达式可能与原源码不同。
        </p>
        <div className="grid gap-3 lg:grid-cols-2" data-testid="source-before-after">
          <SourcePanel label={fromLabel ?? `${result.fromKind}@${result.fromRevision.value}`}
            result={before} available={beforeAvailable} />
          <SourcePanel label={toLabel ?? `${result.toKind}@${result.toRevision.value}`}
            result={after} available={afterAvailable} />
        </div>
      </div>
    );
  }
  return (
    <div className="space-y-4 p-4" data-testid="decompilation-notice">
      <div className="rounded-lg border border-amber-200 bg-amber-50 p-4 text-sm leading-6 text-amber-900">
        <div className="flex items-start gap-2">
          <ShieldAlert className="mt-0.5 size-4 shrink-0 text-amber-600" />
          <div>
            <p className="font-semibold">反编译源码不可用</p>
            <p className="mt-1 text-xs">Before：{decompilationReason(before)}；After：{decompilationReason(after)}</p>
          </div>
        </div>
      </div>
      <div>
        <p className="mb-2 text-xs font-semibold uppercase tracking-[0.14em] text-slate-400">已保留结构化字节码 Diff</p>
        <StructuredDiff result={result} methods={methods} />
      </div>
    </div>
  );
}

function SourcePanel({ label, result, available }: {
  label: string;
  result: BytecodeDiffResult["fromDecompilation"];
  available: boolean;
}) {
  return (
    <section className="min-w-0 rounded-lg border">
      <div className="border-b bg-[var(--surface-subtle)] px-3 py-2">
        <code className="text-xs font-semibold">{label}</code>
        {result?.decompilerName ? <span className="ml-2 text-xs text-[color:var(--muted)]">{result.decompilerName}</span> : null}
      </div>
      {available && result?.sourceCode ? (
        <pre className="max-h-[32rem] overflow-auto p-3 font-mono text-xs leading-5">{result.sourceCode}</pre>
      ) : (
        <p className="p-3 text-xs text-amber-800">{decompilationReason(result)}</p>
      )}
    </section>
  );
}

function instructionRowClass(kind: "added" | "removed" | "context"): string {
  switch (kind) {
    case "added":
      return "bg-emerald-50 text-emerald-800";
    case "removed":
      return "bg-red-50 text-red-800";
    default:
      return "text-[color:var(--foreground)]";
  }
}
