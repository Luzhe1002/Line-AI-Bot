# Render 測試部署

Repository 根目錄的 `render.yaml` 會建立新加坡區域的免費 Web Service 與
PostgreSQL 17。Render 產生的 `DATABASE_URL` 會在啟動時轉換成 Spring JDBC
設定，密鑰則由 Render 產生，不會寫入 Git。

## 測試環境

- Web Service 與 PostgreSQL 都使用 Free plan。
- AI provider 預設為 `local`，LINE API 預設停用。
- 健康檢查使用 `/portal/`。
- `APP_SESSION_COOKIE_SECURE=true`。
- PostgreSQL 禁止外部網路連線，只允許 Render 私有網路。

## 正式營運前

免費資料庫建立 30 天後會到期且沒有備份。正式營運前必須升級資料庫、設定
`APP_PUBLIC_BASE_URL`、啟用正式 LINE/OpenAI 設定，並完成平台管理入口分離。
