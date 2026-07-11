import { afterEach, describe, expect, it, vi } from "vitest";
import {
  captureBytecode,
  fetchBytecodeBytes,
  fetchBytecodeDiff,
  fetchBytecodeTransformations,
  previewBytecode,
} from "@/lib/api/bytecode";
import { encodeClassId } from "@/lib/bytecode/class-id";

type MockResponse = {
  status: number;
  body: unknown;
  headers?: Record<string, string>;
};

type RecordedCall = { url: string; init?: RequestInit };

function mockFetch(responses: MockResponse[]) {
  const calls: RecordedCall[] = [];
  const queue = [...responses];
  global.fetch = vi.fn(async (input: URL | RequestInfo, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input.toString();
    calls.push({ url, init });
    const next = queue.shift();
    if (!next) throw new Error("no mock response queued");
    const headers = new Headers(next.headers ?? { "content-type": "application/json" });
    const body = next.body;
    return {
      ok: next.status >= 200 && next.status < 300,
      status: next.status,
      headers,
      json: async () => body,
      arrayBuffer: async () => {
        if (body instanceof Uint8Array) return body.buffer.slice(body.byteOffset, body.byteOffset + body.byteLength) as ArrayBuffer;
        if (body instanceof ArrayBuffer) return body;
        return new TextEncoder().encode(typeof body === "string" ? body : JSON.stringify(body)).buffer as ArrayBuffer;
      },
    } as Response;
  }) as typeof fetch;
  return calls;
}

afterEach(() => {
  vi.restoreAllMocks();
});

const identity = { binaryClassName: "com.example.Foo", classLoaderId: "loader-1" };

describe("bytecode client routing", () => {
  it("calls transformations through the same-origin BFF path", async () => {
    const classId = encodeClassId("com.example.Foo", "loader-1");
    const calls = mockFetch([{
      status: 200,
      body: { classIdentity: identity, currentRevision: { value: 1 }, count: 0, history: [] },
    }]);
    const result = await fetchBytecodeTransformations("agt-1", classId);
    expect(result.currentRevision.value).toBe(1);
    expect(calls[0].url).toBe(`/api/platform/agents/agt-1/classes/${classId}/transformations`);
  });

  it("fetchBytecodeBytes reads octet-stream and reports byte length", async () => {
    const bytes = new Uint8Array([0xca, 0xfe, 0xba, 0xbe, 1, 2, 3, 4]);
    const calls = mockFetch([{ status: 200, body: bytes, headers: { "content-type": "application/octet-stream" } }]);
    const out = await fetchBytecodeBytes("agt-1", "cls-1", "INPUT", 1);
    expect(out.bytes.byteLength).toBe(8);
    expect(out.sizeBytes).toBe(8);
    expect(calls[0].url).toContain("kind=INPUT");
    expect(calls[0].url).toContain("revision=1");
    expect(calls[0].url).toContain("/bytecode?");
  });

  it("previewBytecode sends the INPUT bytes as a binary octet-stream body", async () => {
    const calls = mockFetch([{
      status: 200,
      body: { classIdentity: identity, revision: { value: 1 }, targetMethodCount: 0, adviceTypes: [], diagnostics: [], changed: false },
    }]);
    const inputBytes = new Uint8Array([1, 2, 3, 4]);
    await previewBytecode("agt-1", "cls-1", inputBytes);
    expect(calls[0].init?.method).toBe("POST");
    expect(calls[0].init?.headers).toMatchObject({ "Content-Type": "application/octet-stream" });
    expect(calls[0].init?.body).toBe(inputBytes);
  });

  it("previewBytecode accepts an ArrayBuffer body too", async () => {
    mockFetch([{
      status: 200,
      body: { classIdentity: identity, revision: { value: 1 }, targetMethodCount: 0, adviceTypes: [], diagnostics: [], changed: false },
    }]);
    const buffer = new Uint8Array([9, 9]).buffer;
    await expect(previewBytecode("agt-1", "cls-1", buffer)).resolves.toBeDefined();
  });

  it("captureBytecode posts with no body", async () => {
    const calls = mockFetch([{
      status: 200,
      body: { classIdentity: identity, revision: { value: 1 }, diagnostics: [], capturedAtMillis: 0, captured: true },
    }]);
    await captureBytecode("agt-1", "cls-1");
    expect(calls[0].init?.method).toBe("POST");
    expect(calls[0].init?.body).toBeUndefined();
  });

  it("fetchBytecodeDiff builds fromKind/fromRevision/toKind/toRevision query params", async () => {
    const calls = mockFetch([{
      status: 200,
      body: {
        classIdentity: identity, fromRevision: { value: 0 }, toRevision: { value: 1 },
        fromKind: "INPUT", toKind: "APPLIED", identical: false, normalized: true,
        methodDiffs: [], structuralDiffs: [],
      },
    }]);
    await fetchBytecodeDiff("agt-1", "cls-1", { kind: "INPUT", revision: 0 }, { kind: "APPLIED", revision: 1 });
    const url = calls[0].url;
    expect(url).toContain("fromKind=INPUT");
    expect(url).toContain("fromRevision=0");
    expect(url).toContain("toKind=APPLIED");
    expect(url).toContain("toRevision=1");
  });
});

describe("bytecode client error mapping", () => {
  it("maps a non-ok response to an error carrying the payload message", async () => {
    mockFetch([{ status: 404, body: { code: "NOT_FOUND", message: "快照不存在", retryable: false } }]);
    await expect(
      fetchBytecodeDiff("agt-1", "cls-1", { kind: "INPUT", revision: 0 }, { kind: "APPLIED", revision: 1 }),
    ).rejects.toThrow("快照不存在");
  });

  it("maps a bytecode-bytes non-ok response to an error", async () => {
    mockFetch([{ status: 413, body: { code: "PAYLOAD_TOO_LARGE", message: "字节码超出 8MiB", retryable: false } }]);
    await expect(fetchBytecodeBytes("agt-1", "cls-1", "INPUT", 1)).rejects.toThrow("字节码超出 8MiB");
  });

  it("rejects classIds containing a slash to protect the catch-all BFF", async () => {
    expect(() => fetchBytecodeTransformations("agt-1", "a/b")).toThrow("非法");
    await expect(previewBytecode("agt-1", "a/b", new Uint8Array())).rejects.toThrow("非法");
  });
});
