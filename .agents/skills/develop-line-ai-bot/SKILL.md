---
name: develop-line-ai-bot
description: Develop, diagnose, review, and validate the Java Spring Boot LINE AI platform in this repository, including tenant isolation, bookings, webhooks, RAG, and static web UI. Use for product code, configuration, tests, and related documentation; release operations use line-ai-release.
---

# LINE AI Bot 開發

## 建立上下文

從含 `pom.xml`、`src/main/java` 與 `scripts/verify.ps1` 的根目錄工作。先讀 `AGENTS.md`，核對分支、上游、工作區與需求範圍；保留既有修改，依該文件選擇分支。系統邊界不清楚時讀 `README.md` 與 `docs/architecture.md`。

現行主線為 Java 21／Spring Boot、Spring JDBC 與 Flyway。舊 Python、SQLAlchemy、Alembic 不作為新功能實作或驗收入口。不要在一般功能任務搬移或刪除舊資料。

## 依任務定位

以下 Java 路徑相對於 `src/main/java/com/lineaibot`；測試從 `src/test/java/com/lineaibot` 尋找。只讀相關檔案，類別改名時以 `rg` 搜尋為準。

| 任務 | 先檢查 |
|---|---|
| API、商家與授權 | 對應 Controller／DTO／Service／Repository；`tenant`、`portal`、`shared`；`docs/api-examples.http` |
| 預約、加購、取消、封鎖 | `booking/BookingManager.java`、Writer 與 Repository；`ApplicationIntegrationTest`、`MerchantBookingManagementIntegrationTest` |
| LINE、事件與通知 | `line/LineWebhookController.java`、`LineEventWorker.java`、`LineEventProcessor.java`、`LineRepository.java`；`merchant` 與 LINE 測試 |
| 對話與意圖 | `line` 的 Conversation 類別、`knowledge/OpenAiProvider.java`；`docs/conversation-context.md` 與 Conversation 測試 |
| RAG、Provider、用量 | `knowledge` 的 Service／Indexer／Repository／Provider／`AiUsageService` 與相關測試 |
| Schema | `src/main/resources/db/migration`、`src/main/java/db/migration`、Repository SQL 與 Migration 測試；先確認實際檔案 |
| 網頁與 Session | `src/main/resources/static`、`portal`、booking／merchant Controller；`tests/frontend`、`playwright.config.cjs` |
| 設定、容器與 CI | `config`、`application.yml`、`.env.example`、`compose.yaml`、`Dockerfile`、`.github/workflows/ci.yml` |

發布、分支管理與部署另使用專案內 `line-ai-release` skill。

## 保持實作邊界

- Controller 處理 HTTP 與輸入驗證，商業規則留在 Service，SQL 留在 Repository。沿用 Spring JDBC，不為單一修改引入另一套 ORM。
- 依 `AGENTS.md` 保護租戶範圍、伺服器授權、預約交易與時段限制、Webhook 驗章與事件管線；修改時驗證相應失敗、競爭或重送情境。
- Schema 變更新增 Flyway migration；已發布版本不得改寫、刪除或用 repair 隱藏差異。核對 H2／PostgreSQL 方言與交易差異。
- 外部呼叫保留逾時、有限重試與去重／冪等；避免在交易內無意新增長時間網路等待。
- 契約或設定變更同步 DTO、網頁呼叫、環境範例、API 範例與相關文件；保留真實憑證於執行環境，不輸出或提交。

## AI 與提示詞變更

- 分清客服回答與對話理解指令；結構化輸出仍由應用驗證，模型意圖不等於交易授權。
- 保留租戶／資料集／向量模型與維度限制、後端來源引用、資料不足時追問或轉人工，以及 Provider 呼叫前的用量租約與停用開關。
- 依變更涵蓋：資料不足不補價格、跨輪指涉與換題、檢索／歷史提示注入、政策詢問不誤判取消、「好」不代表操作授權、停用預約不引導新建、不得虛稱交易完成。
- 行為修正加入可觀察結果的回歸案例；只斷言 prompt 包含某句話不能證明模型遵守。Fake Provider 驗證請求／解析，Local Provider 驗證離線流程，真實模型評測另行記錄。
- 修改 Provider／模型／維度／切塊時核對重建索引與資料相容性；涉及 OpenAI API 或模型行為時查官方文件，環境有 `openai-docs` 時使用該 skill。
- 真實模型評測只在任務授權與成本範圍內使用去識別資料；一般 CI 不依賴真實 API Key。評測未建立時回報缺口，不宣稱已驗證準確率。
- 比較 prompt／模型時記錄 Git commit、模型與設定、案例與判定標準、結果、tokens 與延遲；不把未量測成本或品質當成果。

## 依變更選擇驗證

| 變更 | 適當檢查 |
|---|---|
| 文件／skill | 相對連結、路徑與指令一致性、`git diff --check`；有可用 skill 驗證器時檢查格式 |
| Java 行為 | 先跑 `mvn -Dtest=測試類別 test`；交付整合變更時跑 `mvn clean verify` |
| 前端 | `npm run test:ui:logic`；影響畫面、Session 或操作流程時加 `npm run test:ui:e2e` |
| Migration | 空庫與既有資料升級、外鍵／唯一限制與相關回歸；併發／方言行為需實際 PostgreSQL 驗證 |
| 發布規則腳本 | `node --test tests/release-policy.test.cjs` |
| 容器／Compose | `docker compose config --quiet`、`docker build --target test .`；打包變更加建 `package` stage |

完整驗證使用 `./scripts/verify.ps1`：Java、發布規則、前端邏輯、Playwright 與 Compose。無 Maven 時改用 Docker test stage；先依 README 安裝 npm 依賴與瀏覽器。

僅在已知環境限制時使用 `-SkipDockerConfig` 或 `-SkipUiE2E`，回報省略項目。舊 skill 包裝腳本的 `-SkipCompose` 會轉接 `-SkipDockerConfig`。CI 另檢查發布政策與 package 容器建置；局部測試不能代替 CI，H2 通過不等同 PostgreSQL 或容量已驗證。

## 完成與回報

說明修改目的、影響、實際檢查與未驗證部分；區分離線、Fake Provider 與真實外部呼叫。Git 操作依 `AGENTS.md` 回報分支、提交與 PR；建立 PR、合併與部署分別依既有使用者授權處理。純指引修改不必執行外部 API 或整套產品測試。
