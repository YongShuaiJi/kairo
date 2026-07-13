import { describe, expect, it } from "vitest";
import { demoLoaders } from "@/lib/demo-data";
import { supportLevelVariant, targetMetadataFields } from "@/components/rules/target-class-info";
import type { PlatformRecord } from "@/lib/api/types";

type LoaderInfo = { loaderId: string; loaderClassName: string; parentLoaderId: string | null; frameworkLoader?: string };

describe("demoLoaders (V1.5 loader tree)", () => {
  it("returns a bootstrap-rooted parent->children tree with framework labels", () => {
    const tree = demoLoaders();
    const loaders = tree.loaders as LoaderInfo[];
    const treeMap = tree.tree as Record<string, LoaderInfo[]>;
    expect(tree.bootstrapLoaderId).toBe("bootstrap");
    expect(loaders.length).toBe(4);
    expect(treeMap.bootstrap).toHaveLength(1);
    expect(loaders.some((l) => l.frameworkLoader?.includes("Spring Boot"))).toBe(true);
    // The Tomcat loader is a child of the Spring Boot loader.
    const spring = loaders.find((l) => l.loaderClassName.includes("LaunchedURLClassLoader"));
    expect(spring).toBeTruthy();
    expect(treeMap[spring!.loaderId]).toBeDefined();
  });
});

describe("targetMetadataFields (V1.5 metadata echo)", () => {
  it("reads camelCase keys the agent echoes from DISCOVER_TARGETS / RESOLVE_TARGET", () => {
    const target: PlatformRecord = {
      proxyType: "CGLIB",
      supportLevel: "LIMITED",
      driftStatus: "DRIFTED",
      loaderClass: "org.springframework.boot.loader.launch.LaunchedURLClassLoader",
      frameworkLoader: "Spring Boot (LaunchedURLClassLoader)",
      declaredClassName: "com.example.OrderService",
      actualEnhancedClassName: "com.example.OrderService$$EnhancerByCGLIB$$abc",
      proxyClassName: "com.example.OrderService$$EnhancerByCGLIB$$abc",
      proxySuperclass: "com.example.OrderService",
      recommendedTargetClass: "com.example.OrderService",
    };
    const fields = targetMetadataFields(target);
    expect(fields?.proxyType).toBe("CGLIB");
    expect(fields?.supportLevel).toBe("LIMITED");
    expect(fields?.driftStatus).toBe("DRIFTED");
    expect(fields?.actualEnhancedClassName).toContain("EnhancerByCGLIB");
    expect(fields?.recommendedTargetClass).toBe("com.example.OrderService");
  });

  it("reads snake_case columns the platform persists on rule_target", () => {
    const target: PlatformRecord = {
      proxy_type: "JDK_PROXY",
      support_level: "EXPERIMENTAL",
      drift_status: "FRESH",
      loader_class: "java.net.URLClassLoader",
      framework_loader: "Spring Boot embedded Tomcat",
    };
    const fields = targetMetadataFields(target);
    expect(fields?.proxyType).toBe("JDK_PROXY");
    expect(fields?.supportLevel).toBe("EXPERIMENTAL");
    expect(fields?.driftStatus).toBe("FRESH");
    expect(fields?.loaderClass).toBe("java.net.URLClassLoader");
    expect(fields?.frameworkLoader).toBe("Spring Boot embedded Tomcat");
  });

  it("returns null for a legacy target with no V1.5 metadata", () => {
    const fields = targetMetadataFields({ class_name: "com.example.Foo" });
    expect(fields).not.toBeNull();
    expect(fields?.proxyType).toBe("");
    expect(fields?.supportLevel).toBe("");
  });
});

describe("supportLevelVariant (V1.5 risk badges)", () => {
  it("maps support levels to badge variants", () => {
    expect(supportLevelVariant("SUPPORTED")).toBe("success");
    expect(supportLevelVariant("LIMITED")).toBe("warning");
    expect(supportLevelVariant("UNSUPPORTED")).toBe("danger");
    expect(supportLevelVariant("EXPERIMENTAL")).toBe("neutral");
  });
});
