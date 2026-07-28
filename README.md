# LINE AI 客服機器人

多商家 LINE 客服、知識庫與一對一時段預約後端。主要執行架構已改為 Java 21、Spring Boot 4、Spring JDBC、Flyway 與 PostgreSQL；原本的 FastAPI 原始碼暫時保留，供資料遷移與契約比對，不再由 Docker Compose 啟動。

## 已完成

- 多租戶資料隔離，每個商家有獨立管理 API Key。
- Platform Admin 與 Tenant Admin 兩層 API 驗證。
- LINE Channel Secret 與 Access Token 加密保存。
- 使用未修改的 HTTP Body 驗證 LINE HMAC-SHA256 簽章。
- 以 `tenant_id + webhookEventId` 去重。
- Webhook 先持久化至 `line_events`，再由有界並行的 Java 21 Virtual Thread Worker 處理。
- 每個商家同一時段只能有一筆有效預約，支援冪等建立與取消後釋放時段。
- LINE 文字意圖、預約／取消 Postback、Quick Reply、人工客服工單。
- 商家知識庫草稿、索引、重新索引、發布、租戶限定檢索及引用。
- 本機離線 AI Provider；可選 OpenAI Embeddings 與 Responses API。
- LINE Outbox 稽核；開發模式可模擬傳送，不呼叫 LINE。
- H2 本機開發、PostgreSQL Docker 環境，以及 Flyway Schema 管理。

詳細設計請見 [系統架構](docs/architecture.md)，MQ 決策請見 [ADR-0001](docs/adr/0001-message-queue.md)，API 操作範例位於 [api-examples.http](docs/api-examples.http)。

## 最快啟動方式：Docker

需求：Docker Desktop。

若尚未建立 `.env`：

```powershell
Copy-Item .env.example .env
```

保留現有 `.env` 時不要再次覆蓋。接著啟動：

```powershell
docker compose up --build
```

Compose 會啟動：

- Spring Boot API：<http://localhost:8000>
- Swagger UI：<http://localhost:8000/docs>
- OpenAPI JSON：<http://localhost:8000/openapi.json>
- PostgreSQL／pgvector：`localhost:5432`

Flyway 會在 Spring Boot 啟動時自動執行。

## 不用 Docker 的本機啟動

需求：JDK 21 與 Maven 3.9 以上。預設會使用專案目錄中的 H2 檔案資料庫。

```powershell
mvn spring-boot:run
```

也可先打包：

```powershell
mvn clean verify
java -jar target/line-ai-bot-0.2.0-SNAPSHOT.jar
```

目前電腦若只有 Java 8，請使用 Docker，或先安裝 JDK 21；Spring Boot 4 不能在 Java 8 上執行。

## API 驗證

建立商家時要傳 Platform Admin Key：

```http
POST /api/v1/tenants
X-Platform-Admin-Key: <APP_PLATFORM_ADMIN_API_KEY>
```

建立成功後，回應中的 `admin_api_key` 只顯示一次。之後操作該商家時傳：

```http
X-Tenant-Api-Key: <admin_api_key>
```

若 Swagger 顯示：

```json
{"detail": "Invalid platform admin API key"}
```

代表 `X-Platform-Admin-Key` 沒有填入，或內容與目前 Java 程序讀到的 `.env` 不一致。修改 `.env` 後必須重新啟動容器或 Java 程序。

## 商家管理工作台

啟動服務後開啟：

```text
http://localhost:8000/portal/
```

工作台提供：

- 以 Tenant ID 與只顯示一次的商家管理 API Key 登入。
- 使用 Platform Admin Key 建立商家並直接進入首次設定流程。
- 顯示 LINE、營業設定、知識文件與發布狀態的完成度。
- 貼上知識內容，或上傳 UTF-8 TXT、Markdown、CSV（最多 100,000 字）。
- 查看文件索引狀態、重新索引、測試 AI 回答與引用。
- 發布資料集及設定 LINE Channel。

API Key 只在登入交換時送到後端。登入後使用 HttpOnly Session Cookie，
寫入操作另要求 Session 專用 CSRF Token；工作台不會把 API Key 寫入
`localStorage` 或 `sessionStorage`。

正式 HTTPS 環境必須設定：

```dotenv
APP_SESSION_COOKIE_SECURE=true
```

歷史客服對話不應直接索引。後續匯入流程會先做個資遮蔽、候選知識萃取與
商家人工核准，再把核准內容加入草稿資料集。

## LINE 測試模式

預設：

```dotenv
APP_LINE_API_ENABLED=false
```

