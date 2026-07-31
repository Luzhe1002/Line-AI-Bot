const { test, expect } = require("@playwright/test");

function contrastRatio(foreground, background) {
  const values = (value) => value.match(/[\d.]+/g).slice(0, 3).map(Number);
  const luminance = (color) => {
    const channels = values(color).map((channel) => {
      const normalized = channel / 255;
      return normalized <= 0.03928
        ? normalized / 12.92
        : ((normalized + 0.055) / 1.055) ** 2.4;
    });
    return (0.2126 * channels[0]) + (0.7152 * channels[1]) + (0.0722 * channels[2]);
  };
  const first = luminance(foreground);
  const second = luminance(background);
  return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05);
}

test("portal tabs expose ARIA state and support keyboard navigation", async ({ page }) => {
  await page.goto("/portal/");

  const loginTab = page.getByRole("tab", { name: "商家登入" });
  const onboardTab = page.getByRole("tab", { name: "建立商家" });
  await expect(loginTab).toHaveAttribute("aria-selected", "true");
  await expect(onboardTab).toHaveAttribute("aria-selected", "false");

  await loginTab.focus();
  await loginTab.press("ArrowRight");
  await expect(onboardTab).toBeFocused();
  await expect(onboardTab).toHaveAttribute("aria-selected", "true");
  await expect(page.locator("#onboard-form")).toBeVisible();
  await expect(page.locator("#login-form")).toBeHidden();
});

test("portal remains usable at 320px without iOS input zoom or horizontal overflow", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 568 });
  await page.goto("/portal/");

  const metrics = await page.evaluate(() => ({
    innerWidth: window.innerWidth,
    scrollWidth: document.documentElement.scrollWidth,
    inputFontSize: getComputedStyle(document.querySelector("#login-form input")).fontSize,
  }));
  expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.innerWidth);
  expect(Number(metrics.inputFontSize.replace("px", ""))).toBeGreaterThanOrEqual(16);

  const colors = await page.locator("#auth-tab-onboard").evaluate((element) => ({
    foreground: getComputedStyle(element).color,
    background: getComputedStyle(element.parentElement).backgroundColor,
  }));
  expect(contrastRatio(colors.foreground, colors.background)).toBeGreaterThanOrEqual(4.5);
});

test("portal dashboard presents operational status and role-specific LINE entry", async ({ page }) => {
  await page.goto("/portal/#token=e2e-token");

  await expect(page.locator("#app-view")).toBeVisible();
  await expect(page.locator("#overview-status-title")).toHaveText("AI 客服已準備好服務顧客");
  await expect(page.locator("#system-pill")).toHaveText("營運準備完成");
  await expect(page.locator("#line-metric-value")).toHaveText("已連線");
  await expect(page.locator("#document-count")).toHaveText("1");
  await expect(page.locator("#staff-count")).toHaveText("2");

  await page.getByRole("button", { name: "店家人員", exact: true }).click();
  const owner = page.locator(".staff-item").filter({ hasText: "王店長" });
  await expect(owner.getByText("顯示「管理後台」，可進入完整工作台。"))
    .toBeVisible();
  await expect(owner.locator(".staff-menu-note")).toContainText("管理後台");
});

test("knowledge items can be added, edited, and deleted without choosing a dataset", async ({ page }) => {
  await page.goto("/portal/#token=e2e-token");
  await page.getByRole("button", { name: "知識庫", exact: true }).click();

  await expect(page.locator("#dataset-select")).toHaveCount(0);
  await expect(page.locator("#knowledge-version")).toContainText("目前正式版");
  await page.getByRole("button", { name: "新增知識", exact: true }).click();
  await expect(page.locator("#document-form")).toBeVisible();
  await page.locator("#document-form input[name=title]").fill("營業時間");
  await page.locator("#document-form textarea[name=content]").fill("每天上午十點到晚上八點營業。");
  await page.getByRole("button", { name: "新增並自動索引" }).click();

  const item = page.locator(".document-item").filter({ hasText: "營業時間" });
  await expect(item).toBeVisible();
  await item.getByRole("button", { name: "編輯" }).click();
  await page.locator("#document-form textarea[name=content]").fill("每天上午十點到晚上九點營業。");
  await page.getByRole("button", { name: "儲存修改" }).click();
  await expect(item).toContainText("晚上九點");

  page.once("dialog", (dialog) => dialog.accept());
  await item.getByRole("button", { name: "刪除" }).click();
  await expect(item).toHaveCount(0);
});

test("reindexing published knowledge creates a publishable draft", async ({ page }) => {
  await page.goto("/portal/#token=e2e-token");
  await page.getByRole("button", { name: "知識庫", exact: true }).click();

  await expect(page.locator("#knowledge-version")).toContainText("目前正式版");
  await expect(page.getByRole("button", { name: "發布更新" })).toBeDisabled();
  await page.getByText("索引異常處理", { exact: true }).click();
  await page.getByRole("button", { name: "重新建立全部索引" }).click();

  await expect(page.locator("#knowledge-version")).toContainText("待發布草稿");
  await expect(page.getByRole("button", { name: "發布更新" })).toBeEnabled();
  await page.getByRole("button", { name: "發布更新" }).click();
  await expect(page.locator("#knowledge-version")).toContainText("目前正式版");
});

