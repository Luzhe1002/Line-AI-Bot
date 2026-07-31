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
