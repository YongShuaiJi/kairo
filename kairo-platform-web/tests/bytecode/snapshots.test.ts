import { describe, expect, it } from "vitest";
import type {
  CaptureResponse,
  PreviewResponse,
  TransformationsResponse,
} from "@/lib/api/bytecode";
import { defaultDiffSelection, deriveSnapshots } from "@/lib/bytecode/snapshots";

const identity = { binaryClassName: "com.example.Foo", classLoaderId: "loader-1" };

function transformations(over: Partial<TransformationsResponse> = {}): TransformationsResponse {
  return {
    classIdentity: identity,
    currentRevision: { value: 2 },
    count: 2,
    history: [
      { classIdentity: identity, revision: { value: 2 }, status: "SUCCEEDED", inputHash: "in2", outputHash: "out2", diagnostics: [], attemptedAtMillis: 1000, durationMillis: 5 },
      { classIdentity: identity, revision: { value: 1 }, status: "SUCCEEDED", inputHash: "in1", outputHash: "out1", diagnostics: [], attemptedAtMillis: 500, durationMillis: 3 },
    ],
    ...over,
  };
}

const preview: PreviewResponse = {
  classIdentity: identity,
  revision: { value: 2 },
  inputHash: "in2",
  plannedHash: "plan2",
  plannedSizeBytes: 264,
  targetMethodCount: 2,
  adviceTypes: ["MethodDelegation"],
  diagnostics: [],
  changed: true,
};

const capture: CaptureResponse = {
  classIdentity: identity,
  revision: { value: 2 },
  appliedHash: "cap2",
  sizeBytes: 272,
  diagnostics: [],
  capturedAtMillis: 2000,
  captured: true,
};

describe("deriveSnapshots", () => {
  it("derives INPUT and APPLIED cards from history at the latest revision", () => {
    const { cards, currentRevision } = deriveSnapshots(transformations(), undefined, undefined);
    expect(currentRevision).toBe(2);
    expect(cards.input?.revision).toBe(2);
    expect(cards.input?.hash).toBe("in2");
    expect(cards.input?.capturedAtMillis).toBe(1000);
    expect(cards.applied?.hash).toBe("out2");
    expect(cards.planned).toBeUndefined();
  });

  it("offers INPUT@rev and APPLIED@rev selectors from every history entry", () => {
    const { selectors } = deriveSnapshots(transformations(), undefined, undefined);
    const labels = selectors.map((s) => `${s.kind}@${s.revision}`);
    expect(labels).toEqual(expect.arrayContaining(["INPUT@2", "INPUT@1", "APPLIED@2", "APPLIED@1"]));
  });

  it("prefers a fresh capture for the APPLIED card, including size and capture time", () => {
    const { cards } = deriveSnapshots(transformations(), undefined, capture);
    expect(cards.applied?.hash).toBe("cap2");
    expect(cards.applied?.sizeBytes).toBe(272);
    expect(cards.applied?.capturedAtMillis).toBe(2000);
  });

  it("derives the PLANNED card only from a preview result", () => {
    const { cards, selectors } = deriveSnapshots(transformations(), preview, undefined);
    expect(cards.planned?.hash).toBe("plan2");
    expect(cards.planned?.sizeBytes).toBe(264);
    expect(selectors.map((s) => `${s.kind}@${s.revision}`)).toContain("PLANNED@2");
  });

  it("records the on-demand fetched INPUT size without fetching bytes itself", () => {
    const { cards } = deriveSnapshots(transformations(), undefined, undefined, 999);
    expect(cards.input?.sizeBytes).toBe(999);
  });

  it("falls back to default INITIAL selectors when there is no history", () => {
    const empty = transformations({ history: [], count: 0, currentRevision: { value: 0 } });
    const { selectors } = deriveSnapshots(empty, undefined, undefined);
    expect(selectors).toEqual([{ kind: "INPUT", revision: 0 }, { kind: "APPLIED", revision: 0 }]);
  });
});

describe("defaultDiffSelection", () => {
  it("prefers INPUT -> APPLIED", () => {
    const sel = defaultDiffSelection([
      { kind: "INPUT", revision: 2 },
      { kind: "APPLIED", revision: 2 },
      { kind: "PLANNED", revision: 2 },
    ]);
    expect(sel.from?.kind).toBe("INPUT");
    expect(sel.to?.kind).toBe("APPLIED");
  });

  it("falls back to INPUT -> PLANNED when no APPLIED exists", () => {
    const sel = defaultDiffSelection([
      { kind: "INPUT", revision: 2 },
      { kind: "PLANNED", revision: 2 },
    ]);
    expect(sel.from?.kind).toBe("INPUT");
    expect(sel.to?.kind).toBe("PLANNED");
  });

  it("falls back to the first two selectors otherwise", () => {
    const sel = defaultDiffSelection([
      { kind: "PLANNED", revision: 1 },
      { kind: "PLANNED", revision: 2 },
    ]);
    expect(sel.from?.revision).toBe(1);
    expect(sel.to?.revision).toBe(2);
  });

  it("returns a single selector when only one is available", () => {
    const sel = defaultDiffSelection([{ kind: "INPUT", revision: 0 }]);
    expect(sel.from?.kind).toBe("INPUT");
    expect(sel.to).toBeUndefined();
  });
});
