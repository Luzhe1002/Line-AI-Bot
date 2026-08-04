# 履歷與面試用專案說明

## 一句話版本

以 Java 21／Spring Boot 建置多租戶 LINE AI 客服與一對一預約平台，整合可靠
Webhook 處理、具來源引用的 RAG、角色權限、資料庫一致性與商家行動管理流程。

## 履歷專案描述

**LINE AI 智慧客服與預約管理平台｜Java 21、Spring Boot、PostgreSQL、OpenAI、Docker**

- 設計多租戶模組化單體，將 Tenant、Booking、Knowledge、LINE 與 Merchant
  拆成明確服務邊界；所有外部可指定資源以 `tenant_id` 限定並覆蓋跨租戶測試。
- 建立 Durable Webhook Pipeline：原始 body HMAC 驗章、`webhookEventId` 去重、
  PostgreSQL Queue、Virtual Thread Worker、條件式 Claim、重試與 Outbox 稽核。
- 以冪等鍵、交易與資料庫唯一時段占用處理預約競爭，讓顧客預約與店家封鎖共用
  同一一致性規則，避免「先查可用再寫入」的 race condition。
- 實作可替換 Local／OpenAI RAG Provider、資料集草稿與發布、Embedding 相容檢查、
  後端引用及低信心人工轉接；AI 不具預約資料寫入權限。
- 建立 HttpOnly Session、CSRF、短效單次 Token、API Key 雜湊、敏感憑證加密與
  HTTP Security Headers，並以 JUnit、Node Test、Playwright、Docker 與 CI 驗證。

## 可以在面試深入說明的設計

### 為什麼先用模組化單體

產品初期只有一個部署單元與一個 PostgreSQL。模組化單體保留清楚的 Domain 邊界，
同時避免微服務帶來的分散式交易、服務發現與獨立維運成本。當 API／Worker 需要獨立
擴縮、Queue delay 或資料庫 Claim 成為瓶頸時，再依 ADR 導入外部 Broker。

### 如何保證同一時段只有一人

前端 availability 只改善體驗，不是正確性來源。真正的保證位於資料庫
`booking_slot_occupancies (tenant_id, starts_at)` 唯一限制；預約與封鎖都在交易中競爭
同一筆時段，衝突轉成 `409`。冪等鍵另外處理相同請求重送。

### 如何避免 AI 亂操作

模型只接收問題與已檢索文字，沒有資料庫或 Booking tool。預約／取消由確定性的
Intent 與 `BookingManager` 執行；RAG 只讀 Active Dataset，引用由後端建立，資料不足
就回退人工客服。

### 如何處理 Webhook 可靠性

HTTP request 只做驗章、去重與落庫，Worker 再非同步處理。Claim 是條件式 Update，
失敗有退避與次數上限，過期 Processing 可復原。LINE 重送相同 event 時由唯一鍵去重。

## 誠實的專案邊界

- 目前 Render 是展示部署，不宣稱 Production SLA。
- 尚未完成外部限流、PITR／Restore Drill、完整 Metrics／Tracing、PII 自動清除與
  key-version rotation；這些已列入 [稽核報告](project-review.md)。
- Local AI 是可重現的離線測試替身，不等同正式語意模型。
- 不要在履歷填寫未量測的 QPS、P95、節省成本或準確率；可改寫成「設計門檻」或
  「待壓測驗證」。

## 展示順序

1. 從 README 架構圖說明角色與信任邊界。
2. 展示商家 Portal 的 LINE 設定、知識草稿／發布、人員角色。
3. 從 LINE 入口展示顧客預約與店家行動管理。
4. 打開測試與 CI，說明租戶隔離、競爭條件、Webhook 驗章和失敗重試。
5. 主動說明稽核報告中的未完成項目與正式化路線，展現工程判斷而非過度包裝。
