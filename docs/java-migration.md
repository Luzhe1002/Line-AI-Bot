# FastAPI／SQLite 至 Spring Boot 遷移

## 現況

- `src/line_ai_bot`、`tests`、`alembic` 與 `line_ai_bot.db` 是舊 FastAPI 參考實作。
- `src/main/java`、`pom.xml` 與 Flyway 是新的主要服務。
- Java 本機預設使用 `line_ai_bot_java.mv.db`。
- Docker Compose 使用 PostgreSQL volume `line-ai-bot-java-postgres`。

兩個資料庫不會自動同步。不要在尚未核對資料前刪除舊 SQLite 檔案。

## 建議切換流程

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

若新服務驗證失敗，停止新服務寫入、還原原 Webhook URL，並以舊資料庫重新啟動舊服務。兩邊同時接受寫入會造成預約分歧，應避免雙寫。
