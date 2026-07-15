/**
 * Bytecode diagnostic API contract for the V1.1 visibility foundation.
 *
 * These types mirror the frozen DTOs in {@code kairo-api.com.example.kairo.api.bytecode}
 * and the response records exposed by the agent's {@code BytecodeRoutes} (which the
 * platform proxies at {@code /api/v1/agents/{agentId}/classes/{classId}/...}). The
 * browser always reaches them through the same-origin BFF at {@code /api/platform}; it
 * never learns the agent URL or {@code X-Agent-Token} - the platform authenticates the
 * agent command channel server-side.
 *
 * Serialization notes that drive the shape below:
 *  - {@code ClassIdentity} and {@code TransformationRevision} are non-record frozen
 *    classes serialized by field (a field-visibility mixin on the shared mapper), so
 *    they appear as {@code { binaryClassName, classLoaderId }} and {@code { value }}.
 *  - The agent's response records ({@code TransformationsResponse}, {@code PreviewResponse},
 *    {@code CaptureResponse}) and {@code BytecodeDiffResult} are records, so their
 *    accessor names are the JSON keys.
 */

import { platformFetch, PlatformRequestError } from "@/lib/api/client";
import type { PlatformError } from "@/lib/api/types";

/** The three distinguished kinds of bytecode a transformation records. */
export type BytecodeSnapshotKind = "INPUT" | "PLANNED" | "APPLIED";

/** Frozen {@code (binaryClassName, classLoaderId)} identity of a loaded class. */
export type ClassIdentity = {
  binaryClassName: string;
  classLoaderId: string;
};

/** Monotonic per-class transformation revision; {@code 0} means not yet transformed. */
export type TransformationRevision = {
  value: number;
};

/** Lifecycle status of one transformation attempt, as recorded in the journal. */
export type TransformationStatus =
  | "STARTED"
  | "SUCCEEDED"
  | "FAILED"
  | "VERIFIED"
  | "RECOVERED"
  | "SKIPPED";

export type TransformationDiagnosticSeverity = "INFO" | "WARN" | "ERROR";

/** A single diagnostic attached to a transformation result. */
export type TransformationDiagnostic = {
  severity: TransformationDiagnosticSeverity;
  code: string;
  message: string;
  exceptionClassName?: string | null;
  detail?: string | null;
};

/** Structured per-class outcome of one transformation attempt. */
export type TransformationResult = {
  classIdentity: ClassIdentity;
  revision: TransformationRevision;
  status: TransformationStatus;
  inputHash?: string | null;
  outputHash?: string | null;
  diagnostics: TransformationDiagnostic[];
  attemptedAtMillis: number;
  durationMillis: number;
};

/** Response of {@code GET .../transformations}: per-class revision + bounded history. */
export type TransformationsResponse = {
  classIdentity: ClassIdentity;
  currentRevision: TransformationRevision;
  count: number;
  history: TransformationResult[];
};

/** Outcome status of a decompiler attempt. */
export type DecompilationStatus = "SUCCESS" | "UNAVAILABLE" | "FAILED";

/**
 * Frozen result of one decompiler attempt. Honest by construction: when {@code status}
 * is not {@code SUCCESS}, {@code sourceCode} is absent - so a caller can never mistake
 * an unavailable attempt for a successful decompilation.
 *
 * <p>The current diagnostic API does not place decompilation on the wire, so this is
 * optional on every response; the UI treats absence as "unavailable" and never
 * fabricates source.
 */
export type DecompilationResult = {
  status: DecompilationStatus;
  decompilerName: string;
  sourceCode?: string | null;
  diagnostics: string[];
  durationMillis: number;
};

/** Response of {@code POST .../preview}: offline planned-bytes preview (read-only). */
export type PreviewResponse = {
  classIdentity: ClassIdentity;
  revision: TransformationRevision;
  inputHash?: string | null;
  plannedHash?: string | null;
  plannedSizeBytes?: number | null;
  targetMethodCount: number;
  adviceTypes: string[];
  diagnostics: TransformationDiagnostic[];
  changed: boolean;
  /** Optional decompilation of the planned bytes; absent until the agent exposes it. */
  decompilation?: DecompilationResult | null;
};

/** Response of {@code POST .../capture}: bytes actually running in the JVM. */
export type CaptureResponse = {
  classIdentity: ClassIdentity;
  revision: TransformationRevision;
  appliedHash?: string | null;
  sizeBytes?: number | null;
  diagnostics: TransformationDiagnostic[];
  capturedAtMillis: number;
  captured: boolean;
  /** Optional decompilation of the applied bytes; absent until the agent exposes it. */
  decompilation?: DecompilationResult | null;
};

export type BytecodeDiffChangeType = "ADDED" | "REMOVED" | "MODIFIED";

/** Difference for a single method. */
export type MethodDiff = {
  methodName: string;
  methodDescriptor: string;
  changeType: BytecodeDiffChangeType;
  instructionDiffs: string[];
  attributeDiffs: string[];
};

/**
 * Structured bytecode difference between two snapshots of the same class. The
 * authoritative comparison is the normalized instruction diff; Java source is for
 * readability only and may be absent.
 */
