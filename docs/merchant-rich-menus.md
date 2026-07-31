# 店家個人圖文選單操作手冊

## 目標與邊界

同一個 LINE 官方帳號同時服務顧客與店家人員。顧客沿用商家在 LINE Official
Account Manager 或 Messaging API 設定的預設圖文選單；本系統只為已綁定的
`merchant_staff` 設定個人圖文選單，不修改或取代顧客預設選單。

圖文選單是操作入口，不是授權來源。所有管理操作仍由後端 Session、
`tenant_id + staff_id`、ACTIVE 狀態及 OWNER／MANAGER／VIEWER 角色決定。

## 角色選單

| 角色 | 左上入口 | 其他入口 |
|---|---|---|
| OWNER | 完整商家工作台 | 今日預約、未來七天、顧客預約 |
| MANAGER | 預約管理月曆 | 今日預約、未來七天、顧客預約 |
| VIEWER | 預約管理月曆 | 今日預約、未來七天、顧客預約 |

VIEWER 即使看得到相同的預約入口，後端仍拒絕取消、封鎖與解除時段。

LINE 電腦版不顯示 Rich Menu。管理者可改用文字指令 `管理預約`；OWNER 可輸入
`管理後台` 取得完整工作台連結，或使用既有 API Key 登入 `/portal/`。

## 同步生命週期

1. 人員以 `綁定 <code>` 完成綁定。
2. 綁定交易同時 upsert 一筆 `merchant_rich_menu_sync` READY 工作。
3. 正式 LINE 模式的 Worker 取得租戶 Channel Access Token。
4. Worker 以 `tenant_id + role` 查找或建立 `merchant_rich_menus`。
5. 上傳 2500 × 1686、低於 1 MB 的 PNG。
6. 呼叫 LINE per-user rich menu API，把角色選單綁定到加密保存的 LINE user ID。
7. 同步成功後標記 SYNCED。

LINE 不允許替換已設定在 Rich Menu 上的圖片，因此不能在既有 ID 上重傳中文圖。
`V5__replace_staff_rich_menus_with_chinese_version.sql` 會清除舊 ID、增加同步 revision，
再由 Worker 以版本化名稱建立中文選單。已綁定人員會自動切換，不需要重新綁定
LINE；舊選單不再綁定任何人員。

角色或狀態更新會增加 revision 並重新標記 READY。進行中的舊工作只能更新相同
revision，因此不會覆蓋較新的期望狀態。

人員改成 DISABLED 時，工作改為 `desired_linked=false`。Worker 呼叫 LINE
解除個人選單後，使用者會回到該官方帳號的顧客預設選單。

## OWNER 完整工作台登入

1. OWNER 點個人圖文選單的「管理後台」。
2. LINE 送出 `action=merchant_portal` postback。
3. Webhook 簽章、事件去重及人員狀態檢查通過後，建立 purpose 為
   `PORTAL_LOGIN` 的十分鐘單次 Token。
4. Bot 回覆 `/portal/#token=...` 按鈕。
5. Portal 清除 fragment，再呼叫 `POST /portal/api/line-session`。
6. 後端消耗 Token、重新確認 ACTIVE OWNER、旋轉 Session ID，並建立八小時
   HttpOnly Session 與 CSRF Token。
7. 每次 Portal API 呼叫都重新查詢人員狀態與 OWNER 角色。

`PORTAL_LOGIN` 不能交換預約管理 Session；`BOOKING_MANAGE` 也不能交換完整
Portal Session。

## 設定

```dotenv
APP_LINE_API_ENABLED=true
APP_LINE_API_BASE_URL=https://api.line.me
APP_LINE_API_DATA_BASE_URL=https://api-data.line.me
APP_LINE_RICH_MENU_WORKER_DELAY_MS=2000
```

`APP_LINE_API_ENABLED=false` 時，綁定與期望狀態仍會寫入資料庫，但 Worker 不會
呼叫 LINE，狀態保留 READY。啟用真實 LINE API 後會繼續處理。

每個租戶必須先保存有效且啟用的 Channel Access Token。重新保存 LINE Channel
設定會把該租戶所有人員重新排入同步。

## 故障處理

同步失敗不會回滾店家人員綁定。Worker 會以 5、10、20、40 秒逐步增加等待，
最高五分鐘後持續重試。

可使用以下查詢檢查狀態，不要輸出加密的 LINE user ID 或 Channel Token：

```sql
select tenant_id, staff_id, desired_role, desired_linked,
       status, attempts, next_attempt_at, last_error, updated_at
from merchant_rich_menu_sync
order by updated_at desc;
```

常見狀態：

- `READY`：等待首次同步或設定更新。
- `PROCESSING`：Worker 已 Claim。
- `RETRY`：LINE API、Channel 設定或網路暫時不可用。
- `SYNCED`：LINE 已接受目前 revision 的連結或解除要求。

若 LINE Channel 被換成另一個 Channel，重新保存 Channel 設定會排程全租戶同步。
若舊 rich menu ID 在新 Channel 不存在，第一次連結會收到 404，系統會清除舊
ID 並於下一次重試建立新選單。

## 正式環境驗收

1. 確認顧客帳號只看到原本預設圖文選單。
2. 綁定 OWNER，確認數秒內切換為 OWNER 個人選單。
3. 點「管理後台」，確認連結只能登入一次，且網址 fragment 立即消失。
4. 綁定 MANAGER／VIEWER，確認左上入口顯示「預約管理」而不是完整工作台。
5. 以 VIEWER 嘗試取消或封鎖時段，確認後端拒絕。
6. 將人員設為 DISABLED，確認個人選單解除並恢復顧客預設選單。
7. 暫停 LINE API 或填入無效 Token，確認綁定仍成功且同步進入 RETRY。
8. 修正 Channel Token，確認工作最終轉為 SYNCED。
