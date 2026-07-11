"use client";

import type { ReactNode } from "react";
import type { BytecodeSnapshotKind, ClassIdentity } from "@/lib/api/bytecode";
import type { SnapshotMeta } from "@/lib/bytecode/snapshots";
import {
  ACTUAL_NOTICE,
  formatEpochMillis,
  kindBadgeVariant,
  PREVIEW_NOTICE,
  SNAPSHOT_KIND_DESCRIPTION,
  SNAPSHOT_KIND_LABEL,
} from "@/lib/bytecode/labels";
import { formatBytes } from "@/lib/utils";
import { Badge } from "@/components/ui/badge";
import { Card } from "@/components/ui/card";

/**
 * One of the three INPUT / PLANNED / APPLIED metadata cards. Surfaces the ClassLoader
 * isolation field, revision, content hash, size and collection time. The PLANNED card
 * is explicitly annotated as a read-only preview that never modified the JVM; the
 * APPLIED card is annotated as the bytes actually running in the JVM.
 */
export function SnapshotMetadataCard({
  kind,
  meta,
  identity,
  action,
  pending,
}: {
  kind: BytecodeSnapshotKind;
  meta?: SnapshotMeta;
  identity?: ClassIdentity | null;
  action?: ReactNode;
  pending?: boolean;
}) {
  const notice = kind === "PLANNED" ? PREVIEW_NOTICE : kind === "APPLIED" ? ACTUAL_NOTICE : "Kairo 转换器接收";
  const classLoaderId = identity?.classLoaderId;

  return (
    <Card className="flex flex-col gap-3 p-4" data-testid={`snapshot-card-${kind.toLowerCase()}`}>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <Badge variant={kindBadgeVariant(kind)}>{SNAPSHOT_KIND_LABEL[kind]}</Badge>
          <span className="text-xs font-medium text-[color:var(--muted)]" data-testid={`snapshot-notice-${kind.toLowerCase()}`}>{notice}</span>
        </div>
        {action}
      </div>
      <p className="text-xs leading-5 text-[color:var(--muted)]">{SNAPSHOT_KIND_DESCRIPTION[kind]}</p>
      <dl className="grid grid-cols-[88px_1fr] gap-x-3 gap-y-2 text-sm">
        <Row label="ClassLoader" mono>
          {classLoaderId ? (
            <span className="font-mono text-xs break-all text-[color:var(--foreground)]" data-testid={`snapshot-classloader-${kind.toLowerCase()}`}>
              {classLoaderId}
            </span>
          ) : <span className="text-[color:var(--muted)]">-</span>}
        </Row>
        <Row label="revision" mono>
          {meta ? <span className="font-mono text-xs">r{meta.revision}</span> : <Placeholder pending={pending} />}
        </Row>
        <Row label="hash" mono>
          {meta?.hash ? (
            <span className="font-mono text-xs break-all" title={meta.hash}>{shortHash(meta.hash)}</span>
          ) : <Placeholder pending={pending} />}
        </Row>
        <Row label="size">
          {meta?.sizeBytes !== undefined ? formatBytes(meta.sizeBytes) : kind === "INPUT"
            ? <span className="text-[color:var(--muted)]">需拉取字节码</span>
            : <Placeholder pending={pending} />}
        </Row>
        <Row label="采集时间">
          {meta?.capturedAtMillis ? (
            <span className="whitespace-nowrap text-[color:var(--muted)]">{formatEpochMillis(meta.capturedAtMillis)}</span>
          ) : kind === "PLANNED" ? <span className="text-[color:var(--muted)]">预览无采集时间</span> : <Placeholder pending={pending} />}
        </Row>
      </dl>
    </Card>
  );
}

function Row({ label, children, mono = false }: { label: string; children: ReactNode; mono?: boolean }) {
  return (
    <>
      <dt className={`self-start text-[color:var(--muted)] ${mono ? "font-mono text-xs" : "text-xs"}`}>{label}</dt>
      <dd className="min-w-0">{children}</dd>
    </>
  );
}

function Placeholder({ pending }: { pending?: boolean }) {
  return pending
    ? <span className="text-[color:var(--muted)]">采集中…</span>
    : <span className="text-[color:var(--muted)]">-</span>;
}

function shortHash(hash: string): string {
  return hash.length > 20 ? `${hash.slice(0, 14)}…${hash.slice(-6)}` : hash;
}
