import type {
  BytecodeDiffChangeType,
  BytecodeDiffResult,
  DecompilationResult,
  MethodDiff,
} from "@/lib/api/bytecode";

export const CHANGE_TYPE_LABEL: Record<BytecodeDiffChangeType, string> = {
  ADDED: "新增方法",
  REMOVED: "删除方法",
  MODIFIED: "修改方法",
};

export function changeTypeBadgeVariant(changeType: BytecodeDiffChangeType): "success" | "danger" | "warning" {
  switch (changeType) {
    case "ADDED":
      return "success";
    case "REMOVED":
      return "danger";
    case "MODIFIED":
      return "warning";
  }
}

/** Render {@code methodName + methodDescriptor} as a compact signature. */
export function methodSignature(method: MethodDiff): string {
  return `${method.methodName}${method.methodDescriptor}`;
}

/**
 * True only when a real, non-blank source is present - never fabricated. Mirrors the
 * agent SPI invariant: a result whose status is not SUCCESS never carries source code.
 */
export function decompilationAvailable(result: DecompilationResult | null | undefined): boolean {
  return Boolean(
    result
      && result.status === "SUCCESS"
      && typeof result.sourceCode === "string"
      && result.sourceCode.trim().length > 0,
  );
}

/** Human-readable reason explaining why decompilation is unavailable, for the notice. */
export function decompilationReason(result: DecompilationResult | null | undefined): string {
  if (!result) {
    return "当前诊断接口未返回反编译源码；下方结构化字节码 Diff 为权威对比。";
  }
  if (Array.isArray(result.diagnostics) && result.diagnostics.length > 0) {
    return result.diagnostics.join("；");
  }
  switch (result.status) {
    case "UNAVAILABLE":
      return "Agent 未配置反编译器，无法产出源码。";
    case "FAILED":
      return "反编译器执行失败或超时。";
    default:
      return "反编译源码暂不可用。";
  }
}

export type DiffSummary = {
  identical: boolean;
  normalized: boolean;
  methodCount: number;
  structuralCount: number;
  /** True when the two snapshots are byte-identical and there is nothing to show. */
  empty: boolean;
};

export function summarizeDiff(result: BytecodeDiffResult | null | undefined): DiffSummary | null {
  if (!result) return null;
  const methodCount = result.methodDiffs.length;
  const structuralCount = result.structuralDiffs.length;
  return {
    identical: result.identical,
    normalized: result.normalized,
    methodCount,
    structuralCount,
    empty: result.identical && methodCount === 0 && structuralCount === 0,
  };
}

/** A flat, render-friendly view of one method diff. */
export type MethodDiffView = {
  key: string;
  signature: string;
  changeType: BytecodeDiffChangeType;
  instructionDiffs: string[];
  attributeDiffs: string[];
};

export function toMethodDiffViews(result: BytecodeDiffResult | null | undefined): MethodDiffView[] {
  if (!result) return [];
  return result.methodDiffs.map((method, index) => ({
    key: `${method.methodName}@${method.methodDescriptor}@${index}`,
    signature: methodSignature(method),
    changeType: method.changeType,
    instructionDiffs: method.instructionDiffs,
    attributeDiffs: method.attributeDiffs,
  }));
}

/**
 * Classify a single instruction diff line so the view can color it. The agent's diff
 * lines use a {@code +}/{@code -}/{@code ~} prefix convention; absent a prefix the line
 * is treated as shared context.
 */
export function instructionLineKind(line: string): "added" | "removed" | "context" {
  const trimmed = line.trimStart();
  if (trimmed.startsWith("+")) return "added";
  if (trimmed.startsWith("-")) return "removed";
  return "context";
}
