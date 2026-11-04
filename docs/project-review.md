# 專案工程檢視與已知限制

## 結論

本專案已具備可展示的完整產品主線：Java 21／Spring Boot 模組化單體、明確的
多租戶資料邊界、可靠 LINE Webhook 管線、具資料庫一致性的預約流程、可離線測試的
RAG Provider，以及商家與顧客前端。它適合作為履歷專案，但目前定位應是
「完成安全基線與自動化測試的展示／MVP 系統」，不應宣稱已通過正式資安認證、
高可用 SLA 或法規遵循稽核。

初次檢視：2026-08-04；文件與目前工作區實作對照更新：2026-11-04。
主要實作以 `src/main/java`、Flyway 與 `render.yaml` 為準。
舊版檔案分類見 [歷史遷移參考](java-migration.md)，不屬於現行交付與驗收流程。
本次更新為程式、設定與文件核對，未重跑測試、檢查正式部署或進行外部資安稽核。

## 評估摘要

| 面向 | 判斷 | 證據與限制 |
|---|---|---|
| 架構 | 已建立 | Java 模組化單體、ADR 記錄 Queue 取捨；舊版檔案仍留在倉庫，已在文件標示用途 |
| 正確性 | 良好 | 預約冪等、唯一時段占用、交易式事件、Webhook 去重；已補上停用服務不可預約 |
| 多租戶 | 良好 | 使用者可指定的資源查詢均帶 `tenant_id`，並有跨租戶知識庫測試 |
| 應用資安 | 基線完成、仍有缺口 | 密鑰雜湊／加密、Session／CSRF、短效 Token、Webhook 驗章、安全標頭、AI 配額；仍缺一般入口限流與金鑰輪替 |
| AI 安全 | 良好 | Active Dataset 與模型維度限定、檢索內容視為不可信、後端引用、AI 無預約寫入權限 |
| 測試 | 良好 | Java 整合／單元測試、Node 邏輯測試、Playwright E2E；PostgreSQL 整合與壓測待補 |
| SDLC | 已建立基線 | 共用驗證腳本、GitHub Actions、Dependabot、Flyway、正式發布規則；尚缺正式 SLO 與事故演練證據 |
| 營運 | 展示環境等級 | Docker／Render／Health endpoint 可用；備份還原、監控告警與容量驗證未完成 |

## 已確認的良好設計

1. Webhook 使用未修改的原始 request body 做 HMAC-SHA256 驗證，驗證後才解析
   JSON；事件以 `tenant_id + webhookEventId` 去重並非同步處理，符合 LINE 官方建議。
2. `BookingManager` 是預約建立與取消的唯一應用服務；冪等鍵、有效時段驗證與
   `booking_slot_occupancies` 唯一限制共同處理競爭條件。
3. Tenant API Key 以 PBKDF2-HMAC-SHA256 保存；LINE Channel 憑證與店家 LINE ID
   加密保存；短效管理 Token 在資料庫只留 HMAC，且單次消耗。
4. Portal 使用 HttpOnly／Secure／SameSite Cookie、Session ID rotation 與同步 Token
   型 CSRF；前端不把 Tenant API Key 寫入 Web Storage。
5. RAG 只檢索租戶的 Active Dataset，且要求 Embedding model 與 dimensions 相符；
   提示明確把檢索內容當不可信資料，引用由後端產生。
6. OpenAI Responses request 使用 `store=false` 與隱私保護的穩定
   `safety_identifier`；模型名稱以應用設定為準，可用性、成本與回答品質應以實際帳號、
   用量紀錄及 Eval 驗證，不由預設型號推定。
7. `AiUsageService` 以資料庫交易與全域控制列鎖管理用量租約，涵蓋使用者速率、
   租戶日請求與 token 額度、全域日 token 額度、租戶並行數及全域停用開關。
   `ai_usage_events` 保存成功、失敗與拒絕原因；失敗／逾時按預留 tokens 保守計入額度。

## 初次檢視時的修正紀錄（2026-08-04）

以下保留當時的修正摘要，不代表本次文件更新重新執行或驗證了這些變更。

| 編號 | 原問題 | 修正 |
|---|---|---|
| R-01 | 正式站缺少 CSP、HSTS、Frame、`nosniff` 與 Referrer 等標頭 | 新增全站 Security Header Filter，Production 啟用 HSTS，敏感 API 設 `no-store` |
| R-02 | 停用服務可由舊連結或直接 API 建立預約 | `BookingManager` 在寫入前驗證服務為 active，並新增整合測試 |
| R-03 | 專案 skill 驗證腳本仍執行舊 Python 主線 | 統一委派至 Java／前端驗證腳本；無 Maven 時自動使用 Docker test stage |
| R-04 | 沒有持續整合與依賴更新機制 | 新增 GitHub Actions；Dependabot 分組 Maven／npm／Actions 的 minor／patch，Java 21 image major 由人工審查 |
| R-05 | Render 文件與實際 OpenAI／LINE 設定不一致 | 修正文檔並明確區分展示部署與正式營運 |
| R-06 | Render 以靜態頁作 Health Check，且公開 Swagger／OpenAPI | 改用 `/health`，Production 由環境變數關閉 API 文件 |

