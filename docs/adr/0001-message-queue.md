# ADR-0001：LINE 事件暫不導入外部 Message Queue

- 狀態：Accepted
- 日期：2026-07-24

## 背景

LINE Webhook 必須快速回應，但事件後續可能執行知識檢索、外部 AI、預約或 LINE Reply API。程序重啟、事件重送與慢速外部服務都不能造成事件遺失或重複預約。

目前產品是一個 Spring Boot 部署單元，預期初期流量有限，資料與交易都在 PostgreSQL。團隊尚不需要讓多個獨立服務訂閱同一事件。

## 決策

第一階段不部署 RabbitMQ、Kafka、Redis Queue 或 SQS。使用 PostgreSQL 作為 Durable Work Queue：

- Webhook 交易只驗證、去重並寫入 `line_events`。
- Worker 使用條件式 Update Claim 事件。
- Java 21 Virtual Thread Executor 提供最多 8 個並行工作。
- 失敗採指數退避，最多三次；Crash 後復原過期 Claim。
- 所有狀態可從資料庫查詢及稽核。
- 預約用獨立的冪等鍵與唯一時段限制保證業務一致性。

LINE 回覆與知識索引屬於不同延遲等級。現階段知識文件仍由管理 API 同步索引，不會進入 LINE 事件 Queue，因此不會把 Reply Token 排在大型匯入工作後面。

## 原因

外部 Broker 能提供很好用的 Consumer、Retry 與 DLQ，但也增加部署、權限、監控、容量、備份與故障處理成本。對單一部署單元而言，事件先寫資料庫再發送 Broker 還需要 Transactional Outbox，否則會有「資料已 Commit、訊息未發布」的雙寫缺口。現階段直接以同一資料庫排程可減少移動元件，並已解決程序內 Background Task 會遺失的主要風險。

## 代價

- Worker Claim 會使用 PostgreSQL 連線與寫入 I/O。
- 重試、Metrics、管理介面與 Dead-letter 操作需要由應用自行維護。
- 大量 Consumer 或跨服務訂閱時不如 Broker 自然。
- Outbox 現在主要是傳送稽核，不是獨立 Relay；LINE API 暫時在 Event Worker 中呼叫。

## 重新評估門檻

發生以下任一情形便重新評估：

1. API 與 Worker 需要獨立部署、版本或 Auto Scaling。
2. LINE Webhook 持續超過約 100 events/s。
3. `P95 event queue delay` 超過 2 秒，且原因是 Worker／DB 容量而非外部模型。
4. PostgreSQL Claim 造成鎖競爭、連線池飽和或顯著 I/O 壓力。
5. 文件解析／Embedding 等長任務需要非同步化。
6. 需要跨服務 Fan-out、Broker DLQ、延遲訊息或跨區域容錯。

數字是初始操作門檻，不是容量承諾；上線後應以 Metrics 與壓測結果校正。

## 導入路線

若達門檻：

1. 保留 `line_events` 與預約冪等限制。
2. 在接收事件的同一交易寫入 Transactional Outbox。
3. 由 Outbox Relay 發布至 RabbitMQ 或雲端受管 Queue。
4. LINE 即時事件與知識索引使用不同 Queue、Consumer Pool 與 DLQ。
5. Consumer 仍視為至少一次投遞，不能移除 `webhookEventId` 與預約冪等鍵。
6. 先量測 Queue Delay，確保 LINE 即時回覆不被長工作阻塞。

若部署環境已深度使用 AWS，可優先評估 SQS；若需要細緻路由、低延遲與自管環境，可評估 RabbitMQ；Kafka 只在事件串流、長期保留及多 Consumer Replay 成為核心需求時考慮。
