import { expect, test } from "@playwright/test";

test("login, inspect overview, and open rule editor", async ({ page }) => {
  test.setTimeout(90_000);
  await page.goto("/login");
  await page.getByPlaceholder("粘贴访问 Token").fill("kairo-demo");
  await page.getByRole("button", { name: "进入平台" }).click();
  await expect(page.getByRole("heading", { name: "运行总览" })).toBeVisible();
  await expect(page.getByRole("link", { name: "创建规则" })).toBeVisible();
  await page.goto("/rules/new");
  await expect(page).toHaveURL(/\/rules\/new$/, { timeout: 60_000 });
  await expect(page.getByText("Groovy 安全沙箱")).toBeVisible({ timeout: 60_000 });
  await expect(page.getByText("rule.groovy")).toBeVisible();
  await expect(page.getByRole("heading", { name: "创建规则" })).toBeVisible();
  await expect(page.getByText("尚未保存")).toBeVisible();
  await expect(page.getByLabel("规则名称")).toHaveValue("");
  await expect(page.getByRole("button", { name: "保存草稿" })).toBeDisabled();
  await expect(page.getByText("请先选择目标方法")).toBeVisible();
  await expect(page.getByTestId("rule-editor-surface")).toHaveAttribute("data-editor-theme", "light");
  await expect(page.getByText("订单查询返回值替换")).toHaveCount(0);

  await page.getByLabel("规则名称").fill("订单查询安全放行");
  await page.locator("#rule-application").selectOption("order-service");
  await page.locator("#rule-environment").selectOption("prod");
  await page.getByRole("button", { name: /queryOrder/ }).click();
  await page.locator("#rule-phase").selectOption("RETURN");
  await expect(page.getByRole("button", { name: "保存草稿" })).toBeEnabled();
  await expect(page.getByText("安全默认行为：继续执行原方法")).toBeVisible();
  await page.getByRole("button", { name: "专注模式" }).click();
  await expect(page.getByTestId("rule-editor-surface")).toHaveAttribute("data-editor-theme", "dark");
  await page.getByRole("button", { name: "退出专注" }).click();
  await expect(page.getByTestId("rule-editor-surface")).toHaveAttribute("data-editor-theme", "light");
});
