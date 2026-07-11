import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/auth/constants";
import { decryptSession } from "@/lib/auth/session";
import {
  demoBytecodeBytes,
  demoBytecodeCapture,
  demoBytecodeDiff,
  demoBytecodePreview,
  demoBytecodeTransformations,
  demoDashboard,
  demoDetail,
  demoHealth,
  demoList,
  demoMutation,
  demoPage,
  demoRuleDetail,
  demoTargets,
  demoTest,
  demoValidate,
} from "@/lib/demo-data";
import type { BytecodeSnapshotKind } from "@/lib/api/bytecode";
import { decodeClassId } from "@/lib/bytecode/class-id";
import type { PlatformRecord } from "@/lib/api/types";

export const dynamic = "force-dynamic";

type RouteContext = { params: Promise<{ path: string[] }> };

function isWrite(method: string) {
  return !["GET", "HEAD", "OPTIONS"].includes(method);
}

/**
 * Match the five platform-proxied bytecode diagnostic routes
 * {@code agents/{agentId}/classes/{classId}/{action}}. Returns the parsed segments and
 * action name, or {@code null} when the path is not a bytecode route.
 */
function matchBytecodeRoute(resourcePath: string): { agentId: string; classId: string; action: string } | null {
  const match = resourcePath.match(/^agents\/([^/]+)\/classes\/([^/]+)\/(transformations|bytecode|preview|capture|diff)$/);
  if (!match) return null;
  return { agentId: decodeURIComponent(match[1]), classId: decodeURIComponent(match[2]), action: match[3] };
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

  const requestContentType = request.headers.get("content-type") ?? "";
  const isBinaryBody = isWrite(request.method)
    && requestContentType.toLowerCase().startsWith("application/octet-stream");

  // Bytecode preview sends raw INPUT bytes as application/octet-stream; read them
  // verbatim instead of parsing JSON, and forward the binary body unchanged. Every
  // other write path keeps the existing JSON parsing behavior.
  const declaredLength = Number(request.headers.get("content-length") ?? 0);
  if (isBinaryBody && declaredLength > 1024 * 1024) {
    return NextResponse.json({ code: "PAYLOAD_TOO_LARGE", message: "字节码预览输入不能超过 1 MiB", retryable: false }, { status: 413 });
  }
  const binaryBody = isBinaryBody ? Buffer.from(await request.arrayBuffer()) : undefined;
  if (binaryBody && binaryBody.byteLength > 1024 * 1024) {
    return NextResponse.json({ code: "PAYLOAD_TOO_LARGE", message: "字节码预览输入不能超过 1 MiB", retryable: false }, { status: 413 });
  }
  const jsonBody = !isBinaryBody && isWrite(request.method)
    ? ((await request.json().catch(() => ({}))) as PlatformRecord)
    : undefined;

  if (process.env.KAIRO_WEB_DEMO_MODE === "true") {
    const bytecodeRoute = matchBytecodeRoute(resourcePath);
    if (bytecodeRoute) {
      return demoBytecodeResponse(request.method, bytecodeRoute, new URL(request.url).searchParams);
    }
    if (resourcePath === "control/health") return NextResponse.json(demoHealth());
    if (resourcePath === "dashboard/overview") return NextResponse.json(demoDashboard());
    if (resourcePath === "scripts/validate") return NextResponse.json(demoValidate(jsonBody ?? {}));
    if (resourcePath === "scripts/test" || resourcePath === "scripts/preview") return NextResponse.json(demoTest(jsonBody ?? {}));
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
    return NextResponse.json(demoMutation(resourcePath, jsonBody ?? {}), { status: 201 });
  }

  const apiBase = (process.env.KAIRO_PLATFORM_API_URL ?? "http://127.0.0.1:18280").replace(/\/$/, "");
  const incomingUrl = new URL(request.url);
  const target = `${apiBase}/api/v1/${resourcePath}${incomingUrl.search}`;
  const response = await fetch(target, {
    method: request.method,
    headers: {
      Authorization: `Bearer ${session.token}`,
      Accept: "application/json",
      ...(binaryBody ? { "Content-Type": "application/octet-stream" } : jsonBody ? { "Content-Type": "application/json" } : {}),
      ...(request.headers.get("idempotency-key") ? { "Idempotency-Key": request.headers.get("idempotency-key")! } : {}),
      "X-Kairo-Web": "kairo-platform-web",
    },
    body: binaryBody ?? (jsonBody ? JSON.stringify(jsonBody) : undefined),
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
  for (const name of ["x-kairo-hash", "x-kairo-size", "x-kairo-kind", "x-kairo-revision"]) {
    const value = response.headers.get(name);
    if (value) headers.set(name, value);
  }
  return new NextResponse(response.status === 204 ? null : await response.arrayBuffer(), { status: response.status, headers });
}

function asKind(value: string | null, fallback: BytecodeSnapshotKind): BytecodeSnapshotKind {
  const upper = (value ?? "").toUpperCase();
  return upper === "INPUT" || upper === "PLANNED" || upper === "APPLIED" ? upper : fallback;
}

/** Serve the five bytecode diagnostic routes from demo data; never reaches a real agent. */
function demoBytecodeResponse(
  method: string,
  route: { agentId: string; classId: string; action: string },
  search: URLSearchParams,
): NextResponse {
  const { agentId, classId, action } = route;
  // Mirror the agent: a malformed classId (not base64url of classLoaderId|binaryClassName)
  // is rejected with 400 on every bytecode route.
  if (!decodeClassId(classId)) {
    return NextResponse.json(
      { code: "BAD_CLASS_ID", message: "classId 无法解码为 classLoaderId|binaryClassName", retryable: false },
      { status: 400 },
    );
  }
  if (action === "transformations" && method === "GET") {
    return NextResponse.json(demoBytecodeTransformations(agentId, classId));
  }
  if (action === "bytecode" && (method === "GET" || method === "HEAD")) {
    const kind = asKind(search.get("kind"), "INPUT");
    const revision = Number(search.get("revision") ?? 0);
    const bytes = demoBytecodeBytes(kind, Number.isFinite(revision) && revision >= 0 ? revision : 0);
    const headers = new Headers();
    headers.set("Content-Type", "application/octet-stream");
    headers.set("X-Content-Type-Options", "nosniff");
    if (method === "HEAD") return new NextResponse(null, { status: 200, headers });
    return new NextResponse(bytes as BodyInit, { status: 200, headers });
  }
  if (action === "preview" && method === "POST") {
    return NextResponse.json(demoBytecodePreview(agentId, classId));
  }
  if (action === "capture" && method === "POST") {
    return NextResponse.json(demoBytecodeCapture(agentId, classId));
  }
  if (action === "diff" && method === "GET") {
    const fromKind = asKind(search.get("fromKind"), "INPUT");
    const toKind = asKind(search.get("toKind"), "APPLIED");
    const fromRevision = Number(search.get("fromRevision") ?? 0);
    const toRevision = Number(search.get("toRevision") ?? 0);
    return NextResponse.json(
      demoBytecodeDiff(classId, fromKind, fromRevision, toKind, toRevision),
    );
  }
  return NextResponse.json(
    { code: "METHOD_NOT_ALLOWED", message: `Demo 字节码路由不支持 ${method} 请求`, retryable: false },
    { status: 405 },
  );
}

export const GET = forward;
export const POST = forward;
export const PUT = forward;
export const PATCH = forward;
export const DELETE = forward;
