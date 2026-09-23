const fs = require("node:fs");
const { execFileSync } = require("node:child_process");

function checkReleaseSource(event) {
  const pr = event.pull_request;
  if (!pr || pr.base.ref !== "production") return;
  if (pr.head.ref !== "main" || pr.head.repo?.full_name !== pr.base.repo?.full_name) {
    throw new Error("Production releases must come from main in the same repository.");
  }
}

function checkMigrations(base, head, git) {
  for (const sha of [base, head]) {
    if (!/^[a-f0-9]{40}$/.test(sha)) throw new Error("Expected a full commit SHA.");
  }
  const directory = "src/main/resources/db/migration";
  const paths = git(["ls-tree", "-r", "--name-only", base, directory]).trim().split("\n").filter(Boolean);
  for (const path of paths) {
    const oldBlob = git(["rev-parse", `${base}:${path}`]).trim();
    let newBlob;
    try { newBlob = git(["rev-parse", `${head}:${path}`]).trim(); }
    catch { throw new Error(`Existing migration removed: ${path}`); }
    if (oldBlob !== newBlob) throw new Error(`Existing migration changed: ${path}`);
  }
}

if (require.main === module) {
  const event = JSON.parse(fs.readFileSync(process.env.GITHUB_EVENT_PATH, "utf8"));
  checkReleaseSource(event);
  if (event.pull_request) {
    checkMigrations(event.pull_request.base.sha, event.pull_request.head.sha,
      (args) => execFileSync("git", args, { encoding: "utf8", stdio: ["ignore", "pipe", "pipe"] }));
  }
  console.log("Release source and existing migrations verified.");
}
module.exports = { checkReleaseSource, checkMigrations };
