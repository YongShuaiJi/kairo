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
      .filter((field) => [
        "applicationId",
        "environmentId",
        "environmentKey",
        "applicationEnvironment",
        "targetApplication",
        "targetEnvironment",
      ].includes(field.key));

    expect(relationshipFields.length).toBeGreaterThan(0);
    for (const field of relationshipFields) {
      expect(field.type).toBe("resource");
      expect(field.defaultValue ?? "").toBe("");
    }
  });

  it("uses cascading real-resource selectors for rule rollout", () => {
    const fields = resourceConfigs.rollouts.form?.fields ?? [];
    const byKey = Object.fromEntries(fields.map((field) => [field.key, field]));

    expect(byKey.environmentKey).toMatchObject({
      type: "resource",
      source: "rollout-environments",
    });
    expect(byKey.applicationEnvironment).toMatchObject({
      type: "resource",
      source: "rollout-applications",
      dependsOn: ["environmentKey"],
    });
    expect(byKey.resourceId).toMatchObject({
      type: "resource",
      source: "rules",
      dependsOn: ["environmentKey", "applicationEnvironment"],
    });
    expect(byKey.resourceVersion).toMatchObject({
      type: "resource",
      source: "rule-versions",
      dependsOn: ["resourceId"],
    });
    expect(resourceConfigs.rollouts.form?.buildPayload?.({
      environmentKey: "dev",
      applicationEnvironment: "app-default|env-dev",
      resourceType: "rule",
      resourceId: "AA-20260702-001",
      resourceVersion: "1",
      targetMode: "ALL_ACTIVE_INSTANCES",
      automaticUnload: "true",
    })).toMatchObject({
      applicationId: "app-default",
      environmentId: "env-dev",
    });
    expect(resourceConfigs.rollouts.columns.some((column) => column.key === "resourceVersion")).toBe(false);
    expect(resourceConfigs.rules.columns.some((column) => column.key === "onlineVersion")).toBe(false);
    expect(resourceConfigs.rules.columns.some((column) => column.key === "latestVersion")).toBe(false);
  });

  it("exposes plan-level unload history with Chinese operation labels", () => {
    const tabs = resourceConfigs.rollouts.tabs ?? [];

    expect(tabs.some((tab) => tab.endpoint === "rollback-executions" && tab.label === "卸载记录")).toBe(true);
    expect(actionLabel("UNLOAD")).toBe("卸载规则");
    expect(actionLabel("UNLOAD_PLAN")).toBe("卸载所属计划");
    expect(humanize("RESET_CLASS")).toBe("恢复目标类原始字节码");
  });

  it("keeps token issuing focused on username and explicit expiry time", () => {
    const form = resourceConfigs.settings.form;
    const fields = form?.fields ?? [];

    expect(resourceConfigs.settings.columns.map((column) => [column.key, column.label])).toEqual([
      ["subjectId", "用户名"],
      ["status", "状态"],
      ["expiresAt", "过期时间"],
      ["lastUsedAt", "最后使用"],
    ]);
    expect(fields.map((field) => field.key)).toEqual(["username", "expiresAt"]);
    expect(fields.find((field) => field.key === "username")).toMatchObject({
      type: "text",
      required: true,
    });
    expect(fields.find((field) => field.key === "expiresAt")).toMatchObject({ type: "date-time", required: false });
    expect(humanize("VALID")).toBe("有效");
    expect(humanize("INVALID")).toBe("失效");

    const payload = form?.buildPayload?.({
      username: "system",
      expiresAt: "2026-07-04T12:34:56",
    }) ?? {};

    expect(payload).toMatchObject({
      username: "system",
      displayName: "system",
    });
    expect(payload).not.toHaveProperty("subjectType");
    expect(payload).not.toHaveProperty("subjectId");
    expect(payload).not.toHaveProperty("ttlSeconds");
    expect(typeof payload.expiresAt).toBe("string");
    expect(form?.buildPayload?.({ username: "system", expiresAt: "" })?.expiresAt).toBeNull();
  });
});
