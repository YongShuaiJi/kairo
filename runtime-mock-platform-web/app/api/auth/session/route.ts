import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/auth/constants";
import { decryptSession, encryptSession, type SessionPayload } from "@/lib/auth/session";

const apiBase = () => (process.env.RUNTIME_MOCK_PLATFORM_API_URL ?? "http://127.0.0.1:18280").replace(/\/$/, "");
const demoMode = () => process.env.RUNTIME_MOCK_WEB_DEMO_MODE === "true";
type Identity = Omit<SessionPayload, "token" | "demo">;

function setSessionCookie(response: NextResponse, payload: SessionPayload) {
  const encrypted = encryptSession(payload);
  const maxAge = payload.expiresAt === null
    ? 8 * 60 * 60
    : Math.max(60, Math.min(8 * 60 * 60, Math.floor((new Date(payload.expiresAt).getTime() - Date.now()) / 1000)));
  response.cookies.set(SESSION_COOKIE, encrypted, {
    httpOnly: true,
    sameSite: "lax",
    secure: process.env.NODE_ENV === "production",
    path: "/",
    maxAge,
  });
}

export async function GET() {
  const store = await cookies();
  const session = decryptSession(store.get(SESSION_COOKIE)?.value);
  if (!session) return NextResponse.json({ message: "未登录" }, { status: 401 });
  return NextResponse.json({
    subject: session.subject,
    displayName: session.displayName,
    roles: session.roles,
    capabilities: session.capabilities,
    scopes: session.scopes,
    expiresAt: session.expiresAt,
    demo: session.demo,
  });
}

export async function POST(request: Request) {
  const payload = (await request.json().catch(() => ({}))) as { token?: string };
  const token = payload.token?.trim();
  if (!token) return NextResponse.json({ message: "请输入 Platform Token" }, { status: 400 });

  let identity: Identity = {
    subject: "demo-admin",
    displayName: "演示管理员",
    roles: ["PlatformAdmin"],
    capabilities: ["ADMIN"],
    scopes: [{ resource_type: "GLOBAL", resource_id: "*" }],
    expiresAt: new Date(Date.now() + 8 * 60 * 60 * 1000).toISOString(),
  };
  if (!demoMode()) {
    const response = await fetch(`${apiBase()}/api/v1/auth/me`, {
      headers: { Authorization: `Bearer ${token}`, Accept: "application/json" },
      cache: "no-store",
      signal: AbortSignal.timeout(8000),
    }).catch(() => null);
    if (!response) return NextResponse.json({ message: "无法连接 Platform API" }, { status: 503 });
    if (!response.ok) return NextResponse.json({ message: response.status === 401 ? "Token 无效或已过期" : "Token 验证失败" }, { status: response.status });
    identity = await response.json() as Identity;
  }

  const sessionPayload: SessionPayload = {
    token,
    subject: identity.subject,
    displayName: identity.displayName,
    roles: identity.roles,
    capabilities: identity.capabilities,
    scopes: identity.scopes,
    expiresAt: identity.expiresAt,
    demo: demoMode(),
  };
  const response = NextResponse.json({
    ...identity,
    demo: demoMode(),
  });
  setSessionCookie(response, sessionPayload);
  return response;
}

export async function PATCH(request: Request) {
  const store = await cookies();
  const session = decryptSession(store.get(SESSION_COOKIE)?.value);
  if (!session) return NextResponse.json({ message: "未登录" }, { status: 401 });
  const payload = await request.json().catch(() => ({}));

  let identity: Identity;
  if (demoMode()) {
    identity = {
      subject: String(payload.username ?? session.subject).trim() || session.subject,
      displayName: String(payload.displayName ?? payload.username ?? session.displayName).trim() || session.displayName,
      roles: session.roles,
      capabilities: session.capabilities,
      scopes: session.scopes,
      expiresAt: session.expiresAt,
    };
  } else {
    const response = await fetch(`${apiBase()}/api/v1/auth/me`, {
      method: "PATCH",
      headers: {
        Authorization: `Bearer ${session.token}`,
        Accept: "application/json",
        "Content-Type": "application/json",
      },
      body: JSON.stringify(payload),
      cache: "no-store",
      signal: AbortSignal.timeout(8000),
    }).catch(() => null);
    if (!response) return NextResponse.json({ message: "无法连接 Platform API" }, { status: 503 });
    if (!response.ok) {
      const error = await response.json().catch(() => ({}));
      return NextResponse.json({ message: error.message ?? "账户更新失败" }, { status: response.status });
    }
    identity = await response.json() as Identity;
  }

  const sessionPayload: SessionPayload = {
    token: session.token,
    subject: identity.subject,
    displayName: identity.displayName,
    roles: identity.roles,
    capabilities: identity.capabilities,
    scopes: identity.scopes,
    expiresAt: identity.expiresAt,
    demo: demoMode(),
  };
  const response = NextResponse.json({ ...identity, demo: demoMode() });
  setSessionCookie(response, sessionPayload);
  return response;
}

export async function DELETE() {
  const response = NextResponse.json({ ok: true });
  response.cookies.set(SESSION_COOKIE, "", { httpOnly: true, expires: new Date(0), path: "/" });
  return response;
}
