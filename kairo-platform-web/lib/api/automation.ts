import { platformFetch } from "@/lib/api/client";
import type { AutomationSession, PreviewResult, EnhancementContextBundle } from "@/lib/api/types";

export type CreateAutomationSessionRequest = {
  caller: string;
  source: string;
  applicationId: string;
  environmentId?: string;
  instanceId?: string;
  agentId?: string;
  requestedCapabilityProfile: string;
  ttlMillis: number;
};

export type ResolveTargetsRequest = {
  query?: string;
  classLoaderId?: string;
};

export type PreviewRequest = {
  targetId: string;
  script: string;
  capabilityProfile: string;
};

export function listAutomationSessions(status?: string): Promise<AutomationSession[]> {
  const query = status ? `?status=${encodeURIComponent(status)}` : "";
  return platformFetch<AutomationSession[]>(`automation-sessions${query}`);
}

export function getAutomationSession(id: string): Promise<AutomationSession> {
  return platformFetch<AutomationSession>(`automation-sessions/${encodeURIComponent(id)}`);
}

export function createAutomationSession(body: CreateAutomationSessionRequest): Promise<AutomationSession> {
  return platformFetch<AutomationSession>("automation-sessions", {
    method: "POST",
    body: JSON.stringify(body),
    idempotencyKey: crypto.randomUUID(),
  });
}

export function revertAutomationSession(id: string): Promise<AutomationSession> {
  return platformFetch<AutomationSession>(`automation-sessions/${encodeURIComponent(id)}/revert`, {
    method: "POST",
  });
}

export function resolveTargets(
  id: string,
  body: ResolveTargetsRequest,
): Promise<EnhancementContextBundle> {
  return platformFetch<EnhancementContextBundle>(`automation-sessions/${encodeURIComponent(id)}/resolve-targets`, {
    method: "POST",
    body: JSON.stringify(body),
  });
}

export function previewAutomationScript(
  id: string,
  body: PreviewRequest,
): Promise<PreviewResult> {
  return platformFetch<PreviewResult>(`automation-sessions/${encodeURIComponent(id)}/preview`, {
    method: "POST",
    body: JSON.stringify(body),
  });
}
