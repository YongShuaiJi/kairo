export type PlatformRecord = Record<string, unknown>;

export type PlatformError = {
  code: string;
  message: string;
  correlationId?: string;
  details?: Record<string, unknown>;
  retryable: boolean;
};

export type SessionUser = {
  subject: string;
  displayName: string;
  roles: string[];
  capabilities: string[];
  scopes: Array<{ resource_type?: string; resource_id?: string; resourceType?: string; resourceId?: string }>;
  expiresAt: string;
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
