import { NextResponse } from "next/server";

export function GET() {
  return NextResponse.json({
    status: "UP",
    service: "kairo-platform-web",
    demo: process.env.KAIRO_WEB_DEMO_MODE === "true",
    timestamp: new Date().toISOString(),
  });
}
