# 歷史參考：FastAPI／SQLite 至 Java

> 本文件只用於辨識舊版檔案與規劃舊資料搬移，不是現行安裝、測試或發布步驟。
> 新環境請直接依 [README](../README.md) 啟動 Java 服務。現行 Schema 以 Flyway 為準。

## 現況

- `src/line_ai_bot`、`tests/*.py`、`alembic/`、`alembic.ini`、`pyproject.toml` 是舊版程式、測試與工具設定，不參與現行 Java CI 或部署。
- `.venv/`、`.ruff_cache/` 是可能存在的本機舊版環境與快取，不是啟動需求。
- `line_ai_bot.db`、`test_line_ai_bot.db` 是可能存在的舊 SQLite 檔，不由 Java 自動讀取；先核對內容與備份，不能當快取直接刪除。
- `tests/release-policy.test.cjs` 與 `tests/frontend/*.cjs` 是現行 Node／Playwright 測試，仍由 Java 專案驗證流程使用。
- `src/main/java`、`src/test`、`pom.xml` 與 Flyway 是現行服務與測試主線。
- Java 本機預設使用 `line_ai_bot_java.mv.db`。
- Docker Compose 使用 PostgreSQL volume `line-ai-bot-java-postgres`。

兩個資料庫不會自動同步。不要在尚未核對資料前刪除舊 SQLite 檔案。

## 舊資料搬移檢查清單

以下是歷史切換的規劃起點，Repository 沒有可保證完整搬移現行 Schema 的自動化工具。
目前已加入時段占用、加購快照、人員權限、AI 用量及可選預約等結構，不能只照下列舊欄位清單搬移。
搬移前必須逐項對照目前 Flyway，於隔離資料庫驗證外鍵、唯一限制、時段占用與功能模式；
不得在既有 Java 資料庫執行 Alembic，或改寫已套用的 Flyway 遷移。

1. 暫停舊服務的寫入，保留 SQLite 備份。
2. 啟動新的 PostgreSQL，讓 Flyway 建立空 Schema。
3. 依外鍵順序搬移：
   - `tenants`
   - `line_channels`
   - `business_hours`
   - `booking_services`
   - `reservations`
   - `datasets`
   - `knowledge_documents`
   - 對話、工單與歷史事件
4. 不直接搬移舊 `knowledge_chunks`。Java Local Provider 使用不同的雜湊 Embedding 模型名稱；改由每個資料集的 Reindex API 重建。
5. 為有效預約把 `active_slot_key` 設為 `starts_at`；取消預約設為 `NULL`。
6. 為 Active Dataset 設定 `active_marker='ACTIVE'`，其他資料集設為 `NULL`。
7. 比對每個租戶的資料筆數、有效預約與 Active Dataset。
8. 用測試商家驗證 API Key、LINE Channel 解密、Webhook 簽章、查詢與取消。
9. 更新 LINE Webhook 公開 URL，再切換正式流量。

## 相容性

Java 實作保留舊版 PBKDF2 API Key 格式與 Fernet 相容密文格式，因此使用相同 `APP_ENCRYPTION_KEY` 搬移資料後可繼續驗證既有密鑰。搬移前仍應先在副本資料庫做解密與登入 Smoke Test；不要在 Log 中輸出明文 Secret。

## 回復

切換期間保留：

- 舊 SQLite 唯讀備份。
- PostgreSQL 切換前 Snapshot。
- LINE Webhook 原設定。
- 舊版容器映像或 Commit。

只有在新服務尚未接受新寫入、且已在隔離環境驗證舊服務可用時，才可考慮還原原 Webhook URL 與舊服務。
若新服務已接受寫入，必須先對帳並處理新增資料，不能直接切回舊 SQLite，以免遺失預約或客服紀錄。
現行 Java 版本回復依 [操作手冊](operations-runbook.md) 與 [正式發布管理](production-release.md) 執行。
