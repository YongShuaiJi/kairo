import { describe, expect, it } from "vitest";
import { effectiveTier, tierIndex } from "@/lib/api/script";
import {
  demoScriptCompile,
  demoScriptPolicy,
  demoScriptSession,
  demoScriptSessionEvents,
  demoScriptSessions,
} from "@/lib/demo-data";

describe("effectiveTier (§2.1 min(platform, application, requested))", () => {
  it("returns the most restrictive of the three decisions", () => {
    expect(effectiveTier("UNRESTRICTED", "UNRESTRICTED", "UNRESTRICTED")).toBe("UNRESTRICTED");
    expect(effectiveTier("UNRESTRICTED", "UNRESTRICTED", "SAFE")).toBe("SAFE");
    expect(effectiveTier("SAFE", "UNRESTRICTED", "UNRESTRICTED")).toBe("SAFE");
    expect(effectiveTier("UNRESTRICTED", "EXTENDED", "UNRESTRICTED")).toBe("EXTENDED");
  });

  it("tierIndex orders SAFE < EXTENDED < UNRESTRICTED", () => {
    expect(tierIndex("SAFE")).toBeLessThan(tierIndex("EXTENDED"));
    expect(tierIndex("EXTENDED")).toBeLessThan(tierIndex("UNRESTRICTED"));
  });
});

describe("demo script-session data", () => {
  it("lists sessions optionally filtered by application", () => {
    expect(demoScriptSessions()).toHaveLength(2);
    expect(demoScriptSessions("kairo-demo")).toHaveLength(2);
    expect(demoScriptSessions("no-such-app")).toHaveLength(0);
  });

  it("looks up a single session by id", () => {
    expect(demoScriptSession("ss-01")?.status).toBe("APPLIED");
    expect(demoScriptSession("missing")).toBeUndefined();
  });

  it("builds an event history matching the session state", () => {
    const applied = demoScriptSessionEvents("ss-01");
    expect(applied.map((e) => e.toStatus)).toEqual(["CREATED", "VALIDATED", "APPLIED"]);
    const expired = demoScriptSessionEvents("ss-02");
    expect(expired.map((e) => e.toStatus)).toEqual(["CREATED", "VALIDATED", "APPLIED", "EXPIRED"]);
  });

  it("reports the platform/application/effective ceiling", () => {
    const policy = demoScriptPolicy("kairo-demo");
    expect(policy.platformMaxProfile).toBe("UNRESTRICTED");
    expect(policy.applicationMaxProfile).toBe("SAFE");
    expect(policy.effectiveMaxProfile).toBe("SAFE");
    expect(policy.hasApplicationPolicy).toBe(true);
  });
});

describe("demo script compile console", () => {
  const base = { agentId: "agt-01", scriptHash: "h", policyRevision: { revision: 2, hash: "x" }, compilerVersion: "groovy-4.0.24", targetClassLoaderId: "bootstrap" };
  type CompileOut = { successful: boolean; diagnostics: Array<{ code: string }> };

  it("succeeds for an ordinary script", () => {
    const result = demoScriptCompile({ ...base, script: "return mock.proceed()", capabilityProfile: "SAFE" }) as CompileOut;
    expect(result.successful).toBe(true);
    expect(result.diagnostics).toEqual([]);
  });

  it("reports a forbidden-script diagnostic under SAFE", () => {
    const result = demoScriptCompile({ ...base, script: "new java.io.File('/tmp').exists()", capabilityProfile: "SAFE" }) as CompileOut;
    expect(result.successful).toBe(false);
    expect(result.diagnostics[0].code).toBe("FORBIDDEN_SCRIPT");
  });

  it("reports a compile-error diagnostic for broken syntax", () => {
    const result = demoScriptCompile({ ...base, script: "return mock.proceed(", capabilityProfile: "SAFE" }) as CompileOut;
    expect(result.successful).toBe(false);
    expect(result.diagnostics[0].code).toBe("SCRIPT_COMPILE_ERROR");
  });
});
