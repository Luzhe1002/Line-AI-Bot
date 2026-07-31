const test = require("node:test");
const assert = require("node:assert/strict");
const UiUtils = require("../../src/main/resources/static/shared/ui-utils.js");

test("latest request gate accepts only the newest request", () => {
  const gate = UiUtils.createLatestRequestGate();
  const first = gate.begin();
  const second = gate.begin();

  assert.equal(gate.isLatest(first), false);
  assert.equal(gate.isLatest(second), true);

  gate.invalidate();
  assert.equal(gate.isLatest(second), false);
});

test("tab keyboard navigation wraps and supports Home/End", () => {
  assert.equal(UiUtils.tabIndexForKey(0, "ArrowRight", 2), 1);
  assert.equal(UiUtils.tabIndexForKey(1, "ArrowRight", 2), 0);
  assert.equal(UiUtils.tabIndexForKey(0, "ArrowLeft", 2), 1);
  assert.equal(UiUtils.tabIndexForKey(1, "Home", 2), 0);
  assert.equal(UiUtils.tabIndexForKey(0, "End", 2), 1);
  assert.equal(UiUtils.tabIndexForKey(1, "Enter", 2), 1);
});

test("recovery guidance is appended once", () => {
  const recovery = "請回到 LINE 重新開啟。";
  assert.equal(
    UiUtils.withRecoveryMessage("管理連結已失效。", recovery),
    `管理連結已失效。 ${recovery}`,
  );
  assert.equal(
    UiUtils.withRecoveryMessage(`管理連結已失效。 ${recovery}`, recovery),
    `管理連結已失效。 ${recovery}`,
  );
  assert.equal(UiUtils.withRecoveryMessage("", recovery), recovery);
});

test("merchant roles have localized labels", () => {
  assert.equal(UiUtils.roleLabel("OWNER"), "擁有者");
  assert.equal(UiUtils.roleLabel("MANAGER"), "管理員");
  assert.equal(UiUtils.roleLabel("VIEWER"), "檢視者");
  assert.equal(UiUtils.roleLabel("CUSTOM"), "CUSTOM");
});

test("knowledge index status uses merchant-friendly labels", () => {
  assert.equal(UiUtils.knowledgeIndexStatusLabel("READY"), "可使用");
  assert.equal(UiUtils.knowledgeIndexStatusLabel("FAILED"), "索引失敗");
  assert.equal(UiUtils.knowledgeIndexStatusLabel("INDEXING"), "處理中");
  assert.equal(UiUtils.knowledgeIndexStatusLabel("PENDING"), "等待處理");
});

test("LINE setup state reflects credentials and enabled status", () => {
  assert.deepEqual(UiUtils.lineSetupState(false, false).completed, {
    credentials: false,
    webhook: false,
    status: false,
  });
  assert.equal(UiUtils.lineSetupState(false, false).current, "credentials");

  assert.deepEqual(UiUtils.lineSetupState(true, false).completed, {
    credentials: true,
    webhook: true,
    status: false,
  });
  assert.equal(UiUtils.lineSetupState(true, false).current, "status");

  assert.deepEqual(UiUtils.lineSetupState(true, true).completed, {
    credentials: true,
    webhook: true,
    status: true,
  });
  assert.equal(UiUtils.lineSetupState(true, true).current, null);
});
