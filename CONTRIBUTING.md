# Contributing

## Source of truth

現行產品主線是 `src/main/java`、`src/main/resources/db/migration`、靜態前端與 JUnit／
Playwright 測試。不要在未明確執行遷移工作的情況下，同時修改舊 FastAPI 參考實作。

## Local workflow

1. 從小型、可 review 的 branch 開始，先為預期行為新增或調整測試。
2. 保持 Controller 薄、商業規則在 Service、SQL 在 Repository。
3. Schema 變更只能新增 Flyway migration；已發布 migration 不得回寫。
4. 執行 `.\scripts\verify.ps1`。只有在已知無 Docker 或 Chrome 的受限環境才使用
   `-SkipDockerConfig` 或 `-SkipUiE2E`，並在 PR 說明未執行的檢查。
5. 更新 README、架構、環境變數、API 範例與 Runbook 後再送 Review。

## Definition of done

- 功能與錯誤路徑有測試，CI 全綠且 `git diff --check` 無錯。
- 所有 Merchant-owned 資源都能從程式碼直接看出 Tenant scope。
- 外部呼叫具 Timeout、有限重試、冪等或去重策略，不記錄 Secret。
- Migration、部署設定、操作方式與回復方法一致。
- 新風險已記錄於 ADR、稽核報告或 PR，不以「之後再說」隱藏。

## Commit and review

Commit message 使用動詞描述單一目的。Review 優先檢查租戶隔離、授權、交易邊界、
資料庫唯一限制、Webhook raw body、AI 工具權限、個資與部署預設值，再檢查風格。