test("staff list removes redundant active labels and can remove a binding", async ({ page }) => {
  await page.goto("/portal/#token=e2e-token");
  await page.getByRole("button", { name: "店家人員", exact: true }).click();

  await expect(page.getByText("啟用中", { exact: true })).toHaveCount(0);
  const manager = page.locator(".staff-item").filter({ hasText: "林主管" });
  await expect(manager).toBeVisible();
  await manager.locator("summary").click();
  page.once("dialog", (dialog) => dialog.accept());
  await manager.getByRole("button", { name: "移除綁定" }).click();
  await expect(manager).toHaveCount(0);
  await expect(page.getByText("王店長", { exact: true })).toBeVisible();
});

test("expired portal sessions return to login with localized guidance", async ({ page }) => {
  await page.goto("/portal/#token=e2e-token");
  await page.getByRole("button", { name: "店家人員", exact: true }).click();

  const owner = page.locator(".staff-item").filter({ hasText: "王店長" });
  await owner.locator("summary").click();
  await page.evaluate(() => fetch("/portal/api/test/expire-session", { method: "POST" }));
  await owner.getByRole("button", { name: "儲存設定" }).click();

  await expect(page.locator("#auth-view")).toBeVisible();
  await expect(page.getByRole("alert"))
    .toContainText("登入狀態已過期，請重新登入後再操作");
});

test("connected LINE channel marks every setup step complete", async ({ page }) => {
  await page.goto("/portal/#token=e2e-token");
  await page.getByRole("button", { name: "LINE 設定", exact: true }).click();

  await expect(page.locator(".setup-steps li.done")).toHaveCount(3);
  await expect(page.locator(".setup-steps li.active")).toHaveCount(0);
  await expect(page.locator("#line-setup-status")).toContainText("LINE 設定已完成");
});

test("portal operations dashboard stays within a 375px mobile viewport", async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 });
  await page.goto("/portal/#token=e2e-token");

  const metrics = await page.evaluate(() => ({
    innerWidth: window.innerWidth,
    scrollWidth: document.documentElement.scrollWidth,
    metricColumns: getComputedStyle(document.querySelector(".metric-grid")).gridTemplateColumns,
    navPosition: getComputedStyle(document.querySelector(".sidebar")).position,
  }));
  expect(metrics.scrollWidth).toBeLessThanOrEqual(metrics.innerWidth);
  expect(metrics.metricColumns.trim().split(/\s+/)).toHaveLength(1);
  expect(metrics.navPosition).toBe("fixed");
});

test("customer booking moves focus through the complete happy path", async ({ page }) => {
  await page.goto("/booking/demo/#token=e2e-token");

  const service = page.locator(".service");
  await expect(service).toHaveCount(1);
  await service.click();
  await expect(page.getByRole("heading", { name: "選擇日期與時間" })).toBeFocused();

  const slot = page.locator(".slot");
  await expect(slot).toHaveCount(1);
  await slot.click();
  await expect(page.getByRole("heading", { name: "確認預約內容" })).toBeFocused();

  await page.locator("#customer-name").fill("測試顧客");
  await page.locator("#confirm").click();
  await expect(page.getByRole("heading", { name: "預約完成" })).toBeFocused();
  await expect(page.locator(".progress")).toHaveAttribute("aria-valuenow", "4");
  await expect(page.locator("#success-message")).toContainText("已預約成功");
});

test("customer booking hides inactive steps on fatal errors and renders API text safely", async ({ page }) => {
  await page.goto("/booking/index.html");
  await expect(page.getByRole("heading", { name: "無法開啟預約頁" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "選擇服務" })).toBeHidden();

  await page.goto("/booking/demo/#token=e2e-token");
  await page.locator(".service").click();
  const date = page.locator("#booking-date");
  await date.fill("2099-12-31");
  await date.evaluate((element) => element.dispatchEvent(new Event("change", { bubbles: true })));
  await expect(page.locator("#slots")).toContainText("<strong>時段錯誤</strong>");
  await expect(page.locator("#slots strong")).toHaveCount(0);
});

test("merchant agenda shows loading/error feedback and localized role", async ({ page }) => {
  await page.goto("/merchant-booking/demo/#token=e2e-token");
  await expect(page.locator("#tenant-name")).toHaveText("測試店家");
  await expect(page.locator("#role-badge")).toHaveText("擁有者");
  await expect(page.locator(".eyebrow")).toHaveText([
    "店家預約管理",
    "當日行程",
    "暫停開放",
    "封鎖紀錄",
  ]);

  const refresh = page.locator("#refresh-button");
  await refresh.click();
  await expect(refresh).toBeDisabled();
  await expect(refresh).toHaveText("更新中…");
  await expect(refresh).toBeEnabled();

  const agendaDate = page.locator("#agenda-date");
  await agendaDate.fill("2099-12-31");
  await agendaDate.evaluate((element) => element.dispatchEvent(new Event("change", { bubbles: true })));
  await expect(page.getByRole("alert")).toContainText("暫時無法讀取預約");
  await expect(page.getByRole("alert")).toContainText("請稍後再試");
});

test("merchant fatal error replaces loading chrome and includes recovery guidance", async ({ page }) => {
  await page.goto("/merchant-booking/missing/#token=e2e-token");

  await expect(page.getByRole("heading", { name: "無法開啟管理頁" })).toBeFocused();
  await expect(page.locator(".topbar")).toBeHidden();
  await expect(page.locator("#error-message")).toContainText("管理連結已失效");
  await expect(page.locator("#error-message")).toContainText("請回到 LINE");
});
