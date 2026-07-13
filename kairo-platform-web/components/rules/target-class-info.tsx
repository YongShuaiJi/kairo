"use client";

import { Badge } from "@/components/ui/badge";
import { recordValue as valueOf } from "@/lib/api/record";
import type { PlatformRecord } from "@/lib/api/types";

function supportVariant(level: string) {
  const value = level.toUpperCase();
  if (value === "SUPPORTED") return "success" as const;
  if (value === "LIMITED") return "warning" as const;
  if (value === "UNSUPPORTED") return "danger" as const;
  return "neutral" as const; // EXPERIMENTAL
}

/**
 * V1.5 §5: pure extraction of the modern-JVM target metadata from a record, reading either the
 * camelCase keys the agent echoes or the snake_case columns the platform persists (rule_target).
 * Exported so the Web can be tested without a DOM.
 */
export function targetMetadataFields(target: PlatformRecord | undefined) {
  if (!target) return null;
  return {
    proxyType: String(valueOf(target, "proxyType") ?? ""),
    supportLevel: String(valueOf(target, "supportLevel") ?? ""),
    driftStatus: String(valueOf(target, "driftStatus") ?? ""),
    loaderClass: String(valueOf(target, "loaderClass") ?? valueOf(target, "loaderClassName") ?? ""),
    frameworkLoader: String(valueOf(target, "frameworkLoader") ?? ""),
    declaredClassName: String(valueOf(target, "declaredClassName") ?? ""),
    proxyClassName: String(valueOf(target, "proxyClassName") ?? ""),
    actualEnhancedClassName: String(valueOf(target, "actualEnhancedClassName") ?? ""),
    proxySuperclass: String(valueOf(target, "proxySuperclass") ?? ""),
    recommendedTargetClass: String(valueOf(target, "recommendedTargetClass") ?? ""),
  };
}

export function supportLevelVariant(level: string) {
  return supportVariant(level);
}

/**
 * V1.5 §5: render the modern-JVM target metadata the agent echoes from DISCOVER_TARGETS and
 * RESOLVE_TARGET and the platform persists on rule_target. Surfaces, in one place:
 *  - the declared class / proxy class / actual enhanced class distinction (&sect;5: "明确区分
 *    声明类、代理类、实际增强类");
 *  - the proxy type, support level, drift status, loader/framework (&sect;5: "Lambda、synthetic、
 *    bridge 和 JDK 类显示风险与支持等级");
 *  - the analyzer's recommended target when a proxy is detected (no auto-jump, &sect;4.2).
 *
 * All fields are optional: a legacy target or a target the agent could not analyze renders no
 * badges / columns, so existing flows are unchanged.
 */
export function TargetMetadataBadges({ target }: { target: PlatformRecord | undefined }) {
  if (!target) return null;
  const proxyType = String(valueOf(target, "proxyType") ?? "");
  const supportLevel = String(valueOf(target, "supportLevel") ?? "");
  const driftStatus = String(valueOf(target, "driftStatus") ?? "");
  const loaderClass = String(valueOf(target, "loaderClass") ?? valueOf(target, "loaderClassName") ?? "");
  const frameworkLoader = String(valueOf(target, "frameworkLoader") ?? "");
  if (!proxyType && !supportLevel && !driftStatus && !frameworkLoader && !loaderClass) {
    return null;
  }
  return (
    <div className="mt-1 flex flex-wrap gap-1">
      {proxyType && proxyType !== "PLAIN" ? (
        <Badge variant="info" className="text-[10px]">代理 {proxyType}</Badge>
      ) : null}
      {supportLevel ? (
        <Badge variant={supportVariant(supportLevel)} className="text-[10px]">{supportLevel}</Badge>
      ) : null}
      {driftStatus && driftStatus !== "FRESH" ? (
        <Badge variant={driftStatus === "DRIFTED" ? "danger" : "warning"} className="text-[10px]">{driftStatus}</Badge>
      ) : null}
      {frameworkLoader ? (
        <Badge variant="neutral" className="text-[10px]">{frameworkLoader}</Badge>
      ) : null}
      {!frameworkLoader && loaderClass && loaderClass !== "bootstrap" ? (
        <Badge variant="neutral" className="text-[10px]">{loaderClass}</Badge>
      ) : null}
    </div>
  );
}

/**
 * V1.5 §5: the declared-class / proxy-class / actual-enhanced-class distinction. For a plain
 * class all three are the same and the block is hidden. For a proxy, the proxy class, its
 * superclass (the real target) and the analyzer's recommendation are shown so the operator can
 * choose what to enhance without the system auto-jumping (&sect;4.2).
 */
export function TargetClassColumns({ target }: { target: PlatformRecord | undefined }) {
  if (!target) return null;
  const declared = String(valueOf(target, "declaredClassName") ?? "");
  const proxy = String(valueOf(target, "proxyClassName") ?? "");
  const actual = String(valueOf(target, "actualEnhancedClassName") ?? "");
  const recommended = String(valueOf(target, "recommendedTargetClass") ?? "");
  const proxySuperclass = String(valueOf(target, "proxySuperclass") ?? "");
  const proxyImpact = String(valueOf(target, "proxyImpact") ?? "");
  // Nothing to distinguish: hide the block for plain classes without a recommendation.
  if (!proxy && !recommended && !proxySuperclass) {
    return null;
  }
  return (
    <div className="mt-2 space-y-1.5 rounded-md border border-[color:var(--border)] bg-[var(--surface-subtle)] p-2 text-[10px]">
      <div className="grid grid-cols-[64px_1fr] items-center gap-2">
        <span className="text-[color:var(--muted)]">声明类</span>
        <span className="break-all font-mono text-[color:var(--foreground)]">{declared || actual || "-"}</span>
      </div>
      {proxy ? (
        <div className="grid grid-cols-[64px_1fr] items-center gap-2">
          <span className="text-[color:var(--muted)]">代理类</span>
          <span className="break-all font-mono text-[color:var(--foreground)]">{proxy}</span>
        </div>
      ) : null}
      <div className="grid grid-cols-[64px_1fr] items-center gap-2">
        <span className="text-[color:var(--muted)]">实际增强类</span>
        <span className="break-all font-mono font-semibold text-[color:var(--primary-strong)]">{actual || declared || "-"}</span>
      </div>
      {proxySuperclass ? (
        <div className="grid grid-cols-[64px_1fr] items-center gap-2">
          <span className="text-[color:var(--muted)]">目标父类</span>
          <span className="break-all font-mono text-[color:var(--foreground)]">{proxySuperclass}</span>
        </div>
      ) : null}
      {recommended && recommended !== actual ? (
        <div className="flex items-start gap-1.5 rounded bg-[var(--primary-soft)] px-2 py-1 text-[color:var(--primary-strong)]">
          <span className="font-medium">建议：</span>
          <span className="break-all font-mono">改增强 {recommended}</span>
        </div>
      ) : null}
      {proxyImpact ? (
        <p className="leading-4 text-[color:var(--muted)]">{proxyImpact}</p>
      ) : null}
    </div>
  );
}
