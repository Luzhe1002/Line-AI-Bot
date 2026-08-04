# 操作與事故處理手冊

## 部署前檢查

1. CI、Migration、Docker image build 與 Compose config 全部成功。
2. Production Secret 已由平台注入，沒有使用 `.env.example` 預設值。
3. 備份成功且最近一次 Restore Drill 在允許期限內。
4. `/health`、資料庫連線、LINE Channel、OpenAI Provider 與公開 Base URL 設定正確。
5. 確認 Worker concurrency、DB pool、限流與成本上限符合本次容量。

## 部署後 Smoke Test

- `GET /health` 回 `200`，版本與預期 Release 一致。
- Portal、OpenAPI（若允許公開）與靜態資源可讀，安全標頭存在。
- 測試租戶能登入、讀取 Overview、建立草稿、索引並預覽回答。
- LINE Console Webhook Verify 成功；測試 event 只產生一次 `line_events`。
- 建立／重送／取消測試預約，確認冪等、時段占用與通知狀態。

## 需要監控的訊號

| 訊號 | 告警方向 |
|---|---|
| HTTP 5xx、401／403／429 | 錯誤率突升、暴力嘗試或限流異常 |
| LINE event queue age、FAILED、attempts | Worker 堵塞或外部服務故障 |
| Booking event／Outbox FAILED | 顧客或店家通知漏送 |
| OpenAI／LINE latency 與 error rate | Provider 降級、Timeout 或配額 |
| DB pool、連線、lock wait、儲存量 | 容量或競爭問題 |
| Active Dataset／index FAILED | 知識版本不可發布或回答品質退化 |

## 事故分流

### LINE Webhook 失敗

先查簽章失敗、Tenant／Channel 狀態與 LINE Console error statistics，再查 Queue age 與
FAILED event。不要關閉簽章驗證。修復後只重放仍可安全處理且具去重鍵的事件。

### OpenAI 故障或成本異常

停止高成本 Reindex／Preview，保留已發布資料集。必要時切換 Local Provider 只作保守
擷取式回答；切換 Embedding Provider／模型／維度後，必須重建索引才能發布。

### 重複預約或時段錯誤

保留 Reservation、Occupancy、Booking Event 與 Activity Log 證據。不要直接刪除資料。
先確認唯一限制、時區、營業時間與 Migration，再由 `BookingManager` 正常取消／重建。

### Secret 外洩

立即限制入口、撤銷外洩憑證、建立新 Secret、驗證服務，再調查 Git／Log／Artifact。
Channel Secret 更新會使舊簽章失效；加密主金鑰目前沒有線上輪替能力，必須依變更計畫
執行雙讀／重加密，不能直接替換環境變數。

## 備份與還原

正式環境至少保存加密備份、設定 RPO／RTO，並在隔離環境定期還原。驗證 Flyway
版本、各租戶筆數、Active reservation／occupancy、Active Dataset、LINE Channel 解密
與抽樣登入。還原期間停止寫入；不要讓舊、新資料庫同時接收預約。

## 回復部署

應保留上一個不可變 Image 與相容資料庫版本。若 Migration 不可向下相容，優先修復前進；
若可以回復，先停止新版本寫入、確認資料相容，再切回 Image。完成後記錄時間線、影響、
根因、偵測缺口與防止復發的測試／告警。
