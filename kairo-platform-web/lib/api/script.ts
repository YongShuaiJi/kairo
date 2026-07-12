/**
 * Script capability API contract for V1.2 (§3.6).
 *
 * Mirrors the frozen DTOs in {@code kairo-api} and the response maps exposed by the platform's
 * {@code ScriptSessionController} / {@code ScriptCapabilityPolicyController} /
 * {@code ScriptCompileController}. The browser reaches them through the same-origin BFF at
 * {@code /api/platform}, which forwards to {@code /api/v1/...} on the platform server.
 *
 * The lifecycle endpoints (create/validate/apply/promote/revert) return the frozen
 * {@link ScriptSessionResult} DTO (lifecycle snapshot only). The read endpoints
 * ({@link getScriptSession}, {@link listScriptSessions}) return the full record map so the page
 * can render tier, target, TTL and policy revision (§3.6) without a DTO change.
 */

import { platformFetch } from "@/lib/api/client";

export type CapabilityProfile = "SAFE" | "EXTENDED" | "UNRESTRICTED";

export type ScriptSessionStatus =
  | "CREATED"
  | "VALIDATED"
  | "APPLIED"
  | "EXPIRED"
  | "REVERTED"
  | "FAILED";

export type ScriptDiagnosticPhase = "VALIDATION" | "COMPILATION" | "EXECUTION";
export type ScriptDiagnosticSeverity = "INFO" | "WARNING" | "ERROR";

export type ScriptDiagnostic = {
  phase: ScriptDiagnosticPhase;
  severity: ScriptDiagnosticSeverity;
  line: number;
  column: number;
  code: string;
  message: string;
  targetClassLoaderId?: string | null;
  suggestion?: string | null;
};

/** Lifecycle snapshot returned by the transition endpoints (frozen DTO). */
export type ScriptSessionResult = {
  sessionId: string;
  status: ScriptSessionStatus;
  createdAt: number;
  expiresAt: number;
  hitCount: number;
  diagnostics: ScriptDiagnostic[];
};

/** Full record map returned by the read endpoints, carrying tier/target/TTL for the page. */
export type ScriptSessionDetail = {
  sessionId: string;
  agentId: string;
  applicationId: string;
  target: {
    className: string;
    classLoaderId?: string | null;
    methodName: string;
    methodDescriptor: string;
  };
  scriptHash: string;
  requestedProfile: CapabilityProfile;
  effectiveProfile: CapabilityProfile;
  platformMaxProfile: CapabilityProfile;
  applicationMaxProfile: CapabilityProfile;
  policyRevision: { revision: number; hash: string };
  ttlMillis: number;
  maxHits: number;
  status: ScriptSessionStatus;
  hitCount: number;
  version: number;
  requestedBy: string;
  formalRuleId?: string | null;
  createdAt: number;
  expiresAt: number;
  appliedAt?: number | null;
  revertedAt?: number | null;
  updatedAt: number;
  diagnostics: ScriptDiagnostic[];
};

export type ScriptSessionEvent = {
  id: string;
  sessionId: string;
  action: string;
  fromStatus?: string | null;
  toStatus: string;
  actor: string;
  detail?: string | null;
  commandId?: string | null;
  createdAt: string;
};

export type ScriptPolicy = {
  applicationId: string;
  platformMaxProfile: CapabilityProfile;
  applicationMaxProfile: CapabilityProfile;
  effectiveMaxProfile: CapabilityProfile;
  hasApplicationPolicy: boolean;
  revision: number;
  policyHash: string;
  modifiedBy?: string;
  updatedAt?: string;
};

export type ScriptCompilationResult = {
  successful: boolean;
  scriptHash: string;
  capabilityProfile: CapabilityProfile;
  policyRevision: { revision: number; hash: string };
  compilerVersion: string;
  targetClassLoaderId: string;
  diagnostics: ScriptDiagnostic[];
};

export type ScriptSessionTargetInput = {
  className: string;
  classLoaderId?: string;
  methodName: string;
  methodDescriptor: string;
};

