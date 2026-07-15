import { platformFetch } from "@/lib/api/client";
import type { ImpactSummary, RiskLevel, ScriptValidationResult } from "@/lib/api/types";

/**
 * V1.6 §5.3 server-side canonical rule preview/assembly client. The web workbench
 * (and any AI-driven UI) calls this instead of assembling the rule payload
 * client-side: the platform owns the business defaults (status, risk,
 * capabilities, target/matcher shape) and returns the exact payload to persist,
 * plus a preview token/revision, structured impact/risk and revert guidance.
 */

export type InvokePhase = "BEFORE" | "RETURN" | "THROWS";

export type RulePreviewInput = {
  name: string;
  applicationId: string;
  environmentId: string;
  classId?: string;
  className: string;
  classLoaderId: string;
  methodName: string;
  methodDescriptor: string;
  executionPhase: InvokePhase;
  script: string;
  reason?: string;
};

/** Canonical rule target matcher (classLoaderId + descriptor identity). */
export type RuleTargetMatcher = {
  classId?: string;
  classLoaderId: string;
  descriptor: string;
};

/** Canonical enhancement target assembled by the platform. */
export type RuleTarget = {
  protocol: string;
  className: string;
  methodName: string;
  matcher: RuleTargetMatcher;
};

/** Canonical script body (phase + source). */
export type RuleScript = { phase: InvokePhase; script: string };

/**
 * The canonical rule payload the platform assembled. Forward this verbatim to
 * {@code POST /api/v1/rules} or {@code POST /api/v1/rules/{id}/versions}.
 */
export type RulePayload = {
  name: string;
  applicationId: string;
  environmentId: string;
  status: string;
  versionStatus: string;
  riskLevel: RiskLevel;
  script: RuleScript;
  matcher: { phase: InvokePhase };
  targets: RuleTarget[];
  capabilities: string[];
  reason?: string;
};

export type RevertHint = {
  strategy: string;
  description: string;
  steps: string[];
};

export type RulePreviewResponse = {
  payload: RulePayload;
  previewToken: string;
  revision: number;
  riskLevel: RiskLevel;
  impact: ImpactSummary;
  validation: ScriptValidationResult;
  revert: RevertHint;
};

export function previewRule(input: RulePreviewInput): Promise<RulePreviewResponse> {
  return platformFetch<RulePreviewResponse>("rules/preview", {
    method: "POST",
    body: JSON.stringify(input),
  });
}
