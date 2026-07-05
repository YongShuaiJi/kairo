import { describe, expect, it } from "vitest";
import { demoHealth, demoList, demoTargets, demoTest, demoValidate } from "@/lib/demo-data";

describe("demo platform contract", () => {
  it("provides representative domain data", () => {
    expect(demoList("agents")).toHaveLength(2);
    expect(demoList("rules")?.[0]).toMatchObject({ status: "ENABLED" });
    expect(demoHealth().services.postgresql.status).toBe("UP");
  });

  it("returns actionable script diagnostics", () => {
    const result = demoValidate({ script: "System.exit(0)" });
    expect(result.valid).toBe(false);
    expect(result.diagnostics[0].code).toBe("FORBIDDEN_API");
  });

  it("scopes registered targets by application and environment", () => {
    expect(demoTargets("", "order-service", "prod")).toEqual([
      expect.objectContaining({ method_name: "queryOrder" }),
    ]);
    expect(demoTargets("", "order-service", "staging")).toHaveLength(0);
  });

  it("never executes scripts in the browser demo", () => {
    expect(demoTest({ script: "return result" }).status).toBe("SUCCESS");
    expect(demoTest({ script: "throw new RuntimeException()" }).status).toBe("FAILED");
  });
});
