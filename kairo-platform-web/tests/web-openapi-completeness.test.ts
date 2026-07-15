import { describe, expect, it } from "vitest";
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { PLATFORM_PATHS, type PlatformOperation } from "@/lib/api/paths";

/**
 * V1.6 §6 / §9: automatically exhaustive Web↔OpenAPI verification. Instead of a
 * hand-maintained report, this test scans the actual Web source (`lib/api`,
 * `components`, `app`) for every Platform request the BFF issues and proves each
 * one is covered by the committed {@link PLATFORM_PATHS} registry. The companion
 * Platform test `WebOpenApiCompletenessTest` proves every registry entry exists
 * in the live OpenAPI document at `/v3/api-docs`. Together: every Web request
 * maps to a real OpenAPI operation, with no manual table to keep in sync.
 *
 * Matching is structural: a `{param}` segment matches any segment (literal or
 * param), so a Web callsite that generalises over a union (e.g.
 * `instances/${id}/agent/${action}`) is covered by the concrete operations it
 * expands to (`attach`/`deactivate`/`reload`). Two segments fail to match only
 * when both are literals and differ.
 *
 * Scope: `platformFetch`/`listResource` callsites are always Platform requests.
 * A bare `fetch(...)` is counted only when it targets the Platform BFF proxy
 * (`/api/platform/...`) or the Platform API directly (`.../api/v1/...`); Web-
 * internal fetches (e.g. `api/auth/session`) are excluded.
 */

const WEB_ROOT = process.cwd();
const SCAN_DIRS = ["lib/api", "components", "app"];
const SKIP_FILES = new Set(["lib/api/client.ts"]); // helper definitions, not callsites
const SOURCE_EXT = new Set([".ts", ".tsx"]);

const CALLSITE =
  /(platformFetch|listResource|fetch)\s*(?:<[^>]*>)?\s*\(\s*(?:"([^"]*)"|`([^`]*)`)/g;

interface RawCall {
  fn: string;
  rawPath: string;
  start: number;
  matchEnd: number;
  nextStart: number;
  file: string;
}

function extname(p: string): string {
  const i = p.lastIndexOf(".");
  return i < 0 ? "" : p.slice(i);
}

function listSourceFiles(dir: string, out: string[] = []): string[] {
  let entries: string[];
  try {
    entries = readdirSync(dir);
  } catch {
    return out;
  }
  for (const entry of entries) {
    if (entry === "node_modules" || entry === ".next") continue;
    const abs = join(dir, entry);
    let st;
    try {
      st = statSync(abs);
    } catch {
      continue;
    }
    if (st.isDirectory()) {
      listSourceFiles(abs, out);
    } else if (SOURCE_EXT.has(extname(abs))) {
      out.push(abs);
    }
  }
  return out;
}

/**
 * Normalise a raw callsite path to a structural template: interpolate `${...}`
 * FIRST (so `?.` inside an interpolation is not mistaken for a query string),
 * strip the BFF proxy prefix `/api/platform/` or anything up to `/api/v1/`,
 * drop the query string, and strip a leading slash.
 */
function toStructural(rawPath: string): string {
  let p = rawPath.replace(/\$\{[^}]*\}/g, "{}");
  const v1 = p.indexOf("/api/v1/");
  if (v1 >= 0) {
    p = p.slice(v1 + "/api/v1/".length);
  } else if (p.startsWith("/api/platform/")) {
    p = p.slice("/api/platform/".length);
  }
  p = p.split("?")[0];
  p = p.replace(/^\/+/, "");
  return p;
}

function structuralSegments(path: string): string[] {
  return path.split("/").map((seg) => (seg.startsWith("{") && seg.endsWith("}") ? "{}" : seg));
}

function segmentsMatch(a: string, b: string): boolean {
  return a === b || a === "{}" || b === "{}";
}

function pathsMatch(scannedPath: string, registryPath: string): boolean {
  const a = structuralSegments(scannedPath);
  const b = structuralSegments(registryPath);
  if (a.length !== b.length) return false;
  return a.every((seg, i) => segmentsMatch(seg, b[i]));
}