修正是否已在 Render 生效，須依實際部署 commit 與回應核對，不能從倉庫設定推定。

## 尚未關閉的風險

### 上正式營運前必須處理

- **入口限流與濫用防護：** AI 問答與索引已有應用層用量／並行配額及拒絕紀錄，
  不應再描述成完全沒有成本控制。登入、Onboarding、Webhook 等一般 HTTP 入口仍需
  防暴力嘗試、來源限流與邊界防護；另須驗證 AI 配額在實際 PostgreSQL 併發下的行為。
  AI 配額目前以流程固定訊息回覆，不代表所有超限端點已回傳 HTTP `429`。
- **個資生命週期：** Reservation、Conversation、LINE Event payload 與 Outbox 仍保存
  LINE User ID、訊息或姓名；需要資料分類、保存期限、刪除／匯出流程與排程清除。
- **備份與還原：** 展示資料庫沒有倉庫內可驗證的備份、PITR 與 Restore Drill 證據。
- **密鑰輪替：** 加密資料沒有 key version；輪替 `APP_ENCRYPTION_KEY` 需要雙讀、重加密
  與回復流程。Platform Admin 也應改為具稽核記錄的身分系統，而非單一長效共享密鑰。
- **監控告警：** 需要 Queue delay、重試／FAILED、Webhook 4xx/5xx、OpenAI／LINE
  latency、DB pool、預約衝突率與可用性告警。

### 下一階段工程改進

- 用 Testcontainers 對 PostgreSQL 執行 Flyway 與併發整合測試，降低 H2 方言差異。
- 建立預約、Webhook、RAG 的容量與故障注入測試，依量測修正 Queue 門檻。
- 將向量 JSON 應用層掃描升級為 pgvector index；目前只適合 MVP 資料量。
- 對 Docker base image 採 digest pinning／SBOM／映像掃描，Release 使用不可變版本。
- 舊版檔案目前僅在文件明確標示；若後續搬移或移除，須同步核對現行 Node／Playwright
  共用的 `tests` 路徑、工具設定與資料備份，不能整個刪除 `tests` 或 SQLite 檔。

## 標準對照

本報告以標準作為檢查框架，不代表取得認證：

| 參考 | 已有控制 | 主要缺口 |
|---|---|---|
| [OWASP ASVS 5.0](https://owasp.org/www-project-application-security-verification-standard/) | 驗證、存取控制、輸入限制、密碼學、Session／CSRF、錯誤格式 | 限流、完整安全日誌、金鑰生命週期、正式驗證清單 |
| [OWASP API Security Top 10](https://owasp.org/API-Security/) | Tenant scope、Body 大小限制、參數化 SQL、AI 用量配額 | 一般入口資源消耗防護與管理 API 暴露 |
| [NIST SSDF SP 800-218](https://csrc.nist.gov/pubs/sp/800/218/final) | 版本控制、Reviewable migrations、自動測試、依賴更新 | Threat model 維護、Release provenance、弱點回應與演練證據 |
| [OWASP HTTP Headers](https://cheatsheetseries.owasp.org/cheatsheets/HTTP_Headers_Cheat_Sheet.html) | CSP、HSTS、Frame、Referrer、Permissions、`nosniff` | 部署後需以外部掃描再次驗證 |
| [LINE Webhook 指南](https://developers.line.biz/en/docs/messaging-api/verify-webhook-signature/) | Raw body 驗章、去重、非同步處理 | 應補 Webhook redelivery 與故障告警操作紀錄 |
| [OpenAI Model Guidance](https://developers.openai.com/api/docs/guides/latest-model) | Responses API、`store=false`、`safety_identifier`、verbosity | 模型／門檻 Eval、成本上限、供應商故障降級測試 |

## 驗證與限制

初次檢視文件記錄的檢查：Docker Java test／package stage、Node 前端邏輯測試、Playwright E2E、
`npm audit`、`docker compose config --quiet`、Git diff whitespace check，以及 Render HTTPS
回應與標頭探測。
這些歷史紀錄不代表目前分支或正式部署已通過同一套驗證；請另查該 commit 的 CI 結果。
本次文件對照亦未使用真實 LINE Channel 或 OpenAI 金鑰發送外部請求；未執行滲透測試、SAST／DAST、
SBOM／CVE 掃描、負載測試、PostgreSQL Restore Drill 或法遵顧問審查。

## Release Gate 建議

每次合併至少要求：CI 全綠、Migration 可前進且有回復策略、Tenant scope review、
安全／個資影響說明、文件與環境變數同步、無 Secret 進入 Git。正式發布再加上
PostgreSQL 測試、依賴與映像掃描、備份成功、Smoke Test 與可觀察性 Dashboard 正常。
