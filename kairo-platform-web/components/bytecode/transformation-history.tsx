"use client";

import type { TransformationsResponse } from "@/lib/api/bytecode";
import { formatEpochMillis, statusBadgeVariant, TRANSFORMATION_STATUS_LABEL } from "@/lib/bytecode/labels";
import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";
import { Table, TableBody, TableCell, TableContainer, TableHead, TableHeader, TableRow } from "@/components/ui/table";

/** Per-class transformation revision + bounded journal history. */
export function TransformationHistory({ data }: { data: TransformationsResponse }) {
  const history = [...data.history].sort((left, right) => right.revision.value - left.revision.value);
  return (
    <Card className="overflow-hidden" data-testid="transformation-history">
      <div className="flex flex-wrap items-center gap-2 border-b p-4">
        <h2 className="text-sm font-semibold text-[color:var(--foreground)]">转换历史</h2>
        <Badge variant="info" className="font-mono">当前 revision r{data.currentRevision.value}</Badge>
        <span className="text-xs text-[color:var(--muted)]">共 {data.count} 条记录</span>
      </div>
      <TableContainer>
        <Table className="min-w-[760px] text-left text-sm">
          <TableHeader className="theme-muted-panel whitespace-nowrap text-xs font-medium tracking-wide">
            <TableRow className="hover:bg-transparent">
              <TableHead className="px-4 py-3">revision</TableHead>
              <TableHead className="px-4 py-3">状态</TableHead>
              <TableHead className="px-4 py-3">输入 hash</TableHead>
              <TableHead className="px-4 py-3">输出 hash</TableHead>
              <TableHead className="px-4 py-3">尝试时间</TableHead>
              <TableHead className="px-4 py-3">耗时</TableHead>
              <TableHead className="px-4 py-3">诊断</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {history.length === 0 ? (
              <TableRow>
                <TableCell colSpan={7} className="px-4 py-6 text-center text-[color:var(--muted)]">
                  该类尚未被转换，revision 为 0（INITIAL）。
                </TableCell>
              </TableRow>
            ) : history.map((entry) => (
              <TableRow key={`r${entry.revision.value}`}>
                <TableCell className="whitespace-nowrap px-4 py-3 font-mono text-xs">r{entry.revision.value}</TableCell>
                <TableCell className="whitespace-nowrap px-4 py-3"><Badge variant={statusBadgeVariant(entry.status)}>{TRANSFORMATION_STATUS_LABEL[entry.status]}</Badge></TableCell>
                <TableCell className="px-4 py-3 font-mono text-xs text-[color:var(--muted)]" title={entry.inputHash ?? ""}>{shortHash(entry.inputHash)}</TableCell>
                <TableCell className="px-4 py-3 font-mono text-xs text-[color:var(--muted)]" title={entry.outputHash ?? ""}>{shortHash(entry.outputHash)}</TableCell>
                <TableCell className="whitespace-nowrap px-4 py-3 text-[color:var(--muted)]">{formatEpochMillis(entry.attemptedAtMillis)}</TableCell>
                <TableCell className="whitespace-nowrap px-4 py-3 text-[color:var(--muted)]">{entry.durationMillis} ms</TableCell>
                <TableCell className="px-4 py-3">
                  {entry.diagnostics.length > 0 ? (
                    <span className="inline-flex items-center gap-1">
                      <Badge variant={entry.diagnostics.some((d) => d.severity === "ERROR") ? "danger" : entry.diagnostics.some((d) => d.severity === "WARN") ? "warning" : "info"}>
                        {entry.diagnostics.length} 条
                      </Badge>
                      <span className="font-mono text-xs text-[color:var(--muted)]" title={entry.diagnostics.map((d) => `${d.code}: ${d.message}`).join("\n")}>
                        {entry.diagnostics[0].code}
                      </span>
                    </span>
                  ) : <span className="text-[color:var(--muted)]">-</span>}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </TableContainer>
    </Card>
  );
}

function shortHash(hash?: string | null): string {
  if (!hash) return "-";
  return hash.length > 16 ? `${hash.slice(0, 10)}…${hash.slice(-4)}` : hash;
}
