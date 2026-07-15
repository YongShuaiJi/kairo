import { afterEach, describe, expect, it, vi } from "vitest";
import { PlatformRequestError } from "@/lib/api/client";
import {
  isPlatformRetryable,
  platformErrorCode,
  platformErrorCategory,
  platformErrorActions,
  platformErrorMessage,
} from "@/lib/api/error";
import { createAutomationSession, revertAutomationSession } from "@/lib/api/automation";

type MockResponse = { status: number; body: unknown };

function mockFetch(responses: MockResponse[]) {
  const calls: Array<{ url: string; init?: RequestInit }> = [];
  const queue = [...responses];
  global.fetch = vi.fn(async (input: URL | RequestInfo, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input.toString();
    calls.push({ url, init });
    const next = queue.shift();
    if (!next) throw new Error("no mock response queued");
    return {
      ok: next.status >= 200 && next.status < 300,
      status: next.status,
      headers: new Headers({ "content-type": "application/json" }),
      json: async () => next.body,
      text: async () => JSON.stringify(next.body),
    } as Response;
  });
  return calls;
}

afterEach(() => {
  vi.restoreAllMocks();
});

describe("V1.6 structured error helpers (code-based, not message-based)", () => {
  const errorPayload = {
    code: "RESOURCE_VERSION_CONFLICT",
    message: "version changed",
    category: "CONFLICT" as const,
    retryable: true,
    suggestedActions: [
      { action: "REFRESH_RESOURCE", description: "reload and retry", safe: true },
    ],
  };

  it("branches on code/category/retryable, never on the message", () => {
    const err = new PlatformRequestError(errorPayload.message, 409, errorPayload);
    expect(platformErrorCode(err)).toBe("RESOURCE_VERSION_CONFLICT");
    expect(platformErrorCategory(err)).toBe("CONFLICT");
    expect(isPlatformRetryable(err)).toBe(true);
    expect(platformErrorActions(err)).toHaveLength(1);
    expect(platformErrorActions(err)[0].action).toBe("REFRESH_RESOURCE");
  });

  it("falls back gracefully for non-platform errors", () => {
    expect(platformErrorCode(new Error("boom"))).toBe("UNKNOWN");
    expect(isPlatformRetryable("not an error")).toBe(false);
    expect(platformErrorMessage(new Error("boom"))).toBe("boom");
  });
});

describe("V1.6 automation session API client", () => {
  it("creates a session and propagates the Idempotency-Key header", async () => {
    const calls = mockFetch([
      { status: 201, body: { sessionId: "auto-1", status: "CREATED", maxCapabilityProfile: "SAFE" } },
    ]);
    await createAutomationSession({
      caller: "ai-bot",
      source: "web",
      applicationId: "app-default",
      environmentId: "env-default",
      requestedCapabilityProfile: "SAFE",
      ttlMillis: 600000,
    });
    expect(calls[0].url).toContain("automation-sessions");
    const headers = new Headers(calls[0].init?.headers);
    // V1.6 §2.3: the client always sends an Idempotency-Key for writes.
    expect(headers.get("Idempotency-Key")).toBeTruthy();
    expect(headers.get("Content-Type")).toBe("application/json");
  });

  it("reverts a session via POST", async () => {
    const calls = mockFetch([
      { status: 200, body: { sessionId: "auto-1", status: "REVERTED" } },
    ]);
    await revertAutomationSession("auto-1");
    expect(calls[0].url).toContain("automation-sessions/auto-1/revert");
    expect(calls[0].init?.method).toBe("POST");
  });
});