function isPlatformFetchCall(fn: string, rawPath: string): boolean {
  if (fn !== "fetch") return true; // platformFetch / listResource always target the Platform
  return rawPath.includes("/api/v1/") || rawPath.startsWith("/api/platform/");
}

function collectRawCalls(): RawCall[] {
  const calls: RawCall[] = [];
  for (const scanDir of SCAN_DIRS) {
    for (const file of listSourceFiles(join(WEB_ROOT, scanDir))) {
      const rel = file.replace(WEB_ROOT + "/", "");
      if (SKIP_FILES.has(rel)) continue;
      const source = readFileSync(file, "utf8");
      const local: RawCall[] = [];
      CALLSITE.lastIndex = 0;
      let m: RegExpExecArray | null;
      while ((m = CALLSITE.exec(source)) !== null) {
        const rawPath = m[2] ?? m[3];
        if (rawPath === undefined) continue;
        local.push({
          fn: m[1],
          rawPath,
          start: m.index,
          matchEnd: m.index + m[0].length,
          nextStart: source.length,
          file: rel,
        });
      }
      // Bound each call's method-lookahead window to the next callsite in the same file.
      for (let i = 0; i < local.length; i++) {
        local[i].nextStart = i + 1 < local.length ? local[i + 1].start : source.length;
      }
      calls.push(...local);
    }
  }
  return calls;
}

interface ScannedOp {
  method: string;
  path: string;
  file: string;
}

function scanCallsites(): ScannedOp[] {
  const ops: ScannedOp[] = [];
  const seen = new Set<string>();
  for (const file of Object.values(groupByFile(collectRawCalls()))) {
    for (const call of file) {
      if (!isPlatformFetchCall(call.fn, call.rawPath)) continue;
      const structural = toStructural(call.rawPath);
      if (!structural || structural === "{}" || !structural.includes("/")) continue;
      const method = methodOf(call);
      const key = `${method} ${structural}`;
      if (seen.has(key)) continue;
      seen.add(key);
      ops.push({ method, path: structural, file: call.file });
    }
  }
  return ops;
}

function groupByFile(calls: RawCall[]): Record<string, RawCall[]> {
  const out: Record<string, RawCall[]> = {};
  for (const c of calls) (out[c.file] ??= []).push(c);
  return out;
}

function methodOf(call: RawCall): string {
  // Look ahead only within this call's region (up to the next callsite) for the
  // init-object method, so the method never bleeds from a later call.
  const source = readFileSync(join(WEB_ROOT, call.file), "utf8");
  const window = source.slice(call.matchEnd, Math.min(call.nextStart, call.matchEnd + 400));
  const m = window.match(/method:\s*["'](\w+)["']/);
  return m ? m[1].toUpperCase() : "GET";
}

const REGISTRY: PlatformOperation[] = [...PLATFORM_PATHS];

describe("V1.6 §6 automatic Web↔OpenAPI completeness", () => {
  const scanned = scanCallsites();

  it("scans a non-trivial set of Web Platform callsites", () => {
    expect(scanned.length, "expected the scanner to find Web callsites").toBeGreaterThan(20);
  });

  it("every Web Platform request is covered by the PLATFORM_PATHS registry", () => {
    const uncovered: string[] = [];
    for (const op of scanned) {
      const covered = REGISTRY.some(
        (r) => r.method.toUpperCase() === op.method && pathsMatch(op.path, r.path),
      );
      if (!covered) uncovered.push(`${op.method} ${op.path}  (in ${op.file})`);
    }
    expect(uncovered, `Web callsites not in the registry:\n${uncovered.join("\n")}`).toEqual([]);
  });

  it("registry entries are well-formed OpenAPI-relative paths", () => {
    for (const op of REGISTRY) {
      expect(op.method, op.path).toMatch(/^(GET|POST|PUT|PATCH|DELETE)$/);
      expect(op.path, "registry path must not start with /").toMatch(/^[^{]/);
      expect(op.path, "registry path must not contain $ interpolation").not.toContain("$");
    }
  });
});
