import { describe, expect, it } from "vitest";
import { decodeClassId, describeClassId, encodeClassId } from "@/lib/bytecode/class-id";

describe("classId encode/decode", () => {
  it("round-trips a classLoaderId|binaryClassName pair", () => {
    const classId = encodeClassId("com.example.OrderService", "app-loader-7f3a");
    expect(decodeClassId(classId)).toEqual({
      classLoaderId: "app-loader-7f3a",
      binaryClassName: "com.example.OrderService",
    });
  });

  it("encodes with the url-safe alphabet and no padding", () => {
    const classId = encodeClassId("com.example.Foo", "loader");
    expect(classId).not.toContain("=");
    expect(classId).not.toContain("+");
    expect(classId).not.toContain("/");
  });

  it("returns null for malformed classIds", () => {
    expect(decodeClassId("")).toBeNull();
    // "YWJj" decodes to "abc" - no classLoaderId|binaryClassName separator.
    expect(decodeClassId("YWJj")).toBeNull();
  });

  it("distinguishes same-named classes on different loaders (ClassLoader isolation)", () => {
    const onLoaderA = encodeClassId("com.example.Foo", "loader-a");
    const onLoaderB = encodeClassId("com.example.Foo", "loader-b");
    expect(onLoaderA).not.toBe(onLoaderB);
    expect(decodeClassId(onLoaderA)!.classLoaderId).toBe("loader-a");
    expect(decodeClassId(onLoaderB)!.classLoaderId).toBe("loader-b");
    expect(decodeClassId(onLoaderA)!.binaryClassName).toBe("com.example.Foo");
  });

  it("describeClassId renders className @ loaderId", () => {
    const classId = encodeClassId("com.example.OrderService", "app-loader");
    expect(describeClassId(classId)).toBe("com.example.OrderService @ app-loader");
    expect(describeClassId("YWJj")).toBe("YWJj");
  });
});
