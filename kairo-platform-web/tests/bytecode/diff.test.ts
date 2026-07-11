import { describe, expect, it } from "vitest";
import type { BytecodeDiffResult, DecompilationResult } from "@/lib/api/bytecode";
import {
  CHANGE_TYPE_LABEL,
  changeTypeBadgeVariant,
  decompilationAvailable,
  decompilationReason,
  instructionLineKind,
  summarizeDiff,
  toMethodDiffViews,
} from "@/lib/bytecode/diff";

function result(over: Partial<BytecodeDiffResult> = {}): BytecodeDiffResult {
  return {
    classIdentity: { binaryClassName: "com.example.Foo", classLoaderId: "loader" },
    fromRevision: { value: 0 },
    toRevision: { value: 1 },
    fromKind: "INPUT",
    toKind: "APPLIED",
    fromHash: "h0",
    toHash: "h1",
    identical: false,
    normalized: true,
    methodDiffs: [],
    structuralDiffs: [],
    summary: null,
    ...over,
  };
}

const decompilation = (over: Partial<DecompilationResult> = {}): DecompilationResult => ({
  status: "UNAVAILABLE",
  decompilerName: "UnavailableBytecodeDecompiler",
  sourceCode: null,
  diagnostics: ["Agent 未配置反编译器"],
  durationMillis: 0,
  ...over,
});

describe("decompilation availability", () => {
  it("is available only on SUCCESS with non-blank source", () => {
    expect(decompilationAvailable(decompilation({ status: "SUCCESS", sourceCode: "class Foo {}", decompilerName: "cfr" }))).toBe(true);
    expect(decompilationAvailable(decompilation({ status: "SUCCESS", sourceCode: "   ", decompilerName: "cfr" }))).toBe(false);
    expect(decompilationAvailable(decompilation({ status: "UNAVAILABLE" }))).toBe(false);
    expect(decompilationAvailable(decompilation({ status: "FAILED", decompilerName: "cfr", diagnostics: ["boom"] }))).toBe(false);
  });

  it("treats absent decompilation as unavailable", () => {
    expect(decompilationAvailable(null)).toBe(false);
    expect(decompilationAvailable(undefined)).toBe(false);
  });

  it("explains why decompilation is unavailable without fabricating source", () => {
    expect(decompilationReason(null)).toContain("未返回反编译源码");
    expect(decompilationReason(decompilation({ status: "UNAVAILABLE", diagnostics: ["Agent 未配置反编译器"] }))).toContain("Agent 未配置反编译器");
    expect(decompilationReason(decompilation({ status: "FAILED", decompilerName: "cfr", diagnostics: [] }))).toContain("失败");
  });
});

describe("diff summary", () => {
  it("counts method and structural diffs", () => {
    const r = result({
      methodDiffs: [{
        methodName: "createOrder",
        methodDescriptor: "()V",
        changeType: "MODIFIED",
        instructionDiffs: ["+ areturn"],
        attributeDiffs: [],
      }],
      structuralDiffs: ["super changed"],
    });
    expect(summarizeDiff(r)).toEqual({ identical: false, normalized: true, methodCount: 1, structuralCount: 1, empty: false });
  });

  it("marks byte-identical diffs as empty", () => {
    expect(summarizeDiff(result({ identical: true }))?.empty).toBe(true);
    expect(summarizeDiff(null)).toBeNull();
  });
});

describe("method diff views", () => {
  it("flattens method diffs with compact signatures and stable keys", () => {
    const r = result({
      methodDiffs: [{
        methodName: "createOrder",
        methodDescriptor: "(Ljava/lang/String;)V",
        changeType: "ADDED",
        instructionDiffs: ["+ aload_1"],
        attributeDiffs: ["@Deprecated added"],
      }],
    });
    const views = toMethodDiffViews(r);
    expect(views).toHaveLength(1);
    expect(views[0].signature).toBe("createOrder(Ljava/lang/String;)V");
    expect(views[0].changeType).toBe("ADDED");
    expect(views[0].instructionDiffs).toEqual(["+ aload_1"]);
    expect(views[0].attributeDiffs).toEqual(["@Deprecated added"]);
  });
});

describe("instruction line classification", () => {
  it("classifies added / removed / context lines", () => {
    expect(instructionLineKind("+ 12: areturn")).toBe("added");
    expect(instructionLineKind("- 12: aload")).toBe("removed");
    expect(instructionLineKind("  shared context")).toBe("context");
    expect(instructionLineKind("areturn")).toBe("context");
  });
});

describe("change type labels and badges", () => {
  it("localizes change types", () => {
    expect(CHANGE_TYPE_LABEL.ADDED).toBe("新增方法");
    expect(CHANGE_TYPE_LABEL.REMOVED).toBe("删除方法");
    expect(CHANGE_TYPE_LABEL.MODIFIED).toBe("修改方法");
  });

  it("maps change types to badge variants", () => {
    expect(changeTypeBadgeVariant("ADDED")).toBe("success");
    expect(changeTypeBadgeVariant("REMOVED")).toBe("danger");
    expect(changeTypeBadgeVariant("MODIFIED")).toBe("warning");
  });
});
