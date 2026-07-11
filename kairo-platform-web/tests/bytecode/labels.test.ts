import { describe, expect, it } from "vitest";
import {
  ACTUAL_NOTICE,
  formatEpochMillis,
  parseSelector,
  PREVIEW_NOTICE,
  selectorLabel,
  SNAPSHOT_KIND_LABEL,
} from "@/lib/bytecode/labels";

describe("selector parse/format", () => {
  it("round-trips KIND@revision", () => {
    expect(selectorLabel("INPUT", 2)).toBe("INPUT@2");
    expect(parseSelector("INPUT@2")).toEqual({ kind: "INPUT", revision: 2 });
    expect(parseSelector("APPLIED@0")).toEqual({ kind: "APPLIED", revision: 0 });
    expect(parseSelector("planned@3")).toEqual({ kind: "PLANNED", revision: 3 });
  });

  it("rejects malformed selectors", () => {
    expect(parseSelector("")).toBeNull();
    expect(parseSelector("INPUT")).toBeNull();
    expect(parseSelector("FOO@1")).toBeNull();
    expect(parseSelector("INPUT@-1")).toBeNull();
    expect(parseSelector("INPUT@abc")).toBeNull();
    expect(parseSelector("@1")).toBeNull();
    expect(parseSelector("INPUT@")).toBeNull();
  });
});

describe("formatEpochMillis", () => {
  it("formats positive epoch millis as a zh-CN timestamp", () => {
    const text = formatEpochMillis(1_700_000_000_000);
    expect(text).toMatch(/\d{2}\/\d{2}/);
    expect(text).not.toBe("-");
  });

  it("returns dash for missing or non-positive values", () => {
    expect(formatEpochMillis(null)).toBe("-");
    expect(formatEpochMillis(undefined)).toBe("-");
    expect(formatEpochMillis(0)).toBe("-");
    expect(formatEpochMillis("")).toBe("-");
    expect(formatEpochMillis("not-a-number")).toBe("-");
  });
});

describe("kind labels expose the required annotations", () => {
  it("labels all three kinds", () => {
    expect(SNAPSHOT_KIND_LABEL.INPUT).toBeTruthy();
    expect(SNAPSHOT_KIND_LABEL.PLANNED).toBeTruthy();
    expect(SNAPSHOT_KIND_LABEL.APPLIED).toBeTruthy();
  });

  it("exposes the read-only-preview and JVM-actual notices verbatim", () => {
    expect(PREVIEW_NOTICE).toContain("只读预览");
    expect(PREVIEW_NOTICE).toContain("未修改 JVM");
    expect(ACTUAL_NOTICE).toContain("JVM 实际运行");
  });
});
