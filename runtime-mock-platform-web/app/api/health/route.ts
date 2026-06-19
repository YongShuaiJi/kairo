import { NextResponse } from "next/server";

export function GET() {
  return NextResponse.json({
    status: "UP",
    service: "runtime-mock-platform-web",
    demo: process.env.RUNTIME_MOCK_WEB_DEMO_MODE === "true",
    timestamp: new Date().toISOString(),
  });
}
