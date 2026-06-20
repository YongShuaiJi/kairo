import { describe, expect, it } from "vitest";
import { resourceConfigs, type ResourceForm } from "@/lib/resource-config";
import { actionLabel, humanize } from "@/lib/utils";

function allForms() {
  return Object.values(resourceConfigs).flatMap((config) =>
    [config.form, ...(config.tabs ?? []).map((tab) => tab.form)]
      .filter((form): form is ResourceForm => Boolean(form)),
  );
}

describe("resource form relationships", () => {
  it("never invents default application or environment identifiers", () => {
    const relationshipFields = allForms()
      .flatMap((form) => form.fields)
      .filter((field) => ["applicationId", "environmentId", "targetApplication", "targetEnvironment"].includes(field.key));

    expect(relationshipFields.length).toBeGreaterThan(0);
    for (const field of relationshipFields) {
      expect(field.type).toBe("resource");
      expect(field.defaultValue ?? "").toBe("");
    }
  });

  it("uses cascading real-resource selectors for rule rollout", () => {
    const fields = resourceConfigs.rollouts.form?.fields ?? [];
    const byKey = Object.fromEntries(fields.map((field) => [field.key, field]));

    expect(byKey.applicationId).toMatchObject({ type: "resource", source: "applications" });
    expect(byKey.environmentId).toMatchObject({
      type: "resource",
      source: "environments",
      dependsOn: ["applicationId"],
    });
    expect(byKey.resourceId).toMatchObject({
      type: "resource",
      source: "rules",
      dependsOn: ["applicationId", "environmentId"],
    });
    expect(byKey.resourceVersion).toMatchObject({
      type: "resource",
      source: "rule-versions",
      dependsOn: ["resourceId"],
    });
  });

  it("exposes plan-level unload history with Chinese operation labels", () => {
    const tabs = resourceConfigs.rollouts.tabs ?? [];

    expect(tabs.some((tab) => tab.endpoint === "rollback-executions" && tab.label === "卸载记录")).toBe(true);
    expect(actionLabel("UNLOAD")).toBe("卸载规则");
    expect(actionLabel("UNLOAD_PLAN")).toBe("卸载所属计划");
    expect(humanize("RESET_CLASS")).toBe("恢复目标类原始字节码");
  });
});
