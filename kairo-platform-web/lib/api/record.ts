import type { PlatformRecord } from "@/lib/api/types";

export function recordValue(record: PlatformRecord | undefined, key: string) {
  if (!record) return undefined;
  const snake = key.replace(/[A-Z]/g, (letter) => `_${letter.toLowerCase()}`);
  return record[key] ?? record[snake];
}

export function toOptionalIsoInstant(value: string) {
  if (!value.trim()) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : date.toISOString();
}
