import { PlatformRequestError } from "@/lib/api/client";

export function platformErrorCode(error: unknown): string {
  if (error instanceof PlatformRequestError) return error.code;
  return "UNKNOWN";
}

export function platformErrorCategory(error: unknown): string | undefined {
  if (error instanceof PlatformRequestError) return error.category;
  return undefined;
}

export function isPlatformRetryable(error: unknown): boolean {
  if (error instanceof PlatformRequestError) return error.retryable;
  return false;
}

export function platformErrorActions(error: unknown): Array<{ action: string; description: string; safe: boolean }> {
  if (error instanceof PlatformRequestError) return error.suggestedActions ?? [];
  return [];
}

export function platformErrorTitle(error: unknown): string {
  if (error instanceof PlatformRequestError) {
    return error.code;
  }
  if (error instanceof Error) return error.name;
  return "Error";
}

export function platformErrorMessage(error: unknown): string {
  if (error instanceof PlatformRequestError) {
    return error.message;
  }
  if (error instanceof Error) return error.message;
  return String(error);
}
