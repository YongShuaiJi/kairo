import { cookies } from "next/headers";
import { NextResponse } from "next/server";
import { SESSION_COOKIE } from "@/lib/auth/constants";
import { decryptSession, encryptSession, type SessionPayload } from "@/lib/auth/session";

const apiBase = () => (process.env.RUNTIME_MOCK_PLATFORM_API_URL ?? "http://127.0.0.1:18280").replace(/\/$/, "");
const demoMode = () => process.env.RUNTIME_MOCK_WEB_DEMO_MODE === "true";

function validExpiresAt(value: string | null | undefined, fallback: string | null) {
  if (value === null) return null;
  if (typeof value === "string" && !Number.isNaN(new Date(value).getTime())) return value;
  return fallback;
}

function setSessionCookie(response: NextResponse, payload: SessionPayload) {
  const encrypted = encryptSession(payload);
  const expiresAt = validExpiresAt(payload.expiresAt, null);
  const maxAge = expiresAt === null
    ? 8 * 60 * 60
    : Math.max(60, Math.min(8 * 60 * 60, Math.floor((new Date(expiresAt).getTime() - Date.now()) / 1000)));
  response.cookies.set(SESSION_COOKIE, encrypted, {
    httpOnly: true,
    sameSite: "lax",
    secure: process.env.NODE_ENV === "production",
    path: "/",
    maxAge,
  });
}

export async function POST(request: Request) {
  const store = await cookies();
  const session = decryptSession(store.get(SESSION_COOKIE)?.value);
  if (!session) return NextResponse.json({ message: "未登录" }, { status: 401 });
  const payload = await request.json().catch(() => ({}));

  if (demoMode()) {
    const token = `demo-token-${Date.now()}`;
    const response = NextResponse.json({ ...session, token, demo: true });
    setSessionCookie(response, { ...session, token, demo: true });
    return response;
  }

  const platformResponse = await fetch(`${apiBase()}/api/v1/auth/me/token/replace`, {
    method: "POST",
    headers: {
      Authorization: `Bearer ${session.token}`,
      Accept: "application/json",
      "Content-Type": "application/json",
    },
    body: JSON.stringify(payload),
    cache: "no-store",
    signal: AbortSignal.timeout(8000),
  }).catch(() => null);
  if (!platformResponse) return NextResponse.json({ message: "无法连接 Platform API" }, { status: 503 });
  if (!platformResponse.ok) {
    const error = await platformResponse.json().catch(() => ({}));
    return NextResponse.json({ message: error.message ?? "Token 更换失败" }, { status: platformResponse.status });
  }

  const identity = await platformResponse.json() as SessionPayload;
  const sessionPayload: SessionPayload = {
    token: identity.token,
    subject: identity.subject,
    displayName: identity.displayName,
    roles: identity.roles,
    capabilities: identity.capabilities,
    scopes: identity.scopes,
    expiresAt: validExpiresAt(identity.expiresAt, session.expiresAt),
    demo: false,
  };
  const response = NextResponse.json({ ...identity, demo: false });
  setSessionCookie(response, sessionPayload);
  return response;
}
