# Security Policy

## Scope

目前維護的安全主線是 Java 21／Spring Boot 實作、Flyway migrations、三個靜態前端與
Docker／Render 設定。Python／FastAPI 程式只供遷移參考，不接受新功能修補。

## Reporting a vulnerability

請不要在公開 Issue 貼出 Secret、顧客資料、可利用 Payload 或完整攻擊步驟。使用
GitHub Private Vulnerability Reporting；若該功能未開啟，先以 Repository Owner 的
私人聯絡方式提供摘要、受影響版本、重現條件與影響。收到後應先確認、分級、建立
修補與撤銷／輪替計畫，再公開修補資訊。

## Required production baseline

- `APP_ENVIRONMENT=production`，所有管理與加密金鑰由受管 Secret Store 注入。
- `APP_SESSION_COOKIE_SECURE=true`，只經 HTTPS 對外；不得移除 HSTS 與安全標頭。
- PostgreSQL 不公開對 Internet，使用 TLS、最小權限帳號、備份與定期還原演練。
- LINE Channel Secret／Access Token 與 OpenAI Key 不得出現在 Git、Log、測試或文件。
- Swagger／OpenAPI、Platform Onboarding 與管理 API 應由網路或身分層限制。
- 對登入、Webhook、AI、Upload、Reindex 設定速率與成本限制並監控 `429`／失敗率。

## Data handling

LINE event payload、對話、預約姓名與 LINE User ID 都可能是個資。正式營運前必須定義
用途、保存期限、刪除／匯出程序、備份中的刪除策略與事件存取稽核。除非必要，不得
把原始 payload 或明文識別碼寫入 Log。OpenAI 請求維持 `store=false`，使用者識別碼
只能以穩定 HMAC 後的 `safety_identifier` 傳送。

## Security review checklist

- 外部可指定資源的每個 read／write 是否包含 `tenant_id`？
- 預約異動是否只經 `BookingManager`，並保留冪等與唯一時段限制？
- Webhook 是否先對原始 bytes 驗章，再解析 JSON？
- 新設定是否同步更新 `.env.example`、Compose、Render 與文件？
- 是否新增失敗、跨租戶、重送、競爭與權限降級測試？
- 是否會把 Secret、PII、Prompt 或外部錯誤內容暴露給 Client／Log？

詳細現況與未關閉風險見 [專案稽核報告](docs/project-review.md)。
