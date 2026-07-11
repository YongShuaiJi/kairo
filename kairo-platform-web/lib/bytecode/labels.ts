import type {
  BytecodeSnapshotKind,
  DecompilationStatus,
  TransformationStatus,
} from "@/lib/api/bytecode";

/** Short labels for the three snapshot kinds, as shown on metadata cards and selectors. */
export const SNAPSHOT_KIND_LABEL: Record<BytecodeSnapshotKind, string> = {
  INPUT: "转换输入",
  PLANNED: "计划预览",
  APPLIED: "实际运行",
};

/** One-line description of what each kind means. */
export const SNAPSHOT_KIND_DESCRIPTION: Record<BytecodeSnapshotKind, string> = {
  INPUT: "Kairo 转换器本次接收到的字节码（转换前）",
  PLANNED: "只读预览：按当前规则推算的计划字节码，未修改 JVM",
  APPLIED: "转换实际完成后，从 JVM 重新读取的实际运行字节码",
};

/**
 * The two annotations the page must make unambiguous. PLANNED is a read-only preview
 * that never touches the JVM; APPLIED reflects the bytes actually running in the JVM.
 */
export const PREVIEW_NOTICE = "只读预览 · 未修改 JVM";
export const ACTUAL_NOTICE = "JVM 实际运行";

export type BadgeVariant = "default" | "success" | "warning" | "danger" | "neutral" | "info";

export function kindBadgeVariant(kind: BytecodeSnapshotKind): BadgeVariant {
  switch (kind) {
    case "INPUT":
      return "neutral";
    case "PLANNED":
      return "info";
    case "APPLIED":
      return "success";
  }
}

export const TRANSFORMATION_STATUS_LABEL: Record<TransformationStatus, string> = {
  STARTED: "进行中",
  SUCCEEDED: "成功",
  FAILED: "失败",
  VERIFIED: "已校验",
  RECOVERED: "已回滚",
  SKIPPED: "未变更",
};

export function statusBadgeVariant(status: TransformationStatus): BadgeVariant {
  switch (status) {
    case "SUCCEEDED":
    case "VERIFIED":
      return "success";
    case "STARTED":
      return "info";
    case "FAILED":
      return "danger";
    case "RECOVERED":
      return "warning";
    case "SKIPPED":
      return "neutral";
  }
}

export const DECOMPILATION_STATUS_LABEL: Record<DecompilationStatus, string> = {
  SUCCESS: "反编译成功",
  UNAVAILABLE: "反编译不可用",
  FAILED: "反编译失败",
};

/** Format epoch milliseconds (the wire time unit for snapshots) as a zh-CN timestamp. */
export function formatEpochMillis(value: unknown): string {
  if (value === null || value === undefined || value === "") return "-";
  const millis = Number(value);
  if (!Number.isFinite(millis) || millis <= 0) return "-";
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  }).format(new Date(millis));
}

/** Render a {@code KIND@revision} selector label. */
export function selectorLabel(kind: BytecodeSnapshotKind, revision: number): string {
  return `${kind}@${revision}`;
}

/** Parse a {@code KIND@revision} selector label back into a structured selector. */
export function parseSelector(value: string): { kind: BytecodeSnapshotKind; revision: number } | null {
  if (!value) return null;
  const at = value.lastIndexOf("@");
  if (at <= 0 || at === value.length - 1) return null;
  const rawKind = value.slice(0, at).toUpperCase();
  if (rawKind !== "INPUT" && rawKind !== "PLANNED" && rawKind !== "APPLIED") return null;
  const revision = Number(value.slice(at + 1));
  if (!Number.isFinite(revision) || revision < 0) return null;
  return { kind: rawKind, revision };
}
