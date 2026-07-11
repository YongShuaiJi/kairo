import { expect, test } from "@playwright/test";

/**
 * V1.1 bytecode comparison page contract. Runs against the demo-mode BFF, which proxies
 * the five platform bytecode APIs from in-memory data. Covers the preview/actual
 * annotations, ClassLoader isolation field, diff source-decompilation degradation
 * (notice + retained bytecode diff), and API error handling for a malformed classId.
 */

const VALID_CLASS_ID = Buffer
  .from("app-loader-7f3a|com.example.demo.OrderService")
  .toString("base64url");
const MALFORMED_CLASS_ID = "YWJj"; // base64url of "abc" - no classLoaderId|className separator

async function login(page: import("@playwright/test").Page) {
  await page.goto("/login");
  await page.getByPlaceholder("粘贴访问 Token").fill("kairo-demo");
  await page.getByRole("button", { name: "进入平台" }).click();
  // The session cookie is set once the app-shell renders; the post-login landing page
  // varies, so wait for a stable shell marker rather than a specific heading.
  await expect(page.getByText("当前使用演示数据")).toBeVisible({ timeout: 30_000 });
}

test.describe("bytecode comparison page", () => {
  test.beforeEach(async ({ page }) => {
    test.setTimeout(90_000);
    await login(page);
  });

  test("shows read-only preview / JVM-actual annotations and the ClassLoader isolation field", async ({ page }) => {
    await page.goto(`/agents/agt-01/bytecode?classId=${VALID_CLASS_ID}`);

    await expect(page.getByRole("heading", { name: "字节码增强对比" })).toBeVisible();
    await expect(page.getByTestId("read-only-notice")).toContainText("未修改 JVM");

    // Three metadata cards render.
    await expect(page.getByTestId("snapshot-card-input")).toBeVisible();
    await expect(page.getByTestId("snapshot-card-planned")).toBeVisible();
    await expect(page.getByTestId("snapshot-card-applied")).toBeVisible();

    // The two annotations the task requires must be unambiguous.
    await expect(page.getByTestId("snapshot-notice-planned")).toContainText("只读预览");
    await expect(page.getByTestId("snapshot-notice-planned")).toContainText("未修改 JVM");
    await expect(page.getByTestId("snapshot-notice-applied")).toContainText("JVM 实际运行");

    // ClassLoader isolation field is surfaced on every card.
    await expect(page.getByTestId("snapshot-classloader-input")).toContainText("app-loader-7f3a");
    await expect(page.getByTestId("snapshot-classloader-applied")).toContainText("app-loader-7f3a");

    // Decoded identity is shown for orientation.
    await expect(page.getByTestId("class-id-decoded")).toContainText("com.example.demo.OrderService @ app-loader-7f3a");

    // Transformation history + current revision render.
    await expect(page.getByTestId("transformation-history")).toBeVisible();
    await expect(page.getByTestId("transformation-history")).toContainText("当前 revision r2");
  });

  test("runs a structured bytecode diff and degrades source decompilation honestly", async ({ page }) => {
    await page.goto(`/agents/agt-01/bytecode?classId=${VALID_CLASS_ID}`);
    await expect(page.getByTestId("transformation-history")).toBeVisible();

    // Default selectors (INPUT@rev -> APPLIED@rev) are auto-filled; run the diff.
    await page.getByRole("button", { name: "对比" }).click();

    const diffView = page.getByTestId("bytecode-diff-view");
    await expect(diffView).toBeVisible();
    // Structured method/instruction diff is the authoritative view.
    await expect(page.getByTestId("bytecode-diff-methods")).toBeVisible();
    await expect(page.getByTestId("instruction-line").first()).toBeVisible();

    // Switch to the source-decompilation tab. Decompilation is unavailable in this
    // phase, so a clear notice shows and the structured bytecode diff is retained.
    await page.getByRole("tab", { name: "源码反编译" }).click();
    await expect(page.getByTestId("decompilation-notice")).toBeVisible();
    await expect(page.getByTestId("decompilation-notice")).toContainText("反编译源码不可用");
    await expect(page.getByTestId("decompilation-notice").getByTestId("bytecode-diff-methods")).toBeVisible();
    // No fabricated source panel is rendered.
    await expect(page.getByTestId("decompilation-source")).toHaveCount(0);
  });

  test("surfaces an API error for a malformed classId", async ({ page }) => {
    await page.goto(`/agents/agt-01/bytecode?classId=${MALFORMED_CLASS_ID}`);
    await expect(page.getByTestId("transformations-error")).toBeVisible({ timeout: 30_000 });
    await expect(page.getByTestId("transformations-error")).toContainText("classId 无法解码");
  });
});
