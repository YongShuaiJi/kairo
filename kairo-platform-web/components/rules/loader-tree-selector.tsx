"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { ChevronDown, ChevronRight, Layers } from "lucide-react";
import { platformFetch } from "@/lib/api/client";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";

type LoaderInfo = {
  loaderId: string;
  loaderClassName: string;
  parentLoaderId: string | null;
  codeSource?: string;
  frameworkLoader?: string;
};

type LoaderTreeResponse = {
  loaders: LoaderInfo[];
  tree: Record<string, LoaderInfo[]>;
  count: number;
  bootstrapLoaderId: string;
  agentAvailable?: boolean;
};

function shortLoaderId(id: string) {
  if (!id) return "";
  return id.length > 10 ? `${id.slice(0, 6)}…${id.slice(-4)}` : id;
}

function loaderLabel(loader: LoaderInfo) {
  const name = loader.loaderClassName || "bootstrap";
  return `${name} (${shortLoaderId(loader.loaderId)})`;
}

/**
 * V1.5 §4.1/§5: the ClassLoader tree selector for the class selector. Lets an operator pick a
 * {@code classLoaderId} to disambiguate same-name classes across loaders, and renders the
 * parent&rarr;children loader hierarchy with framework labels (Spring Boot / Tomcat / plugin).
 *
 * The selected loader id is used to filter the target search client-side: {@code targets/search}
 * now keys entries by classLoaderId, so each loader's same-name class is a distinct choice.
 */
export function LoaderTreeSelector({
  applicationId,
  environmentId,
  value,
  onValueChange,
  disabled,
}: {
  applicationId: string;
  environmentId: string;
  value: string;
  onValueChange: (loaderId: string) => void;
  disabled?: boolean;
}) {
  const [treeOpen, setTreeOpen] = useState(false);
  const ready = Boolean(applicationId && environmentId) && !disabled;
  const query = useQuery({
    queryKey: ["loader-tree", applicationId, environmentId],
    queryFn: () =>
      platformFetch<LoaderTreeResponse>(
        `targets/loaders?applicationId=${encodeURIComponent(applicationId)}&environmentId=${encodeURIComponent(environmentId)}`,
      ),
    enabled: ready,
  });

  const loaders = query.data?.loaders ?? [];
  const tree = query.data?.tree ?? {};
  const bootstrapId = query.data?.bootstrapLoaderId ?? "bootstrap";
  const selected = loaders.find((loader) => loader.loaderId === value);

  return (
    <div className="space-y-2 rounded-lg border border-[color:var(--border)] bg-[var(--surface-subtle)] p-2.5">
      <div className="flex flex-wrap items-center gap-2">
        <Layers className="size-3.5 shrink-0 text-slate-400" />
        <select
          aria-label="选择 ClassLoader"
          value={value}
          onChange={(event) => onValueChange(event.target.value)}
          disabled={!ready || query.isLoading}
          className="min-w-[200px] max-w-full flex-1 rounded-md border border-[color:var(--border)] bg-[var(--surface)] px-2 py-1.5 text-xs text-[color:var(--foreground)] disabled:opacity-50"
        >
          <option value="">全部加载器（不筛选）</option>
          {loaders.map((loader) => (
            <option key={loader.loaderId} value={loader.loaderId}>
              {loaderLabel(loader)}
            </option>
          ))}
        </select>
        <button
          type="button"
          onClick={() => setTreeOpen((open) => !open)}
          disabled={!ready || loaders.length === 0}
          className="inline-flex items-center gap-1 rounded-md border border-[color:var(--border)] px-2 py-1 text-[10px] font-medium text-[color:var(--muted)] transition hover:text-[color:var(--foreground)] disabled:opacity-50"
        >
          <ChevronDown className={`size-3 transition ${treeOpen ? "rotate-180" : ""}`} />
          {treeOpen ? "收起加载器树" : "查看加载器树"}
        </button>
      </div>
      {selected?.frameworkLoader ? (
        <Badge variant="info" className="text-[10px]">{selected.frameworkLoader}</Badge>
      ) : null}
      {query.isLoading ? <Skeleton className="h-8 w-full" /> : null}
      {query.isError ? (
        <p className="text-[10px] text-[color:var(--muted)]">加载器树加载失败，可继续按全部加载器搜索。</p>
      ) : null}
      {treeOpen && loaders.length > 0 ? (
        <LoaderTreeView
          tree={tree}
          bootstrapId={bootstrapId}
          onSelect={onValueChange}
          selected={value}
        />
      ) : null}
      {ready && !query.isLoading && loaders.length === 0 ? (
        <p className="text-[10px] text-[color:var(--muted)]">当前应用/环境暂无在线 Agent 上报关加载器。</p>
      ) : null}
    </div>
  );
}

function LoaderTreeView({
  tree,
  bootstrapId,
  onSelect,
  selected,
}: {
  tree: Record<string, LoaderInfo[]>;
  bootstrapId: string;
  onSelect: (loaderId: string) => void;
  selected: string;
}) {
  return (
    <div className="max-h-52 overflow-y-auto rounded-md border border-[color:var(--border)] bg-[var(--surface)] p-2">
      <ul className="space-y-0.5">
        {renderChildren(tree, bootstrapId, 0, onSelect, selected)}
      </ul>
    </div>
  );
}

function renderChildren(
  tree: Record<string, LoaderInfo[]>,
  parentId: string,
  depth: number,
  onSelect: (loaderId: string) => void,
  selected: string,
) {
  const children = tree[parentId] ?? [];
  if (children.length === 0) return null;
  return children.map((loader) => (
    <li key={loader.loaderId}>
      <button
        type="button"
        onClick={() => onSelect(loader.loaderId)}
        className={`flex w-full items-center gap-1.5 rounded px-1.5 py-1 text-left text-[11px] transition ${
          selected === loader.loaderId
            ? "bg-[var(--primary-soft)] font-medium text-[color:var(--primary-strong)]"
            : "text-[color:var(--foreground)] hover:bg-[var(--surface-muted)]"
        }`}
        style={{ paddingLeft: `${depth * 12 + 6}px` }}
      >
        <ChevronRight className="size-3 shrink-0 text-slate-300" />
        <span className="truncate font-mono">{loader.loaderClassName || "bootstrap"}</span>
        {loader.frameworkLoader ? (
          <Badge variant="neutral" className="ml-auto shrink-0 text-[9px]">{loader.frameworkLoader}</Badge>
        ) : null}
      </button>
      {renderChildren(tree, loader.loaderId, depth + 1, onSelect, selected)}
    </li>
  ));
}
