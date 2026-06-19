import { NextResponse, type NextRequest } from "next/server";
import { SESSION_COOKIE } from "@/lib/auth/constants";

export function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const publicPath =
    pathname === "/login" ||
    pathname === "/api/health" ||
    pathname.startsWith("/api/auth/session") ||
    pathname.startsWith("/_next") ||
    pathname === "/favicon.ico";
  if (publicPath) return NextResponse.next();

  if (!request.cookies.get(SESSION_COOKIE)) {
    const loginUrl = new URL("/login", request.url);
    loginUrl.searchParams.set("next", pathname);
    return NextResponse.redirect(loginUrl);
  }

  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!api/platform/health).*)"],
};
