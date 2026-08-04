# Render 展示部署

Repository 根目錄的 `render.yaml` 會建立新加坡區域的免費 Web Service 與
PostgreSQL 17。Render 產生的 `DATABASE_URL` 會在啟動時轉換成 Spring JDBC
設定，密鑰則由 Render 產生，不會寫入 Git。

## 目前設定

- Web Service 與 PostgreSQL 都使用 Free plan。
- `render.yaml` 設定 OpenAI Provider 與啟用 LINE API；實際金鑰只由 Render Secret 注入。
- 健康檢查使用真正驗證應用程式與資料庫的 `/health`。
- `APP_SESSION_COOKIE_SECURE=true`。
- `APP_API_DOCS_ENABLED=false`，正式展示站不公開 Swagger／OpenAPI。
- PostgreSQL 禁止外部網路連線，只允許 Render 私有網路。

這是履歷展示環境，不等同正式營運 SLA。平台方案、休眠與資料保留條款可能變更，
部署前應以 Render 當下方案頁為準，不在文件中假設固定期限。

## 正式營運前

正式營運前必須改用具有備份、Point-in-time Recovery 與可用性承諾的資料庫方案，
建立外部可用性監控，並依 [操作手冊](operations-runbook.md) 完成還原、金鑰輪替與
事故演練。若營運人員需要 Swagger／OpenAPI，應透過受保護的管理入口提供，不要直接
把 `APP_API_DOCS_ENABLED` 對公開正式站設為 `true`。
