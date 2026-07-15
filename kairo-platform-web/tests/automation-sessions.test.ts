import { afterEach, describe, expect, it, vi } from "vitest";
import { existsSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { PlatformRequestError } from "@/lib/api/client";
import {
  createAutomationSession,
  getAutomationSession,
  listAutomationSessions,
  previewAutomationScript,
  resolveTargets,
  revertAutomationSession,
} from "@/lib/api/automation";
import { platformErrorCode } from "@/lib/api/error";

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

afterEach(() => vi.restoreAllMocks());

describe("V1.6 automation-session UI wiring (audit reconciliation)", () => {
  it("the sidebar links to the automation-sessions page", () => {
    const appShell = readFileSync(
      join(process.cwd(), "components/layout/app-shell.tsx"),
      "utf8",
    );
    expect(appShell).toContain('href: "/automation-sessions"');
    expect(appShell).toContain("AI 自动化");
  });

  it("the automation-sessions route renders the management page component", () => {
    const route = join(process.cwd(), "app/(platform)/automation-sessions/page.tsx");
    expect(existsSync(route), "automation-sessions route must exist").toBe(true);
    const source = readFileSync(route, "utf8");
    expect(source).toContain("AutomationSessionsPage");
  });
});

describe("V1.6 automation-session API client (AI lifecycle data layer)", () => {
  it("lists sessions and applies an optional status filter", async () => {
    const calls = mockFetch([
      { status: 200, body: [{ sessionId: "auto-1", status: "ACTIVE" }] },
    ]);
    const rows = await listAutomationSessions("ACTIVE");
    expect(calls[0].url).toContain("automation-sessions?status=ACTIVE");
    expect(rows).toHaveLength(1);
    expect(rows[0].sessionId).toBe("auto-1");
  });

  it("gets a single session by id", async () => {
    const calls = mockFetch([
      { status: 200, body: { sessionId: "auto-1", status: "CREATED" } },
    ]);
    const session = await getAutomationSession("auto-1");
    expect(calls[0].url).toContain("automation-sessions/auto-1");
    expect(session.sessionId).toBe("auto-1");
  });

  it("resolves targets and parses the structured context bundle", async () => {
    const calls = mockFetch([
      {
        status: 200,
        body: {
          sessionId: "auto-1",
          scriptApiSurface: { allowedProfile: "SAFE", schema: { properties: {} } },
          sizeBytes: 128,
        },
      },
    ]);
    const bundle = await resolveTargets("auto-1", { query: "pay" });
    expect(calls[0].url).toContain("automation-sessions/auto-1/resolve-targets");
    expect(calls[0].init?.method).toBe("POST");
    expect(bundle.scriptApiSurface.allowedProfile).toBe("SAFE");
  });

  it("previews an enhancement and parses the preview token", async () => {
    const calls = mockFetch([
      { status: 200, body: { previewToken: "prev-1", revision: 42, riskLevel: "LOW" } },
    ]);
    const preview = await previewAutomationScript("auto-1", {
      targetId: "t-1",
      script: "ctx.result = 1\n",
      capabilityProfile: "SAFE",
    });
    expect(calls[0].url).toContain("automation-sessions/auto-1/preview");
    expect(calls[0].init?.method).toBe("POST");
    expect(preview.previewToken).toBe("prev-1");
  });

  it("propagates an Idempotency-Key when creating a session", async () => {
    const calls = mockFetch([
      { status: 201, body: { sessionId: "auto-1", status: "CREATED" } },
    ]);
    await createAutomationSession({
      caller: "ai-bot",
      source: "web",
      applicationId: "app-default",
      environmentId: "env-default",
      requestedCapabilityProfile: "SAFE",
      ttlMillis: 600000,
    });
    const headers = new Headers(calls[0].init?.headers);
    expect(headers.get("Idempotency-Key")).toBeTruthy();
  });

  it("surfaces a structured platform error by code (not message) on revert failure", async () => {
    mockFetch([
      {
        status: 409,
        body: {
          code: "AUTOMATION_SESSION_TERMINAL",
          message: "会话已处于终态",
          category: "CONFLICT",
          retryable: false,
        },
      },
    ]);
    await expect(revertAutomationSession("auto-1")).rejects.toSatisfy((err: unknown) => {
      if (!(err instanceof PlatformRequestError)) return false;
      return platformErrorCode(err) === "AUTOMATION_SESSION_TERMINAL";
    });
  });
});