Webhook 的簽章、事件去重、對話、預約與 Outbox 都會執行，但回覆只會在 `outbox_messages` 標記為 `SIMULATED`，不會真的呼叫 LINE。確認流程與 Channel 設定後，再改成：

```dotenv
APP_LINE_API_ENABLED=true
```

公開測試時，`APP_PUBLIC_BASE_URL` 必須是 LINE 可連線的 HTTPS 網址，並將商家的 webhook 設定成：

```text
{APP_PUBLIC_BASE_URL}/webhooks/line/{tenantSlug}
```

## AI 與 RAG

預設不需要 API Key：

```dotenv
APP_AI_PROVIDER=local
```

本機模式用可重現的雜湊向量及擷取式回答，適合測試索引、租戶隔離、引用與 LINE 流程，不等同正式語意模型。

啟用 OpenAI：

```dotenv
APP_AI_PROVIDER=openai
OPENAI_API_KEY=sk-...
APP_AI_GENERATION_MODEL=gpt-5.6-luna
APP_AI_EMBEDDING_MODEL=text-embedding-3-small
APP_AI_EMBEDDING_DIMENSIONS=512
```

OpenAI 請求使用 `store=false`，且傳送的 LINE User ID 會先轉成穩定 HMAC 識別碼。切換 Provider、Embedding 模型或維度後，既有資料集必須呼叫：

```http
POST /api/v1/tenants/{tenantId}/datasets/{datasetId}/reindex
```

## Message Queue 結論

目前不需要另外部署 RabbitMQ、Kafka 或 SQS。現階段是一個可部署單體，LINE 事件先寫入 PostgreSQL，再由具重試、失敗狀態、過期 Claim 復原及最多 8 個並行工作的 Worker 處理。這已移除「程序重啟就遺失工作」的問題，也避免多維護一個 Broker。

以下任一條件成立時再導入外部 MQ：

- API 與 Worker 必須獨立部署或獨立擴縮。
- Webhook 持續流量超過約 100 events/s，或 `P95 queue delay` 超過 2 秒。
- 知識匯入等長任務會堵住 LINE 回覆快速通道。
- 需要 Broker 層的 Dead-letter Queue、跨服務事件訂閱或區域級容錯。
- PostgreSQL 的事件 Claim 造成可觀察的鎖競爭或連線池壓力。

導入時應保留資料庫 Transactional Outbox，並將 LINE 即時回覆與文件索引拆成不同 Queue；不要讓 Reply Token 排在長時間索引工作之後。

## 測試

本機有 JDK 21／Maven 時：

```powershell
mvn clean verify
```

Docker：

```powershell
docker build --target test .
docker compose config --quiet
```

測試覆蓋 Platform／Tenant 權限、多租戶隔離、預約冪等與時段競爭、知識庫隔離、LINE 原始 Body 簽章、事件去重及模擬 Outbox。

原 FastAPI 驗證仍可另外執行，但只代表舊版參考實作：

```powershell
.\.venv\Scripts\python -m pytest
```

## 舊資料遷移

現有 `line_ai_bot.db` 是舊 FastAPI／SQLite 資料，不會被 Java 服務自動讀取。新服務預設建立 `line_ai_bot_java.mv.db`，Docker 則使用 PostgreSQL。正式切換前請依 [Java 遷移說明](docs/java-migration.md) 搬移資料、驗證筆數，並重新建立知識向量。

## 主要 API

| 路徑 | 用途 |
|---|---|
| `POST /api/v1/tenants` | 建立商家並取得只顯示一次的管理 API Key |
| `PUT /api/v1/tenants/{id}/line-channel` | 設定 LINE Channel |
| `PUT /api/v1/tenants/{id}/business-hours` | 設定每週營業時間 |
| `GET /api/v1/tenants/{id}/availability` | 查詢指定日期可預約時段 |
| `POST /api/v1/tenants/{id}/reservations` | 建立預約 |
| `POST /api/v1/tenants/{id}/reservations/{id}/cancel` | 取消預約 |
| `POST /api/v1/tenants/{id}/datasets/{id}/documents` | 新增並索引客服資料 |
| `POST /api/v1/tenants/{id}/datasets/{id}/reindex` | 重建資料集向量 |
| `POST /api/v1/tenants/{id}/datasets/{id}/publish` | 發布資料集 |
| `POST /api/v1/tenants/{id}/ai/answer` | 測試知識庫回答 |
| `POST /webhooks/line/{tenantSlug}` | LINE Webhook |