export type CreateScriptSessionRequest = {
  agentId: string;
  target: ScriptSessionTargetInput;
  script: string;
  capabilityProfile: CapabilityProfile;
  ttlMillis?: number;
  maxHits?: number;
  requestedBy?: string;
  applicationId?: string;
  idempotencyKey?: string;
};

export type CompileScriptRequest = {
  agentId: string;
  script: string;
  targetClassLoaderId: string;
  capabilityProfile?: CapabilityProfile;
  applicationId?: string;
};

export type UpdateScriptPolicyRequest = {
  allowedMaxProfile: CapabilityProfile;
  expectedRevision?: number;
};

const TIER_ORDER: Record<CapabilityProfile, number> = { SAFE: 0, EXTENDED: 1, UNRESTRICTED: 2 };

/** {@code min(platform, application, requested)} per §2.1, mirrored for client-side preview. */
export function effectiveTier(
  platformMax: CapabilityProfile,
  applicationMax: CapabilityProfile,
  requested: CapabilityProfile,
): CapabilityProfile {
  return [platformMax, applicationMax, requested].sort(
    (a, b) => TIER_ORDER[a] - TIER_ORDER[b],
  )[0];
}

export function tierIndex(profile: CapabilityProfile): number {
  return TIER_ORDER[profile];
}

export const TIER_LABELS: Record<CapabilityProfile, string> = {
  SAFE: "SAFE（安全）",
  EXTENDED: "EXTENDED（扩展）",
  UNRESTRICTED: "UNRESTRICTED（不受限）",
};

export function listScriptSessions(applicationId?: string): Promise<ScriptSessionDetail[]> {
  const query = applicationId ? `?applicationId=${encodeURIComponent(applicationId)}` : "";
  return platformFetch<ScriptSessionDetail[]>(`script-sessions${query}`);
}

export function getScriptSession(id: string): Promise<ScriptSessionDetail> {
  return platformFetch<ScriptSessionDetail>(`script-sessions/${encodeURIComponent(id)}`);
}

export function createScriptSession(body: CreateScriptSessionRequest): Promise<ScriptSessionResult> {
  return platformFetch<ScriptSessionResult>("script-sessions", {
    method: "POST",
    body: JSON.stringify(body),
    idempotencyKey: body.idempotencyKey,
  });
}

export function validateScriptSession(id: string): Promise<ScriptSessionResult> {
  return platformFetch<ScriptSessionResult>(`script-sessions/${encodeURIComponent(id)}/validate`, {
    method: "POST",
  });
}

export function applyScriptSession(id: string): Promise<ScriptSessionResult> {
  return platformFetch<ScriptSessionResult>(`script-sessions/${encodeURIComponent(id)}/apply`, {
    method: "POST",
  });
}

export function promoteScriptSession(id: string): Promise<ScriptSessionResult> {
  return platformFetch<ScriptSessionResult>(`script-sessions/${encodeURIComponent(id)}/promote`, {
    method: "POST",
  });
}

export function revertScriptSession(id: string): Promise<ScriptSessionResult> {
  return platformFetch<ScriptSessionResult>(`script-sessions/${encodeURIComponent(id)}`, {
    method: "DELETE",
  });
}

export function getScriptSessionEvents(id: string): Promise<ScriptSessionEvent[]> {
  return platformFetch<ScriptSessionEvent[]>(`script-sessions/${encodeURIComponent(id)}/events`);
}

export function getScriptPolicy(applicationId: string): Promise<ScriptPolicy> {
  return platformFetch<ScriptPolicy>(`apps/${encodeURIComponent(applicationId)}/script-policy`);
}

export function updateScriptPolicy(
  applicationId: string,
  body: UpdateScriptPolicyRequest,
): Promise<ScriptPolicy> {
  return platformFetch<ScriptPolicy>(`apps/${encodeURIComponent(applicationId)}/script-policy`, {
    method: "PUT",
    body: JSON.stringify(body),
  });
}

export function compileScript(body: CompileScriptRequest): Promise<ScriptCompilationResult> {
  return platformFetch<ScriptCompilationResult>("scripts/compile", {
    method: "POST",
    body: JSON.stringify(body),
  });
}
