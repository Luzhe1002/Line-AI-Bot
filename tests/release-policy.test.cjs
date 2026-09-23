const { test } = require("node:test");
const assert = require("node:assert/strict");
const { execFileSync } = require("node:child_process");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { checkReleaseSource, checkMigrations } = require("../scripts/check-release-policy.cjs");
const event = (ref, repo = "owner/app") => ({ pull_request: {
  head: { ref, repo: { full_name: repo } }, base: { ref: "production", repo: { full_name: "owner/app" } }
} });
test("production accepts main but rejects feature and fork releases", () => {
  assert.doesNotThrow(() => checkReleaseSource(event("main")));
  assert.throws(() => checkReleaseSource(event("feature")));
  assert.throws(() => checkReleaseSource(event("main", "fork/app")));
  assert.doesNotThrow(() => checkReleaseSource({}));
});
test("new migrations allowed; existing migrations cannot change or disappear", () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), "release-policy-"));
  const git = (args) => execFileSync("git", args, { cwd: root, encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] });
  const commit = () => { git(["add", "."]); git(["-c", "user.name=Test", "-c", "user.email=test@example.com", "commit", "-m", "fixture"]); return git(["rev-parse", "HEAD"]).trim(); };
  try {
    git(["init"]);
    const dir = path.join(root, "src/main/resources/db/migration"); fs.mkdirSync(dir, { recursive: true });
    const first = path.join(dir, "V1__initial.sql"); fs.writeFileSync(first, "select 1;\n");
    const base = commit();
    fs.writeFileSync(path.join(dir, "V2__new.sql"), "select 2;\n");
    assert.doesNotThrow(() => checkMigrations(base, commit(), git));
    fs.writeFileSync(first, "select 3;\n");
    assert.throws(() => checkMigrations(base, commit(), git), /changed/);
    fs.unlinkSync(first);
    assert.throws(() => checkMigrations(base, commit(), git), /removed/);
  } finally {
    if (path.dirname(path.resolve(root)) !== path.resolve(os.tmpdir()) || !path.basename(root).startsWith("release-policy-")) {
      throw new Error("Refusing cleanup outside the test temporary directory.");
    }
    fs.rmSync(root, { recursive: true, force: true });
  }
});
