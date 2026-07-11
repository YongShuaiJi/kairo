import type {
  BytecodeSnapshotKind,
  CaptureResponse,
  PreviewResponse,
  SnapshotSelector,
  TransformationsResponse,
} from "@/lib/api/bytecode";

/** Metadata for one snapshot, as shown on a metadata card or diff selector. */
export type SnapshotMeta = {
  kind: BytecodeSnapshotKind;
  revision: number;
  hash?: string;
  sizeBytes?: number;
  capturedAtMillis?: number;
  /** Provenance label, e.g. "jvm-input", "preview", "jvm". */
  source?: string;
};

export type SnapshotMetadataCards = {
  input?: SnapshotMeta;
  planned?: SnapshotMeta;
  applied?: SnapshotMeta;
};

export type DerivedSnapshots = {
  selectors: SnapshotSelector[];
  cards: SnapshotMetadataCards;
  currentRevision: number;
};

/**
 * Derive the available {@code KIND@revision} diff selectors and the INPUT / PLANNED /
 * APPLIED metadata cards from the loaded transformations and any preview/capture results.
 *
 * <p>Bytes are not fetched here - sizes come only from the JSON metadata (preview's
 * {@code plannedSizeBytes}, capture's {@code sizeBytes}) and the optional
 * {@code fetchedInputSize} set when the page pulls INPUT bytes on demand for a preview.
 * This keeps the octet-stream endpoint strictly on-demand.
 *
 * @param transformations the {@code GET .../transformations} response.
 * @param preview          the most recent {@code POST .../preview} result, if any.
 * @param capture          the most recent {@code POST .../capture} result, if any.
 * @param fetchedInputSize INPUT byte length observed when fetching bytes for a preview.
 */
export function deriveSnapshots(
  transformations: TransformationsResponse | undefined,
  preview: PreviewResponse | undefined,
  capture: CaptureResponse | undefined,
  fetchedInputSize?: number,
): DerivedSnapshots {
  const cards: SnapshotMetadataCards = {};
  const selectorMap = new Map<string, SnapshotSelector>();

  const history = transformations?.history ?? [];
  const sorted = [...history].sort((left, right) => right.revision.value - left.revision.value);
  const latest = sorted[0];
  const currentRevision = transformations?.currentRevision.value ?? latest?.revision.value ?? 0;

  if (latest) {
    cards.input = {
      kind: "INPUT",
      revision: latest.revision.value,
      hash: latest.inputHash ?? undefined,
      sizeBytes: fetchedInputSize,
      capturedAtMillis: latest.attemptedAtMillis,
      source: "jvm-input",
    };
  }

  for (const entry of sorted) {
    if (entry.inputHash) {
      addSelector(selectorMap, { kind: "INPUT", revision: entry.revision.value });
    }
    if (entry.outputHash) {
      addSelector(selectorMap, { kind: "APPLIED", revision: entry.revision.value });
    }
  }

  if (preview) {
    cards.planned = {
      kind: "PLANNED",
      revision: preview.revision.value,
      hash: preview.plannedHash ?? undefined,
      sizeBytes: preview.plannedSizeBytes ?? undefined,
      source: "preview",
    };
    addSelector(selectorMap, { kind: "PLANNED", revision: preview.revision.value });
  }

  if (capture) {
    cards.applied = {
      kind: "APPLIED",
      revision: capture.revision.value,
      hash: capture.appliedHash ?? undefined,
      sizeBytes: capture.sizeBytes ?? undefined,
      capturedAtMillis: capture.capturedAtMillis,
      source: "jvm",
    };
    addSelector(selectorMap, { kind: "APPLIED", revision: capture.revision.value });
  } else if (latest && latest.outputHash) {
    cards.applied = {
      kind: "APPLIED",
      revision: latest.revision.value,
      hash: latest.outputHash,
      capturedAtMillis: latest.attemptedAtMillis,
      source: "jvm",
    };
  }

  return {
    selectors: selectorMap.size > 0
      ? Array.from(selectorMap.values())
      : defaultSelectors(currentRevision),
    cards,
    currentRevision,
  };
}

function addSelector(map: Map<string, SnapshotSelector>, selector: SnapshotSelector) {
  const key = `${selector.kind}@${selector.revision}`;
  if (!map.has(key)) map.set(key, selector);
}

/** When no history exists yet, offer the INITIAL revision so the user can still diff. */
function defaultSelectors(revision: number): SnapshotSelector[] {
  return [
    { kind: "INPUT", revision },
    { kind: "APPLIED", revision },
  ];
}

/**
 * Choose sensible default from/to selectors for the diff panel: INPUT -> APPLIED when
 * both exist, falling back to the first two distinct selectors.
 */
export function defaultDiffSelection(selectors: SnapshotSelector[]): {
  from?: SnapshotSelector;
  to?: SnapshotSelector;
} {
  if (selectors.length === 0) return {};
  if (selectors.length === 1) return { from: selectors[0] };
  const input = selectors.find((s) => s.kind === "INPUT");
  const applied = selectors.find((s) => s.kind === "APPLIED");
  const planned = selectors.find((s) => s.kind === "PLANNED");
  if (input && applied) return { from: input, to: applied };
  if (input && planned) return { from: input, to: planned };
  return { from: selectors[0], to: selectors[1] };
}
