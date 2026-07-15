export type PlatformRecord = Record<string, unknown>;

export type ErrorCategory =
  | "VALIDATION"
  | "AUTHENTICATION"
  | "AUTHORIZATION"
  | "NOT_FOUND"
  | "CONFLICT"
  | "CAPABILITY"
  | "BUSINESS_RULE"
  | "RATE_LIMITED"
  | "OPERATION_IN_PROGRESS"
  | "INTERNAL";

export type SuggestedAction = {
  action: string;
  description: string;
  href?: string;
  safe: boolean;
};

export type PlatformError = {
  code: string;
  message: string;
  correlationId?: string;
  details?: Record<string, unknown>;
  retryable: boolean;
  /** V1.6 §2.4 stable classification */
  category?: ErrorCategory;
  /** V1.6 §2.4 recovery hints */
  suggestedActions?: SuggestedAction[];
  /** V1.6 §2.4 validation target field */
  field?: string;
  /** V1.6 §2.4 JSON pointer or property path */
  path?: string;
};

export type SessionUser = {
  subject: string;
  displayName: string;
  roles: string[];
  capabilities: string[];
  scopes: Array<{ resource_type?: string; resource_id?: string; resourceType?: string; resourceId?: string }>;
  expiresAt: string | null;
  demo: boolean;
};

export type ScriptDiagnostic = {
  severity: "error" | "warning" | "info";
  code: string;
  message: string;
  line: number;
  column: number;
};

export type ScriptValidationResult = {
  valid: boolean;
  diagnostics: ScriptDiagnostic[];
  compileTimeMs?: number;
  policy?: string;
};

export type ScriptTestResult = {
  status: "SUCCESS" | "FAILED";
  durationMs: number;
  output?: unknown;
  exception?: { type: string; message: string };
  logs: string[];
  diff?: { before: unknown; after: unknown };
};

// ---- V1.6 aligned DTOs ----

export type OperationStatus =
  | "PENDING"
  | "RUNNING"
  | "SUCCEEDED"
  | "FAILED"
  | "CANCELLED"
  | "REVERTED"
  | "TIMEOUT";

export type OperationType =
  | "AGENT_COMMAND"
  | "RULE_PUBLISH"
  | "RULE_ROLLBACK"
  | "RULE_UNLOAD"
  | "PREVIEW"
  | "SCRIPT_SESSION"
  | "RECONCILE"
  | "AUTOMATION_TRIAL"
  | "AUTOMATION_PROMOTE"
  | "AUTOMATION_REVERT";

export type RiskLevel = "LOW" | "MEDIUM" | "HIGH" | "CRITICAL";

export type AffectedResource = {
  resourceType: string;
  resourceId: string;
};

export type ImpactSummary = {
  affectedResources: AffectedResource[];
  scope: string;
  blastRadius: string;
  reversible: boolean;
  estimatedAffectedInstances: number;
};

export type Operation = {
  operationId: string;
  type: OperationType;
  status: OperationStatus;
  resourceType: string;
  resourceId: string;
  riskLevel: RiskLevel;
  impact: ImpactSummary;
  progress: number;
  result?: Record<string, unknown>;
  error?: PlatformError;
  revertOperationId?: string;
  correlationId: string;
  actor: string;
  createdAt: number;
  updatedAt: number;
  completedAt: number;
};

export type AutomationSessionStatus =
  | "CREATED"
  | "ACTIVE"
  | "COMPLETED"
  | "EXPIRED"
  | "REVERTED"
  | "FAILED";

export type AutomationSessionResource = {
  resourceType: string;
  resourceId: string;
  reversible: boolean;
  createdAt: number;
};

export type AutomationSession = {
  sessionId: string;
  caller: string;
  source: string;
  applicationId: string;
  environmentId?: string;
  instanceId?: string;
  agentId?: string;
  maxCapabilityProfile: string;
  ttlMillis: number;
  deadlineMillis: number;
  status: AutomationSessionStatus;
  riskLevel: RiskLevel;
  createdResources: AutomationSessionResource[];
  cleanupResult?: Record<string, unknown>;
  correlationId: string;
  version: number;
  createdAt: number;
  updatedAt: number;
};

export type EnhancementContextBundle = {
  version: number;
  sessionId: string;
  candidates: Array<Record<string, unknown>>;
  classLoaders: Array<{
    classLoaderId: string;
    supportLevel: string;
    proxyType: string;
    compatibilityNote: string;
  }>;
  enhancementLocations: Array<{
    targetId: string;
    availableLocations: Array<Record<string, unknown>>;
    callSites: Array<Record<string, unknown>>;
  }>;
  ruleChainConflicts: Array<Record<string, unknown>>;
  scriptApiSurface: Record<string, unknown>;
  sizeBytes: number;
  generatedAtMillis: number;
};

export type PreviewResult = {
  previewToken: string;
  revision: number;
  riskLevel: RiskLevel;
  impact: ImpactSummary;
  diff?: Record<string, unknown>;
  expiresAt: number;
};
