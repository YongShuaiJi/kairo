import type { PlatformError, PlatformRecord } from "@/lib/api/types";

export class PlatformRequestError extends Error {
  public readonly code: string;
  public readonly category?: string;
  public readonly retryable: boolean;
  public readonly suggestedActions?: import("@/lib/api/types").SuggestedAction[];
  public readonly field?: string;
  public readonly path?: string;

  constructor(
    message: string,
    public readonly status: number,
    public readonly payload?: PlatformError,
  ) {
    super(message);
    this.code = payload?.code ?? "UNKNOWN";
    this.category = payload?.category;
    this.retryable = payload?.retryable ?? false;
    this.suggestedActions = payload?.suggestedActions;
    this.field = payload?.field;
    this.path = payload?.path;
  }
}

export async function platformFetch<T>(
  path: string,
  init?: RequestInit & { idempotencyKey?: string },
): Promise<T> {
  const response = await fetch(`/api/platform/${path.replace(/^\/+/, "")}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(init?.idempotencyKey ? { "Idempotency-Key": init.idempotencyKey } : {}),
      ...init?.headers,
    },
  });

  if (!response.ok) {
    let payload: PlatformError | undefined;
    try {
      payload = (await response.json()) as PlatformError;
    } catch {
      payload = undefined;
    }
    throw new PlatformRequestError(payload?.message ?? `请求失败（${response.status}）`, response.status, payload);
  }

  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}

export async function listResource(path: string): Promise<PlatformRecord[]> {
  const result = await platformFetch<PlatformRecord[] | { items: PlatformRecord[] }>(path);
  return Array.isArray(result) ? result : result.items;
}