export type BytecodeDiffResult = {
  classIdentity: ClassIdentity;
  fromRevision: TransformationRevision;
  toRevision: TransformationRevision;
  fromKind: BytecodeSnapshotKind;
  toKind: BytecodeSnapshotKind;
  fromHash?: string | null;
  toHash?: string | null;
  identical: boolean;
  normalized: boolean;
  methodDiffs: MethodDiff[];
  structuralDiffs: string[];
  summary?: string | null;
  /** Approximate readable sources for both sides; structured bytecode remains authoritative. */
  fromDecompilation?: DecompilationResult | null;
  toDecompilation?: DecompilationResult | null;
};

/** A {@code KIND@revision} selector as used by the diff endpoint. */
export type SnapshotSelector = {
  kind: BytecodeSnapshotKind;
  revision: number;
};

function ensureValidSegment(value: string, label: string) {
  // base64url classIds and agent ids are path-safe, but a stray slash would silently
  // re-route the catch-all BFF - reject early with a clear client-side error.
  if (!value || value.includes("/")) {
    throw new Error(`非法的${label}：${value || "(空)"}`);
  }
}

/** GET .../transformations - per-class revision + bounded journal history. */
export function fetchBytecodeTransformations(agentId: string, classId: string) {
  ensureValidSegment(agentId, "agentId");
  ensureValidSegment(classId, "classId");
  return platformFetch<TransformationsResponse>(
    `agents/${encodeURIComponent(agentId)}/classes/${encodeURIComponent(classId)}/transformations`,
  );
}

/**
 * GET .../bytecode?kind=&revision= - raw snapshot bytes ({@code application/octet-stream}).
 * Pulled on demand only; the response is returned as an {@code ArrayBuffer} plus the
 * content hash/size when the platform forwards the agent's {@code X-Kairo-*} headers.
 */
export async function fetchBytecodeBytes(
  agentId: string,
  classId: string,
  kind: BytecodeSnapshotKind,
  revision: number,
): Promise<{ bytes: ArrayBuffer; hash?: string; sizeBytes?: number }> {
  ensureValidSegment(agentId, "agentId");
  ensureValidSegment(classId, "classId");
  const search = new URLSearchParams({ kind, revision: String(revision) });
  const response = await fetch(
    `/api/platform/agents/${encodeURIComponent(agentId)}/classes/${encodeURIComponent(classId)}/bytecode?${search.toString()}`,
    { headers: { Accept: "application/octet-stream" } },
  );
  if (!response.ok) {
    throw await toPlatformError(response);
  }
  const bytes = await response.arrayBuffer();
  return {
    bytes,
    hash: response.headers.get("x-kairo-hash") ?? undefined,
    sizeBytes: response.headers.get("x-kairo-size") ? Number(response.headers.get("x-kairo-size")) : bytes.byteLength,
  };
}

/**
 * POST .../preview - offline planned-bytes preview of supplied INPUT bytes. The INPUT
 * bytes are sent verbatim as {@code application/octet-stream}; the BFF forwards the
 * binary body without re-encoding. This never modifies the JVM.
 */
export async function previewBytecode(
  agentId: string,
  classId: string,
  inputBytes: ArrayBuffer | Uint8Array,
): Promise<PreviewResponse> {
  ensureValidSegment(agentId, "agentId");
  ensureValidSegment(classId, "classId");
  const response = await fetch(
    `/api/platform/agents/${encodeURIComponent(agentId)}/classes/${encodeURIComponent(classId)}/preview`,
    {
      method: "POST",
      headers: { "Content-Type": "application/octet-stream", Accept: "application/json" },
      body: (inputBytes instanceof Uint8Array ? inputBytes : new Uint8Array(inputBytes)) as BodyInit,
    },
  );
  if (!response.ok) {
    throw await toPlatformError(response);
  }
  return (await response.json()) as PreviewResponse;
}

/** POST .../capture - re-read the bytes actually running in the JVM right now. */
export async function captureBytecode(agentId: string, classId: string): Promise<CaptureResponse> {
  ensureValidSegment(agentId, "agentId");
  ensureValidSegment(classId, "classId");
  const response = await fetch(
    `/api/platform/agents/${encodeURIComponent(agentId)}/classes/${encodeURIComponent(classId)}/capture`,
    { method: "POST", headers: { Accept: "application/json" } },
  );
  if (!response.ok) {
    throw await toPlatformError(response);
  }
  return (await response.json()) as CaptureResponse;
}

/** GET .../diff?fromKind=&fromRevision=&toKind=&toRevision= - structured normalized diff. */
export async function fetchBytecodeDiff(
  agentId: string,
  classId: string,
  from: SnapshotSelector,
  to: SnapshotSelector,
): Promise<BytecodeDiffResult> {
  ensureValidSegment(agentId, "agentId");
  ensureValidSegment(classId, "classId");
  const search = new URLSearchParams({
    fromKind: from.kind,
    fromRevision: String(from.revision),
    toKind: to.kind,
    toRevision: String(to.revision),
  });
  return platformFetch<BytecodeDiffResult>(
    `agents/${encodeURIComponent(agentId)}/classes/${encodeURIComponent(classId)}/diff?${search.toString()}`,
  );
}

async function toPlatformError(response: Response): Promise<PlatformRequestError> {
  let payload: PlatformError | undefined;
  try {
    payload = (await response.json()) as PlatformError;
  } catch {
    payload = undefined;
  }
  const message = payload?.message ?? `字节码诊断请求失败（${response.status}）`;
  return new PlatformRequestError(message, response.status, payload);
}
