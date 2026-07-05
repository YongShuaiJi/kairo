import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/auth/constants";
import { decryptSession } from "@/lib/auth/session";
import { demoDashboard, demoDetail, demoHealth, demoList, demoMutation, demoPage, demoRuleDetail, demoTargets, demoTest, demoValidate } from "@/lib/demo-data";
import type { PlatformRecord } from "@/lib/api/types";

export const dynamic = "force-dynamic";

type RouteContext = { params: Promise<{ path: string[] }> };

function isWrite(method: string) {
  return !["GET", "HEAD", "OPTIONS"].includes(method);
}

async function forward(request: Request, context: RouteContext) {
  const { path } = await context.params;
  const resourcePath = path.join("/");
  const store = await cookies();
  const session = decryptSession(store.get(SESSION_COOKIE)?.value);
  if (!session) return NextResponse.json({ code: "UNAUTHORIZED", message: "会话已过期", retryable: false }, { status: 401 });

  if (isWrite(request.method)) {
    const origin = request.headers.get("origin");
    if (origin && new URL(origin).host !== request.headers.get("host")) {
      return NextResponse.json({ code: "ORIGIN_REJECTED", message: "请求来源校验失败", retryable: false }, { status: 403 });
    }
  }

  const body = isWrite(request.method)
    ? ((await request.json().catch(() => ({}))) as PlatformRecord)
    : undefined;

  if (process.env.KAIRO_WEB_DEMO_MODE === "true") {
    if (resourcePath === "control/health") return NextResponse.json(demoHealth());
    if (resourcePath === "dashboard/overview") return NextResponse.json(demoDashboard());
    if (resourcePath === "scripts/validate") return NextResponse.json(demoValidate(body ?? {}));
    if (resourcePath === "scripts/test" || resourcePath === "scripts/preview") return NextResponse.json(demoTest(body ?? {}));
    if (request.method === "GET") {
      const incomingUrl = new URL(request.url);
      if (resourcePath === "targets/search") {
        return NextResponse.json(demoTargets(
          incomingUrl.searchParams.get("q") ?? "",
          incomingUrl.searchParams.get("applicationId") ?? "",
          incomingUrl.searchParams.get("environmentId") ?? "",
        ));
      }
      if (resourcePath.startsWith("query/")) {
        const page = Number(incomingUrl.searchParams.get("page") ?? 0);
        const size = Number(incomingUrl.searchParams.get("size") ?? 25);
        return NextResponse.json(demoPage(resourcePath.slice("query/".length), page, size, incomingUrl.searchParams.get("q") ?? ""));
      }
      if (resourcePath.startsWith("details/")) {
        const [, resource, id] = resourcePath.split("/");
        const result = demoDetail(resource, id);
        return result
          ? NextResponse.json(result)
          : NextResponse.json({ code: "NOT_FOUND", message: `Demo 数据中不存在 ${id}`, retryable: false }, { status: 404 });
      }
      const ruleDetailMatch = resourcePath.match(/^rules\/([^/]+)\/detail$/);
      if (ruleDetailMatch) {
        const result = demoRuleDetail(ruleDetailMatch[1]);
        return result
          ? NextResponse.json(result)
          : NextResponse.json({ code: "NOT_FOUND", message: "Demo 规则不存在", retryable: false }, { status: 404 });
      }
      const result = demoList(resourcePath);
      return result
        ? NextResponse.json(result)
        : NextResponse.json({ code: "DEMO_ROUTE_NOT_FOUND", message: `Demo 数据未覆盖 ${resourcePath}`, retryable: false }, { status: 404 });
    }
    if (request.method === "DELETE") return new NextResponse(null, { status: 204 });
    return NextResponse.json(demoMutation(resourcePath, body ?? {}), { status: 201 });
  }

  const apiBase = (process.env.KAIRO_PLATFORM_API_URL ?? "http://127.0.0.1:18280").replace(/\/$/, "");
  const incomingUrl = new URL(request.url);
  const target = `${apiBase}/api/v1/${resourcePath}${incomingUrl.search}`;
  const response = await fetch(target, {
    method: request.method,
    headers: {
      Authorization: `Bearer ${session.token}`,
      Accept: "application/json",
      ...(body ? { "Content-Type": "application/json" } : {}),
      ...(request.headers.get("idempotency-key") ? { "Idempotency-Key": request.headers.get("idempotency-key")! } : {}),
      "X-Kairo-Web": "kairo-platform-web",
    },
    body: body ? JSON.stringify(body) : undefined,
    cache: "no-store",
    signal: AbortSignal.timeout(20000),
  }).catch(() => null);

  if (!response) {
    return NextResponse.json({ code: "PLATFORM_UNAVAILABLE", message: "Platform API 暂时不可用", retryable: true }, { status: 503 });
  }
  const headers = new Headers();
  headers.set("Content-Type", response.headers.get("content-type") ?? "application/json");
  const correlationId = response.headers.get("x-correlation-id");
  if (correlationId) headers.set("X-Correlation-Id", correlationId);
  return new NextResponse(response.status === 204 ? null : await response.arrayBuffer(), { status: response.status, headers });
}

export const GET = forward;
export const POST = forward;
export const PUT = forward;
export const PATCH = forward;
export const DELETE = forward;
