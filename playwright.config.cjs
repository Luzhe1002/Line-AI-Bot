const { defineConfig } = require("@playwright/test");

module.exports = defineConfig({
  testDir: "./tests/frontend",
  testMatch: "**/*.e2e.spec.cjs",
  fullyParallel: false,
  timeout: 30_000,
  expect: {
    timeout: 5_000,
  },
  reporter: "line",
  use: {
    baseURL: "http://127.0.0.1:4173",
    channel: "chrome",
    headless: true,
    screenshot: "only-on-failure",
    trace: "retain-on-failure",
  },
  webServer: {
    command: "node tests/frontend/mock-server.mjs",
    url: "http://127.0.0.1:4173/portal/",
    reuseExistingServer: false,
    timeout: 15_000,
  },
});
