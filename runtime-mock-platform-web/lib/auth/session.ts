import "server-only";

import { createCipheriv, createDecipheriv, createHash, randomBytes } from "node:crypto";
import type { SessionUser } from "@/lib/api/types";
export { SESSION_COOKIE } from "@/lib/auth/constants";

export type SessionPayload = SessionUser & {
  token: string;
};

function sessionKey() {
  const secret = process.env.RUNTIME_MOCK_WEB_SESSION_KEY;
  if (!secret || secret.length < 32) {
    if (process.env.RUNTIME_MOCK_WEB_DEMO_MODE === "true") {
      return createHash("sha256").update("runtime-mock-demo-session-key-only").digest();
    }
    throw new Error("RUNTIME_MOCK_WEB_SESSION_KEY must contain at least 32 characters");
  }
  return createHash("sha256").update(secret).digest();
}

export function encryptSession(payload: SessionPayload) {
  const iv = randomBytes(12);
  const cipher = createCipheriv("aes-256-gcm", sessionKey(), iv);
  const ciphertext = Buffer.concat([cipher.update(JSON.stringify(payload), "utf8"), cipher.final()]);
  const tag = cipher.getAuthTag();
  return Buffer.concat([iv, tag, ciphertext]).toString("base64url");
}

export function decryptSession(value: string | undefined): SessionPayload | null {
  if (!value) return null;
  try {
    const buffer = Buffer.from(value, "base64url");
    const iv = buffer.subarray(0, 12);
    const tag = buffer.subarray(12, 28);
    const ciphertext = buffer.subarray(28);
    const decipher = createDecipheriv("aes-256-gcm", sessionKey(), iv);
    decipher.setAuthTag(tag);
    const payload = JSON.parse(Buffer.concat([decipher.update(ciphertext), decipher.final()]).toString("utf8")) as SessionPayload;
    return payload.expiresAt === null || new Date(payload.expiresAt).getTime() > Date.now() ? payload : null;
  } catch {
    return null;
  }
}
